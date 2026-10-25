package com.ake.ui.feature.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.ports.AgentProfileSummary
import com.ake.ui.R
import com.ake.ui.components.AgentAvatar
import com.ake.ui.components.AgentTrustTags
import com.ake.ui.components.AlertBar
import com.ake.ui.components.EmptyHint
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.TinyButton
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * Agent 列表（也是"新建对话时挑人"的那个列表）。
 *
 * ## 为什么一个页面承担两种身份
 *
 * 挑人和管理 Agent 看到的是同一批对象、同一个排序（最近用过在前）。
 * 分成两页会让"我想跟那个新角色聊聊"变成一次三级跳：对话 → 新建 → Agent 列表。
 * 这里用 `pickMode` 切换的只有两件事：点击行为（进详情 vs 开对话）与
 * 是否显示删除按钮 —— 布局与数据完全一致。
 */
@Composable
public fun AgentListScreen(
    onOpenAgent: (String) -> Unit,
    onPickAgent: (String) -> Unit,
    onCreateBlank: () -> Unit,
    onCreateFromTemplate: () -> Unit,
    onOpenTemplateMarket: () -> Unit,
    modifier: Modifier = Modifier,
    pickMode: Boolean = false,
    viewModel: AgentListViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp)) {
            Text(
                text = stringResource(if (pickMode) R.string.chat_list_pick_agent_title else R.string.agent_list_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(
                    if (pickMode) R.string.chat_list_pick_agent_subtitle else R.string.agent_list_subtitle,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ui.errorText?.let { err ->
            Column(Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) {
                AlertBar(stringResource(R.string.agent_list_error, err))
            }
        }

        if (!pickMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCreateFromTemplate, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_list_from_template), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onCreateBlank, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_list_blank), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                TinyButton(
                    text = stringResource(R.string.agent_list_template_market),
                    onClick = onOpenTemplateMarket,
                )
            }
        }

        when {
            ui.loading -> Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { SkeletonLine(Modifier.fillMaxWidth().height(52.dp)) }
            }

            ui.items.isEmpty() -> EmptyHint(stringResource(R.string.agent_list_empty))

            else -> LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 20.dp),
            ) {
                items(items = ui.items, key = { it.id }) { item ->
                    AgentRow(
                        item = item,
                        pickMode = pickMode,
                        onClick = { if (pickMode) onPickAgent(item.agentId) else onOpenAgent(item.id) },
                        onDelete = { viewModel.askDelete(item) },
                    )
                }
            }
        }
    }

    ui.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.agent_delete_title, target.name)) },
            text = { Text(stringResource(R.string.agent_delete_body)) },
            confirmButton = {
                TinyButton(
                    text = stringResource(R.string.common_delete),
                    onClick = viewModel::confirmDelete,
                )
            },
            dismissButton = {
                TinyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = viewModel::dismissDelete,
                )
            },
        )
    }
}

@Composable
private fun AgentRow(
    item: AgentProfileSummary,
    pickMode: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AgentAvatar(name = item.name, size = 44.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            AgentTrustTags(
                origin = item.origin,
                trustTier = item.trustTier,
                scanVerdict = item.scanVerdict,
            )
        }
        if (!pickMode) {
            Spacer(Modifier.width(4.dp))
            TinyButton(text = stringResource(R.string.common_delete), onClick = onDelete)
        }
    }
}
