package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.common.TaskId
import com.ake.core.contract.TaskBudgetPatch
import com.ake.core.contract.TaskRecord
import com.ake.core.contract.TaskStatus
import com.ake.core.ports.TaskStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers
import com.ake.data.persist.recovery.TaskReconciler
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.write.StateOp
import com.ake.data.persist.write.StateLaneCaps
import com.ake.data.persist.write.TaskLivenessRegistry
import com.ake.data.persist.write.TaskStateWriter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * `TaskStorePort` 实现。
 *
 * ## ★ 写的三条纪律（2026-10 P1 修订版）
 *
 *  1. **任务状态类走 `TaskStateWriter`（不可丢），轨迹类才走 `TraceBufferWriter`（可丢）。**
 *     在修订之前这两类混在同一条 try-send 队列里 —— `TaskUpsert` 被丢会导致
 *     finish 的 UPDATE 打到不存在的行（任务凭空消失），`TaskFinish` 被丢会导致
 *     永久 RUNNING。现在这件事**由类型守住**：这里能构造出来的全是 [StateOp]，
 *     进不去轨迹那条 `DROP_OLDEST` 的通道。
 *  2. **读才直接走 DAO。** 读发生在 UI 与恢复路径上，不在主循环热路径里。
 *  3. **R1 字段（goal / answer）必须先过 `PersistenceRedactionGate.sealField()`。**
 *     用户原始意图是全库最私密的一列 —— 明文落它等于把「数据不出机」这条承诺作废。
 *
 * ## 写入延迟的分级（详见 `TaskStateWriter` 的台阶论证）
 *
 * | 调用 | 频次 | 是否等待落盘 |
 * |---|---|---|
 * | [create] | 每任务一次 | **等**（必须确认行存在，否则后续 UPDATE 全部落空） |
 * | [advance] | 每步一次 | 不等（在 per-step `withTimeout` 里） |
 * | [finish] 终态 | 每任务一次 | **等**（必须确认 `finished_at` 落盘，否则可能永久 RUNNING） |
 * | [finish] 挂起态 | 罕见 | 不等 |
 *
 * ## 存活登记
 *
 * [liveness] 是「本进程有没有在跑这个任务」的证据，供 `TaskReconciler` 判定孤儿。
 * 它刻意不落盘 —— 进程一死登记表随之清空，而这正好是下一轮冷启动对账想要的起点。
 *
 * ## `advance` 的 null 合并
 *
 * `TaskBudgetPatch` 的语义是「`null` = 本次不动这一列」。但写入队列里传的是
 * **累计终值**（不能传增量，否则累计就错了）。
 * 所以这里维护一份每个任务的「最近累计值」作为合并基准。
 * 这份缓存是权威的 —— 这四个列只有本类会写。
 */
