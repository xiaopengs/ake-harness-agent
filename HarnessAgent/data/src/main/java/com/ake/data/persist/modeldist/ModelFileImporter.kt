package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
import com.ake.core.common.AkeLog
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # 本地模型的人工导入（存储与校验）
 *
 * ## 它解决的是「首次启动 + 无网络 ⇒ 用户永远拿不到模型」
 *
 * 2.6 GB 的模型本体不随 APK 走，所以「内置一份兜底 manifest」也救不了首次启动 ——
 * 那份 manifest 里的 URL 照样下不来。真出路是**让用户自己把模型文件送进来**
 * （SD 卡 / USB 传 / 从别处拷）。这条路本类负责它的后半段：
 * 收下文件、验明正身、放到正确的位置上。**UI（文件选择器）归 `:ui`，本类不碰。**
 *
 * ## ★★ 落点为什么是「归档名」—— 这是本设计的全部要点
 *
 * 判据是既定的那条：** `:llm` 的加载路径需不需要改一行**。顺着它走一遍，答案只有一个：
 *
 * ```
 *   ModelResolver.resolve(...)                  `:data`
 *        └─ ModelFileInventory.probe(entry)     `:llm` 的 ModelDirInventory
 *             └─ File(modelsDir, ModelFileLayout.archiveNameOf(id, variant, sha256))
 *                  ├─ 命中且字节数相符 ⇒ ModelResolution.UseLocal
 *                  └─ ModelDistributionSync.useLocal() 拷贝 → 账本 PROMOTE
 *                       └─ ModelAssetRecord(READY) → InferenceTarget / ModelSession
 * ```
 *
 * `probe()` 在盘上认人的方式**只有一种**：`<modelId>_<variant>_<sha256 前 16 位>.litertlm`，
 * 且字节数必须等于 `entry.sizeBytes`。所以本类把它落成**这个名字**，
 * 后面 `UseLocal → PROMOTE → ModelSession` 一行都不用改，也不会发出一个字节的网络请求。
 *
 * ⇒ 换句话说：**导入 = 提前把「盘上已经有了」这件事做成真的**。
 *   它不是一条新的安装路径，它是既有的「回滚：零下载切换」那条路径的入口。
 *
 * ## 接受什么格式：**单个 `.litertlm` 文件**，没有第二种
 *
 * 现有下载逻辑（`ModelDownloader`）产出的就是 `$id.litertlm`，`ModelSession` 加载的也是
 * 一个单文件路径（`ModelLoadConfig.path`）。工程里不存在 task bundle / 分片这种布局，
 * 所以本类也不接受 —— 一旦接受某种 bundle，就得有人负责拆包，而那个「谁」今天不存在。
 *
 * ## 四个失败场景各自怎么收场（每条都有测试）
 *
 *  | 场景                       | 判定                                        | 结果 |
 *  |----------------------------|---------------------------------------------|------|
 *  | 空间不足                   | **动手之前**先预检，不够就一个字节都不写     | Err(NO_SPACE)，无脏数据 |
 *  | 长度不符（截断 / 损坏）      | 源长度 ≠ `entry.sizeBytes` ⇒ SIZE_MISMATCH   | Err，无脏数据 |
 *  | 摘要不符（静默损坏）        | 流式 SHA-256 ≠ `entry.sha256`               | 删掉 `.part`，Err(CHECKSUM_MISMATCH) |
 *  | 读失败（不是文件 / 中断）    | IOException                                 | 删掉 `.part`，Err(IO_FAILED) |
 *
 * ### ★ 「半成品永远不可见」在这里同样生效
 *
 * 先写 `<name>.litertlm.part`，全部验过才 rename 成正名 —— 与 `ModelDownloader` 同源。
 * 否则一次中断会在盘上留下一个被 `probe()` 认成「已经有了」的残片，
 * 结果不是报错，而是引擎加载一个残缺的 2.6 GB 时在 native 层炸 —— 最难归因的那类失败。
 *
 * ### ★ 它不写任何账本行
 *
 * 「这一版已在盘上」由**下一次 `ModelResolver.resolve()` 自己发现**（落到 `UseLocal`），
 * 账本的状态机、资产冻结、`PROMOTE` 全部照旧。若本类在导入时就插一行 READY，
 * 盘上就同时存在「账本说它是好的」与「文件还没被谁真正验证过」两份真相 ——
 * 多一份真相的地方，迟早长出一致性事故。
 */
