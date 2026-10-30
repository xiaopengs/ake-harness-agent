package com.ake.ui.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.ui.components.AlertBar
import com.ake.ui.components.DangerButton
import com.ake.ui.components.FactRow
import com.ake.ui.components.LabeledField
import com.ake.ui.components.SectionCard
import com.ake.ui.components.SecondaryButton
import com.ake.ui.components.Tag
import com.ake.ui.components.TinyButton
import com.ake.ui.theme.AkeColors
import com.ake.ui.util.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * ## ★ 一键清除全部痕迹是这一页的核心，不是附带的按钮
 *
 * 端侧 Agent 是在用户的**登录态**下访问网页的，而且把过程完整录了下来。
 * 所以「清除」的范围必须包括 WebView 的 `app_webview/`（Cookie / LocalStorage / IndexedDB / FormData）——
 * 只删自己的数据库而留着它，用户拿 ADB 看一眼就能还原访问过哪些站点。
 *
 * 顺序也写死在实现里：**先删密钥再删文件**。中途崩了的话，
 * 「文件还在、密钥已没」只是等一次覆盖，「文件没了、密钥还在」则是白删。
 *
 * ## 只读参数为什么不藏起来
 *
 * 上下文预算档位、单步超时目前改不了（由设备内存档位决定，写在 `LoopBudget` 里）。
 * 这里照样列出来并标注「只读」—— 让用户看见真实约束，
 * 好过给他一个调了也不会生效的滑块（那会造成最难排查的一类不一致）。
 */
