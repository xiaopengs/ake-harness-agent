package com.ake.agent.context

import com.ake.agent.fixtures.execResult
import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.common.Url
import com.ake.core.contract.ErrorCode
import com.ake.core.contract.ExecutionResult
import com.ake.core.contract.PageChangeKind
import com.ake.core.contract.StepAction
import com.ake.core.contract.StepRecord
import com.ake.core.contract.StepStatus
import com.ake.core.contract.ToolResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `DeterministicCompactor` 的回归测试（2026-10）。
 *
 * ## 它守的那笔账
 *
 * 反直觉但必须记住：把 1,536 token 历史压到 400 token，需要 ~800 prefill + ~300 decode
 * ≈ GPU 6 秒；而它省下的是后续每步少 ~1,100 prefill ≈ 0.29 秒/步。
 * **要 20 步才回本，而我们通常只跑 8–25 步。** 这是端侧语境下一次典型的
 * 「为了省小钱花大钱」—— 所以压缩必须是**确定性折算，绝不调用模型做摘要**。
 *
 * 每一步天然是结构化的，压成一行约 15–25 token/步，50 步历史只要 ~1,000 token。
 *
 * ## 三条必须被卡住的契约
 *
 *  1. **保留尾部**：最后 2 步决定性影响下一步，砍头比砍尾安全得多；
 *  2. **失败步要带教训**：只保留对后续决策有用的失败信息，正例不展开；
 *  3. **`compactLine(index, action, result)` 与 `compactLine(record)` 同口径**：
 *     `:data` 写 `step.action_summary` 用的是前者（那里没有 StepId / TaskId），
 *     执行期用的是后者，两者分叉会让「回放的行」与「当时看到的行」对不上。
 */
class DeterministicCompactorTest {

    private fun record(
        index: Int,
        action: StepAction,
        result: ToolResult,
        repairAttempts: Int = 0,
    ): StepRecord = StepRecord(
        id = StepId.new(),
        taskId = TaskId("task-c"),
        index = index,
        createdAtMillis = 1_000L + index,
        action = action,
        result = result,
        status = if (result.isOk) StepStatus.OK else StepStatus.ERROR,
        repairAttempts = repairAttempts,
        errorCode = (result as? ToolResult.Error)?.code?.shortName,
    )

    // ──────────────────────────────────────────── ① 单行压缩

    @Test
    fun `成功行只留动作与结果摘要，不展开`() {
        val line = DeterministicCompactor.compactLine(record(3, StepAction.Click(12), ToolResult.DomChanged(18)))
        assertThat(line).isEqualTo("#3 click([12])→ok [dom+18]")
    }

    @Test
    fun `失败行会带上给模型的教训`() {
        val line = DeterministicCompactor.compactLine(record(5, StepAction.Click(7), ToolResult.Error(ErrorCode.STALE)))
        assertThat(line).startsWith("#5 click([7])→ERR [err]STALE")
        // 「为什么失败」正是模型下一步最需要的信息 —— 没有它模型只会原地重试
        assertThat(line).contains("//WARN:")
        assertThat(line).contains("元素已失效")
    }

    @Test
    fun `结果类型的穷尽映射都对得上`() {
        assertThat(DeterministicCompactor.compactLine(record(0, StepAction.Scroll("down", 800), ToolResult.UrlMoved("b.example.com"))))
            .contains("[url]b.example.com")
        assertThat(DeterministicCompactor.compactLine(record(0, StepAction.Extract(".c", emptyList(), 20), ToolResult.RecordsGot(20))))
            .contains("[rec]20")
        assertThat(DeterministicCompactor.compactLine(record(0, StepAction.Scroll("down", 800), ToolResult.Noop)))
            .contains("[noop]")
        assertThat(DeterministicCompactor.compactLine(record(0, StepAction.AskHuman("q"), ToolResult.Text("ok"))))
            .contains("[ok]")
    }

