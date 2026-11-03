package com.ake.ui.feature.modelmgt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.contract.ModelAssetState
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.ui.components.AlertBar
import com.ake.ui.components.FactRow
import com.ake.ui.components.PrimaryButton
import com.ake.ui.components.SectionCard
import com.ake.ui.components.SecondaryButton
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.Tag
import com.ake.ui.components.TextButton
import com.ake.ui.theme.AkeColors
import com.ake.ui.util.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * 模型管理页。
 *
 * ## 这一页的全部设计都被「2.6 GB」这一个数字决定
 *
 * 一次下载在移动网络上失败是常态。所以：
 *  - **进度必须可中断可续**（后端用 HTTP Range 做到，UI 只需诚实显示已下多少）；
 *  - **速度与剩余时间必须可见**（否则用户不知道该等还是该换网络）；
 *  - **失败原因必须可读**（「下载失败」四个字让人只能反复重试，那是把问题推给用户）；
 *  - **模型文件删不删单独问一次**（重下代价高，不能藏在「清除全部」里顺手删掉）。
 */
@Composable
public fun ModelScreen(
    autoStart: Boolean = false,
    onModelReady: () -> Unit = {},
    onCancelled: () -> Unit = {},
    viewModel: ModelViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val expectedBytes = ui.asset.expectedBytes.takeIf { it > 0L }
        ?: com.ake.ui.feature.onboarding.ModelGateViewModel.VERIFIED_MODEL_BYTES
    val modelSize = formatBytes(expectedBytes)

    LaunchedEffect(autoStart) {
        if (autoStart) viewModel.ensureReady()
    }
    LaunchedEffect(autoStart, ui.asset.state) {
        if (autoStart && ui.asset.state == ModelAssetState.READY) {
            delay(1_200L)
            onModelReady()
        }
    }

    // ── 离线导入：走 SAF，不需要任何存储权限（系统选择器临时授予读权）。
    val importVm: ModelImportViewModel = hiltViewModel()
    val importUi by importVm.ui.collectAsStateWithLifecycle()

    // manifest 通常是 .json，但各家云盘 / 蓝牙传过来的 MIME 五花八门，
    // 所以除了 application/json 外一并兜 "*/*"，否则用户明明看得见文件却点不动。
    val pickManifest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importVm.onManifestPicked(uri?.toString())
    }
    // 模型本体是 .litertlm，不在任何标准 MIME 表里 ⇒ 只能全开。
    val pickModelFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importVm.onModelFilePicked(uri?.toString())
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("模型", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Gemma-4-E2B-it · $modelSize · 全程在本机推理",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        StateCard(
            ui = ui,
            onDownload = viewModel::ensureReady,
            onOpenChat = onModelReady,
        )

        Spacer(Modifier.height(12.dp))

        if (
            ui.asset.state == ModelAssetState.DOWNLOADING ||
            ui.asset.state == ModelAssetState.PAUSED ||
            ui.asset.state == ModelAssetState.VERIFYING
        ) {
            ProgressCard(ui = ui, viewModel = viewModel, onCancelled = onCancelled)
            Spacer(Modifier.height(12.dp))
        }

        // ★ 导入排在下载之后、存储预检之前：内置清单未签名时（见 ModelCatalog 的
        //   `REPLACE_WITH_SIGNED_MANIFEST_SHA256`），这条路是唯一能真正把模型弄上手的。
        //   把它压到三屏之后，等于让用户先白等一次 2.6 GB 的下载。
        ModelImportSection(
            ui = importUi,
            onPickManifest = { pickManifest.launch(arrayOf("application/json", "*/*")) },
            onPickModelFile = { pickModelFile.launch(arrayOf("*/*")) },
            onSelect = importVm::select,
            onReset = importVm::reset,
        )

        Spacer(Modifier.height(12.dp))

        SpaceCard(ui = ui, viewModel = viewModel)

        Spacer(Modifier.height(12.dp))

        SectionCard("设备与后端", subtitle = viewModel.socSummary()) {
            FactRow("内存档位", viewModel.memoryTier())
            FactRow("上下文窗口", "${ui.asset.contextWindow} token")
            FactRow("最低内存要求", "${ui.asset.minRamGb} GB")
            FactRow(
                "当前后端",
                when (val r = ui.runtime) {
                    is ModelRuntimeState.Ready -> "${r.backend.name}（已加载，${r.contextTokens} token）"
                    is ModelRuntimeState.Loading -> "正在加载 ${r.backend.name}…"
                    is ModelRuntimeState.Failed -> "加载失败"
                    ModelRuntimeState.Unloaded -> "未加载"
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "后端是自动选的：优先 GPU / NPU，失败降级到 CPU。NPU 需要该 SoC 变体专用的模型文件，通用版跑不了。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard("操作") {
            // 下载按钮已上移到「状态」卡：状态卡的文案一直在说"第一次点「下载并加载」"，
            // 而按钮原来在第 4 屏。用户滑不到那里，只会以为这一页没有下载入口。
            SecondaryButton("释放内存", onClick = viewModel::unload, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SecondaryButton("删除模型文件（$modelSize，重下代价高）", onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth())
        }

    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除模型文件？") },
            text = {
                Text("删除后要重新下载 $modelSize。如果你只是想释放内存，用「释放内存」就够了 —— 它只卸载常驻、不删文件。")
            },
            confirmButton = {
                com.ake.ui.components.DangerButton("删除", onClick = { confirmDelete = false; viewModel.deleteFile() })
            },
            dismissButton = { SecondaryButton("取消", onClick = { confirmDelete = false }) },
        )
    }
}

