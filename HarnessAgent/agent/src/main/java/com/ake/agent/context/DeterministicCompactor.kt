package com.ake.agent.context

import com.ake.core.contract.StepAction
import com.ake.core.contract.StepRecord
import com.ake.core.contract.ToolResult

/**
 * 历史压缩 —— 确定性折算，**绝不调用模型做摘要**（架构 §4.5）。
 *
 * 反直觉但必须记住的账：把 1,536 token 历史压到 400 token，需要 ~800 prefill + ~300 decode
 * ≈ GPU 6 秒；而它省下的是后续每步少 ~1,100 prefill ≈ 0.29 秒/步。**要 20 步才回本**，
 * 而我们通常只跑 8–25 步。这是端侧语境下一次典型的「为了省小钱花大钱」。
 *
 * 每一步天然是结构化的，所以压成一行就够了：
 *
 *   #3 click([12]销量)→ok [dom+18]  #4 extract(.product-card)→ok [rec]20
 *   #5 type([7]搜索框,"xx")→ERR [err]STALE #6 //WARN: 元素[7]已重绘，编号失效
 *
 * 约 15–25 token/步，50 步历史只要 ~1,000 token，**实时、零延迟、可预测、可被单测覆盖**。
 *
 * 代价要说清楚：确定性摘要会丢语义细节。补偿手段是 SCRATCHPAD —— 给模型一个 256 token 的便签，
 * 由它自己决定记什么，由 AgentLoop 决定能记多少（控制权仍在代码手里）。
 */
object DeterministicCompactor {

    /** L2：结构化记录 → 一行紧凑文本 */
    fun compactLine(r: StepRecord): String = buildString {
        append('#').append(r.index).append(' ')
        append(compactAction(r.action))
        append(if (r.result.isOk) "→ok" else "→ERR")
        append(' ')
        append(compactResult(r.result))
        // 只保留对后续决策有用的失败教训；正例不需要展开
        if (!r.result.isOk) {
            val hint = r.result.hintBrief()
            if (hint.isNotBlank()) append(" //WARN: ").append(hint)
        }
    }

    /**
     * 不依赖完整 `StepRecord` 的压缩行（§4.5）。
     *
     * 供 `:data` 写 `step.action_summary` 使用 —— 那里手上只有 (seq, action, result)，
     * 没有 StepId / TaskId。**不要为此去构造假的 id**：
     * id 是我们唯一能定位一行的东西，伪造它比少一个字段危险得多。
     */
    fun compactLine(index: Int, action: StepAction, result: ToolResult): String = buildString {
        append('#').append(index).append(' ')
        append(compactAction(action))
        append(if (result.isOk) "→ok" else "→ERR")
        append(' ')
        append(compactResult(result))
        if (!result.isOk) {
            val hint = result.hintBrief()
            if (hint.isNotBlank()) append(" //WARN: ").append(hint)
        }
    }

    fun compactAction(a: StepAction): String = buildString {
        append(a.tool)
        append('(').append(a.compactArgs()).append(')')
    }

    private fun compactResult(r: ToolResult): String = when (r) {
        is ToolResult.DomChanged -> "[dom+${r.addedCandidates}]"
        is ToolResult.UrlMoved -> "[url]${r.host}"
        is ToolResult.RecordsGot -> "[rec]${r.count}"
        is ToolResult.Noop -> "[noop]"
        is ToolResult.Error -> "[err]${r.code.shortName}"
        is ToolResult.Text -> "[ok]"
    }

    /**
     * 在给定 token 预算内塞进尽可能多的历史行。
     *
     * 「保留尾部」是硬要求（§4.2 L1）：Agent 场景里最后 2 步决定性影响下一步，
     * 而更早的步骤已被 [compactLine] 压成一行。**砍头比砍尾安全得多。**
     */
    fun compactToBudget(
        records: List<StepRecord>,
        tokenCap: Int,
        estimate: (String) -> Int,
    ): CompactedHistory {
        if (records.isEmpty() || tokenCap <= 0) return CompactedHistory(emptyList(), 0, 0)
        val lines = records.map(::compactLine)
        val kept = ArrayDeque<String>()
        var used = 0
        var dropped = 0
        for (line in lines.asReversed()) {          // 从最新往回装
            val cost = estimate(line) + 1           // +1 换行
            if (used + cost > tokenCap) { dropped++; continue }
            kept.addFirst(line)
            used += cost
        }
        return CompactedHistory(kept.toList(), used, dropped)
    }

    /** L3 骨架化：只留 `[step] tool(args)`，彻底丢弃返回体 */
    fun skeleton(r: StepRecord): String = "#${r.index} ${r.action.tool}(${r.action.compactArgs()})"

    fun skeletonToBudget(
        records: List<StepRecord>,
        tokenCap: Int,
        estimate: (String) -> Int,
    ): CompactedHistory {
        if (records.isEmpty() || tokenCap <= 0) return CompactedHistory(emptyList(), 0, records.size)
        val lines = records.map(::skeleton)
        val kept = ArrayDeque<String>()
        var used = 0
        var dropped = 0
        for (line in lines.asReversed()) {
            val cost = estimate(line) + 1
            if (used + cost > tokenCap) { dropped++; continue }
            kept.addFirst(line)
            used += cost
        }
        return CompactedHistory(kept.toList(), used, dropped)
    }
}

data class CompactedHistory(
    val lines: List<String>,
    val tokens: Int,
    val droppedSteps: Int,
) {
    val isTruncated: Boolean get() = droppedSteps > 0
    fun render(): String = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}
