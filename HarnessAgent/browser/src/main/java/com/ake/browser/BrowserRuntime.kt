package com.ake.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.ake.core.common.AkeLog
import com.ake.core.ports.BrowserExecutorPort
import com.ake.core.ports.BrowserViewHandle
import com.ake.core.ports.DomPerceptionPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 一个 WebView 实例 + 它的 JS 桥 + 挂在上面的两个 Port 实现。
 *
 * ## 为什么必须存在这一层（而不是让 Hilt 直接 new 两个 Port）
 *
 * 1. **WebView 只能有一个 JS 接口名**：`addJavascriptInterface` 是按名字覆盖的。
 *    若感知与执行各 new 一个 `DomExtractBridge` 都用默认名 `AkeBridge` 注入同一个
 *    WebView，后注入的会顶掉前一个 ⇒ 先注入的那一侧 `deliver` 永远收不到回调，
 *    表现为「抽 DOM 必超时，而执行看起来正常」—— 一个能查一整天的假 bug。
 *    所以 bridge 在这里**创建一次、注入一次、两边共用**。
 * 2. **WebView 的生命周期要有归属**：它必须在主线程构造、在不再使用时要
 *    `destroy()`。散在两个 Port 里就没人负责销毁，结果是每开一次任务漏一个 WebView。
 * 3. **给 Hilt 一个可以 `@Singleton` 的装配点**：`:app` 只绑定本类，
 *    再由它派生出 `DomPerceptionPort` / `BrowserExecutorPort` / `BrowserViewHandle`。
 *
 * ## 用 ApplicationContext 构造 WebView 的取舍
 *
 * 用 app context 而非 Activity context，是为了**活过 Activity 重建**（旋转、后台回收）。
 * 代价是少数依赖 Activity 主题的能力（如 `<select>` 弹出层）会退化 ——
 * MVP 以无头执行为主、预览页为辅，这个代价可以接受，这里显式记录下来。
 */
public class BrowserRuntime private constructor(
    public val webView: WebView,
    private val bridge: DomExtractBridge,
    /**
     * ★ 与 [bridge] 同级：**页面脚本宿主也只有一份**，由感知与执行两侧共用。
     *   理由与 bridge 完全同构 —— 若各 new 一个，两侧会各自维护「当前文档注入过了没」，
     *   而它们看到的是同一个 document ⇒ 必有一侧的缓存判断是错的。
     */
    private val scriptHost: PageScriptHost,
) : BrowserViewHandle {

    /** 执行侧 Port 实现。与 [perception] 共享 [bridge] 与 [scriptHost]。 */
    public val executor: BrowserExecutorPort = WebViewExecutor(webView, bridge, scriptHost)

    /** 感知侧 Port 实现。与 [executor] 共享 [bridge] 与 [scriptHost]。 */
    public val perception: DomPerceptionPort = WebViewDomPerception(webView, bridge, scriptHost)

    init {
        // 构造函数整体跑在主线程上（见 create），这里注入 JS 接口是安全的。
        DomExtractBridge.attachTo(webView, bridge)
    }

    // ── BrowserViewHandle ────────────────────────────────────────────────

    override val isAttachable: Boolean get() = true

    override fun nativeView(): Any? = webView

    /**
     * 释放这个运行时：摘 JS 接口 → 取消在飞请求 → 销毁 WebView。
     *
     * 顺序不能反：先摘接口，否则页面 JS 仍可能回调到一个已经 detach 的 bridge 上；
     * 先 cancelAll 则会让正在 await 的协程拿到「cancelled」而不是正常结束。
     * **主线程调用**。
     */
    public fun close(): Unit = runOnMainSync {
        DomExtractBridge.detachFrom(webView, bridge)
        bridge.cancelAll()
        runCatching { webView.destroy() }
    }

    public companion object {

        /**
         * 构造一个运行时。**可在任意线程调用** —— 内部会切到主线程。
         *
         * ★ 为什么要做这个同步切换：Hilt 的 `@Provides` 在**请求方所在线程**执行，
         * 而请求方完全可能是一个 `Dispatchers.IO` 上的服务。直接 `WebView(context)`
         * 在后台线程会抛 `RuntimeException`（WebView 的构造与所有方法都要求主线程），
         * 而且是**运行时**才炸、编译期看不出来。
         */
        public fun create(context: Context): BrowserRuntime = runOnMainSync {
            val webView = WebViewFactory.create(context)
            val bridge = DomExtractBridge { script -> postEvaluate(webView, script) }
            val scriptHost = PageScriptHost(
                evaluate = { js -> evaluateOn(webView, js) },
                assetReader = { path -> readAsset(context, path) },
                // `webView.url` 是主线程 API；换文档即换键 ⇒ 重新注入
                documentKey = { withContext(Dispatchers.Main.immediate) { webView.url.orEmpty() } },
            )
            // 页面自己发起的导航（点链接）也会换 document，必须让宿主缓存失效
            WebViewFactory.watchDocuments(webView) { scriptHost.invalidate() }
            BrowserRuntime(webView, bridge, scriptHost)
        }
    }
}

/**
 * 从 `assets/` 读一份 JS 资源。
 *
 * ★ **失败必须显式返回 null 并记 ERROR 日志**：asset 缺失（打包漏了 / 远端下发失败）
 * 在 WebView 侧的表现是「`__extract` 未定义」，与「注入时机不对」完全同形，
 * 不在这里记一笔就只能靠猜。
 */
private fun readAsset(context: Context, path: String): String? = runCatching {
    context.assets.open(path).bufferedReader().use { it.readText() }
}.onFailure { t ->
    AkeLog.e("BrowserRuntime", "读取 assets/$path 失败", t)
}.getOrNull()

/**
 * 主线程同步执行。
 *
 * 已经在主线程 ⇒ 直接跑（**不** post 再等，那会让同步调用变成异步，
 * 调用点拿返回值时操作其实还没发生，是典型的难复现时序 bug）。
 * 不在主线程 ⇒ post 到主线程并阻塞等待。
 */
private fun <T> runOnMainSync(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val done = CompletableDeferred<Result<T>>()
    Handler(Looper.getMainLooper()).post { done.complete(runCatching(block)) }
    return runBlocking { done.await() }.getOrThrow()
}

/**
 * 桥的 JS 注入通道。
 *
 * 之所以**不用** `evaluateJavascript` 的回调版本：桥是「JS 主动回调 Android」的模型，
 * 注入这边只管发出去，返回值由 `DomExtractBridge.deliver` 异步收回（见该类的注释）。
 */
private fun postEvaluate(webView: WebView, script: String) {
    // 显式 Runnable：`Handler.post` 要的是 () -> Unit，
    // 而 runCatching 的返回值会让 lambda 被推断成 () -> Result<Unit> ⇒ 类型不匹配。
    val run = Runnable { runCatching { webView.evaluateJavascript(script, null) } }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        run.run()
    } else {
        Handler(Looper.getMainLooper()).post(run)
    }
}