public class ModelFileImporter(
    /** 必须与 `:llm` 的 `ModelDownloader.modelsDir` 是同一个目录（见 `PersistenceModule.DIR_MODELS`）。 */
    private val modelsDir: File,
    /**
     * 可用字节数。**做成可注入的唯一理由是可测性**：JVM 单测里没法真的把盘塞满，
     * 而「写到一半盘满了」恰恰是这里最该被验证、又最难造的场景。
     */
    private val freeBytes: () -> Long = { modelsDir.usableSpace },
) {

    /** 一次导入的结果。UI 可以直接把它显示给用户。 */
    public data class Receipt(
        /** 落点文件名。**等于** `ModelFileLayout.archiveNameOf(entry)` —— `probe()` 认的就是它。 */
        val fileName: String,
        /** 绝对路径。`ModelLoadConfig.path` 要的就是这种绝对路径。 */
        val absolutePath: String,
        val sizeBytes: Long,
        /** 实际算出的摘要（小写 hex）。合法导入时必然等于 `entry.sha256`。 */
        val sha256: String,
        val modelId: String,
        val variant: String,
        /** 本次是真的拷了一份（true），还是盘上本来就有、直接跳过（false）。 */
        val copied: Boolean,
    )

    /**
     * 某个目标条目的落点。**公开出来**是让 UI 能在让用户等 2.6 GB 之前，
     * 先回答一句「会放在这里 / 这份已经有了」。
     */
    public fun destinationOf(entry: ModelManifestEntry): File =
        File(modelsDir, ModelFileLayout.archiveNameOf(entry.modelId, entry.variant, entry.sha256))

    /** 这份模型是不是已经在正确的落点上了（已经验证过字节数，未重算摘要）。 */
    public fun isPresent(entry: ModelManifestEntry): Boolean =
        destinationOf(entry).let { it.isFile && it.length() == entry.sizeBytes }

    /**
     * 导入一个本地文件。
     *
     * @param source 用户选中的源文件。**不必**在 `modelsDir` 里（通常是 Downloads / SD 卡）。
     */
    public suspend fun import(entry: ModelManifestEntry, source: File): Outcome<Receipt> =
        withContext(Dispatchers.IO) {
            if (!source.isFile) return@withContext Outcome.Err(sourceMissing())

            // ★ 长度先验。这是「截断的／损坏的源文件」最便宜的判据 ——
            //   不必先把几个 GB 拷过去才发现它是残的。
            if (source.length() != entry.sizeBytes) {
                AkeLog.w(TAG, "源长度 ${source.length()} ≠ 期望 ${entry.sizeBytes} ⇒ 拒绝")
                return@withContext Outcome.Err(verdictError(VerifyVerdict.SIZE_MISMATCH, null))
            }
            importStream(entry, opener = { FileInputStream(source) })
        }

    /**
     * 从一个流导入。`:ui` 走 SAF 拿到 `content://` 时手上只有 `InputStream`，没有 `File`。
     *
     * @param opener 每次调用都应当返回一个新的、位于 0 的流；本实现只会调用一次。
     */
    public suspend fun importStream(
        entry: ModelManifestEntry,
        opener: () -> InputStream,
    ): Outcome<Receipt> = withContext(Dispatchers.IO) {
        val target = destinationOf(entry)

        // ── ① 幂等短路：正确的一份已经在盘上 ⇒ 什么都不做。
        //    用户重选同一个文件时不该再付一次 2.6 GB 的拷贝。
        if (target.isFile && target.length() == entry.sizeBytes && sha256Of(target) == canonical(entry.sha256)) {
            AkeLog.i(TAG, "${target.name} 已在盘上且校验通过 ⇒ 跳过拷贝")
            return@withContext Outcome.Ok(receiptOf(entry, target, canonical(entry.sha256), copied = false))
        }

        // ── ② 空间预检：**任何写入之前**。
        //    按整份大小要求而不打折：拷完之后源与目标同时存在，占的就是两份。
        val needed = entry.sizeBytes
        if (freeBytes() < needed) {
            AkeLog.w(TAG, "空间不足：需要 $needed，可用 ${freeBytes()} ⇒ 不动手")
            return@withContext Outcome.Err(
                AppError(ModelDistErrorCodes.IMPORT_NO_SPACE, "err_model_import_no_space", null, Severity.HIGH),
            )
        }

        if (!ensureDir(modelsDir)) {
            return@withContext Outcome.Err(
                AppError(ModelDistErrorCodes.IMPORT_IO_FAILED, "err_model_import_io_failed", null, Severity.HIGH),
            )
        }
        val part = File(modelsDir, "${target.name}.part")

        // ── ③ 一边拷一边流式算摘要。2.6 GB 既不进内存，也不读第二遍。
        val md = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var overflow = false
        var thrown: Throwable? = null
        try {
            opener().use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        // ★ 已经比期望长了 ⇒ 立即停手，不要为一个必然失败的结果再拷几个 GB
                        if (copied + n > entry.sizeBytes) { overflow = true; break }
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        copied += n
                    }
                    out.flush()
                }
            }
        } catch (t: Throwable) {
            thrown = t
        }
        val actualSha = if (thrown == null) md.digest().hex() else null

        // ── ④ 用**既有的**校验闸裁决 —— 与下载成功后的那一遍是同一把尺子
        val verdict = ModelVerifyGate.evaluate(entry, actualBytes = actualSha?.let { copied }, actualSha256 = actualSha)
        if (thrown != null || overflow || verdict != VerifyVerdict.MATCH) {
            // ★ 任何失败都要把半成品收走：留着它，就是留一个会被 probe() 认错的未来。
            part.delete()
            if (thrown != null) {
                AkeLog.w(TAG, "导入中断：${thrown.message}")
                return@withContext Outcome.Err(
                    AppError(ModelDistErrorCodes.IMPORT_IO_FAILED, "err_model_import_io_failed", thrown, Severity.HIGH),
                )
            }
            if (overflow) {
                AkeLog.w(TAG, "源比期望更长（> ${entry.sizeBytes}）⇒ 拒绝")
                return@withContext Outcome.Err(verdictError(VerifyVerdict.SIZE_MISMATCH, null))
            }
            AkeLog.w(TAG, "导入校验失败：$verdict ⇒ 丢弃 ${part.name}")
            return@withContext Outcome.Err(verdictError(verdict, null))
        }

        // ── ⑤ 原子转正。rename 在同一文件系统上是原子的，过程中 probe() 只会看到 .part。
        if (!part.renameTo(target)) {
            part.delete()
            AkeLog.w(TAG, "转正失败：${part.name} → ${target.name}")
            return@withContext Outcome.Err(
                AppError(ModelDistErrorCodes.IMPORT_IO_FAILED, "err_model_import_io_failed", null, Severity.HIGH),
            )
        }

        Outcome.Ok(receiptOf(entry, target, actualSha!!, copied = true))
    }

    // ──────────────────────────────────────────────── 内部

    private fun ensureDir(dir: File): Boolean = dir.isDirectory || dir.mkdirs() || dir.isDirectory

    private fun receiptOf(entry: ModelManifestEntry, file: File, sha: String, copied: Boolean): Receipt = Receipt(
        fileName = file.name,
        absolutePath = file.absolutePath,
        sizeBytes = file.length(),
        sha256 = sha,
        modelId = entry.modelId,
        variant = entry.variant,
        copied = copied,
    )

    private fun canonical(sha256: String): String = sha256.lowercase()

    /** 流式 SHA-256（小写 hex）。文件不存在返回 `null`。 */
    private fun sha256Of(file: File): String? {
        if (!file.isFile) return null
        val md = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
        } catch (t: Throwable) {
            AkeLog.w(TAG, "sha256 计算失败：${t.message}")
            return null
        }
        return md.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { b -> "%02x".format(b) }

    private fun sourceMissing(): AppError = AppError(
        ModelDistErrorCodes.IMPORT_SOURCE_MISSING,
        "err_model_import_source_missing",
        null,
        Severity.MEDIUM,
    )

    private fun verdictError(verdict: VerifyVerdict, cause: Throwable?): AppError = when (verdict) {
        VerifyVerdict.MATCH -> AppError(AppError.CODE_UNKNOWN, "err_none", cause, Severity.LOW)
        VerifyVerdict.SIZE_MISMATCH -> AppError(
            ModelDistErrorCodes.IMPORT_SIZE_MISMATCH,
            "err_model_import_size_mismatch",
            cause,
            Severity.HIGH,
        )

        VerifyVerdict.HASH_MISMATCH -> AppError(
            ModelDistErrorCodes.IMPORT_CHECKSUM_MISMATCH,
            "err_model_import_checksum_mismatch",
            cause,
            Severity.HIGH,
        )

        VerifyVerdict.FILE_MISSING -> AppError(
            ModelDistErrorCodes.IMPORT_IO_FAILED,
            "err_model_import_io_failed",
            cause,
            Severity.HIGH,
        )
    }

    public companion object {
        public const val TAG: String = "ModelFileImport"

        private const val BUFFER_BYTES: Int = 1024 * 1024
    }
}
