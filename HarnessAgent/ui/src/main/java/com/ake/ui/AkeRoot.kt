package com.ake.ui

import androidx.compose.runtime.Composable
import com.ake.ui.nav.AkeNavHost
import com.ake.ui.theme.AkeTheme

/**
 * `:ui` 对外的唯一入口。
 *
 * `:app` 的 `MainActivity` 目前调的是 `com.ake.app.ui.RootScreen`（占位壳）。
 * 换成这里只需一行 —— 替换后 `app` 不再需要任何 UI 代码：
 *
 * ```kotlin
 * setContent { com.ake.ui.AkeRootScreen() }
 * ```
 *
 * 主题也在 `:ui` 里（`app` 的 `AkeTheme` 是只有默认配色的占位，且依赖方向不可逆：
 * `app` 依赖 `ui`，`ui` 看不见 `app`）。
 */
@Composable
public fun AkeRootScreen() {
    AkeTheme {
        AkeNavHost()
    }
}
