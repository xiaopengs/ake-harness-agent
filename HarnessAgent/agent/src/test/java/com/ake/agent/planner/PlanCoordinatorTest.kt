package com.ake.agent.planner

import com.ake.agent.fixtures.FakeModelPort
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.TaskId
import com.ake.core.contract.PlanRevision
import com.ake.core.contract.PlanStatus
import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.DocumentSource
import com.ake.core.ports.NoAbortSignal
import com.ake.core.ports.PlanDraftResult
import com.ake.core.ports.PlanRunEvent
import com.ake.core.ports.PlanStorePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `PlanCoordinator` 的行为回归。
 *
 * 这里刻意不测"模型输出长什么样"（那是 `PlanParserTest` 的活），只测**编排**：
 * 重试几次、哪一步中止、失败之后后面的步骤有没有被偷偷跑掉。
 */
class PlanCoordinatorTest {

    private val taskId = TaskId("task-plan-1")

    // ─────────────────────────── draft ───────────────────────────

    @Test
    fun `draft saves a DRAFT revision and reports dropped steps`() = runBlocking {
        val model = FakeModelPort(
            script = {
                """
                {"steps":[
                  {"do":"read_file","target":"这份 PDF"},
                  {"do":"analyze","target":"瞎写的"},
                  {"do":"pdf_extract","target":"正文"},
                  {"do":"summarize","target":"重点"}
                ]}
                """.trimIndent()
            },
        )
        val store = FakePlanStore()
        val plan = coordinator(model, store)

        val result = plan.draft(taskId, "把这份 PDF 的重点整理出来", "年报.pdf")

        assertThat(result).isInstanceOf(PlanDraftResult.Drafted::class.java)
        val drafted = result as PlanDraftResult.Drafted
        assertThat(drafted.dropped).isEqualTo(1)
        assertThat(drafted.revision.status).isEqualTo(PlanStatus.DRAFT)
        assertThat(drafted.revision.steps.map { it.kind })
            .containsExactly(PlanStepKind.READ_FILE, PlanStepKind.EXTRACT, PlanStepKind.SUMMARIZE)
        assertThat(store.saved).hasSize(1)
    }

    @Test
    fun `draft retries once with a repair prompt and does not echo the bad output`() = runBlocking {
        val model = FakeModelPort(
            script = { attempt ->
                if (attempt == 0) {
                    "好的，我来帮你规划一下：先读文件，然后总结。"
                } else {
                    """{"steps":[{"do":"read_file","target":"PDF"},{"do":"summarize","target":"重点"}]}"""
                }
            },
        )
        val plan = coordinator(model, FakePlanStore())

        val result = plan.draft(taskId, "读这个 PDF 并总结", null)

        assertThat(result).isInstanceOf(PlanDraftResult.Drafted::class.java)
        assertThat(model.generateCalls).hasSize(2)
        val repairTurn = model.generateCalls[1].userTurn
        assertThat(repairTurn).contains("上一次的输出不能用")
        // ★ 只回灌"怎么改"：把上一次的错误输出整段贴回去，2B 模型有概率照抄。
        assertThat(repairTurn).doesNotContain("先读文件，然后总结")
    }

    @Test
    fun `draft gives up after two failures instead of burning more seconds`() = runBlocking {
        val model = FakeModelPort(script = { "not json at all" })
        val plan = coordinator(model, FakePlanStore())

        val result = plan.draft(taskId, "读这个 PDF", null)

        assertThat(result).isInstanceOf(PlanDraftResult.Rejected::class.java)
        assertThat((result as PlanDraftResult.Rejected).hint.isNotBlank()).isTrue()
        assertThat(model.generateCalls).hasSize(2)
    }

    @Test
    fun `draft reports model failure as Failed not Rejected`() = runBlocking {
        val model = FakeModelPort(openFails = true)
        val plan = coordinator(model, FakePlanStore())

        val result = plan.draft(taskId, "读这个 PDF", null)

        // 模型没加载 ≠ 计划写得不好：前者用户重试可能有用，后者要改目标。
        assertThat(result).isInstanceOf(PlanDraftResult.Failed::class.java)
    }

    @Test
    fun `draft rejects a blank goal without calling the model`() = runBlocking {
        val model = FakeModelPort()
        val plan = coordinator(model, FakePlanStore())

        val result = plan.draft(taskId, "   ", null)

        assertThat(result).isInstanceOf(PlanDraftResult.Rejected::class.java)
        assertThat(model.generateCalls).isEmpty()
    }

    // ────────────────────────── execute ──────────────────────────

    @Test
    fun `execute chains each step's output into the next step`() = runBlocking {
        val store = FakePlanStore().apply {
            seed(confirmed(2))
        }
        val handler = RecordingHandler { step, ctx ->
            "S${step.index}(${ctx.upstream})"
        }
        val plan = coordinator(FakeModelPort(), store, handler)

        val events = plan.execute(taskId, revision = 2).toList()

        assertThat(handler.seenUpstream).containsExactly("", "S0()", "S1(S0())")
        assertThat(events.first()).isInstanceOf(PlanRunEvent.Started::class.java)
        val done = events.last()
        assertThat(done).isInstanceOf(PlanRunEvent.Completed::class.java)
        assertThat((done as PlanRunEvent.Completed).result).isEqualTo("S2(S1(S0()))")
    }

