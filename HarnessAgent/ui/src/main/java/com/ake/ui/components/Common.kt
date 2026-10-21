package com.ake.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ake.ui.theme.AkeColors
import kotlinx.coroutines.delay

// ────────────────────────────────────────────────────────────── 卡片 / 分区

@Composable
public fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

// ────────────────────────────────────────────────────────────── 标签

/** 语义色标签。风险等级与相位共用一套形状，避免界面上出现两套「看起来不一样的重要标记」。 */
@Composable
public fun Tag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    val bg = if (filled) color else color.copy(alpha = 0.14f)
    val fg = if (filled) Color.White else color
    Box(
        modifier = modifier
            .background(bg, shape)
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.5f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** `RiskClass` → 色。只读映射，不做任何风险判定（判定在 `agent/guard`）。 */
@Composable
public fun riskColorOf(riskClass: String?): Color = when (riskClass) {
    "READ_ONLY" -> AkeColors.RiskReadOnly
    "PAGE_MUTATING" -> AkeColors.RiskPageMutating
    "SENSITIVE" -> AkeColors.RiskSensitive
    "EXTERNAL_SIDE_EFFECT" -> AkeColors.RiskExternal
    "FORBIDDEN" -> AkeColors.RiskForbidden
    else -> MaterialTheme.colorScheme.outline
}

public fun riskLabelOf(riskClass: String?): String = when (riskClass) {
    "READ_ONLY" -> "只读"
    "PAGE_MUTATING" -> "会改变页面"
    "SENSITIVE" -> "敏感操作"
    "EXTERNAL_SIDE_EFFECT" -> "域外副作用"
    "FORBIDDEN" -> "禁止"
    else -> "未分级"
}

// ────────────────────────────────────────────────────────────── 指标

@Composable
public fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 预算条。
 *
 * 用「剩余」而不是「已用」做正向指标：端侧任务里用户真正关心的是「还能跑几步」，
 * 而剩余量低于 25% 时模型会收到提示并开始收敛 —— 让用户看到同一个数字，行为才可预测。
 */
@Composable
public fun BudgetBar(remainingPct: Int, modifier: Modifier = Modifier) {
    val used = (100 - remainingPct).coerceIn(0, 100)
    val color = when {
        remainingPct > 50 -> AkeColors.RiskReadOnly
        remainingPct > 25 -> AkeColors.RiskSensitive
        else -> AkeColors.RiskForbidden
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预算剩余", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Text("$remainingPct%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { remainingPct / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        if (used >= 75) {
            Spacer(Modifier.height(4.dp))
            Text(
                "预算快用完了 —— 模型已经收到提示，正在收敛它的探索范围。",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────── 等待态

/**
 * 骨架占位。
 *
 * 端侧单步 4.5 秒、模型首次加载分钟级 —— **任何等待态都不允许白屏**。
 * 这里用一条匀速呼吸的灰条，而不是转圈：转圈暗示「马上就好」，而骨架暗示「内容还在来」，
 * 后者才是端侧推理的真实情况。
 */
@Composable
public fun SkeletonLine(modifier: Modifier = Modifier, height: Int = 14) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .height(height.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), RoundedCornerShape(4.dp)),
    )
}

@Composable
public fun WaitingBlock(text: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        SkeletonLine(Modifier.fillMaxWidth(0.9f))
        Spacer(Modifier.height(6.dp))
        SkeletonLine(Modifier.fillMaxWidth(0.65f))
    }
}

/**
 * 流式文本。
 *
 * ★ 目前的真实流式粒度是**事件级**（感知 → 推理 → 执行），`ModelSession.generate` 是一次返回整段。
 * 这里做的是「已到达文本的分字揭示」，让长答案出现时不会整块跳变；
 * 等 `agent` 侧补上 token 级事件（`TaskEvent.TokenDelta`）后，同一入口直接接增量即可 ——
 * 界面不需要改。
 */
@Composable
public fun StreamingText(
    text: String,
    modifier: Modifier = Modifier,
    charsPerTick: Int = 6,
    tickMs: Long = 16L,
) {
    var shown by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        shown = 0
        while (shown < text.length) {
            shown = (shown + charsPerTick).coerceAtMost(text.length)
            delay(tickMs)
        }
    }
    Text(
        text = text.substring(0, shown),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
    )
}

// ────────────────────────────────────────────────────────────── 空态 / 提示

@Composable
public fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 不可关闭的警示条：用于「结果不可信」这类必须被看到的信息。 */
@Composable
public fun AlertBar(text: String, color: Color = AkeColors.RiskForbidden, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────── 输入

@Composable
public fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            singleLine = singleLine && minLines <= 1,
            minLines = minLines,
            shape = RoundedCornerShape(10.dp),
        )
    }
}

/** 危险操作按钮：用实心红 + 全宽，与「取消」在权重上明显拉开。 */
@Composable
public fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = AkeColors.RiskForbidden),
    ) { Text(text) }
}

@Composable
public fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }
}

@Composable
public fun TinyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

/** 只读键值行。用于设置页那些「目前改不了但必须让用户看见」的参数。 */
@Composable
public fun FactRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
