package com.ake.agent.context

/**
 * Prompt 组装产物（架构 §4.6）。
 *
 * 每一段自带降级等级 [Section.level]：L0 原文 / L1 尾部截断 / L2 确定性摘要 /
 * L3 骨架化 / L4 丢弃+打标。渲染时 [untrusted] 决定要不要套「不可信数据」围栏。
 */
data class PromptSections(
    val system: Section,
    val task: Section,
    val observation: Section,
    val toolReturn: Section,
    val history: Section,
    val recentTrace: Section,
    val scratchpad: Section,
    val meta: PromptMeta,
) {
    /**
     * 渲染顺序是刻意安排的（§4.6）：
     *   ① system（永不裁剪）
     *   ② task（trusted）—— 让「真指令」先于任何不可信数据出现
     *   ③ history / recent —— 我们自己的轨迹，可信
     *   ④ tool_result / page_content —— 不可信，且放在后 1/3，降低其对末尾注意力位的占用
     *   ⑤ notes / meta
     *
     * 为什么不可信段要往后放？端侧小模型对「末尾内容」的服从度显著高于开头。
     * 把网页内容放在安全宪章与真指令之后，是利用注意力位置做一层额外防御。
     */
    fun render(): String = buildString {
        append(system.render())
        append(task.render())
        append(history.render())
        append(recentTrace.render())
        append(toolReturn.render())
        append(observation.render())
        append(scratchpad.render())
        append(meta.render())
    }

    fun totalTokens(): Int =
        system.tokens + task.tokens + observation.tokens + toolReturn.tokens +
            history.tokens + recentTrace.tokens + scratchpad.tokens + meta.tokens
}

/**
 * @param level   L0..L4 降级等级
 * @param trusted true = 来自用户/系统；false = 来自网页或第三方工具返回
 */
data class Section(
    val id: SegmentId,
    val text: String,
    val tokens: Int,
    val level: Int = 0,
    val trusted: Boolean = false,
    val truncated: Boolean = false,
) {
    fun isEmpty(): Boolean = text.isBlank()

    fun render(): String {
        if (isEmpty()) return ""
        return buildString {
            append('<').append(id.tag)
            if (trusted) append(" trusted")
            append(">\n")
            append(text)
            if (!text.endsWith('\n')) append('\n')
            append("</").append(id.tag).append(">\n\n")
        }
    }

    companion object {
        fun empty(id: SegmentId, trusted: Boolean = false) =
            Section(id, "", 0, level = 4, trusted = trusted)
    }
}

/**
 * 让模型感知预算（§4.6 注）：明确告知「你还剩 40% 预算」能显著减少它在早中期做无谓探索。
 * [truncated] 告知哪些段被裁过 —— 避免模型把「历史缺失」误判为「自己没做过」。
 */
data class PromptMeta(
    val truncated: Set<SegmentId>,
    val remainTokens: Int,
    val step: Int,
    val remainingBudgetPct: Int,
    val compactionLevel: Int = 0,
) {
    val tokens: Int = 48

    fun render(): String = buildString {
        append("<meta>\n")
        append("step=").append(step)
        append(" budget_remaining_pct=").append(remainingBudgetPct)
        append(" prompt_tokens_remaining=").append(remainTokens).append('\n')
        if (truncated.isNotEmpty()) {
            append("truncated_segments=")
            append(truncated.joinToString(",") { it.tag }).append('\n')
        }
        if (compactionLevel > 0) append("history_compaction_level=").append(compactionLevel).append('\n')
        append("</meta>\n\n")
    }
}
