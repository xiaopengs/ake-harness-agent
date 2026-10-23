package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity

/**
 * 极小的**严格** JSON 解析器（manifest 专用）。
 *
 * ## 为什么自己写，而不是复用 `store/FlatJson` 或 `agent/planner/JsonTolerant`
 *
 *  | 现有实现        | 形状                        | 为什么不能用于 manifest |
 *  |-----------------|-----------------------------|-------------------------|
 *  | `FlatJson`      | 只支持 `Map<String,String>` | manifest 是嵌套结构（models 数组） |
 *  | `JsonTolerant`  | 嵌套齐全，但**刻意宽容**      | 宽容解析器会接受尾逗号/围栏/多对象 |
 *
 *  第二条是硬排除项：manifest 是**带签名的安全输入**。签名验的是字节，
 *  但如果解析器对「签名之外的花活」睁一只眼闭一只眼，就会出现
 *  「签名合法 ⇒ 整份文档可信」这个前提被悄悄放宽 —— 攻击者只要把 payload 藏进
 *  一段被宽容跳过的前缀文本里即可。**安全输入必须严格解析，一次歧义都不能有。**
 *
 * ## 为什么不用 kotlinx-serialization
 *
 * `:data` 当前依赖里没有它（`data/build.gradle.kts` 未声明，且本轮不允许改构建文件），
 * 为一个 200 行的解析器去动 catalog 不划算。与 `FlatJson` 的结论一致：
 * **零依赖 + 可在 JVM 单测里逐分支覆盖** 的权重高于表达力。
 * 若将来 `:data` 接入 kotlinx-serialization，本文件应整体删除而不是保留双份解析器。
 *
 * ## 严格性清单（每一条都有对应单测）
 *
 *  - 拒绝尾逗号、注释、单引号、未加引号的 key
 *  - 拒绝 `NaN` / `Infinity` / `+1` / `01` / `.5` / `1.`（数字按 RFC 8259 形状）
 *  - 拒绝顶层非对象/数组之外的裸值之外的「文档后还有内容」
 *  - 字符串接受 `\" \\ \/ \b \f \n \r \t \uXXXX`（含代理对）
 *  - **重复 key 直接报错**：`{"a":1,"a":2}` 在宽松解析里是「后者胜」，
 *    在签名文档里是「签名覆盖的内容与解析结果不一致」⇒ 必须失败
 */
internal sealed interface SjValue {
    data class Obj(val fields: Map<String, SjValue>) : SjValue
    data class Arr(val items: List<SjValue>) : SjValue
    data class Str(val value: String) : SjValue
    /** 保留原始字面量：manifest 里有 2.7e9 这种超过 Int 的 `size_bytes`，取整时不能先过 Double。 */
    data class Num(val raw: String) : SjValue
    data class Bool(val value: Boolean) : SjValue
    data object Null : SjValue
}

internal fun SjValue.asString(): String? = (this as? SjValue.Str)?.value

internal fun SjValue.asObject(): Map<String, SjValue>? = (this as? SjValue.Obj)?.fields

internal fun SjValue.asArray(): List<SjValue>? = (this as? SjValue.Arr)?.items

internal fun SjValue.asLong(): Long? = (this as? SjValue.Num)?.raw?.toLongOrNull()

internal fun SjValue.asInt(): Int? = asLong()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

internal fun SjValue.asBool(): Boolean? = (this as? SjValue.Bool)?.value

internal fun SjValue.asStringList(): List<String>? = asArray()?.map { it.asString() ?: return null }

internal object StrictJson {

    fun parse(src: String): Outcome<SjValue> = try {
        val cursor = Cursor(src)
        val value = cursor.readValue()
        cursor.skipWhitespace()
        if (!cursor.atEnd()) throw SjSyntaxError("trailing content at ${cursor.pos}")
        Outcome.Ok(value)
    } catch (e: SjSyntaxError) {
        Outcome.Err(
            AppError(
                code = AppError.CODE_INVALID_INPUT,
                reasonKey = "err_manifest_malformed_json",
                cause = IllegalArgumentException(e.message, e),
                severity = Severity.LOW,
            ),
        )
    }

    /** 字符串字面量编码（供签名规范化复用）。只转义 JSON 要求的字符，非 ASCII 直出 UTF-8。 */
    fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (c in raw) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    private class SjSyntaxError(message: String) : RuntimeException(message)

    private class Cursor(private val src: String) {
        var pos: Int = 0
            private set

        fun atEnd(): Boolean = pos >= src.length

        fun peek(): Char = src[pos]

        fun advance(): Char = src[pos++]

        fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(): SjValue {
            skipWhitespace()
            if (atEnd()) throw SjSyntaxError("unexpected end of input")
            return when (val c = peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> SjValue.Str(readString())
                't' -> readLiteral("true", SjValue.Bool(true))
                'f' -> readLiteral("false", SjValue.Bool(false))
                'n' -> readLiteral("null", SjValue.Null)
                '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> SjValue.Num(readNumber())
                else -> throw SjSyntaxError("unexpected '$c' at $pos")
            }
        }

