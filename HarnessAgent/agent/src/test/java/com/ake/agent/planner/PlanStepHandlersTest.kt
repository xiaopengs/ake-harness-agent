package com.ake.agent.planner

import com.ake.agent.fixtures.FakeModelPort
import com.ake.core.common.Outcome
import com.ake.core.common.TaskId
import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind
import com.ake.core.ports.DocumentError
import com.ake.core.ports.DocumentPort
import com.ake.core.ports.DocumentProfile
import com.ake.core.ports.DocumentSource
import com.ake.core.ports.DocProbeResult
import com.ake.core.ports.NoAbortSignal
import com.ake.core.ports.PageBudget
import com.ake.core.ports.PageEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 步骤实现的行为回归：分块策略、错误映射、抽取上限提示。 */
class PlanStepHandlersTest {

    private val source = DocumentSource(localPath = "/tmp/a.pdf", displayName = "年报.pdf", byteSize = 1_000)

    private fun ctx(upstream: String = "", document: DocumentSource? = source) = PlanStepContext(
        taskId = TaskId("t1"),
        goal = "把这份 PDF 的重点整理出来",
        upstream = upstream,
        document = document,
        abort = NoAbortSignal,
    )

    @Test
    fun `read_file turns an encrypted probe into a typed error, not a crash`() = runBlocking {
        val docs = FakeDocs(probe = DocProbeResult.Failed(DocumentError.Encrypted))
        val r = ReadFileStepHandler(docs).execute(PlanStep(0, PlanStepKind.READ_FILE, "PDF"), ctx())

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo("DOC_ENCRYPTED")
    }

    @Test
    fun `read_file describes the document instead of dumping its text`() = runBlocking {
        val docs = FakeDocs()
        val r = ReadFileStepHandler(docs).execute(PlanStep(0, PlanStepKind.READ_FILE, "PDF"), ctx())

        val text = (r as Outcome.Ok).value.text
        assertThat(text).contains("共 3 页")
        assertThat(text).contains("文本型")
        // 这一步的价值是把"能不能读"提前暴露，正文留给 pdf_extract。
        assertThat(docs.pagesCalls).isEqualTo(0)
    }

    @Test
    fun `read_file without a document fails with NO_DOCUMENT`() = runBlocking {
        val r = ReadFileStepHandler(FakeDocs()).execute(
            PlanStep(0, PlanStepKind.READ_FILE, "PDF"),
            ctx(document = null),
        )
        assertThat((r as Outcome.Err).error.code).isEqualTo("NO_DOCUMENT")
    }

    @Test
    fun `extract reports skipped pages and keeps the rest`() = runBlocking {
        val docs = FakeDocs(
            pages = listOf(
                PageEvent.Text(1, 20, "第一页正文。"),
                PageEvent.Skipped(2, DocumentError.PageFailed(2, "坏页")),
                PageEvent.Text(3, 20, "第三页正文。"),
            ),
        )
        val r = ExtractStepHandler(docs, budget = PageBudget(maxPages = 50, maxCharsPerPage = 20_000))
            .execute(PlanStep(1, PlanStepKind.EXTRACT, "正文"), ctx())

        val text = (r as Outcome.Ok).value.text
        assertThat(text).contains("已抽取 2 页")
        assertThat(text).contains("跳过 1 页")
        assertThat(text).contains("第三页正文")
    }

    @Test
    fun `extract fails loudly when every page is a scan`() = runBlocking {
        val docs = FakeDocs(pages = listOf(PageEvent.Skipped(1, DocumentError.PageFailed(1, "无文本层"))))
        val r = ExtractStepHandler(docs).execute(PlanStep(1, PlanStepKind.EXTRACT, "正文"), ctx())

        assertThat((r as Outcome.Err).error.code).isEqualTo("DOC_NO_TEXT")
    }

    @Test
    fun `summarize fails without opening a session when the focus is busy`() = runBlocking {
        val model = FakeModelPort(script = { "不该被调用" })
        val r = SummarizeStepHandler(model, focus = FakeFocus(busy = true)).execute(
            PlanStep(2, PlanStepKind.SUMMARIZE, "重点"),
            ctx(upstream = "短文本。"),
        )

        assertThat((r as Outcome.Err).error.code).isEqualTo("FOCUS_BUSY")
        // 会话一次都不该开：开了就是两条路径同时进 native（F3 串字）。
        assertThat(model.generateCalls).isEmpty()
    }

    @Test
    fun `short input needs only the map pass`() = runBlocking {
        val model = FakeModelPort(script = { "这段讲了三件事。" })
        val r = SummarizeStepHandler(model).execute(
            PlanStep(2, PlanStepKind.SUMMARIZE, "重点"),
            ctx(upstream = "短文本。"),
        )

        assertThat((r as Outcome.Ok).value.text).isEqualTo("这段讲了三件事。")
        assertThat(model.generateCalls).hasSize(1)
    }

    @Test
    fun `long input is split into map passes plus one reduce`() = runBlocking {
        // 5 段 × 800 字 ≈ 4000 字，每块 2000 字 ⇒ 3 块 map + 1 次 reduce
        val long = (1..5).joinToString("\n") { "第${it}段。" + "内容".repeat(400) }
        val model = FakeModelPort(script = { i -> "摘要$i" })

        val r = SummarizeStepHandler(model, modelId = "m").execute(
            PlanStep(2, PlanStepKind.SUMMARIZE, "重点"),
            ctx(upstream = long),
        )

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(model.generateCalls).hasSize(4)
        assertThat(model.generateCalls[0].userTurn).contains("第 1/3 段")
        assertThat(model.generateCalls[1].userTurn).contains("第 2/3 段")
        // reduce 阶段拿到的是 map 的结果，而不是原文
        assertThat(model.generateCalls[3].userTurn).contains("摘要0")
        assertThat(model.generateCalls[3].userTurn).contains("摘要2")
        assertThat(model.generateCalls[3].userTurn).doesNotContain("内容内容内容内容内容内容内容内容")
    }

    @Test
    fun `summarize refuses to invent content from an empty upstream`() = runBlocking {
        val r = SummarizeStepHandler(FakeModelPort()).execute(
            PlanStep(2, PlanStepKind.SUMMARIZE, "重点"),
            ctx(upstream = "  "),
        )
        assertThat((r as Outcome.Err).error.code).isEqualTo("EMPTY_INPUT")
    }

    @Test
    fun `every kind has a handler except export`() {
        val routed = PlanStepHandlers.routing(FakeDocs(), FakeModelPort())
        val kinds = (routed as KindRoutedPlanStepHandler).supportedKinds()

        assertThat(kinds).containsExactly(
            PlanStepKind.READ_FILE,
            PlanStepKind.EXTRACT,
            PlanStepKind.SUMMARIZE,
            PlanStepKind.TODOS,
        )
        // 导出需要落盘能力，留给 :app 的处理器补 —— 这里显式钉住"它还没实现"。
        assertThat(kinds).doesNotContain(PlanStepKind.EXPORT)
    }

    private class FakeDocs(
        private val probe: DocProbeResult = DocProbeResult.Ok(
            DocumentProfile(pageCount = 3, isEncrypted = false, isTextual = true, avgCharsPerPage = 1_200),
        ),
        private val pages: List<PageEvent> = listOf(PageEvent.Text(1, 10, "正文")),
    ) : DocumentPort {
        var pagesCalls: Int = 0

        override suspend fun probe(source: DocumentSource): DocProbeResult = probe

        override fun pages(source: DocumentSource, budget: PageBudget): Flow<PageEvent> {
            pagesCalls++
            return flowOf(*pages.toTypedArray())
        }
    }
}
