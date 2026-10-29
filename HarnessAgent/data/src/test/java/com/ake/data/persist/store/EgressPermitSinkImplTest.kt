package com.ake.data.persist.store

import com.ake.core.common.ManualClock
import com.ake.core.common.Outcome
import com.ake.core.common.Ulid
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DataClass
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.TaskKind
import com.ake.core.ports.TlsMode
import com.ake.data.persist.db.dao.RouteDecisionDao
import com.ake.data.persist.db.entity.CloudCredentialEntity
import com.ake.data.persist.db.entity.RouteDecisionEntity
import com.ake.data.persist.fake.AadStrictCipher
import com.ake.data.persist.fake.FakeChatSessionDao
import com.ake.data.persist.fake.FakeCloudCredentialDao
import com.ake.data.persist.fake.RecordingTransaction
import com.ake.data.persist.recovery.EgressAuditReconciler
import com.ake.data.persist.recovery.FakeRouteDecisionDao
import com.ake.data.persist.write.PersistenceMetrics
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★★ 出境许可的落点与审计闭环（docs/09 §6）。
 *
 * ## 三条最重要的性质
 *
 *  ① **fail-closed**：拿不到 permit ⇒ 构造不出 `InferenceTarget.CloudAssisted`
 *     ⇒ 一个字节都发不出去。所以 `open()` 的**每一种**失败都必须表现为 `Err`，
 *     而不是抛异常（抛异常在调用侧可能被 catch 成「继续出云」的绿灯）。
 *  ② **幂等**：同一次尝试重复 `open()` 返回**同一个** `auditRowId`，不产生第二行。
 *  ③ **UNKNOWN 不是终态**：sweep 标完，迟到的 `close()` 仍能改回 DELIVERED。
 *
 * ## ⚠ 替身的能力边界
 *
 * `FakeRouteDecisionDao` / `RecordingTransaction` **不模拟 SQLite 的 UNIQUE 与事务隔离**
 * ⇒ 「UNIQUE 真能挡住并发第二次插入」这一条**不在本文件验**，由 `SqliteOracleV4Test`
 * 与真机承担。本文件验的是**顺序语义**（先查幂等键、再插、再读回）。
 */
class EgressPermitSinkImplTest {

    private val credRef = CredentialRef("cred-1")

    private fun ctx(turn: String = "t1") = RouteContext(
        agentId = "a1",
        sessionId = "s1",
        turnId = turn,
        requested = InferenceTarget.CloudRequested,
        userGrantedThisTurn = true,
        estimatedContextTokens = 0,
        needsLongContext = false,
        payloadClasses = setOf(DataClass.GREEN),
        kind = TaskKind.PureDialog,
    )

    private fun decision(at: Long) = RoutingDecision(
        target = InferenceTarget.CloudRequested,
        reasonKey = "route_cloud_assisted",
        allowedClasses = setOf(DataClass.GREEN),
        redactionApplied = true,
        decidedAt = at,
    )

    private fun credentialRow(deletedAt: Long? = null) = CloudCredentialEntity(
        credId = "cred-1",
        label = "gw",
        endpointHost = "openai.example.com",
        baseUrlEnc = ByteArray(0),
        secretEnc = ByteArray(0),
        modelName = "gpt-x",
        tlsMode = TlsMode.REQUIRED.name,
        declaredContextWindow = 128_000,
        createdAt = 1,
        updatedAt = 1,
        lastUsedAt = null,
        deletedAt = deletedAt,
        schemaV = CloudCredentialEntity.SCHEMA_VERSION,
    )

