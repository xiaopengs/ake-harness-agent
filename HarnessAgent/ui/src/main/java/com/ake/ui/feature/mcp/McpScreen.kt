package com.ake.ui.feature.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.McpServerState
import com.ake.core.ports.TransportKind
import com.ake.ui.components.AlertBar
import com.ake.ui.components.EmptyHint
import com.ake.ui.components.FactRow
import com.ake.ui.components.LabeledField
import com.ake.ui.components.SectionCard
import com.ake.ui.components.SecondaryButton
import com.ake.ui.components.Tag
import com.ake.ui.components.TinyButton
import com.ake.ui.components.riskColorOf
import com.ake.ui.components.riskLabelOf
import com.ake.ui.theme.AkeColors
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * MCP 服务器与工具管理页。
 *
 * ## ★ 这一页是安全边界的可见部分
 *
 * 一个 MCP 工具返回的内容会**直接进 prompt** —— 第三方因此能向模型注入指令。
 * 架构 §7.3 用「只连回环地址」把公网这条路物理切断，但本机上跑的 server 依然是外部代码。
 * 所以这里的默认策略是：**工具默认停用、授权逐条给、随时能单条撤。**
 *
 * 连接状态里的 `DEAD` 是刻意的：连不上的 server 达到重连上限后**不再自动重试** ——
 * 移动端上无限退避重试是电量问题，不是可靠性问题。
 */
