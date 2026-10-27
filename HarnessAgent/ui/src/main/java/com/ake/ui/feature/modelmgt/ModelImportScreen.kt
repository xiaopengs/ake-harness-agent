package com.ake.ui.feature.modelmgt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ake.core.ports.LocalModelCandidate
import com.ake.core.ports.ModelAvailability
import com.ake.ui.R
import com.ake.ui.components.AlertBar
import com.ake.ui.components.FactRow
import com.ake.ui.components.NoticeBar
import com.ake.ui.components.SecondaryButton
import com.ake.ui.components.SectionCard
import com.ake.ui.components.Tag
import com.ake.ui.theme.AkeColors

/**
 * 「离线导入」整块 UI。
 *
 * ## 为什么放在模型管理页，而不是单开一路
 *
 * 在用户眼里只有一件事：**把这个模型弄到手**。下载和导入是同一件事的两条岔路，
 * 摆在一起，他才会在「下载不了」的那一刻看见旁边那条路。做成另一个入口，
 * 等于要求用户事先知道「离线导入」这个词。
 *
 * ## 三步，每步只有一个主操作
 *
 *  1. 选 manifest → 2. 从列表里挑一条 → 3. 选模型文件（然后就看进度）
 *
 * 系统文件选择器弹出去又回来的那段时间里，用户的注意力是散的 ——
 * 一次给两个按钮，他会先点错那个。所以每屏只允许有一个主按钮。
 *
 * @param onPickManifest 拉起系统选择器挑 manifest.json。
 * @param onPickModelFile 拉起系统选择器挑模型本体。
 */
@Composable
public fun ModelImportSection(
    ui: ModelImportUi,
    onPickManifest: () -> Unit,
    onPickModelFile: () -> Unit,
    onSelect: (CandidateRow) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.model_import_title),
        subtitle = stringResource(R.string.model_import_subtitle),
        modifier = modifier,
    ) {
        Column {
            when (ui.step) {
                ImportStep.IDLE -> IdleStep(onPickManifest)
                ImportStep.CHOOSE_MODEL -> ChooseStep(ui, onSelect, onPickModelFile, onReset)
                ImportStep.COPYING -> CopyingStep(ui)
                ImportStep.DONE -> DoneStep(ui, onReset)
            }

            if (ui.errorCode != null) {
                Spacer(Modifier.height(10.dp))
                AlertBar(
                    errorMessage(
                        code = ui.errorCode,
                        selected = ui.selected ?: ui.rows.firstOrNull()?.candidate,
                    ),
                )
            }
        }
    }
}

// ───────────────────────────────────────────────────────────── 第 1 步：manifest

@Composable
private fun IdleStep(onPickManifest: () -> Unit) {
    Text(
        stringResource(R.string.model_import_step1_body),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(10.dp))
    Button(onClick = onPickManifest, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.model_import_step1_action))
    }
}

// ───────────────────────────────────────────────────────────── 第 2 步：挑一条

@Composable
private fun ChooseStep(
    ui: ModelImportUi,
    onSelect: (CandidateRow) -> Unit,
    onPickModelFile: () -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tag(stringResource(R.string.model_import_manifest_ok), AkeColors.PhaseTerminalOk)
        Spacer(Modifier.width(8.dp))
        ui.manifestVersion?.let {
            Text(
                stringResource(R.string.model_import_manifest_version, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.model_import_step2_body),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))

    if (ui.rows.isEmpty()) {
        NoticeBar(stringResource(R.string.model_import_no_candidate))
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            stringResource(R.string.model_import_restart),
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    Column {
        ui.rows.forEach { row -> CandidateItem(row, row.candidate == ui.selected, onSelect) }
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = onPickModelFile,
        modifier = Modifier.fillMaxWidth(),
        enabled = ui.selected != null,
    ) { Text(stringResource(R.string.model_import_step2_action)) }
    Spacer(Modifier.height(6.dp))
    SecondaryButton(
        stringResource(R.string.model_import_restart),
        onClick = onReset,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CandidateItem(
    row: CandidateRow,
    picked: Boolean,
    onSelect: (CandidateRow) -> Unit,
) {
    val c = row.candidate
    // REVOKED 不许挑。裁决见 ModelImportViewModel 末尾「裁决一」。
    val revoked = c.availability == ModelAvailability.REVOKED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = picked,
                enabled = !revoked,
                role = Role.RadioButton,
                onClick = { onSelect(row) },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = picked, onClick = null, enabled = !revoked)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${c.modelId} · ${c.variant}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.model_import_candidate_meta, c.version, c.revision),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatBytes(c.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.model_import_context_window, c.contextWindow),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvailabilityTag(c.availability)
                if (row.alreadyOnDevice) {
                    Tag(stringResource(R.string.model_import_already_on_device), AkeColors.PhaseActive)
                }
            }
        }
    }
}