    /**
     * ★★ 替身必须**共享同一批 DAO 实例**：`sink` 与断言各持一份会导致
     * 「改了 A、断言读到 B」的假绿 —— 那是替身最容易骗人的一处。
     */
    private inner class Fixture(
        credDeletedAt: Long? = null,
        bindCredential: Boolean = true,
        val routeDao: RouteDecisionDao = FakeRouteDecisionDao(),
        val clock: ManualClock = ManualClock(1_000L),
    ) {
        val creds: FakeCloudCredentialDao = FakeCloudCredentialDao().apply {
            if (bindCredential || credDeletedAt != null) rows["cred-1"] = credentialRow(credDeletedAt)
        }
        val sessions: FakeChatSessionDao = FakeChatSessionDao().apply {
            if (bindCredential) credentialOf["s1"] = "cred-1"
        }
        val tx = RecordingTransaction()
        val sink = EgressPermitSinkImpl(
            tx = tx,
            routeDao = routeDao,
            sessions = sessions,
            credentials = creds,
            reconciler = EgressAuditReconciler(dao = routeDao, clock = clock, metrics = PersistenceMetrics()),
            clock = clock,
        )
        val rows: LinkedHashMap<String, RouteDecisionEntity>
            get() = (routeDao as FakeRouteDecisionDao).rows

        val enteredTransactions: Int get() = tx.entered
    }

    private fun fixture(
        credDeletedAt: Long? = null,
        bindCredential: Boolean = true,
        routeDao: RouteDecisionDao = FakeRouteDecisionDao(),
        clock: ManualClock = ManualClock(1_000L),
    ) = Fixture(credDeletedAt, bindCredential, routeDao, clock)

    // ───────────────────────────── ① 正常路径

    @Test
    fun openWritesOnePendingRowAndReturnsPermit() = runBlocking {
        val f = fixture()
        val permit = f.sink.open(ctx(), decision(500L))

        assertThat(permit).isInstanceOf(Outcome.Ok::class.java)
        val p = (permit as Outcome.Ok).value
        assertThat(p.credentialRef).isEqualTo(credRef)
        assertThat(p.endpointHost).isEqualTo("openai.example.com")
        assertThat(p.decidedAt).isEqualTo(500L)
        assertThat(p.allowedAttempts).isEqualTo(1)

        assertThat(f.rows).hasSize(1)
        val row = f.rows.values.single()
        assertThat(row.outcome).isEqualTo(AuditOutcome.PENDING.name)
        assertThat(row.idempotencyKey).isEqualTo("t1:500")
        assertThat(row.attemptNo).isEqualTo(0)
        assertThat(row.retryOf).isNull()
        assertThat(row.credentialId).isEqualTo("cred-1")
        assertThat(row.policyProfile).isEqualTo(RouteDecisionSink.POLICY_PROFILE)
        assertThat(row.id).isEqualTo(p.auditRowId)
        // ★ 事务确实被进入过（不是裸写），且被正确退出
        assertThat(f.tx.entered).isEqualTo(1)
        assertThat(f.tx.maxDepth).isEqualTo(1)
    }

    // ───────────────────────────── ② ★ 幂等

    /**
     * ★★ 同一个 decision 重播 ⇒ **同一个 permit、同一行**。
     *
     * 「同一次尝试 ⇒ 同一行 ⇒ 同一个 permit」是结构性保证，靠
     * `UNIQUE(idx_route_idem)` + `INSERT OR IGNORE` + 读回三段合成。
     */
    @Test
    fun openIsIdempotentForTheSameDecision() = runBlocking {
        val f = fixture()
        val first = f.sink.open(ctx(), decision(500L)) as Outcome.Ok
        val second = f.sink.open(ctx(), decision(500L)) as Outcome.Ok

        assertThat(second.value.auditRowId).isEqualTo(first.value.auditRowId)
        assertThat(f.rows).hasSize(1)
    }

    /** ★ 重试（新的 decision 时间戳）⇒ **新的一行**，且串起 `retry_of`。 */
    @Test
    fun retryCreatesASeparateRowAndChainsRetryOf() = runBlocking {
        val f = fixture()
        val first = f.sink.open(ctx(), decision(500L)) as Outcome.Ok
        val second = f.sink.open(ctx(), decision(900L)) as Outcome.Ok

        assertThat(second.value.auditRowId).isNotEqualTo(first.value.auditRowId)
        assertThat(f.rows).hasSize(2)
        val newest = f.rows[second.value.auditRowId]!!
        assertThat(newest.attemptNo).isEqualTo(1)
        assertThat(newest.retryOf).isEqualTo(first.value.auditRowId)
    }

