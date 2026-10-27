package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.AkeLog
import com.ake.core.common.Ulid
import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState
import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.entity.ModelAssetEntity
import com.ake.data.persist.db.mapper.EntityMappers
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 需要 `:llm` 代执行的文件动作 —— `:data` 不持有 `File`，只描述意图。 */
public sealed interface ModelFileOp {
    /** 改名。**绝不出现在这里：删除一个已装模型**（硬规则 1）。 */
    public data class Rename(val fromName: String, val toName: String) : ModelFileOp

    /** 丢弃一个残缺的 `.part`。它不是「可用模型」，丢掉不违反硬规则 1。 */
    public data class Discard(val name: String) : ModelFileOp
}

public data class ModelLedgerHandle(
    val assetId: String,
    /** 续传起点。跨 App 重启就靠它 —— A2「中断 10 次仍能下完」的前提。 */
    val resumeFromBytes: Long,
    /** 目标版本已经在盘上且是 READY ⇒ 调用方无需再下载。 */
    val alreadyReady: Boolean,
    /** 必须在开始下载**之前**执行完（顺序错了会覆盖掉旧模型文件）。 */
    val pendingFileOps: List<ModelFileOp>,
)

public data class VerifyOutcome(
    val verdict: VerifyVerdict,
    val action: VerifyAction,
    /** 含本次的尝试次数 */
    val attemptsDone: Int,
    val pendingFileOps: List<ModelFileOp>,
)

/**
 * `model_asset` 表上的下载/校验状态机（与 manifest 条目对齐）。
 *
 * ## 它在整条链路里的位置
 *
 *  `manifest ──► ModelResolver（选目标 / 判回滚）
 *                    │
 *                    ▼
 *              ModelAssetLedger（落 Room：进度 / 校验 / 失败）
 *                    │  产出 ModelFileOp（改名 / 丢弃 part）
 *                    ▼
 *              :llm ModelDownloader（真正的文件 IO）
 *
 * ## 为什么状态必须落 Room 而不是内存
 *
 * A2 的验收项是「中断 10 次后仍能完成下载」，且中断包含**杀进程**。
 * `bytes_downloaded` 只在内存里 ⇒ 每次冷启动都从 0 开始 ⇒ 2.6 GB 在移动网络上
 * 实际永远下不完。这是这张表存在的唯一理由（数据库 §12.3）。
 *
 * ## 「绝不删除已装模型」在这里的实现方式
 *
 * 目标版本变化时，旧文件被**改名归档**为独立一行（variant = `<variant>@<sha12>`，
 * 避开 `(model_id, variant)` 唯一索引），而不是被覆盖或删除：
 *  - 归档行保持 `READY`，是回滚时的候选；
 *  - 槽位行重置为 `DOWNLOADING`，`localPath = null`（此时可用的模型是归档行那份）；
 *  - 新版本校验通过后槽位行才转正为 `READY`。
 *
 * ★ 副作用：归档行会随时间累积（每次模型更新 +1 行 / +2.6 GB）。
 *   **「可用模型」一个都不会被自动删** —— 那会直接违反硬规则 1。
 *   归档本体的删除入口必须由用户在设置里显式触发（`:ui` → `ModelLifecyclePort.deleteModelFile`）；
 *   只有**已经没有任何价值的 `.part`** 才由自动的那轮扫描回收，见下面一节。
 *
 * ## ★ 归档保留扫描的自触发钩子（[retentionSweep]）
 *
 * `retentionPlan()` 曾经一个生产调用方都没有 —— 这正是上面那条副作用一直没人收拾的原因。
 * 这里按既有先例补上，**不发明第三种方式**：
 *
 * ```
 *   TaskStateWriter.init   { writeScope.launch { reconciler?.invoke(); drainLoop() } }   ← `:data`
 *   ColdStartCoordinator.init { scope.launch { run() } }                                  ← `:app`
 *   ModelAssetLedger.init  { writeScope.launch { retentionSweep?.invoke() } }             ← 本类
 * ```
 *
 * ⇒ 只要这个单例被装配，一轮保留扫描就一定发生过，不需要任何人记得调用它。
 * （`:app` 不需要为此改一行：它本来就会装配 `ModelAssetLedger`。）
 */
