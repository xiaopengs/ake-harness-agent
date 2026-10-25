package com.ake.ui.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.ui.R
import com.ake.ui.components.AgentAvatar
import com.ake.ui.components.AlertBar
import com.ake.ui.components.EmptyHint
import com.ake.ui.components.RelativeTimeText
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.Tag
import com.ake.ui.theme.AkeColors
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 对话列表（首页）。
 *
 * ## 布局方案
 *
 * `Box` + `LazyColumn` + 右下角 `FloatingActionButton`，**不再套一层 Scaffold**：
 * 外层 `TabScaffold` 已经有底栏与内边距，嵌套 Scaffold 会把 FAB 顶到两条
 * inset 之间（底栏之上、又不像浮在列表上）。这里自己定位反而一处就准。
 *
 * ## 为什么一行里同时给「名称 / 预览 / 时间」三样
 *
 * 这是产品形态的最小可辨识组合：名称回答"和谁"，预览回答"聊到哪"，
 * 时间回答"多久以前"。少任何一样，用户都得点进去才能确认是不是要找的那条 ——
 * 而列表页存在的意义就是避免这一下点击。
 */
@Composable
public fun ChatListScreen(
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val now = viewModel.nowMillis()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)) {
                Text(
                    text = stringResource(R.string.chat_list_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.chat_list_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ui.error?.let { err ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    AlertBar(stringResource(R.string.chat_list_error, err))
                }
            }

            when {
                ui.loading -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { SkeletonLine(Modifier.fillMaxWidth().height(44.dp)) }
                }

                ui.items.isEmpty() -> Column(Modifier.padding(14.dp)) {
                    EmptyHint(stringResource(R.string.chat_list_empty_title))
                    EmptyHint(stringResource(R.string.chat_list_empty_body))
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // ★ key = sessionId：会话顺序会随 lastMessageAt 变化而重排，
                    //   用位置当 key 会让「正在输入的那条」在重排时丢状态。
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp),
                ) {
                    items(items = ui.items, key = { it.sessionId }) { item ->
                        SessionRow(
                            item = item,
                            nowMillis = now,
                            onClick = { onOpenSession(item.sessionId) },
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewChat,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                text = "+",
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun SessionRow(
    item: ChatListItem,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val name = item.agentName.ifBlank { stringResource(R.string.chat_list_untitled_agent) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(name = name, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                RelativeTimeText(atMillis = item.lastMessageAt, nowMillis = nowMillis)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.preview.ifBlank { stringResource(R.string.chat_list_no_message) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.unreadCount > 0) {
            Spacer(Modifier.width(8.dp))
            Tag(
                text = stringResource(R.string.chat_list_unread, item.unreadCount),
                color = AkeColors.RiskSensitive,
                filled = true,
            )
        }
    }
}
