package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// **先给「尺子」做标定，再拿它去量产品代码。**
//
// `org/json/JvmJsonShim.kt` 是 `:browser` 单测里替代 android.jar 桩的影子实现。
// 它错任何一个字节，后面所有 DOM 抽取结论都不成立 —— 所以这里先把
// 「解析 / 取值语义 / 畸形输入」三条压死，后面的抽取用例才能信任它给出的每一个数字。
// ===========================================================================

class JsonShimSelfTest {

    // ── 解析

    @Test
    fun parsesScalarsObjectsAndArrays() {
        val root = org.json.JSONObject("""{"a":1,"b":2.5,"c":true,"d":false,"e":null,"f":[1,2,{"g":"x"}]}""")
        assertThat(root.optInt("a", 0)).isEqualTo(1)
        assertThat(root.optDouble("b", 0.0)).isEqualTo(2.5)
        assertThat(root.optBoolean("c", false)).isTrue()
        assertThat(root.optBoolean("d", true)).isFalse()
        assertThat(root.isNull("e")).isTrue()
        assertThat(root.optJSONArray("f")!!.length()).isEqualTo(3)
        assertThat(root.optJSONArray("f")!!.optJSONObject(2)!!.optString("g", "")).isEqualTo("x")
    }

    @Test
    fun parsesJsonProducedByItselfRoundTrip() {
        val original = mapOf<String, Any?>(
            "url" to "https://x.cn/a?b=1#f",
            "text" to "单价 ¥399 起 \"含税\"",
            "list" to listOf(1, "two", null, true, mapOf("k" to 1.25)),
            "unicode" to "你好​世界",
        )
        val round = org.json.JSONObject(org.json.JSONObject(original).toString())
        assertThat(round.optString("url", "")).isEqualTo("https://x.cn/a?b=1#f")
        assertThat(round.optString("text", "")).isEqualTo("单价 ¥399 起 \"含税\"")
        assertThat(round.optJSONArray("list")!!.length()).isEqualTo(5)
        assertThat(round.optJSONArray("list")!!.optString(1, "")).isEqualTo("two")
        assertThat(round.optString("unicode", "")).isEqualTo("你好​世界")
    }

    @Test
    fun escapesControlCharacters() {
        val s = "line1\nline2\t\"q\"\\/ /* keep */"
        val round = org.json.JSONObject(org.json.JSONObject(mapOf("s" to s)).toString())
        assertThat(round.optString("s", "")).isEqualTo(s)
    }

    @Test
    fun laterDuplicateKeyWins() {
        val root = org.json.JSONObject("""{"n":1,"n":2}""")
        assertThat(root.optInt("n", 0)).isEqualTo(2)
        assertThat(root.length()).isEqualTo(1)
    }

    // ── 取值语义（必须与真机 org.json 一致）

    @Test
    fun missingKeyReturnsFallback() {
        val root = org.json.JSONObject("""{"a":1}""")
        assertThat(root.optInt("missing", 7)).isEqualTo(7)
        assertThat(root.optInt("missing")).isEqualTo(0)
        assertThat(root.optString("missing", "fb")).isEqualTo("fb")
        assertThat(root.optBoolean("missing", true)).isTrue()
        assertThat(root.optDouble("missing", 3.5)).isEqualTo(3.5)
        assertThat(root.optJSONObject("missing")).isNull()
        assertThat(root.optJSONArray("missing")).isNull()
    }

    @Test
    fun wrongTypeReturnsFallbackInsteadOfThrowing() {
        val root = org.json.JSONObject("""{"s":"abc","n":5}""")
        assertThat(root.optInt("s", 11)).isEqualTo(11) // 非数字字符串 → fallback
        assertThat(root.optString("n", "fb")).isEqualTo("fb") // 数字不当字符串
        assertThat(root.optJSONObject("s")).isNull()
        assertThat(root.optBoolean("n", false)).isTrue() // 非零数字 → true
    }

    @Test
    fun numericStringCoercesForOptInt() {
        val root = org.json.JSONObject("""{"a":"42","b":"3.75"}""")
        assertThat(root.optInt("a", 0)).isEqualTo(42)
        assertThat(root.optDouble("b", 0.0)).isEqualTo(3.75)
    }

    @Test
    fun arrayOptStringUsesFallback() {
        val arr = org.json.JSONObject("""{"a":["x",null,3]}""").optJSONArray("a")!!
        assertThat(arr.optString(0, "fb")).isEqualTo("x")
        assertThat(arr.optString(1, "fb")).isEqualTo("fb")
        assertThat(arr.optString(9, "fb")).isEqualTo("fb")
        assertThat(arr.optJSONObject(2)).isNull()
        assertThat(arr.isNull(1)).isTrue()
    }

    @Test
    fun keysIterateInsertionOrder() {
        val root = org.json.JSONObject("""{"z":1,"m":2,"a":3}""")
        assertThat(root.keys().asSequence().toList()).containsExactly("z", "m", "a").inOrder()
    }

    // ── 畸形输入

    @Test
    fun malformedInputThrows() {
        for (bad in listOf("", "   ", "{oops", "{\"a\":}", "[1,2]", "123", "\"str\"", "null", "{\"a\":1} junk", "{,}")) {
            val threw = runCatching { org.json.JSONObject(bad) }.exceptionOrNull()
            assertWithMessage("bad=$bad 必须在构造时抛异常，否则 DomJsonParser 的 runCatching 兜不住").that(threw)
                .isInstanceOf(org.json.JSONException::class.java)
        }
    }
}
