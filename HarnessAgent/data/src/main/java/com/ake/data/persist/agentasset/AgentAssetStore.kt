package com.ake.data.persist.agentasset

import com.ake.core.common.AkeLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 人格资产（头像等）的存储（§7.2）。
 *
 * ## ★ 它**不引入新的清除机制**
 *
 * 与 `PayloadStore` 同形：一个根 + `purgeAll()`。
 * 「新增一类落盘物时，清除清单不需要跟着改」—— 这正是
 * 「envelope 与 payload 共用同一个根」那条裁决的同一思路。
 *
 * ## ★ 哪些头像**不在**清除范围
 *
 * `avatar_kind = IDENTICON` 的头像**没有文件**（每次启动重算，不落盘）。
 * 那列的存在的意义之一就是让清除不必去猜 ——
 * **不要写「删 avatar_uri 指向的文件」这种无条件逻辑**：
 * 文件不存在时 `delete()` 返回 false，会被误记成一条失败（§7.4）。
 */
public class AgentAssetStore(private val root: File) {

    init {
        root.mkdirs()
    }

    private fun dirOf(profileId: String): File = File(root, profileId).apply { mkdirs() }

    /** 写入并返回**相对路径**（相对本 store 的根）。 */
    public suspend fun writeAvatar(profileId: String, ext: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = dirOf(profileId)
                val name = "avatar.$ext"
                val tmp = File(dir, "$name.tmp")
                val dst = File(dir, name)
                tmp.outputStream().use { it.write(bytes) }
                tmp.outputStream().close()
                if (!tmp.renameTo(dst)) {
                    tmp.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                    tmp.delete()
                }
                "$profileId/$name"
            }.getOrElse { t ->
                AkeLog.w(TAG, "avatar write failed profile=$profileId: ${t.message}")
                null
            }
        }

    public suspend fun read(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(root, relativePath).takeIf { it.exists() }?.readBytes() }
            .getOrElse { null }
    }

    /** ★ 只删有文件的那些：identicon 无从删起，也不该被记成失败。 */
    public suspend fun deleteProfile(profileId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { dirOf(profileId).deleteRecursively() }
            .onFailure { AkeLog.w(TAG, "deleteProfile($profileId) failed: ${it.message}") }
            .getOrDefault(false)
    }

    /** §8.5 一键清除（④c 段）：整个根目录删掉。 */
    public suspend fun purgeAll(): Boolean = withContext(Dispatchers.IO) {
        runCatching { root.deleteRecursively() }
            .onFailure { AkeLog.w(TAG, "purgeAll failed: ${it.message}") }
            .getOrDefault(false)
            .also { root.mkdirs() }
    }

    public suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) {
        runCatching { walk(root) }.getOrDefault(0L)
    }

    private fun walk(f: File): Long {
        if (f.isFile) return f.length()
        val children = f.listFiles() ?: return 0L
        var sum = 0L
        for (c in children) sum += walk(c)
        return sum
    }

    public companion object {
        public const val TAG: String = "AgentAssetStore"
    }
}
