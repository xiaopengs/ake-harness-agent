package com.ake.mcp

import com.ake.core.common.AkeLog
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 多 server 连接管理（架构 §7.3 封装层）。
 *
 * ## 三条设计取舍
 *
 *  1. **懒连接，不预连接。** App 启动时连一圈 MCP server 会在冷启动路径上增加 6 秒 × N 的不确定性，
 *     而绝大多数任务一个 MCP 工具都不用。连接发生在第一次 `discover()` / 第一次 `invoke()`。
 *  2. **每台 server 一把锁。** 两并发 `discover()` 不应该建两条连接 ——
 *     MCP 的 session 是有状态的，重复握手会让 server 侧出现孤儿 session。
 *  3. **断线重试有上限。** 连不上的 server 走到 [McpServerState.Phase.DEAD] 后不再自动重试。
 *     无限退避重试在移动端是**电量问题**而不是可靠性问题：后台每 30 秒唤醒一次去连一个
 *     已经关掉的 localhost 端口，用户第二天会发现电量掉得快。
 *
 * ## 关于 SDK 类型
 *
 * 本文件与 [McpSchemaMapper] 是 `:mcp` 里**仅有的两个**允许 import `io.modelcontextprotocol.*` 的文件。
 * 对外（[McpToolAdapter]）只有 `:core:contract` 的形状。
 */
