package com.ake.chat.egress

import com.ake.chat.fixtures.FakeClock
import com.ake.core.common.Outcome
import com.ake.core.ports.DataClass
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.OutboundPayload
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * M9：`RoutingPort` + `EgressGate` + 审计（MVP 恒 `LOCAL_ONLY`）。
 *
 * 三件必须被机器守住的事：
 *  ① 决策恒为 LOCAL_ONLY，包含 `requested = CLOUD_ASSISTED` 的情况（Q4）；
 *  ② 一旦不是 CLOUD_ASSISTED，**payload 工厂一次都不会被调**（D0 在物理上没有机会进信封）；
 *  ③ 有人想出云时必须留审计（T12）。
 */
class ChatEgressTest {

    private val clock = FakeClock()
    private val routing = LocalOnlyRoutingPort(clock)
    private val gate = LocalOnlyEgressGate()

    private fun ctx(requested: InferenceTarget = InferenceTarget.LOCAL_ONLY) = RouteContext(
        agentId = "agent-1",
        sessionId = "s1",
        turnId = "t1",
        requested = requested,
        userGrantedThisTurn = requested == InferenceTarget.CLOUD_ASSISTED,
        estimatedContextTokens = 4_000,
        needsLongContext = false,
        payloadClasses = setOf(DataClass.AMBER),
    )

    private class RecordingAudit : ChatEgress.AuditSink {
        val rows: MutableList<Triple<InferenceTarget, InferenceTarget, String?>> = mutableListOf()
        override suspend fun record(
            ctx: RouteContext,
            decision: RoutingDecision,
            chatMessageId: String?,
            payloadBytes: Int,
            egressGateVerdict: String?,
        ) {
            rows += Triple(ctx.requested, decision.target, egressGateVerdict)
        }
    }

    @Test
    fun routingAlwaysDecidesLocalOnly() = runBlocking {
        for (requested in InferenceTarget.entries) {
            val d = routing.decide(ctx(requested))
            assertThat(d.target).isEqualTo(InferenceTarget.LOCAL_ONLY)
            assertThat(d.allowedClasses).isEmpty()
            assertThat(d.reasonKey).isEqualTo(LocalOnlyRoutingPort.REASON_LOCAL_ONLY)
            // 「按最严处理」是显式的，UI 不能误读成「已经脱敏过了所以可以发」
            assertThat(d.redactionApplied).isTrue()
        }
    }

    @Test
    fun egressGateRefusesAnythingUnderLocalOnly() {
        val payload = OutboundPayload.CapabilityCatalog("""{"skills":[]}""")
        val decision = RoutingDecision(
            target = InferenceTarget.LOCAL_ONLY,
            reasonKey = "route_local_only",
            allowedClasses = emptySet(),
            redactionApplied = true,
            decidedAt = 0L,
        )
        val r = gate.screen(payload, decision)
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(LocalOnlyEgressGate.CODE_LOCAL_ONLY)
    }

    @Test
    fun egressGateAllowsOnlyWhenCloudIsExplicitlyDecidedAndClassesAreAllowed() {
        val payload = OutboundPayload.CapabilityCatalog("""{"skills":[]}""")
        val cloud = RoutingDecision(
            target = InferenceTarget.CLOUD_ASSISTED,
            reasonKey = "route_cloud",
            allowedClasses = setOf(DataClass.GREEN),
            redactionApplied = true,
            decidedAt = 0L,
        )
        val ok = gate.screen(payload, cloud)
        assertThat(ok).isInstanceOf(Outcome.Ok::class.java)
        assertThat((ok as Outcome.Ok).value.bytes).isGreaterThan(0)

        val noClasses = gate.screen(payload, cloud.copy(allowedClasses = emptySet()))
        assertThat((noClasses as Outcome.Err).error.code).isEqualTo(LocalOnlyEgressGate.CODE_NO_ALLOWED_CLASS)
    }

    @Test
    fun attemptNeverConstructsThePayloadWhenLocalOnly() = runBlocking {
        val audit = RecordingAudit()
        val egress = ChatEgress(routing, gate, audit, clock)
        var constructed = false

        val outcome = egress.attempt(ctx()) {
            constructed = true
            OutboundPayload.AbstractPlan(listOf("step"))
        }

        // ★ 结构性质：没被裁定允许出云时，payload 连「被构造」这个中间态都不存在
        assertThat(constructed).isFalse()
        assertThat(outcome).isInstanceOf(ChatEgress.EgressOutcome.Denied::class.java)
        assertThat((outcome as ChatEgress.EgressOutcome.Denied).reasonKey)
            .isEqualTo(LocalOnlyRoutingPort.REASON_LOCAL_ONLY)
        // 每一次 attempt 都要留一行审计
        assertThat(audit.rows).hasSize(1)
    }

    @Test
    fun auditSeesTheRequestEvenWhenTheAnswerIsLocalOnly() = runBlocking {
        val audit = RecordingAudit()
        val egress = ChatEgress(routing, gate, audit, clock)

        egress.decide(ctx(InferenceTarget.CLOUD_ASSISTED))

        // T12：事后要能回答「这条数据到底出过国没有」⇒ 提出请求这件事本身必须可查
        assertThat(audit.rows.single().first).isEqualTo(InferenceTarget.CLOUD_ASSISTED)
        assertThat(audit.rows.single().second).isEqualTo(InferenceTarget.LOCAL_ONLY)
    }

    @Test
    fun oversizedPayloadIsRejected() {
        val huge = OutboundPayload.CapabilityCatalog("x".repeat(LocalOnlyEgressGate.MAX_BYTES + 1))
        val cloud = RoutingDecision(
            target = InferenceTarget.CLOUD_ASSISTED,
            reasonKey = "route_cloud",
            allowedClasses = setOf(DataClass.GREEN),
            redactionApplied = true,
            decidedAt = 0L,
        )
        val r = gate.screen(huge, cloud)
        assertThat((r as Outcome.Err).error.code).isEqualTo(LocalOnlyEgressGate.CODE_TOO_LARGE)
    }
}
