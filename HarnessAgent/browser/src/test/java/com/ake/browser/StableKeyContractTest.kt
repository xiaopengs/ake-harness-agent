package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P1/P3 · stableKey：跨语言的**唯一契约**
//
// 架构 §5.6 说得很重：「编号漂移是 DOM 类 Agent 的第一大失败原因」，
// 必须靠「本轮编号 + 跨轮指纹」两套东西解决。而 stableKey 是「跨轮指纹」的全部载体。
//
// 它的特殊性在于：**同一个 fingerprint 有两个实现** —— Kotlin 一份
// （`DomJsonParser.stableKeyOf`）、JS 一份（`dom-extract.js` 的 `__stableKeyOf`）。
// 二者一旦不等，执行侧 `clickScript` 里的 `isSame(...)` **永远返回 false** ⇒
// 每一次点击都是 'STALE'。症状看起来是「页面疯狂重绘」，实际是两侧哈希口径不同 ——
// 这类 bug 在符号层完全验不出来，也只有跨口径对照才抓得到。
//
// ## 修复后 contract 的形状（四重不等价逐一收口）
//
//   ① 算法   ：两端同为「FNV-1a 32 × 2 个 offset basis」⇒ 同为 16 位小写 hex
//   ② 常量   ：唯一数据源是 `DomJsonParser` 的三个 `const val`，由 `BridgeHelpers.SCRIPT`
//              注入成全局变量 —— 结构上不可能漂
//   ③ role   ：null 两端都插空串（不再是 Kotlin 插 "null"、JS 插 ''）
//   ④ selector：由 Kotlin 传入 `isSame(el, selector, key)`，JS 不再自己重算
//   ⑤ 文本   ：两端都走 `compactText`（压空白 + 去零宽）后取前 24 字
//
// ## 这条契约在 JVM 上能验到什么程度（诚实边界）
//
// 单测里**没有 JS 引擎**，所以「JS 跑出来的字面值等于 Kotlin 跑出来的字面值」
// 这一条**必须真机 / node 才能确认**，这里做不到。这里能做的是：
//   · 常量同源（数值级比对，改一端不改另一端 ⇒ 红）
//   · 输入串的构造方式逐字比对（role / selector / 24 字截断 / 码元迭代）
//   · 算法结构的静态对照（FNV-1a 的 `^` 与 `*` 两个算子、定长补零）
// ===========================================================================

class StableKeyContractTest {

    private val jsAsset = java.io.File(browserMainDir(), "assets/js/dom-extract.js").readText()

    // ── Kotlin 侧语义

    @Test
    fun keyIsDeterministicHexOfSixteenChars() {
        val key = DomJsonParser.stableKeyOf("button", "div.pdp>button.buy", "加入购物车", "button")
        assertThat(key).hasLength(DomJsonParser.STABLE_KEY_LEN)
        assertThat(key).matches("[0-9a-f]{16}")
        assertThat(key).isEqualTo(DomJsonParser.stableKeyOf("button", "div.pdp>button.buy", "加入购物车", "button"))
    }

    @Test
    fun eachFieldChangesTheKey() {
        val base = DomJsonParser.stableKeyOf("button", "sel.a", "Buy", "button")
        assertThat(DomJsonParser.stableKeyOf("a", "sel.a", "Buy", "button")).isNotEqualTo(base)
        assertThat(DomJsonParser.stableKeyOf("button", "sel.b", "Buy", "button")).isNotEqualTo(base)
        assertThat(DomJsonParser.stableKeyOf("button", "sel.a", "Sell", "button")).isNotEqualTo(base)
        assertThat(DomJsonParser.stableKeyOf("button", "sel.a", "Buy", "link")).isNotEqualTo(base)
    }

    @Test
    fun textIsTruncatedAtTwentyFourChars() {
        val shared24 = "x".repeat(24)
        val a = DomJsonParser.stableKeyOf("button", "li.card>button.buy", shared24 + "跑鞋A", null)
        val b = DomJsonParser.stableKeyOf("button", "li.card>button.buy", shared24 + "跑鞋B", null)
        assertThat(a).isEqualTo(b)
        // 第 24 字本身还在线内 ⇒ 仍然参与指纹
        assertThat(
            DomJsonParser.stableKeyOf("button", "li.card>button.buy", "x".repeat(23) + "z", null),
        ).isNotEqualTo(a)
    }

