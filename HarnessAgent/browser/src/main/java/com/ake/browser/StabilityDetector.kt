package com.ake.browser

import com.ake.core.common.AkeLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 页面稳定检测 —— **刻意不用 sleep**（架构 §5.8）。
 *
 * 真实世界的「加载完了」不是一个时长，是三个条件的合取：
 *
 *  1. **文档加载完成**：progress 100 / `document.readyState === 'complete'`
 *  2. **网络执行完成**：没有在飞的 XHR / fetch
 *  3. **DOM 静默**：一段静默期内没有 mutation
 *
 * 最容易翻车的是第 3 条：**SPA 的部分重绘（React/Vue 路由切换、异步筛选）根本不触发 `onPageFinished`**。
 * 真机上的表现是「Navigate 之后马上 observe，观察到的是上一页」⇒ 抽出来的候选全是旧元素 ⇒
 * 后续动作必 STALE，而报错信息看起来像是模型乱编编号。
 *
 * 所以判定顺序是：**先等静默，再看 readyState**，而不是反过来。
 *
 * 注入脚本对 XHR/fetch 做计数包装，把「有几个请求在飞」也纳入判断 ——
 * 否则会遇到「mutation 暂时为 0 但数据还没回来」这种伪稳定。
 *
 * ⚠ 但它的角色是**辅助**：只用来「延长」等待，不作为放行的必要条件。
 *   两条出口都要求 `pending == 0` 时，一个永不结算的请求就能让页面永远等不到稳定
 *   （详见 [waitForPageSettled] 的 KDoc）。
 */
public object StabilityScripts {

    /** 幂等：新 document 会清掉全局量，所以每次 navigate 后都要重新注入。 */
    public val INSTALL: String = """
        (function(){
          if (window.__akeInstalled) { return 'already'; }
          window.__akeInstalled = true;
          window.__akeMutationCount = 0;
          window.__akeNetworkPending = 0;

          // ★ 网络计数只在**这里**加减，且**夹在 0 上界**：
          //   判据是 `pendingRequests == 0`，一旦漂成负数就永远不成立
          //   ⇒ 「网络空闲」这条判据永久失效 ⇒ 页面再也等不到稳定。
          var netDone = function(){
            if (window.__akeNetworkPending > 0) window.__akeNetworkPending--;
          };
          window.__akeNetDone = netDone;

          if (!window.__akeXhrWrapped) {
            window.__akeXhrWrapped = true;
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(){
              // ★ 只挂 `loadend`：按 XHR spec，load / abort / error / timeout 之后
              //   **一定**再补发一发 loadend。历史实现同时挂了 `loadend` 与 `error`
              //   ⇒ 失败的请求 decrement 两次，而 send() 只 increment 一次
              //   ⇒ 计数漂成负数。这里再加一道 per-request 的 once 守卫兜底。
              if (!this.__akeHooked) {
                this.__akeHooked = true;
                this.addEventListener('loadend', function(){
                  if (this.__akeSettled) return;
                  this.__akeSettled = true;
                  netDone();
                });
              }
              return origOpen.apply(this, arguments);
            };
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function(){
              window.__akeNetworkPending++;
              return origSend.apply(this, arguments);
            };
            var origFetch = window.fetch;
            if (origFetch) {
              window.fetch = function(){
                window.__akeNetworkPending++;
                try {
                  return origFetch.apply(this, arguments).then(
                    function(r){ netDone(); return r; },
                    function(e){ netDone(); throw e; });
                } catch (e) { netDone(); throw e; }
              };
            }
          }

          var install = function(){
            var target = document.body || document.documentElement;
            if (!target) return;
            new MutationObserver(function(list){
              window.__akeMutationCount += list.length;
            }).observe(target, {
              childList: true, subtree: true, attributes: true, characterData: true
            });
          };
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', install, { once: true });
          } else { install(); }
          return 'ok';
        })();
    """.trimIndent()

    /** 返回 `mutations,pending,readyState,scrollY,height`。 */
    public val PROBE: String = """
        (function(){
          return String(window.__akeMutationCount || 0) + ',' +
                 String(window.__akeNetworkPending || 0) + ',' +
                 String(document.readyState) + ',' +
                 String(Math.round(window.scrollY || window.pageYOffset || 0)) + ',' +
                 String(Math.round(document.documentElement.scrollHeight || 0));
        })();
    """.trimIndent()
}

/** probe 结果的解析产物。 */
public data class StabilityProbe(
    val mutations: Long,
    val pendingRequests: Int,
    val readyComplete: Boolean,
    val scrollY: Int,
    val contentHeight: Int,
) {
    public companion object {
        public fun parse(raw: String?): StabilityProbe {
            if (raw == null) return StabilityProbe(0, 0, false, 0, 0)
            val p = raw.trim().trim('"').split(",")
            return StabilityProbe(
                mutations = p.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L,
                pendingRequests = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                readyComplete = p.getOrNull(2)?.trim()?.equals("complete", true) == true,
                scrollY = p.getOrNull(3)?.trim()?.toIntOrNull() ?: 0,
                contentHeight = p.getOrNull(4)?.trim()?.toIntOrNull() ?: 0,
            )
        }
    }
}

