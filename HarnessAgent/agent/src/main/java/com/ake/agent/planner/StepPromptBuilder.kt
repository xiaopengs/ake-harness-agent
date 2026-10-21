package com.ake.agent.planner

import com.ake.agent.context.AssembleRequest
import com.ake.agent.context.ContextBudget
import com.ake.agent.context.ContextAssembler
import com.ake.agent.context.PromptSections
import com.ake.core.contract.PocketDom
import com.ake.core.contract.StepRecord
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.ToolSpec

/**
 * Step prompt 构造（架构 §3.2 第 2 环的编排侧）。
 *
 * 它的职责除了「拼字符串」，还有两件容易被漏掉的事：
 *  - **把重绘 WARN 插进 recentTrace**（§5.6 协议 2）：模型沿用旧编号是 DOM 类 Agent 的第一大失败原因，
 *    必须显式告知「原 [12] 现在不是 [12] 了」。
 *  - **把上一轮的 RepairHint 插进去**（§8.6）：让修正信息出现在模型看得见的位置。
 */
class StepPromptBuilder(
    private val assembler: ContextAssembler,
) {
    fun build(input: StepPromptInput): PromptSections {
        val req = AssembleRequest(
            task = input.task,
            budget = input.budget,
            flavorHint = FlavorPrompts.of(input.flavor),
            knownFacts = input.knownFacts,
            dom = input.dom,
            trace = input.trace,
            recentRaw = buildRecent(input),
            scratchpad = input.scratchpad,
            toolReturn = input.toolReturn,
            enabledTools = input.enabledTools,
            stepIndex = input.stepIndex,
            remainingBudgetPct = input.remainingBudgetPct,
            recordRows = input.recordRows,
            observationLevel = input.observationLevel,
        )
        return assembler.assemble(req)
    }

    private fun buildRecent(input: StepPromptInput): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(input.recentRaw.size + 4)
        out.addAll(input.recentRaw)
        // 重绘 / 编号失效警告：必须放在 recent 段的最前面（离决策最近）
        input.remapWarnings.forEach { w -> out += (-1 to "SYSTEM_WARN: $w") }
        input.repairHint?.let { out += (-1 to "REPAIR: $it") }
        input.pendingDenials.forEach { d -> out += (-1 to "DENIED: $d") }
        return out
    }
}

data class StepPromptInput(
    val task: TaskSpec,
    /** 本轮上下文预算。由 AgentLoop 按 MemoryTier 决定。 */
    val budget: ContextBudget = ContextBudget.STANDARD,
    val flavor: TaskScriptFlavor,
    val dom: PocketDom?,
    val trace: List<StepRecord>,
    val recentRaw: List<Pair<Int, String>>,
    val scratchpad: String,
    val toolReturn: String?,
    val enabledTools: List<ToolSpec>,
    val stepIndex: Int,
    val remainingBudgetPct: Int,
    val remapWarnings: List<String> = emptyList(),
    val repairHint: String? = null,
    val pendingDenials: List<String> = emptyList(),
    val knownFacts: List<String> = emptyList(),
    val recordRows: Int = 3,
    val observationLevel: Int = 0,
)
