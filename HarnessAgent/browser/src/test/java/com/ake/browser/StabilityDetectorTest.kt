package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test

// ===========================================================================
// P2 · 页面稳定检测
//
// 判据只有两条：
//   ① 稳定信号到达后就绪（而且必须**真的观察到了一段静默**，不是第一次 probe 就放行）
//   ② 信号永不到达时**必须有超时**，不能永久挂起
//
// 怎么测时间：`android/os/TestSystemClock.kt` 把 `SystemClock.elapsedRealtime()`
// 换成了测试推着走的时钟，所以 300 ms 的静默窗口不必真等 300 ms；
// 而 `withTimeoutOrNull` 用的是真实时间 —— 「会不会永久挂起」因此仍然是被真测的。
// ===========================================================================

class StabilityDetectorTest {

    @Before
    fun resetClock() {
        android.os.SystemClock.reset()
    }

    // ───────────────────────────────────────────── probe 解析

    @Test
    fun parsesFullProbeString() {
        val p = StabilityProbe.parse("128,2,complete,420,9800")
        assertThat(p.mutations).isEqualTo(128L)
        assertThat(p.pendingRequests).isEqualTo(2)
        assertThat(p.readyComplete).isTrue()
        assertThat(p.scrollY).isEqualTo(420)
        assertThat(p.contentHeight).isEqualTo(9800)
    }

    @Test
    fun toleratesQuotesWhitespaceAndCasing() {
        val p = StabilityProbe.parse(" \"8,0,COMPLETE, 12, 3400\" \n")
        assertThat(p.mutations).isEqualTo(8L)
        assertThat(p.readyComplete).isTrue()
        assertThat(p.scrollY).isEqualTo(12)
    }

    @Test
    fun missingTrailingFieldsFallBackToDefaults() {
        val p = StabilityProbe.parse("5,1,loading")
        assertThat(p.mutations).isEqualTo(5L)
        assertThat(p.pendingRequests).isEqualTo(1)
        assertThat(p.readyComplete).isFalse()
        assertThat(p.scrollY).isEqualTo(0)
        assertThat(p.contentHeight).isEqualTo(0)
    }

    @Test
    fun garbageNeverThrows() {
        for (bad in listOf(null, "", "  ", "x,y,z", ",,,", "1.5,-1,xyz,2.5,3.5", "-")) {
            val p = StabilityProbe.parse(bad)
            assertWithMessage("bad=$bad").that(p.mutations).isAtLeast(0L)
            assertWithMessage("bad=$bad").that(p.readyComplete).isFalse()
        }
    }

    @Test
    fun negativeNetworkCounterIsKeptAsIs() {
        // probe 不去把它 clamp 到 0 —— 见下面「XHR 双 decrement」那条：
        // 计数漂成负数是真实可能的，这里必须看得见才能诊断。
        val p = StabilityProbe.parse("3,-1,complete,0,1000")
        assertThat(p.pendingRequests).isEqualTo(-1)
    }

    // ───────────────────────────────────────────── 就绪路径

    @Test
    fun settlesAfterARealQuietWindow() {
        val polls = ArrayList<String>()
        var calls = 0
        val settled = settle(
            timeoutMs = 2_000,
            quietWindowMs = 60,
            evaluate = {
                calls++
                android.os.SystemClock.advance(20)
                val probe = when (calls) {
                    1 -> "10,1,loading,0,1000" // 网络还在飞
                    2 -> "10,0,loading,0,1000" // 网络空了，但文档没完成
                    else -> "10,0,complete,0,1000"
                }
                polls += probe
                probe
            },
        )

        assertThat(settled).isTrue()
        // ★ 必须**至少**三轮：第 1 轮建立基线、第 2 轮确认计数没动、第 3 轮才够静默窗口。
        //   一次 probe 就放行 = 拿到的是上一页 ⇒ 候选全是旧元素 ⇒ 后续动作必 STALE。
        assertThat(calls).isAtLeast(3)
        assertThat(polls.last()).contains("complete")
    }

    @Test
    fun documentNotCompleteBlocksSettlingEvenWhenPerfectlyQuiet() {
        val settled = settle(
            timeoutMs = 400,
            quietWindowMs = 20,
            lenientAfterPulses = 5,
            evaluate = { android.os.SystemClock.advance(30); "0,0,loading,0,1000" },
        )
        // ★ 「 DOM 静默」不是充分条件：readyState 没到 complete，永远不放行（超时也不）
        assertThat(settled).isFalse()
    }

    @Test
    fun networkInFlightBlocksSettling() {
        val settled = settle(
            timeoutMs = 400,
            quietWindowMs = 20,
            lenientAfterPulses = 5,
            evaluate = { android.os.SystemClock.advance(30); "0,3,complete,0,1000" },
        )
        // 三个请求在飞 ⇒ 伪稳定（数据还没回来），必须继续等
        assertThat(settled).isFalse()
    }

    @Test
    fun continuouslyChangingDomNeverLooksQuiet() {
        var n = 0
        val settled = settle(
            timeoutMs = 300,
            quietWindowMs = 20,
            lenientAfterPulses = 1_000_000,
            evaluate = { n++; android.os.SystemClock.advance(20); "$n,0,complete,0,1000" },
        )
        assertThat(settled).isFalse() // mutation 每轮都在涨 ⇒ 静默窗口永远被重置
    }

