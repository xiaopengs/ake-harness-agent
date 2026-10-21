package com.ake.ui.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.contract.TaskPhase
import com.ake.core.contract.TaskStatus
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.LocalSettings
import com.ake.core.ports.SettingsStorePort
import com.ake.core.ports.TaskStorePort
import com.ake.ui.runtime.AgentSessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 历史任务列表里的一行。**已结束与已暂停都在同一张表里。 */
public data class HistoryTaskItem(
    val taskId: String,
    val goalPreview: String,
    /** 机器态（落库原值），仅供逻辑判定。显示请用 `statusLabelOf`。 */
    val status: String,
    val phase: TaskPhase,
    /** 已完成步数（= `TaskRecord.stepsDone`，恢复起点由 checkpoint 决定）。 */
    val stepsDone: Int,
    val lastActivityAt: Long,
    /**
     * 是否可以「点继续」。
     *
     * = 落在挂起态 **且** 断点真的还在库里。两个条件缺一不可：
     * 只看 status 会给出「暂停了但恢复不了」的按钮，只看断点会把已结束任务也列成可继续。
     */
    val resumable: Boolean,
    val terminalReason: String?,
)

/**
 * 主界面（任务下达）的 ViewModel。
 *
 * 只做三件事：收集目标文本、把它交给 [AgentSessionCoordinator]、列出历史任务。
 * 任何判定（能不能跑、预算多少、要不要确认）都不在这里 —— 那是 `agent` 与护栏的职责。
 *
 * ## ★ 历史任务为什么走 `TaskStorePort.recent()`
 *
 * 此前这张表是拿 `CheckpointStorePort.findResumable()` 反查出来的，
 * 于是**只有「本会话内暂停过、且断点还在」的任务才出现** —— 冷启动后历史页永远是空的，
 * 跑完的任务更是从来没进过列表。`recent()` 按 `last_activity_at DESC` 直读 task 表，
 * 与「有没有断点」无关，冷启动即可见。
 *
 * ★ 它**含未结束任务**（`TaskDao.recent` 不按 `finished_at` 过滤），
 * 这一点正是需求：暂停中的任务必须出现在历史里，否则用户想不起来回去点「继续」。
 */
@HiltViewModel
public class HomeViewModel @Inject constructor(
    private val coordinator: AgentSessionCoordinator,
    private val settings: SettingsStorePort,
    private val tasks: TaskStorePort,
    private val checkpoints: CheckpointStorePort,
    private val clock: Clock,
) : ViewModel() {

    public val session: StateFlow<com.ake.ui.model.TaskSessionUi> = coordinator.state

    public val localSettings: StateFlow<LocalSettings> =
        settings.values().stateIn(viewModelScope, SharingStarted.Eagerly, LocalSettings())

    private val _goal = MutableStateFlow("")
    public val goal: StateFlow<String> = _goal.asStateFlow()

    private val _startUrl = MutableStateFlow("")
    public val startUrl: StateFlow<String> = _startUrl.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryTaskItem>>(emptyList())
    public val history: StateFlow<List<HistoryTaskItem>> = _history.asStateFlow()

    init {
        viewModelScope.launch { refreshHistory() }
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
            refreshHistory()
        }
    }

    /**
     * 刷新历史任务列表。
     *
     * ## 一个必须知道的时序事实
     *
     * task 表的写走 `TraceBufferWriter`（200 ms 合并窗口），所以「刚结束立刻 recent()」
     * 有可能读不到最后一条状态。这不是 bug，是写入队列的既定代价 ——
     * 界面侧用「进页面时刷一次 + 动作后刷一次」覆盖，不做轮询补偿。
     * 真要「写完立刻看到」得用 `TaskStoreImpl.observeRecent()`（Room Flow 事务提交后自动重发）。
     */
    public fun refreshHistory() {
        viewModelScope.launch {
            val recs = runCatching { tasks.recent(HISTORY_LIMIT) }.getOrDefault(emptyList())
            val withCp = runCatching { checkpoints.findResumable() }
                .getOrDefault(emptyList())
                .mapTo(HashSet()) { it.value }

            _history.value = recs.map { rec ->
                val suspended = TaskStatus.isSuspended(rec.status)
                HistoryTaskItem(
                    taskId = rec.id.value,
                    goalPreview = rec.goalPreview ?: "（目标已清除）",
                    status = rec.status,
                    phase = TaskStatus.phaseOf(rec.status),
                    stepsDone = rec.stepsDone,
                    lastActivityAt = rec.lastActivityAt,
                    resumable = suspended && rec.id.value in withCp,
                    terminalReason = rec.terminalReason,
                )
            }
        }
    }

    /** 「3 分钟前」这类相对时间。历史列表要的是「多久以前」，不是时间戳。 */
    public fun relativeTime(atMillis: Long): String {
        if (atMillis <= 0L) return ""
        val diff = (clock.nowMillis() - atMillis).coerceAtLeast(0L)
        val min = diff / 60_000L
        return when {
            min < 1L -> "刚刚"
            min < 60L -> "${min} 分钟前"
            min < 60L * 24L -> "${min / 60L} 小时前"
            else -> "${min / (60L * 24L)} 天前"
        }
    }

    /** 任务规格里的白名单由设置页决定 —— 这里把「设置真的会影响行为」这件事坐实。 */
    public fun allowHosts(): Set<String> = localSettings.value.allowHosts.toSet()

    public fun taskIdOf(value: String): com.ake.core.common.TaskId = com.ake.core.common.TaskId(value)

    public companion object {
        /** 历史页只列最近这么多条 —— 它是「回去继续」的入口，不是归档浏览器。 */
        public const val HISTORY_LIMIT: Int = 20
    }
}
