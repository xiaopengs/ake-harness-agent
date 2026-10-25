package com.ake.chat.ctx

import com.ake.agent.loop.LoopBudget
import com.ake.core.ports.BudgetEnvelope
import com.ake.core.ports.MemoryTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `ChatBudget` 与「同一份数字的两种看法」。
 *
 * 这一组测试要卡住的是一件很具体的事：**设置页显示的窗口与聊天实际用的窗口不可能不同步**。
 * 所以断言全部指向「它们来自同一个 `ContextBudget.forTier`」。
 */
class ChatBudgetTest {

    @Test
    fun contextBudgetIsTheSameInstanceAsTheTaskLoopUses() {
        for (tier in MemoryTier.entries) {
            val chatBudget = ChatBudget.forTier(tier)
            // ★ 关键断言：不是「相等」，而是「同一个对象」。
            assertThat(chatBudget.contextBudget).isSameInstanceAs(com.ake.agent.context.ContextBudget.forTier(tier))
        }
    }

    @Test
    fun contextFactsProjectTheSameNumbers() {
        val b = ChatBudget.forTier(MemoryTier.MID)
        val facts = b.contextFacts()
        assertThat(facts.tierName).isEqualTo("MID")
        assertThat(facts.workingWindow).isEqualTo(b.contextBudget.workingWindow)
        assertThat(facts.outputReserve).isEqualTo(b.contextBudget.outputReserve)
        assertThat(facts.usablePromptTokens).isEqualTo(b.contextBudget.workingWindow - b.contextBudget.outputReserve)
    }

    @Test
    fun answerBudgetGrowsWithTier() {
        val low = ChatBudget.forTier(MemoryTier.LOW)
        val mid = ChatBudget.forTier(MemoryTier.MID)
        val high = ChatBudget.forTier(MemoryTier.HIGH)
        assertThat(low.answerMaxTokens).isLessThan(mid.answerMaxTokens)
        assertThat(mid.answerMaxTokens).isLessThan(high.answerMaxTokens)
        assertThat(low.rehydrateTokenCap).isLessThan(mid.rehydrateTokenCap)
        assertThat(mid.rehydrateTokenCap).isLessThan(high.rehydrateTokenCap)
    }

    @Test
    fun envelopeNeverExceedsTheHostTurnOrTheTaskLoop() {
        val b = ChatBudget.forTier(MemoryTier.MID)
        val loop = LoopBudget.forTier(MemoryTier.MID)
        val env = b.envelopeFor(ModeKind.TASK_IN_CHAT)

        // §2.6 纪律：任务必须在给它的额度内干活，三项都不得越界
        assertThat(env.maxSteps).isEqualTo(loop.maxSteps)
        assertThat(env.maxWallClockMs).isAtMost(b.maxWallClockMsPerTurn)
        assertThat(env.maxPrefillTokensTotal).isAtMost(b.maxPrefillTokensPerTurn)
        assertThat(env.modeProfile).isEqualTo(BudgetEnvelope.MODE_PROFILE_TASK_IN_CHAT)
        assertThat(env.context.usablePromptTokens).isEqualTo(b.usablePromptTokens)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelopeForChatModeIsAProgrammingError() {
        ChatBudget.forTier(MemoryTier.MID).envelopeFor(ModeKind.CHAT)
    }

    @Test
    fun ledgerAccumulatesAndFlagsOverrun() {
        val b = ChatBudget.forTier(MemoryTier.LOW)
        val ledger = TurnLedger(b)
        ledger.startTurn(1_000L)

        assertThat(ledger.prefillExceeded()).isFalse()
        ledger.onPrefill(b.maxPrefillTokensPerTurn.toInt() + 1)
        assertThat(ledger.prefillExceeded()).isTrue()

        assertThat(ledger.wallClockExceeded(1_000L + b.maxWallClockMsPerTurn + 1)).isTrue()
        assertThat(ledger.wallClockExceeded(1_000L + 10)).isFalse()
    }

    @Test
    fun remainingPctTakesTheFastestBurningAxis() {
        val b = ChatBudget.forTier(MemoryTier.MID)
        val ledger = TurnLedger(b)
        ledger.startTurn(0L)
        // 墙钟烧掉 90%，别的都没动 ⇒ 剩余 10%
        ledger.onPrefill(1)
        assertThat(ledger.remainingPct(b.maxWallClockMsPerTurn * 9 / 10)).isEqualTo(10)
    }
}
