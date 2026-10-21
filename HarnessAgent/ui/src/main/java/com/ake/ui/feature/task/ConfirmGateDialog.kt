package com.ake.ui.feature.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ake.core.contract.StepAction
import com.ake.ui.components.Tag
import com.ake.ui.components.riskColorOf
import com.ake.ui.components.riskLabelOf
import com.ake.ui.model.confirmCopyOf
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.theme.AkeColors
import kotlinx.coroutines.delay

/**
 * 人机确认闸门。
 *
 * ## 这是本 App 唯一的安全关键交互，三条纪律
 *
 *  1. **默认动作必须是「少做」而不是「多做」。** 弹窗出现的瞬间焦点在「拒绝」上，
 *     直接按返回键 = 拒绝（见 `onDismissRequest`）。
 *  2. **「允许」要过一段冷静期才可点。** 端侧 Agent 的确认框出现在用户正盯着屏幕看的时候，
 *     最容易发生的误操作是「上一步操作的余势」顺势点下去 —— 1.2 秒的冷却能拦掉这一类。
 *  3. **四个出口必须都在，且「中止任务」无条件可用。** 用户想停的时候不该先被迫做一个选择。
 *
 * ## 文案来自护栏，不由 UI 生成
 *
 * 中间的说明文字取自 `PolicyEngine` 给出的 `reasonKey`（`confirmCopyOf`），
 * 风险等级取自 `SensitiveActionPolicy.classify` —— UI **不判断风险**，只负责把它讲清楚。
 */
@Composable
public fun ConfirmGateDialog(
    pending: UiHumanGate.PendingGate,
    targetHost: String,
    onAllowOnce: () -> Unit,
    onAllowForTask: () -> Unit,
    onAllowAlways: (() -> Unit)?,
    onAmendNoSubmit: (() -> Unit)?,
    onReject: () -> Unit,
    onAbort: () -> Unit,
) {
    val verdict = pending.verdict
    val reasonText = remember(verdict.reasonKey) { confirmCopyOf(verdict.reasonKey, verdict.args) }
    val risk = pending.riskClass

    // ★ 冷却期：允许类按钮 1.2 秒后才可点，防止「顺势点下去」
    var cooldown by remember(verdict.reasonKey, pending.action) { mutableIntStateOf(COOLDOWN_MS) }
    LaunchedEffect(verdict.reasonKey, pending.action) {
        cooldown = COOLDOWN_MS
        while (cooldown > 0) {
            delay(200L)
            cooldown = (cooldown - 200).coerceAtLeast(0)
        }
    }
    val allowEnabled = cooldown == 0

    AlertDialog(
        onDismissRequest = onReject,   // 返回键 / 点外部 = 拒绝，绝不默认放行
        title = {
            Column {
                Text("需要你确认", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(riskLabelOf(risk), riskColorOf(risk), filled = risk == "SENSITIVE" || risk == "FORBIDDEN")
                    if (targetHost.isNotBlank()) Tag(targetHost, MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("将要执行的动作", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(describeAction(pending.action), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(reasonText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text(
                    "这个动作是在你的登录态下执行的，产生的后果与你自己操作网页完全相同。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = onAllowOnce,
                    enabled = allowEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (allowEnabled) "允许这一次" else "请确认… ${cooldown}ms")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onAllowForTask,
                    enabled = allowEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("本次任务内不再问这类问题")
                }
                if (onAllowAlways != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onAllowAlways, enabled = allowEnabled, modifier = Modifier.fillMaxWidth()) {
                        Text("始终允许这个工具（可在工具页单条撤销）")
                    }
                }
                if (onAmendNoSubmit != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onAmendNoSubmit, enabled = allowEnabled, modifier = Modifier.fillMaxWidth()) {
                        Text("只输入，不提交")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onReject,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                ) {
                    Text("拒绝这一步（让它换条路）")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
                    Text("中止整个任务", color = AkeColors.RiskForbidden, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = null,
    )
}

private fun describeAction(a: StepAction): String = when (a) {
    is StepAction.Click -> "点击页面元素 [${a.id}]"
    is StepAction.Type -> "向 [${a.id}] 输入「${a.text.take(40)}」${if (a.submit) " 并回车提交" else ""}"
    is StepAction.Select -> "在 [${a.id}] 选择「${a.value}」"
    is StepAction.Scroll -> "滚动 ${a.dir} ${a.px}px"
    is StepAction.Navigate -> "跳转到 ${a.url}"
    is StepAction.Extract -> "抽取数据（${a.selectorHint}，最多 ${a.limit} 条）"
    is StepAction.CallTool -> "调用外部工具 ${a.toolId.value}"
    is StepAction.AskHuman -> "向你提问：${a.question}"
    is StepAction.Finish -> "报告任务完成"
}

private const val COOLDOWN_MS: Int = 1_200
