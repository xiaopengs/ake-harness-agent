package com.ake.ui.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.ui.R
import com.ake.ui.components.AlertBar
import com.ake.ui.components.CapabilityPicker
import com.ake.ui.components.LabeledField
import com.ake.ui.components.NoticeBar
import com.ake.ui.components.SectionCard
import com.ake.ui.theme.AkeColors
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 创建 Agent。
 *
 * ## 表单顺序即风险顺序
 *
 * 名称/描述/头像在最上（无风险，随便填），人格设定在中（有风险 —— 它会进 SYSTEM 段），
 * 扩展能力在最下（最高风险 —— 它决定这个角色能对外界做什么）。
 * 把"能力"放在第一屏之外不是为了藏，而是因为它必须在用户已经写过人格之后才出现：
 * 顺序会暗示因果，先写人设的人更容易想清楚"它该有什么能力"。
 *
 * ## 为什么模板来的字段仍然可改
 *
 * 模板是起点不是合同。`docs/05` §4.3：模板实例化之后人格是**独立的**，
 * 删模板也不影响它 —— 所以这里必须允许改，否则"用模板"就变成了"用别人写死的角色"。
 */
@Composable
public fun CreateAgentScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: CreateAgentViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.created) { if (ui.created) onCreated() }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹ " + stringResource(R.string.common_back))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.create_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        ui.templateName?.let { name ->
            Spacer(Modifier.height(8.dp))
            AlertBar(stringResource(R.string.create_from_template_banner, name), color = AkeColors.RiskReadOnly)
        }

        ui.errorText?.let {
            Spacer(Modifier.height(8.dp))
            AlertBar(stringResource(R.string.create_failed, it))
        }

        Spacer(Modifier.height(10.dp))

        LabeledField(
            label = stringResource(R.string.create_name),
            value = ui.name,
            onValueChange = viewModel::onNameChange,
            placeholder = stringResource(R.string.create_name_placeholder),
        )
        if (ui.nameMissing) {
            Spacer(Modifier.height(4.dp))
            AlertBar(stringResource(R.string.create_need_name), color = AkeColors.RiskSensitive)
        }

        Spacer(Modifier.height(10.dp))

        LabeledField(
            label = stringResource(R.string.create_desc),
            value = ui.description,
            onValueChange = viewModel::onDescriptionChange,
            placeholder = stringResource(R.string.create_desc_placeholder),
            singleLine = false,
            minLines = 2,
        )

        Spacer(Modifier.height(10.dp))

        LabeledField(
            label = stringResource(R.string.create_avatar),
            value = ui.avatarUri,
            onValueChange = viewModel::onAvatarChange,
            placeholder = "https://",
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(R.string.create_avatar_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        LabeledField(
            label = stringResource(R.string.create_persona),
            value = ui.persona,
            onValueChange = viewModel::onPersonaChange,
            placeholder = stringResource(R.string.create_persona_placeholder),
            singleLine = false,
            minLines = 8,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(R.string.create_persona_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.personaMissing) {
            Spacer(Modifier.height(4.dp))
            AlertBar(stringResource(R.string.create_need_persona), color = AkeColors.RiskSensitive)
        }
        // 模板带来的合规声明：进 SYSTEM 尾部且不可编辑，所以只展示、不给输入框
        ui.disclaimer?.let { d ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tpl_disclaimer_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(d, style = MaterialTheme.typography.bodySmall, color = AkeColors.RiskSensitive)
        }

        Spacer(Modifier.height(10.dp))

        SectionCard(
            title = stringResource(R.string.create_capabilities, ui.limit),
            subtitle = stringResource(R.string.agent_detail_capabilities_hint),
        ) {
            CapabilityPicker(
                options = ui.options,
                selected = ui.selected,
                limit = ui.limit,
                onToggle = viewModel::onToggleCapability,
                unavailableHint = if (ui.registryMissing) {
                    stringResource(R.string.cap_missing_grants)
                } else {
                    null
                },
            )

            // ★ 零能力提示（P0-4）。三条设计约束：
            //   ① **不禁用「创建」** —— 一个只会聊天的 Agent 是完全正当的用法，
            //      拿按钮去挡等于替用户判断他不需要什么；
            //   ② 说清**后果**（只会聊天、不会开网页、不会调工具），而不是说「建议配置」——
            //      后者听起来像优化建议，用户会跳过；
            //   ③ 给出**具体路径**（详情页 →「以它为模板新建」），因为详情页本轮是只读的，
            //      写「去详情页添加」会让用户到了那里找不到任何开关。
            if (ui.selected.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                NoticeBar(stringResource(R.string.create_no_capability_notice))
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::save,
                enabled = !ui.saving && !ui.created,
                modifier = Modifier.weight(1f).height(50.dp),
            ) {
                Text(
                    when {
                        ui.created -> stringResource(R.string.create_done)
                        ui.saving -> stringResource(R.string.create_saving)
                        else -> stringResource(R.string.create_save)
                    },
                )
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel)) }
        }

        Spacer(Modifier.height(20.dp))
    }
}
