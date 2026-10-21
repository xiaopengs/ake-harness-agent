package com.ake.agent.tools

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.contract.ErrorCode
import com.ake.core.contract.RiskClass
import com.ake.core.contract.ToolCall
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.ToolRegistryPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 工具注册表的聚合实现（架构 §7.1 的 `ToolRegistry` 那一层）。
 *
 * **模型侧看到的永远是同一份 `ToolSpec`，不关心背后是本地 Kotlin 函数还是 MCP server。**
 * 这让 `PolicyPort` 只需在一处把关、tracing 只需在一个点埋 —— 安全代码写在越少的地方越好，
 * 因为它是唯一不能出错的那类代码。
 *
 * ## 三条实现纪律
 *
 *  1. **§7.2 硬限制：同时启用 ≤ 12 个工具。** 每加一个工具就占用 SYSTEM 段预算
 *     （STANDARD 档 SYSTEM 只有 1,152 token）。超限时**静默截断**而不是报错 ——
 *     工具数量是用户配置的结果，不该让任务起不来，但必须记 WARN 让人看得到。
 *  2. **`origin` 决定失败语义。** MCP 工具可能只是断线（值得重试 ⇒ `IO`），
 *     本地工具炸了是真炸（⇒ `UNKNOWN`，换路）。混在一起会让重试策略在错误的方向上重试。
 *  3. **`discover()` 失败不污染已有目录。** 一个 MCP server 挂掉不该清空整个工具列表 ——
 *     那会让模型以为自己什么都不能做，直接 `finish` 掉一个本可完成的任务。
 */
class CompositeToolRegistry(
    private val sources: Set<ToolSource>,
    private val maxEnabledTools: Int = DEFAULT_MAX_ENABLED,
) : ToolRegistryPort {

    private val mutex = Mutex()

    /** 全量目录（含 disabled）。 */
    private val catalog = LinkedHashMap<ToolId, ToolSpec>()

    /** toolId → 归属来源。在 discover 时建立，避免每次 invoke 都重新问一遍所有来源。 */
    private val owner = HashMap<ToolId, ToolSource>()

    /** 用户显式的启停覆盖。重新 discover 不会复活被禁用的工具（§8.5 逐工具授权）。 */
    private val enabledOverrides = HashMap<ToolId, Boolean>()

    private val _flow = MutableStateFlow<List<ToolSpec>>(emptyList())

    override suspend fun discover(): Outcome<List<ToolSpec>> {
        val fresh = LinkedHashMap<ToolId, ToolSpec>()
        val freshOwner = HashMap<ToolId, ToolSource>()

        for (s in sources) {
            val got = runCatching { s.discover() }.getOrElse { t ->
                AkeLog.w(TAG, "source=${s.origin} discover failed: ${t.message}")
                emptyList()
            }
            for (spec in got) {
                if (spec.id in fresh) {
                    AkeLog.w(TAG, "duplicate toolId=${spec.id.value}, later source wins")
                }
                fresh[spec.id] = spec
                freshOwner[spec.id] = s
            }
        }

        mutex.withLock {
            for ((id, spec) in fresh) {
                owner[id] = freshOwner[id] ?: continue
                catalog[id] = if (enabledOverrides[id] == false) spec.copy(enabled = false) else spec
            }
            publishLocked()
            return Outcome.Ok(enabledLocked())
        }
    }

    override suspend fun invoke(call: ToolCall): Outcome<ToolResult> = when (call) {
        // 浏览器动作必须走 BrowserExecutorPort（架构 §3.2 第 6 环）。
        // 这里显式 Err 而不是落到某个 source，是为了让「走错路」在编译后的运行期立刻可见。
        is ToolCall.Browser -> Outcome.Err(
            AppError(code = "BROWSER_NOT_ROUTED", reasonKey = "err_browser_action_must_go_through_executor"),
        )

        is ToolCall.External -> Outcome.Ok(dispatch(call.toolId, call.args))
    }

    private suspend fun dispatch(toolId: ToolId, args: Map<String, String>): ToolResult {
        val (spec, source) = mutex.withLock { catalog[toolId] to owner[toolId] }
        return when {
            spec == null -> ToolResult.Error(ErrorCode.NOT_FOUND, "no such tool: ${toolId.value}")
            !spec.enabled -> ToolResult.Error(ErrorCode.DISABLED, null)
            source == null -> ToolResult.Error(ErrorCode.NOT_FOUND, "no source registered")
            else -> try {
                source.invoke(toolId, args)
            } catch (t: Throwable) {
                AkeLog.w(TAG, "tool=${toolId.value} threw: ${t.message}")
                val code = if (source.origin == ToolOrigin.MCP) ErrorCode.IO else ErrorCode.UNKNOWN
                ToolResult.Error(code, t.message?.take(80))
            }
        }
    }

    override suspend fun enable(toolId: ToolId, enabled: Boolean) {
        mutex.withLock {
            enabledOverrides[toolId] = enabled
            catalog[toolId]?.let { catalog[toolId] = it.copy(enabled = enabled) }
            publishLocked()
        }
        for (s in sources) runCatching { s.setEnabled(toolId, enabled) }
    }

    override fun catalog(): Flow<List<ToolSpec>> = _flow.asStateFlow()

    /** 给 AgentLoop 用：本轮真正注入 SYSTEM 段的那一份（≤ [maxEnabledTools]）。 */
    suspend fun enabledTools(): List<ToolSpec> = mutex.withLock { enabledLocked() }

    /** 给 PolicyPort 用：注册方声明的风险等级，护栏可以覆写它（§7.1）。 */
    suspend fun declaredRisk(toolId: ToolId): RiskClass? = mutex.withLock { catalog[toolId]?.riskClass }

    private fun enabledLocked(): List<ToolSpec> {
        val usable = catalog.values.filter { it.enabled && it.riskClass != RiskClass.FORBIDDEN }
        if (usable.size > maxEnabledTools) {
            AkeLog.w(TAG, "enabled tools ${usable.size} > $maxEnabledTools, truncating (§7.2)")
        }
        return usable.take(maxEnabledTools)
    }

    private fun publishLocked() {
        _flow.value = catalog.values.toList()
    }

    companion object {
        const val TAG: String = "ToolRegistry"

        /** §7.2 硬限制：MVP 同时启用 ≤ 12 个工具。 */
        const val DEFAULT_MAX_ENABLED: Int = 12
    }
}
