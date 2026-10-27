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

    /**
     * §3.4 的 OBSERVATION 降级阶梯：Top-K K → K/2 → 12 → 6 → 骨架
     * （K = `observation / WORST_CASE_TOKENS_PER_CANDIDATE`，不再是写死的 48）。
     */
    fun topKFor(level: Int): Int = when (level) {
        0 -> (observation / WORST_CASE_TOKENS_PER_CANDIDATE).coerceIn(MIN_TOP_K, MAX_TOP_K)
        1 -> (observation / WORST_CASE_TOKENS_PER_CANDIDATE / 2).coerceAtLeast(MIN_TOP_K)
        2 -> MIN_TOP_K
        else -> 6
    }

    companion object {

        /**
         * ★★ 单个候选进 prompt 的**最坏** token 成本 —— **110**（拉丁 / ASCII 内容）。
         *
         * 这个值**不是估的**，是量出来的，由 `ContextBudgetTest` 用最坏候选机械重算：
         *
         *   最坏候选行（`PocketDomSerializer.renderCandidate`，唯一真会进 prompt 的那条路径）
         *     `  [48] textarea[<type 48>] "<text 48>" ph="<48>" value="<48>" aria-label="<48>"`
         *     `     ::type,submit` + `\n`
         *   = **308 字符** ⇒ 按 `:llm/TokenEstimator` 的口径
         *     （拉丁 3.6 chars/token、数字 2.0、+6 token/100 字符、`×1.06` 保守系数）
         *     = **110 token**。
         *
         * ## 为什么 58 是错的
         *
         * 58 反推回去只有 160–210 字符/候选 —— 大约等于「只有文本、没有属性」的一条候选行。
         * 而 `PocketDomSerializer` 实际渲染 **4 个**属性字段（`type` / `placeholder` /
         * `value` / `aria-label`），每个上限 `MAX_TEXT = 48` 字符 ⇒ 光属性就是 192 字符。
         * 于是 `48 条 × 110 = 5,280 token`，而 STANDARD 的 `observation` 只有 2,816
         * ⇒ **超编 1.88×**；NARROW（1,536）超编 3.4×。
         *
         * ## 为什么能错这么久
         *
         * 因为这个数字**没有任何测试** —— 它是 `2816 / 48 ≈ 58` 反推出来的
         * （为了在 STANDARD 上凑出「Top-K 48」这个文档阶梯值），而不是由候选体积推出来的。
         * 现在它由 `ContextBudgetTest` 的 4 条用例钉住：改渲染格式 / 改 `MAX_TEXT` /
         * 改 `:llm` 的 token 口径，这里立刻红。
         *
         * ## 诚实边界
         *
         *   · **CJK 内容更贵**：`TokenEstimator` 对中日韩字符按 1 token/字 计，
         *     全 CJK 的最坏候选是 **294** token/候选（同一个 308 字符）。
         *     任何有限的 `topK` 都挡不住它（1 条就可能 257），那部分的收口在
         *     `ContextAssembler.buildObservation` 的 `tailCut` —— OBSERVATION 段
         *     最终被硬截到 `caps.observation`，所以**总 prompt 不会击穿工作窗口**；
         *     `topKFor` 负责的是「别让截断成为常态」。
         *   · `InjectionIsolator.isolateCandidates` 渲染**全部**白名单属性（18 项），
         *     最坏 1,136 字符 / 406 token —— 但那条路径今天**没有生产调用方**
         *     （由 `ContextBudgetTest` 机械断言盯住），所以它不是本常数的依据。
         *     哪天它接进 prompt，这个常数必须换成 406。
         */
        const val WORST_CASE_TOKENS_PER_CANDIDATE: Int = 110

        /** Top-K 阶梯的下限 / 上限（§3.4：低于 12 条模型认不出页面骨架）。 */
        const val MIN_TOP_K: Int = 12
        const val MAX_TOP_K: Int = 72

        /** 单条候选渲染行的**最坏字符数**（含缩进与换行）。由 `ContextBudgetTest` 重算。 */
        const val WORST_CASE_CHARS_PER_CANDIDATE: Int = 308

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