@Composable
public fun McpScreen(viewModel: McpViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var headerDraft by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("工具与连接", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "MVP 只连接本机（127.0.0.1 / localhost）的 MCP server。允许连公网等于让第三方代表登录态的你执行动作 —— 这条回路我们不打开。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        ui.message?.let {
            AlertBar(it, AkeColors.PhaseActive)
            Spacer(Modifier.height(8.dp))
            TinyButton("知道了", onClick = viewModel::dismissMessage)
            Spacer(Modifier.height(8.dp))
        }

        SectionCard("服务器", subtitle = "添加后要点「发现工具」才会真正握手（懒连接：启动时不连，省电）") {
            if (ui.servers.isEmpty()) {
                EmptyHint("还没有添加服务器。")
            } else {
                ui.servers.forEach { cfg ->
                    ServerRow(cfg, ui.states[cfg.id], viewModel)
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::startAdd, modifier = Modifier.weight(1f)) { Text("添加服务器") }
                OutlinedButton(onClick = viewModel::discover, modifier = Modifier.weight(1f), enabled = !ui.busy) {
                    Text(if (ui.busy) "发现中…" else "发现工具")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(
            "工具清单与授权",
            subtitle = "逐个开关：默认停用，授权可单条撤销（授权落 Room，不落 DataStore）",
        ) {
            if (ui.tools.isEmpty()) {
                EmptyHint("还没有发现任何工具。先添加一台 server，再点「发现工具」。")
            } else {
                ui.tools.forEach { row -> ToolRowView(row, viewModel) }
            }
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton("断开全部连接", onClick = viewModel::disconnectAll, modifier = Modifier.fillMaxWidth())
    }

    ui.editing?.let { cfg ->
        ServerEditDialog(
            initial = cfg,
            onCancel = viewModel::cancelEdit,
            onSave = viewModel::save,
        )
    }
}

@Composable
private fun ServerRow(cfg: McpServerConfig, state: McpServerState?, vm: McpViewModel) {
    val phase = state?.phase ?: McpServerState.Phase.DISCONNECTED
    val (label, color) = when (phase) {
        McpServerState.Phase.DISCONNECTED -> "未连接" to MaterialTheme.colorScheme.outline
        McpServerState.Phase.CONNECTING -> "连接中" to AkeColors.PhaseActive
        McpServerState.Phase.READY -> "已连接" to AkeColors.PhaseTerminalOk
        McpServerState.Phase.DEGRADED -> "降级" to AkeColors.PhaseTerminalWarn
        McpServerState.Phase.DEAD -> "已放弃重连" to AkeColors.RiskForbidden
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cfg.label.ifBlank { cfg.id }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(cfg.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Tag(label, color, filled = phase == McpServerState.Phase.READY)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用", style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = cfg.enabled,
                onCheckedChange = { on -> vm.save(cfg.copy(enabled = on)) },
            )
            Spacer(Modifier.height(4.dp))
            TinyButton("编辑", onClick = { vm.startEdit(cfg.id) })
            TinyButton("删除", onClick = { vm.remove(cfg.id) })
        }
        if (!cfg.isLoopback()) {
            Spacer(Modifier.height(4.dp))
            AlertBar("这是非回环地址。默认策略下连接会被直接拒绝（状态显示「已放弃重连」）。", AkeColors.RiskSensitive)
        }
        state?.lastError?.let {
            Text("最近错误：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        state?.toolCount?.let {
            Text("工具 $it 个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToolRowView(row: ToolRow, vm: McpViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.spec.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    row.spec.description.take(120),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Tag(row.spec.origin.name, if (row.spec.origin.name == "MCP") AkeColors.RiskExternal else AkeColors.RiskReadOnly)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(riskLabelOf(row.spec.riskClass.name), riskColorOf(row.spec.riskClass.name))
            Spacer(Modifier.height(4.dp))
            Text("启用", style = MaterialTheme.typography.labelSmall)
            Switch(checked = row.enabled, onCheckedChange = { vm.setToolEnabled(row.spec.id, it) })
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("始终允许（不再每次问）", style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = row.alwaysAllow,
                onCheckedChange = { on ->
                    if (on) vm.grantAlways(row.spec.id) else vm.revokeAlways(row.spec.id)
                },
            )
        }
    }
}

/**
 * 服务器编辑弹窗。
 *
 * Header 一栏明确写「不要放长期凭据」：`McpServerConfig.headers` 是静态 Header，
 * 每次请求都会带上，而且是**明文落盘**的。
 */
@Composable
private fun ServerEditDialog(
    initial: McpServerConfig,
    onCancel: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var url by remember(initial.id) { mutableStateOf(initial.url) }
    var headers by remember(initial.id) {
        mutableStateOf(initial.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" })
    }
    var transport by remember(initial.id) { mutableStateOf(initial.transport) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial.label.isBlank()) "添加服务器" else "编辑服务器") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                LabeledField("名称", label, { label = it }, placeholder = "例如：本机文件工具")
                Spacer(Modifier.height(8.dp))
                LabeledField("地址", url, { url = it }, placeholder = "http://127.0.0.1:8000/mcp")
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "静态 Header（每行一条，key: value）",
                    headers,
                    { headers = it },
                    placeholder = "Authorization: Bearer xxx",
                    singleLine = false,
                    minLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "不要在这里放长期凭据：它会随每次请求发送，并且是明文存下来的。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("传输方式", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    TinyButton(
                        if (transport == TransportKind.STREAMABLE_HTTP) "Streamable HTTP ✓" else "Streamable HTTP",
                        onClick = { transport = TransportKind.STREAMABLE_HTTP },
                    )
                    TinyButton(
                        if (transport == TransportKind.SSE) "SSE ✓" else "SSE",
                        onClick = { transport = TransportKind.SSE },
                    )
                }
            }
        },
        confirmButton = {
            SecondaryButton(
                "保存",
                onClick = {
                    val map = headers.lineSequence()
                        .mapNotNull { line ->
                            val idx = line.indexOf(':')
                            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                        }
                        .toMap()
                    onSave(
                        McpServerConfig(
                            id = initial.id,
                            label = label.trim(),
                            url = url.trim(),
                            headers = map,
                            enabled = initial.enabled,
                            transport = transport,
                        ),
                    )
                },
            )
        },
        dismissButton = { TinyButton("取消", onClick = onCancel) },
    )
}
