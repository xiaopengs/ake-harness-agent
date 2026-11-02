package com.ake.ui.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import com.ake.core.contract.TaskPhase
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.Tag
import com.ake.ui.model.TaskRow
import com.ake.ui.model.phaseColorOf
import com.ake.ui.model.statusLabelOf
import com.ake.ui.model.terminalCopyOf
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 任务中心：所有任务的聚合列表。
 *
 * ## 它和「首页历史任务」的区别
 *
 * 首页那份是**往回找**（最近 20 条，含可继续的），任务中心是**翻**（最多 200 条，可筛选）。
 * 两个页面共用 [TaskRow] 与同一套状态色，所以同一个任务在两处的说法必然一致。
 *
 * ## 为什么筛选 chip 上带数量
 *
 * 空列表最伤人的地方不是"没有"，而是"点进去才发现没有"。数量直接写在 chip 上，
 * 用户不用点就知道"没跑完"里有没有东西。
 */
@Composable
public fun TaskListScreen(
    viewModel: TaskListViewModel = hiltViewModel(),
    onOpenTask: () -> Unit,
    onGoHome: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("任务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(
                "每一次执行都在这里留底，跑完的、停下的、没跑完的都在。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (f in TaskFilter.entries) {
                    val count = ui.counts[f] ?: 0
                    FilterChip(
                        selected = ui.filter == f,
                        onClick = { viewModel.select(f) },
                        label = { Text(if (count > 0) "${f.label} $count" else f.label) },
                    )
                }
            }
        }

        when {
            !ui.loaded -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { SkeletonLine(Modifier.fillMaxWidth(), height = 56) }
            }

            ui.rows.isEmpty() -> EmptyTasks(ui.filter, onGoHome)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.rows, key = { it.taskId }) { row ->
                    TaskCard(
                        row = row,
                        timeText = viewModel.relativeTime(row.lastActivityAt),
                        onOpen = onOpenTask,
                        onResume = {
                            viewModel.resume(row.taskId)
                            onOpenTask()
                        },
                        onAbandon = { viewModel.abandon(row.taskId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    row: TaskRow,
    timeText: String,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                row.goalPreview,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Tag(statusLabelOf(row.status), phaseColorOf(row.status, row.phase))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (row.phase == TaskPhase.TERMINAL) "共 ${row.stepsDone} 步" else "第 ${row.stepsDone + 1} 步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (timeText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 终态原因的人话结论。孤儿回收（进程被系统杀掉）只有走这里才会被看见 ——
        // 它落库是 ABORTED，与「用户自己按了停止」无法区分。
        val endCopy = terminalCopyOf(row.terminalReason)
        if (row.phase == TaskPhase.TERMINAL && endCopy != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                endCopy.title,
                style = MaterialTheme.typography.labelSmall,
                color = if (endCopy.trust == com.ake.ui.model.TerminalCopy.Trust.UNTRUSTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (row.resumable) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onResume) { Text("继续") }
                OutlinedButton(onClick = onAbandon) { Text("放弃") }
            }
        }
    }
}

@Composable
private fun EmptyTasks(filter: TaskFilter, onGoHome: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (filter) {
                TaskFilter.ALL -> "还没有任务"
                TaskFilter.ACTIVE -> "没有正在进行的任务"
                TaskFilter.DONE -> "还没有跑完的任务"
                TaskFilter.UNFINISHED -> "没有没跑完的任务，挺好"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (filter == TaskFilter.ALL) "回首页写一句要它做的事，它会在本机开始执行。" else "换个筛选看看。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filter == TaskFilter.ALL) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onGoHome) { Text("去首页") }
        }
    }
}
