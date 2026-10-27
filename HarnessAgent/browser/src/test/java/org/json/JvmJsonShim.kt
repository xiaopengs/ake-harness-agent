// 为什么这里有一份 `org.json`（测试专用，编译期影子实现）
// ===========================================================================
// `:browser` 的 P1 命脉 `DomJsonParser` 产在 Android 上跑，而它唯一的 JSON 库是
// Android 自带的 `org.json`。android.jar 在 **JVM 单元测试**里是一份桩实现：
//
//   java.lang.RuntimeException: Method optInt in org.json.JSONObject not mocked.
//
// 也就是说：**不解决 JSON，咽喉项一句都测不了**。仓库里已经有人踩过并且给出了答案 ——
// `data/src/main/.../store/FlatJson.kt` 的文件头写着：
//   「`org.json`：Android 上可用，但它在纯 JVM 单测里是抛异常的 stub ……
//     而这里的消费方要能在 `./gradlew :data:test` 里被覆盖 —— 用 org.json
//     等于主动放弃可测性。」
// `data/src/test/.../AkeMigrationSchemaTest.kt` 用了另一种同源于答案的写法：
// 在测试源码里自带一个 60 行的小解析器，换来「不依赖任何未在 catalog 里的坐标」。
//
// 这里是同一答案的第三种形态：**把桩影子掉**。
// 本文件位于 `src/test/java/org/json/`，编译产物落在 `:browser` 的 test classes 目录，
// 而 AGP 把 android.jar（mockable jar）放在单元测试 classpath 的**最后** ——
// 于是 :browser 单测里 `org.json.*` 解析到的是本文件，而不是抛异常的桩。
//
// ⚠ 三条纪律，改这个文件前先读：
//  1. **只实现 `DomJsonParser` / `WebViewDomPerception` 真正用到的那 12 个方法**，
//     不要长成一个残缺的 JSON 库（那个终点一定是某个转义 bug，见 FlatJson 文件头）。
//  2. 语义要**对齐真机上的 org.json**：取值失败返回 fallback、类型不符返回 fallback/null、
//     畸形输入构造时就抛 `JSONException`。写错语义 = 单测通过但结论不成立。
//     所以配套了 `JsonShimSelfTest`，先证明影子本身可信，再用它去测产品代码。
//  3. 它不是`（新依赖）`，也没进主源码：** production classpath 完全看不到这两个类**。
//
// 已知的、刻意保留的差异：真机 org.json 内部是 `HashMap`（`keys()` 顺序不定），
// 这里是 `LinkedHashMap`（按 JSON 文本顺序）。差别只体现在 `UiElement.attrs` 的迭代顺序，
// 而它被当作集合消费，故无影响。
// ===========================================================================

package org.json

/** 与真机 org.json 同名同位的异常类型：畸形 JSON 应当让 `runCatching` 抓得到。 */
public class JSONException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** `JSONArray`：只提供被测代码用到的三个取值方法 + 序列化。 */
public class JSONArray internal constructor(internal val items: List<Any?>) {

    public constructor() : this(emptyList<Any?>())

    public fun length(): Int = items.size

    public fun isNull(index: Int): Boolean = index !in items.indices || items[index] == null

    public fun optJSONObject(index: Int): JSONObject? = items.getOrNull(index) as? JSONObject

    public fun optJSONArray(index: Int): JSONArray? = items.getOrNull(index) as? JSONArray

    public fun opt(index: Int): Any? = items.getOrNull(index)

    public fun optString(index: Int, fallback: String): String =
        (items.getOrNull(index) as? String) ?: fallback

    public fun optInt(index: Int, fallback: Int): Int = JsonCoerce.asInt(items.getOrNull(index), fallback)

    override fun toString(): String = JsonCoerce.write(this.items)
}

/** `JSONObject`：只提供被测代码用到的取值方法 + 从 Map 构造 + 序列化。 */
public class JSONObject internal constructor(internal val fields: LinkedHashMap<String, Any?>) {

    /** 畸形 `json` ⇒ 抛 [JSONException]（与真机一致，`DomJsonParser.parse` 正依赖这一点）。 */
    public constructor(json: String) : this(JsonParser.parseRootObject(json))

    public constructor(map: Map<String, Any?>) : this(LinkedHashMap(map))

    public fun length(): Int = fields.size

    public fun has(name: String): Boolean = fields.containsKey(name)

    public fun isNull(name: String): Boolean = !fields.containsKey(name) || fields[name] == null

    public fun opt(name: String): Any? = fields[name]

    public fun keys(): Iterator<String> = fields.keys.iterator()

    public fun optJSONObject(name: String): JSONObject? = fields[name] as? JSONObject

    public fun optJSONArray(name: String): JSONArray? = fields[name] as? JSONArray

    public fun optString(name: String, fallback: String): String = (fields[name] as? String) ?: fallback

    public fun optInt(name: String, fallback: Int): Int = JsonCoerce.asInt(fields[name], fallback)

