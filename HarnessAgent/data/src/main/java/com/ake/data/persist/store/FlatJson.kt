package com.ake.data.persist.store

/**
 * 极小的**扁平** JSON 编解码：只支持 `Map<String,String>` 与 `List<String>` 两种形状。
 *
 * ## 为什么自己写，而不是用 `org.json` 或 `kotlinx-serialization`
 *
 * 三选一的实际约束：
 *  - **`org.json`**：Android 上可用，但它在**纯 JVM 单测里是抛异常的 stub**
 *    （`JSONObject` 的 `getString` 在 classpath 上只有 `android.util` 的桩实现）。
 *    而本文件的两个消费方（`McpServerStoreImpl` / `SettingsStoreImpl`）
 *    要能在 `./gradlew :data:test` 里被覆盖 —— 用 `org.json` 等于主动放弃可测性。
 *  - **`kotlinx-serialization`**：表达力够，但它不在 `:data` 的依赖里，
 *    加坐标要邝随行统一处理，且为一个 `Map<String,String>` 拉一套编译插件不划算。
 *  - **手写**：60 行，零依赖，且**能在 JVM 单测里被逐分支覆盖**。
 *
 * 在一个「七个模块零测试」的项目里，第三条理由的权重高于前两条的表达力。
 *
 * ## 边界（写死在这里，别指望它长大）
 *
 * 它**不是**通用 JSON 解析器：不支持嵌套、不支持数字/布尔、不支持 Unicode 转义以外的花活。
 * 一旦哪天需要嵌套结构，正确的做法是**换掉这两张表的落法**（proto / 独立列），
 * 而不是把本文件扩展成一个残缺的 JSON 库 —— 那条路的终点一定是某个转义 bug。
 *
 * 解码**宽容**：坏输入返回空集合而不是抛异常。
 * 理由与 `planner/JsonTolerant` 一致 —— 这里的数据来自上一次写入，
 * 一次解析失败不该让整个设置页打不开；降级为空比崩溃更接近用户预期。
 */
internal object FlatJson {

    // ──────────────────────────────────────── 编码

    /** `Map<String,String>` → `{"k":"v",...}`。键按字典序，保证同一份输入恒得同一份输出（可 diff）。 */
    fun encodeMap(m: Map<String, String>): String {
        if (m.isEmpty()) return "{}"
        val sb = StringBuilder(64)
        sb.append('{')
        var first = true
        for (k in m.keys.sorted()) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(k)).append(':').append(quote(m[k].orEmpty()))
        }
        sb.append('}')
        return sb.toString()
    }

    /** `List<String>` → `["a","b"]`。保序，不去重 —— 域名白名单的显示顺序就是用户的输入顺序。 */
    fun encodeList(items: List<String>): String {
        if (items.isEmpty()) return "[]"
        val sb = StringBuilder(64)
        sb.append('[')
        var first = true
        for (v in items) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(v))
        }
        sb.append(']')
        return sb.toString()
    }

    // ──────────────────────────────────────── 解码

    /** `{"k":"v",...}` → Map。坏输入返回 `emptyMap()`。 */
    fun decodeMap(src: String): Map<String, String> {
        val body = src.trim()
        if (!body.startsWith('{') || !body.endsWith('}')) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = 1
        while (i < body.length - 1) {
            val keyEnd = skipTo(body, i, ':') ?: return out
            val key = unquote(body.substring(i, keyEnd).trim())
            i = keyEnd + 1
            val valueResult = readString(body, i) ?: return out
            out[key] = valueResult.first
            i = valueResult.second
            i = skipSeparators(body, i)
            if (i < body.length && body[i] == '}') break
        }
        return out
    }

    /** `["a","b"]` → List。坏输入返回 `emptyList()`。 */
    fun decodeList(src: String): List<String> {
        val body = src.trim()
        if (!body.startsWith('[') || !body.endsWith(']')) return emptyList()
        val out = ArrayList<String>(4)
        var i = 1
        while (i < body.length - 1) {
            val r = readString(body, i) ?: return out
            out += r.first
            i = skipSeparators(body, r.second)
            if (i < body.length && body[i] == ']') break
        }
        return out
    }

    // ──────────────────────────────────────── 内部

    private fun skipSeparators(s: String, from: Int): Int {
        var i = from
        while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        return i
    }

    /** 找到下一个「不在引号内」的 `:`。**跳过引号是刻意的**：值里含 `:`，键里也可能含转义引号。 */
    private fun skipTo(s: String, from: Int, target: Char): Int? {
        var inStr = false
        var escaped = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                target -> return i
                '}' -> return null          // 键还没写完就收尾 ⇒ 坏输入
            }
            i++
        }
        return null
    }

    private fun readString(s: String, from: Int): Pair<String, Int>? {
        var i = skipSeparators(s, from)
        if (i >= s.length || s[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '\\' -> {
                    if (i >= s.length) break
                    val e = s[i++]
                    sb.append(
                        when (e) {
                            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> ''
                            'u' -> {
                                val hex = s.substring(i, (i + 4).coerceAtMost(s.length))
                                i += hex.length
                                hex.toIntOrNull(16)?.toChar() ?: '?'
                            }
                            else -> e
                        },
                    )
                }
                c == '"' -> return sb.toString() to i
                else -> sb.append(c)
            }
        }
        return null
    }

    /**
     * 编码侧只转义四种字符。
     *
     * ★ 刻意**不转义非 ASCII**：这里的数据是人手录的 URL / 域名 / Header 值，
     *   `\uXXXX` 化之后 `adb shell sqlite3` 里一眼看不懂，而「配置落库到底写成了什么」
     *   恰恰是排查 MCP 连不上时最常问的问题。UTF-8 直存。
     */
    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (c in raw) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    /** 去掉两侧引号并反转义。输入不带引号时原样返回（宽容）。 */
    private fun unquote(raw: String): String {
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
        val sb = StringBuilder(raw.length)
        var i = 1
        while (i < raw.length - 1) {
            val c = raw[i++]
            if (c == '\\' && i < raw.length - 1) {
                val e = raw[i++]
                sb.append(
                    when (e) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> ''
                        'u' -> {
                            val hex = raw.substring(i, (i + 4).coerceAtMost(raw.length - 1))
                            i += hex.length
                            hex.toIntOrNull(16)?.toChar() ?: '?'
                        }
                        else -> e
                    },
                )
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}

