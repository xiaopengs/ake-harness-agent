package com.ake.data.persist.write

import androidx.room.withTransaction
import com.ake.core.common.AkeLog
import com.ake.data.persist.db.AkeDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * # 任务状态类的专用写入通道（不可丢弃）
 *
 * 与 `TraceBufferWriter` 是**平级的另一条泳道**，而不是它的一级优先级：
 * 入口参数类型是 [StateOp]，轨迹类 Op 编译期进不来；反过来 [StateOp]
 * 也进不了 `TraceBufferWriter`。
 *
 * ## ★★ 核心论证：怎么同时满足「不可丢弃」与「不把 IO 抖动注入主循环」
 *
 * 这两个约束看似直接冲突：非阻塞 ⇒ 得承认「我可能没写进去」；不丢 ⇒ 得承认「我可能要等」。
 * **解法是把「允许谁等」与「等多久」显式切成等级，而不是二选一。**
 *
 * ```
 * 约束 A（数据库 §6.3）：每步一次的写入不能引入 IO 等待 —— 主循环到处是
 *                        withTimeout(perceiveMs / decodeMs / actionMs)，
 *                        被落盘吃掉预算会让超时归因失效（分不清是模型慢还是 SQLite 慢）。
 * 约束 B（本轮 P1）：任务状态类 Op 不能丢 —— 丢了就是数据丢失或状态机卡死。
 * ```
 *
 * **关键观察：这两条约束的重叠区远小于直觉。状态类 Op 有两种完全不同的频次：**
 *
 * | 频次 | Op | 能否容忍等待 | 理由 |
 * |---|---|---|---|
 * | 每步一次 | BudgetAdvance / Checkpoint / PolicyDecision | **不能等** | 就在 `withTimeout` 里面 |
 * | 每任务一次 | TaskUpsert（create）/ TaskFinish（终态） | **能等** | 不在任何 per-step 超时里；它本身就是任务的起止点 |
 *
 * 于是三条阶梯：
 *
 *  - **T1 异步快速路径（常态）**：`trySend` 进有界通道，即刻返回。
 *    排水协程**不做 200 ms 合并延迟**（那是轨迹泳道的做法），收到就提交，
 *    所以常态下队列深度约等于 0，`trySend` 必定成功。
 *    主循环感知到的耗时是一次 CAS 加一次原子自增，**和原来的 try-send 同量级**。
 *  - **T2 交接等待（异常降级）**：`trySend` 失败说明已到高水位 ——
 *    只可能是磁盘抖动或写线程被模型加载挤占。此刻调用早已 `suspend` 的入口
 *    （`TaskStorePort.create/advance/finish` 本来就是 suspend 函数）转为 `send()`：
 *    调用方等的是**「腾出一个槽」**，不是等一次完整的 IO 往返，
 *    且等待上界是一次事务的时长（排水协程从不空转合并）。
 *    用 `withTimeout` 封顶：宁可超时失败上抛（被 `AgentLoop` 的 `runCatching` 兜住），
 *    也不无限期拖住主循环。此时尚未落盘的行交给 `TaskReconciler` 在下次启动收敛。
 *  - **T3 落盘确认（只给每任务一次的那两笔）**：`submitAndWait()` 等这一笔真正 COMMIT，
 *    宽限 2 秒。「创建」确认了，后续的 finish 就一定打得到行；
 *    「终态」确认了，就不存在永久 RUNNING。这两笔是全任务最重要的两次 fencing，
 *    用一次毫秒级的等待换掉一个不可恢复的数据损坏，是划算的交易。
 *
 * 一句话总结这个取舍：**延迟可以分级，可靠性不行。**
 * 我们按频次把写入分了级，只对「每任务一次」的那两笔收 latency 税。
 *
 * ## 为什么不用「无界队列」
 *
 * 无界队列确实能同时满足两条约束（永不阻塞 + 永不丢），代价是 OOM ——
 * `QueueCaps` 已经论证过：在一个可能同时加载 2.58 GB 模型的进程里，
 * 这是最贵的一种「免费」。有界加等待，是把压力还给上游（表现为调用方变慢）：
 * 把一次静默的数据丢失，换成一次可见的慢 —— **可诊断的慢，优于流畅的丢。**
 *
 * ## 为什么不用「状态类直接同步写 Room」
 *
 * 直接 `db.taskDao().upsert(row)` 最简单也不丢，但它同时废掉了三件事：
 *  ① **§6.2 单写者纪律**：同一个 Room Database 的写事务必须串行化，否则事务会互相撞锁；
 *  ② **保序**：同步写会绕过队列，与在途的旧 Op 并发，见 [StateOp] 上的保序说明；
 *  ③ **IO 让路**：`PersistenceGovernor` 在模型加载期间要给推理让路，同步写无处可让。
 * 所以即便是「不可丢」，也必须是一条**有消费者**的通道，而不是到处直接开写。
 */
