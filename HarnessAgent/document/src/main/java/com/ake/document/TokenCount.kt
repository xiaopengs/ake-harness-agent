package com.ake.document

/**
 * token 估算（与 `:llm/TokenEstimator` **同一口径**，但代码是独立副本）。
 *
 * ## 为什么复制而不复用
 *
 * `:llm` 与 `:document` 同为 L3，本项目禁止 L3 横向依赖（见根 `build.gradle.kts`
 * 的 `archAllowedEdges`）。把 TokenEstimator 上提到 `:core:contract` 是正确解法，
 * 但那要改动 `:llm` 已有的调用点，属于独立重构，不该混在 M1 的首个模块里做。
 *
 * ## 这份重复的风险与约束
 *
 * 风险是**口径漂移**：某天 `:llm` 把 `CONSERVATIVE_FACTOR` 从 1.06 调到 1.1，
 * 分块按旧口径算，导致实际送入模型的块比预算大。
 * 约束：任何改动 `:llm/TokenEstimator` 常量的人，必须同时改这里；
 * 反向亦然。两处都有这条注释，靠 code review 守住，直到上提到 L2 为止。
 *
 * 口径（与 `:llm` 一致）：
 *  - CJK：1 token/字
 *  - 拉丁：3.6 字符/token
 *  - 数字：2 字符/token（BPE 会把数字拆开）
 *  - 末尾 ×1.06 保守系数，宁可高估
 */
internal object TokenCount {

    private const val LATIN_CHARS_PER_TOKEN = 3.6
    private const val DIGIT_CHARS_PER_TOKEN = 2.0
    private const val CONSERVATIVE_FACTOR = 1.06

    fun of(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0.0
        var asciiRun = 0
        var lastWasDigit = false

        fun flush() {
            if (asciiRun > 0) {
                tokens += asciiRun / if (lastWasDigit) DIGIT_CHARS_PER_TOKEN else LATIN_CHARS_PER_TOKEN
                asciiRun = 0
            }
        }

        for (c in text) {
            when {
                isCjk(c) -> {
                    flush()
                    tokens += 1.0
                }

                c.isDigit() -> {
                    if (!lastWasDigit) flush()
                    asciiRun++
                    lastWasDigit = true
                }

                c.code < 128 -> {
                    asciiRun++
                    lastWasDigit = false
                }

                else -> {
                    flush()
                    tokens += 0.5
                }
            }
        }
        flush()
        return (tokens * CONSERVATIVE_FACTOR).toInt()
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||   // 基本汉字
            code in 0x3400..0x4DBF ||      // 扩展 A
            code in 0xF900..0xFAFF ||      // 兼容汉字
            code in 0x3040..0x30FF ||      // 日文假名
            code in 0xAC00..0xD7AF ||      // 韩文
            code in 0xFF00..0xFFEF         // 全角标点/字母
    }
}
