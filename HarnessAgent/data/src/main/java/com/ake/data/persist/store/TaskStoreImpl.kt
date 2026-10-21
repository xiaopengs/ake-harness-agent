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
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.write.Op
import com.ake.data.persist.write.TraceBufferWriter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `TaskStorePort` 实现。
 *
 * ## 写的三条纪律
 *
 *  1. **写一律走 `TraceBufferWriter`（try-send）**，不在本文件里直接 `db.taskDao().xxx()`。
 *     预算推进发生在每一步，若它是阻塞式 DB 写入，就会吃掉 `withTimeout(decodeMs)` 的时间。
 *  2. **读才直接走 DAO。** 读发生在 UI 与恢复路径上，不在主循环热路径里。
 *  3. **R1 字段（goal / answer）必须先过 `PersistenceRedactionGate.sealField()`。**
 *     用户原始意图是全库最私密的一列 —— 明文落它等于把「数据不出机」这条承诺作废。
 *
 * ## `advance` 的 null 合并
 *
 * `TaskBudgetPatch` 的语义是「`null` = 本次不动这一列」。但写入队列里传的是
 * **累计终值**（不能传增量，否则队列被丢弃时累计就错了）。
 * 所以这里维护一份每个任务的「最近累计值」作为合并基准。
 * 这份缓存是权威的 —— 这四个列只有本类会写。
 */
public class TaskStoreImpl(
    private val db: AkeDatabase,
    private val writer: TraceBufferWriter,
    private val gate: PersistenceRedactionGate,
    private val clock: Clock,
) : TaskStorePort {

    private data class Known(
        var stepsDone: Int,
        var prefill: Long,
        var decode: Long,
        var elapsed: Long,
    )

    private val known = ConcurrentHashMap<String, Known>()

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
        writer.append(Op.TaskUpsert(row))
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
        writer.append(
            Op.BudgetAdvance(
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
     */
    override suspend fun finish(
        taskId: TaskId,
        status: String,
        reason: String?,
        answerEnc: ByteArray?,
        answerPreview: String?,
    ) {
        if (!TaskStatus.isTerminal(status)) {
            writer.append(Op.TaskSuspend(taskId = taskId.value, status = status, at = clock.nowMillis()))
            // 挂起后合并缓存**必须留着**：恢复时 advance 要接着上次累计往下加，
            // 清掉它等于「一次暂停白送一张预算卡」。
            return
        }
        val sealed = if (answerEnc == null && !answerPreview.isNullOrBlank()) {
            gate.sealField(answerPreview, taskId.value.toByteArray()).blob
        } else {
            answerEnc
        }
        writer.append(
            Op.TaskFinish(
                taskId = taskId.value,
                status = status,
                reason = reason,
                finishedAt = clock.nowMillis(),
                answerEnc = sealed,
                answerPreview = answerPreview?.take(200),
            ),
        )
        // 终态后清掉合并缓存：这个任务不会再 advance
        known.remove(taskId.value)
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
     * ## 一个必须知道的时序事实
     *
     * 写走 `TraceBufferWriter`（200 ms 合并窗口），所以「刚 finish 完立刻 recent()」
     * 有可能读不到最后一条 —— 这不是 bug，是写入队列的既定代价。
     * 需要「写完立刻看到」的界面请用 [observeRecent]（Room 的 Flow 在事务提交后自动重发）。
     */
    override suspend fun recent(limit: Int): List<TaskRecord> =
        runCatching { db.taskDao().recent(limit.coerceAtLeast(0)).map(EntityMappers::taskToDomain) }
            .onFailure { AkeLog.w(TAG, "recent failed: ${it.message}") }
            .getOrDefault(emptyList())

    /**
     * [recent] 的 `Flow` 版：列表随写入自动刷新。
     *
     * 契约里只有挂起版（`core/ports/Persistence.kt` 由邝随行定稿，我不改 `core/`），
     * 所以这一条**先备着**：等 `TaskStorePort` 加上它，UI 就能直接切过来，
     * 不必再动 `:data`。我的建议是加 —— 历史页是常驻列表，
     * 挂起版要 UI 自己找时机重查，而那个时机几乎必然和「写入落盘」对不齐。
     */
    fun observeRecent(limit: Int = 50): Flow<List<TaskRecord>> =
        db.taskDao().observeRecent(limit.coerceAtLeast(0))
            .map { list -> list.map(EntityMappers::taskToDomain) }

    /** 会话维度：给 UI 的历史列表。 */
    fun ofSession(sessionId: String): Flow<List<TaskRecord>> =
        db.taskDao().ofSession(sessionId).map { list -> list.map(EntityMappers::taskToDomain) }

    fun finished(limit: Int = 50): Flow<List<TaskRecord>> =
        db.taskDao().finished(limit).map { list -> list.map(EntityMappers::taskToDomain) }

    suspend fun setStarred(taskId: TaskId, starred: Boolean) {
        runCatching { db.taskDao().setStarred(taskId.value, if (starred) 1 else 0) }
    }

    companion object {
        const val TAG: String = "TaskStore"
    }
}