    // ───────────────────────────────────────────── 兜底与超时

    @Test
    fun infiniteScrollFallsBackToNetworkIdleAfterLenientPulses() {
        var n = 0
        var calls = 0
        val settled = settle(
            timeoutMs = 3_000,
            quietWindowMs = 60,
            lenientAfterPulses = 4,
            evaluate = { calls++; n++; android.os.SystemClock.advance(20); "$n,0,complete,0,1000" },
        )
        assertThat(settled).isTrue()
        assertThat(calls).isGreaterThan(4) // 真的等满才兜底，不是第一轮就放行
    }

    @Test
    fun lenientFallbackStillRequiresCompletedDocument() {
        var n = 0
        val settled = settle(
            timeoutMs = 400,
            quietWindowMs = 20,
            lenientAfterPulses = 3,
            evaluate = { n++; android.os.SystemClock.advance(20); "$n,0,loading,0,1000" },
        )
        assertThat(settled).isFalse() // loading 状态下连兜底都不放行
    }

    @Test
    fun neverSettlingPageIsBoundedByTimeoutNotByHanging() {
        val started = System.currentTimeMillis()
        var calls = 0
        val settled = settle(
            timeoutMs = 200,
            quietWindowMs = 10_000, // 静默窗口给得离谱地大，等于永不可能满足
            lenientAfterPulses = 1_000_000,
            evaluate = { calls++; android.os.SystemClock.advance(5); "$calls,1,loading,0,1000" },
        )
        val elapsed = System.currentTimeMillis() - started

        assertThat(settled).isFalse()
        assertThat(calls).isGreaterThan(1)
        // ★ 真实时间的上界：超时是 `withTimeoutOrNull` 保的，与上面的假时钟无关
        assertThat(elapsed).isLessThan(200 + 3_000)
    }

    @Test
    fun evaluateThrowingPropagatesInsteadOfHanging() {
        // ★ evaluate 本身炸了（WebView 已 destroy 的典型表现）必须有出口，不能挂在循环里
        val threw = runCatching {
            runBlocking {
                settleRaw(timeoutMs = 500, quietWindowMs = 20) { throw IllegalStateException("webview gone") }
            }
        }.exceptionOrNull()
        assertThat(threw).isInstanceOf(IllegalStateException::class.java)
    }

    // ───────────────────────────────────────────── ★ 未安装探测脚本时的「假稳定」

    @Test
    fun unobservedPageLooksQuietOnTheSecondProbe() {
        // ⚠★ 为什么这条还在：`waitForPageSettled` **本身**对「探测脚本没装上」这件事
        //   没有任何防御 —— 只要 probe 恒返回 `0,0,complete`，它第二轮就判静默并放行，
        //   而一次 mutation 都没看到过。这正是「页面没加载完就抽取拿到空」的机械解释。
        //
        //   防御不在这里，而在调用方：`WebViewExecutor.waitForSettled` /
        //   `WebViewDomPerception.observe` 都会先 `scriptHost.ensureReady()`，
        //   且注入脚本自带「缺 `__akeInstalled` 就报错」的自检（见 InjectPipelineTest）。
        //   这条用例保留，是为了让「谁也不能把这道自检拆掉」有可执行依据。
        var calls = 0
        val settled = settle(
            timeoutMs = 2_000,
            quietWindowMs = 60,
            evaluate = { calls++; android.os.SystemClock.advance(20); "0,0,complete,0,1000" },
        )
        assertThat(settled).isTrue()
        assertThat(calls).isAtMost(6) // 整条判定只看 readyState
    }

    @Test
    fun installScriptIsOwnedByTheBootstrapManifestNotByAScatteredCallSite() {
        // （原失败）修复前 `StabilityScripts.INSTALL` 没有任何调用方 ⇒ 页面上下文里
        // 没有 `__akeMutationCount` ⇒ probe 恒返回 `0,0,...` ⇒ 见上一条「假稳定」。
        //
        // 现在它必须只出现在**清单**里（JsBootstrap），由 PageScriptHost 统一注入；
        // 并且不再导出 `installStabilityProbe` 之类的第二套入口 —— 两套入口 = 两种漂移。
        val declaring = browserMainSources()
            .filter { it.readText().stripLineComments().contains("StabilityScripts.INSTALL") }
            .map { it.name }
        assertThat(declaring).containsExactly("JsBootstrap.kt")

        val secondEntrance = browserMainSources()
            .map { it.readText().stripLineComments() }
            .filter { it.contains("installStabilityProbe") }
        assertWithMessage("稳定探测只能有一套注入入口").that(secondEntrance).isEmpty()

        assertThat(JsBootstrap.RESOURCES.map { it.id }).contains("stability-probe")
    }

    @Test
    fun installScriptActuallyInstallsAMutationObserver() {
        val src = browserSource("java/com/ake/browser/StabilityDetector.kt").stripLineComments()
        assertThat(src).contains("MutationObserver")
        assertThat(StabilityScripts.INSTALL).contains("__akeMutationCount")
        assertThat(StabilityScripts.INSTALL).contains("__akeNetworkPending")
    }