    @Test
    fun nullRoleAndEmptyRoleProduceTheSameKey() {
        // （原失败·致命级）修复前 Kotlin 的 `"$tag|$role|..."` 在 role=null 时插的是
        // 字符串 **"null"** 四个字母，而 JS 侧 `(role || '')` 插的是空串 ⇒
        // 凡是 role 为 null 的元素（占绝大多数）两端永远不等。
        assertThat(DomJsonParser.stableKeyOf("div", "div.card", "text", null))
            .isEqualTo(DomJsonParser.stableKeyOf("div", "div.card", "text", ""))
    }

    @Test
    fun keyCoversUnicodeTextWithoutCrashing() {
        val emoji = DomJsonParser.stableKeyOf("button", "sel", "\uD83D\uDED2 加购 ✓ 零宽​字符", null)
        assertThat(emoji).matches("[0-9a-f]{16}")
    }

    /**
     * ★ **golden 向量：把「JS 跑出来的值」钉在 Kotlin 侧。**
     *
     * 下面的 16 位 hex 不是手算的，是**把真实的 `assets/js/dom-extract.js` 放进 node 执行**
     * （先注入 `BridgeHelpers.SCRIPT` 的三个常量，顺序与 `JsBootstrap.RESOURCES` 一致）、
     * 调 `globalThis.__stableKeyOf(...)` 得到的原值。
     *
     * 于是这条测试的实际含义是：**Kotlin 的输出必须与真机 JS 的输出逐字相同**。
     * 谁改了任何一侧的算法/常量，这里立刻红 —— 而红的时候必须重跑一遍 JS 重新取值，
     * 不许直接把期望值改掉。
     *
     * JVM 单测里没有 JS 引擎，这是本仓库在「不引 Robolectric、不依赖 node」的前提下
     * 能对这条契约做到的最强保证。
     */
    @Test
    fun goldenVectorsMatchWhatTheRealJsAssetProduces() {
        val vectors = listOf(
            // (tag, selector, text, role, 期望值)
            Quad("button", "div.pdp>button.buy", "加入购物车", "button") to "1e0c06c7494feacb",
            Quad("div", "div.card", "text", "") to "10d5a9c86fbc68cc",
            Quad("a", "#nav>a.home", "", "link") to "d62847d47c68e8e0",
            Quad("input", "form.s>input.q", "搜索鞋子", "textbox") to "ceb67310fcf1c5fc",
            Quad("button", "li.card>button.buy", "x".repeat(24) + "跑鞋A", "") to "b6a65b527313c2d6",
            Quad("span", "div.a>span", "emoji \uD83D\uDED2 零宽\u200B字", "") to "43274fcd96a49041",
            Quad("button", "sel", "a", "") to "7ebfc7182c553474",
        )
        for ((q, expected) in vectors) {
            val actual = DomJsonParser.stableKeyOf(q.tag, q.selector, q.text, q.role)
            assertWithMessage("${q.tag}|${q.role}|${q.selector}|${q.text.take(12)}")
                .that(actual).isEqualTo(expected)
        }
    }

    @Test
    fun nullRoleMatchesTheEmptyRoleVectorBecauseThatIsWhatJsSends() {
        // JS 没有 null role 的概念（`(role || '')`），所以 null 必须落到空串那一档
        assertThat(DomJsonParser.stableKeyOf("div", "div.card", "text", null))
            .isEqualTo("10d5a9c86fbc68cc")
    }

    @Test
    fun observationIdsAreUniqueButUnrelated() {
        val ids = (1..64).map { DomJsonParser.newObservationId() }.toSet()
        assertThat(ids).hasSize(64)
    }

    // ── Kotlin ↔ JS 口径：① 常量同源

