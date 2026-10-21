package com.ake.agent.loop

import com.ake.agent.context.ContextBudget
import com.ake.core.ports.MemoryTier

/**
 * 单步 ReAct 的硬预算（架构 §3.4）。
 *
 * **`maxPrefillTokensTotal` 为什么比 `maxSteps` 更本质**：步与步的成本相差可达 5 倍 ——
 * 一次 `goto()` 几乎不看 DOM（0.3 秒），一次带全量候选的复杂决策可能 15 秒。
 * 按步限流会出现「第 12 步才烧了 30 秒」与「第 8 步已烧了 3 分钟」两种极端，
 * 而**按 token（≈ 按钱）限流才是端侧的正确预算单位**。
 *
 * 所有字段都是 `val`，一次任务内不变：变的东西在 [LoopState] 里累计。
 * 这条切分是刻意的 —— 断点恢复时预算表重新按档位算一遍即可，
 * 而**累计值必须从 checkpoint 里带回来**（宋砚秋的警告：丢了等于每次暂停白送一张预算卡）。
 */
data class LoopBudget(
    val context: ContextBudget,
    val maxSteps: Int,
    val maxWallClockMs: Long,
    val maxPrefillTokensTotal: Long,
    val maxDecodeTokensTotal: Long,
    val perStepMs: Long,
    val perceiveMs: Long,
    val decodeMs: Long,
    val decodeTokens: Int,
    val actionMs: Long,
    val currentTopK: Int,
) {

    /** 剩余预算百分比（0..100）。喂进 prompt 的 `<meta>`，能显著减少模型在早中期的无谓探索。 */
    fun remainingPct(used: UsageTotals): Int {
        val byPrefill = ratio(used.prefillTokensTotal, maxPrefillTokensTotal)
        val byDecode = ratio(used.decodeTokensTotal, maxDecodeTokensTotal)
        val byWall = ratio(used.elapsedMsTotal, maxWallClockMs)
        val bySteps = ratio(used.stepsDone.toLong(), maxSteps.toLong())
        return (100 - (maxOf(byPrefill, byDecode, byWall, bySteps) * 100).toInt()).coerceIn(0, 100)
    }

    /** OBSERVATION 降级阶梯（§3.4 / §4.2）：Top-K 48 → 24 → 12 → 6 → 骨架。 */
    fun topKFor(level: Int): Int = when (level) {
        0 -> currentTopK
        1 -> (currentTopK / 2).coerceAtLeast(12)
        2 -> 12
        else -> 6
    }

    private fun ratio(used: Long, max: Long): Double =
        if (max <= 0L) 0.0 else (used.toDouble() / max.toDouble()).coerceIn(0.0, 1.0)

    companion object {
        fun forTier(tier: MemoryTier): LoopBudget = when (tier) {
            MemoryTier.LOW -> LoopBudget(
                context = ContextBudget.NARROW,
                maxSteps = 15, maxWallClockMs = 150_000L,
                maxPrefillTokensTotal = 60_000L, maxDecodeTokensTotal = 4_000L,
                perStepMs = 12_000L, perceiveMs = 1_500L,
                decodeMs = 8_000L, decodeTokens = 96, actionMs = 2_500L, currentTopK = 24,
            )

            MemoryTier.MID -> LoopBudget(
                context = ContextBudget.STANDARD,
                maxSteps = 25, maxWallClockMs = 240_000L,
                maxPrefillTokensTotal = 150_000L, maxDecodeTokensTotal = 10_000L,
                perStepMs = 10_000L, perceiveMs = 1_200L,
                decodeMs = 6_000L, decodeTokens = 128, actionMs = 2_000L, currentTopK = 48,
            )

            MemoryTier.HIGH -> LoopBudget(
                context = ContextBudget.WIDE,
                maxSteps = 40, maxWallClockMs = 420_000L,
                maxPrefillTokensTotal = 400_000L, maxDecodeTokensTotal = 24_000L,
                perStepMs = 10_000L, perceiveMs = 1_000L,
                decodeMs = 6_000L, decodeTokens = 160, actionMs = 2_000L, currentTopK = 72,
            )
        }
    }
}

/**
 * 预算累计。**这四个字段必须与 `LoopCheckpoint` 同口径（「累计终值」而非增量）** ——
 * `core/contract/Records.kt` 的 `TaskBudgetPatch` 注释里也写了同一条约定。
 */
data class UsageTotals(
    val stepsDone: Int = 0,
    val prefillTokensTotal: Long = 0L,
    val decodeTokensTotal: Long = 0L,
    val elapsedMsTotal: Long = 0L,
)
