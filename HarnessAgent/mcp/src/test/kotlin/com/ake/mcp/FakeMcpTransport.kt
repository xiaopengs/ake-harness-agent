package com.ake.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ★ 测试替身：一台**跑在内存里**的 MCP server。
 *
 * ## 为什么必须有它
 *
 * `:mcp` 里真正值得测的东西是**我们自己的逻辑**：
 * 懒连接、每 server 一把锁、重连上限 → DEAD、并发去重、超时、错误分类、结果裁剪。
 * 这些都与网络无关；而真实 MCP SDK 走 ktor，在 JVM 单测里连不出去。
 *
 * 于是这里把**「网络」换成一根内存管道**，但仍然让**真实的 SDK `Client`** 跑在上面：
 * 握手（`initialize` / `notifications/initialized`）、`tools/list`、`tools/call`
 * 都按 MCP 的真实报文形状走一遭。这样既覆盖了我们的代码路径，
 * 又不会把「SDK 自己会不会握手」变成测试目标 —— 后者是 SDK 的责任，不是我们的。
 *
 * ## 它刻意不模拟的东西
 *
 *  - **不模拟 SSE 帧格式 / HTTP 状态码**：那是 transport 的实现细节，由 SDK 负责；
 *  - **不模拟 OAuth / 重连退避算法**：MVP 不支持前者，后者由
 *    `ReconnectionOptions` 决定，我们只断言「交给它了」这件事；
 *  - **不模拟恶意报文**：提示注入的防线在 `PolicyPort` 与 `extractText` 的裁剪，不在本文件。
 *
 * ## 关于「为什么在 `send()` 里同步回包」
 *
 * 真实 transport 是异步的（另一个协程从 socket 读到报文再回调 `onMessage`）。
 * 这里同步回包，是因为 SDK 的 `Protocol.request()` 在**调用 `send()` 之前**
 * 就已经把 `id → handler` 注册进响应表了（实测握手可通过，见 `McpConnectionManagerTest`）。
 * 同步回包让测试**完全确定**：不需要 `delay`、不需要 `runTest` 的虚拟时间，
 * 一次 `withClient` 返回时账目必然是干净的。
 */
internal class FakeMcpServer(
    val config: McpServerConfig,
    tools: List<Tool> = emptyList(),
) {

    /** 一次握手的结局。 */
    enum class Handshake {
        /** 正常完成握手。 */
        OK,

        /** 握手失败（等价于连不上 / connection refused）。 */
        FAIL,

        /** 握手挂住不返回 —— 用来触发 `withMcpTimeout` 的整体超时。 */
        HANG,
    }

    private val lock = Any()
    private val outcomes = mutableListOf(Handshake.OK)
    private val created = mutableListOf<FakeMcpTransport>()

    /** `tools/list` 返回的工具列表。可在测试中途改，用于验证「发现结果是快照」。 */
    @Volatile
    var tools: List<Tool> = tools

    /** `tools/call` 的处理器。默认回一段文本。 */
    @Volatile
    var callHandler: suspend (String, JsonObject) -> CallToolResult = { _, _ -> textResult("ok") }

    /**
     * 依次指定每次握手的结局。
     *
     * ★ 队列耗尽后**保持最后一个**：这样「首连失败、重试成功」只需写
     * `handshake(FAIL, OK)`，而「一直失败」只需写 `handshake(FAIL)`，不必数次数。
     */
    fun handshake(vararg outcomes: Handshake): FakeMcpServer = apply {
        synchronized(lock) {
            this.outcomes.clear()
            this.outcomes.addAll(outcomes.toList())
        }
    }

    /** `tools/list` 返回的工具。 */
    fun withTools(vararg tools: Tool): FakeMcpServer = apply { this.tools = tools.toList() }

    /** 握手尝试次数（每次 `connectLocked` 建一个 transport ⇒ 与 attempts 等价）。 */
    fun attemptCount(): Int = synchronized(lock) { created.size }

    fun transports(): List<FakeMcpTransport> = synchronized(lock) { created.toList() }

    fun lastTransport(): FakeMcpTransport? = synchronized(lock) { created.lastOrNull() }

    internal fun nextOutcome(): Handshake = synchronized(lock) {
        if (outcomes.size > 1) outcomes.removeAt(0) else outcomes[0]
    }

    internal fun register(t: FakeMcpTransport) {
        synchronized(lock) { created += t }
    }
}

