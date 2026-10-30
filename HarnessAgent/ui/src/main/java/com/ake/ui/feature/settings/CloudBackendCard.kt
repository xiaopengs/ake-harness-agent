package com.ake.ui.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.Locality
import com.ake.ui.R
import com.ake.ui.components.SectionCard
import com.ake.ui.components.SecondaryButton
import com.ake.ui.components.Tag
import com.ake.ui.components.TinyButton
import com.ake.ui.model.cloudFailureCopyOf
import com.ake.ui.model.formatCloudCopy
import com.ake.ui.theme.AkeColors

/**
 * 设置页的「推理后端」一块。
 *
 * ## ★★ 从这里往下数的第一原则是不骗人，包括不明着骗，也包括**暗着骗**
 *
 * 「暗着骗」指的不是写错文案，而是那些**看起来已经生效**的东西：
 *
 *  · 自检要是还没接上（probe 未注入），显示的是「自检还没接上」，不是「已通过」；
 *  · Key 的值不在这个 card 可见的任何一处状态里逗留（它在 `ApiKeyDraft` 里）；
 *  · 失败时回显的是**归一化 host**，不是你填的那个 URL ——
 *    那条 URL 的 query 里可能就带着 key（§6.3 ③）。
 *
 * ## 为什么这一块不写成「全局开关」
 *
 * `DialogTarget` 是**会话级**的（§3.3），没有「全局默认后端」这一个概念。
 * 所以它做的是「把你这次的选择落到还没登记过目标的会话上」，并把落了几个说出来 ——
 * 含糊其辞就是许了一个可能没兑的承诺。
 */
@Composable
public fun CloudBackendCard(
    ui: CloudBackendUi,
    /** ★ 这两个原文来自 `SecretDraft`，不在 `ui` 里 —— 只在 TextField 的值上过一下。 */
    urlValue: String,
    keyValue: String,
    onChoose: (Locality) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onProbe: () -> Unit,
    onApplyChoice: () -> Unit,
    onDelete: (CredentialRef) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.cloud_section_title),
        subtitle = stringResource(R.string.cloud_section_subtitle),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackendChoiceChip(
                text = stringResource(R.string.cloud_choice_device),
                selected = ui.choice == Locality.DEVICE,
                onClick = { onChoose(Locality.DEVICE) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            BackendChoiceChip(
                text = stringResource(R.string.cloud_choice_remote),
                selected = ui.choice == Locality.REMOTE,
                onClick = { onChoose(Locality.REMOTE) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.cloud_dom_lock_note),
            style = MaterialTheme.typography.labelSmall,
            color = AkeColors.RiskSensitive,
        )
        Spacer(Modifier.height(10.dp))

        if (ui.choice == Locality.DEVICE) {
            Text(
                "当前选择是端侧：回答全部在这台设备上算出来，不需要下面这些填项。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "给还没选过后端的会话登记为端侧",
                onClick = onApplyChoice,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CloudCredentialForm(
                ui = ui,
                urlValue = urlValue,
                keyValue = keyValue,
                onBaseUrlChange = onBaseUrlChange,
                onModelNameChange = onModelNameChange,
                onLabelChange = onLabelChange,
                onKeyChange = onKeyChange,
                onSave = onSave,
                onProbe = onProbe,
                onApplyChoice = onApplyChoice,
                onDismissMessage = onDismissMessage,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.cloud_stored_list),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (ui.saved.isEmpty()) {
            Text(stringResource(R.string.cloud_none_stored), style = MaterialTheme.typography.bodySmall)
        } else {
            ui.saved.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // ★ 只有 modelName 与归一化 host —— 不显示完整 URL（§6.7）
                        stringResource(R.string.cloud_stored_item, item.modelName, item.host),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TinyButton("删除", onClick = { onDelete(item.ref) })
                }
            }
        }
    }
}

