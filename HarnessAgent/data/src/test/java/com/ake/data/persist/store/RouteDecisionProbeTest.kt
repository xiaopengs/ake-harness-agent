package com.ake.data.persist.store

import com.ake.core.common.ManualClock
import com.ake.core.ports.DataClass
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.TaskKind
import com.ake.data.persist.db.AkeDatabaseProbe
import com.ake.data.persist.db.RouteDecisionVerdict
import com.ake.data.persist.recovery.FakeRouteDecisionDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★★ **S9 自检**：把「`route_decision` 今日恒空」这条**推理**做成可执行的验证（docs/09 §1.2-N1）。
 *
 * ## 为什么必须做这件事
 *
 * docs/09 里「本表恒空」是由三条代码事实**推**出来的，不是看出来的：
 *  ① `LocalOnlyRoutingPort.decide()` 必返 `LocalOnly`；
 *  ② 没有任何 UI 入口能表达"想出云"；
 *  ③ `forceAudit` 在 `PersistenceModule` 装配时根本没绑（注释说绑 `BuildConfig.DEBUG`，代码没绑）。
 *
 * 而 §5.3「存量保留字面量、不重写」的全部取舍都建立在它之上。**它是全文唯一
 * 靠推理而非实测的前提** ⇒ 必须有东西能直接证伪它。
 *
 * 三个出口（同一个判据）：
 *  ① `RouteDecisionSink.probe()` —— App 进程内；
 *  ② `AkeDatabaseProbe.PROBE_*` —— 纯 SQL，`adb` / device-verify / python 直接跑；
 *  ③ 真机拉库后跑同一句（外部 oracle）。
 *
 * ★ 本测试覆盖 ① 与 ②；③ 由真机执行（`docs/07` 的 M5 已能拉库）。
 */
class RouteDecisionProbeTest {

    private fun ctx(target: InferenceTarget) = RouteContext(
        agentId = "a1",
        sessionId = "s1",
        turnId = "t1",
        requested = target,
        userGrantedThisTurn = false,
        estimatedContextTokens = 0,
        needsLongContext = false,
        payloadClasses = emptySet<DataClass>(),
        kind = TaskKind.PureDialog,
    )

    private fun decision(target: InferenceTarget) = RoutingDecision(
        target = target,
        reasonKey = "route_local_only",
        allowedClasses = emptySet(),
        redactionApplied = true,
        decidedAt = 1L,
    )

    // ───────────────────────────── ① 判据本身：三条分支今天全部为假

    @Test
    fun shouldAuditIsFalseInTheMvpSteadyState() {
        val sink = RouteDecisionSink(dao = FakeRouteDecisionDao(), clock = ManualClock(0))
        // ★ MVP 稳态：请求与裁决都是 LocalOnly ⇒ 不写行 ⇒ 这就是「本表恒空」的那一半。
        assertThat(sink.shouldAudit(ctx(InferenceTarget.LocalOnly), decision(InferenceTarget.LocalOnly)))
            .isFalse()
    }

    /**
     * ★★ S9 的**过期闹钟**（必须保留，别当冗余删掉）。
     *
     * `shouldAudit` 的判据是「requested 或 decided 不是 LOCAL_ONLY」。
     * 而 `:inference/DefaultModelRouter` **已经**能产出 `CloudRequested`
     * （`DialogRoutingPort.decide()` 的 else 分支）⇒ **只要 `:chat` 一接到它，
     * `route_decision` 就会开始有行**，S9「恒空」的预测当场失效。
     *
     * ⇒ 本测试把这件事钉成一条**显式的事实**，而不是让它在半年后变成一个意外。
     *   注意：S9 的**预测**失效 ≠ §5.3 的**结论**失效 —— 新行写的都是规范值
     *   （`PASS`/`BLOCKED` 永远不会再被写入），「保留字面量、不重写」仍然成立。
     */
    @Test
    fun cloudRequestedAlreadyFlipsItOn_s9ExpiryAlarm() {
        val sink = RouteDecisionSink(dao = FakeRouteDecisionDao(), clock = ManualClock(0))
        assertThat(sink.shouldAudit(ctx(InferenceTarget.CloudRequested), decision(InferenceTarget.LocalOnly)))
            .isTrue()
        assertThat(sink.shouldAudit(ctx(InferenceTarget.LocalOnly), decision(InferenceTarget.CloudRequested)))
            .isTrue()
    }

    /**
     * ★ 负向用例：`forceAudit` 一旦打开就必须写行。
     *
     * 它证明上面那条"全 false"不是因为判据函数本身恒 false（那样的话这条测试
     * 就是唯一的防线，而它很可能是被写坏的）。
     */
    @Test
    fun forceAuditFlipsItOn_negativeControl() {
        val on = RouteDecisionSink(dao = FakeRouteDecisionDao(), clock = ManualClock(0), forceAudit = true)
        assertThat(on.shouldAudit(ctx(InferenceTarget.LocalOnly), decision(InferenceTarget.LocalOnly)))
            .isTrue()
    }

