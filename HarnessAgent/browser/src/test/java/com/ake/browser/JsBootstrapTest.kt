package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

// ===========================================================================
// P0 · 注入管线的**行为**测试（不是静态体检）
//
// `InjectPipelineTest` 验的是「清单里写了什么」，这一份验的是
// 「宿主在什么条件下真的注入、注入失败时会不会静默继续」。
//
// ★ 之所以能测：`PageScriptHost` 的三个依赖全是 lambda（`evaluate` / `assetReader` /
//   `documentKey`），没有任何 Android API。不用 Robolectric —— 它的 WebView 是假的，
//   测出来的东西不可信。
// ===========================================================================

class JsBootstrapTest {

    private val assetBody = java.io.File(browserMainDir(), "assets/${JsBootstrap.DOM_EXTRACT_ASSET}").readText()

    // ───────────────────────────────────────────── installScript 的形状

    @Test
    fun installScriptIsASingleIifeThatReturnsTheMissingList() {
        val script = JsBootstrap.installScript(mapOf(JsBootstrap.DOM_EXTRACT_ASSET to assetBody))
        assertThat(script).startsWith("(function(){")
        assertThat(script.trimEnd()).endsWith("})();")
        assertThat(script).contains("return AKE_MISSING.join(',')")
    }

    @Test
    fun installScriptCarriesAllThreeResourcesInOrder() {
        val script = JsBootstrap.installScript(mapOf(JsBootstrap.DOM_EXTRACT_ASSET to assetBody))
        val constantsAt = script.indexOf("AKE_FNV_PRIME")
        val extractAt = script.indexOf("global.__extract = __extract;")
        val stabilityAt = script.indexOf("MutationObserver")

        assertWithMessage("三段脚本必须都在注入脚本里").that(listOf(constantsAt, extractAt, stabilityAt)).containsNoDuplicates()
        assertThat(constantsAt).isGreaterThan(-1)
        assertThat(extractAt).isGreaterThan(-1)
        assertThat(stabilityAt).isGreaterThan(-1)
        assertWithMessage("stableKey 常量必须先于 dom-extract").that(constantsAt).isLessThan(extractAt)
    }

    @Test
    fun missingAssetIsReportedAsAMissingResourceNotAsAnEmptyScript() {
        val script = JsBootstrap.installScript(emptyMap())
        assertThat(script).contains("AKE_MISSING.push('dom-extract');")
        // 但内联的两段照常注入 —— 一个资源缺失不该拖垮另外两个
        assertThat(script).contains("MutationObserver")
        assertThat(script).contains("AKE_FNV_PRIME")
    }

    // ───────────────────────────────────────────── PageScriptHost 状态机

    @Test
    fun installsOncePerDocumentAndReinstallsAfterTheDocumentChanges() {
        var url = "https://shop.example.com/list"
        val evaluated = ArrayList<String>()
        val host = PageScriptHost(
            evaluate = { js -> evaluated += js; "" },
            assetReader = { assetBody },
            documentKey = { url },
        )

        runBlocking {
            assertThat(host.ensureReady()).isTrue()
            assertThat(host.ensureReady()).isTrue() // 同一文档：命中缓存，不再注入
            assertWithMessage("同一文档只应注入一次").that(evaluated).hasSize(1)

            url = "https://shop.example.com/detail?id=1"
            assertThat(host.ensureReady()).isTrue()
            assertWithMessage("换文档必须重新注入").that(evaluated).hasSize(2)
        }
    }

    @Test
    fun invalidateForcesAReinstallEvenOnTheSameUrl() {
        // 同一个 URL 重新加载（刷新）也是新 document ⇒ 全局量被清空
        val evaluated = ArrayList<String>()
        val host = PageScriptHost(
            evaluate = { js -> evaluated += js; "" },
            assetReader = { assetBody },
            documentKey = { "https://shop.example.com/list" },
        )
        runBlocking {
            assertThat(host.ensureReady()).isTrue()
            host.invalidate()
            assertThat(host.ensureReady()).isTrue()
        }
        assertThat(evaluated).hasSize(2)
    }

