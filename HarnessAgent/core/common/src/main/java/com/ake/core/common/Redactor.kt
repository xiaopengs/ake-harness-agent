package com.ake.core.common

/**
 * R0 脱敏器 —— 「采集即脱敏」的执行体（数据库 §8.1 / F3）。
 *
 * **为什么是纯 Kotlin、且必须是同步、ad-hoc 的**：
 *
 * F3 指出架构 §5.2（采集期保留 `UiElement.attrs.value`）与 §8.7（落盘前用正则删掉它）
 * 互相打架 —— 一个保留一个删。修正方向是**在采集点就按元素语义判定**，
 * 而不是让一段文本先流进去、再指望后面有人捞出来。
 *
 * 于是 `Redactor` 做成零依赖纯函数：
 *  - 可以在 `:browser` 采集元素文本的那一刻调用（而不是等到 Room 门口）
 *  - 可以在 JVM 上毫秒级回归 —— 这是脱敏代码唯一能建立信心的方式
 *    （这类 bug 在线上表现为「已经泄漏了但没人知道」）
 *
 * **必须返回计数与类型指纹**（F3(d)）：否则轨迹回放时看到 `[已屏蔽]` 的工程师
 * 无法区分「这是脱敏干的」还是「模型失败导致的」，会花半天查一个不存在的 bug。
 */
public enum class RedactionKind(public val token: String) {
    JWT("JWT"),
    BEARER("BEARER"),
    COOKIE("COOKIE"),
    API_KEY("API_KEY"),
    PRIVATE_KEY("PRIVATE_KEY"),
    PASSWORD("PASSWORD"),
    AUTH_HEADER("AUTH_HEADER"),
    CREDIT_CARD("CREDIT_CARD"),
    CVV("CVV"),
    ID_CARD("ID_CARD"),
    OTP("OTP"),
}

public data class RedactionResult(
    val text: String,
    val redactionCount: Int,
    val redactionTypes: Set<String>,
)

public object Redactor {

    /** 规则顺序 = 优先级。宽泛规则放后面（否则 `Bearer xxx` 会被当成 API_KEY 干掉，类型指纹就失真了）。 */
    private val RULES: List<Triple<Regex, RedactionKind, String>> = listOf(
        Triple(
            Regex("-----BEGIN[\\s\\S]{0,80}?PRIVATE KEY-----[\\s\\S]*?-----END[\\s\\S]{0,80}?PRIVATE KEY-----"),
            RedactionKind.PRIVATE_KEY,
            "<redacted:PRIVATE_KEY>",
        ),
        Triple(
            Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}"),
            RedactionKind.JWT,
            "<redacted:JWT>",
        ),
        Triple(
            Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/\\-]{8,}=*"),
            RedactionKind.BEARER,
            "<redacted:BEARER>",
        ),
        Triple(
            Regex("(?i)(authorization\\s*[:=]\\s*)(?:\\S+)"),
            RedactionKind.AUTH_HEADER,
            "$1<redacted:AUTH_HEADER>",
        ),
        Triple(
            Regex("(?i)(cookie\\s*[:=]\\s*)([^;\\n]{6,})"),
            RedactionKind.COOKIE,
            "$1<redacted:COOKIE>",
        ),
        Triple(
            // ★ `["']?` 必须出现在键名之后：`\b` 是零宽的，不会吃掉 JSON 的闭合引号。
            //   写成 `(key)\b\s*[:=]` 时，`"password":"x"` 永远匹配不上（`:` 位置上是 `"`）。
            Regex("(?i)\\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret[_-]?key)[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9._~+/\\-]{8,})"),
            RedactionKind.API_KEY,
            "$1=<redacted:API_KEY>",
        ),
        Triple(
            Regex("(?i)\\b(password|passwd|pwd|passcode)[\"']?\\s*[:=]\\s*[\"']?([^\\s\"'&,;]{3,})"),
            RedactionKind.PASSWORD,
            "$1=<redacted:PASSWORD>",
        ),
        Triple(
            Regex("(?i)\\b(cvv|cvc|security\\s*code)\\b\\D{0,6}(\\d{3,4})\\b"),
            RedactionKind.CVV,
            "$1 <redacted:CVV>",
        ),
        Triple(
            Regex("(?i)\\b(verification\\s*code|otp|dynamic\\s*code)\\b\\D{0,6}(\\d{4,8})\\b"),
            RedactionKind.OTP,
            "$1 <redacted:OTP>",
        ),
        Triple(
            Regex("\\b(?:\\d[ -]?){15,18}\\d\\b"), // 卡号：13–19 位，后面必须过 Luhn
            RedactionKind.CREDIT_CARD,
            "<redacted:CREDIT_CARD>",
        ),
        Triple(
            Regex("\\b\\d{17}[\\dXx]\\b"), // 18 位身份证
            RedactionKind.ID_CARD,
            "<redacted:ID_CARD>",
        ),
    )

    public fun scrub(text: String): RedactionResult {
        if (text.isEmpty()) return RedactionResult(text, 0, emptySet())
        var out = text
        var total = 0
        val hits = LinkedHashSet<String>()

        for ((regex, kind, replacement) in RULES) {
            var hitsHere = 0
            out = regex.replace(out) { m ->
                // 卡号必须过 Luhn，否则订单号/时间戳这类长数字会被整段打码（业务数据丢失）
                if (kind == RedactionKind.CREDIT_CARD && !passesLuhn(m.value)) {
                    return@replace m.value
                }
                hitsHere++
                // ★ 关键：`$1` 只在「字符串模板版」replace 里被解释成捕获组。
                //   这里是 lambda 版，返回值按字面使用 ⇒ 必须手动做组替换，
                //   否则会把字面量 `$1` 写进 prompt（一个极难发现的 bug）。
                if (replacement.contains('$')) {
                    m.groupValues.let { g ->
                        replacement.replace(Regex("\\$(\\d)")) { mr ->
                            val idx = mr.groupValues[1].toInt()
                            if (idx < g.size) g[idx] else ""
                        }
                    }
                } else {
                    replacement
                }
            }
            if (hitsHere > 0) {
                total += hitsHere
                hits += kind.token
            }
        }
        return RedactionResult(out, total, hits.toSet())
    }

    /** 是否含有 R0 凭据特征。用于 PolicyPort 判定与「这一步要不要 interrupt」。 */
    public fun containsCredential(text: String): Boolean = scrub(text).redactionCount > 0

    public fun redactionCountOf(text: String): Int = scrub(text).redactionCount

    /**
     * Luhn 校验。
     *
     * 卡号规则必须过 Luhn，否则「订单编号 6222020123456789」这类 16 位数字会被整段打码，
     * 直接导致任务所需的业务数据丢失 —— 宁可漏打一个卡号，也不能把订单号变成 `<redacted>`。
     */
    private fun passesLuhn(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        var sum = 0
        var alternate = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (alternate) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alternate = !alternate
        }
        return sum % 10 == 0
    }
}
