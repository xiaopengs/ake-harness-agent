package com.ake.data.persist.modeldist

import com.ake.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `StrictJson` 的分支覆盖。
 *
 * 这里的每一条「拒绝」都对应 `StrictJson` 头部注释里的一条严格性声明。
 * 重点是那几条**宽容解析器会放行**的输入：尾逗号、重复 key、前导零、裸控制字符 ——
 * manifest 是带签名的安全输入，放过任何一条，就等于把「签名合法 ⇒ 内容可信」
 * 这个前提悄悄放宽了一格。
 */
@RunWith(JUnit4::class)
class StrictJsonTest {

    private fun ok(src: String): SjValue = (StrictJson.parse(src) as Outcome.Ok).value

    private fun fails(src: String): Boolean = StrictJson.parse(src) is Outcome.Err

    // ──────────────────────────────────────────── 接受

    @Test
    fun `嵌套对象与数组能解析`() {
        val v = ok("""{"a":{"b":[1,"x",true,null]}}""")
        val inner = v.asObject()!!["a"]!!.asObject()!!["b"]!!.asArray()!!
        assertThat(inner[0].asLong()).isEqualTo(1L)
        assertThat(inner[1].asString()).isEqualTo("x")
        assertThat(inner[2].asBool()).isTrue()
        assertThat(inner[3]).isInstanceOf(SjValue.Null::class.java)
    }

    @Test
    fun `超过 Int 范围的 size_bytes 能作为 Long 取回`() {
        val v = ok("""{"size_bytes":2782923040}""")
        assertThat(v.asObject()!!["size_bytes"]!!.asLong()).isEqualTo(2_782_923_040L)
        assertThat(v.asObject()!!["size_bytes"]!!.asInt()).isNull()
    }

    @Test
    fun `转义字符与 unicode 代理对能解析`() {
        val v = ok("""{"s":"a\"b\\c\nd","u":"😀"}""")
        assertThat(v.asObject()!!["s"]!!.asString()).isEqualTo("a\"b\\c\nd")
        assertThat(v.asObject()!!["u"]!!.asString()).isEqualTo("😀")
    }

    @Test
    fun `空对象与空数组能解析`() {
        assertThat(ok("{}").asObject()).isEmpty()
        assertThat(ok("[]").asArray()).isEmpty()
    }

    @Test
    fun `数字支持小数与指数但 asLong 对小数返回 null`() {
        assertThat(ok("""{"n":1.5}""").asObject()!!["n"]!!.asLong()).isNull()
        assertThat(ok("""{"n":-12}""").asObject()!!["n"]!!.asLong()).isEqualTo(-12L)
    }

    // ──────────────────────────────────────────── 拒绝

    @Test
    fun `拒绝尾逗号`() {
        assertThat(fails("""{"a":1,}""")).isTrue()
        assertThat(fails("""[1,2,]""")).isTrue()
    }

    @Test
    fun `拒绝重复 key`() {
        assertThat(fails("""{"a":1,"a":2}""")).isTrue()
    }

    @Test
    fun `拒绝单引号与未加引号的 key`() {
        assertThat(fails("""{'a':1}""")).isTrue()
        assertThat(fails("""{a:1}""")).isTrue()
    }

    @Test
    fun `拒绝文档后还有内容`() {
        assertThat(fails("""{"a":1} {"b":2}""")).isTrue()
    }

    @Test
    fun `拒绝非法数字形状`() {
        assertThat(fails("""{"n":01}""")).isTrue()
        assertThat(fails("""{"n":+1}""")).isTrue()
        assertThat(fails("""{"n":.5}""")).isTrue()
        assertThat(fails("""{"n":1.}""")).isTrue()
        assertThat(fails("""{"n":NaN}""")).isTrue()
        assertThat(fails("""{"n":Infinity}""")).isTrue()
    }

    @Test
    fun `拒绝字符串里的裸控制字符`() {
        assertThat(fails("{\"a\":\"x\ty\"}")).isTrue()
    }

    @Test
    fun `拒绝孤单的高代理项`() {
        assertThat(fails("""{"a":"\ud83d"}""")).isTrue()
        assertThat(fails("""{"a":"\ud83dA"}""")).isTrue()
    }

    @Test
    fun `拒绝截断与非法的 unicode 转义`() {
        assertThat(fails("""{"a":"\u12"}""")).isTrue()
        assertThat(fails("""{"a":"\uZZZZ"}""")).isTrue()
    }

    @Test
    fun `拒绝未闭合的结构`() {
        assertThat(fails("""{"a":1""")).isTrue()
        assertThat(fails("""[1,2""")).isTrue()
        assertThat(fails("""{"a":"x}""")).isTrue()
    }

    @Test
    fun `拒绝裸字面量与空输入`() {
        assertThat(fails("")).isTrue()
        assertThat(fails("   ")).isTrue()
        assertThat(fails("abc")).isTrue()
        assertThat(fails("""{"a":tru}""")).isTrue()
    }

    // ──────────────────────────────────────────── 编码

    @Test
    fun `quote 只转义 JSON 要求的字符且非 ASCII 直出`() {
        assertThat(StrictJson.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"")
        assertThat(StrictJson.quote("中文")).isEqualTo("\"中文\"")
        assertThat(StrictJson.quote("x\ty")).isEqualTo("\"x\\ty\"")
    }
}
