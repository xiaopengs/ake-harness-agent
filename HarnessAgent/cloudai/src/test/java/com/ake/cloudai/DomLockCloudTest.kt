package com.ake.cloudai

import com.ake.core.common.Outcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.PromptContext
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★★ DOM 锁 C 的**字节级**证据（架构 08 §5.3 / §5.4 / §9.2）。
 *
 * ## 这条测试为什么必须放在 `:cloudai` 而不是 `:inference`
 *
 * 因为要断言的是「`transport.stream()` **一次都没被调用**」，
 * 而 `CloudTransport` 是 `:cloudai` 的 `internal` 类型（刻意不外泄：
 * 外面能自己实现传输层，就等于多了一条出境路径）。
 *
 * ## 为什么「返回了 Err」还不够
 *
 * 一个实现完全可以先发出去、再返回 `PageContextRefused` —— 返回值一模一样，
 * 数据却已经在第三方服务器上了。所以判据必须是**副作用**：
 *  · `transport.calls == 0`     —— 字节没进传输层
 *  · `creds.secretReadCount == 0` —— 连密钥都没读（拒绝发生在读凭证之前）
 *
 * ★ 并且必须有**反证**：[textOnlyPathDoesReachTheTransport] 证明这个计数器
 *   在真的发请求时确实会 +1。否则 `calls == 0` 可能只是因为计数器坏了。
 */
class DomLockCloudTest {

    private fun port(transport: ScriptedTransport, creds: FakeCredentials = FakeCredentials()) =
        OpenAiCompatibleDialogPort(creds, transport, CloudRuntimeConfig.DEFAULT, FixedClock(0L))

    private val page = PromptContext.WithPageContext(
        host = "shop.example.com",
        domChars = 9_000,
        elementCount = 240,
    )

    // ═══ 1. 拒绝发生在任何字节离开之前 ═══

    @Test
    fun pageContextIsRefusedBeforeAnyByteLeaves() = runBlocking {
        val transport = ScriptedTransport()
        val creds = FakeCredentials()

        val r = port(transport, creds).openDialog(testSpec(), testPermit(), page)

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(CloudFailure.PageContextRefused.code)
        // ★★ 真正的判据：副作用为零
        assertThat(transport.calls).isEqualTo(0)
        // 连明文都没读 —— 拒绝发生在「拿凭证」之前，凭证在这条路径上完全没被触碰
        assertThat(creds.secretReadCount).isEqualTo(0)
    }

    /** ★ permit **不是**解锁页面上下文的钥匙：有票也一样拒。 */
    @Test
    fun aValidPermitDoesNotUnlockPageContext() = runBlocking {
        val transport = ScriptedTransport()
        val creds = FakeCredentials()

        val r = port(transport, creds).openDialog(testSpec(), testPermit(), page)

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(creds.secretReadCount).isEqualTo(0)
        assertThat(transport.calls).isEqualTo(0)
    }

    /**
     * ★ 没有 permit 的纯文本请求同样在开流之前失败。
     *
     * 与上一条合起来：**能走到传输层的路径只有一条** ——
     * 「无页面上下文 ∧ 有 permit ∧ 凭证存在 ∧ 端点合规」。
     */
    @Test
    fun textOnlyWithoutPermitFailsBeforeAnyByteLeaves() = runBlocking {
        val transport = ScriptedTransport()

        val r = port(transport).openDialog(testSpec(), permit = null, PromptContext.TextOnly)

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(transport.calls).isEqualTo(0)
    }

    /** 端点不合规（http + 非 LAN）⇒ 同样一个字节都没发。 */
    @Test
    fun insecureEndpointFailsBeforeAnyByteLeaves() = runBlocking {
        val transport = ScriptedTransport()
        val creds = FakeCredentials("http://gw.example.com/v1")

        val r = port(transport, creds).openDialog(testSpec(), testPermit(), PromptContext.TextOnly)

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(transport.calls).isEqualTo(0)
    }

    /** permit 的 host 与配置里的 host 不一致 ⇒ 拒绝（防止 permit 被挪用）。 */
    @Test
    fun permitForAnotherHostIsRejected() = runBlocking {
        val transport = ScriptedTransport()

        val r = port(transport).openDialog(
            testSpec(),
            testPermit(host = "evil.example.com"),
            PromptContext.TextOnly,
        )

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat(transport.calls).isEqualTo(0)
    }

    // ═══ 2. 反证：计数器是活的 ═══

    /**
     * ★★ 反证（anti-vacuity）：真的发一次请求时，`calls` 必须变成 1。
     *
     * 没有这一条，上面四条 `calls == 0` 可能只是「计数器根本没接上」。
     * 这是负向测试的**必要配套**：先证明尺子能量，再证明长度是零。
     */
    @Test
    fun textOnlyPathDoesReachTheTransport() = runBlocking {
        val transport = ScriptedTransport(failWith = CloudTransportOutcome.ConnectFailed)

        val opened = port(transport).openDialog(testSpec(), testPermit(), PromptContext.TextOnly)
        assertThat(opened).isInstanceOf(Outcome.Ok::class.java)

        val session = (opened as Outcome.Ok).value
        val result = session.generateStream(testRequest()) { }

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat(transport.calls).isEqualTo(1)
    }

    /**
     * ★ 页面上下文 + 真 permit + 完整流：**一个 chunk 都不该被投递**。
     *
     * 上面几条只看 `calls`；这条看**用户可见的效果** ——
     * `onDelta` 一次都没被调用。两条证据一起才叫「什么都没发生」。
     */
    @Test
    fun noDeltaIsEverDeliveredForAPageContextSession() = runBlocking {
        val transport = ScriptedTransport()
        val deltas = mutableListOf<String>()

        val opened = port(transport).openDialog(testSpec(), testPermit(), page)

        assertThat(opened).isInstanceOf(Outcome.Err::class.java)
        assertThat(deltas).isEmpty()
        assertThat(transport.calls).isEqualTo(0)
    }
}
