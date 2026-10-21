package com.ake.agent.context

import com.ake.core.contract.PocketDom
import com.ake.core.contract.StepRecord
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.ToolSpec
import com.ake.agent.planner.SystemCharterText
import com.ake.core.ports.ContentSource

/**
 * Prompt 分段组装器（架构 §4.6 + §8.2 闸 2）。
 *
 * 契约（单测必须卡住的三条）：
 *  1. **任何任务、任何步数下 `sections.totalTokens() <= budget.usablePromptTokens`。**
 *     这是 A7 验收项，也是唯一能防住「长任务第 20 步 OOM」的机制。
 *  2. SYSTEM / TASK / OUTPUT_RESERVE 永不动刀。
 *  3. 降级顺序固定：RECENT_TRACE → HISTORY_COMPACT → TOOL_RETURN → OBSERVATION(Top-K) → SCRATCHPAD → TASK。
 *
 * 全程不调用模型 —— 压缩是确定性的（§4.5）。
 */
class ContextAssembler(
    /** 必须本地估算（ModelPort.estimateTokens），禁止跑推理 */
    private val estimate: (String) -> Int,
    private val isolator: InjectionIsolator,
    private val domSerializer: PocketDomSerializer = PocketDomSerializer(),
    private val charter: SystemCharterText,
) {

    fun assemble(req: AssembleRequest): PromptSections {
        val caps = req.budget.caps
        var state = DegradeState.initial(req.observationLevel)

        // 最多 12 轮降级：6 个可动段 × 最多 2 级，留足余量；正常 2–3 轮内必然收敛
        repeat(MAX_DEGRADE_ROUNDS) {
            val built = build(req, caps, state)
            if (built.totalTokens() <= req.budget.usablePromptTokens) return built
            val next = state.escalate(caps.degradationOrder) ?: return built.hardClamp(req.budget.usablePromptTokens, estimate)
            state = next
        }
        return build(req, caps, state)
    }

    private fun build(req: AssembleRequest, caps: SegmentCaps, st: DegradeState): PromptSections {
        val truncated = mutableSetOf<SegmentId>()

        // ── SYSTEM：永不可裁剪。超了是配置错误，直接断言式降级（只裁工具 schema 段）
        val systemText = charter.render(req.enabledTools, req.stepIndex)
        val system = Section(SegmentId.SYSTEM, systemText, estimate(systemText), 0, trusted = true)

        // ── TASK：trusted。不可裁，只在极端情况下裁掉「flavor 补充」部分
        val taskText = buildTask(req, st.taskLevel)
        val task = Section(SegmentId.TASK, taskText, estimate(taskText), st.taskLevel, trusted = true)
        if (st.taskLevel > 0) truncated += SegmentId.TASK

        // ── OBSERVATION：不可信，逐条围栏
        val obs = buildObservation(req, caps, st.observationLevel)
        if (obs.truncated || st.observationLevel > 0) truncated += SegmentId.OBSERVATION

        // ── TOOL_RETURN：不可信
        val tr = buildToolReturn(req, caps, st.toolReturnLevel)
        if (st.toolReturnLevel > 0) truncated += SegmentId.TOOL_RETURN

        // ── HISTORY_COMPACT：我们自己的轨迹，可信（内容已脱敏）
        val hist = buildHistory(req, caps, st.historyLevel)
        if (st.historyLevel > 0) truncated += SegmentId.HISTORY_COMPACT

        // ── RECENT_TRACE：最近 3 步原文锚点
        val recent = buildRecent(req, caps, st.recentLevel)
        if (st.recentLevel > 0) truncated += SegmentId.RECENT_TRACE

        // ── SCRATCHPAD：模型自己的便签。它是模型写的 ⇒ 视作半可信，但仍要过闸 1
        val scratch = buildScratch(req, caps, st.scratchLevel)
        if (st.scratchLevel > 0) truncated += SegmentId.SCRATCHPAD

        val total = system.tokens + task.tokens + obs.tokens + tr.tokens + hist.tokens + recent.tokens + scratch.tokens
        val meta = PromptMeta(
            truncated = truncated,
            remainTokens = (req.budget.usablePromptTokens - total).coerceAtLeast(0),
            step = req.stepIndex,
            remainingBudgetPct = req.remainingBudgetPct,
            compactionLevel = st.historyLevel,
        )
        return PromptSections(system, task, obs, tr, hist, recent, scratch, meta)
    }

    // ──────────────────────────────────────────────────────────── 各段构造

    private fun buildTask(req: AssembleRequest, level: Int): String = buildString {
        append("goal: ").append(req.task.goal).append('\n')
        val criteria = req.task.successCriteria.describe()
        if (criteria.isNotBlank()) append("success_criteria: ").append(criteria).append('\n')
        if (level == 0) {
            req.flavorHint?.let { append("hint: ").append(it).append('\n') }
            req.knownFacts.forEach { append("known: ").append(it).append('\n') }
        }
    }

    private fun buildObservation(req: AssembleRequest, caps: SegmentCaps, level: Int): Section {
        val dom = req.dom
        if (dom == null) {
            val t = "(本轮无页面观察：感知超时或页面不可用)"
            return Section(SegmentId.OBSERVATION, t, estimate(t), 4)
        }
        val raw = when (level) {
            0 -> domSerializer.render(dom, caps.topKFor(0), recordRows = req.recordRows)
            1 -> domSerializer.render(dom, caps.topKFor(1), recordRows = 1)
            2 -> domSerializer.render(dom, caps.topKFor(2), recordRows = 0)
            else -> domSerializer.skeleton(dom)
        }
        val iso = isolator.isolate(raw, ContentSource.PAGE, SegmentId.OBSERVATION.tag)
        val text = iso.rendered
        val tokens = estimate(text)
        return if (tokens > caps.observation) {
            // 兜底：尾部截断（保留最近的信息最有用）
            Section(SegmentId.OBSERVATION, tailCut(text, caps.observation, estimate), caps.observation, level, truncated = true)
        } else {
            Section(SegmentId.OBSERVATION, text, tokens, level)
        }.let { s ->
            if (iso.escalation != Escalation.NONE) s.copy(truncated = true) else s
        }
    }

    private fun buildToolReturn(req: AssembleRequest, caps: SegmentCaps, level: Int): Section {
        val raw = req.toolReturn
        if (raw.isNullOrBlank() || level >= 2) return Section.empty(SegmentId.TOOL_RETURN)
        val cap = if (level == 0) caps.toolReturn else caps.toolReturn / 3
        val iso = isolator.isolate(raw, ContentSource.TOOL_RETURN, SegmentId.TOOL_RETURN.tag)
        val text = if (estimate(iso.rendered) > cap) tailCut(iso.rendered, cap, estimate) else iso.rendered
        return Section(SegmentId.TOOL_RETURN, text, estimate(text), level, truncated = true)
    }

    private fun buildHistory(req: AssembleRequest, caps: SegmentCaps, level: Int): Section {
        if (level >= 3 || req.trace.isEmpty()) return Section.empty(SegmentId.HISTORY_COMPACT, trusted = true)
        val cap = caps.historyCompact / if (level == 0) 1 else 2
        val res = if (level <= 1) {
            DeterministicCompactor.compactToBudget(req.trace, cap, estimate)
        } else {
            DeterministicCompactor.skeletonToBudget(req.trace, cap, estimate)
        }
        val text = res.render()
        return Section(SegmentId.HISTORY_COMPACT, text, estimate(text), level.coerceAtLeast(1), trusted = true, truncated = res.isTruncated)
    }

    private fun buildRecent(req: AssembleRequest, caps: SegmentCaps, level: Int): Section {
        if (level >= 1 || req.recentRaw.isEmpty()) return Section.empty(SegmentId.RECENT_TRACE, trusted = true)
        val text = req.recentRaw.joinToString("\n") { "#${it.first}: ${it.second}" }
        val cap = caps.recentTrace
        val out = if (estimate(text) > cap) tailCut(text, cap, estimate) else text
        return Section(SegmentId.RECENT_TRACE, out, estimate(out), 0, trusted = true)
    }

    private fun buildScratch(req: AssembleRequest, caps: SegmentCaps, level: Int): Section {
        if (level >= 2 || req.scratchpad.isBlank()) return Section.empty(SegmentId.SCRATCHPAD, trusted = true)
        val cap = caps.scratchpad / if (level == 0) 1 else 2
        val text = if (estimate(req.scratchpad) > cap) tailCut(req.scratchpad, cap, estimate) else req.scratchpad
        return Section(SegmentId.SCRATCHPAD, text, estimate(text), level, trusted = true)
    }

    // ──────────────────────────────────────────────────────────── 工具

    /** §4.2 L1：**保留尾部**，头部留省略标记。砍头比砍尾危险得多。 */
    private fun tailCut(text: String, tokenCap: Int, est: (String) -> Int): String {
        if (est(text) <= tokenCap) return text
        val chars = text.length
        var lo = 0
        var hi = chars
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (est(text.substring(chars - mid)) <= tokenCap) lo = mid else hi = mid - 1
        }
        val omitted = est(text.substring(0, chars - lo))
        return "…[省略约 $omitted token]\n" + text.substring(chars - lo)
    }

    companion object {
        const val MAX_DEGRADE_ROUNDS = 12
        const val MAX_ENABLED_TOOLS = 12   // §7.2 硬限制
    }
}

