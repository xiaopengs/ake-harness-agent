package com.ake.llm

import android.os.StatFs
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.modelapi.ModelErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gemma-4-E2B-it 的下载管理（架构 §6.4）。
 *
 * **2.6 GB 不是一个可以「再试一次」的尺寸**：它在移动网络上一次成功下载的概率极低，
 * 所以断点续传不是锦上添花，是能不能交付的前提。
 *
 * 关键决策：
 *  - **不用 `DownloadManager`**：它只能写 `getExternalFilesDir()`，写不了 app 内部存储，
 *    而内部存储对我们的安全模型更合适（模型文件同样属于「不该被别的 App 顺手读到」的资产）。
 *    这里用 `HttpURLConnection` + HTTP Range 手写续传，代价约 100 行。
 *  - **原子落盘**：下到 `*.part`，完整校验后才 rename。**半成品永远不可见为可用模型** ——
 *    否则一次中断会在 UI 上留下一个「已安装但加载必崩」的模型。
 *  - **空间预检**：下载前要求 `size * 1.15` 的余量。模型本体 + 解压/缓存临时文件都可能额外占空间，
 *    撑到最后 1% 才发现写不下是最糟糕的用户体验。
 *  - **必校 SHA-256**：2.6 GB 的文件在网络上静默损坏的概率不算低，
 *    而损坏的模型表现为「随机输出乱码」而不是崩溃 —— 这类 bug 极难定位。
 */
