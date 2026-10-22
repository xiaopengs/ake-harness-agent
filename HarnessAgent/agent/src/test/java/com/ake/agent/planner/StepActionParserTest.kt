package com.ake.agent.planner

import com.ake.core.common.StepId
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolId
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.InferenceStats
import com.ake.core.modelapi.RawToolCall
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `StepActionParser` 与 `JsonTolerant` 的回归测试（2026-10）。
 *
 * ## 为什么这一层值得单测
 *
 * 端侧 decode 只有 ~52 tok/s，模型输出的 JSON **注定**带着各种毛病：
 * 单引号、尾逗号、未加引号的 key、全角引号、值里含裸换行、外层裹 ```json 围栏、
 * 前后带自然语言说明、甚至一次吐两个对象。
 *
 * 严格解析器遇到这些会**整体失败**，而补救成本是**一次 4.5 秒的推理**。
 * 所以这里的容错不是「锦上添花」，是端侧单步成本结构逼出来的必需能力。
 *
 * ## 它守的边界
 *
 * `parse` 只负责**语法落地**，不负责语义合法性 ——
 * 元素存在性、动作可行性、URL 合法性归闸 4 / 闸 5（`StepActionParser` 类注释原文：
 * 「解析成功 ≠ 可以执行」）。所以下面对 [StepAction.Click] 这类只断言它解析出了编号，
 * 不断言编号有效。
 */
class StepActionParserTest {

    private val parser = StepActionParser()

    private fun gen(raw: String, toolCalls: List<RawToolCall> = emptyList()): GenerationResult =
        GenerationResult(
            rawText = raw,
            toolCalls = toolCalls,
            finishReason = FinishReason.STOP,
            stats = InferenceStats(prefillTokens = 0, decodeTokens = 0, ttftMs = 0, decodeTps = 0.0),
        )

    private fun parse(raw: String): StepActionParser.ParseResult =
        parser.parse(gen(raw), com.ake.agent.fixtures.testDom())

    // ──────────────────────────────────────────── ① 干净输入

    @Test
    fun `标准 JSON 逐动作解析正确`() {
        assertThat(parse("""{"act":"click","id":12}""").ok()).isEqualTo(StepAction.Click(12))
        assertThat(parse("""{"act":"type","id":7,"text":"无线鼠标","submit":true}""").ok())
            .isEqualTo(StepAction.Type(7, "无线鼠标", true))
        assertThat(parse("""{"act":"select","id":9,"value":"销量"}""").ok())
            .isEqualTo(StepAction.Select(9, "销量"))
        assertThat(parse("""{"act":"scroll","dir":"up","px":1200}""").ok())
            .isEqualTo(StepAction.Scroll("up", 1200))
        assertThat(parse("""{"act":"goto","url":"https://a.example.com/x"}""").ok())
            .isEqualTo(StepAction.Navigate("https://a.example.com/x"))
        assertThat(parse("""{"act":"ask","question":"需要登录吗"}""").ok())
            .isEqualTo(StepAction.AskHuman("需要登录吗"))
    }

    @Test
    fun `action 字段名与 act 等价`() {
        // 端侧模型经常把 `act` 写成 `action`；两条路都走同一套解析，不搞第二份
        assertThat(parse("""{"action":"click","id":3}""").ok()).isEqualTo(StepAction.Click(3))
    }

    @Test
    fun `动作值的大小写与首尾空白都被容忍`() {
        assertThat(parse("""  {"act":"CLICK","id":3}  """).ok()).isEqualTo(StepAction.Click(3))
        // scroll 的 dir 只认小写 `up`，其余一律按 down 处理（见下面的断言）——
        // 这样模型吐出 "Up" / "UP" 时不会走进一个未定义分支
        assertThat(parse("""{"act":"scroll","dir":"UP"}""").ok()).isEqualTo(StepAction.Scroll("down", 800))
        assertThat(parse("""{"act":"scroll","dir":"up"}""").ok()).isEqualTo(StepAction.Scroll("up", 800))
    }

    @Test
    fun `键名必须严格是 act 或 action`() {
        // ★ 这条是**现状契约**而不是理想：`act` 键的查找大小写敏感。
        //   不做宽松匹配是刻意的 —— 键名由我们的 ACTION SCHEMA 规定，
        //   模型吐出的键名不对属于「没按 schema 输出」，该走修复重试路径，
        //   而不是由解析器去猜。把这条写成断言，是为了让「以后要不要放宽」变成一个显式决定。
        assertThat(parse("""{"ACT":"click","id":3}""")).isInstanceOf(StepActionParser.ParseResult.Fail::class.java)
    }

    @Test
    fun `缺省字段按契约补齐`() {
        // scroll 的 dir 默认 down、px 默认 800；extract 的 limit 默认 20
        assertThat(parse("""{"act":"scroll"}""").ok()).isEqualTo(StepAction.Scroll("down", 800))
        assertThat(parse("""{"act":"extract","selectorHint":".item"}""").ok())
            .isEqualTo(StepAction.Extract(".item", emptyList(), 20))
    }

    // ──────────────────────────────────────────── ② 容错通道（脏输入）

    @Test
    fun `跳过前后自然语言只取第一个完整对象`() {
        val r = parse("好的，我先点击它。\n{\"act\":\"click\",\"id\":1}\n然后继续。")
        assertThat(r.ok()).isEqualTo(StepAction.Click(1))
    }

    @Test
    fun `剥掉 markdown 围栏`() {
        assertThat(parse("```json\n{\"act\":\"click\",\"id\":2}\n```").ok())
            .isEqualTo(StepAction.Click(2))
    }

    @Test
    fun `未加引号的 key 会被补上引号`() {
        // ★ 2026-10 抓到的真 bug：`normalize` 里把 `$1`（分隔符 `{` / `,`）当成了 key，
        //   替换后文本比修复前更不可解析。这条用例就是它的回归锁。
        assertThat(parse("""{act:"click", id:2}""").ok()).isEqualTo(StepAction.Click(2))
    }

    @Test
    fun `尾逗号被容忍`() {
        assertThat(parse("""{"act":"finish","answer":"x",}""").ok())
            .isEqualTo(StepAction.Finish("x", emptyList()))
    }

    @Test
    fun `中文全角引号被规整`() {
        val r = parse("""{“act”:“click”,“id”:1}""")
        assertThat(r.ok()).isEqualTo(StepAction.Click(1))
    }

    @Test
    fun `单引号字符串被规整`() {
        assertThat(parse("""{'act':'click','id':1}""").ok()).isEqualTo(StepAction.Click(1))
    }

    @Test
    fun `整数以浮点或字符串回来都能转成 Int`() {
        // §7.2 的坑：约束解码回灌时整数经常以 Double 回来
        assertThat(parse("""{"act":"click","id":12.0}""").ok()).isEqualTo(StepAction.Click(12))
        assertThat(parse("""{"act":"click","id":"12"}""").ok()).isEqualTo(StepAction.Click(12))
    }

    @Test
    fun `一次吐两个对象时取第一个`() {
        val r = parse("""{"act":"click","id":1}{"act":"click","id":2}""")
        assertThat(r.ok()).isEqualTo(StepAction.Click(1))
    }

    // ──────────────────────────────────────────── ③ 失败路径（要给出可修复的信息）

    @Test
    fun `空输出报 EMPTY_OUTPUT`() {
        assertThat(failureCodeOf("   ")).isEqualTo("EMPTY_OUTPUT")
    }

    @Test
    fun `找不到完整 JSON 报 NO_JSON`() {
        assertThat(failureCodeOf("我想先点一下排序按钮")).isEqualTo("NO_JSON")
    }

    @Test
    fun `动作名不认识报 UNKNOWN_ACT 并把它带回给模型`() {
        val f = parse("""{"act":"teleport"}""") as StepActionParser.ParseResult.Fail
        assertThat(f.hint.failure.code).isEqualTo("UNKNOWN_ACT")
        // 话术里必须带上模型自己吐出来的那个动作名，否则它不知道自己错在哪
        assertThat(f.hint.toModel).contains("teleport")
    }

    @Test
    fun `缺必填字段报 MISSING_FIELD 且指出是哪个字段`() {
        assertThat(failureDetailOf("""{"act":"click"}""")).contains("id")
        assertThat(failureDetailOf("""{"act":"type","id":1}""")).contains("text")
        assertThat(failureDetailOf("""{"act":"goto"}""")).contains("url")
        assertThat(failureDetailOf("""{"act":"finish"}""")).contains("answer")
    }

    @Test
    fun `超长输入被拦下而不是撑爆上下文`() {
        val long = "x".repeat(600)
        val f = parse("""{"act":"type","id":1,"text":"$long"}""") as StepActionParser.ParseResult.Fail
        assertThat(f.hint.failure.code).isEqualTo("BAD_FIELD_TYPE")
        assertThat(f.hint.toModel).contains("512")
    }

    @Test
    fun `limit 被夹到 1 到 100 之间`() {
        assertThat(parse("""{"act":"extract","limit":9999}""").ok())
            .isEqualTo(StepAction.Extract("", emptyList(), 100))
        assertThat(parse("""{"act":"extract","limit":0}""").ok())
            .isEqualTo(StepAction.Extract("", emptyList(), 1))
    }

    @Test
    fun `scroll 的 px 被夹到 0 到 20000`() {
        assertThat(parse("""{"act":"scroll","px":99999}""").ok())
            .isEqualTo(StepAction.Scroll("down", 20_000))
    }

    // ──────────────────────────────────────────── ④ 约束解码通道

    @Test
    fun `结构化工具调用优先于文本通道`() {
        val call = RawToolCall(
            name = "click",
            argumentsJson = """{"id":5}""",
            callId = null,
        )
        val r = parser.parse(gen("这段文本不该被采用", toolCalls = listOf(call)), null)
        assertThat(r.ok()).isEqualTo(StepAction.Click(5))
    }

    @Test
    fun `结构化调用语义不合时回落到文本通道`() {
        // 约束解码只保证语法合法，不保证语义落地 ⇒ 结构调用缺字段时要能退回文本
        val call = RawToolCall(name = "click", argumentsJson = """{}""", callId = null)
        val r = parser.parse(
            gen("""{"act":"click","id":8}""", toolCalls = listOf(call)),
            null,
        )
        assertThat(r.ok()).isEqualTo(StepAction.Click(8))
    }

    @Test
    fun `parseFromFields 与执行期走同一套解析`() {
        // 断点回放与执行期对同一段 JSON 的行为**不得**分叉（否则回放出来的是另一个故事）
        val fields = JsonTolerant.parseObject("""{"act":"click","id":9}""")!!.asObject()!!
        assertThat((parser.parseFromFields(fields) as StepActionParser.ParseResult.Ok).action)
            .isEqualTo(StepAction.Click(9))
    }

    // ──────────────────────────────────────────── 工具

    private fun StepActionParser.ParseResult.ok(): StepAction =
        (this as StepActionParser.ParseResult.Ok).action

    private fun failureCodeOf(raw: String): String =
        ((parse(raw) as StepActionParser.ParseResult.Fail).hint.failure.code)

    private fun failureDetailOf(raw: String): String =
        (parse(raw) as StepActionParser.ParseResult.Fail).hint.toModel

    private val StepActionParser.ParseResult.Fail.hintFailureName: String
        get() = hint.failure.code
}
