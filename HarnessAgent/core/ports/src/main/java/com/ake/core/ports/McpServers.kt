package com.ake.core.ports

import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolSpec

/**
 * # MCP 服务器清单与连接状态
 *
 * 这两个 Port 让 `:ui` 不必再直接依赖 `:mcp`。此前 UI 引用了
 * `McpServerConfig` / `McpServerState` / `TransportKind` / `McpToolFacade` 四个类，
 * 而它们全住在 `:mcp` —— 于是整个 MCP 模块的技术方案都对 UI 可见了。
 *
 * ## ★ 迁移说明（与 `Runtime.kt` 同一批，同一个打法）
 *
 * `McpServerConfig` / `McpServerState` / `TransportKind` 这三个纯数据形状，
 * **新的唯一事实来源是本文件**；`:mcp` 应当删掉自己的三份定义、改为 import 这里，
 * `McpToolAdapter` 再实现本文件的两个接口。
 * 全部改完之后才能摘 `ui/build.gradle.kts` 的 `:mcp` 依赖。
 *
 * ## 「模型永远看不到 MCP 的存在」这条不变
 *
 * 本文件只描述「服务器清单怎么存、连上了没」，不含任何 SDK 类型。
 * MCP 组件对上层收敛成 `:core:contract` 的 `ToolSpec`/`ToolResult` 形状。
 */

/**
 * MCP server 配置。
 *
 * ## MVP 边界（架构 §7.3）
 *
 * | 做 | 不做 |
 * |---|---|
 * | 连接**本机 localhost** 的 MCP server | 连接云端 / 公网 MCP server |
 * | Streamable HTTP transport | stdio transport（Android 上没有干净的进程派生给第三方 server） |
 * | 用户手动录入 server URL | 自动发现 / mDNS 广播 |
 * | 工具 schema 拉取 + 逐工具授权开关 | OAuth 流程（MVP 只支持无认证或静态 Header） |
 *
 * ## 为什么默认强制回环
 *
 * 端侧 Agent 在用户的**登录态**下操作浏览器。允许连公网 MCP server 意味着
 * 「第三方可以请求我们代表用户执行工具」，而工具返回内容又会进 prompt ——
 * 这是一条完整的提示注入回路。MVP 把这条回路**物理切断**：只连回环地址。
 */
public data class McpServerConfig(
    /** 稳定标识。工具 id 的命名空间前缀，改名会让已授权记录失效 —— 所以要稳定。 */
    val id: String,
    val label: String,
    val url: String,
    /** 静态 Header（MVP 不做 OAuth）。会随每次请求发送，不要放长期凭据。 */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val transport: TransportKind = TransportKind.STREAMABLE_HTTP,
    /** 单次工具调用超时。工具可能很慢，但它占用的是主循环的 actionMs 预算。 */
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
    public fun isLoopback(): Boolean {
        val host = url.substringAfter("://").substringBefore('/')
            .substringBefore(':').trim('[', ']')
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1"
    }

    public companion object {
        /** 全局开关：默认 true 即「只允许回环」。改它需要用户显式确认。 */
        public const val DEFAULT_LOOPBACK_ONLY: Boolean = true
    }
}

public enum class TransportKind {
    /** MCP 2025-06-18 起推荐的形态：单 endpoint、POST + 可选 SSE 流。 */
    STREAMABLE_HTTP,

    /** 旧版 HTTP+SSE 双通道。为兼容性保留。 */
    SSE,
}

/** 单台 server 的运行状态。给 UI 的「工具与连接」页用。 */
public data class McpServerState(
    val id: String,
    val label: String,
    val phase: Phase,
    val toolCount: Int = 0,
    val lastError: String? = null,
    val connectedAtMs: Long? = null,
    val attempts: Int = 0,
) {
    public enum class Phase { DISCONNECTED, CONNECTING, READY, DEGRADED, DEAD }
}

/**
 * MCP 服务器清单的持久化（实现应在 `:data`，替换 `:ui` 现在那份 `org.json` 兜底）。
 *
 * 落到 `:data` 而不是留在 UI 的理由很实在：这份清单会被清除流程删掉，
 * 而「清除哪些文件」的判断属于持久化层。放在 UI 会让两边范围悄悄分叉
 * （那边补了 `filesDir/mcp/`、这边没补 ⇒ 「一键清除」到底清没清干净取决于谁来回答）。
 */
public interface McpServerStorePort {

    public suspend fun load(): List<McpServerConfig>

    public suspend fun save(list: List<McpServerConfig>)

    public suspend fun upsert(config: McpServerConfig)

    public suspend fun remove(id: String)

    /**
     * 清空全部清单。★ `WipeEverythingUseCase` 里那条 TODO 等的就是它：
     * 「core/ports 落地 McpServerStorePort 后，这里改成 store.clearAll()，本段删除」。
     */
    public suspend fun clearAll()
}

/**
 * MCP 连接门面 —— **只暴露 UI 实际用到的四个动作**。
 *
 * 刻意**没有** `invoke(...)`：真正去调用工具的是 `:agent` 的 `ToolRegistryPort`
 * （注册表把本地工具与 MCP 工具收敛成同一形状），UI 从来不自己调工具。
 * 多暴露一个方法就多一条「这个方法该由谁在什么时机调用」的歧义。
 *
 * ## 「断到干干净净」为什么是独立方法
 *
 * 用户在设置页点了「断开外部连接」，与此后能不能再连上是两件事；
 * 而 App 退后台、或执行一键清除前，都必须先把连接断掉，否则清完可能立刻又被写回来。
 */
public interface McpConnectionPort {

    /** 重新装载 server 配置。**下线的 server 会被断开**。 */
    public suspend fun configure(servers: List<McpServerConfig>)

    /**
     * 全量发现。**单台 server 失败不影响其它 server 的结果** ——
     * 一台挂了就把整个工具页变空，用户会误以为是自己的配置错了。
     */
    public suspend fun discover(): List<ToolSpec>

    /** 各 server 当前连接状态。 */
    public suspend fun states(): List<McpServerState>

    /** 逐工具启停。它与 `ToolPermissionStorePort.setEnabled` 必须一起走 —— 只改一处会让开关失灵。 */
    public suspend fun setEnabled(toolId: ToolId, enabled: Boolean)

    /** 断掉全部连接。清痕前置动作，也是独立的隐私开关。 */
    public suspend fun shutdown()
}
