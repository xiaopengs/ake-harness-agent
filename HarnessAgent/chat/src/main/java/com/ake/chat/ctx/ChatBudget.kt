package com.ake.chat.ctx

import com.ake.agent.context.ContextBudget
import com.ake.agent.loop.LoopBudget
import com.ake.core.ports.BudgetEnvelope
import com.ake.core.ports.ContextWindowFacts
import com.ake.core.ports.MemoryTier

/**
 * # 聊天的计量单位不是「步」，是「prefill/decode 总量 + 墙钟」（§3.4）
 *
 * 为什么**不**直接复用 `LoopBudget`：`LoopBudget.maxSteps = 25` 是「一次网页任务」的上限，
 * 而聊天一天累积 200 轮是正常的。拿 maxSteps 限流 ⇒ 用户聊到第 26 句被熔断。
 * 那是把锅炉压力表装在了水杯上。
 *
 * 为什么不彻底独立：`contextBudget` 必须与任务侧**同一个对象**（同档位同窗口），
 * 否则就是「设置页显示一套、聊天跑另一套」。所以这里持有的是
 * `ContextBudget.forTier(tier)` 的**同一个实例**，不是新构造的一份数字。
 *
 * ## 它与 `LoopBudget` 的真实关系
 *
 * | 用途 | 来源 |
 * |---|---|
 * | 上下文窗口 / 分段 cap | [ContextBudget]（与任务共享，档位决定） |
 * | 聊天计量（轮数 / 单轮 prefill / 墙钟 / 回答长度） | 本类 |
 * | 派生任务在这一轮里能用的额度 | [envelopeFor] —— 把 `LoopBudget` 的 maxSteps 与墙钟**划转**给任务 |
 *
 * 最后一行是 §3.4「额度划转」的落点：任务不是拿到一张新预算卡，而是拿到本轮聊天
 * 让给它的那一份（`maxWallClockMs` 取两者更小值）。这样「任务跑 4 分钟而聊天单轮上限 90 秒」
 * 这种自相矛盾的组合在构造上不成立。
 */
public data class ChatBudget(
    val memoryTier: MemoryTier,
    /** ★ 与任务侧同一个对象（同一份数字的两种看法），不是拷贝出来的第二份窗口。 */
    val contextBudget: ContextBudget,
    val maxTurnsPer24h: Int,
    val maxPrefillTokensPerTurn: Long,
    val maxPrefillTokensPerDay: Long,
    val maxWallClockMsPerTurn: Long,
    val answerMaxTokens: Int,
    /** ★ 复水预算上限：一次冷启最多花这么多 prefill 把记忆灌回来（§3.5）。 */
    val rehydrateTokenCap: Int,
) {
    public val usablePromptTokens: Int get() = contextBudget.usablePromptTokens

    /**
     * `ContextBudget` 的 L1 投影（`ContextWindowFacts`）。
     *
     * 与 `LoopBudgetFacts` 同一手法：[ContextWindowFacts] 只是**同一份数字的另一种看法**，
     * 不是第二套预算。UI 拿它显示窗口占用，任务循环拿真 `ContextBudget` 分配 ——
     * 两者不可能不同步，因为它们来自同一个 `ContextBudget` 实例。
     */
    public fun contextFacts(): ContextWindowFacts = ContextWindowFacts(
        tierName = memoryTier.name,
        workingWindow = contextBudget.workingWindow,
        outputReserve = contextBudget.outputReserve,
        usablePromptTokens = contextBudget.usablePromptTokens,
    )

    /**
     * 给派生技能的预算信封（§2.6）。**只在 `TASK` / `TASK_IN_CHAT` 下调**。
     *
     * 三个「不超过」是硬约束（§5.2 + §2.6）：
     *  ① `maxSteps` 不超过本档 `LoopBudget`（技能不该比独立任务跑得更久）；
     *  ② `maxWallClockMs` 不超过**本轮聊天**的剩余墙钟；
     *  ③ `maxDecodeTokensTotal` 不超过本轮回答预算 —— 否则任务会把回答的字烧光。
     */
    public fun envelopeFor(kind: ModeKind): BudgetEnvelope {
        require(kind != ModeKind.CHAT) {
            "CHAT 模式没有派生任务，不需要 BudgetEnvelope —— 传 CHAT 说明调用点把模式判断写错了"
        }
        val loop = LoopBudget.forTier(memoryTier)
        val chatRemainingMs = maxWallClockMsPerTurn
        return BudgetEnvelope(
            context = contextFacts(),
            modeProfile = when (kind) {
                ModeKind.TASK_IN_CHAT -> BudgetEnvelope.MODE_PROFILE_TASK_IN_CHAT
                ModeKind.TASK -> BudgetEnvelope.MODE_PROFILE_TASK
                ModeKind.CHAT -> error("unreachable")
            },
            maxSteps = loop.maxSteps,
            maxWallClockMs = minOf(loop.maxWallClockMs, chatRemainingMs),
            maxPrefillTokensTotal = minOf(loop.maxPrefillTokensTotal, maxPrefillTokensPerTurn),
            maxDecodeTokensTotal = minOf(loop.maxDecodeTokensTotal, answerMaxTokens.toLong() * 4),
        )
    }

    public companion object {

        /**
         * 三档数字取自 §3.4 的表，但 `contextBudget` **不再写 `ContextBudget.NARROW` 字面量**，
         * 一律走 `ContextBudget.forTier(tier)` —— 顺序错一个档位的代价是
         * 「LOW 机按 HIGH 的窗口分配」，而它的表现是 native 层 OOM，不是编译错误。
         */
        public fun forTier(tier: MemoryTier): ChatBudget = when (tier) {
            MemoryTier.LOW -> ChatBudget(
                memoryTier = tier,
                contextBudget = ContextBudget.forTier(tier),
                maxTurnsPer24h = 120,
                maxPrefillTokensPerTurn = 24_000,
                maxPrefillTokensPerDay = 200_000,
                maxWallClockMsPerTurn = 60_000,
                answerMaxTokens = 256,
                rehydrateTokenCap = 900,
            )

            MemoryTier.MID -> ChatBudget(
                memoryTier = tier,
                contextBudget = ContextBudget.forTier(tier),
                maxTurnsPer24h = 240,
                maxPrefillTokensPerTurn = 60_000,
                maxPrefillTokensPerDay = 600_000,
                maxWallClockMsPerTurn = 90_000,
                answerMaxTokens = 512,
                rehydrateTokenCap = 2_200,
            )

            MemoryTier.HIGH -> ChatBudget(
                memoryTier = tier,
                contextBudget = ContextBudget.forTier(tier),
                maxTurnsPer24h = 400,
                maxPrefillTokensPerTurn = 140_000,
                maxPrefillTokensPerDay = 1_500_000,
                maxWallClockMsPerTurn = 120_000,
                answerMaxTokens = 768,
                rehydrateTokenCap = 5_000,
            )
        }

        /** Router 那次约束解码的上限。§3.1：~40 token 就够，多给的每个 token 都是白烧的电。 */
        public const val ROUTER_MAX_TOKENS: Int = 48
    }
}

