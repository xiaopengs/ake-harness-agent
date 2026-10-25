package com.ake.chat.ctx

import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole

/**
 * # 聊天历史的**确定性**收集与折算（§3.2 / §3.5 / R6）
 *
 * 三件事，全是确定性 Kotlin，**一次模型都不调**：
 *
 *  ① [split] —— 把历史切成「最近 N 轮原文」与「更早的轮」。
 *     为什么必须留原文：语气、指代、「刚才那个函数的第二个参数」—— 恰恰是唯一必须逐字保真的东西。
 *  ② [compactLine] —— 更早的轮压成一行。聊天没有 `StepRecord`，所以不能用
 *     `DeterministicCompactor`（那是步进循环的压缩器，它的输入是 `StepAction`+`ToolResult`）——
 *     一行的形态是 `U: 前 40 字…` / `A: …`。
 *  ③ [packTail] —— 在给定 token cap 内**保留尾部**。
 *
 * ## 为什么 [packTail] 不算「第二套预算算法」
 *
 * 它不分配任何预算：cap 由调用方从 `SegmentCaps`（`:agent` 的）里取，它只做一件
 * 与预算无关的事 —— 「从最新往回装，装不下就丢最老的」。这条纪律与
 * `DeterministicCompactor.compactToBudget` 逐行同构（包括从 `asReversed()` 开始装这一细节），
 * 之所以不能直接复用那个函数，只因为它的入参类型是 `List<StepRecord>`。
 * **预算的分配权仍然只在 `SegmentCaps` / `ContextBudget` 那一处。**
 *
 * ## 「保留尾部」是硬要求
 *
 * 砍头比砍尾安全得多：最后两轮决定用户此刻在问什么，更早的轮已被压成一行。
 */
public class ChatHistoryCollector(private val estimate: (String) -> Int) {

    /**
     * 切分：`older` 是可以压成行的，`recent` 必须逐字保留。
     *
     * 按 `turnId` 而不是按消息条数分组 —— 一轮里可能有多条（用户一句 + 技能调用 + 技能结果 + 回答），
     * 按条数切会把一轮劈成两半，那比不切开更糟：模型会看到「回答」却没有对应的「问题」。
     */
    public fun split(views: List<ChatMessageView>, keepVerbatimTurns: Int = DEFAULT_VERBATIM_TURNS): HistorySplit {
        val usable = views.filter { it.isRenderable() }
        if (usable.isEmpty()) return HistorySplit(emptyList(), emptyList())

        val turnIds = LinkedHashSet<String>()
        usable.forEach { turnIds += it.turnId }
        val verbatim = turnIds.toList().takeLast(keepVerbatimTurns).toSet()
        if (verbatim.isEmpty()) return HistorySplit(usable, emptyList())
        return HistorySplit(
            older = usable.filter { it.turnId !in verbatim },
            recent = usable.filter { it.turnId in verbatim },
        )
    }

    /**
     * 历史 → `AssembleRequest.recentRaw` 的形状（`(seq, 文本)`，升序）。
     *
     * 为什么走 `RECENT_TRACE` 而不是 `HISTORY_COMPACT`：后者只吃 `StepRecord`（见 [ModeProfile] 注释 ②）。
     * `RECENT_TRACE` 的渲染是 `#seq: 文本`，正好就是聊天历史该有的样子，且它同样「保留尾部」。
     *
     * @param lineBudget 这一段的 token 上限。**调用方要给 `ContextAssembler` 的 meta/占位段留余量**，
     *                   否则总量刚好顶到 `usablePromptTokens` 时会触发一次降级，而降级的第一刀
     *                   就是整段 RECENT_TRACE —— 那等于「预算紧一点，历史全没了」。
     */
    public fun collect(
        views: List<ChatMessageView>,
        lineBudget: Int,
        keepVerbatimTurns: Int = DEFAULT_VERBATIM_TURNS,
    ): HistorySlice {
        val split = split(views, keepVerbatimTurns)
        // 从最新往回：最近的轮给原文，更早的轮给压缩行 —— 但都进同一个「保留尾部」的窗口。
        val entries = buildList {
            split.older.forEach { add(Entry(it.seq, compactLine(it))) }
            split.recent.forEach { add(Entry(it.seq, verbatimLine(it))) }
        }.sortedBy { it.seq }

        val packed = packTail(entries, lineBudget)
        return HistorySlice(
            lines = packed.kept.map { it.seq to it.text },
            tokens = packed.tokens,
            verbatimTurns = split.recent.map { it.turnId }.distinct().size,
            compactedMessages = split.older.size,
            droppedMessages = entries.size - packed.kept.size,
        )
    }

