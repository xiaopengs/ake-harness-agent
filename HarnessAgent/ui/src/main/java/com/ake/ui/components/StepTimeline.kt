package com.ake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ake.ui.model.ObservationUi
import com.ake.ui.model.StepPhase
import com.ake.ui.model.StepUi
import com.ake.ui.model.ToolCallUi
import com.ake.ui.model.denialCopyOf

/**
 * 步骤流（Step Timeline）—— 本 App 的灵魂。
 *
 * ## 为什么每一步都要把「模型想了什么」摊开
 *
 * 端侧 Agent 的价值不在于它做完了，而在于**做错了你能一眼看出错在哪一步**。
 * 一次 10 步失败的任务，要能立刻分辨是「第 4 步 DOM 裁剪丢了元素」还是「第 6 步护栏误拒」——
 * 做不到这一点的 Agent 产物，用户第二次就不会打开。
 *
 * ## 时间线为什么是「倒序最新在上」之外还要自动滚到底
 *
 * 用户在看的是「现在在发生什么」，而不是历史；但历史必须可回溯。
 * 所以默认吸底，用户往上翻时停止自动滚动（不抢滚动条）。
 */
@Composable
public fun StepTimeline(
    steps: List<StepUi>,
    modifier: Modifier = Modifier,
    runningSeq: Int? = null,
    showModelRaw: Boolean = true,
) {
    if (steps.isEmpty()) {
        WaitingBlock(
            text = "正在读取页面…",
            detail = "端侧单步约 4.5 秒：读页面 → 模型推理 → 安全判定 → 执行。第一步还要等页面稳定。",
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState()
    val lastSeq = steps.lastOrNull()?.seq
    LaunchedEffect(lastSeq) {
        if (lastSeq != null) listState.animateScrollToItem(index = (steps.size - 1).coerceAtLeast(0))
    }

    LazyColumn(state = listState, modifier = modifier) {
        items(steps, key = { it.seq }) { step ->
            StepCard(step = step, isRunning = step.seq == runningSeq, showModelRaw = showModelRaw)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepCard(step: StepUi, isRunning: Boolean, showModelRaw: Boolean) {
    val accent = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isRunning) 3.dp else 0.dp,
        shadowElevation = if (isRunning) 2.dp else 0.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            // 左侧序号轴
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${step.seq + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (!step.finished) {
                    Text(step.phase.label, style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }

            Spacer(Modifier.size(10.dp))

            Column(Modifier.weight(1f)) {
                // ── 观察
                step.observation?.let { obs -> ObservationLine(obs) }

                // ── 模型输出
                if (step.actionTitle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("决定：${step.actionTitle}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (!step.actionDetail.isNullOrBlank()) {
                        Text(
                            step.actionDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (isRunning) {
                    Spacer(Modifier.height(6.dp))
                    SkeletonLine(Modifier.fillMaxWidth(0.8f), height = 12)
                    Text(
                        "模型推理中…（端侧约 2–6 秒，这段时间它在逐字生成，不支持打断）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 护栏
                if (step.guardDecision != null) {
                    Spacer(Modifier.height(6.dp))
                    val denied = step.guardDecision == "DENIED"
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tag(
                            text = when (step.guardDecision) {
                                "ALLOW" -> "安全策略：放行"
                                "REQUIRES_CONFIRM" -> "安全策略：需要你确认"
                                else -> "安全策略：已拒绝"
                            },
                            color = if (denied) riskColorOf("FORBIDDEN") else riskColorOf(step.riskClass),
                        )
                        step.riskClass?.let { Tag(riskLabelOf(it), riskColorOf(it)) }
                    }
                    if (denied) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            denialCopyOf(step.guardReasonKey),
                            style = MaterialTheme.typography.bodySmall,
                            color = riskColorOf("FORBIDDEN"),
                        )
                    }
                }

                // ── 异常
                step.repair?.let { r ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "模型输出读不懂，已要求它重来（第 ${r.attempt} 次，原因 ${r.failureCode}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                step.perceptionFaultCode?.let { c ->
                    Spacer(Modifier.height(4.dp))
                    Text("页面读取异常：$c", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                // ── 结果
                if (step.resultText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("结果：${step.resultText}", style = MaterialTheme.typography.bodySmall)
                }

                // ── token 与耗时
                if (step.finished) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tokenLineOf(step),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (showModelRaw && step.modelRaw.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            step.modelRaw,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObservationLine(obs: ObservationUi) {
    Column {
        Text(
            obs.host.ifBlank { "（未知域名）" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        obs.url?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "可交互元素 ${obs.candidateCount} 个 · 结构指纹 ${obs.pageDigest.take(10)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun tokenLineOf(s: StepUi): String = buildString {
    append("prefill ${s.prefillTokens} · decode ${s.decodeTokens}")
    if (s.promptTokens > 0) append(" · 本步上下文 ${s.promptTokens}")
    s.durationMs?.let { append(" · 耗时 ${it}ms") }
}

/** 当前观察面板（步骤流之外的独立卡片）。 */
@Composable
public fun ObservationPanel(
    obs: ObservationUi?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "当前页面观察", subtitle = "这是 Agent 现在看到的东西，不是浏览器截图", modifier = modifier) {
        if (obs == null) {
            WaitingBlock("还没有读到页面", detail = "首次感知要等页面稳定（最多 3 秒），之后每步都会重读一次。")
        } else {
            FactRow("域名", obs.host.ifBlank { "—" })
            FactRow("地址", obs.url ?: "—")
            FactRow("可交互元素", "${obs.candidateCount} 个")
            FactRow("结构指纹", obs.pageDigest.take(24).ifBlank { "—" })
            Spacer(Modifier.height(8.dp))
            if (onInspect != null) {
                SecondaryButton("看它眼里这一页长什么样", onClick = { onInspect(obs.observationId) })
            }
        }
    }
}

/** 工具调用展示：MCP 与本地内置工具同一形状（架构 §7.1）。 */
@Composable
public fun ToolCallRow(call: ToolCallUi, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(call.origin, if (call.origin == "MCP") riskColorOf("EXTERNAL_SIDE_EFFECT") else riskColorOf("READ_ONLY"))
            Spacer(Modifier.size(6.dp))
            Text(call.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (call.durationMs != null) {
                Spacer(Modifier.size(6.dp))
                Text("${call.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("入参：${call.argsSummary}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (call.resultSummary != null) {
            Text(
                "出参：${call.resultSummary}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
