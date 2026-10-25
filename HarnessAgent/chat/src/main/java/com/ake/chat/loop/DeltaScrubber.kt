package com.ake.chat.loop

/**
 * # 流式增量的兜底清洗（§3.3 / R4 ②）
 *
 * **它解决的问题**：Gemma 的 turn 格式 special token（`<start_of_turn>` / `<end_of_turn>` /
 * `<|im_end|>` …）**会在 chunk 边界被切开**。只按 chunk 做正则替换是不够的 ——
 * chunk1 = `<|im_`、chunk2 = `start|>`，各自都「不是 token」，拼起来才是，于是用户看到
 * 屏幕上闪出 `‹|im_` 这样的乱码片段。
 *
 * **做法**：把「可能是半个 token 的尾巴」扣住不给 UI，等下一个 chunk 到了再一起判定。
 * 这是 `:chat` 侧能独立做对的部分 —— 它不依赖引擎，也不依赖 `:llm` 什么时候落地真流式。
 *
 * ## 为什么不干脆整段缓冲
 *
 * 缓冲会让「流式」退化成「一次吐完」，那是 `TokenStream` 注释里明令禁止的**假流式**。
 * 这里只在**尾巴可疑时**扣最多 [MAX_HOLD] 个字符，正常文本一个字节都不延后。
 *
 * ## 与闸 1 的分工
 *
 * 闸 1（`ContentSanitizer`，经 `PolicyPort.sanitize`）清的是「控制 token 序列 / 零宽字符」，
 * 但它是**按传给它的那段文本**工作的，管不到跨 chunk 的半个 token。两者是两层：
 * 本类管边界，闸 1 管内容。**任何一层单用都会漏。**
 */
public class DeltaScrubber {

    private val carry = StringBuilder()

    /** @return 可以立刻投给 UI 的文本（可能是空串）。 */
    public fun feed(delta: String): String {
        if (delta.isEmpty()) return ""
        val buf = carry.toString() + delta
        carry.setLength(0)

        val holdFrom = holdStart(buf)
        if (holdFrom < 0) return SPECIAL.replace(buf, "")

        val tail = buf.substring(holdFrom)
        val body = buf.substring(0, holdFrom)
        // 尾巴太长说明这不是半个 token 而是一段以 '<' 开头的正文（比如数学式子），放它走。
        if (tail.length > MAX_HOLD) return SPECIAL.replace(buf, "")
        carry.append(tail)
        return SPECIAL.replace(body, "")
    }

    /** 流结束（或本轮被中止）时必须调：把扣住的尾巴放出去并清洗。 */
    public fun flush(): String {
        if (carry.isEmpty()) return ""
        val out = SPECIAL.replace(carry.toString(), "")
        carry.setLength(0)
        return out
    }

    /** 尾部存在「未闭合的 `<` 序列」时返回它的起点；否则 -1。 */
    private fun holdStart(buf: String): Int {
        val lt = buf.lastIndexOf('<')
        if (lt < 0) return -1
        val gt = buf.indexOf('>', lt)
        return if (gt >= 0) -1 else lt
    }

    public companion object {
        public const val MAX_HOLD: Int = 24

        /**
         * 已知的 turn 格式 token。**白名单式**匹配 —— 不用
         * `<\w+>` 那种通吃写法：它会把 `<b>`、`<3` 之类的正文一起吃掉，
         * 而「回答里的 HTML 标签被凭空删掉」这种 bug 极难被归因。
         */
        private val NAMES = listOf(
            "start_of_turn", "end_of_turn", "bos", "eos", "pad", "unk",
            "im_start", "im_end", "endoftext", "image_soft_token",
            "system", "user", "assistant", "tool", "bot", "human",
        )

        private val SPECIAL = Regex(
            "</?(?:" + NAMES.joinToString("|") + ")>" +            // <end_of_turn>
                "|<\\|\\s*(?:" + NAMES.joinToString("|") + ")\\s*\\|?>", // <|im_end|> / <|im_end>
            RegexOption.IGNORE_CASE,
        )
    }
}
