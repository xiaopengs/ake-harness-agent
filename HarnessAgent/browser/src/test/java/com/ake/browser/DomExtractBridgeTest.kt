package com.ake.browser

import com.ake.core.contract.PageState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

// ===========================================================================
// P3 · JS Bridge
//
// `@JavascriptInterface` 是按**方法名字符串**回调的：改名、改签名、接口名对不上，
// 都表现为「注入成功、页面正常、永远收不到 DOM」。这一段跑不了真 WebView
// （不引 Robolectric —— 它的 WebView 是假的，测出来的东西不可信；真机验证留给 M10），
// 所以这里只验**桥本身的状态机**：那是一段不依赖 WebView 的纯 Kotlin 逻辑。
//
// ★ 确定性：`evalScript` 是被 `run()` **同步**调用的（注册 pending 之后、await 之前），
//   所以回调直接从 eval lambda 里打回来即可 —— 不需要虚拟时间，也不会有调度竞态。
//   需要「两个请求同时在飞」的用例改用 `CoroutineStart.UNDISPATCHED` 起 async，
//   它会在返回前把 pending 注册完（到第一个挂起点为止），同样没有竞态。
// ===========================================================================

class DomExtractBridgeTest {

    // ───────────────────────────────────────────── 脚本包装与接口名

    @Test
    fun scriptIsWrappedWithAMonotonicCallbackId() {
        val seen = ArrayList<String>()
        val bridge = DomExtractBridge { seen += it }

        runBlocking {
            bridge.run("doStuff();", timeoutMs = 50)
            bridge.run("moreStuff();", timeoutMs = 50)
        }
        assertThat(seen).hasSize(2)
        assertThat(seen[0]).startsWith("(function(){var AKE_CB_ID=1;")
        assertThat(seen[0]).endsWith("})();")
        assertThat(seen[0]).contains("doStuff();")
        assertThat(seen[1]).contains("var AKE_CB_ID=2;")
    }

    @Test
    fun interfaceNameIsTheOneJsCalls() {
        // JS 侧写死 `AkeBridge.deliver(...)`（见 dom-extract.js 与 WebViewDomPerception 的脚本）
        assertThat(DomExtractBridge.INTERFACE_NAME).isEqualTo("AkeBridge")
        assertThat(DomExtractBridge { }.interfaceName).isEqualTo("AkeBridge")
    }

    @Test
    fun deliverSuffixTargetIsTheInterfaceNameUsedByJs() {
        assertThat(DomExtractBridge { }.deliverSuffix).startsWith("AkeBridge.deliver(AKE_CB_ID,")
    }

    @Test
    fun everyJsDeliverCallSiteUsesTheSameInterfaceName() {
        // 静态问诊：主源码里所有 `.deliver(` 的前缀必须是同一个名字 ——
        // 手改一处成 `AkeBridge2` 是「看起来无害」的致命改动。
        val names = browserMainSources()
            .flatMap { DELIVER_CALL.findAll(it.readText().stripLineComments()).toList() }
            .map { it.groupValues[1] }
            .toSet()
        assertThat(names).containsExactly(DomExtractBridge.INTERFACE_NAME)
    }

    // ───────────────────────────────────────────── 正常路径

    @Test
    fun deliverResolvesAndClearsPending() {
        val bridge = answering { id, b -> b.deliver(id, """{"ok":true}""") }
        val result = runBlocking { bridge.run("extract();", timeoutMs = 500) }

        assertThat(result).isEqualTo("""{"ok":true}""")
        assertThat(bridge.inFlightCount()).isEqualTo(0)
    }

    @Test
    fun concurrentRequestsAreMatchedByCallbackId() {
        val bridge = DomExtractBridge { }
        val results = runBlocking {
            val a = async(start = CoroutineStart.UNDISPATCHED) { bridge.run("a", timeoutMs = 1_000) }
            val b = async(start = CoroutineStart.UNDISPATCHED) { bridge.run("b", timeoutMs = 1_000) }
            assertThat(bridge.inFlightCount()).isEqualTo(2)
            bridge.deliver(2, "second") // 后发的先回来也必须落到正确的地方
            bridge.deliver(1, "first")
            listOf(a.await(), b.await())
        }
        assertThat(results).containsExactly("first", "second").inOrder()
        assertThat(bridge.inFlightCount()).isEqualTo(0)
    }

    // ───────────────────────────────────────────── 幂等

    @Test
    fun duplicateDeliverIsIgnored() {
        val bridge = answering { id, b ->
            b.deliver(id, "first-wins")
            b.deliver(id, "duplicate")
            b.deliver(id, "duplicate-again")
        }
        val result = runBlocking { bridge.run("extract();", timeoutMs = 500) }

        // ★ 二次 complete 会抛 IllegalStateException —— 注释里点名的真实崩溃来源
        assertThat(result).isEqualTo("first-wins")
        assertThat(bridge.inFlightCount()).isEqualTo(0)
    }

