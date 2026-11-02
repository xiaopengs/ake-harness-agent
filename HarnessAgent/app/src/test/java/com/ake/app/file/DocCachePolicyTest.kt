package com.ake.app.file

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 缓存策略的 JVM 测试。
 *
 * 这些断言对应交付方案 S02 的验收点：
 *  - 20MB 阈值决定"任务后是否保留"（大文件不能把 512MB 预算吃光）；
 *  - LRU 淘汰只按最后访问时间，不按文件大小（否则一份大财报会把所有小文件挤掉）；
 *  - 缓存键跨进程稳定（sha256，不是 `String.hashCode()`）。
 */
class DocCachePolicyTest {

    @Test
    fun `20MB 是保留阈值而不是拒绝阈值`() {
        assertThat(DocCachePolicy.retainAfterTask(20L * 1024 * 1024)).isTrue()
        assertThat(DocCachePolicy.retainAfterTask(20L * 1024 * 1024 + 1)).isFalse()
        assertThat(DocCachePolicy.retainAfterTask(1)).isTrue()
    }

    @Test
    fun `总量未超限时不淘汰任何文件`() {
        val entries = listOf(
            CacheEntry("a", 100, 1),
            CacheEntry("b", 100, 2),
        )

        assertThat(DocCachePolicy.evict(entries, limitBytes = 1024)).isEmpty()
    }

    @Test
    fun `超限时从最久未访问的开始淘汰直到达标`() {
        val entries = listOf(
            CacheEntry("newest", 400, lastAccessAt = 300),
            CacheEntry("oldest", 400, lastAccessAt = 100),
            CacheEntry("middle", 400, lastAccessAt = 200),
        )

        // 1200 → 上限 800：必须删掉 400 以上。按 LRU，先删 oldest。
        val doomed = DocCachePolicy.evict(entries, limitBytes = 800)

        assertThat(doomed).containsExactly("oldest")
    }

    @Test
    fun `单个超大文件也会被淘汰`() {
        val entries = listOf(
            CacheEntry("huge", 2_000, lastAccessAt = 999),
            CacheEntry("small", 10, lastAccessAt = 1),
        )

        val doomed = DocCachePolicy.evict(entries, limitBytes = 512)

        assertThat(doomed).contains("huge")
    }

    @Test
    fun `缓存键是稳定的 64 位十六进制且不同 uri 不碰撞`() {
        val a = DocCachePolicy.keyOf("content://docs/a.pdf")
        val b = DocCachePolicy.keyOf("content://docs/b.pdf")

        assertThat(a).hasLength(64)
        assertThat(a).matches("[0-9a-f]{64}")
        assertThat(a).isEqualTo(DocCachePolicy.keyOf("content://docs/a.pdf"))
        assertThat(a).isNotEqualTo(b)
    }
}