    @Test
    fun `无 id 的压缩行与完整行的口径完全一致`() {
        val a = StepAction.Click(12)
        val r = ToolResult.DomChanged(18)
        val full = DeterministicCompactor.compactLine(record(3, a, r))
        val brief = DeterministicCompactor.compactLine(3, a, r)
        assertThat(brief).isEqualTo(full)
    }

    @Test
    fun `compactAction 用工具名加紧凑参数`() {
        assertThat(DeterministicCompactor.compactAction(StepAction.Click(12))).isEqualTo("click([12])")
        assertThat(DeterministicCompactor.compactAction(StepAction.Type(7, "无线鼠标")))
            .isEqualTo("""type([7],"无线鼠标")""")
        // 长文本必须被截断 —— 它进的是 RECENT_TRACE 的固定预算
        assertThat(DeterministicCompactor.compactAction(StepAction.Type(7, "x".repeat(200))).length)
            .isLessThan(40)
    }

    // ──────────────────────────────────────────── ② 预算内装配

    private val estimate: (String) -> Int = { (it.length + 3) / 4 }

    @Test
    fun `预算不足时砍头保尾`() {
        val history = (0 until 10).map { record(it, StepAction.Click(it), ToolResult.DomChanged(1)) }
        val out = DeterministicCompactor.compactToBudget(history, tokenCap = 40, estimate = estimate)

        assertThat(out.isTruncated).isTrue()
        assertThat(out.droppedSteps).isGreaterThan(0)
        // ★ 保留的是**尾部**：最后一步一定在
        assertThat(out.lines.last()).contains("#9")
        assertThat(out.lines.first()).doesNotContain("#0")
        // 剩下的仍按原顺序
        assertThat(out.lines.map { it.substringBefore(' ').removePrefix("#").toInt() })
            .isInStrictOrder()
    }

    @Test
    fun `预算充足时一步不丢`() {
        val history = (0 until 5).map { record(it, StepAction.Click(it), ToolResult.DomChanged(1)) }
        val out = DeterministicCompactor.compactToBudget(history, tokenCap = 10_000, estimate = estimate)

        assertThat(out.droppedSteps).isEqualTo(0)
        assertThat(out.isTruncated).isFalse()
        assertThat(out.lines).hasSize(5)
    }

    @Test
    fun `空历史与非正预算都返回空结果`() {
        assertThat(DeterministicCompactor.compactToBudget(emptyList(), 100, estimate).lines).isEmpty()
        assertThat(DeterministicCompactor.compactToBudget(listOf(record(0, StepAction.Click(1), ToolResult.Noop)), 0, estimate).lines)
            .isEmpty()
    }

    @Test
    fun `token 用量不超过预算`() {
        val history = (0 until 30).map { record(it, StepAction.Click(it), ToolResult.DomChanged(1)) }
        val out = DeterministicCompactor.compactToBudget(history, tokenCap = 60, estimate = estimate)
        assertThat(out.tokens).isAtMost(60)
    }

    // ──────────────────────────────────────────── ③ L3 骨架化

    @Test
    fun `骨架化彻底丢弃返回体`() {
        val r = record(2, StepAction.Type(7, "手机"), ToolResult.RecordsGot(60))
        assertThat(DeterministicCompactor.skeleton(r)).isEqualTo("""#2 type([7],"手机")""")
        assertThat(DeterministicCompactor.skeleton(r)).doesNotContain("rec")
    }

    @Test
    fun `骨架化同样砍头保尾`() {
        val history = (0 until 10).map { record(it, StepAction.Click(it), ToolResult.DomChanged(1)) }
        val out = DeterministicCompactor.skeletonToBudget(history, tokenCap = 20, estimate = estimate)

        assertThat(out.lines.last()).contains("#9")
        assertThat(out.tokens).isAtMost(20)
    }

    @Test
    fun `render 在末尾补换行且空历史渲染为空串`() {
        val history = listOf(record(0, StepAction.Click(1), ToolResult.Noop))
        assertThat(DeterministicCompactor.compactToBudget(history, 100, estimate).render()).endsWith("\n")
        assertThat(DeterministicCompactor.compactToBudget(emptyList(), 100, estimate).render()).isEmpty()
    }
}
