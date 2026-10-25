package com.ake.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ake.core.ports.MessageStatus
import com.ake.ui.R
import com.ake.ui.model.SkillCardUi
import com.ake.ui.theme.AkeColors

/**
 * 技能调用卡片（可折叠）。
 *
 * ## 为什么绝对不能当普通文本渲染
 *
 * 一次浏览器任务会在对话里跑 10–40 秒、烧掉大半预算，而它在消息流里
 * 只占一行。如果渲染成一段正文，用户读到的是一串散文，看不到"走到第几步"，
 * 也就无法判断"再等 5 秒"还是"这玩意儿卡住了"。所以：
 *
 *  - **默认折叠**：它占用的高度必须和它的信息密度匹配，否则长对话会被卡片淹掉；
 *  - **折叠时也要显示进度**：`正在操作「浏览器」第 3 步` 这一行是折叠态的唯一内容，
 *    它回答了"现在在干嘛"这个唯一的问题；
 *  - **展开才给参数与结果**：这两样只在排查时有用。
 *
 * ## 为什么用 `rememberSaveable` 而不是 `remember`
 *
 * 长对话滚出屏幕、或转屏后，`LazyColumn` 会重建 item。`remember` 会让
 * 用户刚展开的卡片自己合上 —— 那是一种"界面在跟我作对"的体验。
 * key 用 `callId`：同一张卡片在重组之间保持展开状态，不同卡片互不影响。
 */
@Composable
public fun SkillCallCard(
    card: SkillCardUi,
    modifier: Modifier = Modifier,
    onOpenTask: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(card.callId) { mutableStateOf(false) }

    val accent = when (card.status) {
        MessageStatus.FAILED -> AkeColors.RiskForbidden
        MessageStatus.STREAMING -> MaterialTheme.colorScheme.primary
        else -> AkeColors.RiskPageMutating
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tag(text = stringResource(R.string.chat_skill_title), color = accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = progressTextOf(card),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (expanded) R.string.chat_skill_collapse else R.string.chat_skill_expand,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 步数进度条：只在折叠态也有意义，所以放在折叠区域之外
            val done = card.stepsDone
            val max = card.maxSteps
            if (done != null && max != null && max > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (done.toFloat() / max.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (!card.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        FieldLabel(stringResource(R.string.chat_skill_detail))
                        Text(card.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!card.args.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        FieldLabel(stringResource(R.string.chat_skill_args))
                        Text(
                            card.args,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!card.result.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        FieldLabel(stringResource(R.string.chat_skill_result_title))
                        Text(card.result, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!card.escalation.isNullOrBlank() && card.escalation != "NONE") {
                        Spacer(Modifier.height(8.dp))
                        AlertBar(
                            text = stringResource(R.string.chat_skill_escalation, card.escalation),
                            color = AkeColors.RiskSensitive,
                        )
                    }
                    // 卡片只给结论；逐步的观察/护栏/结果在任务页 —— 想看细节就跳过去
                    val taskId = card.taskId
                    if (taskId != null && onOpenTask != null) {
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(
                            text = stringResource(R.string.chat_skill_open_task),
                            onClick = { onOpenTask(taskId) },
                        )
                    }
                }
            }

            // 可疑结果的警示**不允许折叠**：它必须在用户还没展开时就可见
            if (!card.escalation.isNullOrBlank() && card.escalation != "NONE" && !expanded) {
                Spacer(Modifier.height(8.dp))
                AlertBar(
                    text = stringResource(R.string.chat_skill_escalation, card.escalation),
                    color = AkeColors.RiskSensitive,
                )
            }
        }
    }
}

/**
 * 折叠态的那一行字。
 *
 * 有步数就说"第几步"，没有步数就退回状态词 —— 两种情况都不能空着：
 * 空一行会让卡片看起来像"什么都没发生"，而实际上它可能正在跑。
 */
@Composable
private fun progressTextOf(card: SkillCardUi): String {
    val step = card.stepsDone
    return when {
        card.status == MessageStatus.FAILED -> stringResource(R.string.chat_skill_failed)
        card.status == MessageStatus.COMPLETE && step == null -> stringResource(R.string.chat_skill_done)
        step != null -> stringResource(R.string.chat_skill_progress, card.name, step)
        else -> stringResource(R.string.chat_skill_running)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(2.dp))
}
