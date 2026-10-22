package com.ake.mcp

import com.ake.core.contract.ErrorCode
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.McpServerState
import com.ake.core.ports.ToolPermissionStorePort
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `McpToolFacade`（实现 [McpToolAdapter]）的单测 —— 编排层。
 *
 * ## 这一层要守的四条契约
 *
 *  1. **工具发现**：`discover()` 把多台 server 的工具收敛成同一份 `ToolSpec`；
 *  2. **单台失败不扩散**：一台 server 挂了，其它 server 的工具照常出现（契约原文）；
 *  3. **按工具粒度启停 + 授权同步**：`setEnabled` 必须**同时**改内存开关与授权表
 *     （只改一处会让开关失灵，见 `core/ports/McpServers.kt` 的注释）；
 *  4. **结果按预算裁剪**：工具返回可能几百 KB，不裁就会在 ContextAssembler 里被尾部截断。
 *
 * ## 错误分类（★ 第 3 条是刻意保留的观察点，见 [传输层异常按 UNKNOWN 归类]）
 *
 * | 情形 | 期望 |
 * |---|---|
 * | 工具被禁用 | `Error(DISABLED)`，且不发起调用 |
 * | 连接拿不到 | `Error(IO)`（值得重试） |
 * | 工具自己报 isError | `Error(UNKNOWN)`（换路） |
 * | 调用途中传输层异常 | 当前实现是 `Error(UNKNOWN)` |
 */
@RunWith(JUnit4::class)
class McpToolAdapterTest {

    private val httpClient = HttpClient()

    @After
    fun tearDown() {
        httpClient.close()
    }

    // ──────────────────────────────────────────── ① 工具发现

    @Test
    fun `没有配置 server 时 discover 返回空表`(): Unit = runBlocking {
        val facade = facade()

        assertThat(facade.discover()).isEmpty()
    }

    @Test
    fun `discover 把 MCP 工具转成契约形状`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(
            tool(name = "read", description = "读文件", properties = schemaOf("path")),
        )
        val facade = facade(fs)
        facade.configure(listOf(fs.config))

        val specs = facade.discover()

