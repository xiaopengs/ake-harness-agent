package com.ake.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.ui.R
import com.ake.ui.model.ChatTurnFailure
import com.ake.ui.model.ModelBadge
import com.ake.ui.model.SkillCardUi
import com.ake.ui.model.chatTerminalCopyOf
import com.ake.ui.model.messageStatusLabelRes
import com.ake.ui.theme.AkeColors

/**
 * 一条消息。
 *
 * ## 三种形态，按 `kind` 分派而不是按 `role`
 *
 * `MessageRole` 决定"谁说的"（左右与头像），`MessageKind` 决定"怎么画"：
 *  - `TEXT`：普通气泡；
 *  - `SKILL_CALL` / `SKILL_RESULT`：技能卡片（[SkillCallCard]）—— **绝不按正文渲染**；
 *  - `*_NOTE` / `COMPACT_BOUNDARY`：居中细字旁注，不占气泡宽度。
 *
 * 两者混着判断（比如"role==ASSISTANT 就当正文"）会把技能卡片画成一段散文，
 * 而那段散文的实际内容是模型编的参数摘要 —— 用户会把它当答案读。
 *
 * ## 失败态为什么贴在气泡里
 *
 * 只给页面顶部一条横幅的话，用户往回翻到那条失败消息时看不到任何理由，
 * 只会看到一句戛然而止的话。原因必须跟着它自己走。
 *
 * ## [statusOverride]：库里那行的状态可能是**陈旧事实**
 *
 * `commitAssistant` 写失败时，占位行的 `STREAMING` 再也改不动（写路径坏了），
 * 要等下次冷启 sweep 才收敛。此时数据库说的「还在生成」与事实相反 ——
 * 模型早就答完了。所以调用方可以把这一轮的失败状态**覆盖**上来，
 * **纯渲染，不回写**：那扇门已经关上了。
 */
