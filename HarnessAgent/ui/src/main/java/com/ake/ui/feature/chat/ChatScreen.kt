package com.ake.ui.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.MessageStatus
import com.ake.ui.R
import com.ake.ui.components.AgentAvatar
import com.ake.ui.components.AlertBar
import com.ake.ui.components.EmptyHint
import com.ake.ui.components.MessageBubble
import com.ake.ui.components.NoticeBar
import com.ake.ui.components.StreamingCursor
import com.ake.ui.components.Tag
import com.ake.ui.components.riskColorOf
import com.ake.ui.components.riskLabelOf
import com.ake.ui.model.CapabilityPresence
import com.ake.ui.model.ChatTerminalTone
import com.ake.ui.model.chatTerminalCopyOf
import com.ake.ui.theme.AkeColors
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 会话页。
 *
 * ## 布局：三段式（头部固定 / 消息流吃满 / 输入区贴底）
 *
 * `Column` + `weight(1f)` 的 `LazyColumn` + `imePadding()`。不套 Scaffold 的理由：
 * 这一页没有底栏也没有 FAB，Scaffold 只会再叠一层 inset 计算，而输入框与键盘
 * 的关系必须绝对可控 —— 键盘弹起时输入框不能被顶出屏幕，这只能由一个
 * 明确的 `imePadding` 负责。
 *
 * ## ★ 消息流为什么用 `reverseLayout`
 *
 * 聊天要"永远吸在最底下"，而 `LazyColumn` 默认从顶部锚定：最后一条消息变高
 * （流式输出逐字变长）时，视图不会跟着走，用户得手动往下拖。
 * 常规解法是监听长度变化再 `scrollToItem(lastIndex)` —— 那是**每个 token 一次
 * 滚动调用**，端侧 decode 期间每秒几十次布局请求，正好抢掉最紧张的 CPU。
 *
 * `reverseLayout = true` 把锚点放在底部：只要用户停在最底部（offset 0），
 * 最后一条变高、或新消息插入，视图自然就跟着走，零额外调用。
 * 代价是列表必须自己倒着喂（`asReversed()`，视图不复制）。
 *
 * ## 失败与确认都不弹窗
 *
 * 失败原因贴在气泡里（见 [MessageBubble]），人机确认也贴在气泡里（[ConfirmBubble]）。
 * 端侧 Agent 一旦开始弹窗，用户就会在"看着它干活"和"处理弹窗"之间反复跳，
 * 而 `docs/05` §V3 的验收点正是「确认回到气泡而不是第二个对话框」。
 */