        assertThat(specs).hasSize(1)
        val spec = specs.single()
        assertThat(spec.id).isEqualTo(ToolId("mcp.fs.read"))
        assertThat(spec.name).isEqualTo("read")
        assertThat(spec.description).isEqualTo("读文件")
        assertThat(spec.origin).isEqualTo(ToolOrigin.MCP)
        assertThat(spec.jsonSchema).contains("path")
        assertThat(spec.riskClass).isEqualTo(com.ake.core.contract.RiskClass.EXTERNAL_SIDE_EFFECT)
    }

    @Test
    fun `discover 汇总多台 server 的工具`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a")).withTools(tool("a1"), tool("a2"))
        val b = FakeMcpServer(cfg("b")).withTools(tool("b1"))
        val facade = facade(a, b)
        facade.configure(listOf(a.config, b.config))

        val ids = facade.discover().map { it.id.value }

        assertThat(ids).containsExactly("mcp.a.a1", "mcp.a.a2", "mcp.b.b1")
    }

    @Test
    fun `单台 server 失败不影响其它 server 的结果`(): Unit = runBlocking {
        // ★ 契约原文：「一台挂了就把整个工具页变空，用户会误以为是自己的配置错了」
        val dead = FakeMcpServer(cfg("dead")).handshake(FakeMcpServer.Handshake.FAIL)
        val alive = FakeMcpServer(cfg("alive")).withTools(tool("still_here"))
        val facade = facade(dead, alive)
        facade.configure(listOf(dead.config, alive.config))

        val ids = facade.discover().map { it.id.value }

        assertThat(ids).containsExactly("mcp.alive.still_here")
        assertThat(facade.states().first { it.id == "dead" }.phase)
            .isNotEqualTo(McpServerState.Phase.READY)
    }

    @Test
    fun `被禁用的 server 不参与发现`(): Unit = runBlocking {
        val off = FakeMcpServer(cfg("off", enabled = false)).withTools(tool("hidden"))
        val facade = facade(off)
        facade.configure(listOf(off.config))

        assertThat(facade.discover()).isEmpty()
        assertThat(off.attemptCount()).isEqualTo(0)
    }

    // ──────────────────────────────────────────── ② 按工具粒度启停

    @Test
    fun `禁用单个工具后 discover 不再返回它`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("read"), tool("rm"))
        val facade = facade(fs)
        facade.configure(listOf(fs.config))

        facade.setEnabled(ToolId("mcp.fs.rm"), false)

        assertThat(facade.discover().map { it.id.value }).containsExactly("mcp.fs.read")
    }

    @Test
    fun `重新启用后工具回到发现结果`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("read"), tool("rm"))
        val facade = facade(fs)
        facade.configure(listOf(fs.config))

        facade.setEnabled(ToolId("mcp.fs.rm"), false)
        facade.setEnabled(ToolId("mcp.fs.rm"), true)

        assertThat(facade.discover().map { it.id.value }).containsExactly("mcp.fs.read", "mcp.fs.rm")
    }

    @Test
    fun `启停会同步写入授权表（只改一处会让开关失灵）`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("rm"))
        val store = FakePermissionStore()
        val facade = facade(fs, permissions = store)
        facade.configure(listOf(fs.config))
        facade.discover()

        facade.setEnabled(ToolId("mcp.fs.rm"), false)

        // ★ `McpConnectionPort.setEnabled` 的契约：「它与 ToolPermissionStorePort.setEnabled 必须一起走」
        assertThat(store.setEnabledCalls).containsExactly(ToolId("mcp.fs.rm") to false)
    }

    @Test
    fun `discover 会把结果落进授权表（按工具撤销的前提是先有行）`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("read"), tool("rm"))
        val store = FakePermissionStore()
        val facade = facade(fs, permissions = store)
        facade.configure(listOf(fs.config))

        facade.discover()
        facade.setEnabled(ToolId("mcp.fs.rm"), false)
        facade.discover()

        // 第二次发现时 rm 已被禁用 ⇒ 落库的清单里不该再有它
        assertThat(store.discovered.size).isEqualTo(2)
        assertThat(store.discovered.last().map { it.id.value }).containsExactly("mcp.fs.read")
    }

    // ──────────────────────────────────────────── ③ 调用编排

    @Test
    fun `invoke 正常返回文本结果`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("read"))
        val facade = facade(fs)
        facade.configure(listOf(fs.config))
        facade.discover()

        val res = facade.invoke(ToolId("mcp.fs.read"), mapOf("path" to "/tmp/a"))

        assertThat(res).isEqualTo(ToolResult.Text("ok"))
        assertThat(fs.lastTransport()!!.receivedCalls)
            .containsExactly("read" to mapOf("path" to "/tmp/a"))
    }

    @Test
    fun `invoke 未 discover 时按 id 反解 server 直连`(): Unit = runBlocking {
        // catalog 为空的兜底路径：`mcp.<serverId>.<toolName>` 直接反解出 server
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("read"))
        val facade = facade(fs)
        facade.configure(listOf(fs.config))

        val res = facade.invoke(ToolId("mcp.fs.read"), emptyMap())

        assertThat(res).isEqualTo(ToolResult.Text("ok"))
        assertThat(fs.attemptCount()).isEqualTo(1)
    }

    @Test
    fun `invoke 被禁用的工具返回 DISABLED 且不发起调用`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("rm"))
        val facade = facade(fs)
        facade.configure(listOf(fs.config))
        facade.discover()
        facade.setEnabled(ToolId("mcp.fs.rm"), false)

        val res = facade.invoke(ToolId("mcp.fs.rm"), emptyMap())

        assertThat(res).isEqualTo(ToolResult.Error(ErrorCode.DISABLED, null))
        assertThat(fs.lastTransport()!!.receivedCalls).isEmpty()
    }

    @Test
    fun `invoke 未登记的工具返回 NOT_FOUND`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs"))
        val facade = facade(fs)
        facade.configure(listOf(fs.config))

        assertThat(facade.invoke(ToolId("mcp.unknown.tool"), emptyMap()))
            .isEqualTo(ToolResult.Error(ErrorCode.NOT_FOUND, "unknown mcp tool: mcp.unknown.tool"))
        assertThat(fs.attemptCount()).isEqualTo(0)
    }

    @Test
    fun `invoke 非 mcp 命名空间的 id 返回 NOT_FOUND`(): Unit = runBlocking {
        val facade = facade()

        assertThat(facade.invoke(ToolId("browser.click"), emptyMap()))
            .isEqualTo(ToolResult.Error(ErrorCode.NOT_FOUND, "unknown mcp tool: browser.click"))
    }

    @Test
    fun `invoke 连接不可用时归类为 IO（值得重试）`(): Unit = runBlocking {
        val dead = FakeMcpServer(cfg("dead")).handshake(FakeMcpServer.Handshake.FAIL)
        val facade = facade(dead)
        facade.configure(listOf(dead.config))

        val res = facade.invoke(ToolId("mcp.dead.anything"), emptyMap())

        assertThat(res).isInstanceOf(ToolResult.Error::class.java)
        assertThat((res as ToolResult.Error).code).isEqualTo(ErrorCode.IO)
    }

    @Test
    fun `invoke 工具自身报错时归类为 UNKNOWN（换路而不是重试）`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("boom"))
        fs.callHandler = { _, _ -> CallToolResult(content = listOf(textBlock("炸了")), isError = true) }
        val facade = facade(fs)
        facade.configure(listOf(fs.config))
        facade.discover()

        val res = facade.invoke(ToolId("mcp.fs.boom"), emptyMap())

        assertThat(res).isInstanceOf(ToolResult.Error::class.java)
        assertThat((res as ToolResult.Error).code).isEqualTo(ErrorCode.UNKNOWN)
    }

    @Test
    fun `传输层异常按 UNKNOWN 归类`(): Unit = runBlocking {
        // ★ 观察点：只有「连接阶段就拿不到 client」才是 IO（`McpUnavailable`）。
        //   调用途中炸出来的 `IOException("broken pipe")` 会先被 `withClient` 标记 DEGRADED 再原样抛出，
        //   落到 adapter 的 catch 里时它**不是** `McpUnavailable` ⇒ 归到 UNKNOWN。
        //   这里按当前实现断言，用来固定住这个行为；若判定应当改成 IO，改这里即可。
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("flaky"))
        fs.callHandler = { _, _ -> throw java.io.IOException("broken pipe") }
        val facade = facade(fs)
        facade.configure(listOf(fs.config))
        facade.discover()

        val res = facade.invoke(ToolId("mcp.fs.flaky"), emptyMap())

        assertThat(res).isInstanceOf(ToolResult.Error::class.java)
        assertThat((res as ToolResult.Error).code).isEqualTo(ErrorCode.UNKNOWN)
    }

    @Test
    fun `invoke 空结果映射为 Noop 而不是空 Text`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("noop"))
        fs.callHandler = { _, _ -> CallToolResult(content = emptyList()) }
        val facade = facade(fs)
        facade.configure(listOf(fs.config))
        facade.discover()

        assertThat(facade.invoke(ToolId("mcp.fs.noop"), emptyMap())).isEqualTo(ToolResult.Noop)
    }

    // ──────────────────────────────────────────── ④ 结果按预算裁剪

    @Test
    fun `工具返回超长文本时结果被裁到预算内`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("read"))
        fs.callHandler = { _, _ -> textResult("X".repeat(50_000)) }
        val facade = facade(fs)
        facade.configure(listOf(fs.config))
        facade.discover()

        val res = facade.invoke(ToolId("mcp.fs.read"), emptyMap())

        assertThat(res).isInstanceOf(ToolResult.Text::class.java)
        val text = (res as ToolResult.Text).value
        assertThat(text.length).isLessThan(50_000)
        assertThat(text).contains("中间省略")
        assertThat(text.length).isAtMost(6_000)
    }

    // ──────────────────────────────────────────── ⑤ 状态与停机

    @Test
    fun `states 反映各 server 的连接阶段`(): Unit = runBlocking {
        val a = FakeMcpServer(cfg("a"))
        val facade = facade(a)
        facade.configure(listOf(a.config))

        assertThat(facade.states().single().phase).isEqualTo(McpServerState.Phase.DISCONNECTED)

        facade.discover()

        val state = facade.states().single()
        assertThat(state.phase).isEqualTo(McpServerState.Phase.READY)
        assertThat(state.connectedAtMs).isNotNull()
    }

    @Test
    fun `shutdown 断开连接并清空 catalog`(): Unit = runBlocking {
        val fs = FakeMcpServer(cfg("fs")).withTools(tool("read"))
        val facade = facade(fs)
        facade.configure(listOf(fs.config))
        facade.discover()
        assertThat(fs.attemptCount()).isEqualTo(1)

        facade.shutdown()

        assertThat(fs.lastTransport()!!.closeCount).isEqualTo(1)
        assertThat(facade.states().single().phase).isEqualTo(McpServerState.Phase.DISCONNECTED)

        // catalog 已清空 ⇒ 再调用会走 resolveFromId 兜底路径，仍然可用
        assertThat(facade.invoke(ToolId("mcp.fs.read"), emptyMap())).isEqualTo(ToolResult.Text("ok"))
        assertThat(fs.attemptCount()).isEqualTo(2)
    }

    // ──────────────────────────────────────────── 助手

    private fun cfg(id: String, enabled: Boolean = true): McpServerConfig = McpServerConfig(
        id = id,
        label = id,
        url = "http://127.0.0.1:9000/mcp",
        enabled = enabled,
        connectTimeoutMs = 1_000L,
        callTimeoutMs = 1_000L,
    )

    private fun facade(
        vararg servers: FakeMcpServer,
        permissions: ToolPermissionStorePort? = null,
    ): McpToolFacade = McpToolAdapter(
        httpClient = httpClient,
        permissions = permissions,
        loopbackOnly = true,
        transportFactory = fakeTransportFactory(servers.toList()),
    )

    private fun schemaOf(vararg names: String): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(
            names.associateWith {
                kotlinx.serialization.json.JsonObject(
                    mapOf("type" to kotlinx.serialization.json.JsonPrimitive("string")),
                )
            },
        )
}