public class TaskStoreImpl(
    private val db: AkeDatabase,
    private val state: TaskStateWriter,
    private val gate: PersistenceRedactionGate,
    private val clock: Clock,
    private val liveness: TaskLivenessRegistry,
    private val reconciler: TaskReconciler,
) : TaskStorePort {

    private data class Known(
        var stepsDone: Int,
        var prefill: Long,
        var decode: Long,
        var elapsed: Long,
    )

    private val known = ConcurrentHashMap<String, Known>()

    /**
     * ★ 创建是两笔「必须等到真正 COMMIT」的写入之一。
     *
     * `TaskUpsert` 是这一行在库里的产床：它成功了，后面的 `advance` / `finish`
     * 的 `UPDATE` 才不会打空（0 行受影响且不报错 —— 这就是「任务凭空消失」的机理）。
     */
    override suspend fun create(rec: TaskRecord) {
        // R1：goal 加密。AAD 用 taskId —— GCM 的 AAD 能防止密文被跨行搬移。
        val sealed: ByteArray? = rec.goalEnc
            ?: rec.goalPreview?.takeIf { it.isNotBlank() }
                ?.let { gate.sealField(it, rec.id.value.toByteArray()).blob }
        val row = EntityMappers.taskToEntity(
            rec = rec,
            goalEnc = sealed,
            answerEnc = null,
            answerPreview = null,
        )
        known[rec.id.value] = Known(rec.stepsDone, rec.prefillTokensTotal, rec.decodeTokensTotal, rec.elapsedMsTotal)
        liveness.markLive(rec.id.value, clock.nowMillis())
        val committed = state.submitAndWait(StateOp.TaskUpsert(row), StateLaneCaps.COMMIT_AWAIT_MS)
        if (!committed) {
            // ★ 不要因为「没确认」就把库当成失联：这一笔还在通道里排队等着重试。
            //   最坏情况是设备真的写不进去了 —— 那条根本不存在的行不会被任何人当成孤儿，
            //   而任务只是「没跑到」，不存在「看起来在跑其实没了」这种最糟糕的形态。
            AkeLog.w(TAG, "create not committed within grace: task=${rec.id.value}")
        }
    }

    override suspend fun advance(taskId: TaskId, patch: TaskBudgetPatch) {
        val cur = known[taskId.value] ?: Known(0, 0, 0, 0)
        val merged = Known(
            stepsDone = patch.stepsDone ?: cur.stepsDone,
            prefill = patch.prefillTokensTotal ?: cur.prefill,
            decode = patch.decodeTokensTotal ?: cur.decode,
            elapsed = patch.elapsedMsTotal ?: cur.elapsed,
        )
        known[taskId.value] = merged
        // 每步一次 ⇒ 不等落盘（T1 快速路径）。这一行同时也是**心跳**：
        // last_activity_at 的推进是 TaskReconciler 判断「有没有卡住」的唯一依据。
        state.submit(
            StateOp.BudgetAdvance(
                taskId = taskId.value,
                stepsDone = merged.stepsDone,
                prefillTotal = merged.prefill,
                decodeTotal = merged.decode,
                elapsedTotal = merged.elapsed,
                lastActivityAt = patch.lastActivityAt ?: clock.nowMillis(),
            ),
        )
        patch.kvCachePeak?.let { peak ->
            // KV cache 峰值只升不降：直接同步写，因为它低频（每步一次但值小），
            // 且丢了它只是少一条诊断数据，不值得为它开一条队列
            runCatching { db.taskDao().raiseKvCachePeak(taskId.value, peak) }
        }
        patch.redactionCount?.let { delta ->
            runCatching { db.taskDao().addRedactionCount(taskId.value, delta) }
        }
    }

    /**
     * 落一次状态跃迁。
     *
     * ★ **挂起态（PAUSED / WAITING_HUMAN）不走 `TaskFinish`**：那会把 `finished_at` 置上，
     * 于是「暂停中、等着用户点继续」的任务会被历史查询与清理作业（`pickForDigest` 以
     * `finished_at IS NOT NULL` 为入口）当成已结束的捞走。它其实还活着，只是停了一下。
     *
     * ★ **终态是第二个「必须等落盘」的写入**：这一次 `finished_at` 不落地，
     * 这一行就会以 RUNNING 的形态留在库里，直到下一次启动的对账来收拾它。
     */
    override suspend fun finish(
        taskId: TaskId,
        status: String,
        reason: String?,
        answerEnc: ByteArray?,
        answerPreview: String?,
    ) {
        if (!TaskStatus.isTerminal(status)) {
            state.submit(StateOp.TaskSuspend(taskId = taskId.value, status = status, at = clock.nowMillis()))
            // 挂起后合并缓存**必须留着**：恢复时 advance 要接着上次累计往下加，
            // 清掉它等于「一次暂停白送一张预算卡」。
            liveness.markNotLive(taskId.value)
            return
        }
        val sealed = if (answerEnc == null && !answerPreview.isNullOrBlank()) {
            gate.sealField(answerPreview, taskId.value.toByteArray()).blob
        } else {
            answerEnc
        }
        val committed = state.submitAndWait(
            StateOp.TaskFinish(
                taskId = taskId.value,
                status = status,
                reason = reason,
                finishedAt = clock.nowMillis(),
                answerEnc = sealed,
                answerPreview = answerPreview?.take(200),
            ),
        )
        if (!committed) {
            // 没有确认 ⇒ 这一行可能停在 RUNNING。**TaskReconciler 会在下次启动时收敛它**
            // （没有断点就判 ABORTED）。这里再记一条 WARN，方便现场把它和「正常慢」区分开。
            AkeLog.w(TAG, "terminal write unconfirmed: task=${taskId.value} status=$status (reconciler is the backstop)")
        }
        // 终态后清掉合并缓存与存活登记：这个任务不会再 advance
        known.remove(taskId.value)
        liveness.markNotLive(taskId.value)
    }

    override suspend fun load(taskId: TaskId): TaskRecord? =
        runCatching { db.taskDao().byId(taskId.value)?.let(EntityMappers::taskToDomain) }
            .onFailure { AkeLog.w(TAG, "load failed: ${it.message}") }
            .getOrDefault(null)

    override fun observe(taskId: TaskId): Flow<TaskRecord?> =
        db.taskDao().observe(taskId.value).map { it?.let(EntityMappers::taskToDomain) }

    /**
     * `TaskStorePort.recent` —— 冷启动后的历史任务列表。
     *
     * ★ 含**未结束**的任务（暂停中的也在）。用户在历史页看到「已暂停」才想得起来回去点继续，
     * 只给已结束的会让暂停的任务凭空消失。
     *
     * ## 一个必须知道的时序事实（2026-10 修订）
     *
     * 轨迹写走 `TraceBufferWriter`（200 ms 合并窗口），但**任务状态已经不走那条路了** ——
     * 走 `TaskStateWriter`，而 `create()` / 终态 `finish()` 是会明确等待 COMMIT 的。
     * 所以「刚 create/finish 完立刻 recent()」现在**保证能看到**这两个关键跃迁；
     * 仍可能落后的只剩「最近一次 per-step 预算推进」（它能容忍，下一次 beat 会补上）。
     * 需要「数据变了界面立刻跟着变」的常驻列表请用 [observeRecent]。
     */
    override suspend fun recent(limit: Int): List<TaskRecord> =
        runCatching { db.taskDao().recent(limit.coerceAtLeast(0)).map(EntityMappers::taskToDomain) }
            .onFailure { AkeLog.w(TAG, "recent failed: ${it.message}") }
            .getOrDefault(emptyList())

    /**
     * [recent] 的 `Flow` 版：列表随写入自动刷新。契约层（`core/ports` 的 `TaskStorePort`，
     * ← 契约已由邝随行于 2026-10 补进 `TaskStorePort`，本方法现已是正式的 `override`。
     * ★ 顺带更正原注释里的一处误判：**Kotlin 不允许重写方法再给参数默认值**
     *   （编译期实测：`An overriding function is not allowed to specify default values
     *   for its parameters`）。所以这里的 `DEFAULT_RECENT_LIMIT` 兜底已经撤掉 ——
     *   `limit` 由调用方显式传。全仓无调用点依赖默认值，改动无副作用。
     *   契约签名见 `core/ports/Persistence.kt`：
     * ```kotlin
     * public fun observeRecent(limit: Int): Flow<List<TaskRecord>>
     * ```
     *
     * ## 为什么 UI 的历史列表值得用 Flow
     *
     * 历史页是**常驻列表**，挂起版要 UI 自己找时机重查，而那个时机几乎必然和
     * 「写入何时真正落盘」对不齐。Room 的 Flow 在**事务提交后**自动重发，天然对齐。
     *
     * ★ 但它**不是**写入可靠性的替代品：`TaskFinish` 若没走到事务这一步，Flow 一条都不会发
     * （2026-10 上线验收的原话：「Flow 只在事务提交后重发，写入被丢弃时事务根本没发生」）。
     * 那件事由 `TaskStateWriter` + `TaskReconciler` 负责，别指望这里能兜。
     */
    public override fun observeRecent(limit: Int): Flow<List<TaskRecord>> =
        db.taskDao().observeRecent(limit.coerceAtLeast(0))
            .map { rows -> rows.map(EntityMappers::taskToDomain) }
            // Flow 里崩一个查询会把整条列表订阅打死；这里吞掉并保持上一次的值语义
            // （emit 空表会让 UI 闪一下「什么都没有」，所以只记 WARN 不 emit）
            .catch { t -> AkeLog.w(TAG, "observeRecent failed: ${t.message}") }

    /** 会话维度：给 UI 的历史列表。 */
    public fun ofSession(sessionId: String): Flow<List<TaskRecord>> =
        db.taskDao().ofSession(sessionId).map { list -> list.map(EntityMappers::taskToDomain) }

    public fun finished(limit: Int = 50): Flow<List<TaskRecord>> =
        db.taskDao().finished(limit).map { list -> list.map(EntityMappers::taskToDomain) }

    public suspend fun setStarred(taskId: TaskId, starred: Boolean) {
        runCatching { db.taskDao().setStarred(taskId.value, if (starred) 1 else 0) }
    }

    /**
     * UI 冷启动想显式回收一次孤儿任务时调用（例如历史页下拉刷新）。
     *
     * 日常不需要 —— `TaskStateWriter` 构造时已经自触发过一轮；这里只是给 UI/VM
     * 一个「我想现在就看到干净列表」的入口。
     */
    public suspend fun reconcileNow() {
        runCatching { reconciler.sweep() }
            .onFailure { AkeLog.w(TAG, "reconcileNow failed: ${it.message}") }
    }

    public companion object {
        public const val TAG: String = "TaskStore"

        /** `observeRecent` 的默认条数（契约侧不设默认值，实现侧给一个兜底）。 */
        public const val DEFAULT_RECENT_LIMIT: Int = 50
    }
}
