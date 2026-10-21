package com.ake.data.persist.write

import androidx.room.withTransaction
import com.ake.core.common.AkeLog
import com.ake.core.common.Ulid
import com.ake.core.contract.TraceEventKind
import com.ake.core.contract.TraceLevel
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.TaskCheckpointEntity
import com.ake.data.persist.db.entity.TaskEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.TraceEventEntity
import com.ake.data.persist.payload.PayloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * # 三级有界写入队列（数据库 §6.3）
 *
 * **契约：`append()` 永不阻塞、永不抛业务异常。丢数据可接受，拖慢主循环不可接受。**
 *
 * 这条契约的由来：`ObservationStorePort.append` 与 `PolicyDecisionSink.record` 在
 * `core:ports` 里被刻意设计成**非挂起、返回 Boolean**。主循环各处都有
 * `withTimeout(budget.perceiveMs / decodeMs / actionMs)`，
 * 若落盘是阻塞式 DB 写入，**这些 timeout 会被 IO 吃掉**，
 * 而落盘不属于任何一个环节的职责 —— 于是超时归因彻底失效。
 *
 * 四条设计取舍：
 *  1. **`Channel(capacity, DROP_OLDEST)` 而不是 `UNLIMITED`**：`UNLIMITED` 的语义是永不限长，
 *     在一个 IO 可能与 2.58 GB 模型加载争抢的进程里，这是 OOM 的一条免费通道。
 *  2. **三级优先级**：P0 必须落盘；P2 的 DEBUG 事件丢了不影响任何事。
 *  3. **`append()` 必须是 `fun` 不是 `suspend fun`**：只有非挂起的 `trySend`
 *     能从 API 层面保证「调用即返回」。
 *  4. **丢弃必须自证**（`SyntheticTraceDropped`），否则回放缺几步会变成无法解释的灵异现象。
 */
