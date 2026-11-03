package com.ake.ui.feature.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.ui.components.PrimaryButton
import com.ake.ui.components.PrivacyBadge
import com.ake.ui.components.SecondaryButton
import com.ake.ui.util.collectAsStateWithLifecycle

@Composable
public fun ModelGateScreen(
    onStartDownload: () -> Unit,
    onModelReady: () -> Unit,
    viewModel: ModelGateViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sizeText = formatModelSize(ui.expectedBytes)

    BackHandler(enabled = true) { }
    LaunchedEffect(ui.ready) {
        if (ui.ready) onModelReady()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(72.dp).background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(24.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("AI", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "先把本地模型装好",
                    style = MaterialTheme.typography.displayLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "阿客在你手机上运行，不联网也能用。首次需要下载一个 $sizeText 的模型，之后所有对话都在本机完成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                // ★ 这一句是给"装完怎么还是这个页面"准备的：底栏与对话入口要等模型就绪才出现，
                //   不说清楚，用户会以为装错了包（本轮真实发生过）。
                Text(
                    text = "模型就绪后才会出现底部导航与对话入口。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrivacyBadge("数据不出手机", Modifier.fillMaxWidth())
                    PrivacyBadge("下载后可离线使用", Modifier.fillMaxWidth())
                    PrivacyBadge("仅首次需要下载", Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(32.dp))
                PrimaryButton(
                    text = "下载模型 ($sizeText)",
                    onClick = {
                        if (viewModel.requestDownload()) onStartDownload()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "需要 Wi‑Fi，约 5–10 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (ui.showMeteredWarning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("当前不是 Wi‑Fi") },
            text = { Text("继续下载会消耗 $sizeText 流量。") },
            confirmButton = {
                PrimaryButton(
                    text = "仍要下载",
                    onClick = {
                        viewModel.confirmMeteredDownload()
                        onStartDownload()
                    },
                )
            },
            dismissButton = {
                SecondaryButton(
                    text = "去连 Wi‑Fi",
                    onClick = {
                        viewModel.dismissMeteredWarning()
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    },
                )
            },
        )
    }
}

internal fun formatModelSize(bytes: Long): String {
    val gib = bytes / 1_073_741_824.0
    return String.format("%.2f GB", gib)
}