/**
 * 等待页面**DOM 稳定**。
 *
 * ## 等待的语义（★ 这一条被重新定义过，别再退回旧口径）
 *
 * 要等的到底是「页面 DOM 不再变化」，**不是**「网络空闲」。
 * 网络 pending 只是**辅助信号**：它唯一的用处是防止「mutation 暂时为 0 但数据还在路上」
 * 这种伪稳定，所以它可以**延长**等待，但它不能成为放行的**必要条件**。
 *
 * 旧实现的两条出口都硬要求 `pending == 0`：
 *
 *   ① 主出口 `quiet = mutations 没动 && pending == 0`
 *   ② 兜底出口 `pulses > N && readyComplete && pending == 0`
 *
 * ⇒ 页面上只要有一个**永不结算**的请求（长轮询 / 挂死的 SSE / 被 CORS 拦掉的 fetch），
 *   `pending` 就永远归不了零 ⇒ 两条出口同时失效 ⇒ 页面**永远等不到稳定**。
 *   用户看到的是「App 卡住了」，而它其实只是在等一个永远不会来的信号 ——
 *   这正是本项目反复警告的静默失效：不崩、不报错、只让你干等。
 *
 * ## 真正的出口：**区分「还在等有意义的信号」与「信号已经不可能来了」**
 *
 * 判据不看 `pending` 的**绝对值**，看它**还在不在动**：
 *
 *   · `pending` 仍在变化 ⇒ 请求真的在跑（起新的 / 结算旧的）⇒ **继续等**
 *   · `pending` 冻住不动且已冻够 [networkStallGraceMs]，同时 DOM 静默 + `readyState == complete`
 *     ⇒ 那几个挂着的东西**已经不可能再给 DOM 带来任何变化**（否则 mutation 计数会动）
 *     ⇒ **判定放行**
 *   · `pending <= 0` ⇒ 网络侧无事；`pending < 0` 说明计数器已经失真，同样不能拿来挡住放行
 *
 * 于是「加个超时」不是这里的解法：那只是把「永久挂起」换成「每次都等满超时」，
 * 用户感受到的仍然是「每次打开页面都要卡 N 秒」，而且真正该等的请求也被一起等死了。
 *
 * @param evaluate 执行 JS 并返回结果字符串的桥（内部只用 [StabilityScripts.PROBE]）。
 * @param quietWindowMs   连续 **DOM** 静默多久算稳定。300 ms 是 §5.8 的推荐值。
 * @param lenientAfterPulses 页面「永远在动」的兜底（无限滚动 / 轮播图）：
 *                            轮数超过它且文档已完成时放行；这条兜底同样**不再**要求 pending == 0。
 * @param networkStallGraceMs pending 计数冻住多久算「信号已经不可能来了」。
 * @return true = 已稳定；false = 超时
 */
public suspend fun waitForPageSettled(
    evaluate: suspend (String) -> String?,
    timeoutMs: Long,
    quietWindowMs: Long = 300L,
    pollIntervalMs: Long = 100L,
    lenientAfterPulses: Int = 40,
    networkStallGraceMs: Long = 1_000L,
): Boolean {
    var lastMutations = -1L
    var stableSince = -1L
    var lastPending: Int? = null
    var pendingFrozenSince = -1L
    var pulses = 0

    // ★ 原实现在 lambda 里用 `return@withTimeoutOrNull true` 提前返回，
    //   但 lambda 的**最后一个表达式**是 `while(true){...}`（Unit），
    //   于是 R 被推断成 Unit ⇒ 外层 `return` 报「expected Boolean, actual Unit」。
    //   改成 `done` + `break` 收口，让 lambda 有明确的 Boolean 结果，不依赖带标签返回。
    val settled: Boolean? = withTimeoutOrNull(timeoutMs) {
        var done = false
        while (!done) {
            val probe = StabilityProbe.parse(evaluate(StabilityScripts.PROBE))
            val now = android.os.SystemClock.elapsedRealtime()

            // ── 网络信号：辅助量，只看「还在不在动」，不看绝对值
            if (lastPending == null || probe.pendingRequests != lastPending) {
                lastPending = probe.pendingRequests
                pendingFrozenSince = now
            }
            val networkClear: Boolean = when {
                // 没有在飞的请求；计数漂成负数说明它已经失真，同样不该挡住放行
                probe.pendingRequests <= 0 -> true
                // 挂着但一直不动 ⇒ 它不会给 DOM 带来变化（带来了 mutation 计数就会动）
                else -> now - pendingFrozenSince >= networkStallGraceMs
            }

            // ── 主判据：DOM 静默（网络只做「延长」，不做「否决」）
            val domQuiet = probe.mutations == lastMutations
            if (domQuiet) {
                if (stableSince < 0) stableSince = now
                if (now - stableSince >= quietWindowMs && probe.readyComplete && networkClear) {
                    done = true
                    break
                }
            } else {
                stableSince = -1L
            }
            lastMutations = probe.mutations
            pulses++

            // ── 兜底：无限滚动 / 轮播图永远不会静默 ⇒ 只看文档完成
            //   （这里同样不能要求 pending == 0：轮播页常常配着一个长轮询）
            if (pulses > lenientAfterPulses && probe.readyComplete) {
                AkeLog.w("PageSettler", "page never settles (infinite scroll?); accepting document-complete state")
                done = true
                break
            }
            delay(pollIntervalMs.coerceAtMost(500L))
        }
        done
    }

    return settled ?: false
}

// ★ 这里**刻意不再导出** `WebView.installStabilityProbe()` 之类的第二套注入入口。
//   稳定探测脚本的安装现在和 `__extract` / stableKey 常量同属 `JsBootstrap` 一份清单，
//   由 `PageScriptHost` 在每次需要前统一注入 ——
//   留下第二套入口就等于留下「有人调了它、有人没调」的第二处漂移。
//   （`WebViewExecutor` / `WebViewDomPerception` 各自调 `scriptHost.ensureReady()`。）