@Composable
private fun AvailabilityTag(availability: ModelAvailability) {
    val (text, color) = when (availability) {
        ModelAvailability.ACTIVE -> stringResource(R.string.model_availability_active) to AkeColors.PhaseTerminalOk
        ModelAvailability.DEPRECATED -> stringResource(R.string.model_availability_deprecated) to AkeColors.PhaseSuspended
        ModelAvailability.REVOKED -> stringResource(R.string.model_availability_revoked) to AkeColors.RiskForbidden
    }
    Tag(text, color)
}

// ───────────────────────────────────────────────────────────── 第 3 步：拷 2.6 GB

/**
 * 正在进行中的那一段 —— **这里是整个功能最容易被做假的地方**。
 *
 * 三种画法，选哪一种取决于手上有没有真实字节：
 *
 *  | 情况 | 画什么 |
 *  |---|---|
 *  | 有累计字节，也知道总大小 | 确定进度条 + 百分比 + 「已拷 x / y」+ 速度 + 剩余时间 |
 *  | 一个字节都还没有 | 不确定进度条 + 到底在干什么 |
 *  | 连总大小都未知 | 不确定进度条 |
 *
 * 中间那种是这条链路独有的：`ModelFileImporter` 一旦发现落点上已经有一份同大小的，
 * 会先把那 2.6 GB 顺序读一遍重算摘要 —— 这期间**没有任何东西可以数**，
 * 所以老老实实显示不确定。一旦把这段时间假装成百分比，用户看到的就是一个
 * 二十分钟不动的 3%，他会认为 App 死了。
 */
@Composable
private fun CopyingStep(ui: ModelImportUi) {
    val determinate = ui.totalBytes > 0L && ui.copiedBytes > 0L
    // 起步多久了：过了容忍上限还颗粒无收，说明走的是「先重算已有那份」那条路。
    val silentTooLong = ui.copiedBytes == 0L &&
        ui.startedAt > 0L &&
        android.os.SystemClock.elapsedRealtime() - ui.startedAt > ModelImportViewModel.SILENT_MS

    if (determinate) {
        val pct = (ui.copiedBytes * 100 / ui.totalBytes).toInt().coerceIn(0, 100)
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.model_import_percent, pct),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    R.string.model_import_bytes,
                    formatBytes(ui.copiedBytes),
                    formatBytes(ui.totalBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.model_import_speed, formatBytes(ui.bytesPerSecond)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            ui.etaSeconds?.let { stringResource(R.string.model_import_eta, formatDuration(it)) }
                ?: stringResource(R.string.model_import_measuring),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            if (silentTooLong) {
                stringResource(R.string.model_import_verifying_existing)
            } else {
                stringResource(R.string.model_import_starting)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(8.dp))
    NoticeBar(stringResource(R.string.model_import_do_not_close))
}

// ───────────────────────────────────────────────────────────── 完成

@Composable
private fun DoneStep(ui: ModelImportUi, onReset: () -> Unit) {
    val receipt = ui.receipt
    Tag(stringResource(R.string.model_import_done_tag), AkeColors.PhaseTerminalOk, filled = true)
    Spacer(Modifier.height(8.dp))
    Text(
        if (receipt?.copied == true) {
            stringResource(R.string.model_import_done_copied)
        } else {
            stringResource(R.string.model_import_done_already)
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (receipt != null) {
        Spacer(Modifier.height(8.dp))
        FactRow(stringResource(R.string.model_import_done_id), "${receipt.modelId} · ${receipt.variant}")
        FactRow(stringResource(R.string.model_import_done_size), formatBytes(receipt.sizeBytes))
        FactRow(stringResource(R.string.model_import_done_sha), receipt.sha256.take(16) + "…")
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.model_import_done_next),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    SecondaryButton(
        stringResource(R.string.model_import_restart),
        onClick = onReset,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ───────────────────────────────────────────────────────────── 错误文案

/**
 * 机器码 → 那一句中文。
 *
 * 挑哪句话、带哪几个数字，是 [importErrorViewOf] 的事（那里也有单元测试）；
 * 这里只负责把资源 id 变成真正的字 —— 台词归 `strings.xml`，代码归判断。
 */
@Composable
private fun errorMessage(code: String, selected: LocalModelCandidate?): String {
    val view = importErrorViewOf(code, selected)
    val args = view.args.toTypedArray()
    return if (args.isEmpty()) {
        stringResource(view.messageRes)
    } else {
        stringResource(view.messageRes, *args)
    }
}