    @Test
    fun deliverForUnknownOrFinishedIdIsIgnored() {
        val bridge = DomExtractBridge { }
        bridge.deliver(999, "no-one-awaits-this")
        bridge.deliverError(999, "boom")
        assertThat(bridge.inFlightCount()).isEqualTo(0)
    }

    @Test
    fun deliverErrorAlsoSettlesExactlyOnce() {
        val bridge = answering { id, b ->
            b.deliverError(id, "ReferenceError: __extract is not defined")
            b.deliverError(id, "again")
        }
        // ⚠ deliverError 落到调用方手里就是 **null**：错误信息在 `getOrNull()` 的那一刻丢了
        assertThat(runBlocking { bridge.run("extract();", timeoutMs = 500) }).isNull()
        assertThat(bridge.inFlightCount()).isEqualTo(0)
    }

    // ───────────────────────────────────────────── 超时与取消

    @Test
    fun noCallbackYieldsNullWithinTimeout() {
        val started = System.currentTimeMillis()
        val result = runBlocking { DomExtractBridge { }.run("x", timeoutMs = 150) }
        assertThat(result).isNull()
        assertThat(System.currentTimeMillis() - started).isLessThan(3_000)
    }

    @Test
    fun timeoutClearsPendingSoLateCallbacksCannotLeak() {
        val bridge = DomExtractBridge { }
        runBlocking { bridge.run("x", timeoutMs = 100) }
        assertThat(bridge.inFlightCount()).isEqualTo(0)
        bridge.deliver(1, "late") // 迟到的回调必须被吞掉
        assertThat(bridge.inFlightCount()).isEqualTo(0)
    }

    @Test
    fun cancelAllReleasesWaitersWithNull() {
        val bridge = DomExtractBridge { }
        val results = runBlocking {
            val a = async(start = CoroutineStart.UNDISPATCHED) { bridge.run("a", timeoutMs = 1_000) }
            val b = async(start = CoroutineStart.UNDISPATCHED) { bridge.run("b", timeoutMs = 1_000) }
            assertThat(bridge.inFlightCount()).isEqualTo(2)
            bridge.cancelAll()
            listOf(a.await(), b.await())
        }
        assertThat(results).containsExactly(null, null)
        assertThat(bridge.inFlightCount()).isEqualTo(0)
    }

    @Test
    fun defaultTimeoutIsThreeSeconds() {
        assertThat(DomExtractBridge.DEFAULT_TIMEOUT_MS).isEqualTo(3_000L)
    }

    // ───────────────────────────────────────────── ★ 静默失败链

    @Test
    fun emptyPayloadIsASuccessfulDeliverThatBecomesAnErroredPage() {
        // ⚠★ 咽喉 failure mode：`deliver(id, "")` 是一次**成功**回调，`run()` 返回 ""（不是 null！）
        //   ⇒ 上层当正常产物处理 ⇒ `parse("")` 抛 ⇒ `PocketDom.errored()`。
        //   整条链路上没有任何一处报「注入/抽取失败」，UI 上看到的就是「这一页没有元素」。
        val bridge = answering { id, b -> b.deliver(id, null) }
        val payload = runBlocking { bridge.run("extract();", timeoutMs = 500) }

        assertThat(payload).isEmpty()
        assertThat(DomJsonParser.parse(payload!!, topK = 48).pageState).isEqualTo(PageState.ERROR)
    }

    @Test
    fun timeoutAndJsErrorAreIndistinguishableToCaller() {
        val timedOut = runBlocking { DomExtractBridge { }.run("x", timeoutMs = 100) }
        val errored = runBlocking {
            answering { id, b -> b.deliverError(id, "TypeError: x is null") }
                .run("extract();", timeoutMs = 500)
        }
        // ★ 两者都只是 null —— 上层拿到的信息里完全看不出「是慢了，还是页面脚本炸了」
        assertThat(timedOut).isNull()
        assertThat(errored).isNull()
    }

    // ───────────────────────────────────────────── helper

    /** eval 里同步把结果打回来 —— 无调度竞态的单请求夹具。 */
    private fun answering(reply: (id: Long, bridge: DomExtractBridge) -> Unit): DomExtractBridge {
        lateinit var bridge: DomExtractBridge
        bridge = DomExtractBridge { script ->
            val id = CALLBACK_ID.find(script)!!.groupValues[1].toLong()
            reply(id, bridge)
        }
        return bridge
    }

    private companion object {
        val CALLBACK_ID = Regex("""AKE_CB_ID=(\d+)""")
        val DELIVER_CALL = Regex("""(\w+)\.deliver\(""")
    }
}
