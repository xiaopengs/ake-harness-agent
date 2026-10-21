package com.ake.mcp

/**
 * MCP server 配置。
 *
 * ## MVP 边界（架构 §7.3，务必写清楚以免范围失控）
 *
 * | 做 | 不做 |
 * |---|---|
 * | 连接**本机 localhost** 的 MCP server | 连接云端 / 公网 MCP server |
 * | Streamable HTTP transport | stdio transport（Android 上没有干净的进程派生 + IPC 方案给第三方 server） |
 * | 用户手动录入 server URL | 自动发现 / mDNS 广播 |
 * | 工具 schema 拉取 + 逐工具授权开关 | OAuth 流程（MVP 只支持无认证或静态 Header） |
 *
 * ## 为什么默认强制 localhost
 *
 * 端侧 Agent 在用户的**登录态**下操作浏览器。允许连公网 MCP server 意味着
 * 「第三方可以请求我们代表用户执行工具」，而工具返回内容又会进 prompt ——
 * 这是一条完整的提示注入回路（T1）。MVP 把这条回路**物理切断**：只连回环地址。
 * 需要放开时由用户显式关闭 [allowNonLoopback]，且 UI 必须给出明确警示。
 */
data class McpServerConfig(
    /** 稳定标识。工具 id 的命名空间前缀，改名会让已授权记录失效 —— 所以要稳定。 */
    val id: String,
    val label: String,
    val url: String,
    /** 静态 Header（MVP 不做 OAuth）。会随每次请求发送，不要放长期凭据。 */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val transport: TransportKind = TransportKind.STREAMABLE_HTTP,
    /** 单次工具调用超时。工具可能很慢，但它占用的是我们的 LoopBudget.actionMs。 */
    val callTimeoutMs: Long = 8_000L,
    /** 连接握手超时。 */
    val connectTimeoutMs: Long = 6_000L,
    /** 断线重连最大次数。达上限后标记 DEAD，不再自动重试（避免无意义的轮询烧电）。 */
    val maxReconnectAttempts: Int = 3,
) {
    init {
        require(id.isNotBlank()) { "server id must not be blank" }
        require(url.startsWith("http://") || url.startsWith("https://")) { "url must be http(s): $url" }
    }

    /** 回环判定。IPv4/IPv6/主机名三种写法都要认 —— 只判 `127.0.0.1` 会漏掉 `[::1]` 与 `localhost`。 */
    fun isLoopback(): Boolean {
        val host = url.substringAfter("://").substringBefore('/')
            .substringBefore(':').trim('[', ']')
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1"
    }

    companion object {
        /** 全局开关：默认 true 即「只允许回环」。改它需要用户显式确认。 */
        const val DEFAULT_LOOPBACK_ONLY: Boolean = true
    }
}

enum class TransportKind {
    /** MCP 2025-06-18 起推荐的形态：单 endpoint、POST + 可选 SSE 流。 */
    STREAMABLE_HTTP,

    /** 旧版 HTTP+SSE 双通道。为兼容性保留。 */
    SSE,
}

/** 单台 server 的运行状态。给 UI 的「工具与连接」页用。 */
data class McpServerState(
    val id: String,
    val label: String,
    val phase: Phase,
    val toolCount: Int = 0,
    val lastError: String? = null,
    val connectedAtMs: Long? = null,
    val attempts: Int = 0,
) {
    enum class Phase { DISCONNECTED, CONNECTING, READY, DEGRADED, DEAD }
}