@Composable
public fun ChatScreen(
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pending by viewModel.pendingConfirm.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val agentName = ui.agentName.ifBlank { stringResource(R.string.chat_agent_unknown) }

    Column(Modifier.fillMaxSize().imePadding()) {

        ChatHeader(
            agentName = agentName,
            streaming = ui.streaming,
            onBack = onBack,
        )

        if (ui.runnerMissing) {
            BannerSlot { AlertBar(stringResource(R.string.chat_runner_missing)) }
        }

        ui.errorText?.let { err ->
            BannerSlot { AlertBar(err) }
        }

        // 上一轮的终态：只对"没说完"的轮次给结论，`COMPLETED` 不打扰
        //
        // ★ 例外：`stuck` 非空时这段话已经贴在气泡脚注里了（那里离"没说完的那条消息"
        //   更近，还带着重试按钮）。横幅再来一遍，屏幕上就出现两段一字不差的长文案 ——
        //   重复的告警会被读成"两个不同的问题"，用户反而不知道该看哪一处。
        //
        // ★ 颜色跟 `copy.tone` 走，不跟"是不是 COMPLETED"走：`IN_FLIGHT`
        //   （上一轮还没收尾、这一轮没开始）没有任何东西失败，用红色去说"等一下"
        //   等于先说"出事了"再补一句"其实没事" —— 用户只会记住前半句。
        ui.terminal?.let { reason ->
            if (reason != ChatTerminalReason.COMPLETED && ui.stuck.isEmpty()) {
                BannerSlot { TerminalBanner(reason, onRetry = viewModel::retry) }
            }
        }

        if (ui.loadingHistory) {
            EmptyHint(stringResource(R.string.chat_loading_history), Modifier.fillMaxWidth())
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 倒着喂：index 0 = 最新一条 = 贴在输入框上方
            items(items = ui.messages.asReversed(), key = { it.id }) { msg ->
                val live = ui.isLive(msg.id)
                // ★ 写路径坏掉时库里那行永远是 STREAMING（直到冷启 sweep）。
                //   这一层覆盖让用户立刻看到"答完了但没存下来 + 重试"，而不是一直转圈。
                val stuck = ui.stuck[msg.id]
                MessageBubble(
                    message = msg,
                    agentName = agentName,
                    streaming = live,
                    // liveText 是"正在生成"的那一段；stuck.text 是失败时冻住的那一段 ——
                    // 两者都代表"这话在库里查不到，但它确实说过"，所以走同一个渲染位。
                    liveText = if (live) ui.liveText else stuck?.text,
                    statusOverride = stuck?.let { MessageStatus.FAILED },
                    // 失败现场只贴给同一轮的消息，避免历史里的旧失败也挂上新一轮的原因
                    failure = stuck?.failure
                        ?: ui.failure?.takeIf { it.turnId == msg.turnId || it.turnId == null },
                    skillCard = ui.skillCardOf(msg),
                    // ★ 这条是谁写的 —— 每条助手消息都要答得上这个问题。
                    badge = ui.badgeOf(msg),
                    // ★ 回落 / 出境失败。有就必须出镜，一次都不能被丢掉。
                    egressNotice = ui.noticeOf(msg),
                    onOpenTask = onOpenTask,
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (ui.messages.isEmpty() && !ui.loadingHistory) {
                item(key = "__empty__") {
                    EmptyHint(stringResource(R.string.chat_empty_history), Modifier.fillMaxWidth())
                }
            }

            // 首个 delta 还没到时也要有"它在想"的迹象
            if (ui.streaming && ui.liveMessageId == null) {
                item(key = "__thinking__") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentAvatar(name = agentName, size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        StreamingCursor()
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_streaming_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        pending?.let { gate ->
            ConfirmBubble(
                gate = gate,
                onAllow = viewModel::allowOnce,
                onReject = viewModel::rejectOnce,
            )
        }

        // ★ 零能力提示（P0-4）：「只回文字」的另一半。
        //
        //   放在输入框**上方**、常驻、中性色，不弹窗也不可关闭：
        //   用户马上要打字了，这是他能看到「为什么它什么都不做」的最后一个位置；
        //   而一旦它真的只回了文字，用户已经读过这句话，不会去怀疑 App 坏了。
        //   只在**确认**零能力时出现（`UNKNOWN` 不出现）—— 见 `CapabilityPresence`。
        if (ui.capabilityPresence == CapabilityPresence.NONE) {
            NoticeBar(
                text = stringResource(R.string.chat_no_capability_notice),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        ChatInputBar(
            input = ui.input,
            streaming = ui.streaming,
            enabled = !ui.runnerMissing,
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::send,
            onStop = viewModel::stop,
        )
    }
}

/** 一行高度固定的 padding 容器，省得每个横幅各写一遍修饰符。 */
@Composable
private fun BannerSlot(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) { content() }
}

/**
 * 上一轮的终态横幅。
 *
 * ## 为什么要把它从 [ChatScreen] 里拆出来
 *
 * 这段原来是内联在 `ChatScreen` 的组合体里的，而 `ChatScreen` 的最后一个参数是
 * `viewModel: ChatViewModel = hiltViewModel()` —— 要渲染它就得先造出 ViewModel 的
 * 七个依赖（`ChatSessionStorePort` / `AgentProfileStorePort` / `Optional<ChatRunnerPort>` /
 * `Optional<CapabilityGrantPort>` / `UiHumanGate` / `Clock` / `SavedStateHandle`）。
 *
 * 那意味着「横幅到底弹没弹、是不是红色的」这两个**用户第一眼看到的东西**只能靠 JVM 单测
 * 间接推断（`chatTerminalCopyOf` 的 tone 对了 ⇒ 推出来颜色对），中间那一层"tone 真的喂给了
 * `NoticeBar` 而不是 `AlertBar`"没人验证过。拆出来之后它变成一个只吃 `reason` 和回调的
 * 纯函数式 composable，instrumentation 测试可以直接渲染它并**读屏幕像素**。
 *
 * 提取是行为等价的：`COMPLETED` 的过滤与 `stuck` 的例外仍留在调用侧
 * （那里才拿得到 `ui.stuck`），这里只负责画。
 *
 * ## 为什么颜色由 `copy.tone` 决定，而不是由"是不是失败"决定
 *
 * `IN_FLIGHT`（上一轮还没收尾、这一轮没开始）没有任何东西失败，用红色去说"等一下"
 * 等于先说"出事了"再补一句"其实没事" —— 用户只会记住前半句。
 *
 * ## 为什么只有 `IN_FLIGHT` 挂重试按钮
 *
 * 它的宿主一定是「用户刚发出去的那句话」，而 USER 气泡不画脚注（见 `MessageBubble` 里
 * `isUser` 的早退）—— `FailureFoot` 那条路对它是死的：文案里写了「点重试」，屏幕上却没有
 * 那个按钮，用户只能把整句话重打一遍。横幅是它唯一的出口。
 * 其它终态的宿主是助手气泡，脚注里已经有按钮；横幅再挂一个，屏幕上就会同时出现两个
 * 「重试」，反而不知道该按哪个。
 */
@Composable
public fun TerminalBanner(
    reason: ChatTerminalReason,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    if (reason == ChatTerminalReason.COMPLETED) return

    val copy = chatTerminalCopyOf(reason)
    val text = stringResource(copy.titleRes) + " · " + stringResource(copy.nextRes)

    Column(modifier) {
        when (copy.tone) {
            ChatTerminalTone.ALERT -> AlertBar(text, color = AkeColors.RiskSensitive)
            ChatTerminalTone.NOTICE -> NoticeBar(text)
        }
        if (reason == ChatTerminalReason.IN_FLIGHT && copy.retryable) {
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_turn_retry),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ChatHeader(
    agentName: String,
    streaming: Boolean,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("‹ " + stringResource(R.string.common_back), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(2.dp))
        AgentAvatar(name = agentName, size = 32.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = agentName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (streaming) {
                Text(
                    text = stringResource(R.string.chat_streaming_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 人机确认卡片。
 *
 * 它读的是 [UiHumanGate] 的挂起状态 —— 也就是**真的会让主循环停下来等**的那一个，
 * 不是 UI 自己攒的一个标志位。按钮直接完成那个 `deferred`，
 * 所以"点了允许"和"主循环继续"是同一件事，不存在两边状态不一致的窗口。
 */
@Composable
private fun ConfirmBubble(
    gate: UiHumanGate.PendingGate,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = riskColorOf(gate.riskClass).copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(stringResource(R.string.chat_confirm_title), riskColorOf(gate.riskClass))
                Spacer(Modifier.width(6.dp))
                Tag(riskLabelOf(gate.riskClass), riskColorOf(gate.riskClass))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_confirm_body, gate.verdict.reasonKey),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow) {
                    Text(stringResource(R.string.chat_confirm_allow_once), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.chat_confirm_reject), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    streaming: Boolean,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    enabled = enabled,
                )
                Spacer(Modifier.width(8.dp))
                if (streaming) {
                    Button(onClick = onStop) {
                        Text(stringResource(R.string.chat_stop))
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = enabled && input.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.chat_send))
                    }
                }
            }
            if (!enabled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chat_input_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
