package com.ake.ui.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.ports.AgentProfile
import com.ake.core.ports.ProfileOrigin
import com.ake.core.ports.TrustScanVerdict
import com.ake.ui.R
import com.ake.ui.components.AgentAvatar
import com.ake.ui.components.AgentTrustTags
import com.ake.ui.components.AlertBar
import com.ake.ui.components.DangerButton
import com.ake.ui.components.EmptyHint
import com.ake.ui.components.FactRow
import com.ake.ui.components.SecondaryButton
import com.ake.ui.components.SectionCard
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.Tag
import com.ake.ui.components.TinyButton
import com.ake.ui.components.relativeTimeLabel
import com.ake.ui.theme.AkeColors
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * Agent 详情。
 *
 * ## 这一页的定位：**证据页**，不是编辑器
 *
 * 用户点进一个 Agent 通常有两个理由：① 想改点什么；② 想知道"它到底被我许了什么"。
 * 本轮 ① 不可做（Port 没有 update，见 [AgentDetailViewModel] 的类注释），
 * 于是这一页把力气全花在 ② 上：人设原文、信任层级、扫描结论、对外 ID、模型、工作目录
 * 全部摊开。**只读但完整，好过一个能改但会悄悄多出一份的编辑器。**
 *
 * ## 为什么人格正文要用等宽可滚动块而不是普通段落
 *
 * persona 是**会被原样拼进 SYSTEM 段**的文本，用户看它是为了找"哪句话让模型变得爱拒答"。
 * 普通排版会把换行与空行吃掉，而 prompt 里的空行是有语义的（分段）。
 * 所以这里用紧字距的块保留原始换行。
 */
@Composable
public fun AgentDetailScreen(
    onBack: () -> Unit,
    onClone: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: AgentDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹ " + stringResource(R.string.common_back))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.agent_detail_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))

        ui.errorText?.let {
            AlertBar(stringResource(R.string.agent_list_error, it))
            Spacer(Modifier.height(8.dp))
        }

        when {
            ui.loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { SkeletonLine(Modifier.fillMaxWidth().height(48.dp)) }
            }

            ui.profile == null -> EmptyHint(stringResource(R.string.agent_detail_not_found))

            else -> ProfileBody(
                profile = ui.profile!!,
                nowMillis = viewModel.nowMillis(),
                onClone = onClone,
                onArchive = viewModel::archive,
                onDeleteClick = { confirmDelete = true },
            )
        }
    }

    val profile = ui.profile
    if (confirmDelete && profile != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.agent_delete_title, profile.name)) },
            text = { Text(stringResource(R.string.agent_delete_body)) },
            confirmButton = {
                TinyButton(
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        confirmDelete = false
                        viewModel.delete(onDeleted)
                    },
                )
            },
            dismissButton = {
                TinyButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmDelete = false },
                )
            },
        )
    }
}

@Composable
private fun ProfileBody(
    profile: AgentProfile,
    nowMillis: Long,
    onClone: (String) -> Unit,
    onArchive: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgentAvatar(name = profile.name, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (profile.description.isNotBlank()) {
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            AgentTrustTags(
                origin = profile.origin,
                trustTier = profile.trustTier,
                scanVerdict = profile.trustScan.verdict,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // ★ 扫描命中的后果：每次技能调用都会被二次确认。用户看到弹窗变多时要知道原因。
    if (profile.trustScan.verdict == TrustScanVerdict.FLAGGED ||
        profile.trustScan.verdict == TrustScanVerdict.SANITIZED
    ) {
        AlertBar(
            text = stringResource(
                R.string.agent_detail_scan_warning,
                profile.trustScan.hits.joinToString(", ").ifBlank { profile.trustScan.verdict },
            ),
            color = AkeColors.RiskSensitive,
        )
        Spacer(Modifier.height(8.dp))
    }
    if (profile.trustScan.clausesRemoved.isNotEmpty()) {
        AlertBar(
            text = profile.trustScan.clausesRemoved.joinToString(" / "),
            color = AkeColors.RiskSensitive,
        )
        Spacer(Modifier.height(8.dp))
    }

    SectionCard(
        title = stringResource(R.string.agent_detail_persona),
        subtitle = stringResource(R.string.agent_detail_persona_hint),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = profile.personaCharter.ifBlank { stringResource(R.string.common_dash) },
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    SectionCard(
        title = stringResource(R.string.agent_detail_capabilities),
        subtitle = stringResource(R.string.agent_detail_capabilities_hint),
    ) {
        // 两个 Port 都还没接线，所以这里不画开关 —— 画了也只会是假开关
        AlertBar(stringResource(R.string.agent_detail_cap_missing), color = AkeColors.RiskSensitive)
        Spacer(Modifier.height(6.dp))
        AlertBar(stringResource(R.string.agent_detail_grant_missing), color = AkeColors.RiskSensitive)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.agent_detail_cap_limit, profile.maxEnabledSkills),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(10.dp))

    SectionCard(title = stringResource(R.string.agent_detail_meta)) {
        FactRow(stringResource(R.string.agent_detail_field_agent_id), profile.agentId)
        FactRow(stringResource(R.string.agent_detail_field_model), profile.modelId)
        FactRow(stringResource(R.string.agent_detail_field_origin), originLabel(profile.origin))
        FactRow(
            stringResource(R.string.agent_detail_field_created),
            relativeTimeLabel(profile.createdAt, nowMillis),
        )
        FactRow(
            stringResource(R.string.agent_detail_field_last_used),
            profile.lastUsedAt?.let { relativeTimeLabel(it, nowMillis) }
                ?: stringResource(R.string.agent_detail_field_never_used),
        )
        FactRow(
            stringResource(R.string.agent_detail_working_dir),
            profile.workingDirUri ?: stringResource(R.string.common_dash),
        )
        FactRow(
            stringResource(R.string.create_capabilities_short),
            profile.maxEnabledSkills.toString(),
        )
        if (profile.alwaysConfirmSkills) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(stringResource(R.string.cap_confirm_always), AkeColors.RiskSensitive)
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    SectionCard(title = stringResource(R.string.agent_detail_danger)) {
        Text(
            text = stringResource(R.string.agent_detail_readonly_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = stringResource(R.string.agent_detail_clone),
                onClick = { onClone(profile.id) },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = stringResource(R.string.agent_archive),
                onClick = onArchive,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        DangerButton(
            text = stringResource(R.string.agent_detail_delete),
            onClick = onDeleteClick,
        )
    }
}

@Composable
private fun originLabel(origin: ProfileOrigin): String = when (origin) {
    ProfileOrigin.USER_DIRECT -> stringResource(R.string.agent_trust_user)
    ProfileOrigin.TEMPLATE_BUILTIN -> stringResource(R.string.agent_trust_builtin)
    ProfileOrigin.TEMPLATE_MARKET -> stringResource(R.string.agent_trust_market)
}
