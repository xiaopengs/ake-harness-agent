package com.ake.data.persist.recovery

import com.ake.data.persist.db.entity.RouteDecisionEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★ 审计表状态机 + 冷启 sweep（docs/09 §6.1 / §6.3）。
 *
 * ```
 *   (INSERT PENDING) ─► SENT ─┬─► DELIVERED | PARTIAL | FAILED   [终态]
 *                             │
 *                    ┌────────┘
 *   PENDING ─► NOT_SENT (PRE 失败，零字节)                        [终态]
 *   sweep:  PENDING | SENT | UNKNOWN ─► UNKNOWN  ──►（可回改）
 * ```
 *
 * ★★ 本文件最重要的三条：
 *  ① **UNKNOWN 不是终态** —— 迟到的 `close()` 可以把它改回 DELIVERED；
 *  ② sweep **幂等** —— 重复扫不把终态掀回中间态；
 *  ③ 替身**不模拟 SQLite 约束** ⇒ 「UNIQUE 真能挡住重复行」由 `SqliteOracleV4Test` 验，
 *     本文件只验状态机语义。
 */
class EgressAuditReconcilerTest {

    private fun row(
        id: String,
        outcome: String?,
        updatedAt: Long = 0L,
        turnId: String = "t1",
    ): RouteDecisionEntity = RouteDecisionEntity(
        id = id,
        chatMessageId = null,
        agentId = "a1",
        sessionId = "s1",
        turnId = turnId,
        requested = "LOCAL_ONLY",
        requestedClassesJson = "",
        decided = "LOCAL_ONLY",
        reasonKey = "route_local_only",
        allowedClassesJson = "",
        egressGateVerdict = null,
        redactionApplied = 1,
        payloadBytes = 0,
        revokedAt = null,
        decidedAt = updatedAt,
        outcome = outcome,
        attemptPhase = null,
        attemptNo = 0,
        retryOf = null,
        idempotencyKey = null,
        credentialId = null,
        endpointHost = null,
        modelName = null,
        requestId = null,
        deliveredBytes = 0,
        finishReasonRaw = null,
        policyProfile = "test",
        updatedAt = updatedAt,
        schemaV = RouteDecisionEntity.SCHEMA_VERSION,
    )

    private fun reconciler(dao: FakeRouteDecisionDao, now: Long) = EgressAuditReconciler(
        dao = dao,
        clock = com.ake.core.common.ManualClock(now),
        metrics = com.ake.data.persist.write.PersistenceMetrics(),
        staleAfterMs = 1_000L,
    )

    // ───────────────────────────── ① 状态机

    @Test
    fun unknownIsRevisable_notTerminal() = runBlocking {
        val dao = FakeRouteDecisionDao().apply { rows["r1"] = row("r1", "UNKNOWN") }
        // ★ 迟到的 close()：UNKNOWN 必须能被改回 DELIVERED
        val rows = dao.converge("r1", "DELIVERED", "POST", 100, "stop", "req-1", now = 9)
        assertThat(rows).isEqualTo(1)
        assertThat(dao.rows["r1"]!!.outcome).isEqualTo("DELIVERED")
    }

    @Test
    fun terminalStatesAreNotRewritten() = runBlocking {
        val dao = FakeRouteDecisionDao().apply { rows["r1"] = row("r1", "DELIVERED") }
        // ★ 已经 DELIVERED ⇒ 再 close 一次不得把它掀回 PENDING/UNKNOWN
        assertThat(dao.converge("r1", "FAILED", "POST", 0, null, null, now = 9)).isEqualTo(0)
        assertThat(dao.rows["r1"]!!.outcome).isEqualTo("DELIVERED")
    }

    @Test
    fun pendingAndSentAreConvergeable() = runBlocking {
        for (from in listOf("PENDING", "SENT", "UNKNOWN")) {
            val dao = FakeRouteDecisionDao().apply { rows["r"] = row("r", from) }
            assertThat(dao.converge("r", "FAILED", "POST", 0, null, null, now = 1)).isEqualTo(1)
            assertThat(dao.rows["r"]!!.outcome).isEqualTo("FAILED")
        }
    }

    /** ★ 负向用例：如果守卫里漏掉了 UNKNOWN，上面那条就会失败 —— 这里显式证明守卫含三项。 */
    @Test
    fun sweepableSetContainsUnknownItself() {
        assertThat(RouteDecisionEntity.SWEEPABLE_OUTCOMES)
            .containsExactly("PENDING", "SENT", "UNKNOWN")
    }