@Composable
public fun MessageBubble(
    message: ChatMessageView,
    agentName: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    liveText: String? = null,
    /**
     * 覆盖 `message.status` 的显示状态（`null` = 照数据库说的画）。
     *
     * 只改**渲染**：徽标、气泡底色、失败脚注一律跟着它走，
     * 但 `message` 本身不被伪造 —— 数据仍是数据，投影仍是投影。
     */
    statusOverride: MessageStatus? = null,
    failure: ChatTurnFailure? = null,
    skillCard: SkillCardUi? = null,
    /**
     * ★ 这条回答是**哪里**产出的（端侧 / 云端 / 不知道）。
     *
     * `null` ⇒ 一次都没算出来（例如还没有登记过目标的会话刚开始）。
     * 「没有 badge」与「badge 说端侧」必须一眼分开 —— 前者是还没答案，后者是一个答案。
     */
    badge: ModelBadge? = null,
    /** ★ 本轮若发生过回落 / 出境失败，整份事实传进来。
     *  它的渲染不是"可选点缀" —— 有就必须出镜，一次都不能被丢掉。 */
    egressNotice: com.ake.ui.spi.TurnEgressNotice? = null,
    onRetry: () -> Unit = {},
    onOpenTask: ((String) -> Unit)? = null,
) {
    val status = statusOverride ?: message.status

    when (message.kind) {
        MessageKind.COMPACT_BOUNDARY -> {
            ChatNote(stringResource(R.string.chat_note_compacted), modifier)
            return
        }

        MessageKind.ROUTER_NOTE -> {
            ChatNote(stringResource(R.string.chat_note_router, message.textRedacted), modifier)
            return
        }

        MessageKind.MODE_NOTE -> {
            ChatNote(stringResource(R.string.chat_note_mode, message.textRedacted), modifier)
            return
        }

        MessageKind.SKILL_CALL, MessageKind.SKILL_RESULT -> {
            Box(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                if (skillCard != null) {
                    SkillCallCard(skillCard, onOpenTask = onOpenTask)
                } else {
                    // 事件流没给到进度（例如冷启动后读历史）时，至少把落库摘要画成卡片
                    SkillCallCard(
                        SkillCardUi(
                            callId = message.callId ?: message.id,
                            name = message.skillRef?.id ?: message.role.name,
                            ref = message.skillRef,
                            result = message.textRedacted.takeIf { it.isNotBlank() },
                            status = status,
                            taskId = message.taskId,
                        ),
                        onOpenTask = onOpenTask,
                    )
                }
            }
            return
        }

        MessageKind.TEXT -> Unit
    }

    val isUser = message.role == MessageRole.USER
    val shown = liveText ?: message.textRedacted

    if (message.role == MessageRole.SYSTEM_NOTE) {
        ChatNote(shown, modifier)
        return
    }

    if (isUser) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                // 右下角收一点，气泡的"尾巴"指向说话的人
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = shown,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        return
    }

    Row(modifier.fillMaxWidth()) {
        AgentAvatar(name = agentName, size = 30.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                messageStatusLabelRes(status)?.let { res ->
                    Spacer(Modifier.width(6.dp))
                    Tag(
                        text = stringResource(res),
                        color = if (status == MessageStatus.FAILED) {
                            AkeColors.RiskForbidden
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
                ModelBadgeChip(badge)
            }
            Spacer(Modifier.height(4.dp))

            val failed = status == MessageStatus.FAILED
            Surface(
                shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                color = if (failed) {
                    AkeColors.RiskForbidden.copy(alpha = 0.08f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    if (shown.isNotBlank()) {
                        Row {
                            Text(
                                text = shown,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (streaming) {
                                Spacer(Modifier.width(2.dp))
                                StreamingCursor()
                            }
                        }
                    } else if (streaming) {
                        // 首个 delta 还没到：给一个正在跳的光标，而不是一片空白
                        StreamingCursor()
                    }

                    if (failed) {
                        FailureFoot(failure = failure, onRetry = onRetry)
                    }
                }
            }

            // ★ 回落 / 出境告知挂在气泡下面：用户看到的是「这一条回答」，
            //   原因贴在它身上才对得上。放到页面顶部，往回翻历史时它就是一条没有来由的残句。
            egressNotice?.let { EgressNoticeFoot(it) }
        }
    }
}

/**
 * ★ PRE 回落 / POST 失败的告知 —— §4.2「禁止静默回落」在屏幕上的全部内容。
 *
 * 由两部分组成，缺一不可：
 *  · 标题 —— 说清楚「出了什么事」；
 *  · 下一步 —— 说清楚「你的数据到底出去没有」。
 *
 * 只给标题的话，用户知道失败了，却不知道这次失败意味着他的话有没有出境 ——
 * 而「我以为在本地 / 实际已出境」正是本项目最贵的一种错觉。
 */
@Composable
private fun EgressNoticeFoot(notice: com.ake.ui.spi.TurnEgressNotice) {
    val copy = com.ake.ui.model.cloudFailureCopyOf(notice.failure)
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(copy.titleRes),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = AkeColors.RiskSensitive,
    )
    Text(
        text = com.ake.ui.model.formatCloudCopy(stringResource(copy.nextRes), notice.host),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * ★★ 「这条回答是谁写的」。
 *
 * ## 为什么它跟状态徽标并排，却不能合并成一个
 *
 * 状态徽标答的是「这条说完了没有」，本徽标答的是「它在哪儿被算出来的」。
 * 两件事各自独立：一条由云端产出的回答可以说完也可以失败 ——
 * 合并成一个就必然有人丢掉一半信息。
 *
 * ## ★ 为什么 `[ModelBadge.Unregistered]` / `[ModelBadge.Unavailable]` 也要画出来
 *
 * 「不知道」是一种**必须被看见**的状态。把它画成空白，用户会默认答案是「本机」——
 * 那正是 §4.2 禁止的那种不可察觉的假象：**界面什么都不说，比说错更糟**。
 */
@Composable
private fun ModelBadgeChip(badge: ModelBadge?) {
    val (text, color) = when (badge) {
        null -> return
        ModelBadge.Unregistered -> stringResource(R.string.badge_unregistered) to AkeColors.RiskSensitive
        is ModelBadge.Device -> stringResource(R.string.badge_device) to MaterialTheme.colorScheme.outline
        // ★ 云端那一档必须带上模型名与归一化 host —— 「云端」两个字不说明发给谁了。
        is ModelBadge.Remote -> stringResource(R.string.badge_remote, badge.modelName, badge.host) to
            AkeColors.RiskExternal
        is ModelBadge.Unavailable -> stringResource(R.string.badge_unavailable) to AkeColors.RiskSensitive
    }
    Spacer(Modifier.width(6.dp))
    Tag(text = text, color = color)
}

/**
 * 失败脚注：**为什么失败** + **下一步做什么** + 重试。
 *
 * 三样缺一不可。只写"出错了"等于把排查成本全推给用户；
 * 只写原因不给按钮，用户只能自己重打一遍那句话。
 */
@Composable
private fun FailureFoot(failure: ChatTurnFailure?, onRetry: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    if (failure != null) {
        val copy = chatTerminalCopyOf(failure.reason)
        Text(
            text = stringResource(copy.titleRes),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = AkeColors.RiskForbidden,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(copy.nextRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failure.retryable) {
            TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.chat_turn_retry), style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        // 拿不到终态（例如冷启动时读到的历史失败行）：仍然要说人话，不能只写"失败"
        Text(
            text = stringResource(R.string.chat_term_model_failed_title),
            style = MaterialTheme.typography.bodySmall,
            color = AkeColors.RiskForbidden,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.chat_term_model_failed_next),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 旁注：不是消息，是"发生了某件事"的标记。居中、细、不抢滚动条。 */
@Composable
private fun ChatNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 流式光标。
 *
 * 它的存在意义是回答"它还在说吗" —— 端侧 decode 每秒只有几个 token，
 * 两个 token 之间可能停 300ms，没有光标时界面看起来是卡死的。
 * 呼吸而不是闪烁：闪烁会被误读成"光标在等着我输入"。
 */
@Composable
public fun StreamingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Text(
        text = "▍",
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
    )
}