internal class McpConnectionManager(
    private val httpClient: HttpClient,
    /** 客户端自述。写进握手包，server 侧日志里能看到是谁在调。 */
    private val clientName: String = "ake-agent",
    private val clientVersion: String = "1.0.0",
    private val loopbackOnly: Boolean = McpServerConfig.DEFAULT_LOOPBACK_ONLY,
) {

    private class Entry(
        val config: McpServerConfig,
        val mutex: Mutex = Mutex(),
        @Volatile var client: Client? = null,
        @Volatile var state: McpServerState = McpServerState(config.id, config.label, McpServerState.Phase.DISCONNECTED),
        @Volatile var attempts: Int = 0,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun register(configs: List<McpServerConfig>) {
        val live = configs.filter { it.enabled }.associateBy { it.id }
        // 下线的 server：关连接并从表里移除，但不删授权记录（:data 的 tool_registry 按 last_seen 处理）
        for ((id, e) in entries) {
            if (id !in live) {
                entries.remove(id)
                runCatching { e.client?.close() }
            }
        }
        for ((id, cfg) in live) {
            val existing = entries[id]
            if (existing == null) {
                entries[id] = Entry(cfg)
            } else if (existing.config != cfg) {
                entries[id] = Entry(cfg)
            }
        }
    }

    fun states(): List<McpServerState> = entries.values.map { it.state }

    fun configs(): List<McpServerConfig> = entries.values.map { it.config }

    /**
     * 在连接可用的前提下执行 [block]。
     *
     * 失败语义：**连接类失败会自动重连一次**，工具执行类失败不重连（工具失败不是连接的锅）。
     */
    suspend fun <T> withClient(config: McpServerConfig, block: suspend (Client) -> T): T {
        val e = entries.computeIfAbsent(config.id) { Entry(config) }
        e.mutex.withLock {
            if (e.client == null) {
                connectLocked(e)
            }
            var client = e.client
            if (client == null) {
                // 首连失败 ⇒ 退避后重试一次（§7.3「断线重连」）
                delay(RETRY_BASE_MS)
                connectLocked(e)
                client = e.client
            }
            if (client == null) {
                throw McpUnavailable(config.id, e.state.lastError ?: "connect failed")
            }
            return try {
                block(client)
            } catch (t: Throwable) {
                // 只把「很可能已断线」的情况标记为降级，避免一次工具报错就拆掉连接
                if (looksLikeTransportFailure(t)) {
                    e.state = e.state.copy(phase = McpServerState.Phase.DEGRADED, lastError = t.message?.take(160))
                }
                throw t
            }
        }
    }

    suspend fun disconnectAll() {
        for (e in entries.values) {
            e.mutex.withLock {
                runCatching { e.client?.close() }
                e.client = null
                e.state = e.state.copy(phase = McpServerState.Phase.DISCONNECTED)
            }
        }
    }

    // ──────────────────────────────────────────── 内部

    private suspend fun connectLocked(e: Entry) {
        val cfg = e.config
        if (loopbackOnly && !cfg.isLoopback()) {
            e.state = e.state.copy(
                phase = McpServerState.Phase.DEAD,
                lastError = "refused: non-loopback url is disabled in MVP (§7.3)",
            )
            AkeLog.w(TAG, "server ${cfg.id} rejected: non-loopback ${cfg.url}")
            return
        }
        if (e.attempts >= cfg.maxReconnectAttempts) {
            e.state = e.state.copy(phase = McpServerState.Phase.DEAD, lastError = "max reconnect attempts reached")
            return
        }

        e.attempts += 1
        e.state = e.state.copy(phase = McpServerState.Phase.CONNECTING, lastError = null)
        val client = Client(
            clientInfo = Implementation(name = clientName, version = clientVersion),
            options = ClientOptions(capabilities = ClientCapabilities()),
        )
        try {
            // ★ 握手超时必须自己管：SDK 的 transport 不会替我们做整体超时
            val transport = buildTransport(cfg)
            withMcpTimeout(cfg.connectTimeoutMs) { client.connect(transport) }
            e.client = client
            e.attempts = 0
            e.state = e.state.copy(
                phase = McpServerState.Phase.READY,
                lastError = null,
                connectedAtMs = System.currentTimeMillis(),
            )
            AkeLog.i(TAG, "server ${cfg.id} connected (${cfg.transport.name})")
        } catch (t: Throwable) {
            runCatching { client.close() }
            e.client = null
            val phase = if (e.attempts >= cfg.maxReconnectAttempts) {
                McpServerState.Phase.DEAD
            } else {
                McpServerState.Phase.DISCONNECTED
            }
            e.state = e.state.copy(phase = phase, lastError = t.message?.take(160))
            AkeLog.w(TAG, "server ${cfg.id} connect failed: ${t.message}")
        }
    }

    /** 唯一的 SDK transport 构造点。两种形态的参数名不同（`url` vs `urlString`），别混。 */
    private fun buildTransport(cfg: McpServerConfig): Transport {
        val requestBuilder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (cfg.headers.isNotEmpty()) {
                headers {
                    for ((k, v) in cfg.headers) append(k, v)
                }
            }
        }
        return when (cfg.transport) {
            TransportKind.STREAMABLE_HTTP -> StreamableHttpClientTransport(
                client = httpClient,
                url = cfg.url,
                reconnectionOptions = ReconnectionOptions(maxRetries = cfg.maxReconnectAttempts),
                requestBuilder = requestBuilder,
            )

            TransportKind.SSE -> SseClientTransport(
                client = httpClient,
                urlString = cfg.url,
                reconnectionTime = cfg.connectTimeoutMs.milliseconds,
                requestBuilder = requestBuilder,
            )
        }
    }

    /** 连接层故障的形态很杂，这里按消息特征识别；识别错了的代价只是一次多余重连。 */
    private fun looksLikeTransportFailure(t: Throwable): Boolean {
        val m = (t.message ?: return false).lowercase()
        return m.contains("closed") || m.contains("broken pipe") || m.contains("connection reset") ||
            m.contains("timeout") || m.contains("unable to resolve") || m.contains("refused") ||
            m.contains("not connected") || m.contains("eof")
    }

    private suspend fun <T> withMcpTimeout(ms: Long, block: suspend () -> T): T =
        kotlinx.coroutines.withTimeout(ms.coerceAtLeast(1_000L)) { block() }

    companion object {
        const val TAG: String = "McpConnection"
        const val RETRY_BASE_MS: Long = 500L

        /** 供 ReconnectionOptions 的默认档，避免到处写魔法数。 */
        val DEFAULT_RECONNECT: ReconnectionOptions = ReconnectionOptions(maxRetries = 3)
        val DEFAULT_RECONNECT_TIME = 5.seconds
    }
}

/** 连接不可用。`McpToolAdapter` 会把它转成 `ToolResult.Error(IO)`，不让它穿透到上层。 */
class McpUnavailable(val serverId: String, override val message: String) : RuntimeException(message)
