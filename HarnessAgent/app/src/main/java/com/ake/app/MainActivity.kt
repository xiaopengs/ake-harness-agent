package com.ake.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ake.app.ui.theme.AkeTheme
import com.ake.app.ui.RootScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity。
 *
 * 只负责：权限申请入口 + 把内容区交给 Compose。
 * 不含任何业务规则（架构：L6 唯一职责是渲染 + 收集人类输入）。
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也允许继续跑任务，只是丢失了中止入口 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeAskNotifications()
        setContent {
            AkeTheme {
                RootScreen()
            }
        }
    }

    /** Android 13+ 通知需要运行时权限；它是「随时可中止」体验的依赖。 */
    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
