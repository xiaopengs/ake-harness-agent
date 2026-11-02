package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.TaskId
import com.ake.core.contract.PlanRevision
import com.ake.core.contract.PlanStatus
import com.ake.core.contract.PlanStep
import com.ake.core.ports.PlanStorePort
import com.ake.data.persist.db.DbTransaction
import com.ake.data.persist.db.dao.PlanDao
import com.ake.data.persist.db.entity.PlanRevisionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `PlanStorePort` 实现。
 *
 * ## 版本号分配的并发处理
 *
 * `saveDraft` 读 `MAX(revision)` 再 +1，两个协程同时进来会算出同一个号。
 * 处理方式是 **`INSERT ... IGNORE` + 冲突自增重试**，而不是查询时加锁：
 *  · 这张表的写入只发生在"用户点重新生成"，冲突概率极低；
 *  · 复合主键上的 `IGNORE` 让第二次插入返回 `-1`，据此重算即可，不需要 `BEGIN IMMEDIATE`；
 *  · 重试上限 [MAX_REVISION_RETRY] 次后放弃并抛 —— **静默覆盖一版计划**比抛异常更糟。
 *
 * ## 为什么 `saveDraft` 里要先把旧草稿置为 SUPERSEDED
 *
 * 否则用户重生成三次，任务详情页会看到三张"待确认"的卡片。旧版本本身保留
 * （状态变了、行还在），审计仍能查到"当初那一版长什么样"。
 */
public class PlanStoreImpl(
    private val dao: PlanDao,
    private val tx: DbTransaction,
) : PlanStorePort {

    override suspend fun saveDraft(taskId: TaskId, goal: String, steps: List<PlanStep>): PlanRevision =
        tx.run {
            dao.supersedeDrafts(taskId.value)
            var revision = dao.maxRevision(taskId.value) + 1
            var attempt = 0
            val now = System.currentTimeMillis()
            val row = PlanRevisionEntity(
                taskId = taskId.value,
                revision = revision,
                goal = goal,
                stepsText = PlanCodec.encode(steps),
                status = PlanStatus.DRAFT,
                createdAt = now,
            )
            while (dao.insert(row.copy(revision = revision)) == -1L) {
                attempt++
                if (attempt > MAX_REVISION_RETRY) {
                    // 连续撞号说明有别的东西在并发写同一个任务 —— 这是真异常，必须让上层知道
                    error("plan revision allocation failed after $MAX_REVISION_RETRY retries")
                }
                revision = dao.maxRevision(taskId.value) + 1
            }
            PlanRevision(
                taskId = taskId,
                revision = revision,
                goal = goal,
                steps = steps,
                status = PlanStatus.DRAFT,
                createdAt = now,
            )
        }

    override suspend fun confirm(taskId: TaskId, revision: Int): Boolean =
        dao.confirm(taskId.value, revision) == 1

    override suspend fun latest(taskId: TaskId): PlanRevision? =
        runCatching { dao.latest(taskId.value) }
            .onFailure { AkeLog.w(TAG, "latest failed: ${it.message}") }
            .getOrNull()
            ?.toDomain(taskId)

    override fun observeLatest(taskId: TaskId): Flow<PlanRevision?> =
        dao.observeLatest(taskId.value).map { it?.toDomain(taskId) }

    private fun PlanRevisionEntity.toDomain(taskId: TaskId): PlanRevision = PlanRevision(
        taskId = taskId,
        revision = revision,
        goal = goal,
        steps = PlanCodec.decode(stepsText),
        status = status,
        createdAt = createdAt,
    )

    private companion object {
        const val TAG: String = "PlanStore"
        const val MAX_REVISION_RETRY: Int = 5
    }
}
