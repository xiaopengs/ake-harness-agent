package com.ake.mcp

import com.ake.core.common.AkeLog
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
// ★ 显式 import core 原类（理由见 McpServerConfig.kt 与 McpConnectionManager.kt 的注释）：
//   同包 alias 只解决「旧坐标还能用」，不代理嵌套 classifier 与伴生对象成员。
import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.McpServerState
import com.ake.core.ports.McpConnectionPort
import com.ake.core.ports.ToolPermissionStorePort
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对外暴露的 MCP 门面（架构 §7.1 `McpToolAdapter`）。
 *
 * ## 签名刻意与 `:agent` 的 `ToolSource` 完全同构
 *
 * `:mcp`（L3）不能反向依赖 `:agent`（L4），所以真正把 MCP 接进注册表的是 `:app` 里
 * 一个 6 行桥接类（见 `MCP_BRIDGE.md`）。这里保证两个接口**方法名与签名一致**，
 * 于是桥接是纯转发 —— 纯转发里不可能藏 bug，这是我们愿意多一层的唯一形态。
 *
 * ## 不暴露任何 SDK 类型
 *
 * 本文件的 import 里**没有** `io.modelcontextprotocol.*`（除 `Client`/`RequestOptions` 仅出现在
 * 私有实现细节中）。调用方（`:app` → `:agent`）从头到尾只见 `:core:contract` 的形状。
 */
/**
 * ★ 继承而非重抄：`McpToolFacade` 现在**就是** `:core:ports` 的 [McpConnectionPort]，
 * 只在此基础上多一个 [invoke]。此前本接口把 `configure/discover/setEnabled/states/shutdown`
 * 五个方法原样重抄了一遍 —— 那时 `:mcp` 与契约层同时存在两个互不相干的同构接口。
 *
 * 为什么是继承而不是留着旧接口再写一个 adapter：`:app` 侧有
 * `McpToolSourceAdapter` 依赖 [McpToolFacade]，继承后 `McpToolAdapter` **同时**满足
 * `McpToolFacade` 与 `McpConnectionPort`，`:app` 可以直接把同一个实例绑到 Port 上，
 * 不需要第 4 个类去转发转发。
 *
 * 被刻意留在 Port 之外的 [invoke] 不属于 UI：真正调用工具的是 `:agent` 的
 * `ToolRegistryPort`（注册表把本地工具与 MCP 工具收敛成同一形状），UI 从来不自己调工具。
 */
interface McpToolFacade : McpConnectionPort {

    /** 调用一个 MCP 工具。**只有 `:agent` 经 ToolRegistry 会走到这里，UI 不调。** */
    suspend fun invoke(toolId: ToolId, args: Map<String, String>): ToolResult
}

/**
 * 默认实现。
 *
 * ## `automaticToolCalling = false`（架构 §3.2 第 6 环）
 *
 * 工具调用**由 Loop 驱动**，SDK 不做任何自动的多轮工具编排。
 * 原因很直接：端侧一次 decode 2.3 秒，任何「SDK 内部再跑一轮模型」的隐式行为
 * 都会让 `LoopBudget` 的账对不上 —— 而预算对不上就是 OOM 的前兆。
 *
 * ## 并发与串行
 *
 * `discover()` 对多台 server **串行**执行：MVP 规模下（≤ 5 台）串行与并发的差别是几十毫秒，
 * 而并发会让「哪台 server 慢」这件事在日志里变得难以归因。
 * `invoke()` 走 `McpConnectionManager` 的每 server 锁，同 server 串行、不同 server 天然并行。
 */