    @Test
    fun `execute refuses a revision the user did not confirm`() = runBlocking {
        val store = FakePlanStore().apply { seed(confirmed(3)) }
        val handler = RecordingHandler { step, _ -> "S${step.index}" }
        val plan = coordinator(FakeModelPort(), store, handler)

        val events = plan.execute(taskId, revision = 2).toList()

        assertThat(events).hasSize(1)
        assertThat((events.single() as PlanRunEvent.Failed).error.code)
            .isEqualTo("PLAN_REVISION_MISMATCH")
        assertThat(handler.calls).isEqualTo(0)
    }

    @Test
    fun `execute refuses a draft that was never confirmed`() = runBlocking {
        val store = FakePlanStore().apply {
            seed(confirmed(1).copy(status = PlanStatus.DRAFT))
        }
        val plan = coordinator(FakeModelPort(), store, RecordingHandler { s, _ -> "S${s.index}" })

        val events = plan.execute(taskId, revision = 1).toList()

        assertThat((events.single() as PlanRunEvent.Failed).error.code)
            .isEqualTo("PLAN_NOT_CONFIRMED")
    }

    @Test
    fun `a failed step stops the run and the rest never executes`() = runBlocking {
        val store = FakePlanStore().apply { seed(confirmed(1)) }
        val handler = RecordingHandler { step, _ ->
            if (step.index == 1) throw IllegalStateException("pdf box 炸了") else "S${step.index}"
        }
        val plan = coordinator(FakeModelPort(), store, handler)

        val events = plan.execute(taskId, revision = 1).toList()

        assertThat(handler.calls).isEqualTo(2) // 0、1 跑过，2 没跑
        assertThat(events.filterIsInstance<PlanRunEvent.StepFailed>().single().index).isEqualTo(1)
        assertThat(events.last()).isInstanceOf(PlanRunEvent.Failed::class.java)
        assertThat(events.none { it is PlanRunEvent.Completed }).isTrue()
    }

    @Test
    fun `abort between steps emits Aborted and names the step it stopped at`() = runBlocking {
        val store = FakePlanStore().apply { seed(confirmed(1)) }
        val abort = object : AbortSignal {
            var hits = 0
            override fun isAborted(): Boolean = ++hits > 1
        }
        val handler = RecordingHandler { step, _ -> "S${step.index}" }
        val plan = coordinator(FakeModelPort(), store, handler)

        val events = plan.execute(taskId, revision = 1, abort = abort).toList()

        assertThat(events.last()).isEqualTo(PlanRunEvent.Aborted(1))
        assertThat(handler.calls).isEqualTo(1)
    }

    @Test
    fun `missing handler fails the step instead of silently skipping it`() = runBlocking {
        val store = FakePlanStore().apply { seed(confirmed(1)) }
        val routed = KindRoutedPlanStepHandler(mapOf(PlanStepKind.READ_FILE to RecordingHandler { s, _ -> "S${s.index}" }))
        val plan = PlanCoordinator(FakeModelPort(), store, routed)

        val events = plan.execute(taskId, revision = 1).toList()

        val failed = events.filterIsInstance<PlanRunEvent.StepFailed>().single()
        assertThat(failed.index).isEqualTo(1) // 第 1 步是 pdf_extract，没注册
        assertThat(failed.error.code).isEqualTo(AppError.CODE_UNSUPPORTED)
    }

    // ─────────────────────────── fakes ───────────────────────────

    private fun coordinator(
        model: FakeModelPort,
        store: PlanStorePort,
        handler: PlanStepHandler = RecordingHandler { s, _ -> "S${s.index}" },
    ) = PlanCoordinator(model = model, plans = store, steps = handler)

    private fun confirmed(revision: Int) = PlanRevision(
        taskId = taskId,
        revision = revision,
        goal = "把这份 PDF 的重点整理出来",
        steps = listOf(
            PlanStep(0, PlanStepKind.READ_FILE, "这份 PDF"),
            PlanStep(1, PlanStepKind.EXTRACT, "正文"),
            PlanStep(2, PlanStepKind.SUMMARIZE, "重点"),
        ),
        status = PlanStatus.CONFIRMED,
    )

    private class RecordingHandler(
        private val block: (PlanStep, PlanStepContext) -> String,
    ) : PlanStepHandler {
        var calls: Int = 0
        val seenUpstream: MutableList<String> = ArrayList()

        override suspend fun execute(
            step: PlanStep,
            ctx: PlanStepContext,
        ): Outcome<PlanStepOutput> {
            calls++
            seenUpstream += ctx.upstream
            return Outcome.Ok(PlanStepOutput(block(step, ctx)))
        }
    }

    private class FakePlanStore : PlanStorePort {
        val saved: MutableList<PlanRevision> = ArrayList()
        private val latest = MutableStateFlow<PlanRevision?>(null)
        private var next = 1

        fun seed(revision: PlanRevision) {
            latest.value = revision
            next = revision.revision + 1
        }

        override suspend fun saveDraft(
            taskId: TaskId,
            goal: String,
            steps: List<PlanStep>,
        ): PlanRevision {
            val rev = PlanRevision(taskId, next++, goal, steps, PlanStatus.DRAFT)
            saved += rev
            latest.value = rev
            return rev
        }

        override suspend fun confirm(taskId: TaskId, revision: Int): Boolean {
            val cur = latest.value ?: return false
            if (cur.revision != revision || cur.status != PlanStatus.DRAFT) return false
            latest.value = cur.copy(status = PlanStatus.CONFIRMED)
            return true
        }

        override suspend fun latest(taskId: TaskId): PlanRevision? = latest.value

        override fun observeLatest(taskId: TaskId): Flow<PlanRevision?> = latest.asStateFlow()
    }
}