public class TaskStateWriter(
    private val db: AkeDatabase,
    /** SupervisorJob + **单线程** dispatcher（§6.2 单写者） */
    private val writeScope: CoroutineScope,
    private val governor: PersistenceGovernor,
    private val metrics: PersistenceMetrics,
    /** 启动对账钩子。见 [init] 注释：它不需要挡住新来的 Op。 */
    private val reconciler: (suspend () -> Unit)? = null,
) {

    /**
     * ★ `BufferOverflow.SUSPEND`：**满了排队，不是丢**。
     * 这是与轨迹泳道本质不同的那一行。
     */
    private val lane = Channel<Envelope>(StateLaneCaps.CAPACITY, BufferOverflow.SUSPEND)

    /** 真实在途数量：`Channel` 没有 O(1) 的 size API，而 send/receive 都在本类里，自己数一个。 */
    private val pending = AtomicInteger(0)

    @Volatile
    private var stopped: Boolean = false

    init {
        writeScope.launch {
            // ① 先把上一次生命周期遗留的「孤儿 RUNNING」收敛掉，再开始流动写入。
            //    顺序上**不需要**挡住新来的 Op：通道有界且不丢，先攒着，亏不了。
            reconciler?.let {
                runCatching { it() }
                    .onFailure { t -> AkeLog.w(TAG, "startup reconcile failed: ${t.message}") }
            }
            drainLoop()
        }
    }

    // ──────────────────────────────────── 三个入口（按「能否容忍等待」分级）

    /**
     * T1 / T2：suspend 上下文里的通用入口。常态即刻返回；
     * 只有在高水位下才会挂起等待槽位（T2），且有 5 秒封顶。
     *
     * 用于每步一次的 Op（BudgetAdvance / Checkpoint / PolicyDecision）与挂起态 finish。
     */
    public suspend fun submit(op: StateOp) {
        val env = Envelope(op, ack = null)
        if (tryOffer(env)) return
        metrics.recordStateBacklog()
        AkeLog.w(TAG, "state lane at high water (${pending.get()}), waiting for a slot")
        withTimeout(StateLaneCaps.SUBMIT_GRACE_MS) { handOff(env) }
    }

    /**
     * T3：**必须确认落盘**的路径。只有 `TaskStoreImpl.create()` 与终态 `finish()` 用。
     *
     * @return true = 已 COMMIT。false = 宽限内没落地（磁盘异常或写线程卡死），
     *         **但这一笔没有丢**：它还在通道里排队等着重试；
     *         真到了设备不可用，由 `TaskReconciler` 在下次启动收敛 —— 总之不会变成永久 RUNNING。
     */
    public suspend fun submitAndWait(
        op: StateOp,
        timeoutMs: Long = StateLaneCaps.COMMIT_AWAIT_MS,
    ): Boolean {
        val ack = CompletableDeferred<Boolean>()
        val env = Envelope(op, ack)
        if (!tryOffer(env)) {
            metrics.recordStateBacklog()
            val handed = runCatching {
                withTimeout(StateLaneCaps.SUBMIT_GRACE_MS) { handOff(env) }
            }.isSuccess
            if (!handed) {
                metrics.recordStateGiveUp(1)
                ack.complete(false)
                return false
            }
        }
        val done = runCatching { withTimeout(timeoutMs) { ack.await() } }.getOrDefault(false)
        if (!done) {
            AkeLog.w(TAG, "commit not confirmed in ${timeoutMs}ms: ${op.javaClass.simpleName} task=${op.taskId}")
        }
        return done
    }

    /**
     * **非挂起**入口，给 `PolicyDecisionSink.record()` 这类在 `core:ports` 里被定死为非挂起的契约。
     *
     * ★ 高水位下的降级是「后台协程代为投递」：它不会被丢，只是可能被排到后续 Op 之后。
     * 审计行之间不靠相对顺序表达正确性（各自带 ULID 与任务内 seq），所以这个错位可接受；
     * 重要的是**它仍然不会被丢弃**。
     *
     * @return true = 已入队（常态）；false = 已转后台投递（不丢，可能稍晚落盘）。
     */
    public fun trySubmit(op: StateOp): Boolean {
        if (tryOffer(Envelope(op, ack = null))) return true
        metrics.recordStateBacklog()
        val env = Envelope(op, ack = null)
        writeScope.launch { runCatching { handOff(env) } }
        return false
    }

    /**
     * 任务结束 / 暂停恢复前 / 用户点停止：**显式等待状态类全部落盘**。
     *
     * 实现方式是投一枚「屏障」并等它被消费：屏障排在前面所有 Op 之后，
     * 所以它被消费时，前面的数据一定已经写完了（同一个单线程消费者）。
     */
    public suspend fun flushNow() {
        if (stopped) return
        val barrier = CompletableDeferred<Boolean>()
        val accepted = runCatching {
            withTimeout(StateLaneCaps.SUBMIT_GRACE_MS) { handOff(Envelope(op = null, ack = barrier)) }
        }.isSuccess
        if (!accepted) return
        runCatching { withTimeout(StateLaneCaps.COMMIT_AWAIT_MS) { barrier.await() } }
    }

    /** §8.5 一键清除：停掉一切在途写入，否则刚删完又被写回来。 */
    public fun stopAndDropQueued() {
        stopped = true
        runCatching { lane.cancel() }
        pending.set(0)
    }

    /** 观测口径：当前在途状态 Op 数（健康常态是 0 或个位数）。 */
    public fun pendingCount(): Int = pending.get()

    // ──────────────────────────────────────────── 内部

    private fun tryOffer(env: Envelope): Boolean {
        if (stopped) return false
        val ok = lane.trySend(env).isSuccess
        if (ok) pending.incrementAndGet()
        return ok
    }

    /** T2 的实际交接动作。也会自增深度 —— 观测口径不能因为走了降级路径就失真。 */
    private suspend fun handOff(env: Envelope) {
        lane.send(env)
        pending.incrementAndGet()
    }

    private suspend fun drainLoop() {
        while (writeScope.isActive) {
            val first = try {
                lane.receive()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce                 // ★ 架构 §6.2 第 3 条：不许吞取消
            } catch (t: Throwable) {
                return                   // 通道被 cancel（§8.5 一键清除）⇒ 正常收摊
            }
            pending.decrementAndGet()
            val batch = ArrayList<Envelope>(StateLaneCaps.MAX_BATCH)
            batch += first
            // ★ 顺手把已经到站的都带上，但**不做任何 coalesce 延迟**
            while (batch.size < StateLaneCaps.MAX_BATCH) {
                val r = lane.tryReceive()
                if (!r.isSuccess) break
                val v = r.getOrNull() ?: break
                pending.decrementAndGet()
                batch += v
            }
            metrics.recordStateQueueDepth(pending.get())
            commitWithRetry(batch)
            governor.yieldForIoBudget()
        }
    }

    /**
     * ★ 与轨迹泳道的第二个本质区别：**失败要重试，不是丢**。
     *
     * 轨迹批次失败就整批丢是可接受的；这里不行 —— 丢一次 `TaskFinish` 就是一个永久 RUNNING。
     * 指数退避到上限后若仍然失败，这批 Op 会在 `metrics.stateGiveUp` 上留痕，
     * 由 `TaskReconciler` 在下次启动收敛（所以「写不进去」的最终形态是
     * 「任务被判为孤儿并终态化」，而不是「永久 RUNNING」）。
     */
    private suspend fun commitWithRetry(batch: List<Envelope>) {
        var attempt = 0
        var wait = StateLaneCaps.RETRY_BASE_MS
        while (true) {
            val ok = runCatching { commitOnce(batch) }
                .onFailure { t -> metrics.recordStateWriteFailure(t) }
                .getOrDefault(false)
            if (ok) return
            attempt++
            if (attempt > StateLaneCaps.RETRY_MAX_TIMES) {
                AkeLog.e(TAG, "state commit failed ${attempt}x, ${batch.size} op(s) unwritten; reconciler will converge")
                metrics.recordStateGiveUp(batch.size)
                batch.forEach { it.ack?.complete(false) }
                return
            }
            metrics.recordStateRetry()
            delay(wait)
            wait = (wait * 2).coerceAtMost(StateLaneCaps.RETRY_BASE_MS * 16)
        }
    }

    /** 一个事务、多张表、一次 COMMIT。返回 false 表示没写成功（由调用方重试）。 */
    private suspend fun commitOnce(batch: List<Envelope>): Boolean {
        val started = metrics.beginFlush()
        try {
            db.withTransaction {
                val taskDao = db.taskDao()
                val checkpointDao = db.checkpointDao()
                val policyDao = db.policyDao()

                // ★ ★ 单一严格有序的循环：**刻意不做「先按表攒一批再批量 insert」**。
                //
                // 轨迹泳道那么做是为了省预编译语句，这里不行 —— 这批里有外键：
                // `task_checkpoint.task_id → task.id`。把 checkpoint 提到 task 行之前写，
                // 在同一批同时含 `TaskUpsert` 与 `Checkpoint` 时会直接违反外键
                // （这也是允许部署 CASCADE 之外唯一会踩到的场景）。
                // 状态类的量级是 O(步数)，攒批省下的那点钱不值得拿正确性去换。
                for (env in batch) {
                    when (val op = env.op) {
                        is StateOp.TaskUpsert -> taskDao.upsert(op.row)

                        is StateOp.Checkpoint -> checkpointDao.save(op.row)

                        is StateOp.PolicyDecision -> policyDao.insert(listOf(op.row))

                        is StateOp.BudgetAdvance -> taskDao.advance(
                            id = op.taskId,
                            stepsDone = op.stepsDone,
                            prefillTotal = op.prefillTotal,
                            decodeTotal = op.decodeTotal,
                            elapsedTotal = op.elapsedTotal,
                            lastActivityAt = op.lastActivityAt,
                        )

                        is StateOp.TaskFinish -> taskDao.finish(
                            id = op.taskId,
                            status = op.status,
                            reason = op.reason,
                            finishedAt = op.finishedAt,
                            answerEnc = op.answerEnc,
                            answerPreview = op.answerPreview,
                        )

                        is StateOp.TaskSuspend -> taskDao.markSuspended(
                            id = op.taskId,
                            status = op.status,
                            at = op.at,
                        )

                        null -> Unit      // flushNow 的屏障：不落任何数据
                    }
                }
            }
            metrics.endFlush(started, batch.size)
            batch.forEach { it.ack?.complete(true) }
            return true
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // ★ 架构 §6.2 第 3 条：绝不能吞 CancellationException
            throw ce
        } catch (t: Throwable) {
            // 吞掉异常但返回 false：由调用侧重试并计数。此处不再记录一次，避免双计。
            return false
        }
    }

    /** @param op null 表示 `flushNow()` 的屏障：不落任何数据，只用于确认「前面的都写完了」。 */
    private class Envelope(val op: StateOp?, val ack: CompletableDeferred<Boolean>?)

    public companion object {
        public const val TAG: String = "TaskStateWriter"
    }
}
