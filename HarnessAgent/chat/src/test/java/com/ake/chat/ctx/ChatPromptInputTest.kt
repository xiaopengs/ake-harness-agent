package com.ake.chat.ctx

import com.ake.agent.context.ContextAssembler
import com.ake.agent.context.ContextBudget
import com.ake.agent.context.InjectionIsolator
import com.ake.chat.fixtures.RecordingPolicy
import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.contract.StepAction
import com.ake.core.contract.StepRecord
import com.ake.core.contract.ToolResult
import com.ake.core.ports.MemoryTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `ChatPromptInput` → `AssembleRequest` 的投影。
 *
 * 这组测试要证明一件事：**聊天走的就是 `:agent` 那一套组装器**。
 * 分别验证四件事：
 *  ① 系统段恒空（宪章/人格在 `SessionSpec.systemInstruction`，不进每轮 prompt）；
 *  ② 聊天历史落 `RECENT_TRACE`；
 *  ③ 任务步落 `HISTORY_COMPACT`，且压缩由 `DeterministicCompactor` 完成（不是我们重写的）；
 *  ④ 总量恒 ≤ `usablePromptTokens`（A7 在聊天场景的直接延伸）。
 */
class ChatPromptInputTest {

    private val policy = RecordingPolicy()
    private val estimate: (String) -> Int = { (it.length / 4).coerceAtLeast(1) }
    private val registry = ModeProfileRegistry()

    private fun assembler() = ContextAssembler(
        estimate = estimate,
        isolator = InjectionIsolator(policy),
        charter = FixedSystemCharter.EMPTY,
    )

    private fun step(index: Int) = StepRecord(
        id = StepId("step-$index"),
        taskId = TaskId("task-1"),
        index = index,
        createdAtMillis = index.toLong(),
        action = StepAction.Scroll("down", 800),
        result = ToolResult.DomChanged(3),
    )

    private fun input(
        kind: ModeKind,
        history: List<Pair<Int, String>> = listOf(1 to "U: 帮我搜一下无线鼠标"),
        trace: List<StepRecord> = listOf(step(1), step(2)),
        skillResult: String? = "[skill_result:abc]\n前三条：A/39 元\n[/skill_result:abc]",
    ) = ChatPromptInput(
        agentId = "agent-1",
        sessionId = "sess-1",
        turnId = "turn-1",
        userTurn = "帮我搜一下无线鼠标",
        historyLines = history,
        taskTrace = trace,
        skillResult = skillResult,
        kind = kind,
        epoch = 1,
        goal = "用自然语言回答用户最新这句话",
    )

    @Test
    fun systemSegmentIsAlwaysEmptyBecauseItLivesInSessionSpec() {
        val sections = assembler().assemble(
            input(ModeKind.CHAT).toAssembleRequest(
                ContextBudget.forTier(MemoryTier.MID),
                registry.profile(ModeKind.CHAT, MemoryTier.MID),
            ),
        )
        assertThat(sections.system.isEmpty()).isTrue()
        assertThat(sections.render()).doesNotContain("<system")
    }

    @Test
    fun chatHistoryLandsInTheRecentSegment() {
        val sections = assembler().assemble(
            input(ModeKind.CHAT).toAssembleRequest(
                ContextBudget.forTier(MemoryTier.MID),
                registry.profile(ModeKind.CHAT, MemoryTier.MID),
            ),
        )
        assertThat(sections.recentTrace.text).contains("帮我搜一下无线鼠标")
    }

    @Test
    fun taskStepsAreCompactedIntoTheHistorySegmentByTheAgentCompactor() {
        val sections = assembler().assemble(
            input(ModeKind.TASK_IN_CHAT).toAssembleRequest(
                ContextBudget.forTier(MemoryTier.MID),
                registry.profile(ModeKind.TASK_IN_CHAT, MemoryTier.MID),
            ),
        )
        // DeterministicCompactor 的形态：`#<index> tool(args)→ok [dom+n]`
        assertThat(sections.history.text).contains("#1")
        assertThat(sections.history.text).contains("scroll(down,800)")
        assertThat(sections.history.text).contains("→ok")
    }

    @Test
    fun skillResultGoesThroughTheInjectorFence() {
        val sections = assembler().assemble(
            input(ModeKind.TASK_IN_CHAT).toAssembleRequest(
                ContextBudget.forTier(MemoryTier.MID),
                registry.profile(ModeKind.TASK_IN_CHAT, MemoryTier.MID),
            ),
        )
        // 不可信段必须落在 tool_result 里，并且已经过闸 2（带 nonce，尖括号被中和）
        assertThat(sections.toolReturn.text).contains("tool_result")
        assertThat(sections.toolReturn.trusted).isFalse()
    }

    @Test
    fun totalTokensNeverExceedsUsablePromptTokens() {
        // 塞满：长历史 + 长技能结果 + 多个任务步
        val bigHistory = (1..40).map { it to ("U: " + "历".repeat(80)) }
        val bigTrace = (1..30).map { step(it) }
        for (kind in ModeKind.entries) {
            for (tier in MemoryTier.entries) {
                val profile = registry.profile(kind, tier)
                val budget = ContextBudget.forTier(tier)
                val sections = assembler().assemble(
                    input(kind, bigHistory, bigTrace, "[skill_result:x]\n" + "结".repeat(2_000)).toAssembleRequest(
                        budget, profile,
                    ),
                )
                assertThat(sections.totalTokens()).isAtMost(budget.usablePromptTokens)
            }
        }
    }

    @Test
    fun promptTaskSpecUsesAChatPrefixedId() {
        val req = input(ModeKind.CHAT).toAssembleRequest(
            ContextBudget.forTier(MemoryTier.MID),
            registry.profile(ModeKind.CHAT, MemoryTier.MID),
        )
        // 一眼能看出「这不是真任务」，防止它被误传给 TaskRunnerPort
        assertThat(req.task.id.value).isEqualTo("chat:turn-1")
        assertThat(req.task.goal).isEqualTo("用自然语言回答用户最新这句话")
    }
}
