package com.ake.agent.guard

import com.ake.core.common.AkeLog
import com.ake.core.ports.ContentSource
import com.ake.core.ports.Sanitized

/**
 * 闸 1 —— 注入清洗与 tripwire（架构 §8.3 / T1）。
 *
 * **为什么它是同步的**：它必须在「每次拼接 prompt 的路上被无条件调用」
 * （`PolicyPort.sanitize` 的签名就是 `fun` 不是 `suspend fun`）。
 * 一旦它是挂起的，调用点就会开始想着「这次要不跳过一下」—— 而跳过一次就是整个防线的洞。
 *
 * ## 三段处理，顺序不能换
 *
 *  ① **无条件清除**：chat-template 控制 token 序列与零宽字符。
 *     零宽字符是真实存在的隐写手段（把指令藏在商品标题的零宽空格里，人眼完全看不见，
 *     模型却把它读成 token）。
 *  ② **硬截断到 `source.maxLen`**：**预算即安全**。这条上限是为了对抗「用超长文本把 context 挤爆」——
 *     模型在 context 被挤爆时会「忘掉」SYSTEM 段里的安全宪章，宪章一忘，后面三道闸都失去语义支撑。
 *  ③ **tripwire 计数**：命中不一定是真攻击，但必须降级处理。
 *
 * ## 关于「误杀」
 *
 * 一个页面里出现 "navigate to https://..." 完全可能是正常的外链文案。
 * 所以 **1 次命中只打标（flagged）继续，≥2 次才整块屏蔽**（`InjectionIsolator` 消费这个结果）。
 * 这个阈值是刻意的：单条阈值会让正常页面大面积变 `[已屏蔽]`，
 * 而大面积误杀的后果是工程师调低阈值 → 防线名存实亡。
 */
class ContentSanitizer {

    fun sanitize(text: String, source: ContentSource): Sanitized {
        if (text.isEmpty()) return Sanitized(text = "", redacted = false)

        var t = CONTROL_TOKENS.replace(text, "")
        t = ZERO_WIDTH.replace(t, "")
        t = BIDI_OVERRIDE.replace(t, "")

        val truncated = t.length > source.maxLen
        if (truncated) t = t.substring(0, source.maxLen)

        val hits = TRIPWIRE.count { it.containsMatchIn(t) }

        return when {
            // 只有 PAGE 走「屏蔽」这条路：工具返回与用户输入里的指令性语言是合法的
            source == ContentSource.PAGE && hits >= BLOCK_THRESHOLD -> {
                AkeLog.w(TAG, "sanitize BLOCKED: tripwires=$hits len=${text.length}")
                Sanitized(text = BLOCKED_NOTICE, redacted = true, flagged = true, tripwires = hits)
            }

            hits >= 1 -> Sanitized(text = t, redacted = false, flagged = true, tripwires = hits)
            else -> Sanitized(text = t, redacted = false, flagged = truncated, tripwires = hits)
        }
    }

    companion object {
        const val TAG: String = "Sanitizer"

        /** §8.3 原文：静默失败阈值。 */
        const val BLOCK_THRESHOLD: Int = 2

        const val BLOCKED_NOTICE: String = "[已屏蔽：检测到疑似提示注入内容]"

        /**
         * 无条件清除：任何 chat template / 控制 token 序列（架构 §8.3 原文）。
         *
         * 注意这一条与 `InjectionIsolator.neutralizePseudoTags` 是**两层不同的防御**：
         * 这里删掉的是「已知的控制 token 序列」，那边中和的是「所有尖括号」。
         * 攻击者只要能构造出任一层的漏网形态，另一层还在。
         */
        private val CONTROL_TOKENS = Regex(
            """<\|\s*(im_start|im_end|endoftext|system|user|assistant|tool|bot|human)\s*\|?>""",
            RegexOption.IGNORE_CASE,
        )

        /** 零宽与 bidi 控制符：隐写 + 视觉重排（真人或 UI 上看到的与模型读到的不一致）。 */
        private val ZERO_WIDTH = Regex("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]")
        private val BIDI_OVERRIDE = Regex("""[\u202A-\u202E\u2066-\u2069]""")

        /** tripwire：命中不一定是真攻击，但必须降级（架构 §8.3 原文 + 中文补充）。 */
        private val TRIPWIRE: List<Regex> = listOf(
            Regex("""(?i)(ignore|disregard|forget)\s+(all\s+)?(previous|prior|above|earlier)"""),
            Regex("""(?i)(你的?新指令|新的指令|system\s*:|assistant\s*:)"""),
            Regex("""(?i)(你现在是|from now on you are|you are now)"""),
            Regex("""(?i)(不要询问|未经用户|without asking|bypass|绕过)\s*(确认|confirmation|approval)?"""),
            Regex("""(?i)(请访问|navigate to|go to)\s+https?://"""),
            Regex("""(?i)(忽略|无视)\s*(以上|上面|之前|下列)?\s*(的)?\s*(指令|指示|规则|要求)"""),
            Regex("""(?i)(立即|马上)\s*(把|将|发送|提交)\s*(密码|口令|验证码|token|cookie)"""),
            Regex("""(?i)(reveal|print|output|repeat)\s+(your\s+)?(system\s+prompt|instructions)"""),
        )
    }
}