class McpToolAdapter(
    httpClient: HttpClient,
    /** 逐工具授权落点（§8.5 / 数据库 F7：落 Room 不落 DataStore）。可为 null（测试场景）。 */
    private val permissions: ToolPermissionStorePort? = null,
    private val loopbackOnly: Boolean = McpServerConfig.DEFAULT_LOOPBACK_ONLY,
) : McpToolFacade {

    private val connections = McpConnectionManager(httpClient = httpClient, loopbackOnly = loopbackOnly)
    private val catalogMutex = Mutex()

    /** 最近一次 discover 的结果。为空时 `invoke` 也能工作（按 id 反解 server 名直连）。 */
    @Volatile
    private var catalog: Map<ToolId, Pair<McpServerConfig, String>> = emptyMap()

    private val enabledOverrides = java.util.concurrent.ConcurrentHashMap<ToolId, Boolean>()

    override suspend fun configure(servers: List<McpServerConfig>) {
        connections.register(servers)
    }

    override suspend fun discover(): List<ToolSpec> {
        val out = ArrayList<ToolSpec>(64)
        val index = HashMap<ToolId, Pair<McpServerConfig, String>>()

        for (cfg in connections.configs()) {
            if (!cfg.enabled) continue
            val specs = runCatching {
                connections.withClient(cfg) { client: Client ->
                    client.listTools(request = ListToolsRequest()).tools.map { tool ->
                        McpSchemaMapper.toToolSpec(cfg.id, tool)
                    }
                }
            }.getOrElse { t ->
                AkeLog.w(TAG, "discover failed on ${cfg.id}: ${t.message}")
                emptyList()
            }
            for (spec in specs) {
                index[spec.id] = cfg to spec.name
                if (enabledOverrides[spec.id] != false) out += spec
            }
        }

        catalogMutex.withLock { catalog = index }

        // 落授权表：§8.5 要求「按工具粒度可撤销」，前提是先有行
        permissions?.let { store ->
            runCatching { store.upsertDiscovered(out) }
                .onFailure { AkeLog.w(TAG, "upsertDiscovered failed: ${it.message}") }
        }
        return out
    }

    override suspend fun invoke(toolId: ToolId, args: Map<String, String>): ToolResult {
        if (enabledOverrides[toolId] == false) {
            return ToolResult.Error(com.ake.core.contract.ErrorCode.DISABLED, null)
        }
        val (cfg, toolName) = catalog[toolId] ?: resolveFromId(toolId)
        if (cfg == null) {
            return ToolResult.Error(com.ake.core.contract.ErrorCode.NOT_FOUND, "unknown mcp tool: ${toolId.value}")
        }
        return try {
            connections.withClient(cfg) { client: Client ->
                val res = client.callTool(
                    name = toolName,
                    arguments = args,
                    meta = emptyMap(),
                    options = RequestOptions(timeout = cfg.callTimeoutMs.milliseconds),
                )
                McpSchemaMapper.toToolResult(res)
            }
        } catch (t: Throwable) {
            // 连接类故障 ⇒ IO（值得重试）；工具自身报错 ⇒ UNKNOWN（换路）
            val code = if (t is McpUnavailable) com.ake.core.contract.ErrorCode.IO
            else com.ake.core.contract.ErrorCode.UNKNOWN
            AkeLog.w(TAG, "invoke ${toolId.value} failed: ${t.message}")
            ToolResult.Error(code, t.message?.take(160))
        }
    }

    override suspend fun setEnabled(toolId: ToolId, enabled: Boolean) {
        enabledOverrides[toolId] = enabled
        permissions?.let { runCatching { it.setEnabled(toolId, enabled) } }
    }

    override suspend fun states(): List<McpServerState> = connections.states()

    override suspend fun shutdown() {
        connections.disconnectAll()
        catalogMutex.withLock { catalog = emptyMap() }
    }

    /** `mcp.<serverId>.<toolName>` → (配置, 工具名)。catalog 未命中时的兜底路径。 */
    private fun resolveFromId(toolId: ToolId): Pair<McpServerConfig?, String> {
        val v = toolId.value
        if (!v.startsWith(MCP_PREFIX)) return null to ""
        val rest = v.removePrefix(MCP_PREFIX)
        val dot = rest.indexOf('.')
        if (dot <= 0) return null to ""
        val serverId = rest.substring(0, dot)
        val toolName = rest.substring(dot + 1)
        val cfg = connections.configs().firstOrNull { it.id == serverId }
        return cfg to toolName
    }

    companion object {
        const val TAG: String = "McpToolAdapter"
        const val MCP_PREFIX: String = "mcp."

        val ORIGIN: ToolOrigin = ToolOrigin.MCP
    }
}