    @Test
    fun hashConstantsAreSingleSourcedInKotlinAndInjectedIntoThePage() {
        // （原失败·最严重的一处）修复前：Kotlin 用 SHA-256 前 8 字节（恒 16 hex），
        // JS 用 FNV-1a 32 位（`h.toString(16)`，长度 1–8 且不补零）
        // ⇒ 两个值域甚至不相交 ⇒ `isSame()` 恒 false ⇒ 每次点击都是 'STALE'。
        //
        // 现在的保证是**结构性**的：常量只写在 Kotlin 里，页面里那一份是注入出来的。
        val injected = BridgeHelpers.SCRIPT
        assertThat(injected).contains("var AKE_FNV_PRIME = ${DomJsonParser.FNV_PRIME};")
        assertThat(injected).contains("var AKE_FNV_OFFSET_A = ${DomJsonParser.FNV_OFFSET_A};")
        assertThat(injected).contains("var AKE_FNV_OFFSET_B = ${DomJsonParser.FNV_OFFSET_B};")

        // JS 侧读的就是这三个全局变量（不是自己抄一份数字）
        assertThat(jsAsset).contains("typeof AKE_FNV_PRIME")
        assertThat(jsAsset).contains("typeof AKE_FNV_OFFSET_A")
        assertThat(jsAsset).contains("typeof AKE_FNV_OFFSET_B")

        // 兜底值（脱离 Android 单跑本文件时）也必须与 Kotlin 常量逐字相同
        assertWithMessage("asset 里的兜底常量与 Kotlin 不一致")
            .that(jsAsset).contains("AKE_FNV_PRIME : ${DomJsonParser.FNV_PRIME}")
        assertWithMessage("asset 里的兜底常量与 Kotlin 不一致")
            .that(jsAsset).contains("AKE_FNV_OFFSET_A : ${DomJsonParser.FNV_OFFSET_A}")
        assertWithMessage("asset 里的兜底常量与 Kotlin 不一致")
            .that(jsAsset).contains("AKE_FNV_OFFSET_B : ${DomJsonParser.FNV_OFFSET_B}")
    }

    @Test
    fun bothSidesUseTheSameTwoLaneFnvStructure() {
        // Kotlin：`h = ((h xor 码元) * PRIME) and 0xFFFFFFFF`，跑两遍（两个 offset basis）
        val kt = browserSource("java/com/ake/browser/DomJsonParser.kt").stripLineComments()
        assertThat(kt).contains("FNV_PRIME")
        assertThat(kt).contains("fnv1a32(input, FNV_OFFSET_A)")
        assertThat(kt).contains("fnv1a32(input, FNV_OFFSET_B)")
        assertThat(kt).contains("padStart(8, '0')") // 定长补零 —— 长度是契约的一部分

        // JS：`h = Math.imul(h ^ s.charCodeAt(i), KEY_PRIME)`，同样跑两遍
        assertThat(jsAsset).contains("Math.imul(h ^ s.charCodeAt(i), KEY_PRIME)")
        assertThat(jsAsset).contains("__fnv1a32(s, KEY_OFFSET_A)")
        assertThat(jsAsset).contains("__fnv1a32(s, KEY_OFFSET_B)")
        assertThat(jsAsset).contains("('00000000' + (h >>> 0).toString(16)).slice(-8)")
    }

    @Test
    fun bothSidesIterateUtf16CodeUnits_notUtf8Bytes() {
        // 中文 / emoji 上两端必须一致：Kotlin 按 `String` 的 Char（UTF-16 码元）迭代，
        // JS 按 `charCodeAt` 迭代。任一侧改成 UTF-8 字节都会在这里失去对照。
        val kt = browserSource("java/com/ake/browser/DomJsonParser.kt").stripLineComments()
        // `fnv1a32` 内部必须按码元迭代（`s[i].code`），不能按 UTF-8 字节
        val fnvBody = kt.substringAfter("public fun fnv1a32(").substringBefore("return h.toInt()")
        assertThat(fnvBody).contains("s[i].code")
        assertThat(fnvBody).doesNotContain("toByteArray")

        // JS 侧同样只按码元走（不引入 TextEncoder 之类的 UTF-8 编码路径）
        val jsFnvBody = jsAsset.substringAfter("function __fnv1a32(").substringBefore("return h;")
        assertThat(jsFnvBody).contains("s.charCodeAt(i)")
        assertThat(jsFnvBody).doesNotContain("TextEncoder")
        assertThat(jsFnvBody).doesNotContain("encodeURIComponent")
    }

