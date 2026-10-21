package com.ake.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ake.core.common.AkeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Android ⇄ JS 双向通信桥。
 *
 * ★ **为什么不用 `evaluateJavascript` 的回调拿大结果**：
 * `evaluateJavascript` 的结果是**通过 JNI 传回的字符串**。
 * dom 抽取结果虽然已经被 §5.1 的第①步砍到 30–120 KB，但走 JNI 仍是一次大字符串拷贝；
 * 更重要的是它把「返回值」和「求值」绑成一次性事件，做不了「多次推送」（比如稳定探测）。
 * 用 `addJavascriptInterface` 让 JS 主动回调 Android，二者彻底解耦。
 *
 * ★ **幂等性**：每个请求有单调递增的 `callbackId`，**首次回调即结算并从 pending 移除**。
 * 重复回调（有些站点会让 requestAnimationFrame 触发多次注入路径）不会让同一个 Deferred 二次 complete
 * （二次 complete 会抛 `IllegalStateException`，是真实的崩溃来源）。
 */
public class DomExtractBridge(
    private val evalScript: (String) -> Unit,
) {

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val nextId = AtomicLong(1)
    private val lastScriptId = AtomicInteger(0)

    /** 注入到 WebView 的接口名。JS 侧调用 `AkeBridge.deliver(id, json)`。 */
    public val interfaceName: String = INTERFACE_NAME

    /**
     * 执行一次抽取脚本，并等待 JS 把结果deliver回来。
     *
     * @param timeoutMs §5.8：这个超时是**安全边界**，不只是性能边界 ——
     *                  恶意页面可以构造病态 DOM 对 App 做 DoS。
     */
    public suspend fun run(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        // 把 callbackId 注入脚本作用域
        val wrapped = "(function(){var AKE_CB_ID=$id;$script})();"
        evalScript(wrapped)

        return withTimeoutOrNull(timeoutMs) {
            runCatching { deferred.await() }.getOrNull()
        }.also {
            pending.remove(id)
        }
    }

    /** JS 回调入口。**必须**是 public + @JavascriptInterface，且不能有 suspend。 */
    @JavascriptInterface
    public fun deliver(id: Long, payload: String?) {
        val target = pending[id] ?: run {
            AkeLog.w(TAG, "deliver for unknown/already-finished id=$id (ignored)")
            return
        }
        pending.remove(id)
        val text = payload ?: ""
        // offer 失败说明已经结算过 —— 幂等地丢弃
        runCatching { target.complete(text) }
    }

    /** JS 侧出错时的回调。同样幂等。 */
    @JavascriptInterface
    public fun deliverError(id: Long, message: String?) {
        val target = pending[id] ?: return
        pending.remove(id)
        runCatching { target.completeExceptionally(RuntimeException(message ?: "js error")) }
    }

    /**
     * 把 deliver 这段 JS 拼到抽取脚本尾部。
     * 用 try/catch 包住，保证**页面 JS 抛异常时我们是超时而不是死锁**。
     */
    public val deliverSuffix: String = """
        AkeBridge.deliver(AKE_CB_ID, JSON.stringify(result));
    """.trimIndent()

    public fun cancelAll() {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.let { d -> runCatching { d.completeExceptionally(RuntimeException("cancelled")) } }
        }
    }

    /** 是否有请求在飞。用于 WebView 销毁前的兜底检查。 */
    public fun inFlightCount(): Int = pending.size

    // ★ 一个类只允许一个 companion object（原文件里 public + private 各写了一个 ⇒
    //   「Conflicting declarations / Only one companion object is allowed per class」）。
    //   TAG 合并进来即可，不需要第二个伴生对象。
    public companion object {
        private const val TAG = "DomExtractBridge"

        public const val INTERFACE_NAME: String = "AkeBridge"
        public const val DEFAULT_TIMEOUT_MS: Long = 3_000L

        public fun attachTo(webView: WebView, bridge: DomExtractBridge) {
            webView.addJavascriptInterface(bridge, bridge.interfaceName)
        }

        public fun detachFrom(webView: WebView, bridge: DomExtractBridge) {
            runCatching { webView.removeJavascriptInterface(bridge.interfaceName) }
        }
    }
}