public class ModelDownloader(
    /** 公开给 `ModelDistributionSync`：盘点目录与账本的 `ModelFileLayout` 名字同源。 */
    public val modelsDir: File,
) {

    public data class Request(
        val id: String,
        val remoteUrl: String,
        val expectedBytes: Long,
        val sha256: String,
    )

    public sealed interface Progress {
        public data class Running(val downloadedBytes: Long, val totalBytes: Long) : Progress
        public data class Verifying(val downloadedBytes: Long) : Progress
        public data object Done : Progress
    }

    /**
     * 断点续传下载。
     *
     * @param onProgress IO 线程回调；调用方可据此更新通知
     * @param maxAttempts 失败重试次数（指数退避）
     */
    public suspend fun download(
        req: Request,
        onProgress: (Progress) -> Unit,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Outcome<File> = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()

        val final = File(modelsDir, "${req.id}.litertlm")
        if (final.exists() && final.length() == req.expectedBytes && verify(final, req.sha256)) {
            onProgress(Progress.Done)
            return@withContext Outcome.Ok(final)
        }

        val part = File(modelsDir, "${req.id}.litertlm.part")

        // ── ① 空间预检
        val needed = (req.expectedBytes * SPACE_HEADROOM).toLong()
        if (usableBytes(modelsDir) < needed) {
            return@withContext Outcome.Err(
                AppError(ModelErrorCodes.DOWNLOAD_NO_SPACE, "err_model_no_space", severity = Severity.HIGH),
            )
        }

        var attempt = 0
        var lastError: Throwable? = null
        // ★ 摘要不符是一个**独立的失败类别**，不能混进 DOWNLOAD_FAILED：
        //   `ModelDistributionSync` 靠它决定「让 `ModelAssetLedger` 裁决」还是「保留字节等续传」。
        //   混在一起的话，一次静默损坏会被当成网络抖动 ⇒ 账本记 PAUSED ⇒ 故障被永久留下。
        var checksumMismatch = false

        while (attempt < maxAttempts) {
            if (!isActive) return@withContext Outcome.Err(cancelled())
            attempt++
            try {
                fetch(req, part, onProgress)
                if (!verify(part, req.sha256)) {
                    // 校验失败 = 文件已损坏 ⇒ 必须重头再来，不能续传接着错
                    part.delete()
                    lastError = IllegalStateException("sha256 mismatch")
                    checksumMismatch = true
                    // ★ 摘要不符**不在这里重试**：一次重试就是 2.6 GB。
                    //   「还要不要再下一次」归 `ModelAssetLedger` 裁决（它有重试预算与失败原因落库），
                    //   这里只负责把「摘要不符」如实报上去。
                    //   传输类错误（网络抖动）保留下面的 `maxAttempts` 退避重试 —— 它走 Range 续传，便宜。
                    break
                }
                if (part.renameTo(final)) Unit else {
                    lastError = IllegalStateException("rename failed")
                    continue
                }
                onProgress(Progress.Done)
                return@withContext Outcome.Ok(final)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxAttempts) delay(backoffMs(attempt))
            }
        }

        if (checksumMismatch) {
            Outcome.Err(
                AppError(ModelErrorCodes.CHECKSUM_MISMATCH, "err_model_checksum_mismatch", lastError, Severity.HIGH),
            )
        } else {
            Outcome.Err(
                AppError(ModelErrorCodes.DOWNLOAD_FAILED, "err_model_download_failed", lastError, Severity.HIGH),
            )
        }
    }

    /** 单次取最终下载段（带 Range 续传）。 */
    private fun fetch(req: Request, part: File, onProgress: (Progress) -> Unit) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing > req.expectedBytes) { part.delete(); existing = 0L }

        val conn = (URL(req.remoteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            conn.connect()
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_PARTIAL -> Unit // 续传成功
                code == HttpURLConnection.HTTP_OK -> { part.delete(); existing = 0L } // 服务端不支持 Range：重下
                code >= 400 -> error("HTTP $code for ${req.remoteUrl}")
                else -> Unit
            }
            val contentLen = conn.contentLengthLong
            val total = if (contentLen > 0) existing + contentLen else req.expectedBytes

            conn.inputStream.use { input ->
                FileOutputStream(part, existing > 0).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var written = existing
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > PROGRESS_EMIT_INTERVAL_MS) {
                            lastEmit = now
                            onProgress(Progress.Running(written, total))
                        }
                    }
                    out.flush()
                }
            }
            onProgress(Progress.Running(part.length(), req.expectedBytes))
        } finally {
            conn.disconnect()
        }
    }

    /** 流式 SHA-256 —— 2.6 GB 不能读进内存。 */
    private fun verify(file: File, expected: String): Boolean {
        val actual = sha256Of(file) ?: return false
        return actual.equals(expected, ignoreCase = true)
    }

    /**
     * 流式 SHA-256，返回小写 hex；文件不存在返回 `null`。
     *
     * 公开的必要：`ModelAssetLedger.finish()` 要的是**实际摘要**（不是「对不对」），
     * 它得把值写进 `actual_sha256` 才能事后回答「上次的模型为什么坏」。
     */
    public fun sha256Of(file: File): String? {
        if (!file.exists()) return null
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = raf.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun usableBytes(dir: File): Long = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Throwable) {
        0L
    }

    private fun backoffMs(attempt: Int): Long =
        (BASE_BACKOFF_MS * (1 shl (attempt - 1).coerceAtMost(5))).coerceAtMost(MAX_BACKOFF_MS)

    private fun cancelled() = AppError(ModelErrorCodes.DOWNLOAD_FAILED, "err_interrupted", null, Severity.LOW)

    public fun localFileOf(id: String): File = File(modelsDir, "$id.litertlm")

    public suspend fun delete(id: String): Outcome<Unit> = deleteFiles(
        listOf("$id.litertlm", "$id.litertlm.part"),
    )

    /**
     * 按**文件名**批量删（名字由 `ModelLedgerGateway.ownedFileNames` 出）。
     *
     * 只接受 basename（内部再取一次 `File(name).name`）：账本里 `local_path`/`temp_file`
     * 历史上有写成绝对路径的记录，直接拼会造成 `modelsDir//sdcard/...` 这种幽灵路径 ——
     * 不报错，只是永远删不掉，属于最难查的那类失败。
     */
    public suspend fun deleteFiles(names: List<String>): Outcome<Unit> = withContext(Dispatchers.IO) {
        names.forEach { name -> File(modelsDir, File(name).name).delete() }
        Outcome.Ok(Unit)
    }

    public companion object {
        private const val BUFFER_SIZE = 256 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val PROGRESS_EMIT_INTERVAL_MS = 500L
        const val SPACE_HEADROOM = 1.15

        /** 传输类错误的重试上限；摘要不符不走这里（见 `download` 内注释）。 */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 5
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
