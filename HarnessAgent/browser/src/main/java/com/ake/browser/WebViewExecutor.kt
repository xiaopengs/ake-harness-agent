package com.ake.browser

import android.webkit.WebView
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Ulid
import com.ake.core.common.Url
import com.ake.core.contract.BrowserAction
import com.ake.core.contract.ExecutionResult
import com.ake.core.contract.PageChangeKind
import com.ake.core.contract.PageDigest
import com.ake.core.contract.PageEvent
import com.ake.core.contract.PageHandle
import com.ake.core.contract.PageState
import com.ake.core.ports.BrowserExecutorPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * `BrowserExecutorPort` 的 WebView 实现（架构 §2.2）。
 *
 * ### 线程模型
 * WebView 的所有方法**必须在主线程调用**。这里统一用 `Dispatchers.Main.immediate`
 * 切过去，不用手写 `Handler.post` —— 手写容易在「调用方已经在主线程」时多绕一圈，
 * 更糟的是会在 WebView 已 detach 时把异常吞掉。
 *
 * ### 每个动作的形状：**前置校验 → 执行 → 等待稳定 → 返回观察**
 *
 * 「等待稳定」绝不能省：绝大多数「点了没反应」的报告，真实原因是
 * 「点了 → 异步请求还没回来 → 就去观察了」，而不是动作本身失灵。
 *
 * ### 元素二级确认（§5.6 / R3）
 * 执行前先用 selector 命中元素，再用 **stableKey 校验还是不是那个元素**；
 * 命中但 stableKey 变了 ⇒ 返回 `change = NO_CHANGE` 且 note 标 STALE，
 * 让 loop 走重新观察，而不是去点一个已经换过的东西。
 */
