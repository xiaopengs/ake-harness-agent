package com.ake.ui.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.contract.TaskStatus
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.TaskStorePort
import com.ake.ui.model.TaskRow
import com.ake.ui.model.relativeTimeOf
import com.ake.ui.model.toRow
import com.ake.ui.runtime.AgentSessionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 任务中心的筛选。分组口径必须互斥且穷尽，否则「总数对不上」会比筛错更让人困惑。 */
public enum class TaskFilter(public val label: String) {
    ALL("全部"),
    ACTIVE("进行中"),
    DONE("已完成"),
    UNFINISHED("没跑完"),
    ;

    public fun matches(status: String): Boolean = when (this) {
        ALL -> true
        // 「进行中」含暂停与等待确认：对用户来说它们都还没结束，只是需要他做点什么
        ACTIVE -> TaskStatus.isActive(status) || TaskStatus.isSuspended(status)
        DONE -> status == TaskStatus.SUCCEEDED
        UNFINISHED -> TaskStatus.isTerminal(status) && status != TaskStatus.SUCCEEDED
    }
}

/** 任务中心的一屏状态。 */
public data class TaskListUi(
    val rows: List<TaskRow> = emptyList(),
    val filter: TaskFilter = TaskFilter.ALL,
    /** 每个筛选下有多少条。用于在 chip 上显示数量，避免用户点进去发现是空的。 */
    val counts: Map<TaskFilter, Int> = emptyMap(),
    /** 首次数据是否已到（Room Flow 第一条）。false 时显示骨架而不是"暂无任务"。 */
    val loaded: Boolean = false,
)

/**
 * 任务中心（五 tab 之一）。
 *
 * ## 数据为什么是 Flow 而不是一次性查询
 *
 * 任务状态由前台服务写、界面在另一个进程状态下读。用 `observeRecent`（Room Flow）意味着
 * **事务提交后自动重发**，用户停在任务列表上就能看见「第 3 步 → 第 4 步」。
 * 一次性查询只能靠轮询或手动下拉，而这正是旧版历史列表「跑完了列表里还显示运行中」的成因。
 *
 * ## 为什么 `resumable` 每发一次都重查
 *
 * 断点在 `task_checkpoint` 表，与 `task` 表没有外键联动 —— 任务被清理时断点可能先没了。
 * 只按 status 判断会给出「点了继续却恢复不了」的按钮。这次查询很轻（一张小表 + 主键索引），
 * 相对一次列表刷新可以忽略。
 */
@HiltViewModel
public class TaskListViewModel @Inject constructor(
    private val tasks: TaskStorePort,
    private val checkpoints: CheckpointStorePort,
    private val coordinator: AgentSessionCoordinator,
    private val clock: Clock,
) : ViewModel() {

    private val _filter = MutableStateFlow(TaskFilter.ALL)
    public val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    private val rows: StateFlow<List<TaskRow>?> = tasks.observeRecent(LIST_LIMIT)
        .map { recs ->
            val resumableIds = runCatching { checkpoints.findResumable() }
                .getOrDefault(emptyList())
                .mapTo(HashSet()) { it.value }
            recs.map { rec -> rec.toRow(resumable = rec.id.value in resumableIds) }
        }
        // 读失败不能把整页打成崩溃：任务列表是只读浏览，显示"读不出来"比闪退好
        .catch { emit(emptyList<TaskRow>()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    public val ui: StateFlow<TaskListUi> = combine(rows, _filter) { all, f ->
        if (all == null) {
            TaskListUi(filter = f, loaded = false)
        } else {
            TaskListUi(
                rows = all.filter { f.matches(it.status) },
                filter = f,
                counts = TaskFilter.entries.associateWith { candidate ->
                    all.count { candidate.matches(it.status) }
                },
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUi())

    public fun select(f: TaskFilter) {
        _filter.value = f
    }

    public fun resume(taskId: String) {
        viewModelScope.launch { coordinator.resume(taskId) }
    }

    public fun abandon(taskId: String) {
        viewModelScope.launch { coordinator.abandon(taskId) }
    }

    /** 「3 分钟前」。与首页共用同一口径，见 `ui/model/TaskRow.kt`。 */
    public fun relativeTime(atMillis: Long): String = relativeTimeOf(clock.nowMillis(), atMillis)

    public companion object {
        /**
         * 列表上限。
         *
         * 200 而不是 50：任务中心是**归档式浏览**，用户会往下翻找上周那份 PDF 的结果；
         * 首页的「继续上次」只需要 20 条。两者上限不同是刻意的，不是漏改。
         */
        public const val LIST_LIMIT: Int = 200
    }
}
