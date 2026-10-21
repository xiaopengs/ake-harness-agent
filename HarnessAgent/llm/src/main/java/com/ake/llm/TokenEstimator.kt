package com.ake.llm

/**
 * 本地 token 估算（架构 §2.2 的硬要求：**必须本地估算，禁止跑模型**）。
 *
 * 为什么这一条被写成接口方法而不是实现细节：上下文预算是本项目唯一能保证「不 OOM、不太慢」的机制，
 * 而预算的前提是能**不跑推理**就知道一段文本多贵。若这一步必须问模型，
 * 反馈回路的时间常数就超过了单步成本，整套「压缩 ⇄ 预算」体系当场失效。
 *
 * **为什么用启发式而不是 SentencePiece tokenizer**：
 * Gemma 的 vocab 有 262k 条，把 tokenizer 带进 App 意味着数百 KB 数据 + 一次 native 加载，
 * 而它的用途只是「判断这轮要不要降级」，不需要逐 token 精确。
 * 精度的代价由 `CONSERVATIVE_FACTOR` 承担 —— **宁可高估也不要低估**：
 * 高估只是少塞一点上下文，低估会在长任务第 20 步把 KV cache 撑爆（§11 R6）。
 *
 * 经验阈值（`String.length` 相关，端侧小模型常见口径）：
 *  - 中日韩字符：约 1 token/字（比 ASCII 贵得多 —— 这也是 prompt 尽量少用中文长句的原因）
 *  - 拉丁文：约 3.6 chars/token
 *  - 数字：约 2 chars/token（数字常被 BPE 拆开，价格/日期这类尤其贵）
 */
public object TokenEstimator {

    private const val LATIN_CHARS_PER_TOKEN = 3.6
    private const val DIGIT_CHARS_PER_TOKEN = 2.0
    private const val CONSERVATIVE_FACTOR = 1.06
    private const val JSON_NOISE_PER_100 = 6

    public fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        var tokens = 0.0
        var asciiRun = 0
        var lastWasDigit = false

        fun flushAscii() {
            if (asciiRun > 0) {
                tokens += asciiRun / if (lastWasDigit) DIGIT_CHARS_PER_TOKEN else LATIN_CHARS_PER_TOKEN
                asciiRun = 0
            }
        }

        for (c in text) {
            when {
                isCjk(c) -> {
                    flushAscii()
                    tokens += 1.0
                }

                c.isDigit() -> {
                    if (!lastWasDigit) flushAscii()
                    asciiRun++
                    lastWasDigit = true
                }

                c.code < 128 -> {
                    asciiRun++
                    lastWasDigit = false
                }

                else -> {
                    flushAscii()
                    tokens += 0.5 // 其它 Unicode（假名/西里尔/emoji…）
                }
            }
        }
        flushAscii()

        val raw = (tokens + text.length / 100.0 * JSON_NOISE_PER_100) * CONSERVATIVE_FACTOR
        return raw.toInt().coerceAtLeast(if (text.isBlank()) 0 else 1)
    }

    /** 多行文本（会额外算换行开销）。用于 Estimate(prompt) 而不是 Estimate(word)。 */
    public fun estimateLines(text: String): Int =
        estimate(text) + text.count { it == '\n' }

    private fun isCjk(c: Char): Boolean = c in '\u4E00'..'\u9FFF' ||
        c in '\u3400'..'\u4DBF' ||
        c in '\u3040'..'\u30FF' ||
        c in '\uAC00'..'\uD7AF'
}
