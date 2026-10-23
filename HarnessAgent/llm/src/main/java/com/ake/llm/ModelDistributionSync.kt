package com.ake.llm

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.modelapi.ModelErrorCodes
import com.ake.data.persist.modeldist.ModelAssetLedger
import com.ake.data.persist.modeldist.ModelManifest
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.ake.data.persist.modeldist.ModelResolution
import com.ake.data.persist.modeldist.ModelResolver
import com.ake.data.persist.modeldist.VerifyAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * manifest 决议 → 账本状态机 → 文件 IO 的编排者。**本轮「通电」的落点。**
 *
 * ## 一次模型更新在这里的完整时序（顺序即正确性）
 *
 * ```
 *   beginOrResume(entry)                      账本：目标版本变了 ⇒ 产出 Rename(旧 → 归档)
 *        │
 *        ▼
 *   ModelFileOpsExecutor.apply(pendingOps)    ★ 必须在开 HTTP **之前**完成
 *        │                                     （否则下载完成时的 part→final 转正会覆盖旧模型）
 *        ▼
 *   ModelDownloader.download(...)             真·网络 IO
 *        │
 *        ▼
 *   ledger.finish(id, entry, size, sha)       账本裁决：PROMOTE / DISCARD_AND_RESTART / GIVE_UP
 *        │
 *        ▼
 *   ModelFileOpsExecutor.apply(pendingOps)    丢 part（**只可能是 .part**，见执行器）
 * ```
 *
 * ### 关于第②步那条顺序（霍承钧 P1 第 1 条）
 *
 * `ModelDownloader` 下完后会把 `.part` 改名成 `$assetId.litertlm`。
 * 若旧模型还占着这个名字，这次改名就把**用户已装的可用模型**覆盖掉了 ——
 * 那正是硬规则 1「绝不因模型更新删除用户已装的可用模型」要防的事。
 * 所以 `pendingFileOps` 必须在 `beginOrResume` 返回后、任何网络动作之前执行完。
 * `ModelDistributionSyncTest` 对这条顺序有断言，不是只写在注释里。
 *
 * ## 关于重试上界（这里必须自己兜底）
 *
 * `ModelVerifyGate.nextAction()` 对 `FILE_MISSING` **恒返回** `DISCARD_AND_RESTART`
 * （它认为「没有文件」不构成一次校验失败）。而摘要不符时 `ModelDownloader` 已经把
 * `.part` 删了 ⇒ `finish` 一定判 `FILE_MISSING` ⇒ 不设上界就是**无限重下 2.6 GB**，
 * 直接烧穿用户流量。所以循环上界由本类把住（与账本的 `verifyAttempts` 上限同一数值）。
 *
 * ## 回滚（UseLocal）走的是拷贝而不是改名
 *
 * 归档件是「旧版本在盘上唯一的一份」。把它 rename 到槽位名会让账本里那条归档行
 * 指向一个不存在的文件 ⇒ 下次再回滚时 `probe` 找不到它 ⇒ 退化成重新下载 2.6 GB。
 * 所以这里**拷贝**：归档件原样保留，槽位得到自己的一份。
 * 代价是回滚一次多占 2.6 GB —— 与「归档只增不减」是同一个问题（见回传卡点①），
 * 需要产品侧给清理策略后才能收窄。
 */
