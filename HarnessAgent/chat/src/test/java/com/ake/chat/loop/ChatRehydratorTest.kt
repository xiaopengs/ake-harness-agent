package com.ake.chat.loop

import com.ake.chat.ctx.ChatBudget
import com.ake.chat.ctx.ChatHistoryCollector
import com.ake.chat.fixtures.FakeChatStore
import com.ake.core.modelapi.TurnRole
import com.ake.core.ports.MemoryTier
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.core.ports.ChatMessageView
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 复水的成本与形态（§3.5）。
 *
 * 两件事：**成本必须 ≤ `rehydrateTokenCap`**（否则冷启等 72 秒），
 * 且**最近几轮必须是原文**（否则模型记不住「刚才那个函数的第二个参数」）。
 */
class ChatRehydratorTest {

    private val estimate: (String) -> Int = { (it.length / 4).coerceAtLeast(1) }
    private val collector = ChatHistoryCollector(estimate)
    private val store = FakeChatStore()
    private val rehydrator = ChatRehydrator(store, collector, estimate)

    private suspend fun seed(turns: Int, bodyChars: Int = 40) {
        var seq = 0
        repeat(turns) { t ->
            seq++
            store.stored += ChatMessageView(
                id = "u$seq", sessionId = "sess-1", seq = seq, turnId = "t$t",
                role = MessageRole.USER, kind = MessageKind.TEXT, status = MessageStatus.COMPLETE,
                textRedacted = "第${t}个问题 " + "x".repeat(bodyChars), createdAt = seq.toLong(),
            )
            seq++
            store.stored += ChatMessageView(
                id = "a$seq", sessionId = "sess-1", seq = seq, turnId = "t$t",
                role = MessageRole.ASSISTANT, kind = MessageKind.TEXT, status = MessageStatus.COMPLETE,
                textRedacted = "第${t}个回答 " + "y".repeat(bodyChars), createdAt = seq.toLong(),
            )
        }
    }

    @Test
    fun emptySessionRehydratesToNothing() = runBlocking<Unit> {
        val r = rehydrator.buildInitialMessages("sess-1", ChatBudget.forTier(MemoryTier.MID))
        assertThat(r.turns).isEmpty()
        assertThat(r.tokens).isEqualTo(0)
    }

    @Test
    fun lastTurnsStayVerbatimAndOlderOnesBecomeDigestLines() = runBlocking<Unit> {
        seed(turns = 12)
        val r = rehydrator.buildInitialMessages("sess-1", ChatBudget.forTier(MemoryTier.MID))

        // 第一条是压缩记录的边界说明，其后是最近 6 轮的原文
        assertThat(r.turns.first().role).isEqualTo(TurnRole.USER)
        assertThat(r.turns.first().text).contains("压缩记录")
        assertThat(r.verbatimTurns).isEqualTo(ChatHistoryCollector.DEFAULT_VERBATIM_TURNS)
        // 最近一轮必须是**原文**（含完整正文），不能被压成一行
        assertThat(r.turns.last().text).contains("第11个回答")
        assertThat(r.turns.last().text.length).isGreaterThan(ChatHistoryCollector.COMPACT_CHARS)
    }

    @Test
    fun costStaysWithinTheRehydrateCap() = runBlocking<Unit> {
        seed(turns = 60, bodyChars = 200)
        for (tier in MemoryTier.entries) {
            val budget = ChatBudget.forTier(tier)
            val r = rehydrator.buildInitialMessages("sess-1", budget)
            // cap 是硬上限，只有「最新那条自己就超了」时例外（见 fitVerbatim 注释）
            if (r.verbatimTurns > 1) {
                assertThat(r.tokens).isAtMost(budget.rehydrateTokenCap)
            }
        }
    }

    @Test
    fun identityIsPreservedForVerbatimTurns() = runBlocking<Unit> {
        seed(turns = 8)
        val r = rehydrator.buildInitialMessages("sess-1", ChatBudget.forTier(MemoryTier.MID))
        val verbatim = r.turns.drop(1)
        // 说话人不能丢：用户的话不能变成模型的话（那会让模型以为自己说过）
        assertThat(verbatim.map { it.role }).containsAtLeast(TurnRole.USER, TurnRole.MODEL)
    }

    @Test
    fun digestIsDroppedWhenNothingOlderExists() = runBlocking<Unit> {
        seed(turns = 3)
        val r = rehydrator.buildInitialMessages("sess-1", ChatBudget.forTier(MemoryTier.MID))
        // 只有 3 轮 ⇒ 全部原文，不该硬塞一条空的压缩记录
        assertThat(r.turns.none { it.text.contains("压缩记录") }).isTrue()
        assertThat(r.turns).hasSize(6)
    }
}