    /** ★ 默认值必须是 false —— 装配没绑 forceAudit 时靠它兜住（docs/09 §1.2-N1 ③）。 */
    @Test
    fun forceAuditDefaultsToFalse() {
        val sinkClass = RouteDecisionSink::class.java
        val ctor = sinkClass.constructors.first { it.parameterCount == 3 }
        // 用反射拿到 Kotlin 默认构造路径不方便 ⇒ 直接断言"三个参数的构造存在且默认 false"
        val sink = RouteDecisionSink(dao = FakeRouteDecisionDao(), clock = ManualClock(0))
        assertThat(sink.shouldAudit(ctx(InferenceTarget.LocalOnly), decision(InferenceTarget.LocalOnly)))
            .isFalse()
        assertThat(ctor).isNotNull()
    }

    // ───────────────────────────── ② 探针

    @Test
    fun probeReportsEmptyTableAsPredicted() = runBlocking {
        val dao = FakeRouteDecisionDao()
        val sink = RouteDecisionSink(dao = dao, clock = ManualClock(0))
        val probe = sink.probe()
        assertThat(probe).isNotNull()
        assertThat(probe!!.isEmptyAsPredicted).isTrue()
        assertThat(probe.verdictsAreCanonical).isTrue()
        assertThat(probe.totalRows).isEqualTo(0)
    }

    /** ★ 负向：表里真有行时，`isEmptyAsPredicted` 必须是 false（否则自检形同虚设）。 */
    @Test
    fun probeDetectsNonEmptyTable_negativeControl() = runBlocking {
        val dao = FakeRouteDecisionDao().apply {
            tryInsert(
                com.ake.data.persist.db.entity.RouteDecisionEntity(
                    id = "r1", chatMessageId = null, agentId = "a", sessionId = "s", turnId = "t",
                    requested = "CLOUD_REQUESTED", requestedClassesJson = "", decided = "CLOUD_REQUESTED",
                    reasonKey = "x", allowedClassesJson = "", egressGateVerdict = null,
                    redactionApplied = 1, payloadBytes = 0, revokedAt = null, decidedAt = 1,
                    outcome = "PENDING", attemptPhase = null, attemptNo = 0, retryOf = null,
                    idempotencyKey = null, credentialId = null, endpointHost = null,
                    modelName = null, requestId = null, deliveredBytes = 0,
                    finishReasonRaw = null, policyProfile = "p", updatedAt = 1, schemaV = 2,
                ),
            )
        }
        val sink = RouteDecisionSink(dao = dao, clock = ManualClock(0))
        val probe = sink.probe()
        assertThat(probe!!.isEmptyAsPredicted).isFalse()
        assertThat(probe.totalRows).isEqualTo(1)
    }

    /** ★ 负向：存量里出现 `PASS` 时，`verdictsAreCanonical` 必须翻脸。 */
    @Test
    fun probeDetectsLegacyVerdictLiteral_negativeControl() = runBlocking {
        val dao = FakeRouteDecisionDao().apply {
            tryInsert(
                com.ake.data.persist.db.entity.RouteDecisionEntity(
                    id = "r2", chatMessageId = null, agentId = "a", sessionId = "s", turnId = "t",
                    requested = "LOCAL_ONLY", requestedClassesJson = "", decided = "LOCAL_ONLY",
                    reasonKey = "x", allowedClassesJson = "", egressGateVerdict = "PASS",
                    redactionApplied = 1, payloadBytes = 0, revokedAt = null, decidedAt = 1,
                    outcome = null, attemptPhase = null, attemptNo = 0, retryOf = null,
                    idempotencyKey = null, credentialId = null, endpointHost = null,
                    modelName = null, requestId = null, deliveredBytes = 0,
                    finishReasonRaw = null, policyProfile = "p", updatedAt = 1, schemaV = 1,
                ),
            )
        }
        val sink = RouteDecisionSink(dao = dao, clock = ManualClock(0))
        assertThat(sink.probe()!!.verdictsAreCanonical).isFalse()
        // ★ 而读时规范化仍然救得回来 —— 这就是「保留字面量」策略能成立的原因
        assertThat(RouteDecisionVerdict.normalise("PASS")).isEqualTo("SCREENED")
    }

    // ───────────────────────────── ③ 纯 SQL 探针必须自洽

    @Test
    fun probeSqlIsRunnableAndSelfConsistent() {
        assertThat(AkeDatabaseProbe.EXPECTED_USER_VERSION).isEqualTo(4)
        assertThat(AkeDatabaseProbe.PROBE_ROUTE_DECISION_ROWS).isEqualTo("SELECT COUNT(*) FROM route_decision")
        assertThat(AkeDatabaseProbe.PROBE_NON_CANONICAL_VERDICTS).contains("NOT IN")
        assertThat(AkeDatabaseProbe.PROBE_UNCONVERGED_ROWS).contains("PENDING")
        // ★ 规范值集合必须与 verdict 常量同源，不能各写一份
        for (v in RouteDecisionVerdict.ALL) {
            assertThat(AkeDatabaseProbe.PROBE_NON_CANONICAL_VERDICTS).contains(v)
        }
    }
}
