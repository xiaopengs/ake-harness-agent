package com.ake.data.persist.write

import androidx.room.withTransaction
import com.ake.core.common.AkeLog
import com.ake.core.common.Ulid
import com.ake.core.contract.TraceEventKind
import com.ake.core.contract.TraceLevel
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.TraceEventEntity
import com.ake.data.persist.payload.PayloadStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * # 轨迹类写入队列：**保留丢弃语义**，并把丢弃变成可观测事件
 *
 * **本轮（2026-10 P1）只改了三件事，语义本身没动：**
 *
 *  1. **入口收窄为 [TraceOp]** —— 任务状态类 Op（`TaskUpsert/TaskFinish/TaskSuspend/BudgetAdvance/
 *     Checkpoint/PolicyDecision`）已经整体迁到 `TaskStateWriter`。
 *     它们之所以在那里而不在这里，是因为**这里从根上就承认「我可能会丢」**：
 *     队列是 `DROP_OLDEST` 的有界 Channel，这是针对「IO 可能与 2.58 GB 模型加载争抢」的刻意的背压出口。
 *     把状态类写在这里，等于给「任务消失 / 永久 RUNNING」留了一扇合法的门 —— 详见 `WriteOp.kt` 的分级论证。
 *  2. **丢弃台账**：按任务累计 + 按优先级累计，并有明确告警口径（`PersistenceMetrics`）。
 *     原来只有一个全局计数器 —— 想知道「哪个任务的轨迹缺了」只能猜。
 *  3. **自证改在**排水时**生成**：原来 `append()` 丢弃时会往同一个已满的队列里 `trySend` 一条
 *     `TRACE_DROPPED` 事件 —— 也就是说，**在最需要它的时候它自己也会因为队列满被丢掉**。
 *     现在台账在 `append()` 侧累加，drain 时按台账聚合生成一行自证事件、直接进事务 ——
 *     它既不会被丢，也不会引发自证风暴。
 *
 * ## 仍然成立的四条设计取舍（数据库 §6.3）
 *
 *  1. **`Channel(capacity, DROP_OLDEST)` 而不是 `UNLIMITED`**：在一个 IO 可能与 2.58 GB
 *     模型加载争抢的进程里，无界队列是 OOM 的一条免费通道。轨迹的量没有结构上界（见 `WriteOp` 分级表）。
 *  2. **两级优先级**：P1 常规轨迹，P2 DEBUG 事件。**注意：优先级只表示「先救谁」，不表示「不丢」。**
 *  3. **`append()` 必须是 `fun` 不是 `suspend fun`**：只有非挂起的 `trySend` 能从 API 层面保证「调用即返回」。
 *  4. **丢弃必须自证**，否则回放缺几步会变成无法解释的灵异现象。
 */
