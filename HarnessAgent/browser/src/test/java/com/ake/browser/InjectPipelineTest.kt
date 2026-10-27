package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P1 · 「注入链路」体检
//
// `DomJsonParser` 再结实，也要有人把 `__extract` 喂进页面才有输入。这一份不看算法，
// 只看**链路两端是否真的对得上**：
//   ① 谁负责把 `assets/js/dom-extract.js` 注进 WebView？（Kotlin 侧字符串拼接 ⇒ 没有任何编译期检查）
//   ② Kotlin 拼进 `OPTS` 的 key，和 JS 里 `opts.xxx` 读的 key 是不是同一套？
//   ③ 三份必须常驻页面的脚本，是不是都在同一份清单里、由同一个宿主注入？
//
// 这类不一致的 Failure mode 全是静默的：key 拼错 ⇒ JS 走默认值（或直接得到 NaN）
// ⇒ 「明明传了 includeTextBelowFold=true，还是抽不到折叠下方」这种难归因问题。
//
// ★ 本文件里带 “（原失败）” 注解的断言，修复前是**红**的：它们现在锁的是修复后的行为。
// ===========================================================================

class InjectPipelineTest {

    private val extractorJs = java.io.File(browserMainDir(), "assets/js/dom-extract.js")
    private val perception = browserSource("java/com/ake/browser/WebViewDomPerception.kt").stripLineComments()
    private val executor = browserSource("java/com/ake/browser/WebViewExecutor.kt").stripLineComments()

    @Test
    fun extractorAssetExistsOnDisk() {
        assertThat(extractorJs.isFile).isTrue()
        assertThat(extractorJs.readText()).contains("global.__extract = __extract;")
    }

    @Test
    fun observeScriptCallsTheJsEntryPoint() {
        assertThat(perception).contains("var result = __extract(OPTS);")
    }

    // ───────────────────────────────────────────── ① 谁把 asset 读进页面

    @Test
    fun exactlyOneBrowserSourceOpensTheAsset() {
        // （原失败）修复前 `browserMainSources()` 里一处 `assets.open` / `AssetManager` /
        // `dom-extract` 都没有 ⇒ `__extract` 只活在 asset 文件里，从未进入页面上下文
        // ⇒ observe 的 IIFE 抛 ReferenceError ⇒ 没有任何 deliver ⇒ `err_perceive_timeout`。
        //
        // 「只许一处」和「必须有一处」同等重要：读 asset 的地方有两个，
        // 就会出现「一边读到了新下发版本、一边读到了包内版本」的第二类漂移。
        val readers = browserMainSources()
            .filter { "assets.open" in it.readText().stripLineComments() }
            .map { it.name }
        assertThat(readers).containsExactly("BrowserRuntime.kt")
    }

    @Test
    fun bootstrapManifestDeclaresAllThreeResidentScripts() {
        // （原失败）三份脚本曾各自散落、且都没有注入者。现在必须同属一份清单。
        assertThat(JsBootstrap.RESOURCES.map { it.id }).containsExactly(
            "stable-key-constants",
            "dom-extract",
            "stability-probe",
        ).inOrder()
    }

    @Test
    fun theDeclaredAssetResolvesToAFileThatExistsOnDisk() {
        val assets = JsBootstrap.RESOURCES.filter { it.kind == JsResourceKind.ASSET }
        assertThat(assets).hasSize(1)
        val path = assets.single().body
        assertWithMessage("清单里的 asset 路径 $path 必须在磁盘上存在")
            .that(java.io.File(browserMainDir(), "assets/$path").isFile).isTrue()
        assertThat(java.io.File(browserMainDir(), "assets/$path").readText())
            .contains("global.__extract = __extract;")
    }

    @Test
    fun stableKeyConstantsAreInjectedBeforeTheAssetThatReadsThem() {
        // ★ 顺序是契约：`dom-extract.js` 在闭包求值时读 `AKE_FNV_*` 三个全局变量，
        //   常量段排在其后会让它们变成 undefined ⇒ 尽管有兜底值，仍是两套数据源。
        val kinds = JsBootstrap.RESOURCES.map { it.kind }
        assertThat(kinds).isNotEmpty()
        val constantsAt = JsBootstrap.RESOURCES.indexOfFirst { it.id == "stable-key-constants" }
        val assetAt = JsBootstrap.RESOURCES.indexOfFirst { it.id == "dom-extract" }
        assertThat(constantsAt).isLessThan(assetAt)
    }

    @Test
    fun installedScriptCarriesTheAssetBodyAndSelfChecksTheGlobals() {
        val asset = java.io.File(browserMainDir(), "assets/${JsBootstrap.DOM_EXTRACT_ASSET}").readText()
        val script = JsBootstrap.installScript(mapOf(JsBootstrap.DOM_EXTRACT_ASSET to asset))

        assertThat(script).contains("global.__extract = __extract;")
        assertThat(script).contains(StabilityScripts.INSTALL.trim())
        assertThat(script).contains("var AKE_FNV_PRIME = ${DomJsonParser.FNV_PRIME};")
        for (g in JsBootstrap.REQUIRED_GLOBALS) {
            assertWithMessage("注入脚本必须自检全局 $g").that(script).contains(g)
        }
        assertThat(script).contains("return AKE_MISSING.join(',')")
    }