public class WebViewExecutor(
    private val webView: WebView,
    /**
     * ★ **必须与 `WebViewDomPerception` 共用同一个 bridge**。
     *
     * `addJavascriptInterface` 是**按名字覆盖**的：感知与执行各 new 一个 bridge、
     * 都用默认名 `AkeBridge` 注入同一个 WebView 时，后注入的那个会把前一个顶掉。
     * 症状极具迷惑性 —— 抽 DOM 永远超时（它的 deliver 落到了已被顶替的对象上），
     * 而执行看起来正常。所以 bridge 由 [BrowserRuntime] 统一创建并注入进来。
     */
    private val bridge: DomExtractBridge,
) : BrowserExecutorPort {

    private val _events = MutableSharedFlow<PageEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun pageEvents(): Flow<PageEvent> = _events.asSharedFlow()

    override suspend fun open(url: Url): Outcome<PageHandle> = onMain {
        if (!url.value.startsWith("http", true)) {
            return@onMain Outcome.Err(AppError(AppError.CODE_INVALID_INPUT, "err_bad_url"))
        }
        _events.tryEmit(PageEvent.NavStarted(url))
        webView.loadUrl(url.value)
        Outcome.Ok(PageHandle(Ulid.next(), url, System.currentTimeMillis()))
    }

    override suspend fun waitForSettled(timeoutMs: Long): Outcome<PageState> {
        val ok = waitForPageSettled(evaluate = { js -> evaluate(js) }, timeoutMs = timeoutMs)
        return if (ok) {
            _events.tryEmit(PageEvent.Settled)
            Outcome.Ok(PageState.READY)
        } else {
            Outcome.Err(AppError(AppError.CODE_TIMEOUT, "err_page_settle_timeout"))
        }
    }

    override suspend fun execute(action: BrowserAction): Outcome<ExecutionResult> {
        // WebView.getUrl() 是主线程 API；从后台线程读会拿到过期值甚至抛异常
        val beforeUrl = onMain { webView.url }
        val beforeMutations = currentProbe().mutations
        val beforeDigest = evaluate(DigestScripts.DIGEST)?.trim('"')

        val outcome: Outcome<Unit> = when (action) {
            is BrowserAction.Click -> runScript(clickScript(action), action.selector)
            is BrowserAction.Type -> runScript(typeScript(action), action.selector)
            is BrowserAction.Select -> runScript(selectScript(action), action.selector)
            is BrowserAction.Scroll -> scroll(action)
            is BrowserAction.Navigate -> navigate(action)
            is BrowserAction.ExtractRecords -> Outcome.Ok(Unit)
        }
        if (outcome is Outcome.Err) return Outcome.Err(outcome.error)

        val settled = waitForPageSettled(evaluate = { js -> evaluate(js) }, timeoutMs = ACTION_SETTLE_TIMEOUT_MS)
        val afterDigest = evaluate(DigestScripts.DIGEST)?.trim('"')
        val afterMutations = currentProbe().mutations
        val afterUrl = onMain { webView.url }

        val change: PageChangeKind = when {
            afterUrl != null && afterUrl != beforeUrl -> PageChangeKind.URL_MOVED
            afterMutations != beforeMutations || afterDigest != beforeDigest -> PageChangeKind.DOM_CHANGED
            else -> PageChangeKind.NO_CHANGE
        }

        return Outcome.Ok(
            ExecutionResult(
                observationId = Ulid.next(),
                before = beforeDigest?.let(::PageDigest),
                after = afterDigest?.let(::PageDigest),
                url = afterUrl?.let { Url(it) },
                pageState = if (settled) PageState.READY else PageState.LOADING,
                change = change,
                addedCandidates = candidateDigestCount(),
                note = if (!settled) "settle timeout; DOM may still be updating" else null,
            ),
        )
    }

    // ────────────────────────────────────────────────────────────── 脚本构造

    private fun clickScript(a: BrowserAction.Click): String = """
        (function(){
          var el = document.querySelector(${a.selector.js()});
          if (!el) return 'NOT_FOUND';
          if (!isSame(el, ${a.stableKey.js()})) return 'STALE';
          el.scrollIntoView({block:'center'});
          el.click();
          return 'OK';
        })();
    """.trimIndent()

    private fun typeScript(a: BrowserAction.Type): String = """
        (function(){
          var el = document.querySelector(${a.selector.js()});
          if (!el) return 'NOT_FOUND';
          if (!isSame(el, ${a.stableKey.js()})) return 'STALE';
          el.focus();
          var proto = el instanceof HTMLTextAreaElement
            ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
          var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
          setter.call(el, ${a.text.js()});
          el.dispatchEvent(new Event('input', {bubbles:true}));
          el.dispatchEvent(new Event('change', {bubbles:true}));
          ${if (a.submit) """
          var form = el.closest('form');
          if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); }
          """ else ""}
          return 'OK';
        })();
    """.trimIndent()

    private fun selectScript(a: BrowserAction.Select): String = """
        (function(){
          var el = document.querySelector(${a.selector.js()});
          if (!el) return 'NOT_FOUND';
          if (!isSame(el, ${a.stableKey.js()})) return 'STALE';
          el.value = ${a.value.js()};
          el.dispatchEvent(new Event('change', {bubbles:true}));
          return 'OK';
        })();
    """.trimIndent()

    private suspend fun scroll(a: BrowserAction.Scroll): Outcome<Unit> = onMain {
        val dy = if (a.dir.equals("up", true)) -a.px else a.px
        webView.scrollBy(0, dy)
        Outcome.Ok(Unit)
    }

    private suspend fun navigate(a: BrowserAction.Navigate): Outcome<Unit> {
        if (!a.url.startsWith("http", true)) {
            return Outcome.Err(AppError(AppError.CODE_INVALID_INPUT, "err_bad_url"))
        }
        val url = Url(a.url)
        _events.tryEmit(PageEvent.NavStarted(url))
        return onMain {
            webView.loadUrl(url.value)
            Outcome.Ok(Unit)
        }
    }

    public suspend fun goBack(): Outcome<Unit> = onMain {
        if (webView.canGoBack()) webView.goBack()
        Outcome.Ok(Unit)
    }

    // ────────────────────────────────────────────────────────────── 桥与执行

    // （bridge 的创建与 JS 接口注入统一在 BrowserRuntime 做 —— 见构造函数注释。
    //  这里不再持有 mainHandler / postEvaluate：evaluate() 已经用
    //  `withContext(Dispatchers.Main.immediate)` 保证线程，重复一套只会多一处漂移。）

    /**
     * 在主线程求值一段 JS。
     *
     * ★ **幂等**：`evaluateJavascript` 的回调**可能被多次调用**（页面重定向、
     *   frame 变化等路径上都出现过）。用 `cont.isActive` 守卫，只让第一次生效 ——
     *   二次 resume 会抛 `IllegalStateException`，是真实的崩溃来源。
     */
    public suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            var resumed = false
            runCatching {
                webView.evaluateJavascript(script) { value ->
                    if (!resumed) {
                        resumed = true
                        cont.resume(value)
                    }
                }
            }.onFailure { t ->
                if (!resumed) {
                    resumed = true
                    cont.resumeWith(Result.failure(t))
                }
            }
        }
    }

    /** 执行 JS 并把统一错误语义映射到 Outcome。 */
    private suspend fun runScript(script: String, selector: String): Outcome<Unit> {
        if (selector.isBlank()) {
            return Outcome.Err(AppError(AppError.CODE_INVALID_INPUT, "err_empty_selector"))
        }
        val result = evaluate(script)
        return when (result?.trim('"')) {
            "OK" -> Outcome.Ok(Unit)
            "STALE" -> Outcome.Err(AppError(AppError.CODE_INVALID_INPUT, "err_element_stale"))
            "NOT_FOUND" -> Outcome.Err(AppError(AppError.CODE_INVALID_INPUT, "err_element_not_found"))
            else -> Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_browser_action_failed"))
        }
    }

    private suspend fun currentProbe(): StabilityProbe =
        StabilityProbe.parse(evaluate(StabilityScripts.PROBE))

    /**
     * 把一段 WebView 操作切到主线程执行。
     *
     * 用 `Main.immediate` 而不是 `Handler.post`：**调用方已经在主线程时不额外绕一圈** ——
     * 绕一圈会让 `open()` 在 UI 线程上变成异步的，调用点拿到返回值时 loadUrl 还没跑，
     * 这种「顺序看起来对、时序其实错」的问题极难复现。
     *
     * 声明成 `suspend` lambda 而不是普通 lambda，是为了让调用点能在里面继续 `await`。
     */
    private suspend fun <T> onMain(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

    /** 当前候选元素数量，用于 [ExecutionResult.addedCandidates] 的粗略记账。 */
    private suspend fun candidateDigestCount(): Int =
        evaluate("(function(){return String(document.querySelectorAll('a,button,input,select,textarea,[role],[onclick]').length);})();")
            ?.trim('"')?.toIntOrNull() ?: 0

    private companion object {
        /** 单动作后的等待上限。比全局 settle 短 —— 动作后通常只等局部重绘。 */
        const val ACTION_SETTLE_TIMEOUT_MS = 8_000L
    }
}