    @Test
    fun heartbeatOnlyTouchesSentRows() = runBlocking {
        val dao = FakeRouteDecisionDao().apply {
            rows["sent"] = row("sent", "SENT", updatedAt = 0)
            rows["done"] = row("done", "DELIVERED", updatedAt = 0)
        }
        assertThat(dao.heartbeat("sent", 500)).isEqualTo(1)
        assertThat(dao.rows["sent"]!!.updatedAt).isEqualTo(500)
        assertThat(dao.heartbeat("done", 500)).isEqualTo(0)
    }

    // ───────────────────────────── ② 冷启 sweep

    @Test
    fun sweepMarksStalePendingAsUnknown() = runBlocking {
        val dao = FakeRouteDecisionDao().apply {
            rows["old"] = row("old", "PENDING", updatedAt = 0)
            rows["fresh"] = row("fresh", "PENDING", updatedAt = 10_000)
        }
        val report = reconciler(dao, now = 10_000).sweep()
        assertThat(report.convergedUnknown).isEqualTo(1)
        assertThat(dao.rows["old"]!!.outcome).isEqualTo("UNKNOWN")
        // ★ 还在 TTL 内的不动 —— 误杀活请求比暂时留着孤儿糟糕得多
        assertThat(dao.rows["fresh"]!!.outcome).isEqualTo("PENDING")
    }

    /**
     * ★ sweep 重复跑不产生副作用。
     *
     * 注意真实行为：第一次 sweep 会把 `updated_at` 刷成当前时间 ⇒ 该行**不再 stale**
     * ⇒ 第二次扫的 `convergedUnknown` 应该是 **0**（不是 1）。
     * 这正是我们想要的：sweep 一次之后不要被反复改写，
     * 而迟到的 `close()` 仍然能通过「UNKNOWN 可回改」把值改回去。
     */
    @Test
    fun sweepIsIdempotent() = runBlocking {
        val dao = FakeRouteDecisionDao().apply { rows["r"] = row("r", "PENDING", updatedAt = 0) }
        val r = reconciler(dao, now = 10_000)
        assertThat(r.sweep().convergedUnknown).isEqualTo(1)
        val second = r.sweep()
        assertThat(second.convergedUnknown).isEqualTo(0)   // ← 已刷 updated_at，不再 stale
        assertThat(dao.rows["r"]!!.outcome).isEqualTo("UNKNOWN")
        // ★ 而且迟到的 close 仍然能改回来
        assertThat(dao.converge("r", "PARTIAL", "POST", 10, "length", "req-9", now = 99)).isEqualTo(1)
        assertThat(dao.rows["r"]!!.outcome).isEqualTo("PARTIAL")
    }

    @Test
    fun sweepSkipsTerminalRows() = runBlocking {
        val dao = FakeRouteDecisionDao().apply {
            rows["a"] = row("a", "DELIVERED", updatedAt = 0)
            rows["b"] = row("b", "NOT_SENT", updatedAt = 0)
            rows["c"] = row("c", "FAILED", updatedAt = 0)
        }
        val report = reconciler(dao, now = 10_000).sweep()
        assertThat(report.convergedUnknown).isEqualTo(0)
        assertThat(dao.rows["a"]!!.outcome).isEqualTo("DELIVERED")
    }

    /** ★ TTL 必须比 `EgressPermitSink.PENDING_TTL_MS` 宽松 —— 长流式响应不能被误判。 */
    @Test
    fun sweepTtlIsLooserThanPermitTtl() {
        assertThat(EgressAuditReconciler.PENDING_TTL_MS)
            .isGreaterThan(com.ake.core.ports.EgressPermitSink.PENDING_TTL_MS)
    }

    // ───────────────────────────── ③ 幂等键（数据库层由 oracle 验，这里验语义）

    @Test
    fun duplicateInsertIsIgnoredNotOverwritten() = runBlocking {
        val dao = FakeRouteDecisionDao()
        val r = row("r1", "PENDING")
        assertThat(dao.tryInsert(r)).isEqualTo(1L)
        // ★ 同主键再插一次 ⇒ IGNORE（返回 -1），既有行不被覆盖
        assertThat(dao.tryInsert(r.copy(outcome = "DELIVERED"))).isEqualTo(-1L)
        assertThat(dao.rows["r1"]!!.outcome).isEqualTo("PENDING")
        assertThat(dao.insertIgnoredCount).isEqualTo(1)
    }
}
