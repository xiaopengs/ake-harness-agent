package com.ake.browser

import com.ake.core.common.AkeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ★★ **注入管线的唯一所有者**（本模块 M10 咽喉项的真正根因）。
 *
 * ## 根因，不是三个 bug
 *
 * 修复前 `:browser` 里有三段必须常驻页面上下文的 JS，却**没有任何一处负责把它们读进 WebView**：
 *
 *  1. `assets/js/dom-extract.js` → 定义 `__extract` —— 没人读 asset；
 *  2. `BridgeHelpers.SCRIPT`     → 定义 `isSame` —— 没人注入；
 *  3. `StabilityScripts.INSTALL` → 装 MutationObserver 与网络计数 —— 没人注入。
 *
 * 三段各自被"某个调用方以为已经在页面里了"地引用，于是三个症状同时出现：
 * 点击全废（`isSame` 未定义 ⇒ ReferenceError / 全部 STALE）、DOM 抽取全废
 * （`__extract` 未定义 ⇒ observe 必超时）、稳定检测形同虚设（mutation 计数恒为 0 ⇒
 * 第二次 poll 就判「页面已静默」，一次 mutation 都没看到就放行）。
 *
 * 所以修法是：**把「谁负责、什么时候注入、失败了怎么办」收敛成一处**，
 * 而不是在三个调用点各补一次 `evaluate(...)`。
 *
 * ## 这里的形态
 *
 *  - [JsBootstrap.RESOURCES] 是**唯一一份清单**：要注入什么、按什么顺序、从哪来。
 *  - [PageScriptHost] 是**唯一一个执行者**：按「文档键」惰性注入，并**自检**结果。
 *
 * ## 为什么必须自检而不是"注入完就走"
 *
 * 注入失败在 WebView 里是**静默**的：`evaluateJavascript(script, null)` 不抛异常，
 * 页面 JS 出错也只是 console 里一行。若不自检，失败会一路退化成
 * 「bridge 超时 ⇒ 空观察 ⇒ `PocketDom.errored()`」，与「页面真的是空的」同形。
 * 所以 [JsBootstrap.installScript] 的返回值是**缺失的全局名清单**，
 * `PageScriptHost` 据此明确返回 false，让调用方报 `err_js_bootstrap_failed`
 * 而不是报一个误导性的超时。
 */
public enum class JsResourceKind {
    /** 源码里的常量脚本。 */
    INLINE,

    /** `assets/` 下的文件。[JsResource.body] 是相对 assets 根目录的路径。 */
    ASSET,
}

public data class JsResource(
    /** 诊断用的标识，缺失时会出现在自检结果里。 */
    val id: String,
    val kind: JsResourceKind,
    /** INLINE = 脚本文本；ASSET = assets 相对路径。 */
    val body: String,
)

public object JsBootstrap {

    /** `assets/js/dom-extract.js` 相对 assets 根目录的路径。 */
    public const val DOM_EXTRACT_ASSET: String = "js/dom-extract.js"

    /**
     * 常驻页面上下文的脚本清单。**顺序即注入顺序**，有依赖：
     * stableKey 常量必须先于 `dom-extract.js`（后者在闭包求值时读这三个全局变量）。
     */
    public val RESOURCES: List<JsResource> = listOf(
        JsResource("stable-key-constants", JsResourceKind.INLINE, BridgeHelpers.SCRIPT),
        JsResource("dom-extract", JsResourceKind.ASSET, DOM_EXTRACT_ASSET),
        JsResource("stability-probe", JsResourceKind.INLINE, StabilityScripts.INSTALL),
    )

    /** 注入完成后页面上必须存在的全局名。 */
    public val REQUIRED_GLOBALS: List<String> = listOf("__extract", "isSame", "__akeInstalled")

    public fun assetPaths(): List<String> =
        RESOURCES.filter { it.kind == JsResourceKind.ASSET }.map { it.body }

    /**
     * 拼装注入脚本。
     *
     * @param assets 资源 id → 文件内容。缺失的资源会**显式**记进返回值，绝不静默跳过。
     * @return 一段 IIFE，其值是**缺失项的逗号分隔清单**（空串 = 全部就位）。
     */
    public fun installScript(assets: Map<String, String>): String = buildString {
        appendLine("(function(){")
        appendLine("var AKE_MISSING = [];")
        for (r in RESOURCES) {
            appendLine()
            appendLine("/* [${r.kind}] ${r.id} */")
            when (r.kind) {
                JsResourceKind.INLINE -> appendLine(r.body)
                JsResourceKind.ASSET -> {
                    val src = assets[r.body]
                    if (src.isNullOrBlank()) {
                        appendLine("AKE_MISSING.push('${r.id}');")
                    } else {
                        appendLine(src)
                    }
                }
            }
        }
        appendLine()
        append(SELF_CHECK.trimIndent())
        appendLine()
        appendLine("})();")
    }

