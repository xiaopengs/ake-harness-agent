package com.ake.ui.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.TaskId
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.TaskStorePort
import com.ake.ui.runtime.AgentSessionCoordinator
import com.ake.ui.spi.UiLocalSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 历史 / 可恢复列表里的一行。 */
public data class ResumableTaskItem(
    val taskId: String,
    val goalPreview: String,
    val stepSeq: Int,
    val status: String,
)

/**
 * 主界面（任务下达）的 ViewModel。
 *
 * 只做三件事：收集目标文本、把它交给 [AgentSessionCoordinator]、列出「还能继续」的任务。
 * 任何判定（能不能跑、预算多少、要不要确认）都不在这里 —— 那是 `agent` 与护栏的职责。
 */
@HiltViewModel
public class HomeViewModel @Inject constructor(
    private val coordinator: AgentSessionCoordinator,
    private val settings: UiLocalSettings,
    private val tasks: TaskStorePort,
    private val checkpoints: CheckpointStorePort,
) : ViewModel() {

    public val session: StateFlow<com.ake.ui.model.TaskSessionUi> = coordinator.state

    public val localSettings: StateFlow<UiLocalSettings.Values> =
        settings.values.stateIn(viewModelScope, SharingStarted.Eagerly, UiLocalSettings.Values())

    private val _goal = MutableStateFlow("")
    public val goal: StateFlow<String> = _goal.asStateFlow()

    private val _startUrl = MutableStateFlow("")
    public val startUrl: StateFlow<String> = _startUrl.asStateFlow()

    private val _resumable = MutableStateFlow<List<ResumableTaskItem>>(emptyList())
    public val resumable: StateFlow<List<ResumableTaskItem>> = _resumable.asStateFlow()

    init {
        viewModelScope.launch {
            settings.refresh()
            refreshResumable()
        }
    }

    public fun onGoalChange(v: String): Unit { _goal.value = v }

    public fun onStartUrlChange(v: String): Unit { _startUrl.value = v }

    /** 发起执行。白名单与最大步数从设置页的真生效项里取。 */
    public fun start() {
        val g = _goal.value.trim()
        if (g.isBlank()) return
        val conf = localSettings.value
        coordinator.start(
            goal = g,
            startUrl = _startUrl.value,
            maxSteps = conf.maxSteps,
            allowHosts = conf.allowHosts.toSet(),
        )
    }

    /** 从暂停点继续（预算累计从断点带回，不是重跑）。 */
    public fun resume(taskId: String) {
        viewModelScope.launch { coordinator.resume(taskId) }
    }

    /** 放弃一个暂停中的任务：清除断点，让它彻底结束。 */
    public fun abandon(taskId: String) {
        viewModelScope.launch {
            coordinator.abandon(taskId)
            refreshResumable()
        }
    }

    public fun refreshResumable() {
        viewModelScope.launch {
            val ids = runCatching { checkpoints.findResumable() }.getOrDefault(emptyList())
            val items = ids.mapNotNull { id ->
                val rec = runCatching { tasks.load(id) }.getOrNull() ?: return@mapNotNull null
                val cp = runCatching { checkpoints.load(id) }.getOrNull()
                ResumableTaskItem(
                    taskId = id.value,
                    goalPreview = rec.goalPreview ?: "（目标已清除）",
                    stepSeq = cp?.currentStepSeq ?: rec.stepsDone,
                    status = rec.status,
                )
            }
            _resumable.value = items
        }
    }

    /** 任务规格里的白名单由设置页决定 —— 这里把「设置真的会影响行为」这件事坐实。 */
    public fun allowHosts(): Set<String> = localSettings.value.allowHosts.toSet()

    public fun taskIdOf(value: String): TaskId = TaskId(value)
}
