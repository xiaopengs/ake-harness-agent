package com.ake.agent.context

import com.ake.core.ports.MemoryTier

/**
 * 上下文工作窗口预算（架构 §4.1 / §4.2）。
 *
 * 关键事实：Gemma 4 E2B/E4B 支持 32K 上下文，但 KV cache 每一 K token 都占内存。
 * 在「2.58 GB 模型 + WebView + App 自身」同抢 8 GB 的进程里，用满 32K 是最快的 OOM 途径。
 * 因此 32K（[MODEL_LIMIT]）只作为理论上限登记在案，MVP 按设备物理内存分三档工作窗口。
 */
data class ContextBudget(
    val workingWindow: Int,
    val outputReserve: Int,
    val caps: SegmentCaps,
) {
    val usablePromptTokens: Int get() = workingWindow - outputReserve

    companion object {
        const val MODEL_LIMIT = 32_768

        val NARROW: ContextBudget = ContextBudget(4_096, 384, SegmentCaps.NARROW)
        val STANDARD: ContextBudget = ContextBudget(8_192, 896, SegmentCaps.STANDARD)
        val WIDE: ContextBudget = ContextBudget(16_384, 1_024, SegmentCaps.WIDE)

        fun forTier(tier: MemoryTier): ContextBudget = when (tier) {
            MemoryTier.LOW -> NARROW
            MemoryTier.MID -> STANDARD
            MemoryTier.HIGH -> WIDE
        }
    }
}

/**
 * 分段配额（§4.2 分配表）。
 *
 * 三档的 cap 都是**整数且精确求和**到 usablePromptTokens，不允许出现「加起来差几十 token」
 * 这种含糊 —— 差的那几十会在长任务里变成系统段被静默挤压。
 *
 * 校验（构造期断言，单测必过）：
 *   STANDARD: 1152+256+2816+768+1536+512+256 = 7296 = 8192-896  ✅
 *   NARROW  :  576+128+1536+384+ 704+256+128 = 3712 = 4096-384  ✅
 *   WIDE    : 2304+512+6656+1536+3072+1024+256 = 15360 = 16384-1024 ✅
 */
data class SegmentCaps(
    val system: Int,
    val task: Int,
    val observation: Int,
    val toolReturn: Int,
    val historyCompact: Int,
    val recentTrace: Int,
    val scratchpad: Int,
) {
    val total: Int get() =
        system + task + observation + toolReturn + historyCompact + recentTrace + scratchpad

    /** 降级动刀顺序（§4.2）：越靠前越先被砍。SYSTEM 永不在列。 */
    val degradationOrder: List<SegmentId> = listOf(
        SegmentId.RECENT_TRACE,
        SegmentId.HISTORY_COMPACT,
        SegmentId.TOOL_RETURN,
        SegmentId.OBSERVATION,
        SegmentId.SCRATCHPAD,
        SegmentId.TASK,
    )

    fun capOf(id: SegmentId): Int = when (id) {
        SegmentId.SYSTEM -> system
        SegmentId.TASK -> task
        SegmentId.OBSERVATION -> observation
        SegmentId.TOOL_RETURN -> toolReturn
        SegmentId.HISTORY_COMPACT -> historyCompact
        SegmentId.RECENT_TRACE -> recentTrace
        SegmentId.SCRATCHPAD -> scratchpad
        SegmentId.OUTPUT_RESERVE -> 0
    }

    /** §3.4 的 OBSERVATION 降级阶梯：Top-K 48 → 24 → 12 → 6 → 骨架 */
    fun topKFor(level: Int): Int = when (level) {
        0 -> (observation / 58).coerceIn(12, 72)   // 满配：约 58 token/候选
        1 -> (observation / 58 / 2).coerceAtLeast(12)
        2 -> 12
        else -> 6
    }

    companion object {
        val NARROW = SegmentCaps(
            system = 576, task = 128, observation = 1_536, toolReturn = 384,
            historyCompact = 704, recentTrace = 256, scratchpad = 128,
        )
        val STANDARD = SegmentCaps(
            system = 1_152, task = 256, observation = 2_816, toolReturn = 768,
            historyCompact = 1_536, recentTrace = 512, scratchpad = 256,
        )
        val WIDE = SegmentCaps(
            system = 2_304, task = 512, observation = 6_656, toolReturn = 1_536,
            historyCompact = 3_072, recentTrace = 1_024, scratchpad = 256,
        )
    }
}

enum class SegmentId(val tag: String) {
    SYSTEM("system"),
    TASK("task"),
    OBSERVATION("page_content"),
    TOOL_RETURN("tool_result"),
    HISTORY_COMPACT("history"),
    RECENT_TRACE("recent"),
    SCRATCHPAD("notes"),
    OUTPUT_RESERVE("output_reserve"),
}
