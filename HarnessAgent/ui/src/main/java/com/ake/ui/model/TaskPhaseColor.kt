package com.ake.ui.model

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ake.core.contract.TaskPhase
import com.ake.core.contract.TaskStatus
import com.ake.ui.theme.AkeColors

/**
 * 相位 → 语义色。
 *
 * 首页历史、任务中心、任务详情顶部相位条共用这一处 —— 同一个状态在两个页面两种颜色
 * 是用户最容易当成"两个不同的东西"的歧义来源。
 */
@Composable
public fun phaseColorOf(status: String, phase: TaskPhase): Color = when (phase) {
    TaskPhase.ACTIVE -> AkeColors.PhaseActive
    TaskPhase.SUSPENDED -> AkeColors.PhaseSuspended
    TaskPhase.TERMINAL -> if (status == TaskStatus.SUCCEEDED) {
        AkeColors.PhaseTerminalOk
    } else {
        AkeColors.PhaseTerminalWarn
    }
}
