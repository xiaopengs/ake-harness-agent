package com.ake.ui.feature.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.ake.core.contract.StepAction
import com.ake.ui.components.AlertBar
import com.ake.ui.components.BudgetBar
import com.ake.ui.components.FactRow
import com.ake.ui.components.MetricTile
import com.ake.ui.components.ObservationPanel
import com.ake.ui.components.SectionCard
import com.ake.ui.components.SecondaryButton
import com.ake.ui.components.StreamingText
import com.ake.ui.components.StepTimeline
import com.ake.ui.components.Tag
import com.ake.ui.components.ToolCallRow
import com.ake.ui.components.WaitingBlock
import com.ake.ui.util.collectAsStateWithLifecycle
import com.ake.ui.model.RunState
import com.ake.ui.model.TaskSessionUi
import com.ake.ui.model.copy
import com.ake.ui.model.humanAdvice
import com.ake.ui.model.humanTitle
import com.ake.ui.model.suspendReasonOf
import com.ake.ui.theme.AkeColors

/**
 * 执行过程实时可视化页。
 *
 * 布局是**单列纵向 + 底部固定操作区**：端侧任务全程竖屏单手操作，
 * 步骤流吃掉剩余高度，底部的「中止」永远在同一位置 —— 误操作时的第一反应要能立刻够到它。
 */
