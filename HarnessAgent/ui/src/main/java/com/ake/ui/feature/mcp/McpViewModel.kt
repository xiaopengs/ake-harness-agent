package com.ake.ui.feature.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.McpConnectionPort
import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.McpServerState
import com.ake.core.ports.McpServerStorePort
import com.ake.core.ports.ToolPermissionStorePort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 工具清单里的一行：工具本身 + 它的授权开关。 */
public data class ToolRow(
    val spec: ToolSpec,
    val enabled: Boolean,
    val alwaysAllow: Boolean,
)

/** MCP 页状态。 */
public data class McpUi(
    val servers: List<McpServerConfig> = emptyList(),
    val states: Map<String, McpServerState> = emptyMap(),
    val tools: List<ToolRow> = emptyList(),
    val editing: McpServerConfig? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * MCP 服务器与工具管理页的 ViewModel。
 *
 * ## ★ 逐工具授权是安全模型的核心，不是设置项
 *
 * 一个 MCP 工具返回的内容会**直接进 prompt** —— 也就是说第三方能借此向模型注入指令。
 * 架构 §7.3 用「只连回环地址」物理切断了公网这条路，但本机上跑着的 server 仍然是外部代码。
 * 所以每个工具都要有独立的「启用」与「始终允许」开关，
 * 而且授权必须**能按条撤销**（落 Room 而不是 DataStore —— 整体重写文件会有可感知延迟）。
 *
 * 这两件事在 UI 上体现为：默认关、逐条开、随时能单条撤。
 */
@HiltViewModel
public class McpViewModel @Inject constructor(
    private val connections: McpConnectionPort,
    private val permissions: ToolPermissionStorePort,
    private val store: McpServerStorePort,
) : ViewModel() {

    private val _ui = MutableStateFlow(McpUi())
    public val ui: StateFlow<McpUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            store.load().also { list ->
                _ui.update { it.copy(servers = list) }
                // 配置装载会断开已下线的 server；不做全量 discover —— 懒连接是刻意的
                runCatching { connections.configure(list) }
            }
            refreshStates()
        }
        viewModelScope.launch {
            // 工具目录是 Flow：授权变更要立刻反映到列表
            permissions.catalog().collect { specs ->
                val rows = specs.map { s ->
                    ToolRow(
                        spec = s,
                        enabled = permissions.isEnabled(s.id),
                        alwaysAllow = permissions.isAlwaysAllow(s.id),
                    )
                }
                _ui.update { it.copy(tools = rows) }
            }
        }
    }

    public fun startAdd(): Unit = _ui.update {
        it.copy(editing = McpServerConfig(id = "srv-" + System.currentTimeMillis(), label = "", url = "http://127.0.0.1:8000/mcp"))
    }

    public fun startEdit(id: String): Unit = _ui.update {
        it.copy(editing = it.servers.firstOrNull { s -> s.id == id })
    }

    public fun cancelEdit(): Unit = _ui.update { it.copy(editing = null) }

    /** 保存（新增或更新）。非回环地址会被连接层拒绝，这里只是把警示前置到保存时。 */
    public fun save(cfg: McpServerConfig) {
        viewModelScope.launch {
            store.upsert(cfg)
            val list = store.load()
            _ui.update { it.copy(servers = list, editing = null) }
            runCatching { connections.configure(list) }
            refreshStates()
            _ui.update {
                it.copy(
                    message = if (cfg.isLoopback()) "已保存：${cfg.label}" else "已保存，但这是非回环地址 —— 默认策略下会被拒绝连接，需要显式放开。",
                )
            }
        }
    }

    public fun remove(id: String) {
        viewModelScope.launch {
            store.remove(id)
            val list = store.load()
            _ui.update { it.copy(servers = list, editing = null, message = "已删除") }
            runCatching { connections.configure(list) }
            refreshStates()
        }
    }

    /** 手动发现工具清单（懒连接：这一步才会真正握手）。 */
    public fun discover() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            val specs = runCatching { connections.discover() }.getOrDefault(emptyList())
            if (specs.isEmpty()) {
                _ui.update { it.copy(busy = false, message = "没有发现任何工具。检查 server 是否在运行、端口是否正确（默认只允许回环地址）。") }
            } else {
                permissions.upsertDiscovered(specs)
                _ui.update { it.copy(busy = false, message = "发现 ${specs.size} 个工具，默认全部为「未授权」状态。") }
            }
            refreshStates()
        }
    }

    /** 逐工具启停。 */
    public fun setToolEnabled(toolId: ToolId, enabled: Boolean) {
        viewModelScope.launch {
            permissions.setEnabled(toolId, enabled)
            runCatching { connections.setEnabled(toolId, enabled) }
        }
    }

    /** 逐工具「始终允许」。这是持久化授权，可在任何时候单条撤销。 */
    public fun grantAlways(toolId: ToolId) {
        viewModelScope.launch { permissions.grantAlwaysAllow(toolId) }
    }

    public fun revokeAlways(toolId: ToolId) {
        viewModelScope.launch { permissions.revokeAlwaysAllow(toolId) }
    }

    /** 断开全部连接。App 退后台或用户想立刻切断外部工具时调用。 */
    public fun disconnectAll() {
        viewModelScope.launch {
            runCatching { connections.shutdown() }
            _ui.update { it.copy(message = "已断开全部外部工具连接") }
            refreshStates()
        }
    }

    public fun dismissMessage(): Unit = _ui.update { it.copy(message = null) }

    private suspend fun refreshStates() {
        val states = runCatching { connections.states() }.getOrDefault(emptyList())
        _ui.update { it.copy(states = states.associateBy { s -> s.id }) }
    }
}
