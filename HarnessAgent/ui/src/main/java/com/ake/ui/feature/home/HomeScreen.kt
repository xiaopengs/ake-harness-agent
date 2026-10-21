package com.ake.ui.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.contract.TaskPhase
import com.ake.core.contract.TaskStatus
import com.ake.ui.components.EmptyHint
import com.ake.ui.components.LabeledField
import com.ake.ui.components.SectionCard
import com.ake.ui.components.Tag
import com.ake.ui.model.RunState
import com.ake.ui.model.statusLabelOf
import com.ake.ui.util.collectAsStateWithLifecycle
import com.ake.ui.theme.AkeColors

/**
 * 主界面：下达任务。
 *
 * ## 为什么输入框是主角
 *
 * 端侧 Agent 的唯一入口就是一句自然语言。把它放在首屏正中、下面直接是「开始执行」，
 * 而不是藏在某个二级页面 —— 用户第一次打开这个 App 时，要能在 5 秒内理解它是干什么的。
 *
 * ## 例子不是装饰
 *
 * 端侧小模型对「任务描述的写法」极其敏感：说「帮我看看」它会一直探索到预算耗尽，
 * 说「整理前三条商品名 / 价格 / 店铺」它会直接收敛。
 * 所以示例语句写的是**能被 `SuccessCriteria` 判定的结构**，而不是随便一句文案。
 */
@Composable
public fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onOpenTask: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val goal by viewModel.goal.collectAsStateWithLifecycle()
    val startUrl by viewModel.startUrl.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    // 从任务页返回时重新拉一次：任务刚暂停/结束的落盘走 200 ms 合并队列，
    // 靠事件推比靠猜时机可靠。
    LaunchedEffect(Unit) { viewModel.refreshHistory() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {

        Text("要它做什么？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "全部在本机完成：不联网、不上云、不看你的登录态以外的数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        LabeledField(
            label = "任务目标",
            value = goal,
            onValueChange = viewModel::onGoalChange,
            placeholder = "例：在某电商站搜「降噪耳机」，按销量排序，整理前三条的商品名 / 价格 / 店铺",
            singleLine = false,
            minLines = 3,
        )
        Spacer(Modifier.height(10.dp))

        LabeledField(
            label = "起始网址（可选，不给就让它自己找）",
            value = startUrl,
            onValueChange = viewModel::onStartUrlChange,
            placeholder = "https://",
        )
        Spacer(Modifier.height(12.dp))

        val running = session.runState is RunState.Running || session.runState is RunState.Preparing
        Button(
            onClick = {
                viewModel.start()
                onOpenTask()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = goal.isNotBlank() && !running,
        ) {
            Text(if (running) "正在执行中…" else "开始执行")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (goal.isBlank()) "先写一句目标。" else "执行过程中可以随时中止，也可以让它停下来等你确认。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // ── 历史任务：来自 TaskStorePort.recent()，冷启动即可见，含未结束的
        SectionCard(
            "历史任务",
            subtitle = "最近 ${HomeViewModel.HISTORY_LIMIT} 条，含还没跑完的 —— 暂停的可以直接接着跑",
        ) {
            if (history.isEmpty()) {
                EmptyHint("还没有跑过任务。")
            } else {
                Column {
                    history.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.goalPreview, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Spacer(Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Tag(statusLabelOf(item.status), phaseColorOf(item.status, item.phase))
                                    Tag(
                                        if (item.phase == TaskPhase.TERMINAL) "共 ${item.stepsDone} 步"
                                        else "第 ${item.stepsDone + 1} 步",
                                        MaterialTheme.colorScheme.outline,
                                    )
                                    val ago = viewModel.relativeTime(item.lastActivityAt)
                                    if (ago.isNotEmpty()) {
                                        Text(
                                            ago,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (item.resumable) {
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    OutlinedButton(onClick = { viewModel.resume(item.taskId); onOpenTask() }) {
                                        Text("继续", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(onClick = { viewModel.abandon(item.taskId) }) {
                                        Text("放弃", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard("示例（可以直接照着改）") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExampleLine("搜「降噪耳机」，按销量排序，整理前三条的商品名 / 价格 / 店铺")
                ExampleLine("打开这个商品页，告诉我它的价格、月销量、是否包邮")
                ExampleLine("在这个站点里找「退货政策」那一页，把关键条款摘出来")
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenModel, modifier = Modifier.weight(1f)) { Text("模型") }
            OutlinedButton(onClick = onOpenTools, modifier = Modifier.weight(1f)) { Text("工具") }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("设置") }
        }

        if (session.runState is RunState.Running || session.runState is RunState.Suspended) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = if (session.runState is RunState.Suspended) {
                    AkeColors.PhaseSuspended.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (session.runState is RunState.Suspended) "有一个任务在等你" else "任务正在执行",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(session.goal, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    OutlinedButton(onClick = onOpenTask) { Text("去看") }
                }
            }
        }
    }
}

/** 相位 → 语义色。与任务页顶部的相位条同一套色，避免同一个状态在两个页面两种颜色。 */
@Composable
private fun phaseColorOf(status: String, phase: TaskPhase): Color = when (phase) {
    TaskPhase.ACTIVE -> AkeColors.PhaseActive
    TaskPhase.SUSPENDED -> AkeColors.PhaseSuspended
    TaskPhase.TERMINAL -> if (status == TaskStatus.SUCCEEDED) {
        AkeColors.PhaseTerminalOk
    } else {
        AkeColors.PhaseTerminalWarn
    }
}

@Composable
private fun ExampleLine(text: String) {
    Text(
        "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
