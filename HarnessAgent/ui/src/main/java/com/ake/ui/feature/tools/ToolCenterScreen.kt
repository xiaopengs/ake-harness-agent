package com.ake.ui.feature.tools

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.contract.ToolSpec
import com.ake.ui.components.NoticeBar
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.Tag
import com.ake.ui.components.riskColorOf
import com.ake.ui.components.riskLabelOf
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 工具中心：它有哪些能力，哪些会动你的东西。
 *
 * ## 文案口径
 *
 * 用户不关心 `ToolSpec` / `jsonSchema` / `origin`。这一页只回答三件事：
 * **叫什么、能干什么、dangerous 到什么程度**。所以 `description` 被截到两行，
 * schema 完全不展示 —— 它是给模型看的，不是给用户看的。
 *
 * ## 为什么风险标签用「会改变页面 / 敏感操作 / 域外副作用」这种人话
 *
 * `PAGE_MUTATING` 这种词对用户等于没说。`riskLabelOf` 已经把五个枚举映射成中文，
 * 这里直接复用，**不在页面里另写一套**（同一个风险两种说法是歧义的源头）。
 */
@Composable
public fun ToolCenterScreen(
    viewModel: ToolCenterViewModel = hiltViewModel(),
    onOpenMcp: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("工具", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "它干活时能用上的能力。带颜色标记的会改动东西，动手前会先问你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.loaded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "共 ${ui.total} 个 · 已启用 ${ui.enabledCount} 个" +
                            if (ui.needsConfirm > 0) " · ${ui.needsConfirm} 个会改动东西" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!ui.loaded) {
            items(4) { SkeletonLine(Modifier.fillMaxWidth(), height = 64) }
            return@LazyColumn
        }

        if (ui.local.isEmpty() && ui.mcp.isEmpty()) {
            item {
                NoticeBar("还没有可用的工具。内置工具会随任务类型自动出现，MCP 工具要在「连接服务」里添加。")
            }
        }

        if (ui.local.isNotEmpty()) {
            item { GroupTitle("本机内置", "随应用提供，不经网络") }
            items(ui.local.size) { i -> ToolRow(ui.local[i]) }
        }

        if (ui.mcp.isNotEmpty()) {
            item { GroupTitle("来自 MCP 服务", "由你连接的外部服务提供") }
            items(ui.mcp.size) { i -> ToolRow(ui.mcp[i]) }
        }

        item {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onOpenMcp, modifier = Modifier.fillMaxWidth()) {
                Text("连接与管理 MCP 服务")
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "工具是否可用由安全策略决定。即使出现在这里，动手改东西之前也会先征求你的同意。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolRow(spec: ToolSpec) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                if (!spec.enabled) {
                    Tag("已停用", MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                spec.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        Tag(riskLabelOf(spec.riskClass.name), riskColorOf(spec.riskClass.name))
    }
}
