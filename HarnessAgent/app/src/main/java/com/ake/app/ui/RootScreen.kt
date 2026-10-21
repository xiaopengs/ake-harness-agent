package com.ake.app.ui

import androidx.compose.runtime.Composable

/**
 * 根界面 —— **已切换到 `:ui` 的真实入口**（2026-10-21）。
 *
 * 占位壳（原 `Surface { PlaceholderHome() }`）在此退役：`:ui` 模块已交付
 * `com.ake.ui.AkeRootScreen()`，它自带 `AkeTheme` 与 `AkeNavHost`。
 *
 * 这个文件现在只剩一个转发，存在的唯一理由是 `MainActivity` 的调用点不用改。
 * 等 UI 侧稳定后可以把它整个删掉，让 `MainActivity` 直接调 `com.ake.ui.AkeRootScreen()`。
 */
@Composable
public fun RootScreen() {
    com.ake.ui.AkeRootScreen()
}