    // ───────────────────────────── ③ ★ fail-closed

    /** 会话没登记凭证 ⇒ 拿不到 permit（而不是拿到一个「无凭证的许可」）。 */
    @Test
    fun openFailsClosedWhenSessionHasNoCredential() = runBlocking {
        val f = fixture(bindCredential = false)
        assertThat(f.sink.open(ctx(), decision(500L))).isInstanceOf(Outcome.Err::class.java)
        assertThat(f.rows).isEmpty()
    }

    /** 凭证已被粉碎 ⇒ 拿不到 permit。 */
    @Test
    fun openFailsClosedWhenCredentialIsShredded() = runBlocking {
        val f = fixture(credDeletedAt = 7L)
        assertThat(f.sink.open(ctx(), decision(500L))).isInstanceOf(Outcome.Err::class.java)
        assertThat(f.rows).isEmpty()
    }

    /** 会话登记了凭证但凭证行不存在 ⇒ 拿不到 permit（不静默放行）。 */
    @Test
    fun openFailsClosedWhenCredentialRowIsMissing() = runBlocking {
        val f = fixture()
        f.creds.rows.clear()
        assertThat(f.sink.open(ctx(), decision(500L))).isInstanceOf(Outcome.Err::class.java)
        assertThat(f.rows).isEmpty()
    }

    /**
     * ★★ DAO 抛异常 ⇒ 表现为 `Err`，**不抛出去**。
     *
     * 这是 fail-closed 最容易被写坏的一处：如果异常直接冒泡，调用侧一个
     * `catch` 就可能被当成「继续出云」的绿灯。必须显式验它变成了 Err。
     */
    @Test
    fun openFailsClosedWhenTheDaoThrows() = runBlocking {
        val boom = object : RouteDecisionDao by FakeRouteDecisionDao() {
            override suspend fun byIdempotencyKey(key: String): RouteDecisionEntity? = error("boom")
        }
        val f = fixture(routeDao = boom)
        assertThat(f.sink.open(ctx(), decision(500L))).isInstanceOf(Outcome.Err::class.java)
    }

    // ───────────────────────────── ④ 收敛（close）

    @Test
    fun closeConvergesTheRowToATerminalOutcome() = runBlocking {
        val f = fixture()
        val p = (f.sink.open(ctx(), decision(500L)) as Outcome.Ok).value

        assertThat(f.sink.close(p, AuditOutcome.DELIVERED, EgressPhase.POST, 2048, "stop"))
            .isInstanceOf(Outcome.Ok::class.java)

        val row = f.rows[p.auditRowId]!!
        assertThat(row.outcome).isEqualTo("DELIVERED")
        assertThat(row.attemptPhase).isEqualTo("POST")
        assertThat(row.deliveredBytes).isEqualTo(2048)
        assertThat(row.finishReasonRaw).isEqualTo("stop")
        assertThat(row.requestId).isEqualTo(p.auditRowId)
    }

    /** ★ 重复 close 不改变终态（守卫 `outcome IN ('PENDING','SENT','UNKNOWN')`）。 */
    @Test
    fun closeIsIdempotentOnceTerminal() = runBlocking {
        val f = fixture()
        val p = (f.sink.open(ctx(), decision(500L)) as Outcome.Ok).value
        f.sink.close(p, AuditOutcome.DELIVERED, EgressPhase.POST, 10, null)
        f.sink.close(p, AuditOutcome.FAILED, EgressPhase.POST, 0, null)
        assertThat(f.rows[p.auditRowId]!!.outcome).isEqualTo("DELIVERED")
    }

    // ───────────────────────────── ⑤ sweep 与心跳

