package com.ake.data.persist.recovery

import com.ake.data.persist.db.dao.RouteDecisionDao
import com.ake.data.persist.db.entity.RouteDecisionEntity

/**
 * `RouteDecisionDao` 的内存替身 —— **只实现审计闭环用到的那几条**，其余一律 `TODO()`。
 *
 * ★ 它存在的原因是让状态机与 sweep 可以在**纯 JVM**上跑（`docs/07` 的教训：
 *   真机验证一次 4.7 分钟，能毫秒级验的东西不要留给真机）。
 *
 * ⚠ **它不模拟 SQLite 的约束**（没有 UNIQUE、没有事务）。所以：
 *   - 状态机语义（谁能被改写成谁）由本替身**照抄 DAO 的守卫字符串**来表达；
 *   - 「UNIQUE 索引真的挡住了重复行」这种**数据库层**的性质由 `SqliteOracleV4Test` 验。
 *   两层互补，不能互相替代。
 */
internal class FakeRouteDecisionDao : RouteDecisionDao {

    val rows = LinkedHashMap<String, RouteDecisionEntity>()
    var insertIgnoredCount = 0

    private fun sweepable(id: String): Boolean =
        rows[id]?.outcome in RouteDecisionEntity.SWEEPABLE_OUTCOMES

    override suspend fun tryInsert(decision: RouteDecisionEntity): Long {
        // 模拟 INSERT OR IGNORE：同主键已存在 ⇒ 什么都不做，返回 -1
        if (rows.containsKey(decision.id)) {
            insertIgnoredCount++
            return -1L
        }
        rows[decision.id] = decision
        return 1L
    }

    override suspend fun byIdempotencyKey(key: String): RouteDecisionEntity? =
        rows.values.firstOrNull { it.idempotencyKey == key }

    override suspend fun byId(id: String): RouteDecisionEntity? = rows[id]

    override suspend fun countByTurn(turnId: String): Int =
        rows.values.count { it.turnId == turnId }

    override suspend fun lastIdByTurn(turnId: String): String? =
        rows.values.filter { it.turnId == turnId }.maxByOrNull { it.decidedAt }?.id

    override suspend fun converge(
        id: String,
        outcome: String,
        phase: String,
        deliveredBytes: Int,
        finishReasonRaw: String?,
        requestId: String?,
        now: Long,
    ): Int {
        if (!sweepable(id)) return 0
        val old = rows[id] ?: return 0
        rows[id] = old.copy(
            outcome = outcome,
            attemptPhase = phase,
            deliveredBytes = deliveredBytes,
            finishReasonRaw = finishReasonRaw,
            requestId = requestId,
            updatedAt = now,
        )
        return 1
    }

    override suspend fun heartbeat(id: String, now: Long): Int {
        val old = rows[id] ?: return 0
        if (old.outcome != "SENT") return 0
        rows[id] = old.copy(updatedAt = now)
        return 1
    }

    override suspend fun staleOpenIds(before: Long, limit: Int): List<String> =
        rows.values.filter {
            it.outcome in RouteDecisionEntity.SWEEPABLE_OUTCOMES && it.updatedAt < before
        }.sortedBy { it.updatedAt }.take(limit).map { it.id }

    override suspend fun convergeToUnknown(id: String, now: Long): Int {
        if (!sweepable(id)) return 0
        val old = rows[id] ?: return 0
        rows[id] = old.copy(outcome = "UNKNOWN", updatedAt = now)
        return 1
    }

    override suspend fun countUnknown(): Int = rows.values.count { it.outcome == "UNKNOWN" }

    override suspend fun countConvergedOrSent(): Int = rows.values.count {
        it.outcome in setOf("NOT_SENT", "SENT", "DELIVERED", "PARTIAL", "FAILED", "UNKNOWN")
    }

    override suspend fun probeTotalRows(): Int = rows.size

    override suspend fun probeNonCanonicalVerdicts(): Int = rows.values.count {
        it.egressGateVerdict != null &&
            it.egressGateVerdict !in setOf("UNSCREENED", "SCREENED", "GATE_DENIED")
    }

    // ── 未使用（本替身只服务审计闭环）
    override suspend fun insert(decision: RouteDecisionEntity) = TODO()
    override suspend fun countCloudAssisted(): Int = TODO()
    override suspend fun byTurn(turnId: String): List<RouteDecisionEntity> = TODO()
    override suspend fun byMessage(messageId: String?): List<RouteDecisionEntity> = TODO()
    override suspend fun revoke(id: String, now: Long): Int = TODO()
    override suspend fun deleteBefore(before: Long): Int = TODO()
    override suspend fun deleteKeepingNewest(keep: Int): Int = TODO()
    override suspend fun count(): Int = TODO()
}
