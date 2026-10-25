package com.ake.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ake.core.contract.RiskClass
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.SkillConfirmPolicy
import com.ake.core.ports.SkillCostClass
import com.ake.core.ports.SkillSpec
import com.ake.ui.R
import com.ake.ui.theme.AkeColors

/**
 * 选择器里的一行。
 *
 * ## 为什么不直接用 `SkillSpec`
 *
 * `SkillSpec` 来自 `SkillRegistryPort`，而该 Port 目前**没有 Hilt 绑定**。
 * 但创建 Agent 这件事不能因此停摆：模板自带 `suggestedCapabilities`（一串
 * `CapabilityRef`），用户至少该看见"这个模板建议开哪些能力"并取消其中某几条。
 *
 * 所以 UI 层用这个"厚薄皆可"的投影：有 `SkillSpec` 就带全描述与风险等级，
 * 只有 `CapabilityRef` 就只显示 id。**两者的勾选与上限判定完全一样** ——
 * 上限是 `count(refs) ≤ 6`，跟有没有描述无关。
 */
public data class CapabilityOption(
    val ref: CapabilityRef,
    /** 有 SkillSpec 时是它的中文名，否则退回 ref.id。 */
    val name: String,
    val description: String,
    /** `RiskClass.name`；`null` 表示注册表没接线、风险未知（界面上必须显式说"未分级"）。 */
    val riskClass: String?,
    /** 即时 / 长任务；未知为 `null`。 */
    val costClass: SkillCostClass?,
    /** 确认策略；未知为 `null`。 */
    val confirmPolicy: SkillConfirmPolicy?,
) {
    val forbidden: Boolean get() = riskClass == RiskClass.FORBIDDEN.name
}

public fun SkillSpec.toOption(): CapabilityOption = CapabilityOption(
    ref = ref,
    name = name,
    description = description,
    riskClass = riskClass.name,
    costClass = costClass,
    confirmPolicy = confirmPolicy,
)

/**
 * 只有引用、没有规格时的投影。
 *
 * ★ 不编造描述：`description` 留空，界面上就只显示 id。宁可让用户看到
 * `builtin.browser_task` 这种略硬的名字，也不要写一句"帮你操作浏览器"
 * —— 那句我们没从注册表里读到，是编的。
 */
public fun CapabilityRef.toOption(): CapabilityOption = CapabilityOption(
    ref = this,
    name = this.id,
    description = "",
    riskClass = null,
    costClass = null,
    confirmPolicy = null,
)

/**
 * 扩展能力多选（上限 `limit`，默认 6）。
 *
 * ## 上限为什么必须在界面上"看得见"
 *
 * `SYSTEM` 段给 tools 的配额只有 1,024 token（`docs/05` §5.4），所以 6 不是审美上限，
 * 是**上下文装不下**的物理上限。设计文档原来写的是"超限静默截断 + WARN" ——
 * 那是后端的正确做法，但界面上不行：用户勾了第 7 个、点了保存、回来发现少了一个，
 * 会以为是自己记错了，或者以为是 bug。
 *
 * 这里的做法是：**满了就把没勾的置灰，并在上面直接说明"先取消一个"。**
 * 不静默截断，也不弹窗打断 —— 用户此刻正在做选择，任何弹出层都会打断这个动作。
 *
 * ## 为什么 FORBIDDEN 不给勾
 *
 * 三级授权里的第 ③ 条是"护栏硬禁止、不可撤销"。让它出现在可勾列表里，
 * 就等于暗示"理论上你可以开"——那是一条骗人的选项。
 */
@Composable
public fun CapabilityPicker(
    options: List<CapabilityOption>,
    selected: Set<CapabilityRef>,
    limit: Int,
    onToggle: (CapabilityRef, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    unavailableHint: String? = null,
) {
    Column(modifier) {
        if (unavailableHint != null) {
            AlertBar(unavailableHint, color = AkeColors.RiskSensitive)
            Spacer(Modifier.height(8.dp))
        }

        if (options.isEmpty()) {
            EmptyHint(stringResource(R.string.cap_empty))
            return
        }

        val atLimit = selected.size >= limit
        if (atLimit) {
            Text(
                text = stringResource(R.string.cap_limit_reached, limit),
                style = MaterialTheme.typography.labelSmall,
                color = AkeColors.RiskSensitive,
            )
            Spacer(Modifier.height(6.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                val isSelected = option.ref in selected
                // 已选中的永远可点（取消不受上限约束），未选中的在满额后置灰
                val checkable = !option.forbidden && (isSelected || !atLimit)
                CapabilityRow(
                    option = option,
                    checked = isSelected,
                    enabled = checkable,
                    onToggle = { onToggle(option.ref, !isSelected) },
                )
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    option: CapabilityOption,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(
                text = option.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // 置灰用颜色而不是整块 alpha：文字仍要能读，用户才知道自己错过了什么
                color = if (enabled || checked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (option.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(kindLabelOf(option.ref.kind), MaterialTheme.colorScheme.outline)
                Tag(riskLabelOf(option.riskClass), riskColorOf(option.riskClass))
                option.costClass?.let { Tag(costLabelOf(it), AkeColors.RiskPageMutating) }
                option.confirmPolicy?.let { policy ->
                    if (policy != SkillConfirmPolicy.NEVER) {
                        Tag(confirmLabelOf(policy), AkeColors.RiskSensitive)
                    }
                }
            }
            if (option.forbidden) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.cap_forbidden),
                    style = MaterialTheme.typography.labelSmall,
                    color = AkeColors.RiskForbidden,
                )
            }
        }
    }
}

@Composable
private fun kindLabelOf(kind: CapabilityKind): String = when (kind) {
    CapabilityKind.BUILTIN -> stringResource(R.string.cap_kind_builtin)
    CapabilityKind.MCP_TOOL -> stringResource(R.string.cap_kind_mcp)
    CapabilityKind.PLUGIN -> stringResource(R.string.cap_kind_plugin)
}

@Composable
private fun costLabelOf(cost: SkillCostClass): String = when (cost) {
    SkillCostClass.CHEAP_SYNC -> stringResource(R.string.cap_cost_cheap)
    SkillCostClass.LONG_RUNNING -> stringResource(R.string.cap_cost_long)
}

@Composable
private fun confirmLabelOf(policy: SkillConfirmPolicy): String = when (policy) {
    SkillConfirmPolicy.NEVER -> stringResource(R.string.cap_confirm_never)
    SkillConfirmPolicy.FIRST_USE_PER_AGENT -> stringResource(R.string.cap_confirm_first)
    SkillConfirmPolicy.ALWAYS -> stringResource(R.string.cap_confirm_always)
}