/**
 * 内存 transport。
 *
 * 由 [FakeMcpServer] 按握手次数创建；不直接 new，否则计数会漏。
 */
internal class FakeMcpTransport(
    private val serverId: String,
    private val server: FakeMcpServer,
) : Transport {

    @Volatile
    var startCount: Int = 0
        private set

    @Volatile
    var closeCount: Int = 0
        private set

    /** 收到的请求方法名（按序）。 */
    val receivedMethods: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** 收到的 `tools/call`（工具名 → 入参）。 */
    val receivedCalls: MutableList<Pair<String, Map<String, String>>> =
        java.util.Collections.synchronizedList(mutableListOf())

    private var messageHandler: (suspend (JSONRPCMessage) -> Unit)? = null
    private var closeHandler: (() -> Unit)? = null
    private var errorHandler: ((Throwable) -> Unit)? = null

    override suspend fun start() {
        startCount++
        when (server.nextOutcome()) {
            FakeMcpServer.Handshake.OK -> Unit
            FakeMcpServer.Handshake.FAIL -> throw java.io.IOException("Connection refused (fake $serverId)")
            FakeMcpServer.Handshake.HANG -> awaitCancellation()
        }
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        when (message) {
            is JSONRPCRequest -> {
                receivedMethods += message.method
                val result: RequestResult = handleRequest(message)
                messageHandler?.invoke(JSONRPCResponse(message.id, result))
            }

            is JSONRPCNotification -> receivedMethods += message.method
            else -> receivedMethods += message.javaClass.simpleName
        }
    }

    override suspend fun close() {
        closeCount++
    }

    override fun onClose(block: () -> Unit) {
        closeHandler = block
    }

    override fun onError(block: (Throwable) -> Unit) {
        errorHandler = block
    }

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        messageHandler = block
    }

    private suspend fun handleRequest(req: JSONRPCRequest): RequestResult {
        val params = req.params?.jsonObject ?: JsonObject(emptyMap())
        return when (req.method) {
            "initialize" -> InitializeResult(
                // ★ 回显请求里的版本号：SDK 会用「自己发的是什么」来校验，
                //   写死一个版本会在 SDK 升版后突然连不上，那是假失败。
                protocolVersion = params["protocolVersion"]?.jsonPrimitive?.content
                    ?: "2025-06-18",
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
                serverInfo = Implementation(name = "fake-$serverId", version = "0.0.1"),
            )

            "tools/list" -> ListToolsResult(tools = server.tools)

            "tools/call" -> {
                val name = params["name"]?.jsonPrimitive?.content ?: ""
                val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                receivedCalls += name to args.mapValues { (_, v) -> v.jsonPrimitive.content }
                server.callHandler(name, args)
            }

            else -> error("fake server '$serverId' does not implement method '${req.method}'")
        }
    }
}

// ──────────────────────────────────────────────────────────────── 工厂

/**
 * 把若干台 [FakeMcpServer] 装成 `McpConnectionManager` 的测试接缝。
 *
 * ★ 未登记的 server id 会让测试**快速失败**（抛异常）而不是悄悄连真实网络 ——
 *   后者会让「这台 server 为什么连不上」变成一个需要读网络栈才能回答的问题。
 */
internal fun fakeTransportFactory(servers: List<FakeMcpServer>): McpConnectionManager.TransportFactory =
    McpConnectionManager.TransportFactory { cfg ->
        val s = servers.firstOrNull { it.config.id == cfg.id }
            ?: error("no FakeMcpServer registered for id='${cfg.id}'")
        FakeMcpTransport(cfg.id, s).also(s::register)
    }

// ──────────────────────────────────────────────────────────────── 报文构造糖

internal fun textResult(text: String, isError: Boolean = false): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = text)), isError = isError)

internal fun blocksResult(vararg blocks: ContentBlock, isError: Boolean = false): CallToolResult =
    CallToolResult(content = blocks.toList(), isError = isError)