/**
 * 一轮的账。**可变的、单轮生命周期的**计数员。
 *
 * 为什么不复用 `UsageTotals`：那是「累计终值」（任务断点恢复要带回），
 * 这里要的是「这一轮还剩多少」—— 两者的语义方向相反，混用必然写错。
 */
public class TurnLedger(private val budget: ChatBudget) {

    public var prefillTokens: Long = 0L
        private set
    public var decodeTokens: Long = 0L
        private set
    public var skillCalls: Int = 0
        private set

    private var startedAt: Long = -1L

    public fun startTurn(nowMillis: Long) {
        startedAt = nowMillis
    }

    public fun onPrefill(tokens: Int) {
        prefillTokens += tokens.coerceAtLeast(0)
    }

    public fun onDecode(tokens: Int) {
        decodeTokens += tokens.coerceAtLeast(0)
    }

    public fun onSkillCall() {
        skillCalls++
    }

    /** 单轮 prefill 上限 —— 防止一次复水吃掉半天预算（§3.4）。 */
    public fun prefillExceeded(): Boolean = prefillTokens > budget.maxPrefillTokensPerTurn

    /** 墙钟上限。`startedAt < 0` 表示还没开始，永远不算超。 */
    public fun wallClockExceeded(nowMillis: Long): Boolean =
        startedAt >= 0 && nowMillis - startedAt > budget.maxWallClockMsPerTurn

    public fun remainingMs(nowMillis: Long): Long =
        if (startedAt < 0) budget.maxWallClockMsPerTurn
        else (budget.maxWallClockMsPerTurn - (nowMillis - startedAt)).coerceAtLeast(0L)

    /** `<meta>` 段用的百分比：取 prefill / decode / 墙钟三者消耗最快的那一项。 */
    public fun remainingPct(nowMillis: Long): Int {
        val byPrefill = ratio(prefillTokens, budget.maxPrefillTokensPerTurn)
        val byDecode = ratio(decodeTokens, budget.answerMaxTokens.toLong())
        val byWall = ratio(if (startedAt < 0) 0L else nowMillis - startedAt, budget.maxWallClockMsPerTurn)
        return (100 - (maxOf(byPrefill, byDecode, byWall) * 100).toInt()).coerceIn(0, 100)
    }

    private fun ratio(used: Long, max: Long): Double =
        if (max <= 0L) 0.0 else (used.toDouble() / max.toDouble()).coerceIn(0.0, 1.0)
}