        private fun readObject(): SjValue.Obj {
            advance() // '{'
            val out = LinkedHashMap<String, SjValue>()
            skipWhitespace()
            if (!atEnd() && peek() == '}') {
                advance()
                return SjValue.Obj(out)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || peek() != '"') throw SjSyntaxError("expected object key at $pos")
                val key = readString()
                // ★ 重复 key 直接失败：签名文档里「后者胜」等于默许内容被偷换
                if (out.containsKey(key)) throw SjSyntaxError("duplicate key \"$key\" at $pos")
                skipWhitespace()
                if (atEnd() || peek() != ':') throw SjSyntaxError("expected ':' at $pos")
                advance()
                out[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw SjSyntaxError("unterminated object at $pos")
                when (advance()) {
                    ',' -> {
                        skipWhitespace()
                        // ★ 尾逗号：`{ "a":1, }` 在宽容解析里合法，这里必须拒绝
                        if (!atEnd() && peek() == '}') throw SjSyntaxError("trailing comma at $pos")
                    }
                    '}' -> return SjValue.Obj(out)
                    else -> throw SjSyntaxError("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun readArray(): SjValue.Arr {
            advance() // '['
            val out = ArrayList<SjValue>()
            skipWhitespace()
            if (!atEnd() && peek() == ']') {
                advance()
                return SjValue.Arr(out)
            }
            while (true) {
                out += readValue()
                skipWhitespace()
                if (atEnd()) throw SjSyntaxError("unterminated array at $pos")
                when (advance()) {
                    ',' -> {
                        skipWhitespace()
                        if (!atEnd() && peek() == ']') throw SjSyntaxError("trailing comma at $pos")
                    }
                    ']' -> return SjValue.Arr(out)
                    else -> throw SjSyntaxError("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            advance() // opening '"'
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw SjSyntaxError("unterminated string at $pos")
                when (val c = advance()) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw SjSyntaxError("dangling escape at $pos")
                        when (val e = advance()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> appendUnicodeEscape(sb)
                            else -> throw SjSyntaxError("invalid escape '\\$e' at ${pos - 1}")
                        }
                    }
                    // ★ 裸控制字符：JSON 不允许未转义的换行出现在字符串里
                    in '\u0000'..'\u001F' -> throw SjSyntaxError("raw control char in string at ${pos - 1}")
                    else -> sb.append(c)
                }
            }
        }

        /**
         * `\uXXXX`。代理对必须成对出现：单个 `\uD83D` 在宽松解析里会合成一个非法码点，
         * 而它落在 `variant` / `url` 这类字段上时，后果是「签名合法但字段语义被改写」。
         */
        private fun appendUnicodeEscape(sb: StringBuilder) {
            if (pos + 4 > src.length) throw SjSyntaxError("truncated \\u escape at $pos")
            val hex = src.substring(pos, pos + 4)
            if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                throw SjSyntaxError("invalid \\u escape at $pos")
            }
            pos += 4
            val code = hex.toInt(16)
            if (code !in 0xD800..0xDBFF) {
                sb.append(code.toChar())
                return
            }
            // 高代理项：后面必须紧跟一个 `\uDC00..\uDFFF`
            if (pos + 6 > src.length || src[pos] != '\\' || src[pos + 1] != 'u') {
                throw SjSyntaxError("unpaired high surrogate at $pos")
            }
            val lowHex = src.substring(pos + 2, pos + 6)
            if (lowHex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                throw SjSyntaxError("invalid low surrogate at $pos")
            }
            pos += 6
            val low = lowHex.toInt(16)
            if (low !in 0xDC00..0xDFFF) throw SjSyntaxError("unpaired high surrogate at $pos")
            val codePoint = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
            sb.append(((codePoint - 0x10000) ushr 10).plus(0xD800).toChar())
                .append(((codePoint - 0x10000) and 0x3FF).plus(0xDC00).toChar())
        }

        private fun readNumber(): String {
            val start = pos
            if (!atEnd() && peek() == '-') advance()
            // 整数部分：0 或 [1-9][0-9]*（拒绝 01 / +1 / .5）
            if (atEnd()) throw SjSyntaxError("truncated number at $pos")
            when {
                peek() == '0' -> advance()
                peek() in '1'..'9' -> while (!atEnd() && peek() in '0'..'9') advance()
                else -> throw SjSyntaxError("invalid number at $pos")
            }
            if (!atEnd() && peek() == '.') {
                advance()
                if (atEnd() || peek() !in '0'..'9') throw SjSyntaxError("invalid fraction at $pos")
                while (!atEnd() && peek() in '0'..'9') advance()
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                advance()
                if (!atEnd() && (peek() == '+' || peek() == '-')) advance()
                if (atEnd() || peek() !in '0'..'9') throw SjSyntaxError("invalid exponent at $pos")
                while (!atEnd() && peek() in '0'..'9') advance()
            }
            val raw = src.substring(start, pos)
            // NaN / Infinity 走的是标识符路径，不会到这里；这里再挡一次「-」单独出现
            if (raw == "-") throw SjSyntaxError("invalid number at $start")
            return raw
        }

        private fun readLiteral(text: String, value: SjValue): SjValue {
            if (pos + text.length > src.length || src.substring(pos, pos + text.length) != text) {
                throw SjSyntaxError("invalid literal at $pos")
            }
            pos += text.length
            return value
        }
    }
}