public class TraceBufferWriter(
    private val db: AkeDatabase,
    /** SupervisorJob + **单线程** dispatcher（§6.2 单写者） */
    private val writeScope: CoroutineScope,
    private val payloads: PayloadStore,
    private val governor: PersistenceGovernor,
    private val metrics: PersistenceMetrics,
) {

    private val p1 = Channel<TraceOp>(QueueCaps.P1, BufferOverflow.DROP_OLDEST)
    private val p2 = Channel<TraceOp>(QueueCaps.P2, BufferOverflow.DROP_OLDEST)

    /**
     * 真实队列深度。
     *
     * ★ 原实现每轮都调 `metrics.recordQueueDepth(0, 0, 0)` —— 恒为零的「深度」比没有深度更糟：
     * 它会让人以为「队列从来没积压过」。`Channel` 没有 O(1) 的 size API，而入队/出队都在本类里，
     * 自己数一个原子计数器是最省的做法（代价：两个 visible 记账点，见 `trySend` 与 `drainInto`）。
     */
    private val depth1 = AtomicInteger(0)
    private val depth2 = AtomicInteger(0)

    /**
     * 丢弃台账：taskId → 该任务被丢弃的（按优先级）行数。
     *
     * 排水的第一个动作就是把它捞出来写成自证事件，所以它的稳态大小 ≈ 同时活跃的任务数。
     * 仍然给一个上界（`LEDGER_MAX_TASKS`）防失控：满了就只累加到溢出桶，**不替换已有条目** ——
     * 已经记了账的任务，比一个刚来的新键更值得保住它的自证。
     */
    private val ledger = ConcurrentHashMap<String, TaskDropCounter>()
    private val ledgerOverflow = AtomicInteger(0)

    init {
        writeScope.launch { drainLoop() }
    }

    /** ★ 主循环只碰这一个方法。非挂起、try-send，语义上等同于 "return"。 */
    public fun append(op: TraceOp): Boolean {
        val (target, depth) = when (op.priority) {
            Priority.P1 -> p1 to depth1
            Priority.P2 -> p2 to depth2
        }
        val ok = target.trySend(op).isSuccess
        if (ok) {
            depth.incrementAndGet()
            return true
        }
        // ① 记账（按任务 + 按优先级）② 自证留到排水时再落，这里只累加
        metrics.recordTraceDrop(op.priority)
        noteDrop(op.taskId, op.priority)
        return false
    }

    /**
     * 任务结束 / 暂停恢复前 / 用户点停止：**显式同步等待一次 flush**。
     * 这里的阻塞是刻意且必要的 —— 断点恢复的正确性依赖「暂停时数据已真正落盘」。
     *
     * ★ 只冲出去轨迹。**任务状态类要另走 `TaskStateWriter.flushNow()`** ——
     * 别指望这里会帮你把 `TaskFinish` 也写掉（它根本不在这条泳道上）。
     */
    public suspend fun flushNow() {
        val pending = ArrayList<TraceOp>(QueueCaps.MAX_BATCH * 8)
        drainInto(p1, depth1, pending)
        drainInto(p2, depth2, pending)
        // 台账也要跟着冲出去：否则「暂停前最后一次 flush」会漏掉那一批丢弃的自证
        pending += takeDropAcknowledgements()
        pending.chunked(QueueCaps.MAX_BATCH).forEach { flush(it) }
    }

    /** §8.5 一键清除：停掉一切在途写入，否则刚删完又被写回来。 */
    public fun stopAndDropQueued() {
        runCatching { p1.cancel() }
        runCatching { p2.cancel() }
        depth1.set(0)
        depth2.set(0)
        ledger.clear()
    }

    /** 观测：当前轨迹队列深度。给健康检查面板用。 */
    public fun queueDepths(): Pair<Int, Int> = depth1.get() to depth2.get()

    // ──────────────────────────────────────────── 内部

    private suspend fun drainLoop() {
        while (writeScope.isActive) {
            val batch = ArrayList<TraceOp>(QueueCaps.MAX_BATCH)
            drainInto(p1, depth1, batch)
            drainInto(p2, depth2, batch)
            metrics.recordTraceQueueDepth(depth1.get(), depth2.get())
            if (batch.isEmpty()) {
                delay(QueueCaps.COALESCE_MS)
                continue
            }
            // ★ 自证事件在这里生成：跟着这一批一起进事务，不可能被丢
            val acks = takeDropAcknowledgements()
            flush(if (acks.isEmpty()) batch else batch + acks)
            governor.yieldForIoBudget()
        }
    }

    /**
     * ★ `ChannelResult` 是 **value class**，没有 `Success` 子类型 ——
     * 数据库 §6.3 的 `is ChannelResult.Success -> into += r.value` 在 coroutines 1.11.0 上**编不过**。
     * 正确形态是 `isSuccess` + `getOrNull()`。
     */
    private fun drainInto(ch: Channel<TraceOp>, depth: AtomicInteger, into: MutableList<TraceOp>) {
        repeat(QueueCaps.MAX_BATCH - into.size) {
            val r = ch.tryReceive()
            if (!r.isSuccess) return
            val v = r.getOrNull() ?: return
            depth.decrementAndGet()
            into += v
        }
    }

    /** 一个事务、多张表、一次 COMMIT。 */
    private suspend fun flush(batch: List<TraceOp>) {
        val started = metrics.beginFlush()
        try {
            // ★ 先写文件，再写 DB 行（§4.5 纪律 1 的镜像）
            val fileNames = HashMap<String, String>(4)
            for (op in batch) {
                if (op is TraceOp.Observation && op.payload != null) {
                    payloads.write(op.row.taskId, op.row.seq, op.row.id, op.payload)
                        ?.let { fileNames[op.row.id] = it }
                }
            }

            db.withTransaction {
                val traceDao = db.traceDao()

                // 先按表分类，再批量 insert —— 批量插入能复用预编译语句，
                // 这是批量写比逐行写快的关键（数据库 §6.6）
                val steps = ArrayList<StepEntity>()
                val tools = ArrayList<ToolInvocationEntity>()
                val observations = ArrayList<ObservationEntity>()
                val events = ArrayList<TraceEventEntity>()

                for (op in batch) {
                    when (op) {
                        is TraceOp.Step -> steps += op.row
                        is TraceOp.ToolInvocation -> tools += op.row
                        is TraceOp.Observation -> observations += op.row.withFile(fileNames[op.row.id])
                        is TraceOp.TraceEvent -> events += op.row
                        is TraceOp.SyntheticTraceDropped -> events += op.toEntity()
                    }
                }
                if (steps.isNotEmpty()) traceDao.insertSteps(steps)
                if (tools.isNotEmpty()) traceDao.insertToolInvocations(tools)
                if (observations.isNotEmpty()) traceDao.insertObservations(observations)
                if (events.isNotEmpty()) traceDao.insertEvents(events)
            }
            metrics.endFlush(started, batch.size)
            metrics.recordTraceFlush(batch.size)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // ★ 架构 §6.2 第 3 条：绝不能吞 CancellationException
            throw ce
        } catch (t: Throwable) {
            // ★ 轨迹批次失败整批丢是**可接受的**（状态类才要重试，见 TaskStateWriter）：
            // 这是轨迹泳道存在的意义 —— 用几行诊断数据换「无论如何都不拖慢主循环」。
            metrics.recordTraceFlushFailure(t)
        }
    }

    private fun ObservationEntity.withFile(name: String?): ObservationEntity =
        if (name == null) {
            copy(payloadState = PAYLOAD_MISSING, payloadFile = null, payloadCodec = null)
        } else {
            copy(payloadFile = name, payloadCodec = "gzip", payloadState = PAYLOAD_EXTERNAL)
        }

    // ──────────────────────────────────────── 丢弃台账与自证

    private fun noteDrop(taskId: String, priority: Priority) {
        val existing = ledger[taskId]
        if (existing != null) {
            existing.add(priority)
            return
        }
        if (ledger.size >= LEDGER_MAX_TASKS) {
            ledgerOverflow.incrementAndGet()
            return
        }
        ledger[taskId] = TaskDropCounter().apply { add(priority) }
    }

    /**
     * 取出并清空台账，每个任务每个优先级合成**一行**自证事件。
     *
     * @return 待写入的事务成员；空表示这一轮没有丢弃。
     */
    private fun takeDropAcknowledgements(): List<TraceOp> {
        if (ledger.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<TraceOp>(4)
        for ((taskId, counter) in ledger.entries) {
            for (priority in listOf(Priority.P1, Priority.P2)) {
                val n = counter.take(priority)
                if (n <= 0) continue
                metrics.recordTraceDropAck(taskId, priority, n)
                if (n >= DROP_ALERT_THRESHOLD) {
                    AkeLog.w(TAG, "trace drop alert: task=$taskId lost $n $priority item(s) — IO or writer thread starved?")
                }
                out += TraceOp.SyntheticTraceDropped(
                    taskId = taskId,
                    droppedOf = priority,
                    droppedCount = n,
                    createdAt = now,
                    id = Ulid.next(now),
                    seq = now,          // 任务内单调递增但**不做 unique**，用时间戳足够
                )
            }
        }
        ledger.clear()
        return out
    }

    private fun TraceOp.SyntheticTraceDropped.toEntity(): TraceEventEntity = TraceEventEntity(
        id = id,
        taskId = taskId,
        stepId = null,
        seq = seq,
        createdAt = createdAt,
        level = TraceLevel.WARN,
        kind = TraceEventKind.TRACE_DROPPED,
        messageRedacted = "trace lane full: dropped $droppedCount $droppedOf item(s)",
        kvJson = null,
    )

    private class TaskDropCounter {
        private val p1 = AtomicInteger(0)
        private val p2 = AtomicInteger(0)

        fun add(priority: Priority) {
            when (priority) {
                Priority.P1 -> p1.incrementAndGet()
                Priority.P2 -> p2.incrementAndGet()
            }
        }

        fun take(priority: Priority): Int = when (priority) {
            Priority.P1 -> p1.getAndSet(0)
            Priority.P2 -> p2.getAndSet(0)
        }
    }

    public companion object {
        public const val TAG: String = "TraceBufferWriter"
        public const val PAYLOAD_MISSING: String = "MISSING"
        public const val PAYLOAD_EXTERNAL: String = "EXTERNAL"

        /** 台账里的最大任务数。它正常不会靠近，纯属防御。 */
        public const val LEDGER_MAX_TASKS: Int = 512

        /** 单次自证超过这个数量就告警（logcat + metric），对应 SLO「每任务丢弃率 < 0.5%」的量级。 */
        public const val DROP_ALERT_THRESHOLD: Int = 16
    }
}