@Singleton
public class ModelDistributionSync @Inject constructor(
    private val ledger: ModelLedgerGateway,
    private val downloader: ModelDownloader,
) {

    /**
     * 传输接缝。默认走 [ModelDownloader]（单次尝试 —— 重试策略归账本）。
     *
     * ⚠ **它存在的唯一理由是可测性**：JVM 单测里 `StatFs` 不可用、也没有网络，
     * 「下载 → 校验 → 账本裁决」这条链否则一行都测不到。
     * 生产代码不要替换它；要改传输行为请改 `ModelDownloader`。
     */
    internal var transfer: suspend (ModelDownloader.Request, (ModelDownloader.Progress) -> Unit) -> Outcome<File> =
        { req, onProgress -> downloader.download(req, onProgress, maxAttempts = ModelDownloader.DEFAULT_MAX_ATTEMPTS) }

    /** 执行轨迹（仅单测使用）：用于断言「Rename 发生在开 HTTP 之前」。 */
    internal var trace: ((String) -> Unit)? = null

    // ───────────────────────────────────────────────────── 决议

    /**
     * 「现在该用哪个模型」。判定规则全在 `ModelResolver`（`:data`），
     * 本类只负责补上它需要的两个本地事实：已装清单（账本）与盘上文件（[ModelDirInventory]）。
     */
    public suspend fun resolve(manifest: ModelManifest, appVersionCode: Int): ModelResolution =
        ModelResolver.resolve(
            manifest = manifest,
            installed = ledger.installed(),
            appVersionCode = appVersionCode,
            inventory = inventory(),
        )

    /** 按决议把目标版本准备好。返回值是**已经可以交给引擎加载**的文件。 */
    public suspend fun ensureResolved(
        manifest: ModelManifest,
        appVersionCode: Int,
        onProgress: (ModelDownloader.Progress) -> Unit = {},
    ): Outcome<File> = when (val r = resolve(manifest, appVersionCode)) {
        is ModelResolution.Current ->
            Outcome.Ok(fileOf(r.asset.localPath))

        is ModelResolution.UseLocal ->
            useLocal(r, onProgress)

        is ModelResolution.Download ->
            ensure(r.entry, onProgress)

        // 没有兼容条目但本地有可用模型 ⇒ 继续用旧的，只是不再更新。
        is ModelResolution.KeepInstalled ->
            Outcome.Ok(fileOf(r.asset.localPath))

        // 在用版本被服务端撤下 ⇒ **停止加载**，但文件留在盘上（2.6 GB 的重下代价不该用户承担）。
        is ModelResolution.InstalledRevoked ->
            Outcome.Err(AppError(ModelErrorCodes.LOAD_FAILED, "err_model_revoked", null, Severity.HIGH))

        is ModelResolution.Blocked ->
            Outcome.Err(AppError(ModelErrorCodes.LOAD_FAILED, r.reasonKey, null, Severity.HIGH))
    }

    // ───────────────────────────────────────────────────── 下载主链

    /**
     * 把一个 manifest 条目落到盘上（含续传、校验、账本裁决）。
     *
     * @param onProgress 非挂起回调（由 `ModelDownloader` 在 IO 线程回调）
     */
    public suspend fun ensure(
        entry: ModelManifestEntry,
        onProgress: (ModelDownloader.Progress) -> Unit = {},
    ): Outcome<File> {
        var restarts = 0

        while (true) {
            val handle = ledger.beginOrResume(entry)
            trace?.invoke("begin:${handle.assetId}:ready=${handle.alreadyReady}")

            // ★★ ① 硬规则 1 的落点：先归档旧模型，**再**开 HTTP。
            ModelFileOpsExecutor.apply(downloader.modelsDir, handle.pendingFileOps)
            handle.pendingFileOps.forEach { trace?.invoke("op:$it") }

            if (handle.alreadyReady) {
                onProgress(ModelDownloader.Progress.Done)
                return Outcome.Ok(downloader.localFileOf(handle.assetId))
            }

            // Request 由 manifest 条目直接映射；id 用账本主键，与 `ModelFileLayout.finalNameOf` 同源。
            val req = ModelDownloader.Request(
                id = handle.assetId,
                remoteUrl = entry.url,
                expectedBytes = entry.sizeBytes,
                sha256 = entry.sha256,
            )

            trace?.invoke("download:${handle.assetId}")
            val result = transfer(req, onProgress)

            val probe: Pair<Long?, String?> = when (result) {
                is Outcome.Ok -> result.value.length() to downloader.sha256Of(result.value)
                is Outcome.Err -> when (result.error.code) {
                    // 字节齐了但内容不对 ⇒ 交给账本裁决（part 已被下载器删除）
                    ModelErrorCodes.CHECKSUM_MISMATCH -> null to null
                    // 其余（网络 / 空间 / 中断）⇒ 保留已下字节等续传，**不**消耗校验次数
                    else -> {
                        ledger.fail(handle.assetId, result.error)
                        return Outcome.Err(result.error)
                    }
                }
            }

            // ★ ③ 下载完成后由账本裁决
            ledger.markVerifying(handle.assetId)
            val outcome = ledger.finish(handle.assetId, entry, probe.first, probe.second)
            ModelFileOpsExecutor.apply(downloader.modelsDir, outcome.pendingFileOps)
            outcome.pendingFileOps.forEach { trace?.invoke("op:$it") }
            trace?.invoke("finish:${outcome.verdict}/${outcome.action}")

            when (outcome.action) {
                VerifyAction.PROMOTE -> {
                    onProgress(ModelDownloader.Progress.Done)
                    return Outcome.Ok(downloader.localFileOf(handle.assetId))
                }

                VerifyAction.DISCARD_AND_RESTART -> {
                    if (++restarts >= MAX_RESTARTS) {
                        AkeLog.w(
                            TAG,
                            "校验连续 ${outcome.verdict} 已达上限 $MAX_RESTARTS ⇒ 停止重下（避免烧穿流量）",
                        )
                        return Outcome.Err(giveUp(entry))
                    }
                    AkeLog.w(TAG, "校验未过（${outcome.verdict}）⇒ 丢弃 part 重下（第 $restarts 次）")
                }

                VerifyAction.GIVE_UP -> {
                    AkeLog.w(TAG, "校验放弃：${outcome.verdict}（已尝试 ${outcome.attemptsDone} 次）")
                    return Outcome.Err(giveUp(entry))
                }
            }
        }
    }

    // ───────────────────────────────────────────────────── 回滚：零下载切换

    /**
     * 目标版本的文件盘上已经有了（下载过 / 被归档过）⇒ 校验后拷贝进槽位，**零下载**。
     *
     * 仍然走一遍 `beginOrResume`：它负责把「当前在用的那一版」归档成独立行 ——
     * 新版本万一校验不过，`previous` 那份（现在是归档行）就是唯一的退路。
     */
    private suspend fun useLocal(
        r: ModelResolution.UseLocal,
        onProgress: (ModelDownloader.Progress) -> Unit,
    ): Outcome<File> {
        val handle = ledger.beginOrResume(r.entry)
        ModelFileOpsExecutor.apply(downloader.modelsDir, handle.pendingFileOps)
        handle.pendingFileOps.forEach { trace?.invoke("op:$it") }

        if (handle.alreadyReady) {
            onProgress(ModelDownloader.Progress.Done)
            return Outcome.Ok(downloader.localFileOf(handle.assetId))
        }

        val source = fileOf(r.file.name)
        val target = downloader.localFileOf(handle.assetId)

        // 先算摘要再动手：拷贝 2.6 GB 之前就知道它是不是坏的
        val srcSha = downloader.sha256Of(source)
        if (srcSha == null || !srcSha.equals(r.entry.sha256, ignoreCase = true)) {
            AkeLog.w(TAG, "UseLocal：归档件 ${r.file.name} 摘要不符 ⇒ 退回下载路径")
            ledger.markVerifying(handle.assetId)
            val outcome = ledger.finish(handle.assetId, r.entry, source.length(), srcSha)
            ModelFileOpsExecutor.apply(downloader.modelsDir, outcome.pendingFileOps)
            return ensure(r.entry, onProgress)
        }

        val copied = copyInto(source, target)
        if (!copied) {
            // 半截文件绝不能留在槽位名上（会被当成可用模型）⇒ 删掉自己刚写的残片
            target.delete()
            val error = AppError(AppError.CODE_IO, "err_model_download_failed", null, Severity.HIGH)
            ledger.fail(handle.assetId, error)
            return Outcome.Err(error)
        }

        ledger.markVerifying(handle.assetId)
        val outcome = ledger.finish(handle.assetId, r.entry, target.length(), downloader.sha256Of(target))
        ModelFileOpsExecutor.apply(downloader.modelsDir, outcome.pendingFileOps)
        trace?.invoke("finish:${outcome.verdict}/${outcome.action}")

        return when (outcome.action) {
            VerifyAction.PROMOTE -> {
                onProgress(ModelDownloader.Progress.Done)
                Outcome.Ok(target)
            }
            // 拷贝件有问题 ⇒ 退回下载（账本已记一次失败尝试）
            VerifyAction.DISCARD_AND_RESTART, VerifyAction.GIVE_UP -> ensure(r.entry, onProgress)
        }
    }

    private suspend fun copyInto(source: File, target: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            true
        }.getOrElse { t ->
            AkeLog.w(TAG, "拷贝归档件失败：${t.message}")
            false
        }
    }

    // ───────────────────────────────────────────────────── 内部

    private fun inventory(): ModelDirInventory = ModelDirInventory(downloader.modelsDir)

    /**
     * 账本里的 `localPath` 记的是**文件名**（`ModelFileLayout.finalNameOf`），
     * 而别处有写成绝对路径的旧记录 —— 统一只取 basename 再拼目录，两种都安全。
     */
    private fun fileOf(localPath: String?): File =
        File(downloader.modelsDir, localPath?.let { File(it).name } ?: "")

    private fun giveUp(entry: ModelManifestEntry): AppError = AppError(
        code = ModelErrorCodes.CHECKSUM_MISMATCH,
        reasonKey = "err_model_verify_failed",
        cause = null,
        severity = Severity.HIGH,
    ).also { AkeLog.w(TAG, "giveUp: ${entry.modelId}/${entry.variant} r${entry.revision}") }

    private companion object {
        const val TAG: String = "ModelDistSync"
        const val COPY_BUFFER_SIZE: Int = 1024 * 1024

        /**
         * 与 `ModelAssetLedger.DEFAULT_MAX_VERIFY_ATTEMPTS` 同值。
         * 上界存在的理由见类注释「关于重试上界」。
         */
        val MAX_RESTARTS: Int = ModelAssetLedger.DEFAULT_MAX_VERIFY_ATTEMPTS
    }
}
