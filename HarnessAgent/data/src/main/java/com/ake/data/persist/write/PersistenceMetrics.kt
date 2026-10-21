package com.ake.data.persist.write

import com.ake.core.common.AkeLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 写入侧观测（数据库 §6.7）。
 *
 * **关键 SLO（没有观测就没有优化）：**
 *  - flush p95 < 15 ms（超出说明 IO 被模型加载挤了，去看 governor）
 *  - **状态泳道** `append()`/submit 耗时 p99 < 1 ms（超出说明有人把它改成了阻塞调用）
 *  - **状态泳道** 队列深度常态为 0（非 0 是异常，不是流量）
 *  - **轨迹泳道** 每任务丢弃率 < 0.5%
 *
 * ## ★ 2026-10 P1：告警口径必须区分两条泳道
 *
 * 原实现的三个计数器（`droppedP0/P1/P2`）混在一起数，于是**丢掉一个 `TaskFinish`
 * 和丢掉一行 DEBUG 事件在 metric 上完全一样** —— 这正是缺陷能活着上线的原因之一。
 * 现在这两类数据走两条互不重叠的metric 命名空间：
 *
 * | 前缀 | 泳道 | 非零意味着 |
 * |---|---|---|
 * | `traceDropped*` | TRACE | **预期之内**（背压出口在正常工作），超过阈值要看 IO |
 * | `state*` | STATE | **事故**（`stateBacklog` 除外，它是降级信号） |
 *
 * 尤其注意 `droppedState`：它**恒为 0**，一旦非 0 就是 P0 bug（`TaskStateWriter` 里
 * 没有一条路径会丢数据，非 0 说明有人绕过泳道直接调了别的东西）。
 */
public class PersistenceMetrics {

    // ── 通用落盘 ────────────────────────────────
    @Volatile
    private var flushCount: Long = 0

    @Volatile
    private var flushRows: Long = 0

    @Volatile
    private var lastFlushMs: Long = 0

    // ── 轨迹泳道（允许丢） ────────────────────────
    @Volatile
    private var flushedTrace: Long = 0

    @Volatile
    private var droppedP1: Long = 0

    @Volatile
    private var droppedP2: Long = 0

    @Volatile
    private var traceFlushFailures: Long = 0

    /** 已自证的行数（写进了 `TRACE_DROPPED` 事件）。 */
    @Volatile
    private var dropAcks: Long = 0

    // ── 状态泳道（不可丢） ────────────────────────
    private val stateBacklog = AtomicLong(0)
    private val stateRetries = AtomicLong(0)
    private val stateFailures = AtomicLong(0)
    private val stateGiveUps = AtomicLong(0)

    /** 恒为 0。任何非 0 都是 P0 bug 的铁证。 */
    private val droppedState = AtomicLong(0)

    // ── 对账 ────────────────────────────────────
    private val sweeps = AtomicLong(0)
    private val sweepPaused = AtomicLong(0)
    private val sweepAborted = AtomicLong(0)
    private val sweepSkippedLive = AtomicLong(0)
    private val sweepRaced = AtomicLong(0)

    private val lastDropsByTask = ConcurrentHashMap<String, Long>()

    // ──────────────────────────────────────────── flush

    public fun beginFlush(): Long = System.nanoTime()