    public fun optInt(name: String): Int = optInt(name, 0)

    public fun optLong(name: String, fallback: Long): Long =
        JsonCoerce.asLong(fields[name]) ?: fallback

    public fun optBoolean(name: String, fallback: Boolean): Boolean =
        JsonCoerce.asBoolean(fields[name]) ?: fallback

    public fun optDouble(name: String, fallback: Double): Double =
        JsonCoerce.asDouble(fields[name]) ?: fallback

    override fun toString(): String = JsonCoerce.write(fields)
}

// ────────────────────────────────────────────────────────────── 解析

/** 递归下降解析器。零容错：坏输入抛 [JSONException]，绝不静默返回半棵树。 */
private object JsonParser {

    private class Cursor(private val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++
        }

        fun peek(): Char = s.getOrNull(i) ?: throw JSONException("JSON 意外结束于 $i")

        fun fail(what: String): Nothing = throw JSONException("JSON 解析失败 @$i：$what")

        fun expect(c: Char) {
            skipWs()
            if (peek() != c) fail("期待 '$c' 实得 '${peek()}'")
            i++
        }

        fun literal(word: String): Any? {
            if (!s.startsWith(word, i)) fail("期待字面量 $word")
            i += word.length
            return when (word) { "true" -> true; "false" -> false; else -> null }
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("字符串未闭合")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("转义未完成")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u 转义不足 4 位")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: fail("\\u$hex 不是十六进制")
                                i += 4
                                sb.append(code.toChar())
                            }
                            else -> fail("非法转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Number {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val raw = s.substring(start, i)
            if (raw.isEmpty() || raw == "-" || raw == "+") fail("不是数字")
            return if (isDouble) raw.toDoubleOrNull() ?: fail("非法浮点数 $raw")
            else raw.toLongOrNull() ?: (raw.toDoubleOrNull() ?: fail("非法整数 $raw"))
        }

        fun array(): JSONArray {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return JSONArray(out) }
            while (true) {
                out += value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return JSONArray(out) }
                    else -> fail("数组缺少 ',' 或 ']'")
                }
            }
        }

        fun obj(): JSONObject {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return JSONObject(out) }
            while (true) {
                skipWs()
                val key = string()
                expect(':')
                // 重复键：后者覆盖前者（与 org.json 的 put 语义一致）
                out[key] = value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return JSONObject(out) }
                    else -> fail("对象缺少 ',' 或 '}'")
                }
            }
        }

        fun value(): Any? {
            skipWs()
            val v = when (val c = s.getOrNull(i)) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    if (c == null) fail("空输入")
                    if (c == '-' || c == '+' || c in '0'..'9') number() else fail("非法字符 '$c'")
                }
            }
            return v
        }
    }

    /**
     * 解析一个**根必须是对象**的 JSON 文本。
     * 真机 `new JSONObject("[]")` / `new JSONObject("123")` 都会抛 JSONException，这里对齐。
     */
    fun parseRootObject(json: String): LinkedHashMap<String, Any?> {
        val c = Cursor(json)
        val v = c.value()
        c.skipWs()
        if (c.i != json.length) throw JSONException("JSON 结束后仍有内容 @${c.i}")
        return (v as? JSONObject)?.fields
            ?: throw JSONException("根不是 JSON 对象（实得 ${v?.javaClass?.simpleName}）")
    }
}

// ────────────────────────────────────────────────────────────── 取值语义与序列化

/**
 * 取值契约（逐条对齐 org.json）：
 *  - 键不存在 / 值为 null ⇒ **返回 fallback**（不抛，不返回 null）
 *  - 类型不符 ⇒ 返回 fallback（`optString`、`optInt` 等各自校对自有类型，跨类型只对 String 做尝试）
 *  - 字符串形式的数字参与 `optInt` / `optDouble`，与 org.json 一致
 */
private object JsonCoerce {

    fun asInt(v: Any?, fallback: Int): Int = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt() ?: fallback
        else -> fallback
    }

    fun asLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    fun asDouble(v: Any?): Double? = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun asBoolean(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.trim().lowercase()) { "true" -> true; "false" -> false; else -> null }
        else -> null
    }

    fun write(v: Any?): String = buildString {
        when (v) {
            null -> append("null")
            is String -> writeString(v)
            is Boolean -> append(if (v) "true" else "false")
            is Int, is Long -> append(v.toString())
            is Double -> append(if (v % 1.0 == 0.0) v.toLong().toString() else v.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) append(',')
                    first = false
                    writeString(k.toString())
                    append(':')
                    append(write(value))
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                for (value in v) {
                    if (!first) append(',')
                    first = false
                    append(write(value))
                }
                append(']')
            }
            is JSONObject -> append(write(v.fields))
            is JSONArray -> append(write(v.items))
            else -> writeString(v.toString())
        }
    }

    private fun StringBuilder.writeString(s: String) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

}