public class TraceBufferWriter(
    private val db: AkeDatabase,
    /** SupervisorJob + **单线程** dispatcher（§6.2 单写者） */
    private val writeScope: CoroutineScope,
    private val payloads: PayloadStore,
    private val governor: PersistenceGovernor,
    private val metrics: PersistenceMetrics,
) {

    private val p0 = Channel<Op>(QueueCaps.P0, BufferOverflow.DROP_OLDEST)
    private val p1 = Channel<Op>(QueueCaps.P1, BufferOverflow.DROP_OLDEST)
    private val p2 = Channel<Op>(QueueCaps.P2, BufferOverflow.DROP_OLDEST)

    init {
        writeScope.launch { drainLoop() }
    }

    /** ★ 主循环只碰这一个方法。非挂起、try-send，语义上等同于 "return"。 */
    fun append(op: Op): Boolean {
        val target = when (op.priority) {
            Priority.P0 -> p0
            Priority.P1 -> p1
            Priority.P2 -> p2
        }
        val ok = target.trySend(op).isSuccess
        if (!ok) {
            metrics.recordDrop(op.priority)
            syntheticDrop(op)?.let { target.trySend(it) }
        }
        return ok
    }

    /**
     * 任务结束 / 暂停恢复前 / 用户点停止：**必须显式同步等待一次 flush**。
     * 这里的阻塞是刻意且必要的 —— 断点恢复的正确性依赖「暂停时数据已真正落盘」。
     */
    suspend fun flushNow() {
        val pending = ArrayList<Op>(QueueCaps.MAX_BATCH * 8)
        for (ch in listOf(p0, p1, p2)) {
            while (pending.size < QueueCaps.MAX_BATCH * 8) {
                val r = ch.tryReceive()
                if (!r.isSuccess) break
                val v = r.getOrNull() ?: break
                pending += v
            }
        }
        pending.chunked(QueueCaps.MAX_BATCH).forEach { flush(it) }
    }

    /** §8.5 一键清除：停掉一切在途写入，否则刚删完又被写回来。 */
    fun stopAndDropQueued() {
        p0.close(); p1.close(); p2.close()
        runCatching { p0.cancel() }
        runCatching { p1.cancel() }
        runCatching { p2.cancel() }
    }

    // ──────────────────────────────────────────── 内部

    private suspend fun drainLoop() {
        while (writeScope.isActive) {
            val batch = ArrayList<Op>(QueueCaps.MAX_BATCH)
            drainInto(p0, batch)     // P0 优先：先把不可丢的吸干
            drainInto(p1, batch)
            drainInto(p2, batch)
            metrics.recordQueueDepth(0, 0, 0)
            if (batch.isEmpty()) {
                delay(QueueCaps.COALESCE_MS)
                continue
            }
            flush(batch)
            governor.yieldForIoBudget()
        }
    }

    /**
     * ★ `ChannelResult` 是 **value class**，没有 `Success` 子类型 ——
     * 数据库 §6.3 的 `is ChannelResult.Success -> into += r.value` 在 coroutines 1.11.0 上**编不过**。
     * 正确形态是 `isSuccess` + `getOrNull()`。
     */
    private fun drainInto(ch: Channel<Op>, into: MutableList<Op>) {
        repeat(QueueCaps.MAX_BATCH - into.size) {
            val r = ch.tryReceive()
            if (!r.isSuccess) return
            val v = r.getOrNull() ?: return
            into += v
        }
    }

    /** 一个事务、多张表、一次 COMMIT。 */
    private suspend fun flush(batch: List<Op>) {
        val started = metrics.beginFlush()
        try {
            // ★ 先写文件，再写 DB 行（§4.5 纪律 1 的镜像）
            val fileNames = HashMap<String, String>(4)
            for (op in batch) {
                if (op is Op.Observation && op.payload != null) {
                    payloads.write(op.row.taskId, op.row.seq, op.row.id, op.payload)
                        ?.let { fileNames[op.row.id] = it }
                }
            }

            db.withTransaction {
                val taskDao = db.taskDao()
                val traceDao = db.traceDao()
                val policyDao = db.policyDao()
                val checkpointDao = db.checkpointDao()

                val steps = ArrayList<StepEntity>()
                val tools = ArrayList<ToolInvocationEntity>()
                val observations = ArrayList<ObservationEntity>()
                val events = ArrayList<TraceEventEntity>()
                val policies = ArrayList<PolicyDecisionEntity>()

                // 先按表分类，再批量 insert —— 批量插入能复用预编译语句，
                // 这是批量写比逐行写快的关键（数据库 §6.6）
                for (op in batch) {
                    when (op) {
                        is Op.Step -> steps += op.row
                        is Op.ToolInvocation -> tools += op.row
                        is Op.Observation -> observations += op.row.withFile(fileNames[op.row.id])
                        is Op.TraceEvent -> events += op.row
                        is Op.PolicyDecision -> policies += op.row
                        else -> Unit
                    }
                }
                if (steps.isNotEmpty()) traceDao.insertSteps(steps)
                if (tools.isNotEmpty()) traceDao.insertToolInvocations(tools)
                if (observations.isNotEmpty()) traceDao.insertObservations(observations)
                if (events.isNotEmpty()) traceDao.insertEvents(events)
                if (policies.isNotEmpty()) policyDao.insert(policies)

                // 事务内的挂起 DAO 调用（Room 允许 suspend DAO 在 withTransaction 内）
                for (op in batch) {
                    when (op) {
                        is Op.TaskUpsert -> taskDao.upsert(op.row)
                        is Op.BudgetAdvance -> taskDao.advance(
                            id = op.taskId,
                            stepsDone = op.stepsDone,
                            prefillTotal = op.prefillTotal,
                            decodeTotal = op.decodeTotal,
                            elapsedTotal = op.elapsedTotal,
                            lastActivityAt = op.lastActivityAt,
                        )

                        is Op.TaskFinish -> taskDao.finish(
                            id = op.taskId,
                            status = op.status,
                            reason = op.reason,
                            finishedAt = op.finishedAt,
                            answerEnc = op.answerEnc,
                            answerPreview = op.answerPreview,
                        )

                        is Op.TaskSuspend -> taskDao.markSuspended(
                            id = op.taskId,
                            status = op.status,
                            at = op.at,
                        )

                        is Op.Checkpoint -> checkpointDao.save(op.row)
                        else -> Unit
                    }
                }
            }
            metrics.endFlush(started, batch.size)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // ★ 架构 §6.2 第 3 条：绝不能吞 CancellationException
            throw ce
        } catch (t: Throwable) {
            metrics.recordFlushFailure(t)
            // 降级：整批丢弃但异常不得穿透到 AgentLoop
        }
    }

    private fun ObservationEntity.withFile(name: String?): ObservationEntity =
        if (name == null) {
            copy(payloadState = PAYLOAD_MISSING, payloadFile = null, payloadCodec = null)
        } else {
            copy(payloadFile = name, payloadCodec = "gzip", payloadState = PAYLOAD_EXTERNAL)
        }

    private fun syntheticDrop(op: Op): Op? {
        val taskId = when (op) {
            is Op.Step -> op.row.taskId
            is Op.TraceEvent -> op.row.taskId
            is Op.ToolInvocation -> op.row.taskId
            is Op.Observation -> op.row.taskId
            is Op.PolicyDecision -> op.row.taskId
            is Op.Checkpoint -> op.row.taskId
            is Op.TaskUpsert -> op.row.id
            is Op.BudgetAdvance -> op.taskId
            is Op.TaskFinish -> op.taskId
            is Op.TaskSuspend -> op.taskId
            is Op.SyntheticTraceDropped -> return null   // 不给自己记丢，避免递归
        }
        val now = System.currentTimeMillis()
        return Op.TraceEvent(
            row = TraceEventEntity(
                id = Ulid.next(now),
                taskId = taskId,
                stepId = null,
                seq = now,          // 任务内单调递增但**不做 unique**，用时间戳足够
                createdAt = now,
                level = TraceLevel.WARN,
                kind = TraceEventKind.TRACE_DROPPED,
                messageRedacted = "queue full, dropped a ${op.priority} op",
                kvJson = null,
            ),
            priority = Priority.P1,
        )
    }

    companion object {
        const val TAG: String = "TraceBufferWriter"
        const val PAYLOAD_MISSING: String = "MISSING"
        const val PAYLOAD_EXTERNAL: String = "EXTERNAL"
    }
}
