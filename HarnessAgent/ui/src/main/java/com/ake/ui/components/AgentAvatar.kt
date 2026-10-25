package com.ake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ake.ui.theme.AkeColors

/**
 * Agent 头像。
 *
 * ## 为什么是「首字 + 配色」而不是真的图片
 *
 * `AgentProfile.avatarUri` 存的是用户填的地址，而 `:ui` 目前**没有任何图片加载库**
 * （版本目录里没有 Coil/Glide，加一个坐标要走版本目录）。在没接图片加载之前
 * 画一个「永远转圈的占位图」比画首字更糟：用户会以为是网络慢。
 *
 * 配色由名称稳定哈希得出 —— 同一个 Agent 在列表、详情、气泡里颜色一致，
 * 用户靠颜色就能在长列表里认出「刚才那个」。这不是装饰，是可辨识性。
 */
@Composable
public fun AgentAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val color = avatarColorOf(name)
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitialOf(name),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** 取首字。空名兜底成「A」—— 一个空圆圈会让列表看起来像没加载完。 */
private fun avatarInitialOf(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "A"
    return t.substring(0, if (t.length >= 2 && t.codePointAt(0) > 0xFFFF) 2 else 1)
}

/**
 * 名称 → 颜色。刻意只从 [AkeColors] 的既有语义色里挑：
 * 界面上出现的每一种颜色都该有含义，新造一个色相就等于偷偷加了第七种语义。
 */
@Composable
private fun avatarColorOf(name: String): Color {
    val palette = rememberAvatarPalette()
    if (name.isBlank()) return MaterialTheme.colorScheme.outline
    val h = name.fold(7) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return palette[h % palette.size]
}

@Composable
private fun rememberAvatarPalette(): List<Color> = listOf(
    AkeColors.RiskPageMutating,
    AkeColors.RiskReadOnly,
    AkeColors.RiskSensitive,
    AkeColors.RiskExternal,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.primary,
)
