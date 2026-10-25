package com.ake.data.persist.payload

import com.ake.core.common.AkeLog
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 外置快照存储（数据库 §5）。
 *
 * ## 唯一需要外置的对象
 *
 * 只有 `PocketDom` 序列化快照稳定超过 3 KB（目标 12 KB ≈ 2,800 token）。
 * 工具出参约 2 KB，但它**不参与 SQL 条件查询**，所以留在同一行的密文列里 ——
 * 压缩后 700 B，仍在单页内联线内（§5.1）。
 *
 * ## 写入顺序：`.tmp` → `force(true)` → `rename`
 *
 * 这三步不能省任何一步：
 *  - 写 `.tmp` 而不是直接写目标名 ⇒ 进程被杀时不会留下一个半截的 `.pdom.gz`；
 *  - `FileChannel.force(true)` ⇒ 数据真的到闪存了（否则掉电后文件内容可能是旧的）；
 *  - `renameTo` 在同一文件系统上是原子的 ⇒ 读者永远看不到半截文件。
 *
 * 而且 **行必须只在文件就位之后才落**（`TraceBufferWriter` 里的顺序），
 * 反过来的后果是「行指向不存在的文件」—— 回放 UI 会崩。
 */
public class PayloadStore(private val root: File) {

    init {
        root.mkdirs()
    }

    /** 目录：filesDir/trace/<taskId>/ */
    private fun dirOf(taskId: String): File = File(root, taskId).apply { mkdirs() }

    /**
     * 写入并返回**相对文件名字**。
     * @return null 表示写失败 —— 调用方据此把行标成 `payload_state='MISSING'` 继续落，
     *         不要因为快照写不进去就丢掉整条 observation 元数据。
     */
    suspend fun write(taskId: String, seq: Int, id: String, payload: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = dirOf(taskId)
                val name = "$seq-$id.pdom.gz"
                val tmp = File(dir, "$name.tmp")
                val dst = File(dir, name)
                tmp.outputStream().use { raw ->
                    DeflaterOutputStream(raw, Deflater(Deflater.DEFAULT_COMPRESSION), true).use { def ->
                        def.write(payload.toByteArray(Charsets.UTF_8))
                    }
                }
                forceAndRename(tmp, dst)
                name
            }.getOrElse { t ->
                AkeLog.w(TAG, "payload write failed task=$taskId seq=$seq: ${t.message}")
                null
            }
        }

    suspend fun read(taskId: String, fileName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(dirOf(taskId), fileName)
            if (!f.exists()) return@runCatching null
            f.inputStream().use { java.util.zip.GZIPInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }.getOrElse {
            AkeLog.w(TAG, "payload read failed: ${it.message}")
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // ★ v3：聊天外置 payload（`filesDir/chat/<sessionId>/<seq>-<id>.txt.gz`）
    //
    // 同一个类、不同根（§7.2）—— 这就是「新增一类落盘物时清除清单不需要跟着改」的收益。
    //
    // ★ 为什么单独开 gzip 两个方法而不是复用上面的 `write()`：
    //   上面那条用的是 `DeflaterOutputStream`（raw deflate），而 `read()` 用的是
    //   `GZIPInputStream` —— 两者并不配对。本轮不动既有那对（轨迹在用它，改了要连带改数据），
    //   聊天这边从第一天就用**真正配对的** GZIP 写/读（§4.2：`payload_codec = 'gzip'`）。
    // ────────────────────────────────────────────────────────────────────────

    /** 写一个 gzip 文本文件，返回**相对目录键的文件名**。 */
    suspend fun writeGzip(dirKey: String, fileName: String, text: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(root, dirKey).apply { mkdirs() }
                val tmp = File(dir, "$fileName.tmp")
                val dst = File(dir, fileName)
                tmp.outputStream().use { raw -> GZIPOutputStream(raw).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
                forceAndRename(tmp, dst)
                "$dirKey/$fileName"
            }.getOrElse { t ->
                AkeLog.w(TAG, "chat payload write failed $dirKey/$fileName: ${t.message}")
                null
            }
        }

    suspend fun readGzip(dirKey: String, fileName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(File(root, dirKey), fileName)
            if (!f.exists()) return@runCatching null
            f.inputStream().use { java.util.zip.GZIPInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { null }
    }

    /** §8.2 ④：删除会话时删它的整个目录。**事务外调用**（先没了 DB 指针，再删本体）。 */
    suspend fun purgeSession(sessionId: String) = purgeTask(sessionId)

    /** §8.5 一键清除 / §4.5 清理：整任务目录删掉。 */
    suspend fun purgeTask(taskId: String) = withContext(Dispatchers.IO) {
        runCatching { dirOf(taskId).deleteRecursively() }
            .onFailure { AkeLog.w(TAG, "purgeTask($taskId) failed: ${it.message}") }
    }

    /**
     * 孤儿文件 GC（§4.5 纪律 3：**双向核对**）。
     *
     * @param referenced 所有 DB 行仍在引用的文件名集合
     * @return 被删掉的文件数
     */
    suspend fun reconcile(taskId: String, referenced: Set<String>): Int = withContext(Dispatchers.IO) {
        val dir = dirOf(taskId)
        val files = dir.listFiles() ?: return@withContext 0
        var removed = 0
        for (f in files) {
            val isKnown = f.name in referenced || f.name.removeSuffix(".tmp") in referenced
            val isTmp = f.name.endsWith(".tmp")
            val isStaleTmp = isTmp && System.currentTimeMillis() - f.lastModified() > TMP_TTL_MS
            if (!isKnown || isStaleTmp) {
                if (f.delete()) removed++
            }
        }
        removed
    }

    /** §8.5 一键清除：整个 trace 根目录删掉。 */
    suspend fun purgeAll() = withContext(Dispatchers.IO) {
        runCatching { root.deleteRecursively() }
            .onFailure { AkeLog.w(TAG, "purgeAll failed: ${it.message}") }
        root.mkdirs()
    }

    suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) {
        runCatching { walk(root) }.getOrDefault(0L)
    }

    private fun walk(f: File): Long {
        if (f.isFile) return f.length()
        val children = f.listFiles() ?: return 0L
        var sum = 0L
        for (c in children) sum += walk(c)
        return sum
    }

    private fun forceAndRename(tmp: File, dst: File) {
        FileChannel.open(tmp.toPath(), StandardOpenOption.WRITE).use { ch ->
            ch.force(true)
        }
        if (!tmp.renameTo(dst)) {
            // 极少数文件系统 renameTo 会失败（跨设备）；退化为拷贝 + 删 tmp
            tmp.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            tmp.delete()
        }
    }

    companion object {
        const val TAG: String = "PayloadStore"

        /** `.tmp` 存活上限。超过这个时间的 tmp 一定是上次崩溃留下的。 */
        const val TMP_TTL_MS: Long = 6L * 60 * 60 * 1000
    }
}
