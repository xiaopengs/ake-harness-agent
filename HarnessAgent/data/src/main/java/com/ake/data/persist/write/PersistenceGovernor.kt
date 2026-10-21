package com.ake.data.persist.write

import com.ake.core.common.AkeLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 写入让路控制器（数据库 §6.5）。
 *
 * ## 为什么需要它
 *
 * 进程里有两个推理相关的 IO 尖峰，持久化必须给它们让路：
 *  ① **模型下载**：2.58 GB 顺序写入；
 *  ② **模型加载**：2.58 GB 读入 + GPU 权重搬运，`initialize()` 可能耗时 10 秒。
 *
 * ## 取舍方向不能搞反
 *
 * **宁可丢掉几行轨迹，也不能让模型加载变慢或失败。**
 * 两者权重完全不对等 —— 前者损失的是诊断信息，后者损失的是用户能不能用这个 App。
 */
public class PersistenceGovernor {

    enum class Throttle { NONE, HALF, FULL }

    private val _throttle = MutableStateFlow(Throttle.NONE)
    val throttle: StateFlow<Throttle> = _throttle.asStateFlow()

    @Volatile
    private var maintenanceOpen: Boolean = true

    fun onModelDownloadStarted() {
        _throttle.value = Throttle.HALF
    }

    fun onModelLoadStarted() {
        _throttle.value = Throttle.FULL
        maintenanceOpen = false
    }

    fun onModelReady() {
        _throttle.value = Throttle.NONE
        maintenanceOpen = true
    }

    /** 清理作业（RetentionJob）的闸门。模型加载期间不许跑 —— 它会发 fsync，可能造成数百毫秒抖动。 */
    fun isMaintenanceAllowed(): Boolean = maintenanceOpen

    suspend fun yieldForIoBudget() {
        when (_throttle.value) {
            Throttle.NONE -> Unit
            Throttle.HALF -> delay(50)
            Throttle.FULL -> delay(200)
        }
    }
}

/**
 * 写入侧观测（数据库 §6.7）。
 *
 * **关键 SLO（没有观测就没有优化）：**
 *  - flush p95 < 15 ms（超出说明 IO 被模型加载挤了，去看 governor）
 *  - append() 耗时 p99 < 1 ms（超出说明有人把它改成了挂起/阻塞调用）
 *  - p0 队列深度恒为 0（非 0 是 bug 级告警）
 *  - 每任务丢弃率 < 0.5%
 */
public class PersistenceMetrics {

    @Volatile
    private var flushCount: Long = 0

    @Volatile
    private var flushRows: Long = 0

    @Volatile
    private var flushFailures: Long = 0

    @Volatile
    private var lastFlushMs: Long = 0

    @Volatile
    private var droppedP0: Long = 0

    @Volatile
    private var droppedP1: Long = 0

    @Volatile
    private var droppedP2: Long = 0

    fun beginFlush(): Long = System.nanoTime()

    fun endFlush(startNanos: Long, rows: Int) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        flushCount++
        flushRows += rows
        lastFlushMs = ms
        // 15 ms 是 SLO。超出不抛异常（不该因为观测影响主链路），但要能在 logcat 里捞到。
        if (ms > SLO_FLUSH_MS) {
            AkeLog.w(TAG, "flush ${ms}ms exceeds SLO ${SLO_FLUSH_MS}ms (rows=$rows)")
        }
    }

    fun recordFlushFailure(t: Throwable) {
        flushFailures++
        AkeLog.w(TAG, "flush failed: ${t.message}")
    }

    fun recordDrop(priority: Priority) {
        when (priority) {
            Priority.P0 -> droppedP0++
            Priority.P1 -> droppedP1++
            Priority.P2 -> droppedP2++
        }
    }

    fun recordQueueDepth(p0: Int, p1: Int, p2: Int) {
        if (p0 > 0) AkeLog.w(TAG, "P0 queue depth=$p0 — this is a bug: P0 must never back up")
        if (p1 > QueueCaps.P1 * 3 / 4) AkeLog.w(TAG, "P1 queue near full: $p1/${QueueCaps.P1}")
        if (p2 > QueueCaps.P2 * 3 / 4) AkeLog.w(TAG, "P2 queue near full: $p2/${QueueCaps.P2}")
    }

    fun snapshot(): Snapshot = Snapshot(
        flushCount = flushCount,
        flushRows = flushRows,
        flushFailures = flushFailures,
        lastFlushMs = lastFlushMs,
        droppedP0 = droppedP0,
        droppedP1 = droppedP1,
        droppedP2 = droppedP2,
    )

    data class Snapshot(
        val flushCount: Long,
        val flushRows: Long,
        val flushFailures: Long,
        val lastFlushMs: Long,
        val droppedP0: Long,
        val droppedP1: Long,
        val droppedP2: Long,
    ) {
        val dropRate: Double
            get() {
                val total = flushRows + droppedP0 + droppedP1 + droppedP2
                return if (total == 0L) 0.0 else (droppedP0 + droppedP1 + droppedP2).toDouble() / total
            }
    }

    companion object {
        const val TAG: String = "PersistenceMetrics"
        const val SLO_FLUSH_MS: Long = 15L
    }
}
