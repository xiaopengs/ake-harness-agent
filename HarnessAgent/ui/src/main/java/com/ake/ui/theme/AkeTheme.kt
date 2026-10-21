package com.ake.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 阿客 Harness 的 Compose 主题。
 *
 * ## 为什么 `:ui` 要自带一套，而不复用 `:app` 那套占位主题
 *
 * `:app:ui/theme/AkeTheme` 是移动端同事留的占位（只有 `lightColorScheme()` 默认值），
 * 且 `app` 依赖 `ui`、依赖方向不可逆 —— `:ui` 看不见 `app`。
 * 所以真正的主题只能定义在 `:ui`，`app` 的 `AkeTheme` 改为转发到本文件即可（一行）。
 *
 * ## 配色不是审美问题
 *
 * 本 App 有一条安全关键路径：确认弹窗。它的「拒绝」必须是**一眼可辨的危险色**，
 * 而不是与「允许」同权重的两个按钮 —— 用户在端侧 Agent 上点错一次的代价是真实世界的副作用。
 * 因此这里把 [AkeColors] 单独提出来，让风险色只在一处定义。
 */
public object AkeColors {

    // ── 风险语义色：与 `core/contract/RiskClass` 一一对应，不在别处再发明一套
    public val RiskReadOnly: Color = Color(0xFF2E7D32)       // 只读：绿
    public val RiskPageMutating: Color = Color(0xFF1565C0)   // 改页面：蓝
    public val RiskSensitive: Color = Color(0xFFEF6C00)      // 敏感：橙
    public val RiskExternal: Color = Color(0xFF6A1B9A)       // 域外副作用：紫
    public val RiskForbidden: Color = Color(0xFFB71C1C)      // 禁止：深红

    // ── 相位语义色
    public val PhaseActive: Color = Color(0xFF1565C0)
    /** ★ 暂停态刻意用「琥珀」而不是灰：它必须与「已结束」在色相上分开。 */
    public val PhaseSuspended: Color = Color(0xFFF9A825)
    public val PhaseTerminalOk: Color = Color(0xFF2E7D32)
    public val PhaseTerminalBad: Color = Color(0xFFB71C1C)
    public val PhaseTerminalWarn: Color = Color(0xFFEF6C00)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF00695C),
    error = Color(0xFFB71C1C),
    surface = Color(0xFFFDFCFF),
    surfaceVariant = Color(0xFFE9EEF6),
    outline = Color(0xFFB9C4D4),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF004A87),
    secondary = Color(0xFF6BDBCA),
    error = Color(0xFFFF8A80),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF222831),
    outline = Color(0xFF414B59),
)

/**
 * 应用主题入口。
 *
 * 端侧推理时屏幕会长时间停留在一个「正在推理」的画面上 —— 那正是 OLED 烧屏的场景，
 * 所以默认跟随系统深色模式，而不是写死浅色。
 */
@Composable
public fun AkeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