@Composable
private fun CloudCredentialForm(
    ui: CloudBackendUi,
    /** ★ 这两个原文来自 `SecretDraft`，不在 `ui` 里 —— 只在 TextField 的值上过一下。 */
    urlValue: String,
    keyValue: String,
    onBaseUrlChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onProbe: () -> Unit,
    onApplyChoice: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    OutlinedTextField(
        value = urlValue,
        onValueChange = onBaseUrlChange,
        label = { Text(stringResource(R.string.cloud_field_base_url)) },
        placeholder = { Text(stringResource(R.string.cloud_field_base_url_placeholder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = ui.modelName,
        onValueChange = onModelNameChange,
        label = { Text(stringResource(R.string.cloud_field_model)) },
        placeholder = { Text(stringResource(R.string.cloud_field_model_placeholder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = ui.label,
        onValueChange = onLabelChange,
        label = { Text(stringResource(R.string.cloud_field_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))

    // ★ `visualTransformation` 只管屏幕上不出明文；真正的保证在别处 ——
    //   这个值来自 `ApiKeyDraft`，既不在 `SettingsUi` 里，也不在任何 `SavedStateHandle` 里。
    OutlinedTextField(
        value = keyValue,
        onValueChange = onKeyChange,
        label = { Text(stringResource(R.string.cloud_field_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.cloud_key_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(
            text = stringResource(R.string.cloud_action_save),
            onClick = onSave,
            enabled = ui.readyToProbe,
        )
        Spacer(Modifier.width(8.dp))
        SecondaryButton(
            text = stringResource(R.string.cloud_probe_action),
            onClick = onProbe,
            enabled = ui.readyToProbe && ui.probe !is CloudProbeState.Probing,
        )
        Spacer(Modifier.width(8.dp))
        SecondaryButton(
            text = "把这个云端登记到还没选过的会话",
            onClick = onApplyChoice,
            enabled = ui.saved.isNotEmpty(),
        )
    }

    Spacer(Modifier.height(8.dp))
    ProbeResult(ui)

    ui.message?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        TinyButton("知道了", onClick = onDismissMessage)
    }
}

/**
 * 四个状态各有各的说法 —— 没有第五种「看起来还行」。
 *
 * ★ 失败态必须吐出 host：只写「连接失败」三个字，用户不知道自己连的是不是他以为的那个网关。
 */
@Composable
private fun ProbeResult(ui: CloudBackendUi) {
    when (val p = ui.probe) {
        CloudProbeState.Idle -> Text(
            stringResource(R.string.cloud_probe_idle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CloudProbeState.Probing -> Text(
            stringResource(R.string.cloud_probe_testing),
            style = MaterialTheme.typography.bodySmall,
            color = AkeColors.PhaseActive,
        )

        is CloudProbeState.Available -> Text(
            stringResource(R.string.cloud_probe_ok, p.host),
            style = MaterialTheme.typography.bodySmall,
            color = AkeColors.PhaseTerminalOk,
        )

        is CloudProbeState.Failed -> {
            val copy = cloudFailureCopyOf(p.failure)
            Column {
                // ★ 归一化 host 是这里唯一被允许出现的端点信息。
                Tag(stringResource(R.string.cloud_host_line, p.host), MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(copy.titleRes),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AkeColors.RiskForbidden,
                )
                Text(
                    formatCloudCopy(stringResource(copy.nextRes), p.host),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CloudProbeState.NotWired -> Text(
            stringResource(R.string.cloud_probe_not_wired),
            style = MaterialTheme.typography.bodySmall,
            color = AkeColors.RiskSensitive,
        )
    }
}

@Composable
private fun BackendChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // ★ 选中与否落在 `ui.choice` 上，这里只是它的视觉投影 —— 视觉不与状态各持一份。
    val fg = if (selected) colors.primary else colors.onSurfaceVariant
    val bg = if (selected) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = if (selected) null else BorderStroke(1.dp, colors.outline),
        modifier = modifier,
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = text,
                color = fg,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