@Composable
private fun StateCard(
    ui: com.ake.ui.feature.modelmgt.ModelUi,
    onDownload: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val expected = ui.asset.expectedBytes.takeIf { it > 0L }
        ?: com.ake.ui.feature.onboarding.ModelGateViewModel.VERIFIED_MODEL_BYTES
    val size = formatBytes(expected)
    val (label, color) = when (ui.asset.state) {
        ModelAssetState.NOT_PRESENT -> "未下载" to MaterialTheme.colorScheme.outline
        ModelAssetState.DOWNLOADING -> "下载中" to AkeColors.PhaseActive
        ModelAssetState.PAUSED -> "已暂停" to AkeColors.PhaseSuspended
        ModelAssetState.VERIFYING -> "校验中" to AkeColors.PhaseTerminalWarn
        ModelAssetState.READY -> "就绪" to AkeColors.PhaseTerminalOk
        ModelAssetState.CORRUPTED -> "文件损坏" to AkeColors.RiskForbidden
        else -> ui.asset.state to MaterialTheme.colorScheme.outline
    }
    val ready = ui.asset.state == ModelAssetState.READY
    val downloading = ui.asset.state == ModelAssetState.DOWNLOADING
    val pct = if (ui.asset.expectedBytes > 0L) {
        (ui.asset.bytesDownloaded * 100 / ui.asset.expectedBytes).toInt().coerceIn(0, 100)
    } else {
        0
    }
    // 内置清单的 sha256 还是占位值 ⇒ 下完也一定会被校验拒掉。这件事必须在开始下载前说，
    // 不能等 2.6 GB 拉完再报"文件损坏"。
    val unsigned = ModelViewModel.isUnsignedManifest(ui.asset.sha256)
    // 未签名清单 + 网络下载 = 白下 2.6 GB。不禁止（那是替用户做决定），但要他知情。
    var askUnsigned by remember { mutableStateOf(false) }

    SectionCard("状态", subtitle = "模型 id：${ui.asset.id}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(label, color, filled = ready)
            Spacer(Modifier.height(4.dp))
            Text("变体 ${ui.asset.variant} · $size", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))

        when {
            ready -> Text(
                "模型已就绪，可以直接开始对话。",
                style = MaterialTheme.typography.bodySmall,
            )

            ui.starting -> Text(
                "正在准备下载…正在建立连接并创建文件，还没有字节进来。",
                style = MaterialTheme.typography.bodySmall,
                color = AkeColors.PhaseActive,
            )

            downloading -> {
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "$pct% · ${formatBytes(ui.asset.bytesDownloaded)} / ${formatBytes(ui.asset.expectedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatDownloadEstimate(ui.bytesPerSecond, ui.etaSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Text(
                "还没有模型文件。点下面的按钮开始下载 $size —— 建议连接 Wi‑Fi。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (ui.progressStale) {
            Spacer(Modifier.height(8.dp))
            AlertBar(
                "10 秒内没有收到任何下载进度：内置地址指向 huggingface.co，" +
                    "在中国大陆的移动网络下通常连不上。请改用下面的「从本地导入」，或换一个可访问的下载源。",
            )
        }
        if (unsigned) {
            Spacer(Modifier.height(8.dp))
            AlertBar("内置清单里的校验值还是占位值（未签名），网络下载完成后会被校验拒绝。要走网络下载需先配置签名 manifest；否则请用下面的「从本地导入」。")
        }
        if (ui.asset.state == ModelAssetState.CORRUPTED) {
            Spacer(Modifier.height(8.dp))
            AlertBar("文件校验不通过，它不会被当成可用模型 —— 请重新下载。")
        }
        if (ui.errorCode != null) {
            Spacer(Modifier.height(8.dp))
            AlertBar("！ ${ui.errorMessage ?: "暂时无法准备模型，请检查网络后重试。"}")
        }

        Spacer(Modifier.height(12.dp))
        when {
            ready -> PrimaryButton(
                text = "开始对话",
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth(),
            )

            downloading -> Text(
                "正在下载，请勿关闭应用。可在下方暂停或取消。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> PrimaryButton(
                text = if (ui.asset.state == ModelAssetState.PAUSED) {
                    "继续下载（$size）"
                } else {
                    "下载并加载（$size）"
                },
                onClick = { if (unsigned) askUnsigned = true else onDownload() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.busy && !ui.starting,
                loading = ui.starting,
            )
        }
    }

    if (askUnsigned) {
        AlertDialog(
            onDismissRequest = { askUnsigned = false },
            title = { Text("清单未签名，下载多半白费") },
            text = {
                Text(
                    "内置清单里的 sha256 仍是占位值，文件下完也一定会被校验拒绝。" +
                        "另外该地址指向 huggingface.co，在部分网络下无法访问，表现是「一直 0%」。" +
                        "建议改用「从本地导入」。仍要继续尝试网络下载吗？",
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = "仍然尝试",
                    onClick = {
                        askUnsigned = false
                        onDownload()
                    },
                )
            },
            dismissButton = { SecondaryButton("取消", onClick = { askUnsigned = false }) },
        )
    }
}

@Composable
private fun ProgressCard(
    ui: com.ake.ui.feature.modelmgt.ModelUi,
    viewModel: ModelViewModel,
    onCancelled: () -> Unit,
) {
    val pct = if (ui.asset.expectedBytes > 0L) {
        (ui.asset.bytesDownloaded * 100 / ui.asset.expectedBytes).toInt().coerceIn(0, 100)
    } else {
        0
    }
    val paused = ui.asset.state == ModelAssetState.PAUSED
    val verifying = ui.asset.state == ModelAssetState.VERIFYING
    val size = formatBytes(
        ui.asset.expectedBytes.takeIf { it > 0L }
            ?: com.ake.ui.feature.onboarding.ModelGateViewModel.VERIFIED_MODEL_BYTES,
    )

    SectionCard(
        title = when {
            verifying -> "正在校验"
            paused -> "下载已暂停"
            else -> "正在下载"
        },
        subtitle = when {
            verifying -> "正在确认文件完整性"
            paused -> "已保留下载进度，继续后不会从头开始"
            else -> "正在下载，请勿关闭应用"
        },
    ) {
        if (verifying) {
            SkeletonLine(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "正在检查 $size 的模型文件，请稍候。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "$pct%",
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = formatDownloadEstimate(ui.bytesPerSecond, ui.etaSeconds),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            SecondaryButton(
                text = if (paused) "继续" else "暂停",
                onClick = if (paused) viewModel::ensureReady else viewModel::pauseDownload,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = "取消",
                onClick = { viewModel.cancelDownload(onCancelled) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SpaceCard(ui: com.ake.ui.feature.modelmgt.ModelUi, viewModel: ModelViewModel) {
    val need = (ui.asset.expectedBytes * ModelViewModel.SPACE_HEADROOM).toLong()
    SectionCard("存储空间预检", subtitle = "下载前要求 15% 余量（模型本体 + 解压与缓存临时文件）") {
        FactRow("可用空间", formatBytes(ui.availableBytes))
        FactRow("需要空间", if (need > 0L) formatBytes(need) else "—")
        if (!ui.enoughSpace) {
            Spacer(Modifier.height(8.dp))
            AlertBar("空间不够。撑到最后 1% 才发现写不下是最糟的体验，所以这里提前拦住。")
        }
        Spacer(Modifier.height(6.dp))
        SecondaryButton("重新检查", onClick = viewModel::refreshSpace)
    }
}

