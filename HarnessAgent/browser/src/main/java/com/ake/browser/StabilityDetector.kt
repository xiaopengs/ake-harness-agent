package com.ake.browser

import android.webkit.WebView
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
 * 注入脚本对 XHR/fetch 做计数包装，把「有几个请求在飞」也纳入 `quiet` 判断 ——
 * 否则会遇到「mutation 暂时为 0 但数据还没回来」这种伪稳定。
 */
public object StabilityScripts {

    /** 幂等：新 document 会清掉全局量，所以每次 navigate 后都要重新注入。 */
    public val INSTALL: String = """
        (function(){
          if (window.__akeInstalled) { return 'already'; }
          window.__akeInstalled = true;
          window.__akeMutationCount = 0;
          window.__akeNetworkPending = 0;

          if (!window.__akeXhrWrapped) {
            window.__akeXhrWrapped = true;
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(){
              this.addEventListener('loadend', function(){ window.__akeNetworkPending--; });
              this.addEventListener('error',   function(){ window.__akeNetworkPending--; });
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
                return origFetch.apply(this, arguments).then(
                  function(r){ window.__akeNetworkPending--; return r; },
                  function(e){ window.__akeNetworkPending--; throw e; });
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
 * 等待页面稳定。
 *
 * @param evaluate 执行 JS 并返回结果字符串的桥（内部只用 [StabilityScripts.PROBE]）。
 * @param quietWindowMs   连续静默多久算稳定。300 ms 是 §5.8 的推荐值。
 * @param lenientAfterPulses 页面「永远在动」的兜底：轮数超过它且网络空闲时放行，避免死等。
 * @return true = 已稳定；false = 超时
 */
public suspend fun waitForPageSettled(
    evaluate: suspend (String) -> String?,
    timeoutMs: Long,
    quietWindowMs: Long = 300L,
    pollIntervalMs: Long = 100L,
    lenientAfterPulses: Int = 40,
): Boolean {
    var lastMutations = -1L
    var stableSince = -1L
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

            val quiet = probe.mutations == lastMutations && probe.pendingRequests == 0
            if (quiet) {
                if (stableSince < 0) stableSince = now
                if (now - stableSince >= quietWindowMs && probe.readyComplete) {
                    done = true
                    break
                }
            } else {
                stableSince = -1L
            }
            lastMutations = probe.mutations
            pulses++

            // 无限滚动/轮播图永远不会静默 ⇒ 退化为「文档已完成且网络空闲」
            if (pulses > lenientAfterPulses && probe.readyComplete && probe.pendingRequests == 0) {
                AkeLog.w("PageSettler", "page never settles (infinite scroll?); accepting network-idle state")
                done = true
                break
            }
            delay(pollIntervalMs.coerceAtMost(500L))
        }
        done
    }

    return settled ?: false
}

/** 供外部做「注入脚本」的统一入口：每次导航后调用。 */
public suspend fun WebView.installStabilityProbe(evaluate: suspend (String) -> String?) {
    evaluate(StabilityScripts.INSTALL)
}
