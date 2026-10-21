package com.ake.agent.ports

import com.ake.agent.guard.SensitiveActionPolicy
import com.ake.agent.loop.LoopBudget
import com.ake.core.contract.RiskClass
import com.ake.core.contract.StepAction
import com.ake.core.ports.ActionRiskPort
import com.ake.core.ports.LoopBudgetFacts
import com.ake.core.ports.LoopBudgetPort
import com.ake.core.ports.MemoryTier
import com.ake.core.ports.UsageTotals

/**
 * `:agent` 对「面向 UI 的那组 Port」的实现（2026-10 `:core:ports` 收口）。
 *
 * 这里只有**实现**，没有契约 —— 契约在 `core/ports/Runtime.kt`。
 * 之所以要这两个实现，是因为 `:ui` 需要预算档位与风险分级，
 * 而这两件事的答案都只能由本模块给出（预算是主循环的账，风险归护栏判）。
 *
 * ★ 两者刻意都是 `object`：它们没有任何状态，也不持有任何有生命周期的资源。
 * 做成可实例化的类只会凭空多出一个「什么时候构造」的问题。
 */

/**
 * 预算只读查询。答案仍由 [LoopBudget.forTier] 给出 —— [LoopBudgetFacts] 只是它的一张
 * **投影**，把带降级阶梯、分段配额的实现细节挡在外面。
 *
 * ★ 这张投影是「同一份数字的两种看法」，不是两套预算：
 * 投影的存在意味着 UI 拿到的 `maxSteps` 与主循环实际执行的 `maxSteps`
 * **不可能不同步**（它们来自同一个 `LoopBudget` 实例）。如果 UI 自己存一份预算表，
 * 就会出现「设置页显示 25 步、任务跑 40 步」这种最难排查的不一致。
 */
public object AgentLoopBudgetPort : LoopBudgetPort {

    override fun forTier(tier: MemoryTier): LoopBudgetFacts = LoopBudget.forTier(tier).toFacts(tier)

    override fun remainingPct(tier: MemoryTier, used: UsageTotals): Int =
        LoopBudget.forTier(tier).remainingPct(used)

    private fun LoopBudget.toFacts(tier: MemoryTier): LoopBudgetFacts = LoopBudgetFacts(
        memoryTier = tier.name,
        maxSteps = maxSteps,
        maxWallClockMs = maxWallClockMs,
        perStepMs = perStepMs,
        decodeMs = decodeMs,
        actionMs = actionMs,
        maxPrefillTokens = maxPrefillTokensTotal,
        maxDecodeTokens = maxDecodeTokensTotal,
        currentTopK = currentTopK,
        workingWindowTokens = context.workingWindow,
        outputReserveTokens = context.outputReserve,
        usablePromptTokens = context.usablePromptTokens,
    )
}

/**
 * 动作的风险分级。判定**复用护栏那唯一的分类器**，这里一行判据都不写。
 *
 * ## 为什么必须是 `SensitiveActionPolicy.classify` 而不是另算
 *
 * `PolicyEngine` 记进 `PolicyDecision.riskClass` 的值来自 `PolicyEngine.riskOf(action)`
 * （`= SensitiveActionPolicy.classify(action)`）。本 Port 用同一个函数 ⇒
 * UI 确认弹窗上的风险徽标与事后写进决策记录的 `riskClass` **不可能不一致**。
 * 一旦这里走另一套算法，「弹窗说只读、记录里写着 EXTERNAL_SIDE_EFFECT」就只是时间问题。
 *
 * ### ⚠ 一个刻意的边界（供 review 确认，本轮未改）
 *
 * `PolicyEngine.checkTool()` 里另有一条独立的**拒绝**路径：
 * 若工具目录声明该 MCP 工具为 `FORBIDDEN`，则直接 Denied。
 * 那一步写在 `checkTool` 分支里、**不改变 `riskClass`**。
 * 因此本 Port 对 `StepAction.CallTool` 一律返回 `EXTERNAL_SIDE_EFFECT`，
 * 而不会显示 `FORBIDDEN` —— 这与决策记录里的口径一致。
 * 若将来 UI 需要「一眼看出这个工具是被禁的」，正解是让 `PolicyVerdict` 携带 UI 需要的字段，
 * 而不是在这里再猜一次风险。
 */
public object AgentActionRiskPort : ActionRiskPort {

    override fun classify(action: StepAction): RiskClass = SensitiveActionPolicy.classify(action)
}
