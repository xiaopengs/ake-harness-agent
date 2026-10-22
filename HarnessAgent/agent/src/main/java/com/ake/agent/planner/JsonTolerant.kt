package com.ake.agent.planner

/**
 * 极小的**宽容** JSON 解析器。
 *
 * 为什么不用 kotlinx.serialization：模型输出的是「长得像 JSON 的东西」，不是 JSON。
 * 真实失败样本里出现过：单引号、尾逗号、未加引号的 key、中文全角引号、值里含未转义换行、
 * 外层裹了 ```json 围栏、JSON 前后带了自然语言说明、甚至输出了两个对象。
 * 严格解析器遇到这些会整体失败，而我们的补救成本是一次 4.5 秒的推理 —— 必须宽容。
 *
 * 设计取舍：只支持对象/数组/字符串/数字/布尔/null 这六种，够用；不支持 Unicode 转义以外的花活。
 * 解析失败返回 null，由 [StepActionParser] 走修复重试路径。
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    fun asString(): String? = when (this) {
        is Str -> value
        is Num -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        else -> null
    }

    /** §7.2 的坑：整数参数经常以 Double 回来，必须 `(it as Number).toInt()` 而不是 `as Int` */
    fun asInt(): Int? = when (this) {
        is Num -> value.toInt()
        is Str -> value.trim().toIntOrNull()
        else -> null
    }

    fun asBool(): Boolean? = when (this) {
        is Bool -> value
        is Str -> value.equals("true", true) || value == "1"
        is Num -> value != 0.0
        else -> null
    }

    fun asObject(): Map<String, JsonValue>? = (this as? Obj)?.fields
    fun asArray(): List<JsonValue>? = (this as? Arr)?.items

    fun asStringList(): List<String>? = asArray()?.mapNotNull { it.asString() }
}

object JsonTolerant {

    /** 动作字段名。约束解码回灌时我们把它当作普通字段参与同一套解析，避免两条路径行为分叉。 */
    const val ACT_FIELD = "act"

    /** 从任意文本中抠出**第一个**语法完整的 JSON 对象（会自动跳过前后的自然语言）。 */
    fun extractFirstObject(text: String): JsonValue.Obj? {
        val s = text
        var depth = 0
        var start = -1
        var inStr = false
        var escaped = false
        for (i in s.indices) {
            val c = s[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        parseObject(s.substring(start, i + 1))?.let { return it }
                        start = -1
                    }
                    if (depth < 0) depth = 0
                }
            }
        }
        return null
    }

    fun parseObject(src: String): JsonValue.Obj? = Parser(normalize(src)).run {
        val v = parseValue()
        if (!atEnd()) null else v as? JsonValue.Obj
    }

    /**
     * 修复已知的模型输出毛病。逐条都是真实样本驱动：
     *  1. markdown 围栏 ```json / ```
     *  2. 中文全角引号 “ ” ‘ ’ → "
     *  3. 中文逗号 、，→ , （仅出现在结构位置时替换风险大，这里只替换紧跟在 `"` 或 `}` 后的）
     *  4. 未加引号的 key：{act:"click"} → {"act":"click"}
     *  5. 尾逗号：[1,2,] → [1,2]
     *  6. 单引号字符串
     *  7. 字符串内部的裸换行 → \n
     */
    internal fun normalize(raw: String): String {
        var s = raw.trim()
        s = FENCE.replace(s, "$1")
        s = s.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
        // ★★ 这里踩过两个坑，都在 2026-10 补单测时被抓出来：
        //  ① 原写法是 `"$1"` —— 引用的是**分隔符**（`{` / `, `）而不是 key，
        //     于是 `{act:` 被修成 `"{"act:`，比不修更不可解析；
        //  ② 只改成 `$1"$2"` 仍不够：这个正则的**匹配范围包含末尾的冒号**
        //     （`(…)\s*:`），而替换串里不写回冒号就会把它吃掉 ⇒ `{"act""click"`。
        //  正确形态：`$1"$2":` —— 分隔符 + 加引号的 key + 补回冒号。
        s = UNQUOTED_KEY.replace(s, "$1\"$2\":")
        s = TRAILING_COMMA.replace(s, "$1")
        s = SINGLE_QUOTED.replace(s, "\"$1\"")
        s = BARE_NEWLINE_IN_STRING.replace(s, "\\n")
        s = s.replace(' ', ' ')
        return s
    }

    private val FENCE = Regex("""```(?:json)?\s*([\s\S]*?)```""")
    private val UNQUOTED_KEY = Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:""")
    private val TRAILING_COMMA = Regex(""",(\s*[}\]])""")
    private val SINGLE_QUOTED = Regex("""'([^'\\]*)'""")

    // 仅处理字符串内出现的裸换行（前一个是非转义字符、后一个是非引号）
    private val BARE_NEWLINE_IN_STRING = Regex("""([^\\])\n(?!\s*[}\]])""")

    private class Parser(val s: String) {
        var i = 0

        fun atEnd(): Boolean {
            var j = i
            while (j < s.length && s[j].isWhitespace()) j++
            return j >= s.length
        }

        fun parseValue(): JsonValue? {
            skipWs()
            if (i >= s.length) return null
            return when (val c = s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> parseNumber()
            }
        }

        private fun parseObj(): JsonValue.Obj? {
            expect('{')
            val out = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') { i++; return JsonValue.Obj(out) }
            while (true) {
                skipWs()
                if (peek() != '"') return null
                val k = parseString()
                skipWs()
                if (peek() != ':') return null
                i++
                val v = parseValue() ?: return null
                out[k] = v
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return JsonValue.Obj(out) }
                    else -> return null
                }
            }
        }

        private fun parseArr(): JsonValue.Arr? {
            expect('[')
            val out = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') { i++; return JsonValue.Arr(out) }
            while (true) {
                val v = parseValue() ?: return null
                out += v
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return JsonValue.Arr(out) }
                    else -> return null
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '\\' -> {
                        if (i >= s.length) break
                        val e = s[i++]
                        sb.append(
                            when (e) {
                                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> ''
                                'u' -> {
                                    val hex = s.substring(i, (i + 4).coerceAtMost(s.length))
                                    i += hex.length
                                    hex.toIntOrNull(16)?.toChar() ?: '?'
                                }
                                else -> e
                            }
                        )
                    }
                    c == '"' -> return sb.toString()
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): JsonValue? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            val d = t.toDoubleOrNull() ?: return null
            return JsonValue.Num(d)
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun expect(c: Char) { if (peek() == c) i++ }
        private fun expect(lit: String) { if (s.startsWith(lit, i)) i += lit.length }
    }
}
