package com.ake.chat.ctx

import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 聊天历史的确定性折算：切分、压行、保留尾部装箱。
 *
 * 这一组测试的价值观：**压缩必须可预测**。给它同一串消息，它必须每次都吐同一批行 ——
 * 否则黄金轨迹比对会永远失败（`docs/01` §10.3 的既有纪律）。
 */
class ChatHistoryCollectorTest {

    /** 4 字符 ≈ 1 token，跟 `FakeModelPort.estimateTokens` 同一口径。 */
    private val estimate: (String) -> Int = { (it.length / 4).coerceAtLeast(1) }
    private val collector = ChatHistoryCollector(estimate)

    private fun msg(
        seq: Int,
        turnId: String,
        role: MessageRole,
        text: String,
        kind: MessageKind = MessageKind.TEXT,
    ): ChatMessageView = ChatMessageView(
        id = "m$seq",
        sessionId = "s1",
        seq = seq,
        turnId = turnId,
        role = role,
        kind = kind,
        status = MessageStatus.COMPLETE,
        textRedacted = text,
        createdAt = seq.toLong(),
    )

    private fun conversation(turns: Int, bodyPerTurn: Int = 40): List<ChatMessageView> = buildList {
        var seq = 0
        repeat(turns) { t ->
            seq++
            add(msg(seq, "t$t", MessageRole.USER, "问题$t " + "x".repeat(bodyPerTurn)))
            seq++
            add(msg(seq, "t$t", MessageRole.ASSISTANT, "回答$t " + "y".repeat(bodyPerTurn)))
        }
    }

    @Test
    fun splitKeepsLastNthTurnsVerbatimAndCompactsTheRest() {
        val split = collector.split(conversation(turns = 10), keepVerbatimTurns = 6)
        assertThat(split.recent.map { it.turnId }.distinct()).hasSize(6)
        assertThat(split.older.map { it.turnId }.distinct()).hasSize(4)
        // 一条消息都不许凭空消失
        assertThat(split.older.size + split.recent.size).isEqualTo(20)
    }

    @Test
    fun compactLineIsTruncatedAndSingleLine() {
        val long = msg(1, "t0", MessageRole.ASSISTANT, "a".repeat(200) + "\n第二行")
        val line = collector.compactLine(long)
        assertThat(line).startsWith("A: ")
        assertThat(line).doesNotContain("\n")
        assertThat(line.length).isAtMost(ChatHistoryCollector.COMPACT_CHARS + 4)
        assertThat(line).endsWith("…")
        // 确定性：同一输入两次调用必须完全相同
        assertThat(collector.compactLine(long)).isEqualTo(line)
    }

    @Test
    fun verbatimLineKeepsFullTextButFoldsNewlines() {
        val m = msg(1, "t0", MessageRole.USER, "第一行\n第二行")
        assertThat(collector.verbatimLine(m)).isEqualTo("U: 第一行 第二行")
    }

    @Test
    fun packTailDropsOldestNotNewest() {
        val entries = (1..10).map { ChatHistoryCollector.Entry(it, "行$it " + "z".repeat(40)) }
        val packed = collector.packTail(entries, tokenCap = 40)
        assertThat(packed.kept).isNotEmpty()
        // 尾部必须在：最新的那条一定留下来了
        assertThat(packed.kept.last().seq).isEqualTo(10)
        // 被丢的是老的
        assertThat(packed.kept.first().seq).isGreaterThan(1)
        assertThat(packed.dropped).isEqualTo(10 - packed.kept.size)
    }

    @Test
    fun packTailSkipsAnOversizedOldEntryInsteadOfStarvingTheRest() {
        // 一条超长的老消息不该把它后面所有更新的行都饿死
        val entries = listOf(
            ChatHistoryCollector.Entry(1, "x".repeat(4_000)),
            ChatHistoryCollector.Entry(2, "短2"),
            ChatHistoryCollector.Entry(3, "短3"),
        )
        val packed = collector.packTail(entries, tokenCap = 50)
        assertThat(packed.kept.map { it.seq }).containsExactly(2, 3)
        assertThat(packed.dropped).isEqualTo(1)
    }

    @Test
    fun compactBoundaryMarkerIsNotFedToTheModel() {
        val views = conversation(turns = 2) + msg(
            99, "t9", MessageRole.SYSTEM_NOTE, "以下为压缩历史", kind = MessageKind.COMPACT_BOUNDARY,
        )
        val slice = collector.collect(views, lineBudget = 10_000)
        assertThat(slice.lines.map { it.second }).doesNotContain("N: 以下为压缩历史")
    }

    @Test
    fun collectReturnsAscendingSeqWithinBudget() {
        val slice = collector.collect(conversation(turns = 8), lineBudget = 120)
        assertThat(slice.tokens).isAtMost(120)
        val seqs = slice.lines.map { it.first }
        assertThat(seqs).isInOrder()
        assertThat(slice.droppedMessages).isAtLeast(0)
    }

    @Test
    fun emptyConversationYieldsEmptySlice() {
        val slice = collector.collect(emptyList(), lineBudget = 1_000)
        assertThat(slice.lines).isEmpty()
        assertThat(slice.tokens).isEqualTo(0)
    }
}
