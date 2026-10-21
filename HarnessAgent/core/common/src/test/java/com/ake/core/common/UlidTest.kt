package com.ake.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ULID 契约测试。
 *
 * 三条性质是数据库 §6 里「追加写」优化的全部依据：
 *  ① 时间序（前 10 字符单调递增）
 *  ② 26 字符 Crockford base32
 *  ③ 同一毫秒内不重复（单调性）
 *
 * 任何一条破掉，B-tree 插入就会退化成随机写 —— 在移动端 SQLite 上这是肉眼可见的写入劣化。
 */
class UlidTest {

    @Test
    fun `is 26 chars of crockford base32`() {
        repeat(200) {
            val u = Ulid.next(nowMillis = 1_700_000_000_000L + it)
            assertThat(u).hasLength(26)
            assertThat(u).matches("[0-9A-HJKMNP-TV-Z]{26}")
        }
    }

    @Test
    fun `encodes the millisecond timestamp in the first 10 chars`() {
        val t = 1_700_000_000_123L
        val u = Ulid.next(nowMillis = t)
        assertThat(Ulid.parseTimeMillis(u)).isEqualTo(t)
    }

    @Test
    fun `later timestamps sort after earlier ones`() {
        val a = Ulid.next(nowMillis = 1_000L)
        val b = Ulid.next(nowMillis = 2_000L)
        val c = Ulid.next(nowMillis = 3_000L)
        val sorted = listOf(c, a, b).sorted()
        assertThat(sorted).containsExactly(a, b, c).inOrder()
    }

    @Test
    fun `is monotonic within the same millisecond`() {
        val t = 1_700_000_000_999L
        val burst = (0 until 50).map { Ulid.next(nowMillis = t) }
        assertThat(burst.toSet()).hasSize(50)
        // 同毫秒内也要保持单调
        assertThat(burst).isInStrictOrder()
    }

    @Test
    fun `isValid rejects bad input`() {
        assertThat(Ulid.isValid("")).isFalse()
        assertThat(Ulid.isValid("short")).isFalse()
        // I / L / O / U 不在 Crockford 字母表内
        assertThat(Ulid.isValid("U".repeat(26))).isFalse()
        assertThat(Ulid.isValid(Ulid.next(nowMillis = 5L))).isTrue()
    }
}
