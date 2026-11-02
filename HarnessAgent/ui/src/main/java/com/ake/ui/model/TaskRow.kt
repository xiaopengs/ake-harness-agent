package com.ake.ui.model

import com.ake.core.contract.TaskPhase
import com.ake.core.contract.TaskRecord
import com.ake.core.contract.TaskStatus

/**
 * 任务列表的一行。**首页「历史任务」与任务中心的列表共用同一个模型。**
 *
 * ## 为什么要抽出来
 *
 * 这两个页面显示的是同一批数据，此前首页那份是 `HistoryTaskItem`。如果任务中心再写一份
 * 映射，就会出现「首页说可继续、任务中心说不可继续」这种同一个任务两种说法的经典事故 ——
 * 而 `resumable` 的判定条件（挂起态 **且** 断点还在）恰恰是最容易写漏的那种。
 *
 * @property status 落库原值，仅供逻辑判定；显示请用 [statusLabelOf]。
 * @property resumable 可点「继续」。= 挂起态 && 断点真的还在库里，两个条件缺一不可。
 */
public data class TaskRow(
    public val taskId: String,
    public val goalPreview: String,
    public val status: String,
    public val phase: TaskPhase,
    public val stepsDone: Int,
    public val lastActivityAt: Long,
    public val resumable: Boolean,
    public val terminalReason: String?,
)

/** [TaskRecord] → 列表行。`resumable` 由调用方查断点后传入，这里不做 IO。 */
public fun TaskRecord.toRow(resumable: Boolean): TaskRow = TaskRow(
    taskId = id.value,
    goalPreview = goalPreview?.takeIf { it.isNotBlank() } ?: "（目标已清除）",
    status = status,
    phase = TaskStatus.phaseOf(status),
    stepsDone = stepsDone,
    lastActivityAt = lastActivityAt,
    resumable = TaskStatus.isSuspended(status) && resumable,
    terminalReason = terminalReason,
)

/**
 * 「3 分钟前」这类相对时间。
 *
 * 放在这里而不是各个 ViewModel 里，是因为**同一屏里出现两种时间口径**（一个"3 分钟前"、
 * 一个"14:05"）比时间不准更让人困惑。阈值取整：<1 分钟说"刚刚"，不显示"0 分钟前"。
 */
public fun relativeTimeOf(nowMillis: Long, atMillis: Long): String {
    if (atMillis <= 0L) return ""
    val diff = (nowMillis - atMillis).coerceAtLeast(0L)
    val min = diff / 60_000L
    return when {
        min < 1L -> "刚刚"
        min < 60L -> "$min 分钟前"
        min < 60L * 24L -> "${min / 60L} 小时前"
        else -> "${min / (60L * 24L)} 天前"
    }
}
