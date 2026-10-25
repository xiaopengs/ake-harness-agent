package com.ake.ui.feature.agent

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ake.core.ports.AgentTemplate
import com.ake.core.ports.TemplateSource
import com.ake.ui.R
import com.ake.ui.components.AgentAvatar
import com.ake.ui.components.AlertBar
import com.ake.ui.components.EmptyHint
import com.ake.ui.components.SkeletonLine
import com.ake.ui.components.Tag
import com.ake.ui.theme.AkeColors
import com.ake.ui.util.collectAsStateWithLifecycle

/**
 * 模板市场。
 *
 * ## 卡片上一定有「合规声明」这一栏
 *
 * 投资顾问与金牌法务这两个模板带 `disclaimer`，它会被拼进 SYSTEM 段尾部且**不可编辑**。
 * 用户在选模板时就该看到"这个角色会一直带着一句免责声明"——
 * 等他创建完再在详情页发现，感受是"你偷偷加了东西"。
 *
 * ## 人格正文默认折叠
 *
 * 5 个模板的 charter 每个都有 200–400 字。全展开会让页面变成一堵墙，
 * 而用户此刻要做的判断是"这个角色是不是我要的"，一句话摘要就够。
 * 想细看的人点开 —— 这跟技能卡片是同一套折叠逻辑（`rememberSaveable` + `callId`/`templateId`）。
 */
@Composable
public fun TemplatePickerScreen(
    onBack: () -> Unit,
    onUseTemplate: (String) -> Unit,
    viewModel: TemplatePickerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                    Text("‹ " + stringResource(R.string.common_back))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.tpl_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.tpl_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.tpl_market_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ui.errorText?.let { err ->
            Column(Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) {
                AlertBar(stringResource(R.string.tpl_error, err))
            }
        }

        when {
            ui.loading -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { SkeletonLine(Modifier.fillMaxWidth().height(96.dp)) }
            }

            ui.templates.isEmpty() -> EmptyHint(stringResource(R.string.tpl_empty))

            else -> LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = ui.templates, key = { it.templateId }) { t ->
                    TemplateCard(template = t, onUse = { onUseTemplate(t.templateId) })
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: AgentTemplate,
    onUse: () -> Unit,
) {
    var expanded by rememberSaveable(template.templateId) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(name = template.name, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = template.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Tag(
                    text = stringResource(
                        if (template.trust.source == TemplateSource.BUILTIN) {
                            R.string.tpl_source_builtin
                        } else {
                            R.string.tpl_source_installed
                        },
                    ),
                    color = AkeColors.RiskReadOnly,
                )
            }

            if (template.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    template.tags.forEach { tag ->
                        Tag(text = tag, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            if (template.suggestedCapabilities.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.tpl_suggested_caps,
                        template.suggestedCapabilities.joinToString("、") { it.id },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 合规声明：不可编辑，必须提前看到
            template.disclaimer?.let { d ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tpl_disclaimer_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = AkeColors.RiskSensitive,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = d,
                    style = MaterialTheme.typography.bodySmall,
                    color = AkeColors.RiskSensitive,
                )
            }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.common_collapse else R.string.common_expand,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = stringResource(R.string.tpl_charter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(template.charter, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tpl_use))
            }
        }
    }
}
