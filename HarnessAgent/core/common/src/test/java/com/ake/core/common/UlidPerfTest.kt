package com.ake.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * ULID 与红action 的性能/顺延性质。
 *
 * 为什么要测性能：ULID 被用作**主键**，写入热路径每步 10 余行 ⇒ 每次生成都算一次 SHA？
 * 不 —— 当前实现只做 base32 编码，没有哈希，这里的断言是为了防止将来有人「顺手」把它改成
 * 带哈希的版本，把写入路径拖慢一个数量级。
 */
class UlidPerfTest {

    @Test
    fun `generating 10k ids is fast`() {
        val ids = ArrayList<String>(10_000)
        val ns = measureNanoTime {
            for (i in 0 until 10_000) ids += Ulid.next(nowMillis = 1_700_000_000_000L + i / 5)
        }
        assertThat(ids).hasSize(10_000)
        assertThat(ids.toSet()).hasSize(10_000)
        // 10k 次生成应当在数百毫秒量级内完成（CI 机器差异很大，这里只做量级断言）
        assertThat(ns / 1_000_000L).isLessThan(5_000L)
    }
}
