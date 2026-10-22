package com.ake.mcp

import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.McpServerState
import com.ake.core.ports.TransportKind
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.HttpClient
import kotlin.time.measureTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `McpConnectionManager` 的单测 —— 本轮 :mcp 覆盖空洞里最重要的一块。
 *
 * ## 它测的是三条设计取舍（见主文件类注释），不是「能不能连上」
 *
 * | 取舍 | 对应断言 |
 * |---|---|
 * | 懒连接，不预连接 | [注册后不建连接] |
 * | 每台 server 一把锁 | [并发 withClient 只握手一次] |
 * | 重连有上限 → DEAD | [重连到上限后标记 DEAD 并停止重试] |
 *
 * ## 关于「快」
 *
 * 连接层最容易写出的坏测试是「`delay(3s)` 等它连上」。这里没有：
 * 网络被换成内存管道（[FakeMcpServer]），握手是同步完成的，
 * 于是「并发去重」这类分支可以用 `delay(50)` 的**真实并发**断言出来，而不是靠睡眠赌时序。
 * 唯一的真实等待是退避 500ms 与超时 1s，二者都在断言里被显式对照。
 */
@RunWith(JUnit4::class)
class McpConnectionManagerTest {

    private val httpClient = HttpClient()

    @After
    fun tearDown() {
        httpClient.close()
    }

    // ──────────────────────────────────────────── ① 懒连接