    /** ★ 冷启 sweep：超时未收敛的行被推到 UNKNOWN，且**迟到的 close 仍能改回来**。 */
    @Test
    fun sweepConvergesStaleRowsAndLateCloseStillWins() = runBlocking {
        val f = fixture()
        val p = (f.sink.open(ctx(), decision(500L)) as Outcome.Ok).value

        f.clock.setTo(1_000L + EgressAuditReconciler.PENDING_TTL_MS + 1)
        assertThat(f.sink.sweepPending(EgressAuditReconciler.PENDING_TTL_MS)).isEqualTo(1)
        assertThat(f.rows[p.auditRowId]!!.outcome).isEqualTo("UNKNOWN")

        // ★ 迟到的 close：UNKNOWN 不是终态
        f.sink.close(p, AuditOutcome.PARTIAL, EgressPhase.POST, 7, "length")
        assertThat(f.rows[p.auditRowId]!!.outcome).isEqualTo("PARTIAL")
        assertThat(f.sink.countUnknown()).isEqualTo(0)
    }

    /** 心跳只刷 `SENT` 行 —— 让活着的长流不被误标 UNKNOWN。 */
    @Test
    fun heartbeatOnlyRefreshesSentRows() = runBlocking {
        val f = fixture()
        val p = (f.sink.open(ctx(), decision(500L)) as Outcome.Ok).value
        // 刚 open 的行是 PENDING ⇒ 心跳不刷
        assertThat(f.sink.heartbeat(p)).isEqualTo(0)
        f.rows[p.auditRowId] = f.rows[p.auditRowId]!!.copy(outcome = "SENT")
        assertThat(f.sink.heartbeat(p)).isEqualTo(1)
    }

    // ───────────────────────────── ⑥ 落库内容的最小值

    /**
     * ★ 审计行**不含任何原文**：只有类别与体积。
     *
     * 这一条不是"顺便验一下"，它是 `route_decision` 能与 `chat_message` 解耦保留
     * （90 天 + 5000 行上限）的前提 —— 留着它不增加隐私风险。
     */
    @Test
    fun auditRowCarriesNoPayloadText() = runBlocking {
        val f = fixture()
        val p = (f.sink.open(ctx(), decision(500L)) as Outcome.Ok).value
        val row = f.rows[p.auditRowId]!!
        assertThat(row.requestedClassesJson).isEqualTo("GREEN")
        assertThat(row.allowedClassesJson).isEqualTo("GREEN")
        assertThat(row.payloadBytes).isEqualTo(0)
        // 兜一遍：实体不得有任何能装原文的字段（新增列时这里会红）
        val suspicious = RouteDecisionEntity::class.java.declaredFields.map { it.name }
            .filter { it.contains("content", true) || it.contains("text", true) || it.contains("body", true) }
        assertThat(suspicious).isEmpty()
    }

    /** ★ permit 的 id 是可排序 ULID（审计行按时间有序 ⇒ sweep 的 `ORDER BY` 有意义）。 */
    @Test
    fun permitIdsAreUlids() = runBlocking {
        val f = fixture()
        val a = (f.sink.open(ctx("t1"), decision(500L)) as Outcome.Ok).value.auditRowId
        val b = (f.sink.open(ctx("t2"), decision(600L)) as Outcome.Ok).value.auditRowId
        assertThat(a).isNotEqualTo(b)
        assertThat(a.length).isEqualTo(Ulid.next(0L).length)
    }

    /** ★ 顺带钉住：凭证 store 与 permit 用的是同一份 host（不是各归一一次）。 */
    @Test
    fun permitHostComesFromTheStoredNormalisedHost() = runBlocking {
        val creds = FakeCloudCredentialDao().apply { rows["cred-1"] = credentialRow() }
        val url = "https://WWW.Example.com:443/v1"
        val store = CloudCredentialStoreImpl(dao = creds, cipher = AadStrictCipher(), clock = ManualClock(1))
        store.upsert(credRef, EndpointConfig(url, "m", TlsMode.REQUIRED, null, "l"), PlainSecret.of("k"))
        assertThat(creds.byId("cred-1")!!.endpointHost).isEqualTo("example.com")

        val f = fixture()
        // ★ 把 sink 的凭证 dao 指向刚写入的那一份（共享实例，不是另一份替身）
        f.creds.rows["cred-1"] = creds.byId("cred-1")!!
        val p = (f.sink.open(ctx(), decision(500L)) as Outcome.Ok).value
        assertThat(p.endpointHost).isEqualTo("example.com")
    }
}
