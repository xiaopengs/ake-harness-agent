package com.ake.ui.feature.modelmgt

/**
 * 模型页共用的格式化。
 *
 * ## 为什么要抽出来
 *
 * 「下载-progress」卡片与「本地导入」卡片要显示同一套字节 / 时长的写法。
 * 抄两份的下场在 `SettingsScreen` 已经发生过一次（`formatBytes` 在那里就有第二份）：
 * 一侧改了单位阈值，另一侧没改，同一个 App 里同一个 2.6 GB 显示成两个数字。
 * 模型页只有 2.6 GB 这一个量级可以讲 —— **它显示的数必须处处一致**。
 */

/** 字节数 → 人类可读。阈值取二进制单位（GiB / MiB），与 Android 文件管理器一致。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return String.format("%.2f GB", gb)
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) return String.format("%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format("%.0f KB", kb)
}

/** 秒 → 「几分几秒」。用于剩余时间，不用于耗时（耗时另有按毫秒的版本）。 */
internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m} 分 ${s} 秒" else "${s} 秒"
}
