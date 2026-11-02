package com.ake.ui.feature.memory

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ake.core.ports.MemoryEntry
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.Tag
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 记忆中心：它自己记下的东西，全在这里，可以看、可以删。
 *
 * ## 为什么这一页必须存在（不是"顺便做个管理页"）
 *
 * 这个应用的卖点是"数据不出设备"。如果模型偷偷记了一堆东西而用户看不到，
 * 那句承诺就只是文案。**可见 + 可删**是这条承诺唯一的验证方式 ——
 * 所以这一页是 P0，不是 P1。
 *
 * ## 青绿在这里的用法
 *
 * 青绿在本项目的语义是"本机处理 / 数据不出机"。记忆卡的来源标记用它，
 * 是提醒用户：这条不是从云端同步下来的。
 */
@Composable
public fun MemoryListScreen(
    viewModel: MemoryListViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<MemoryEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("记忆", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                "它替你记住的事，只存在这台手机上。看不顺眼的可以直接删。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜一搜记过什么") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }

        when {
            !ui.loaded -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { SkeletonLine(Modifier.fillMaxWidth(), height = 64) }
            }

            ui.entries.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (ui.total == 0) "还没有记忆" else "没搜到",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ui.total == 0) {
                        "它跑任务时会自动记下有用的东西，比如你常用的站点和你偏好的说法。"
                    } else {
                        "换个词试试，或者清空搜索框。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.entries.size) { i ->
                    val entry = ui.entries[i]
                    MemoryCardRow(
                        entry = entry,
                        timeText = viewModel.relativeTime(entry.updatedAtMillis),
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这条记忆？") },
            text = {
                Text(
                    "「${target.title}」会被直接从本机删除，删了就找不回来。" +
                        "它之后不会再拿这条来回答你。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("留着") }
            },
        )
    }
}

@Composable
private fun MemoryCardRow(
    entry: MemoryEntry,
    timeText: String,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (entry.pinned) {
                Spacer(Modifier.width(6.dp))
                Tag("常用", MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onDelete) { Text("删除") }
        }
        if (entry.preview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(entry.scopeHost?.takeIf { it.isNotBlank() } ?: "全局", MaterialTheme.colorScheme.secondary)
            if (timeText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.useCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "用过 ${entry.useCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
