package com.ake.data.persist.store

import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `PlanCodec` 的编解码测试。
 *
 * ## 为什么这几条值得写
 *
 * 这个格式是自己发明的（不是 JSON），所以在**转义边界**上没有任何"标准库帮我兜着"。
 * 一个 `|` 没转义，解码时就会被当成字段分隔符 —— 症状是"用户确认的计划里，
 * 某一步的 target 被截断成半个词"，而且**只在用户的目标句里恰好有竖线时出现**。
 * 这类 bug 不写测试基本发现不了。
 */
class PlanCodecTest {

    @Test
    fun roundTripsPlainSteps() {
        val steps = listOf(
            PlanStep(0, PlanStepKind.READ_FILE, "这份 PDF"),
            PlanStep(1, PlanStepKind.SUMMARIZE, "正文"),
            PlanStep(2, PlanStepKind.TODOS, "待办"),
        )
        val decoded = PlanCodec.decode(PlanCodec.encode(steps))
        assertThat(decoded.map { it.kind }).containsExactlyElementsIn(steps.map { it.kind }).inOrder()
        assertThat(decoded.map { it.target }).containsExactlyElementsIn(steps.map { it.target }).inOrder()
    }

    @Test
    fun escapesDelimiterBackslashAndNewlines() {
        val tricky = """a|b\c
第二行"""
        val steps = listOf(PlanStep(0, PlanStepKind.READ_FILE, tricky))
        val encoded = PlanCodec.encode(steps)

        // 编码结果必须只有一行（换行被折成空格），否则解码会多出一条
        assertThat(encoded.count { it == '\n' }).isEqualTo(0)

        val decoded = PlanCodec.decode(encoded).single()
        assertThat(decoded.target).isEqualTo("""a|b\c 第二行""")
    }

    @Test
    fun dropsUnknownKindsOnDecode() {
        // 旧版本写进去的、或被人手工改过的行：闭集之外一律丢弃
        val text = "read_file|文件\nanalyze|深度分析\nsummarize|正文"
        val decoded = PlanCodec.decode(text)
        assertThat(decoded.map { it.kind })
            .containsExactly(PlanStepKind.READ_FILE, PlanStepKind.SUMMARIZE).inOrder()
    }

    @Test
    fun ignoresBlankAndMalformedLines() {
        val text = "\n\nread_file|文件\n\n没有分隔符的一行\nsummarize|正文\n"
        val decoded = PlanCodec.decode(text)
        assertThat(decoded).hasSize(2)
        // 重排后的 index 必须连续 —— 断号的 index 会让断点续跑定位错步骤
        assertThat(decoded.map { it.index }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun emptyInputDecodesToEmptyList() {
        assertThat(PlanCodec.decode("")).isEmpty()
        assertThat(PlanCodec.decode("   ")).isEmpty()
        assertThat(PlanCodec.encode(emptyList())).isEmpty()
    }
}