    /** 一条消息压成一行。**确定性**：截断长度是常量，不做摘要、不问模型。 */
    public fun compactLine(msg: ChatMessageView): String {
        val text = msg.textRedacted.replace('\n', ' ').trim()
        val clipped = if (text.length <= COMPACT_CHARS) text else text.take(COMPACT_CHARS) + "…"
        return "${roleTag(msg)} $clipped"
    }

    /** 原文行：保留全部文本，只把换行折掉（渲染成单行 `#seq: …` 更稳定）。 */
    public fun verbatimLine(msg: ChatMessageView): String =
        "${roleTag(msg)} " + msg.textRedacted.replace('\n', ' ').trim()

    /**
     * 保留尾部的装箱。**与 `DeterministicCompactor.compactToBudget` 同一纪律**：
     * 从最新往回装，装不下就跳过（跳过而不是中断 —— 一条超长的老消息不该把它后面的全饿死）。
     */
    public fun packTail(entries: List<Entry>, tokenCap: Int): PackedLines {
        if (entries.isEmpty() || tokenCap <= 0) {
            return PackedLines(emptyList(), 0, entries.size)
        }
        val kept = ArrayDeque<Entry>()
        var used = 0
        var dropped = 0
        for (e in entries.asReversed()) {
            val cost = estimate(e.text) + 1 // +1 换行
            if (used + cost > tokenCap) {
                dropped++
                continue
            }
            kept.addFirst(e)
            used += cost
        }
        return PackedLines(kept.toList(), used, dropped)
    }

    private fun roleTag(msg: ChatMessageView): String = when (msg.role) {
        MessageRole.USER -> "U:"
        MessageRole.ASSISTANT -> "A:"
        MessageRole.SKILL_CALL -> "S:"
        MessageRole.SKILL_RESULT -> "R:"
        MessageRole.SYSTEM_NOTE -> "N:"
    }

    /**
     * 哪些消息进 prompt。
     *
     * 刻意排除 `COMPACT_BOUNDARY`：它是**给我们自己看的**边界标记，
     * 不是说过的话。把它当历史喂给模型，等于告诉它「你之前说过一句『以下为压缩历史』」。
     */
    private fun ChatMessageView.isRenderable(): Boolean =
        kind != MessageKind.COMPACT_BOUNDARY && textRedacted.isNotBlank()

    public data class Entry(val seq: Int, val text: String)

    public data class PackedLines(val kept: List<Entry>, val tokens: Int, val dropped: Int)

    public data class HistorySplit(val older: List<ChatMessageView>, val recent: List<ChatMessageView>)

    public data class HistorySlice(
        val lines: List<Pair<Int, String>>,
        val tokens: Int,
        val verbatimTurns: Int,
        val compactedMessages: Int,
        val droppedMessages: Int,
    )

    public companion object {
        /** 最近多少**轮**给原文（§3.5 的 `n = 6`）。 */
        public const val DEFAULT_VERBATIM_TURNS: Int = 6

        /** 压缩行的截断长度。一行 ≈ 20–30 token，40 轮 ≈ 1.1k token。 */
        public const val COMPACT_CHARS: Int = 40
    }
}
