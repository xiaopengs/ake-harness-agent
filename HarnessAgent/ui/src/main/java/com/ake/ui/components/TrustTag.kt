package com.ake.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ake.core.ports.ProfileOrigin
import com.ake.core.ports.TrustScanVerdict
import com.ake.core.ports.TrustTier
import com.ake.ui.R
import com.ake.ui.theme.AkeColors

/**
 * Agent 的来源与信任徽标。
 *
 * ## 为什么「谁写的这个人设」必须挂在列表上
 *
 * 一个 Agent 的危险程度不取决于它说什么，而取决于**谁在它的 persona 里写了什么**。
 * `MARKET_QUARANTINED`（验签通过但静态扫描命中）在数据上完全可用，
 * 但它意味着"这个人设里有试图越权的话术"—— 用户点进去之前就该看到。
 *
 * 同理 `FLAGGED`：扫描命中的 Agent 每次技能调用都会被要求二次确认（§8.3 S4），
 * 这个事实必须可见，否则用户会以为"确认弹窗怎么这么多"。
 */
@Composable
public fun AgentTrustTags(
    origin: ProfileOrigin,
    trustTier: TrustTier,
    scanVerdict: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val (labelRes, color) = when (trustTier) {
            TrustTier.USER_AUTHORED -> R.string.agent_trust_user to MaterialTheme.colorScheme.outline
            TrustTier.BUILTIN -> R.string.agent_trust_builtin to AkeColors.RiskReadOnly
            TrustTier.MARKET_VERIFIED -> R.string.agent_trust_market to AkeColors.RiskPageMutating
            TrustTier.MARKET_QUARANTINED -> R.string.agent_trust_quarantined to AkeColors.RiskSensitive
        }
        Tag(text = stringResource(labelRes), color = color)

        // 来源与信任层级是两件事：模板实例化出来的人格，其正文仍然是"人写的"
        if (origin == ProfileOrigin.TEMPLATE_BUILTIN) {
            Tag(text = stringResource(R.string.agent_trust_builtin), color = AkeColors.RiskReadOnly)
        }

        when (scanVerdict) {
            TrustScanVerdict.FLAGGED, TrustScanVerdict.SANITIZED -> Tag(
                text = stringResource(R.string.agent_scan_flagged),
                color = AkeColors.RiskSensitive,
            )

            TrustScanVerdict.REFUSED -> Tag(
                text = stringResource(R.string.agent_scan_refused),
                color = AkeColors.RiskForbidden,
            )

            else -> Unit
        }
    }
}
