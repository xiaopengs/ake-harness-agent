package com.ake.ui.feature.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.ui.runtime.AgentSessionCoordinator
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.spi.UiLocalSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 执行过程可视化页的 ViewModel。
 *
 * 它**不消费任何 Room / MCP / LiteRT 类型**，只读 `AgentSessionCoordinator` 归约出来的
 * `TaskSessionUi` 与 `UiHumanGate` 的挂起请求 —— 这两处已经是 `core:contract` 的形状。
 */
@HiltViewModel
public class TaskViewModel @Inject constructor(
    private val coordinator: AgentSessionCoordinator,
    private val gate: UiHumanGate,
    private val settings: UiLocalSettings,
) : ViewModel() {

    public val session: StateFlow<com.ake.ui.model.TaskSessionUi> = coordinator.state

    /** 挂起中的确认请求。非空即应弹窗 —— Loop 此刻正挂在这一行上。 */
    public val pendingGate: StateFlow<UiHumanGate.PendingGate?> = gate.pending

    public val localSettings: StateFlow<UiLocalSettings.Values> =
        settings.values.stateIn(viewModelScope, SharingStarted.Eagerly, UiLocalSettings.Values())

    private val _domPayload = MutableStateFlow<String?>(null)
    public val domPayload: StateFlow<String?> = _domPayload.asStateFlow()

    init {
        viewModelScope.launch { settings.refresh() }
        coordinator.refreshResumable()
    }

    /** 拉取某一步 Agent 真正看到的 DOM 原文（不是 UI 猜的）。 */
    public fun loadDom(observationId: String) {
        viewModelScope.launch {
            _domPayload.value = coordinator.domPayloadOf(observationId)
        }
    }

    public fun dismissDom(): Unit { _domPayload.value = null }

    // ── 人工裁决：四个分支与 `Resolved` 一一对应 ──

    public fun allowOnce(): Unit = gate.allowOnce()

    public fun allowForThisTask(): Unit = gate.allowForThisTask()

    public fun allowAlways() {
        viewModelScope.launch { gate.allowAlways() }
    }

    public fun amendNoSubmit(): Unit = gate.amendDropSubmit()

    public fun reject(): Unit = gate.reject()

    public fun abortGate(): Unit = gate.abort()

    public fun isGrantedInSession(reasonKey: String): Boolean = gate.isGrantedInSession(reasonKey)

    // ── 任务控制 ──

    public fun abort(): Unit = coordinator.abort()

    public fun resume(): Unit {
        val id = session.value.taskId ?: return
        coordinator.resume(id)
    }

    public fun abandon(): Unit {
        val id = session.value.taskId ?: return
        coordinator.abandon(id)
    }

    public fun dismiss(): Unit = coordinator.dismiss()

    /** 供 UI 判断「继续」按钮是否可点：暂停了但断点没存住时不给点。 */
    public fun canResume(): Boolean {
        val s = session.value.runState
        return s is com.ake.ui.model.RunState.Suspended && s.suspension.checkpointSaved
    }
}