    @Test
    fun aSelfCheckThatNamesMissingGlobalsIsAFailure_notSilentProgress() {
        val host = PageScriptHost(
            evaluate = { "__extract,__akeInstalled" },
            assetReader = { assetBody },
            documentKey = { "https://a.cn" },
        )
        runBlocking {
            assertWithMessage("自检报缺必须显式 false —— 静默继续会把失败伪装成超时")
                .that(host.ensureReady()).isFalse()
            assertWithMessage("失败后不应标记为已就绪，否则再也不重试")
                .that(host.isReadyFor("https://a.cn")).isFalse()
        }
    }

    @Test
    fun aScriptThatThrowsIsAFailureToo() {
        // `evaluateJavascript` 在页面 JS 抛异常时返回 null ⇒ 必须判失败，
        // 不能把 null 当成「返回了空串 = 全部就位」
        val host = PageScriptHost(
            evaluate = { null },
            assetReader = { assetBody },
            documentKey = { "https://a.cn" },
        )
        runBlocking { assertThat(host.ensureReady()).isFalse() }
    }

    @Test
    fun quotedResultsFromEvaluateJavascriptAreUnwrapped() {
        // WebView 把字符串结果 JSON 化回来（带引号），空串 = 全部就位
        val host = PageScriptHost(
            evaluate = { "\"\"" },
            assetReader = { assetBody },
            documentKey = { "https://a.cn" },
        )
        runBlocking { assertThat(host.ensureReady()).isTrue() }
    }

    @Test
    fun anUnreadableAssetFailsLoudly() {
        val host = PageScriptHost(
            evaluate = { js -> if (js.contains("AKE_MISSING.push('dom-extract')")) "dom-extract" else "" },
            assetReader = { null },
            documentKey = { "https://a.cn" },
        )
        runBlocking { assertThat(host.ensureReady()).isFalse() }
    }

    @Test
    fun evaluateThrowingDoesNotEscapeAsACrash() {
        val host = PageScriptHost(
            evaluate = { throw IllegalStateException("webview gone") },
            assetReader = { assetBody },
            documentKey = { "https://a.cn" },
        )
        runBlocking { assertThat(host.ensureReady()).isFalse() }
    }

    @Test
    fun everyAssetPathInTheManifestIsActuallyRead() {
        val asked = ArrayList<String>()
        val host = PageScriptHost(
            evaluate = { "" },
            assetReader = { path -> asked += path; assetBody },
            documentKey = { "https://a.cn" },
        )
        runBlocking { host.ensureReady() }
        assertThat(asked).containsExactlyElementsIn(JsBootstrap.assetPaths())
    }

    @Test
    fun everyGlobalTheSelfCheckLooksForReallyExistsInTheInjectedBundle() {
        // 最后一道：注入自检要找的三个名字，必须真的由清单里的脚本产出 ——
        // 否则自检只会永远报「缺 XX」，排查的人却找不到是哪一段没干这事。
        val script = JsBootstrap.installScript(mapOf(JsBootstrap.DOM_EXTRACT_ASSET to assetBody))

        assertWithMessage("asset 必须导出 __extract").that(assetBody).contains("global.__extract = __extract;")
        assertWithMessage("asset 必须导出 isSame").that(assetBody).contains("global.isSame = isSame;")
        assertWithMessage("INSTALL 必须设置 __akeInstalled").that(StabilityScripts.INSTALL)
            .contains("window.__akeInstalled = true;")

        // 注入脚本把三者拼在一起 ⇒ 自检在真实组合下不会误报
        assertThat(script).contains("global.__extract = __extract;")
        assertThat(script).contains("global.isSame = isSame;")
        assertThat(script).contains("window.__akeInstalled = true;")
    }
}