    @Test
    fun xhrWrapperDecrementsExactlyOncePerRequest() {
        // ⚠（原失败）修复前 `open()` 里同时挂了 `loadend` 和 `error` 两个监听：
        //   按 XHR spec，**loadend 在 abort/error/load 之后一定再补一发** ⇒
        //   失败的请求 decrement 两次，而 send() 只 increment 一次
        //   ⇒ `__akeNetworkPending` 漂成负数 ⇒ `pendingRequests == 0` 再也不成立
        //   ⇒ 网络空闲这条判据永久失效，页面再也等不到「稳定」。
        val src = StabilityScripts.INSTALL

        // 只剩 loadend 一条 decrement 通路
        assertThat(src).doesNotContain("'error',")
        assertWithMessage("send() 只能有一次 increment").that(src.countOccurrences("__akeNetworkPending++")).isEqualTo(2)

        // 且带 per-request 的 once 守卫 —— 双保险，任何一条补发路径都不会多减
        assertThat(src).contains("this.__akeHooked")
        assertThat(src).contains("this.__akeSettled")

        // 计数夹在 0 下界：即便还有别的漏减路径，负数也不会让「网络空闲」永久不成立
        assertThat(src).contains("if (window.__akeNetworkPending > 0) window.__akeNetworkPending--;")
    }

    @Test
    fun fetchWrapperCannotLeakAnIncrementWhenItThrowsSynchronously() {
        // `fetch()` 可能同步抛（非法 URL / 被 CSP 拦），那时 Promise 分支一个都不会跑
        // ⇒ 计数永久 +1 ⇒ 页面再也等不到稳定。必须有 try/catch 兜住。
        val fetchPart = StabilityScripts.INSTALL.substringAfter("window.fetch = function(){")
        assertThat(fetchPart).contains("try {")
        assertThat(fetchPart).contains("catch (e) { netDone(); throw e; }")
    }

    @Test
    fun theInstalledProbeStillReportsTheNetworkCounter() {
        // 计数器必须是 probe 的一部分，否则上面几条修了也没有观测点
        assertThat(StabilityScripts.PROBE).contains("window.__akeNetworkPending")
        assertThat(StabilityScripts.INSTALL).contains("__akeMutationCount")
    }

    // ───────────────────────────────────────────── 静态：不许偷懒 sleep

    @Test
    fun noSleepBasedWaitingAnywhereInBrowser() {
        for (f in browserMainSources()) {
            val src = f.readText().stripLineComments()
            assertWithMessage("${f.name} 不应出现 Thread.sleep").that(src).doesNotContain("Thread.sleep")
            assertWithMessage("${f.name} 不应出现 TimeUnit").that(src).doesNotContain("TimeUnit")
            assertWithMessage("${f.name} 不应出现 Object.wait").that(src).doesNotContain(".wait(")
        }
    }

    @Test
    fun everyDelayIsAPollIntervalOrATimeout_notAPrioriSleep() {
        val src = browserSource("java/com/ake/browser/StabilityDetector.kt").stripLineComments()
        val delays = Regex("""delay\(([^)]*)\)""").findAll(src).map { it.groupValues[1].trim() }.toList()
        assertThat(delays).isNotEmpty()
        for (d in delays) {
            val literalMs = d.replace("_", "").toLongOrNull()
            if (literalMs != null) {
                // 写死的 sleep 只能是个轮询间隔（§5.8 推荐 100ms 量级），
                // 出现 delay(2000) 这种「等 2 秒应该差不多了」就是本项目反复警告的偷懒写法。
                assertWithMessage("delay($d) 疑似 sleep 式偷懒").that(literalMs).isLessThan(1_000L)
            }
        }
        assertThat(delays.single()).contains("pollIntervalMs") // 真机那条：轮询间隔
    }

    @Test
    fun waitingIsAlwaysBoundedByATimeout() {
        val src = browserSource("java/com/ake/browser/StabilityDetector.kt").stripLineComments()
        assertThat(src).contains("withTimeoutOrNull(timeoutMs)")
    }

    // ───────────────────────────────────────────── helper

    private fun settle(
        timeoutMs: Long,
        quietWindowMs: Long,
        lenientAfterPulses: Int = 40,
        evaluate: suspend (String) -> String?,
    ): Boolean = runBlocking { settleRaw(timeoutMs, quietWindowMs, lenientAfterPulses, evaluate) }

    private fun String.countOccurrences(token: String): Int =
        windowed(token.length, 1) { sub -> if (sub == token) 1 else 0 }.sum()
}

private suspend fun settleRaw(
    timeoutMs: Long,
    quietWindowMs: Long,
    lenientAfterPulses: Int = 40,
    evaluate: suspend (String) -> String?,
): Boolean = waitForPageSettled(
    evaluate = evaluate,
    timeoutMs = timeoutMs,
    quietWindowMs = quietWindowMs,
    pollIntervalMs = 1,
    lenientAfterPulses = lenientAfterPulses,
)