@Composable
public fun TaskScreen(
    viewModel: TaskViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val pending by viewModel.pendingGate.collectAsStateWithLifecycle()
    val domPayload by viewModel.domPayload.collectAsStateWithLifecycle()
    val settings by viewModel.localSettings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        GoalHeader(session = session)

        Spacer(Modifier.height(8.dp))
        MetricsRow(session = session)

        Spacer(Modifier.height(8.dp))
        ObservationPanel(
            obs = session.observation,
            onInspect = if (settings.showDomPayload) viewModel::loadDom else null,
        )

        // ★ 暂停 / 终止：两条互斥的分支，视觉上必须一眼分得开
        when (val rs = session.runState) {
            is RunState.Suspended -> {
                Spacer(Modifier.height(8.dp))
                SuspendedCard(
                    session = session,
                    canResume = viewModel.canResume(),
                    onResume = viewModel::resume,
                    onAbandon = viewModel::abandon,
                )
            }

            is RunState.Terminated -> {
                Spacer(Modifier.height(8.dp))
                TerminalCard(rs.reason.copy(), rs.outcome, rs.detail, viewModel::resume, viewModel::abandon)
            }

            is RunState.Preparing -> {
                Spacer(Modifier.height(8.dp))
                WaitingBlock(rs.step, detail = "模型首次加载是分钟级的；之后每一步约 4.5 秒。")
            }

            else -> Unit
        }

        Spacer(Modifier.height(8.dp))

        // ── 步骤流吃掉剩余高度
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("步骤流", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                StepTimeline(
                    steps = session.steps,
                    runningSeq = (session.runState as? RunState.Running)?.stepSeq,
                    showModelRaw = settings.showDomPayload,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (session.toolCalls.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionCard("工具调用", subtitle = "MCP 工具与本地内置工具在同一份清单里，授权逐条管理") {
                Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                    session.toolCalls.forEach { ToolCallRow(it) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("返回", onClick = onBack, modifier = Modifier.weight(1f))
            when (session.runState) {
                is RunState.Idle, is RunState.Terminated -> Unit
                else -> {
                    androidx.compose.material3.Button(
                        onClick = viewModel::abort,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = AkeColors.RiskForbidden,
                        ),
                    ) { Text("中止") }
                }
            }
        }
    }

    // ── DOM 原文（Agent 真正看到的那一页）
    domPayload?.let { payload ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissDom,
            title = { Text("Agent 眼里的这一页") },
            text = {
                Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        payload.ifBlank { "（这一步没有留下 DOM 快照 —— 可能是被丢弃了）" },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            confirmButton = {
                SecondaryButton("关闭", onClick = viewModel::dismissDom)
            },
        )
    }

    // ── 安全确认闸门：Loop 此刻正挂起等待这里的裁决
    pending?.let { gate ->
        val isToolCall = gate.action is StepAction.CallTool
        ConfirmGateDialog(
            pending = gate,
            targetHost = viewModel.session.value.observation?.host.orEmpty(),
            onAllowOnce = viewModel::allowOnce,
            onAllowForTask = viewModel::allowForThisTask,
            onAllowAlways = if (isToolCall) viewModel::allowAlways else null,
            onAmendNoSubmit = if (gate.action is StepAction.Type && gate.action.submit) viewModel::amendNoSubmit else null,
            onReject = viewModel::reject,
            onAbort = viewModel::abortGate,
        )
    }
}

@Composable
private fun GoalHeader(session: TaskSessionUi) {
    Column {
        Text(
            session.goal.ifBlank { "（未开始）" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val (label, color) = when (session.runState) {
                is RunState.Suspended -> "已暂停（可恢复）" to AkeColors.PhaseSuspended
                is RunState.Terminated -> "已结束" to AkeColors.PhaseTerminalBad
                is RunState.Running -> "执行中" to AkeColors.PhaseActive
                is RunState.Preparing -> "准备中" to AkeColors.PhaseActive
                RunState.Idle -> "空闲" to MaterialTheme.colorScheme.outline
            }
            Tag(label, color, filled = session.runState is RunState.Suspended)
            Text("状态 ${session.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetricsRow(session: TaskSessionUi) {
    val m = session.metrics
    SectionCard("实时指标", subtitle = "档位 ${m.memoryTier} · 上下文窗口 ${m.contextWindowTokens}") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricTile("步数", "${m.stepsDone}/${m.maxSteps}")
            MetricTile("prefill", "${m.prefillTokens}")
            MetricTile("decode", "${m.decodeTokens}")
            MetricTile("用时", formatDuration(m.elapsedMs))
        }
        Spacer(Modifier.height(8.dp))
        BudgetBar(m.budgetRemainingPct)
        Spacer(Modifier.height(6.dp))
        Text(
            "上下文占用 ${m.contextUsedPct}%（本步 ${m.promptTokensLastStep} token）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ★ 暂停卡片。
 *
 * 刻意用琥珀色 + 「可恢复」字眼 + 两个明确出口（继续 / 放弃）：
 * 「暂停」与「已结束」在语义上是两件事，在界面上就不能共用一张卡片。
 */
@Composable
private fun SuspendedCard(
    session: TaskSessionUi,
    canResume: Boolean,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
) {
    val suspension = (session.runState as? RunState.Suspended)?.suspension ?: return
    val reason = suspendReasonOf(suspension.reasonKey)

    Surface(
        color = AkeColors.PhaseSuspended.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("已暂停 · 可恢复", AkeColors.PhaseSuspended, filled = true)
                Spacer(Modifier.width(6.dp))
                Text("停在第 ${suspension.stepSeq + 1} 步", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                reason?.humanTitle() ?: "任务已暂停",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(reason?.humanAdvice() ?: "等条件恢复后可以从断点继续。", style = MaterialTheme.typography.bodySmall)
            if (!suspension.checkpointSaved) {
                Spacer(Modifier.height(6.dp))
                AlertBar("这次暂停没有存住断点，无法继续 —— 只能重跑。", AkeColors.RiskForbidden)
            }
            if (suspension.needsUserAction) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "它在等你做一个决定：弹窗关掉之前，主循环会一直停在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = onResume,
                    enabled = canResume && !suspension.needsUserAction,
                    modifier = Modifier.weight(1f),
                ) { Text("继续") }
                SecondaryButton("放弃", onClick = onAbandon, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "继续 = 从断点接上（已做完的步骤与已消耗的预算都会带回来），不是从头重跑。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 终态卡片。文案全部来自 `TerminalCopy`，含「结果不可信」的硬警示。 */
@Composable
private fun TerminalCard(
    copy: com.ake.ui.model.TerminalCopy,
    outcome: com.ake.ui.model.TaskOutcomeUi?,
    detail: String?,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
) {
    val color = when (copy.trust) {
        com.ake.ui.model.TerminalCopy.Trust.TRUSTED -> AkeColors.PhaseTerminalOk
        com.ake.ui.model.TerminalCopy.Trust.UNTRUSTED -> AkeColors.RiskForbidden
        com.ake.ui.model.TerminalCopy.Trust.NONE -> AkeColors.PhaseTerminalWarn
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(copy.title, color, filled = true)
                Spacer(Modifier.width(6.dp))
                Text("已结束", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(copy.what, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text("建议：${copy.nextStep}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (copy.trust == com.ake.ui.model.TerminalCopy.Trust.UNTRUSTED) {
                Spacer(Modifier.height(8.dp))
                AlertBar("★ 这次的结果不可信，不要直接采用。")
            }
            outcome?.answer?.let { answer ->
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("产出", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        com.ake.ui.components.StreamingText(answer)
                        if (outcome.evidenceRefs.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "溯源：${outcome.evidenceRefs.joinToString(" / ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            detail?.let {
                Spacer(Modifier.height(6.dp))
                Text("细节：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (copy.resumableHint) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("从断点继续", onClick = onResume, modifier = Modifier.weight(1f))
                    SecondaryButton("放弃断点", onClick = onAbandon, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}
