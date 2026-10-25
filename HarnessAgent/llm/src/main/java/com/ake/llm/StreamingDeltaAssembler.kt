package com.ake.llm

/**
 * 「引擎给我的这段文字」→「本轮真正新增的那一段」。
 *
 * ## 为什么需要它（而不是直接把 chunk 扔给 onDelta）
 *
 * LiteRT-LM 的异步回调 `MessageCallback.onMessage(message)` 每次给一个 `Message`，
 * 但**它给的是增量还是累计全文，官方没有写明**，本机也没有真机可验证
 * （`docs/05-chat-agents-design.md` §3.3 的原话是「`Flow<Message>` 的累加语义需在真机确认」）。
 *
 * 两种语义在 UI 上是**完全不同的故障**：
 *  - 若真是累计语义而我们当增量转发 ⇒ 屏幕上会出现 "我我我觉得得得"（重复累加）；
 *  - 若真是增量语义而我们当累计取尾部 ⇒ 会吞字（比重复更难发现）。
 *
 * 所以这里不赌任何一种，而是**按前缀关系自适应**：
 * 新 chunk 若以「已收到的全文」为前缀，就只能是累计语义，投递差集；
 * 否则只能是增量语义，原样投递。两种语义下最终全文都一致。
 *
 * 已知歧义窗口：只有第一个 chunk 之后紧跟着一个「恰好以已收到内容为前缀」的增量 chunk
 * （例如 token 序列 ["我", "我们"]）时，会按累计语义理解而少投一个字。
 * 它需要 chunk 边界恰好重合，且只可能发生在开头一两个字符上 —— 相对「整屏重复」是可接受的代价。
 *
 * ## 不做的事
 *
 * **不清洗 special token**。Gemma 的 turn 标记可能在 chunk 边界被切开，
 * 而跨 chunk 缓冲清洗只有拥有「整轮文本」的一侧能做对 —— 那一侧是 `:chat` 的 `DeltaScrubber`。
 * 两边都做会重复吞字符（`<start_of_turn>` 被吃掉一半、另一半留在 UI 上）。
 */
internal class StreamingDeltaAssembler {

    private val full = StringBuilder()

    /** 已收到的全文（按确认后的语义拼接）。用于结束时的 `GenerationResult.rawText`。 */
    val text: String get() = full.toString()

    /**
     * @return 本次应当向下游投递的**新增片段**；空串表示这一 chunk 没有新内容（不要投）。
     */
    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val seen = full.toString()
        if (seen.isEmpty()) {
            full.append(chunk)
            return chunk
        }

        // 累计语义：chunk 是「到目前为止的全文」
        if (chunk.length >= seen.length && chunk.startsWith(seen)) {
            val delta = chunk.substring(seen.length)
            full.setLength(0)
            full.append(chunk)
            return delta
        }

        // 已投递过同样内容（重复投递同一条 Message）
        if (chunk == seen) return ""

        // 增量语义：chunk 只是新的一段
        full.append(chunk)
        return chunk
    }
}
