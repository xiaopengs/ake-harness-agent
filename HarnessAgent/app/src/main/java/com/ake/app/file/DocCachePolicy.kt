package com.ake.app.file

import java.security.MessageDigest

/**
 * 文档缓存策略（纯逻辑，无 Android 依赖 ⇒ 可在 JVM 上直接测）。
 *
 * 拆出来的理由：真正难测的不是 `copy()`，而是"什么时候该留、什么时候该删"。
 * 把判定抽成纯函数后，LRU 的行为可以用 4 条断言锁死，而 `AndroidFileAccess`
 * 只剩 IO 编排 —— 后者由真机验证（交付方案 M1 出口条件）。
 */
internal object DocCachePolicy {

    /** ≤20MB 的小文件留在缓存里复用；大文件任务结束即删，避免 512MB 预算被单份财报吃掉。 */
    const val RETAIN_LIMIT_BYTES: Long = 20L * 1024 * 1024

    /** 缓存总上限。超过就按最后访问时间从旧到新删。 */
    const val CACHE_LIMIT_BYTES: Long = 512L * 1024 * 1024

    /** 单文件硬上限：超过直接拒，不尝试解析。200MB 的 PDF 在端侧没有可用性。 */
    const val MAX_FILE_BYTES: Long = 200L * 1024 * 1024

    fun retainAfterTask(bytes: Long): Boolean = bytes <= RETAIN_LIMIT_BYTES

    /** uri 的稳定短键。用 sha256 而不是 hashCode：后者在进程间不稳定，重启后缓存全失效。 */
    fun keyOf(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算需要淘汰的 key（按 lastAccessAt 升序，直到剩余量 ≤ [limitBytes]）。
     *
     * 不返回"保留哪些"，而是"删哪些"：调用方拿到后逐个删文件即可，
     * 不需要理解预算算法。空列表 = 不需要淘汰。
     */
    fun evict(entries: List<CacheEntry>, limitBytes: Long = CACHE_LIMIT_BYTES): List<String> {
        val total = entries.sumOf { it.bytes }
        if (total <= limitBytes) return emptyList()

        var remaining = total
        val doomed = mutableListOf<String>()
        for (entry in entries.sortedBy { it.lastAccessAt }) {
            if (remaining <= limitBytes) break
            doomed += entry.key
            remaining -= entry.bytes
        }
        return doomed
    }
}

internal data class CacheEntry(
    val key: String,
    val bytes: Long,
    val lastAccessAt: Long,
)