    /**
     * 自检段。
     *
     * ★ 除了「在不在」，还要验 `__stableKeyOf` **算不算得出** 16 位 hex：
     * 常量没注进去（远端替换了 asset / 注入顺序错了）时它会算出 `NaN`，
     * 「函数存在」这条断言会绿，但每个点击都会 STALE —— 正是要防的那类静默失败。
     */
    private val SELF_CHECK: String = """
        if (typeof __extract !== 'function') AKE_MISSING.push('__extract');
        if (typeof isSame !== 'function') AKE_MISSING.push('isSame');
        if (typeof __stableKeyOf !== 'function') AKE_MISSING.push('__stableKeyOf');
        else if (!/^[0-9a-f]{16}$/.test(__stableKeyOf('a','','',''))) AKE_MISSING.push('__stableKeyOf');
        if (!window.__akeInstalled) AKE_MISSING.push('__akeInstalled');
        return AKE_MISSING.join(',');
    """
}

/**
 * 页面脚本宿主：**谁在什么时候把 [JsBootstrap.RESOURCES] 弄进当前文档**。
 *
 * ## 为什么按「文档键」而不是「注入一次就完事」
 *
 * `evaluateJavascript` 注入的全局量属于**document**。一次导航（包括页面自己点链接跳转，
 * 不一定经过我们的 `loadUrl`）就会把它们全清掉。所以每次使用前的判据是
 * 「当前文档是否已经注入过」，而不是「历史上有没有注入过」。
 *
 * ## 为什么三个入参都是 lambda
 *
 * `WebView` / `AssetManager` 在 JVM 单测里是桩（`RuntimeException: not mocked`）。
 * 把它们退化成三个 suspend/普通 lambda 后，宿主的全部状态机
 * （惰性注入、换文档重注、缺失自检、asset 读失败）都能在 JVM 上毫秒级跑，
 * 不需要 Robolectric —— 而 Robolectric 的 WebView 是假的，测出来的东西不可信。
 */
public class PageScriptHost(
    /** 在主线程求值并返回结果字符串。 */
    private val evaluate: suspend (String) -> String?,
    /** 读 `assets/<path>`；失败或缺失返回 null。 */
    private val assetReader: suspend (String) -> String?,
    /** 当前文档的标识（通常是 `webView.url`）。换值即视为换文档。 */
    private val documentKey: suspend () -> String = { "" },
) {

    @Volatile
    private var installedFor: String? = null

    /** 文档即将/已经变化（导航开始、页面开始加载）。下一个 [ensureReady] 会重新注入。 */
    public fun invalidate(): Unit {
        installedFor = null
    }

    /** 当前文档是否已注入成功。仅供诊断 / 测试。 */
    public fun isReadyFor(document: String): Boolean = installedFor == document

    /**
     * 确保当前文档里三份脚本都在。
     *
     * @return true = 全部就位；false = 缺失（具体缺什么已写 ERROR 日志）。
     *
     * ★ 失败必须是**显式的 false**，不能静默继续：静默继续会把失败伪装成
     * 「bridge 超时」或「页面是空的」，这两条在 UI 上完全看不出区别。
     */
    public suspend fun ensureReady(): Boolean {
        val key = runCatching { documentKey() }.getOrDefault("")
        if (installedFor == key) return true

        val assets = HashMap<String, String>()
        for (path in JsBootstrap.assetPaths()) {
            val text = withContext(Dispatchers.IO) {
                runCatching { assetReader(path) }
                    .onFailure { AkeLog.e(TAG, "读取 assets/$path 失败", it) }
                    .getOrNull()
            }
            if (text.isNullOrBlank()) {
                AkeLog.e(TAG, "assets/$path 为空 —— DOM 抽取脚本缺失，观察必然超时")
            }
            assets[path] = text.orEmpty()
        }

        val raw = runCatching { evaluate(JsBootstrap.installScript(assets)) }
            .onFailure { AkeLog.e(TAG, "注入 JS 运行时脚本时抛异常", it) }
            .getOrNull()

        // `evaluateJavascript` 把字符串结果 JSON 化回来（带引号）；脚本抛异常时是 null
        val missing = raw?.trim()?.trim('"')?.trim().orEmpty()
        if (raw != null && missing.isEmpty()) {
            installedFor = key
            return true
        }

        AkeLog.e(
            TAG,
            if (raw == null) {
                "JS 运行时注入失败：脚本未返回结果（页面上下文异常？）"
            } else {
                "JS 运行时注入不完整，缺：$missing"
            },
        )
        return false
    }

    private companion object {
        const val TAG = "PageScriptHost"
    }
}
