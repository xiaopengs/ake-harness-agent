package com.ake.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ake.ui.R

/**
 * 「3 分钟前」这类相对时间。
 *
 * ## 为什么在 Composable 里算，而不是在 ViewModel 里算
 *
 * 文案要进 `strings.xml`（本轮要求），而 ViewModel 不该持有 `Context`/`Resources` ——
 * 一旦持有，"会话列表几点刷新"就会和"界面还在不在"耦合在一起。
 * 所以 ViewModel 只给时间戳与 `Clock.nowMillis()`，格式化留在这一层。
 *
 * 时间戳 ≤ 0 时**什么都不渲染**：会话表里 `lastMessageAt` 可能还没写，
 * 显示「0 分钟前」比不显示更让人困惑。
 */
@Composable
public fun RelativeTimeText(
    atMillis: Long,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val label = relativeTimeLabel(atMillis, nowMillis)
    if (label.isEmpty()) return
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
public fun relativeTimeLabel(atMillis: Long, nowMillis: Long): String {
    if (atMillis <= 0L) return ""
    val diff = (nowMillis - atMillis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    return when {
        minutes < 1L -> stringResource(R.string.time_just_now)
        minutes < 60L -> stringResource(R.string.time_minutes_ago, minutes.toInt())
        minutes < 60L * 24L -> stringResource(R.string.time_hours_ago, (minutes / 60L).toInt())
        else -> stringResource(R.string.time_days_ago, (minutes / (60L * 24L)).toInt())
    }
}