/**
 * 单次组装的输入。刻意做成不可变的纯数据 —— 便于黄金轨迹回放与单测。
 */
data class AssembleRequest(
    val task: TaskSpec,
    /** 本轮工作窗口预算。决定各段 cap 与降级阶梯。 */
    val budget: ContextBudget,
    val flavorHint: String?,
    val knownFacts: List<String> = emptyList(),
    val dom: PocketDom?,
    val trace: List<StepRecord>,
    val recentRaw: List<Pair<Int, String>>,          // (seq, 已脱敏的模型输出)
    val scratchpad: String,
    val toolReturn: String?,
    val enabledTools: List<ToolSpec>,
    val stepIndex: Int,
    val remainingBudgetPct: Int,
    val recordRows: Int = 3,
    val observationLevel: Int = 0,
)

/**
 * 降级状态机。每段独立记 level，按 [SegmentCaps.degradationOrder] 依次动刀。
 */
private data class DegradeState(
    val recentLevel: Int,
    val historyLevel: Int,
    val toolReturnLevel: Int,
    val observationLevel: Int,
    val scratchLevel: Int,
    val taskLevel: Int,
) {
    fun escalate(order: List<SegmentId>): DegradeState? {
        for (id in order) {
            val (cur, max) = when (id) {
                SegmentId.RECENT_TRACE -> recentLevel to 1
                SegmentId.HISTORY_COMPACT -> historyLevel to 3
                SegmentId.TOOL_RETURN -> toolReturnLevel to 2
                SegmentId.OBSERVATION -> observationLevel to 3
                SegmentId.SCRATCHPAD -> scratchLevel to 2
                SegmentId.TASK -> taskLevel to 1
                else -> continue
            }
            if (cur < max) return copy(id, cur + 1)
        }
        return null
    }

    private fun copy(id: SegmentId, v: Int): DegradeState = when (id) {
        SegmentId.RECENT_TRACE -> copy(recentLevel = v)
        SegmentId.HISTORY_COMPACT -> copy(historyLevel = v)
        SegmentId.TOOL_RETURN -> copy(toolReturnLevel = v)
        SegmentId.OBSERVATION -> copy(observationLevel = v)
        SegmentId.SCRATCHPAD -> copy(scratchLevel = v)
        SegmentId.TASK -> copy(taskLevel = v)
        else -> this
    }

    companion object {
        fun initial(observationLevel: Int) = DegradeState(
            recentLevel = 0, historyLevel = 0, toolReturnLevel = 0,
            observationLevel = observationLevel, scratchLevel = 0, taskLevel = 0,
        )
    }
}

/** 极端兜底：降级全部走完仍超预算时，按优先级从低到高整段丢弃。只在配置错误时触发。 */
private fun PromptSections.hardClamp(usable: Int, est: (String) -> Int): PromptSections {
    var s = this
    val dropOrder = listOf(
        { x: PromptSections -> x.copy(scratchpad = Section.empty(SegmentId.SCRATCHPAD, trusted = true)) },
        { x: PromptSections -> x.copy(recentTrace = Section.empty(SegmentId.RECENT_TRACE, trusted = true)) },
        { x: PromptSections -> x.copy(toolReturn = Section.empty(SegmentId.TOOL_RETURN)) },
        { x: PromptSections -> x.copy(history = Section.empty(SegmentId.HISTORY_COMPACT, trusted = true)) },
    )
    for (d in dropOrder) {
        if (s.totalTokens() <= usable) break
        s = d(s)
    }
    if (s.totalTokens() > usable) {
        val obs = s.observation
        val text = obs.text
        val keep = (text.length * usable / s.totalTokens()).coerceAtLeast(0)
        s = s.copy(observation = obs.copy(text = text.substring(text.length - keep), tokens = est(text.substring(text.length - keep))))
    }
    return s
}
