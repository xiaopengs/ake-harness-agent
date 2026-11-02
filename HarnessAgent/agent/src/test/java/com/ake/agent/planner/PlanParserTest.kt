package com.ake.agent.planner

import com.ake.core.contract.PlanLimits
import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `PlanParser` 的回归测试。
 *
 * ## 为什么这一层必须单测
 *
 * 计划解析的输出会**原样展示给用户确认**。解析错一步，用户确认的就是另一件事 ——
 * 这是本项目里少数"错误不会当场报错、而是让人做错决定"的地方之一。
 * 而且模型输出天然不干净（围栏、解释、字段名漂移），宽容度与闭合度之间只隔一层
 * `when`，改一行就可能把"丢弃未知 kind"变成"放行未知 kind"。
 */
class PlanParserTest {

    @Test
    fun parsesTheHappyPath() {
        val r = PlanParser.parse(
            """{"steps":[{"do":"read_file","target":"这份 PDF"},""" +
                """{"do":"pdf_extract"},{"do":"summarize"},{"do":"report_todos"}]}""",
        )
        assertThat(r).isInstanceOf(PlanParser.Result.Ok::class.java)
        val ok = r as PlanParser.Result.Ok
        assertThat(ok.steps.map { it.kind }).containsExactly(
            PlanStepKind.READ_FILE,
            PlanStepKind.EXTRACT,
            PlanStepKind.SUMMARIZE,
            PlanStepKind.TODOS,
        ).inOrder()
        assertThat(ok.steps.map { it.index }).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(ok.dropped).isEqualTo(0)
        assertThat(ok.truncated).isEqualTo(0)
    }

    @Test
    fun stripsCodeFenceAndSurroundingProse() {
        val r = PlanParser.parse(
            "好的，计划如下：\n```json\n" +
                """{"steps":[{"do":"read_file"},{"do":"summarize"}]}""" +
                "\n```\n需要我调整吗？",
        )
        assertThat(r).isInstanceOf(PlanParser.Result.Ok::class.java)
        assertThat((r as PlanParser.Result.Ok).steps).hasSize(2)
    }

    @Test
    fun dropsUnknownKindsInsteadOfPassingThemThrough() {
        // ★ 这条是整个类最要紧的一条：未知 kind 绝不能流到执行期。
        //   执行期没有对应分支，只会"静默跳过"，而用户看到的是计划里明明写着这一步。
        val r = PlanParser.parse(
            """{"steps":[{"do":"read_file"},{"do":"analyze_deeply"},""" +
                """{"do":"summarize"},{"do":"report_todos"}]}""",
        )
        val ok = r as PlanParser.Result.Ok
        assertThat(ok.steps.map { it.kind }).doesNotContain("analyze_deeply")
        assertThat(ok.dropped).isEqualTo(1)
    }

    @Test
    fun mergesConsecutiveDuplicateKinds() {
        val r = PlanParser.parse(
            """{"steps":[{"do":"read_file"},{"do":"read_file"},""" +
                """{"do":"summarize"},{"do":"report_todos"}]}""",
        )
        val ok = r as PlanParser.Result.Ok
        assertThat(ok.steps).hasSize(3)
        assertThat(ok.dropped).isEqualTo(1)
    }

    @Test
    fun truncatesToMaxStepsAndReportsHowManyWereCut() {
        // 8 步，第 5 步是产出步 ⇒ 截到 5 步之后仍然合法（最后一步是 report_todos）。
        // ★ 这条测试顺带钉住了一个容易忘的规则：截断发生在"校验收尾"之前，
        //   所以截完必须再检查一次最后一步 —— 否则会产出一份"跑完什么都没有"的计划。
        val json = """
            {"steps":[
              {"do":"read_file"},
              {"do":"pdf_extract"},
              {"do":"summarize"},
              {"do":"read_file"},
              {"do":"report_todos"},
              {"do":"read_file"},
              {"do":"pdf_extract"},
              {"do":"summarize"}
            ]}
        """.trimIndent()
        val ok = PlanParser.parse(json) as PlanParser.Result.Ok
        assertThat(ok.steps).hasSize(PlanLimits.MAX_STEPS)
        assertThat(ok.steps.last().kind).isEqualTo(PlanStepKind.TODOS)
        assertThat(ok.truncated).isEqualTo(3)
        assertThat(ok.dropped).isEqualTo(0)
    }

    @Test
    fun rejectsPlanThatEndsWithoutAnOutputStep() {
        val r = PlanParser.parse("""{"steps":[{"do":"read_file"},{"do":"pdf_extract"}]}""")
        assertThat(r).isInstanceOf(PlanParser.Result.Invalid::class.java)
        val invalid = r as PlanParser.Result.Invalid
        assertThat(invalid.hint).contains("summarize")
    }

    @Test
    fun rejectsTruncationThatCutsOffTheOutputStep() {
        // 6 步全是 read/extract，产出步在第 6 步 —— 截断后最后一步是 extract ⇒ 必须拒绝，
        // 而不是"截完了但用户拿不到结果"。
        val r = PlanParser.parse(
            """{"steps":[{"do":"read_file"},{"do":"pdf_extract"},""" +
                """{"do":"read_file"},{"do":"pdf_extract"},""" +
                """{"do":"read_file"},{"do":"summarize"}]}""",
        )
        assertThat(r).isInstanceOf(PlanParser.Result.Invalid::class.java)
    }

    @Test
    fun rejectsEmptyOrMissingSteps() {
        assertThat(PlanParser.parse("""{"steps":[]}"""))
            .isInstanceOf(PlanParser.Result.Invalid::class.java)
        assertThat(PlanParser.parse("""{"plan":42}"""))
            .isInstanceOf(PlanParser.Result.Invalid::class.java)
        assertThat(PlanParser.parse("我觉得应该先读文件"))
            .isInstanceOf(PlanParser.Result.Invalid::class.java)
    }

    @Test
    fun acceptsAlternativeFieldNamesWithoutSemanticGuessing() {
        val r = PlanParser.parse(
            """{"plan":[{"act":"read_file","on":"这个文件"},""" +
                """{"act":"summarize","on":"正文"}]}""",
        )
        val ok = r as PlanParser.Result.Ok
        assertThat(ok.steps.first().target).isEqualTo("这个文件")
    }

    @Test
    fun normalizeRenumbersAfterUserDeletesAStep() {
        val steps = listOf(
            PlanStep(0, PlanStepKind.READ_FILE, "文件"),
            PlanStep(1, PlanStepKind.EXTRACT, "正文"),
            PlanStep(3, PlanStepKind.TODOS, "待办"), // 用户删掉了第 2 步，index 断号
        )
        val ok = PlanParser.normalize(steps) as PlanParser.Result.Ok
        assertThat(ok.steps.map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun normalizeRejectsWhenUserDeletesTheOutputStep() {
        val steps = listOf(
            PlanStep(0, PlanStepKind.READ_FILE, "文件"),
            PlanStep(1, PlanStepKind.EXTRACT, "正文"),
        )
        val r = PlanParser.normalize(steps)
        assertThat(r).isInstanceOf(PlanParser.Result.Invalid::class.java)
        assertThat((r as PlanParser.Result.Invalid).hint).contains("summarize")
    }
}
