package com.ake.chat.loop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * R4 坑 ②：Gemma 的 turn 格式 special token **会在 chunk 边界被切开**。
 *
 * 这些用例就是「按 chunk 做正则」为什么不够的证据 —— 每一个都对应真机上一次乱码。
 */
class DeltaScrubberTest {

    @Test
    fun wholeTokenInOneChunkIsRemoved() {
        val s = DeltaScrubber()
        assertThat(s.feed("你好<end_of_turn>")).isEqualTo("你好")
        assertThat(s.feed("，继续")).isEqualTo("，继续")
    }

    @Test
    fun tokenSplitAcrossChunksIsStillRemoved() {
        val s = DeltaScrubber()
        // chunk1 只是半个 token：必须被扣住，不能先投给 UI
        assertThat(s.feed("你好<|im_")).isEqualTo("你好")
        assertThat(s.feed("start|>世界")).isEqualTo("世界")
    }

    @Test
    fun splitOfTheClassicGemmaTurnMarker() {
        val s = DeltaScrubber()
        assertThat(s.feed("<start_of")).isEqualTo("")
        assertThat(s.feed("_turn>模型")).isEqualTo("模型")
    }

    @Test
    fun normalTextIsNeverDelayed() {
        val s = DeltaScrubber()
        assertThat(s.feed("一")).isEqualTo("一")
        assertThat(s.feed("二")).isEqualTo("二")
        assertThat(s.flush()).isEqualTo("")
    }

    @Test
    fun legitimateAngleBracketsAreKept() {
        val s = DeltaScrubber()
        // 白名单式匹配：<b> 是正文，不是 special token
        assertThat(s.feed("公式 a<b 且 c>d")).isEqualTo("公式 a<b 且 c>d")
    }

    @Test
    fun flushReleasesAHeldButIncompleteTail() {
        val s = DeltaScrubber()
        assertThat(s.feed("结尾是不完整的 <")).isEqualTo("结尾是不完整的 ")
        assertThat(s.flush()).isEqualTo("<")
    }

    @Test
    fun overlongAngleSequenceIsNotHeldForever() {
        val s = DeltaScrubber()
        val long = "<" + "x".repeat(DeltaScrubber.MAX_HOLD + 10)
        // 超过阈值就当作正文放出去，否则内存与延迟都不可控
        assertThat(s.feed(long)).isEqualTo(long)
        assertThat(s.flush()).isEqualTo("")
    }
}
