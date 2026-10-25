package com.ake.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `StreamingDeltaAssembler` —— 把「引擎给的 chunk」变成「真正新增的那一段」。
 *
 * ## 为什么要为三行逻辑写六条断言
 *
 * LiteRT-LM 0.18.0 的回调每次给一个 `Message`，但**增量和累计两种语义官方都没有写明**，
 * 本项目也没有真机可验证（§3.3 原话：「`Flow<Message>` 的累加语义需在真机确认」）。
 * 两种语义接错的表现完全不同，而且都很难在现场归因：
 *  - 累计当增量 ⇒ 屏幕上 "我我我觉得得得"（每 chunk 重复一遍已有内容）；
 *  - 增量当累计 ⇒ 从第二个 chunk 起全部吞掉（比重复更难发现）。
 *
 * 所以实现按前缀关系自适应，这组测试就是那两个分支的合同：
 * **无论引擎给的是哪一种，投递序列拼起来都必须等于最终全文。**
 */
@RunWith(JUnit4::class)
class StreamingDeltaAssemblerTest {

    @Test
    fun `累计语义：每次给全文，只投增量`() {
        val a = StreamingDeltaAssembler()

        assertThat(a.feed("我")).isEqualTo("我")
        assertThat(a.feed("我说")).isEqualTo("说")
        assertThat(a.feed("我说完了")).isEqualTo("完了")
        assertThat(a.text).isEqualTo("我说完了")
    }

    @Test
    fun `增量语义：每次给一段，原样投递`() {
        val a = StreamingDeltaAssembler()

        assertThat(a.feed("我")).isEqualTo("我")
        assertThat(a.feed("说")).isEqualTo("说")
        assertThat(a.feed("完了")).isEqualTo("完了")
        assertThat(a.text).isEqualTo("我说完了")
    }

    @Test
    fun `两种语义拼出来的全文一致`() {
        val cumulative = StreamingDeltaAssembler().apply {
            listOf("a", "ab", "abc", "abcd").forEach { feed(it) }
        }
        val incremental = StreamingDeltaAssembler().apply {
            listOf("a", "b", "c", "d").forEach { feed(it) }
        }

        assertThat(cumulative.text).isEqualTo("abcd")
        assertThat(incremental.text).isEqualTo("abcd")
    }

    @Test
    fun `重复投递同一条不产生 delta`() {
        // 引擎在 onDone 之前可能把最后一条 Message 再送一次
        val a = StreamingDeltaAssembler()

        assertThat(a.feed("你好")).isEqualTo("你好")
        assertThat(a.feed("你好")).isEmpty()
        assertThat(a.text).isEqualTo("你好")
    }

    @Test
    fun `空 chunk 不产 delta（UI 不该白跑一次重组）`() {
        val a = StreamingDeltaAssembler()

        assertThat(a.feed("")).isEmpty()
        assertThat(a.text).isEmpty()
    }

    @Test
    fun `tool-call 那种空正文的 chunk 不会污染全文`() {
        // 约束解码下引擎可能先吐一个只有 toolCalls、没有正文的 Message
        val a = StreamingDeltaAssembler()

        assertThat(a.feed("")).isEmpty()
        assertThat(a.feed("{\"act\":\"tap\"}")).isEqualTo("{\"act\":\"tap\"}")
        assertThat(a.text).isEqualTo("{\"act\":\"tap\"}")
    }
}