    @Test
    fun aMissingAssetIsReportedInsteadOfSilentlySkipped() {
        // （原失败）资产缺失时不能只是「少了一段脚本」—— 那会退化成 bridge 超时。
        val script = JsBootstrap.installScript(emptyMap())
        assertThat(script).contains("AKE_MISSING.push('dom-extract');")
    }

    @Test
    fun bothPortsEnsureTheRuntimeBeforeUsingIt() {
        // （原失败）注入曾经没有任何调用方。
        assertThat(perception).contains("scriptHost.ensureReady()")
        assertThat(executor).contains("scriptHost.ensureReady()")
        // 感知侧两个入口（observe / extractRecords）+ 执行侧两个入口（execute / waitForSettled）
        assertThat(perception.countOccurrences("scriptHost.ensureReady()")).isEqualTo(2)
        assertThat(executor.countOccurrences("scriptHost.ensureReady()")).isEqualTo(2)
    }

    @Test
    fun navigationInvalidatesTheHostCache() {
        // 换 document ⇒ 注入的全局量被清空 ⇒ 必须让宿主缓存失效。
        // 两条路都要有：我们自己 loadUrl 的，和页面自己点链接跳的。
        assertThat(executor).contains("scriptHost.invalidate()")
        assertThat(browserSource("java/com/ake/browser/WebViewFactory.kt").stripLineComments())
            .contains("onPageStarted")
    }

    // ───────────────────────────────────────────── ② OPTS key 对齐

    @Test
    fun optsKeyNamesLineUpBetweenKotlinAndJs() {
        val js = extractorJs.readText()
        for (key in shared) {
            assertWithMessage("Kotlin 应发出 $key").that(perception).contains("$key:")
            assertWithMessage("JS 应读取 opts.$key").that(js).contains("opts.$key")
        }
    }

    @Test
    fun everyKeyKotlinEmitsIsAKeyJsActuallyReads() {
        // （原失败）这是把「两端 key 是否同一套」变成一条通用断言，而不是逐个点名。
        val js = extractorJs.readText()
        val optsBlock = perception.substringAfter("var OPTS = {").substringBefore("};")
        val emitted = Regex("""(\w+):""").findAll(optsBlock).map { it.groupValues[1] }.toList()

        assertThat(emitted).isNotEmpty()
        for (key in emitted) {
            assertWithMessage(
                "Kotlin 发出 `$key:`，但 JS 里没有 `opts.$key` —— 该参数传过去没人接"
            ).that(js).contains("opts.$key")
        }
    }

    @Test
    fun viewportExpansionKeyMatchesExactly_notJustByPrefix() {
        // ★（原失败·比另外两条严重一个量级）Kotlin 曾发 `viewportExpansion`，
        //   JS 读 `opts.viewportExpansionPx` ⇒ `minY = -undefined = NaN`
        //   ⇒ `rect.bottom > NaN` 恒 false ⇒ **整页候选恒为空**。
        //   注意：光断言 `js.contains("viewportExpansion")` 是**验不出来**的 ——
        //   它是 `viewportExpansionPx` 的子串，所以这里必须断言完整 key。
        assertThat(perception).contains("viewportExpansionPx:")
        assertThat(perception).doesNotContain("viewportExpansion:")
        assertThat(extractorJs.readText()).contains("opts.viewportExpansionPx")
    }

    @Test
    fun belowFoldFlagIsEmittedUnderTheNameJsReads() {
        // （原失败）Kotlin 写 `includeBelowFold:`，JS 读 `opts.includeTextBelowFold`
        // ⇒ 该开关恒为 undefined ⇒ 恒按视口裁剪。
        assertThat(perception).contains("includeTextBelowFold:")
        assertThat(perception).doesNotContain("includeBelowFold:")
        assertThat(extractorJs.readText()).contains("opts.includeTextBelowFold")
    }

    @Test
    fun maxRecordsHintIsEmittedUnderTheNameJsReads() {
        // （原失败）Kotlin 发 `maxRecords:`，JS 读 `opts.maxRecordsRows`
        // ⇒ `ObserveOptions.maxRecordsHint` 形同虚设，行数恒为 JS 默认的 60。
        assertThat(perception).contains("maxRecordsRows:")
        assertThat(perception).doesNotContain("maxRecords:")
        assertThat(extractorJs.readText()).contains("opts.maxRecordsRows")
    }

    @Test
    fun vendorAbilityToOverrideTheScriptNowHasAWiredOwner() {
        // build.gradle.kts 的注释写着「可远端下发替换 asset 修一个选择器 bug」。
        // 现在这条退路是通的：asset 由 JsBootstrap 清单声明、由 BrowserRuntime 唯一一处读取。
        assertThat(java.io.File(repoRoot(), "browser/build.gradle.kts").readText())
            .contains("dom-extract.js 走 assets")
        assertThat(JsBootstrap.RESOURCES.any { it.kind == JsResourceKind.ASSET }).isTrue()
    }

    private companion object {
        val shared = listOf("maxTextLen", "viewportExpansionPx", "maxNodes", "maxDepth")

        fun String.countOccurrences(token: String): Int =
            windowed(token.length, 1) { sub -> if (sub == token) 1 else 0 }.sum()
    }
}
