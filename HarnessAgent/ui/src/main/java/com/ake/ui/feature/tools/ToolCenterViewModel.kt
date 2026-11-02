package com.ake.ui.feature.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.contract.RiskClass
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.ToolRegistryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 工具中心的一屏状态。 */
public data class ToolCenterUi(
    val local: List<ToolSpec> = emptyList(),
    val mcp: List<ToolSpec> = emptyList(),
    val loaded: Boolean = false,
) {
    public val total: Int get() = local.size + mcp.size
    public val enabledCount: Int get() = (local + mcp).count { it.enabled }

    /** 「有 2 个工具需要你确认」。只统计会改变页面或产生副作用的 —— 只读工具不该吓人。 */
    public val needsConfirm: Int
        get() = (local + mcp).count {
            it.enabled && it.riskClass in setOf(
                RiskClass.PAGE_MUTATING,
                RiskClass.SENSITIVE,
                RiskClass.EXTERNAL_SIDE_EFFECT,
            )
        }
}

/**
 * 工具中心（五 tab 之一）。**只读**。
 *
 * ## 为什么本期不做「在这里直接开关工具」
 *
 * 开关一个工具等于改一条安全边界，而 `ToolRegistryPort.enable()` 的语义是
 * "让模型可以调用它"。在没有「这个工具上次被谁用过、用过几次、结果如何」的可查记录之前，
 * 给一个一键开关就是在鼓励用户瞎点，然后把责任留给策略引擎。
 *
 * 本期只回答三个问题：**有哪些工具、各自能干什么、哪些会动你的东西**。
 * 真正的授权发生在任务执行时（策略引擎判定 + 人工确认门），那才是上下文完整的位置。
 */
@HiltViewModel
public class ToolCenterViewModel @Inject constructor(
    registry: ToolRegistryPort,
) : ViewModel() {

    public val ui: StateFlow<ToolCenterUi> = registry.catalog()
        .map { specs ->
            ToolCenterUi(
                local = specs.filter { it.origin == ToolOrigin.LOCAL }.sortedBy { it.name },
                mcp = specs.filter { it.origin == ToolOrigin.MCP }.sortedBy { it.name },
                loaded = true,
            )
        }
        // 注册表读不出来时显示空列表 + 顶部提示，而不是让整页崩掉
        .catch { emit(ToolCenterUi(loaded = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolCenterUi())
}