    @Test
    fun `注册后不建连接，第一次调用才连`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("a"))
        val manager = manager(server)

        manager.register(listOf(server.config))

        // ★ 冷启动路径上不该有握手：绝大多数任务一个 MCP 工具都不用
        assertThat(server.attemptCount()).isEqualTo(0)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DISCONNECTED)
        assertThat(manager.configs()).containsExactly(server.config)

        manager.withClient(server.config) { "used" }

        assertThat(server.attemptCount()).isEqualTo(1)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.READY)
    }

    @Test
    fun `连接会被复用，不会每次调用都握手`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("a"))
        val manager = manager(server)
        manager.register(listOf(server.config))

        repeat(5) { i -> manager.withClient(server.config) { i } }

        // MCP 的 session 是有状态的，重复握手会在 server 侧留下孤儿 session
        assertThat(server.attemptCount()).isEqualTo(1)
        assertThat(server.lastTransport()!!.receivedMethods)
            .containsExactly("initialize", "notifications/initialized")
    }

    @Test
    fun `未注册的 server 也能按需连接（computeIfAbsent 路径）`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("lazy"))
        val manager = manager(server)

        assertThat(manager.withClient(server.config) { it.hashCode() != 0 }).isTrue()
        assertThat(server.attemptCount()).isEqualTo(1)
    }

    // ──────────────────────────────────────────── ② 每 server 一把锁

    @Test
    fun `并发 withClient 只握手一次`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("a"))
        val manager = manager(server)
        manager.register(listOf(server.config))

        // 真实并发：8 个协程同时进来，且每个都要在 block 里挂起 50ms。
        // 若没有锁 ⇒ 8 次握手；有锁 ⇒ 只有第一个建连，其余复用。
        val results = coroutineScope {
            (1..8).map { i ->
                async {
                    manager.withClient(server.config) {
                        delay(50)
                        i
                    }
                }
            }.map { it.await() }
        }

        assertThat(results).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
        assertThat(server.attemptCount()).isEqualTo(1)
    }

    @Test
    fun `不同 server 各自独立连接`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a"))
        val b = FakeMcpServer(cfg("b"))
        val manager = manager(a, b)
        manager.register(listOf(a.config, b.config))

        coroutineScope {
            val ra = async { manager.withClient(a.config) { "a" } }
            val rb = async { manager.withClient(b.config) { "b" } }
            assertThat(ra.await()).isEqualTo("a")
            assertThat(rb.await()).isEqualTo("b")
        }

        assertThat(a.attemptCount()).isEqualTo(1)
        assertThat(b.attemptCount()).isEqualTo(1)
    }

    // ──────────────────────────────────────────── ③ 重试与上限

    @Test
    fun `首连失败后退避重试一次并成功`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("a")).handshake(FakeMcpServer.Handshake.FAIL, FakeMcpServer.Handshake.OK)
        val manager = manager(server)
        manager.register(listOf(server.config))

        val elapsed = measureTime { manager.withClient(server.config) { "ok" } }

        assertThat(server.attemptCount()).isEqualTo(2)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.READY)
        // 重试前有一次 500ms 退避 —— 断言它真的退避了，而不是瞬时打满
        assertThat(elapsed.inWholeMilliseconds).isAtLeast(McpConnectionManager.RETRY_BASE_MS)
    }

    @Test
    fun `重连到上限后标记 DEAD 并停止重试`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("a")).handshake(FakeMcpServer.Handshake.FAIL)
        val cfgA = server.config.copy(maxReconnectAttempts = 1)
        val manager = manager(server)
        manager.register(listOf(cfgA))

        // 首连：attempts 0→1，失败后 `attempts >= max` ⇒ 直接 DEAD
        val first = runCatching { manager.withClient(cfgA) { "never" } }
        assertThat(first.exceptionOrNull()).isInstanceOf(McpUnavailable::class.java)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DEAD)

        // ★ 关键：DEAD 之后**不再自动重试**。这是刻意的 —— 移动端无限退避轮询是电量问题，
        //   不是可靠性问题。连不上就交给用户去改配置，不要后台每 30 秒偷偷唤醒一次。
        val second = runCatching { manager.withClient(cfgA) { "never" } }
        assertThat(second.exceptionOrNull()).isInstanceOf(McpUnavailable::class.java)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DEAD)

        // 1 次而不是 2 次：重试虽仍会走一次 `delay(RETRY_BASE_MS)` 退避，
        // 但 `connectLocked` 在**建 transport 之前**就被上限拦住了 ⇒ 第二次握手根本没发生。
        assertThat(server.attemptCount()).isEqualTo(1)
    }

    @Test
    fun `连接成功后 attempt 计数归零：后续调用不再重试`(): Unit = runBlocking {
        // 第一次调用内部：FAIL（attempts=1）→ 退避 → OK（attempts 归零，READY）
        val server = FakeMcpServer(cfg("a")).handshake(
            FakeMcpServer.Handshake.FAIL,
            FakeMcpServer.Handshake.OK,
        )
        val manager = manager(server)
        manager.register(listOf(server.config))

        manager.withClient(server.config) { "first" }
        assertThat(server.attemptCount()).isEqualTo(2)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.READY)

        // 第二次调用：client 已就绪 ⇒ 直接复用，不再握手
        manager.withClient(server.config) { "second" }
        assertThat(server.attemptCount()).isEqualTo(2)
    }

    @Test
    fun `重连额度跨调用累计，耗尽后才 DEAD`(): Unit = runBlocking {
        // max=3，每次 withClient 最多握手 2 次（首连 + 退避重试）
        val server = FakeMcpServer(cfg("a")).handshake(FakeMcpServer.Handshake.FAIL)
        val cfgA = server.config.copy(maxReconnectAttempts = 3)
        val manager = manager(server)
        manager.register(listOf(cfgA))

        runCatching { manager.withClient(cfgA) { "call1" } }
        assertThat(server.attemptCount()).isEqualTo(2)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DISCONNECTED)

        // 第三次握手把额度用尽 ⇒ DEAD
        runCatching { manager.withClient(cfgA) { "call2" } }
        assertThat(server.attemptCount()).isEqualTo(3)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DEAD)
    }

    @Test
    fun `McpUnavailable 带上 serverId 与失败原因`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("dead-server")).handshake(FakeMcpServer.Handshake.FAIL)
        val cfgA = server.config.copy(maxReconnectAttempts = 1)
        val manager = manager(server)
        manager.register(listOf(cfgA))

        val t = runCatching { manager.withClient(cfgA) { Unit } }.exceptionOrNull() as McpUnavailable

        assertThat(t.serverId).isEqualTo("dead-server")
        assertThat(t.message).isNotEmpty()
    }

    // ──────────────────────────────────────────── ④ 回环护栏

    @Test
    fun `非回环地址在握手之前就被拒，标记 DEAD 且不计入尝试`(): Unit = runBlocking {
        val cfgRemote = McpServerConfig(id = "remote", label = "R", url = "https://evil.example.com/mcp")
        val server = FakeMcpServer(cfgRemote)
        val manager = manager(server, loopbackOnly = true)
        manager.register(listOf(cfgRemote))

        val result = runCatching { manager.withClient(cfgRemote) { "nope" } }

        assertThat(result.exceptionOrNull()).isInstanceOf(McpUnavailable::class.java)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DEAD)
        // ★ 0 次：护栏在建 transport 之前生效 —— 一次 DNS 解析都不该发生
        assertThat(server.attemptCount()).isEqualTo(0)
        assertThat(manager.states().single().lastError).contains("loopback")
    }

    @Test
    fun `关闭回环限制后允许连非回环地址`(): Unit = runBlocking {
        val cfgRemote = McpServerConfig(id = "remote", label = "R", url = "https://example.com/mcp")
        val server = FakeMcpServer(cfgRemote)
        val manager = manager(server, loopbackOnly = false)
        manager.register(listOf(cfgRemote))

        assertThat(manager.withClient(cfgRemote) { "ok" }).isEqualTo("ok")
        assertThat(server.attemptCount()).isEqualTo(1)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.READY)
    }

    @Test
    fun `回环认定的两种常见写法都被接受`(): Unit = runBlocking {
        // `127.0.0.1` 与 `localhost` 是配置页最常写的两种，必须都能过护栏
        val hosts = listOf("127.0.0.1", "localhost")
        hosts.forEachIndexed { i, host ->
            val c = McpServerConfig(id = "h$i", label = "h$i", url = "http://$host:9999/mcp")
            val s = FakeMcpServer(c)
            val m = manager(s, loopbackOnly = true)
            m.register(listOf(c))
            assertThat(m.withClient(c) { "ok" }).isEqualTo("ok")
            assertThat(s.attemptCount()).isEqualTo(1)
        }
    }

    /**
     * ★ 已知缺陷（归属 [:core:ports] 的 [McpServerConfig.isLoopback]，**不在本模块边界内，故只钉住现状、不修**）。
     *
     * `isLoopback()` 用 `substringBefore(':')` 从 URL 里切 host，而 IPv6 回环的方括号写法
     * `[::1]` 的第一个冒号在方括号**内部** ⇒ host 被切成一个 `[`（trim 后为空串）⇒ 判为非回环。
     * 后果是**用户在本机起的 IPv6 MCP server 会被本模块的回环护栏拒连**，
     * 且报错文案会误导成「这是公网地址」。
     *
     * 修法在 core 侧：先把 `://` 与 `/` 之间的 authority 取出来，
     * 再按 `]` 判断是否含方括号、分别处理端口。修好后请同步更新本断言。
     */
    @Test
    fun `IPv6 回环的方括号写法目前会被误判为非回环`(): Unit = runBlocking {
        for (host in listOf("[::1]", "[0:0:0:0:0:0:0:1]")) {
            val cfg = McpServerConfig(id = "v6", label = "v6", url = "http://$host:9999/mcp")
            assertWithMessage("host=$host 当前被判为非回环（core 侧缺陷，修复后请更新本断言）")
                .that(cfg.isLoopback())
                .isFalse()
        }
    }

    // ──────────────────────────────────────────── ⑤ 超时

    @Test
    fun `握手挂住时由整体超时兜底，而不是无限等待`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("slow")).handshake(FakeMcpServer.Handshake.HANG)
        val cfgSlow = server.config.copy(connectTimeoutMs = 1_000L, maxReconnectAttempts = 3)
        val manager = manager(server)
        manager.register(listOf(cfgSlow))

        val elapsed = measureTime { runCatching { manager.withClient(cfgSlow) { "never" } } }

        // ★ 超时由我们自己的 withMcpTimeout 管，SDK 的 transport 不负责整体超时
        assertThat(elapsed.inWholeMilliseconds).isLessThan(5_000L)
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DISCONNECTED)
        assertThat(server.attemptCount()).isEqualTo(2)
    }

    // ──────────────────────────────────────────── ⑥ 失败分类：降级 ≠ 拆连接

    @Test
    fun `连接类故障标记 DEGRADED 且保留连接不重连`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("a"))
        val manager = manager(server)
        manager.register(listOf(server.config))
        manager.withClient(server.config) { "warmup" }

        val thrown = runCatching {
            manager.withClient(server.config) { throw java.io.IOException("broken pipe") }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(java.io.IOException::class.java)
        // 只降级，不拆连接 —— 一次工具报错就拆连接会让后续调用全都退化成「重连风暴」
        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.DEGRADED)
        assertThat(server.attemptCount()).isEqualTo(1)
    }

    @Test
    fun `工具自身报错不降级（工具失败不是连接的锅）`(): Unit = runBlocking {
        val server = FakeMcpServer(cfg("a"))
        val manager = manager(server)
        manager.register(listOf(server.config))

        runCatching {
            manager.withClient(server.config) { throw IllegalArgumentException("bad args") }
        }

        assertThat(manager.states().single().phase).isEqualTo(McpServerState.Phase.READY)
        assertThat(server.attemptCount()).isEqualTo(1)
    }

    @Test
    fun `多种连接故障措辞都能被识别为传输层故障`(): Unit = runBlocking {
        val messages = listOf(
            "Connection closed",
            "Broken pipe",
            "connection reset by peer",
            "Read timed out",
            "Unable to resolve host",
            "Connection refused",
            "Not connected",
            "unexpected EOF",
        )
        messages.forEach { msg ->
            val server = FakeMcpServer(cfg("x")).handshake(FakeMcpServer.Handshake.OK)
            val manager = manager(server)
            manager.register(listOf(server.config))
            manager.withClient(server.config) { "warmup" }

            runCatching { manager.withClient(server.config) { throw java.io.IOException(msg) } }

            assertWithMessage("故障措辞='%s' 应被识别为传输层故障", msg)
                .that(manager.states().single().phase)
                .isEqualTo(McpServerState.Phase.DEGRADED)
        }
    }

    // ──────────────────────────────────────────── ⑦ register 的增删与连接回收

    @Test
    fun `下线的 server 会被关连接并移出表`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a"))
        val b = FakeMcpServer(cfg("b"))
        val manager = manager(a, b)

        manager.register(listOf(a.config, b.config))
        manager.withClient(a.config) { "a" }
        manager.withClient(b.config) { "b" }
        assertThat(a.attemptCount()).isEqualTo(1)
        assertThat(b.attemptCount()).isEqualTo(1)

        // 重新装载：只剩 a
        manager.register(listOf(a.config))

        assertThat(manager.configs()).containsExactly(a.config)
        assertThat(manager.states().map { it.id }).containsExactly("a")
        // ★ 连接必须真的关掉：否则「用户删掉了 server」之后端口还占着，
        //   server 侧的 session 也永远不会释放
        assertThat(b.lastTransport()!!.closeCount).isEqualTo(1)
        assertThat(a.lastTransport()!!.closeCount).isEqualTo(0)
    }

    @Test
    fun `配置变更会换掉 Entry 并重新连接`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a"))
        val manager = manager(a)
        manager.register(listOf(a.config))
        manager.withClient(a.config) { "v1" }

        val changed = a.config.copy(url = "http://127.0.0.1:9100/mcp", label = "changed")
        manager.register(listOf(changed))

        assertThat(manager.configs().single().url).isEqualTo("http://127.0.0.1:9100/mcp")
        manager.withClient(changed) { "v2" }
        // 新 Entry 的 client 为空 ⇒ 需要重新握手
        assertThat(a.attemptCount()).isEqualTo(2)
    }

    @Test
    fun `配置未变时保留 Entry，连接不中断`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a"))
        val manager = manager(a)
        manager.register(listOf(a.config))
        manager.withClient(a.config) { "v1" }

        manager.register(listOf(a.config.copy()))

        manager.withClient(a.config) { "v2" }
        assertThat(a.attemptCount()).isEqualTo(1)
    }

    @Test
    fun `被禁用的 server 不进表，不会被发现`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a", enabled = true))
        val off = FakeMcpServer(cfg("off", enabled = false))
        val manager = manager(a, off)
        manager.register(listOf(a.config, off.config))

        assertThat(manager.configs().map { it.id }).containsExactly("a")
        assertThat(off.attemptCount()).isEqualTo(0)
    }

    // ──────────────────────────────────────────── ⑧ disconnectAll

    @Test
    fun `disconnectAll 关闭全部连接并回到 DISCONNECTED`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a"))
        val b = FakeMcpServer(cfg("b"))
        val manager = manager(a, b)
        manager.register(listOf(a.config, b.config))
        manager.withClient(a.config) { "a" }
        manager.withClient(b.config) { "b" }

        manager.disconnectAll()

        assertThat(a.lastTransport()!!.closeCount).isEqualTo(1)
        assertThat(b.lastTransport()!!.closeCount).isEqualTo(1)
        assertThat(manager.states().map { it.phase })
            .containsExactly(McpServerState.Phase.DISCONNECTED, McpServerState.Phase.DISCONNECTED)

        // 断开后仍可按需重连：disconnectAll 是「断到干干净净」，不是「永久拉黑」
        manager.withClient(a.config) { "again" }
        assertThat(a.attemptCount()).isEqualTo(2)
        assertThat(manager.states().first { it.id == "a" }.phase).isEqualTo(McpServerState.Phase.READY)
    }

    @Test
    fun `一台 server 连接失败不影响另一台`(): Unit = runBlocking {
        val dead = FakeMcpServer(cfg("dead")).handshake(FakeMcpServer.Handshake.FAIL)
        val alive = FakeMcpServer(cfg("alive"))
        val manager = manager(dead, alive)
        manager.register(listOf(dead.config, alive.config))

        assertThat(manager.withClient(alive.config) { "ok" }).isEqualTo("ok")
        assertThat(runCatching { manager.withClient(dead.config) { "no" } }.exceptionOrNull())
            .isInstanceOf(McpUnavailable::class.java)

        val byId = manager.states().associateBy { it.id }
        assertThat(byId["alive"]!!.phase).isEqualTo(McpServerState.Phase.READY)
        assertThat(byId["dead"]!!.phase).isNotEqualTo(McpServerState.Phase.READY)
    }

    // ──────────────────────────────────────────── 助手

    private fun cfg(
        id: String,
        url: String = "http://127.0.0.1:9000/mcp",
        enabled: Boolean = true,
        transport: TransportKind = TransportKind.STREAMABLE_HTTP,
    ): McpServerConfig = McpServerConfig(
        id = id,
        label = id,
        url = url,
        enabled = enabled,
        transport = transport,
        // 单测里把超时压到最小：withMcpTimeout 有 1s 下限，重试退避 500ms 是常量
        connectTimeoutMs = 1_000L,
        callTimeoutMs = 1_000L,
    )

    private fun manager(vararg servers: FakeMcpServer, loopbackOnly: Boolean = true): McpConnectionManager =
        McpConnectionManager(
            httpClient = httpClient,
            loopbackOnly = loopbackOnly,
            transportFactory = fakeTransportFactory(servers.toList()),
        )
}