    public fun endFlush(startNanos: Long, rows: Int) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        flushCount++
        flushRows += rows
        lastFlushMs = ms
        // 15 ms 是 SLO。超出不抛异常（不该因为观测影响主链路），但要能在 logcat 里捞到。
        if (ms > SLO_FLUSH_MS) {
            AkeLog.w(TAG, "flush ${ms}ms exceeds SLO ${SLO_FLUSH_MS}ms (rows=$rows)")
        }
    }

    // ──────────────────────────────────────────── 轨迹泳道

    /**
     * 轨迹批次写入失败 → 整批丢弃。
     *
     * ★ 这是**预期之内**的降级路径，不是事故：轨迹行的价值低于主循环的流畅度。
     * 真正的事故在下面（状态泳道）。
     */
    public fun recordTraceFlushFailure(t: Throwable) {
        traceFlushFailures++
        AkeLog.w(TAG, "trace flush failed, batch dropped: ${t.message}")
    }

    /**
     * 一条轨迹 Op 被丢弃（队列满）。
     *
     * @param priority 只会是 P1 / P2 —— P0 已随状态泳道的独立而退役。
     */
    public fun recordTraceDrop(priority: Priority) {
        when (priority) {
            Priority.P1 -> droppedP1++
            Priority.P2 -> droppedP2++
        }
    }

    /** 自证事件真正写进事务时调用。**自证数应当始终等于丢弃数** —— 这是验收可以直接断言的恒等式。 */
    public fun recordTraceDropAck(taskId: String, priority: Priority, count: Int) {
        dropAcks += count
        lastDropsByTask.merge(taskId, count.toLong()) { old, add -> old + add }
        if (lastDropsByTask.size > LAST_DROP_MAX_TASKS) lastDropsByTask.clear()
    }

    public fun recordTraceFlush(rows: Int) {
        flushedTrace += rows
    }

    /** 真实队列深度。原来恒传 0，等于没有这个观测。 */
    public fun recordTraceQueueDepth(p1: Int, p2: Int) {
        if (p1 > QueueCaps.P1 * 3 / 4) AkeLog.w(TAG, "P1 trace queue near full: $p1/${QueueCaps.P1}")
        if (p2 > QueueCaps.P2 * 3 / 4) AkeLog.w(TAG, "P2 trace queue near full: $p2/${QueueCaps.P2}")
    }

    // ──────────────────────────────────────────── 状态泳道（事故口径）

    /** 降级信号（不是故障）：高水位触发，`submit` 进入等待槽位模式。 */
    public fun recordStateBacklog() {
        stateBacklog.incrementAndGet()
    }

    public fun recordStateRetry() {
        stateRetries.incrementAndGet()
    }

    public fun recordStateWriteFailure(t: Throwable) {
        stateFailures.incrementAndGet()
        AkeLog.e(TAG, "state write failed (will retry): ${t.message}")
    }

    /** 退避到上限仍写不进去。**兜底责任已转交 `TaskReconciler`**，不是静默丢失。 */
    public fun recordStateGiveUp(ops: Int) {
        stateGiveUps.addAndGet(ops.toLong())
        AkeLog.e(TAG, "state lane gave up on $ops op(s); TaskReconciler will converge their task rows")
    }

    /**
     * 记录一次「状态类写入被丢弃」。
     *
     * ★ **本 `TaskStateWriter` 实现里没有任何路径会调它。**
     * 留着它是为了两件事：① 如果以后有人加了绕道的写路径，这里就是它的测谎仪；
     * ② 验收可以直接断言「droppedState == 0」。
     */
    public fun recordStateDrop() {
        droppedState.incrementAndGet()
        AkeLog.e(TAG, "A STATE OP WAS DROPPED — this is a P0 bug, task state must never be discarded")
    }

    public fun recordStateQueueDepth(depth: Int) {
        if (depth > StateLaneCaps.CAPACITY / 2) {
            AkeLog.w(TAG, "state lane depth=$depth/${StateLaneCaps.CAPACITY} — writer thread starved or disk slow?")
        }
    }

    // ──────────────────────────────────────────── 对账

    /** 用基本类型而不是 `SweepReport`：观测层不反向依赖 recovery 包，只认数字。 */
    public fun recordSweep(paused: Int, aborted: Int) {
        sweeps.incrementAndGet()
        sweepPaused.addAndGet(paused.toLong())
        sweepAborted.addAndGet(aborted.toLong())
    }

    public fun recordSweepSkippedLive() {
        sweepSkippedLive.incrementAndGet()
    }

    /** candidate 在扫描与收敛之间自己收尾了 —— 好消息，只记数。 */
    public fun recordSweepRace(count: Int = 1) {
        sweepRaced.addAndGet(count.toLong())
    }

    // ──────────────────────────────────────────── 对外沉淀

    public fun snapshot(): Snapshot = Snapshot(
        flushCount = flushCount,
        flushRows = flushRows,
        lastFlushMs = lastFlushMs,
        traceRows = flushedTrace,
        droppedP1 = droppedP1,
        droppedP2 = droppedP2,
        traceFlushFailures = traceFlushFailures,
        dropAcks = dropAcks,
        stateBacklog = stateBacklog.get(),
        stateRetries = stateRetries.get(),
        stateFailures = stateFailures.get(),
        stateGiveUps = stateGiveUps.get(),
        droppedState = droppedState.get(),
        sweeps = sweeps.get(),
        sweepPaused = sweepPaused.get(),
        sweepAborted = sweepAborted.get(),
        sweepSkippedLive = sweepSkippedLive.get(),
        sweepRaced = sweepRaced.get(),
    )

    public data class Snapshot(
        val flushCount: Long,
        val flushRows: Long,
        val lastFlushMs: Long,
        val traceRows: Long,
        val droppedP1: Long,
        val droppedP2: Long,
        val traceFlushFailures: Long,
        val dropAcks: Long,
        val stateBacklog: Long,
        val stateRetries: Long,
        val stateFailures: Long,
        val stateGiveUps: Long,
        val droppedState: Long,
        val sweeps: Long,
        val sweepPaused: Long,
        val sweepAborted: Long,
        val sweepSkippedLive: Long,
        val sweepRaced: Long,
    ) {

        /** 丢弃行数 / (已写行数 + 丢弃行数)。 */
        val traceDropRate: Double
            get() {
                val dropped = droppedP1 + droppedP2
                val total = traceRows + dropped
                return if (total == 0L) 0.0 else dropped.toDouble() / total
            }

        /** ★ 红灯判定：任何一个非零都说明这一轮闯了祸，不该出现在健康设备上。 */
        val criticalAlerts: List<String>
            get() = buildList {
                if (droppedState > 0) add("droppedState=$droppedState (P0 bug: task state lost)")
                if (stateGiveUps > 0) add("stateGiveUps=$stateGiveUps (writes failed permanently)")
                if (stateFailures > 0) add("stateFailures=$stateFailures")
                if (traceFlushFailures > 0) add("traceFlushFailures=$traceFlushFailures (expected under load)")
                if (dropAcks < droppedP1 + droppedP2) add("drops not fully acknowledged (${droppedP1 + droppedP2 - dropAcks} missing)")
            }

        override fun toString(): String =
            "flushCount=$flushCount rows=$flushRows last=${lastFlushMs}ms trace=$traceRows " +
                "dropped(P1/P2)=$droppedP1/$droppedP2 acks=$dropAcks rate=${"%.4f".format(traceDropRate)} " +
                "state(backlog=$stateBacklog retry=$stateRetries fail=$stateFailures giveUp=$stateGiveUps dropped=$droppedState) " +
                "sweep(n=$sweeps paused=$sweepPaused aborted=$sweepAborted skipLive=$sweepSkippedLive race=$sweepRaced)"
    }

    public companion object {
        public const val TAG: String = "PersistenceMetrics"
        public const val SLO_FLUSH_MS: Long = 15L

        /** `lastDropsByTask` 的上界。它只是「最近谁丢得最多」的调试视图，不需要精确。 */
        public const val LAST_DROP_MAX_TASKS: Int = 256
    }
}