public class ModelAssetLedger(
    private val dao: ModelAssetDao,
    private val clock: Clock,
    private val maxVerifyAttempts: Int = DEFAULT_MAX_VERIFY_ATTEMPTS,
    /**
     * `:data` 的单线程 writer scope。**只在装配期注入**，测试不带 ⇒ 不触发任何协程。
     */
    private val writeScope: CoroutineScope? = null,
    /**
     * 归档保留扫描钩子。**它为 null 时本类就是一个纯状态机**（既有单测的形态）。
     */
    private val retentionSweep: (suspend () -> Unit)? = null,
) {

    init {
        // ① 先跑一轮归档保留扫描，账本才开始接新写入。
        //    顺序上不必挡住谁：它跑在同一个单线程 writer 上，且每个动作自带 CAS 守卫。
        val scope = writeScope
        val sweep = retentionSweep
        if (scope != null && sweep != null) {
            scope.launch {
                runCatching { sweep.invoke() }
                    .onFailure { t -> AkeLog.w(TAG, "archive retention sweep failed: ${t.message}") }
            }
        }
    }

    /**
     * 开始 / 续传一次下载。
     *
     * @return 句柄里的 [ModelLedgerHandle.pendingFileOps] **必须在启动 HTTP 之前执行完**：
     *   `ModelDownloader` 下载完成后会把 `.part` 改名成 `$id.litertlm`，
     *   若不先把旧模型改名归档，这一次改名就把旧模型覆盖掉了 —— 那正是硬规则 1 要防的事。
     */
    public suspend fun beginOrResume(entry: ModelManifestEntry): ModelLedgerHandle {
        val now = clock.nowMillis()
        val existing = dao.byVariant(entry.modelId, entry.variant)

        // ── ① 同一个目标：续传（保留已落盘字节，不从头来）
        if (existing != null && existing.sha256.equals(entry.sha256, ignoreCase = true)) {
            if (existing.state == ModelAssetState.READY && !existing.localPath.isNullOrBlank()) {
                return ModelLedgerHandle(existing.id, existing.bytesDownloaded, true, emptyList())
            }
            dao.updateProgress(existing.id, existing.bytesDownloaded, ModelAssetState.DOWNLOADING)
            return ModelLedgerHandle(existing.id, existing.bytesDownloaded, false, emptyList())
        }

        // ── ② 目标版本变了：旧文件归档（不删），槽位重置，从 0 开始
        val ops = ArrayList<ModelFileOp>(2)
        if (existing != null && existing.state == ModelAssetState.READY && !existing.localPath.isNullOrBlank()) {
            val oldPath = existing.localPath!!
            val archiveName = ModelFileLayout.archiveNameOf(entry.modelId, entry.variant, existing.sha256)
            if (archiveName != oldPath) ops += ModelFileOp.Rename(oldPath, archiveName)
            dao.upsert(
                existing.copy(
                    id = Ulid.next(now),
                    variant = ModelFileLayout.archiveVariantOf(existing.variant, existing.sha256),
                    state = ModelAssetState.READY,
                    localPath = archiveName,
                    tempFile = null,
                    bytesDownloaded = existing.expectedBytes,
                    downloadFinishedAt = existing.downloadFinishedAt ?: now,
                ),
            )
        }
        if (existing?.tempFile != null) ops += ModelFileOp.Discard(existing.tempFile!!)

        val id = existing?.id ?: Ulid.next(now)
        dao.upsert(
            newEntity(
                id = id,
                entry = entry,
                state = ModelAssetState.DOWNLOADING,
                localPath = null,
                tempFile = ModelFileLayout.partNameOf(id),
                bytes = 0L,
                now = now,
            ),
        )
        return ModelLedgerHandle(id, 0L, false, ops)
    }

    /** 进度写库。**低频高频之间取 500ms 节流的调用方负责**（`ModelDownloader` 已这么做）。 */
    public suspend fun progress(assetId: String, bytes: Long): Unit =
        dao.updateProgress(assetId, bytes, ModelAssetState.DOWNLOADING)

    public suspend fun markVerifying(assetId: String): Unit =
        dao.updateProgress(assetId, dao.byId(assetId)?.bytesDownloaded ?: 0L, ModelAssetState.VERIFYING)

    /**
     * 校验收尾：写状态、记摘要、决定下一步。
     *
     * ⚠ **不负责改名转正**：`ModelDownloader` 内部已经把 `.part` 原子改名成
     * `$id.litertlm` 后才返回，这里再发一次 `Rename` 会操作一个已不存在的文件。
     * 契约是：调用方保证 `PROMOTE` 时文件已在 [ModelFileLayout.finalNameOf] 指定的名字上。
     */
    public suspend fun finish(
        assetId: String,
        entry: ModelManifestEntry,
        actualBytes: Long?,
        actualSha256: String?,
    ): VerifyOutcome {
        val row = dao.byId(assetId)
            ?: return VerifyOutcome(VerifyVerdict.FILE_MISSING, VerifyAction.GIVE_UP, 0, emptyList())

        val verdict = ModelVerifyGate.evaluate(entry, actualBytes, actualSha256)
        val attempts = row.verifyAttempts + 1
        val action = ModelVerifyGate.nextAction(verdict, attempts, maxVerifyAttempts)
        val now = clock.nowMillis()
        val ops = ArrayList<ModelFileOp>(1)

        when (action) {
            VerifyAction.PROMOTE -> {
                dao.finishVerify(
                    id = assetId,
                    state = ModelAssetState.READY,
                    localPath = ModelFileLayout.finalNameOf(assetId),
                    tempFile = null,
                    at = now,
                    actualBytes = actualBytes,
                    actualSha = actualSha256,
                    result = verdict.name,
                )
            }

            VerifyAction.DISCARD_AND_RESTART -> {
                if (row.tempFile != null) ops += ModelFileOp.Discard(row.tempFile!!)
                dao.finishVerify(
                    id = assetId,
                    state = ModelAssetState.DOWNLOADING,
                    // ★ 保留旧归档路径：下载重试期间 App 仍有可用模型
                    localPath = row.localPath,
                    tempFile = ModelFileLayout.partNameOf(assetId),
                    at = now,
                    actualBytes = actualBytes,
                    actualSha = actualSha256,
                    result = verdict.name,
                )
                dao.updateProgress(assetId, 0L, ModelAssetState.DOWNLOADING)
                dao.setError(assetId, reasonKeyOf(verdict), now)
            }

            VerifyAction.GIVE_UP -> {
                if (row.tempFile != null) ops += ModelFileOp.Discard(row.tempFile!!)
                dao.finishVerify(
                    id = assetId,
                    state = ModelAssetState.CORRUPTED,
                    localPath = row.localPath,
                    tempFile = null,
                    at = now,
                    actualBytes = actualBytes,
                    actualSha = actualSha256,
                    result = verdict.name,
                )
                dao.setError(assetId, reasonKeyOf(verdict), now)
            }
        }
        return VerifyOutcome(verdict, action, attempts, ops)
    }

    /** 传输层失败（非校验失败）：保留已下字节，等下一次续传。 */
    public suspend fun fail(assetId: String, error: AppError): Unit {
        val row = dao.byId(assetId) ?: return
        dao.updateProgress(assetId, row.bytesDownloaded, ModelAssetState.PAUSED)
        dao.setError(assetId, error.reasonKey, clock.nowMillis())
    }

    /** 回滚候选集：所有 READY 行（含归档行）。 */
    public suspend fun installed(): List<ModelAssetRecord> =
        dao.allOnce().map(EntityMappers::modelAssetToDomain)

    // ────────────────────────────────────────────────────────────────────────
    // 删除路径：按 modelId 列出**这个模型拥有的全部文件名**（交接项 B2）
    //
    // `:llm` 的 `ModelLifecyclePort.deleteModelFile(modelId)` 只拿到一个 modelId，
    // 而它自己的 `ModelDownloader.delete(id)` 删的是 `<id>.litertlm` —— 两者只在
    // 「assetId 恰好等于 modelId」的旧数据上重合。账本才是唯一知道
    // 「这个 modelId 在盘上到底留了几个文件」的地方，所以名单由这里出。
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 某个 modelId 在盘上可能拥有的**全部文件名**（去重，顺序稳定）。
     *
     * 覆盖三类名字：
     *  1. `<modelId>.litertlm` / `<modelId>.litertlm.part` —— 旧数据（assetId 就是 modelId）；
     *  2. `<assetId>.litertlm` / `<assetId>.litertlm.part` —— 槽位行的正名与半成品；
     *  3. 各行 `local_path` / `temp_file` 实际记录的名字 —— 归档件的
     *     `<modelId>_<variant>_<sha16>.litertlm` 就在这两列里。
     *
     * 只返回**名字**不带目录：`:data` 不持有 `File`，真实路径由 `:llm` 拼在 `modelsDir` 下
     * （与 `ModelFileOp` 同样的分工）。
     */
    public suspend fun ownedFileNames(modelId: String): List<String> {
        val names = LinkedHashSet<String>()
        // ① 旧命名：`ModelDownloader.delete(modelId)` 认的就是这两个
        names += ModelFileLayout.finalNameOf(modelId)
        names += ModelFileLayout.partNameOf(modelId)
        // ② ③ 账本知道的每一个名字
        dao.byModelId(modelId).forEach { row ->
            names += ModelFileLayout.finalNameOf(row.id)
            names += ModelFileLayout.partNameOf(row.id)
            row.localPath?.let { names += File(it).name }
            row.tempFile?.let { names += File(it).name }
        }
        return names.toList()
    }

    /**
     * 某个 modelId 的归档清理裁决（策略见 [ModelArchiveRetention]）。
     *
     * ★ 想知道某份账本行长什么样就直接调用本方法；真正动手清理的是
     *   [ArchiveRetentionSweep]，两者共用同一个算法入口
     *   （[ModelArchiveRetention.planOf]），不存在两份名单。
     */
    public suspend fun retentionPlan(
        modelId: String,
        graceMillis: Long = 0L,
        nowMillis: Long = Long.MAX_VALUE,
    ): ModelRetentionPlan = ModelArchiveRetention.planOf(
        entities = dao.byModelId(modelId),
        graceMillis = graceMillis,
        nowMillis = nowMillis,
    )

    private fun newEntity(
        id: String,
        entry: ModelManifestEntry,
        state: String,
        localPath: String?,
        tempFile: String?,
        bytes: Long,
        now: Long,
    ): ModelAssetEntity = ModelAssetEntity(
        id = id,
        modelId = entry.modelId,
        variant = entry.variant,
        remoteUrl = entry.url,
        localPath = localPath,
        expectedBytes = entry.sizeBytes,
        sha256 = entry.sha256,
        contextWindow = entry.contextWindow,
        minRamGb = entry.minRamGb,
        capabilitiesJson = entry.capabilities.joinToString(","),
        state = state,
        bytesDownloaded = bytes,
        downloadStartedAt = now,
        downloadFinishedAt = null,
        tempFile = tempFile,
        verifyAttempts = 0,
        verifyLastResult = null,
        verifiedAt = null,
        actualBytes = null,
        actualSha256 = null,
        lastError = null,
        lastErrorAt = null,
        lastUsedAt = null,
        loadCount = 0,
        lastBackend = null,
        lastLoadMs = null,
    )

    private fun reasonKeyOf(verdict: VerifyVerdict): String = when (verdict) {
        VerifyVerdict.MATCH -> "err_none"
        VerifyVerdict.SIZE_MISMATCH -> "err_model_size_mismatch"
        VerifyVerdict.HASH_MISMATCH -> "err_model_checksum_mismatch"
        VerifyVerdict.FILE_MISSING -> "err_model_file_missing"
    }

    public companion object {
        public const val DEFAULT_MAX_VERIFY_ATTEMPTS: Int = 3

        public const val TAG: String = "ModelAssetLedger"
    }
}