    // ── Kotlin ↔ JS 口径：② 输入串构造

    @Test
    fun inputStringShapeIsIdenticalOnBothSides() {
        // Kotlin: "$tag|${role.orEmpty()}|$selector|${text.take(24)}"
        val kt = browserSource("java/com/ake/browser/DomJsonParser.kt").stripLineComments()
        assertThat(kt).contains("\"\$tag|\${role.orEmpty()}|\$selector|\${text.take(24)}\"")

        // JS: tag + '|' + role + '|' + selector + '|' + text.slice(0, 24)
        assertThat(jsAsset).contains("String(role || '')")
        assertThat(jsAsset).contains("String(text || '').slice(0, 24)")
    }

    @Test
    fun jsSideTakesTheSelectorFromKotlin_neverRecomputesIt() {
        // （原失败·第二重）修复前 JS 自己算 `tag + '.' + class首段`，
        // 与 Kotlin 哈希用的 `safeSelectorPath(el)`（形如 div.app>ul.list>li.item）
        // 根本不是同一个东西 ⇒ 即使算法一致也永不等。
        val executor = browserSource("java/com/ake/browser/WebViewExecutor.kt").stripLineComments()
        assertWithMessage("三个动作脚本都要把 selector 传给 isSame")
            .that(executor.countOccurrences("isSame(el, \${a.selector.js()}")).isEqualTo(3)
        assertThat(jsAsset).contains("function isSame(el, selector, expectedKey)")
        assertThat(jsAsset).doesNotContain("var selector = tag + (cls ? '.' + cls : '')")
    }

    @Test
    fun jsSideCompactsTextTheSameWayTheExtractorDoes() {
        // （原失败·第四重）抽取产物里的 text 是压过空白、去过零宽的；
        // 若 isSame 用 `textContent.trim()`，多行文本上两端必然不等。
        assertThat(jsAsset).contains("compactText(el.textContent || '').slice(0, 24)")
        assertThat(jsAsset).doesNotContain("el.textContent.trim()")
    }

    @Test
    fun theHelpersAreExportedByTheAssetAndDeclaredInTheBootstrapManifest() {
        // （原失败·第三重）修复前 `isSame` 定义在 `BridgeHelpers.SCRIPT` 里，
        // 而那份脚本从未被注入 ⇒ 点击脚本在 `isSame(...)` 处抛 ReferenceError。
        assertThat(jsAsset).contains("global.isSame = isSame;")
        assertThat(jsAsset).contains("global.__stableKeyOf = __stableKeyOf;")
        for (g in listOf("__extract", "isSame", "__akeInstalled")) {
            assertWithMessage("注入自检必须覆盖 $g").that(JsBootstrap.REQUIRED_GLOBALS).contains(g)
        }
    }

    @Test
    fun theSelfCheckWouldCatchAStableKeyThatDoesNotCompute() {
        // ★ 只断言「函数在不在」是不够的：常量没注进去时 `__stableKeyOf` 算出 NaN，
        //   「typeof === 'function'」照样绿。注入脚本里必须有 16 位 hex 的形状断言。
        assertThat(JsBootstrap.installScript(emptyMap())).contains("^[0-9a-f]{16}$")
    }

    @Test
    fun everyStableKeyCallSiteInScriptsIsGuardedByIsSame() {
        val executor = browserSource("java/com/ake/browser/WebViewExecutor.kt").stripLineComments()
        assertThat(executor.countOccurrences("if (!isSame(")).isEqualTo(3) // click / type / select
        assertThat(executor.countOccurrences("return 'STALE'")).isEqualTo(3)
    }

    private data class Quad(val tag: String, val selector: String, val text: String, val role: String)

    private companion object {
        fun String.countOccurrences(token: String): Int =
            windowed(token.length, 1) { sub -> if (sub == token) 1 else 0 }.sum()
    }
}