@Composable
public fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val local by viewModel.local.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        ui.message?.let {
            AlertBar(it, AkeColors.PhaseActive)
            Spacer(Modifier.height(8.dp))
            TinyButton("知道了", onClick = viewModel::dismissMessage)
            Spacer(Modifier.height(8.dp))
        }

        // ── 推理后端（端侧 / 云端）★ 本轮新增：切换入口在这里
        CloudBackendCard(
            ui = ui.cloud,
            urlValue = viewModel.urlDraft.value,
            keyValue = viewModel.apiKey.value,
            onChoose = viewModel::chooseBackend,
            onBaseUrlChange = viewModel::onBaseUrlChange,
            onModelNameChange = viewModel::onModelNameChange,
            onLabelChange = viewModel::onLabelChange,
            onKeyChange = viewModel::onApiKeyChange,
            onSave = viewModel::saveCloudCredential,
            onProbe = viewModel::testConnection,
            onApplyChoice = viewModel::applyBackendChoice,
            onDelete = viewModel::deleteCloudCredential,
            onDismissMessage = viewModel::dismissCloudMessage,
        )

        Spacer(Modifier.height(12.dp))

        // ── 域名白名单（真生效）
        SectionCard(
            "域名白名单",
            subtitle = "白名单内的域名不会再触发「跨域名需确认」。空 = 不限，交给跨域名确认兜底。",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabeledField(
                    "新增域名",
                    ui.hostDraft,
                    viewModel::onHostDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = "example.com",
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = viewModel::addAllowHost) { Text("加入") }
            }
            Spacer(Modifier.height(8.dp))
            if (local.allowHosts.isEmpty()) {
                Text("（空）当前不限域名，每次跨站都会问你一次。", style = MaterialTheme.typography.bodySmall)
            } else {
                local.allowHosts.forEach { host ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(host, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TinyButton("移除", onClick = { viewModel.removeAllowHost(host) })
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 最大步数（真生效）
        SectionCard("最大步数", subtitle = "真生效：下发到 TaskSpec.maxSteps，不能超过档位上限") {
            val cap = ui.budget.maxSteps
            val current = local.maxSteps ?: cap
            Text("当前：$current 步（档位上限 $cap）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Slider(
                value = current.toFloat(),
                onValueChange = { viewModel.setMaxSteps(it.roundToInt()) },
                valueRange = 3f..cap.toFloat().coerceAtLeast(3f),
                steps = (cap - 3).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "步数不是真正的成本单位 —— 端侧按 token（≈按钱）限流。调低步数只是让它更早收手。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            SecondaryButton("跟随档位默认值", onClick = { viewModel.setMaxSteps(null) })
        }

        Spacer(Modifier.height(12.dp))

        // ── 预算（只读）
        SectionCard("上下文预算与超时", subtitle = "由设备内存档位决定，目前只读（档位：${ui.budget.memoryTier}）") {
            FactRow("上下文工作窗口", "${ui.budget.workingWindow} token")
            FactRow("输出预留", "${ui.budget.outputReserve} token")
            FactRow("单步上限", "${ui.budget.perStepMs} ms")
            FactRow("推理（decode）上限", "${ui.budget.decodeMs} ms")
            FactRow("动作执行上限", "${ui.budget.actionMs} ms")
            FactRow("总时长上限", "${ui.budget.maxWallClockMs / 1000} 秒")
            FactRow("prefill 总额度", "${ui.budget.maxPrefillTokens} token")
            FactRow("decode 总额度", "${ui.budget.maxDecodeTokens} token")
            FactRow("候选元素 Top-K", "${ui.budget.topK}")
            Spacer(Modifier.height(6.dp))
            Text(
                "这几个值是「2.58 GB 模型 + WebView + App 同抢一份内存」算出来的硬约束，" +
                    "不是偏好。要开放调节需要后端提供预算覆写入口 —— 在那之前 UI 不假装有得调。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 可解释性
        SectionCard("可解释性") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = local.showDomPayload, onCheckedChange = viewModel::setShowDomPayload)
                Text("显示模型原文与 Agent 看到的 DOM", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "关掉会让步骤流变短，但出问题时就无从查起了 —— 默认开着。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 存储占用
        SectionCard("存储占用", subtitle = "「我们存了什么」是这个 App 必须回答的问题") {
            val u = ui.usage
            if (u == null) {
                Text("正在统计…", style = MaterialTheme.typography.bodySmall)
            } else {
                FactRow("数据库", formatBytes(u.databaseBytes))
                FactRow("轨迹文件", formatBytes(u.traceBytes))
                FactRow("WebView 本地数据", formatBytes(u.webViewBytes))
                FactRow("模型文件", formatBytes(u.modelBytes))
                FactRow("合计", formatBytes(u.totalBytes))
                FactRow("设备可用", formatBytes(u.availableBytes))
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton("重新统计", onClick = viewModel::refreshUsage)
        }

        Spacer(Modifier.height(12.dp))

        // ── 数据保留
        SectionCard("数据保留", subtitle = "FULL → DIGEST → 删除，没有反向") {
            Text(
                "FULL：完整轨迹与快照。DIGEST：只留摘要（原文已不可读）。" +
                    "清理作业在设备空闲且非低存储时自动跑，每批 20 个任务、总预算 2 秒。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Tag("保留策略目前由后端作业统一管理", MaterialTheme.colorScheme.outline)
        }

        Spacer(Modifier.height(12.dp))

        // ── ★ 一键清除
        SectionCard("一键清除全部痕迹", subtitle = "包括 WebView 的 Cookie 与本地存储 —— 只删自己的库等于没删") {
            Text(
                "清除顺序：① 删 Keystore 密钥（密码学粉碎，主手段）→ ② 卸载模型 → ③ 删数据库 → " +
                    "④ 删轨迹文件 → ⑤ 删配置 → ⑥ 删 WebView 痕迹（app_webview/）→ ⑦ 模型文件（可选）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = ui.deleteModelFiles,
                    onCheckedChange = viewModel::setDeleteModelFiles,
                )
                Text("同时删除 2.6 GB 的模型文件", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            DangerButton(
                text = if (ui.wiping) "正在清除…" else "清除全部痕迹",
                onClick = viewModel::requestWipe,
                enabled = !ui.wiping,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton("仅断开外部工具连接", onClick = viewModel::disconnectTools, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(24.dp))
    }

    if (ui.confirmWipe) {
        AlertDialog(
            onDismissRequest = viewModel::cancelWipe,
            title = { Text("确认清除全部痕迹？") },
            text = {
                Column {
                    Text("这会删除：全部任务记录与轨迹、模型加载状态、WebView 的 Cookie / 本地存储 / 缓存，以及加密密钥。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "删除密钥之后，所有加密字段（任务目标、答案、工具入出参）在数学上永久不可恢复 —— " +
                            "这是唯一「无论文件是否被真正擦除都成立」的保证。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (ui.deleteModelFiles) "模型文件也会一并删除。" else "模型文件会保留（2.6 GB，重下代价高）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ui.wipeReport?.let { r ->
                        Spacer(Modifier.height(8.dp))
                        FactRow("删除的密钥条目", "${r.keystoreEntriesDeleted}")
                        FactRow("删除的数据库", "${r.databasesDeleted}")
                        FactRow("WebView 痕迹", if (r.webViewCleared) "已清除" else "清除失败")
                    }
                }
            },
            confirmButton = { DangerButton("确认清除", onClick = viewModel::doWipe, enabled = !ui.wiping) },
            dismissButton = { SecondaryButton("取消", onClick = viewModel::cancelWipe) },
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return String.format("%.2f GB", gb)
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) return String.format("%.1f MB", mb)
    return String.format("%.0f KB", bytes / 1024.0)
}
