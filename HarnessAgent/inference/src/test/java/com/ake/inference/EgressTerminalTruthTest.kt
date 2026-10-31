package com.ake.inference

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.ports.AnnouncementKind
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudCallOutcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.EgressAnnouncementSink
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.EgressResult
import com.ake.core.ports.FallbackAnnouncement
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.TaskKind
import com.ake.inference.FallbackPolicy.TerminalAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * ★★ **「告知恒等于事实」的结构性证明**（2026-10 主理人裁决）。
 *
 * ## 缺陷的形状：**两个来源各说一半**
 *
 * 旧实现的 `while` 尾部是「先 `announce(kind 由 failure.phase 推)`，
 * 再 `return` 一个由循环落点决定的 `EgressResult`」⇒ 两者之间没有约束。
 * 于是**任何**落到 `return EgressResult.Failed` 的路径都会吐出
 * `FELL_BACK_TO_LOCAL` 告知 ⇒ UI 说「已由本机完成」、本机一次都没跑。
 *
 * `ConnectFailed` 只是**第一个被撞到的**，不是唯一的一个：
 * 「PRE 重试时 permit 重开失败」那一条也是同一处错位。
 *
 * ## 这组测试要证明的不是「某个 case 对了」
 *
 * 而是三条**全称命题**：
 *  ① `EgressResult` 与 `AnnouncementKind` **一一对应**（17 个失败形态 × 重试耗尽，逐个驱动）；
 *  ② 编排**不再**产出 `EgressResult.Failed`（它没有诚实的告知形态）；
 *  ③ `terminalFor` 与 `phase` 恒一致 ⇒ 「PRE 却不让回落」这种形态一旦出现就会红
 *     （那时必须给 `AnnouncementKind` 加第三个值，属 `:core:ports`，需单独一轮）。
 */
class EgressTerminalTruthTest {

    // ───────────────────────────── 替身 ─────────────────────────────

    /** 记开/关的 permit 替身。`failOpenFrom` = 第几次 `open` 开始失败。 */
    private class Permits(private val failOpenFrom: Int = Int.MAX_VALUE) : EgressPermitSink {
        var openCount = 0
            private set
        val closed = mutableListOf<AuditOutcome>()
        val delivered = mutableListOf<Int>()

        override suspend fun open(ctx: RouteContext, decision: RoutingDecision): Outcome<EgressPermit> {
            openCount++
            if (openCount >= failOpenFrom) {
                return Outcome.Err(AppError(code = "TEST_OPEN_DENIED", reasonKey = "err_test", severity = Severity.LOW))
            }
            return Outcome.Ok(
                EgressPermit(
                    auditRowId = "row-$openCount",
                    decidedAt = 1L,
                    endpointHost = "gw.example.com",
                    credentialRef = CredentialRef("svc-a"),
                ),
            )
        }

        override suspend fun close(
            permit: EgressPermit,
            outcome: AuditOutcome,
            phase: EgressPhase,
            deliveredBytes: Int,
            finishReasonRaw: String?,
        ): Outcome<Unit> {
            closed += outcome
            delivered += deliveredBytes
            return Outcome.Ok(Unit)
        }

        override suspend fun sweepPending(olderThanMs: Long): Int = 0
    }

    private companion object {
        /** 反射枚举全部失败形态。★ 不用手写列表：新增变体会自动进来。 */
        fun allFailures(): List<CloudFailure> =
            CloudFailure::class.java.permittedSubclasses.mapNotNull { cls ->
                runCatching { cls.getDeclaredField("INSTANCE").get(null) }.getOrNull() as? CloudFailure
            }

        fun ctx(): RouteContext = RouteContext(
            agentId = "a1", sessionId = "s1", turnId = "t1",
            requested = InferenceTarget.CloudRequested,
            userGrantedThisTurn = true,
            estimatedContextTokens = 512,
            needsLongContext = false,
            payloadClasses = emptySet(),
            kind = TaskKind.PureDialog,
            page = null,
        )

        val DECISION: RoutingDecision = RoutingDecision(
            target = InferenceTarget.CloudRequested,
            reasonKey = "route_cloud_allowed",
            allowedClasses = emptySet(),
            redactionApplied = true,
            decidedAt = 1L,
        )

        /** POST 阶段「已经发出去」的字节数；PRE 恒 0（那一步根本没发）。 */
        fun bytesFor(f: CloudFailure): Int = if (f.phase == EgressPhase.POST) 512 else 0
    }

    /** 驱动一次完整编排：`call` **永远**失败（⇒ 必然把重试预算走完）。 */
    private suspend fun drive(
        failure: CloudFailure,
        permits: Permits = Permits(),
    ): Pair<EgressResult, List<FallbackAnnouncement>> {
        val announcements = mutableListOf<FallbackAnnouncement>()
        val coordinator = DefaultEgressCoordinator(
            permits,
            EgressAnnouncementSink { announcements += it },
        )
        var calls = 0
        val result = coordinator.execute(ctx(), DECISION) { _ ->
            calls++
            CloudCallOutcome.Failed(failure, bytesFor(failure))
        }
        // ★ 反证：链路是活的（不是「一次都没调就返回了」）
        assertThat(calls).isEqualTo(1 + FallbackPolicy.maxRetries(failure))
        return result to announcements
    }

    // ═══════ ① 全称命题：17 个形态 × 重试耗尽，告知与终态一一对应 ═══════

    @Test
    fun everyFailureShape_announcementMatchesItsTerminalResult() = runTest {
        assertThat(allFailures()).hasSize(17)

        for (f in allFailures()) {
            val (result, announcements) = drive(f)

            // ★★ 判据一：不可能是 `Failed` —— 它没有诚实的告知形态
            assertThat(result).isNotInstanceOf(EgressResult.Failed::class.java)
            assertThat(result).isNotInstanceOf(EgressResult.NotPermitted::class.java)

            // ★★ 判据二：恰有一条告知，且 kind 与终态**严格对应**
            assertThat(announcements).hasSize(1)
            val a = announcements.single()
            assertThat(a.reasonKey).isEqualTo(f.reasonKey)
            assertThat(a.isWellFormed()).isTrue()

            when (result) {
                is EgressResult.FellBackToLocal -> {
                    assertThat(f.phase).isEqualTo(EgressPhase.PRE)
                    assertThat(a.kind).isEqualTo(AnnouncementKind.FELL_BACK_TO_LOCAL)
                    // 「已由本机完成」这句话里出现 host 是自相矛盾的
                    assertThat(a.host).isNull()
                }

                is EgressResult.NeedsUserDecision -> {
                    assertThat(f.phase).isEqualTo(EgressPhase.POST)
                    assertThat(a.kind).isEqualTo(AnnouncementKind.ALREADY_SENT)
                    assertThat(a.host).isEqualTo("gw.example.com")
                }

                // 上面已排除；列出来是为了让「新增终态」当场编译失败
                is EgressResult.Delivered, EgressResult.NotPermitted, is EgressResult.Failed ->
                    throw AssertionError("unexpected terminal for $f: $result")
            }
        }
    }

    /** ★★ 反过来的方向：`FELL_BACK_TO_LOCAL` 只允许出现在「PRE + 判据允许回落」上。 */
    @Test
    fun noPathAnnouncesFallbackUnlessTheResultReallyIsFallback() = runTest {
        for (f in allFailures()) {
            val (result, announcements) = drive(f)
            val saidFallback = announcements.any { it.kind == AnnouncementKind.FELL_BACK_TO_LOCAL }

            assertThat(saidFallback).isEqualTo(result is EgressResult.FellBackToLocal)
        }
    }

    // ═══════ ② 缺陷的第二处：PRE 重试时 permit 重开失败 ═══════

    /**
     * ★ 旧实现在这里 `announce(FELL_BACK_TO_LOCAL)` + `return EgressResult.Failed`。
     *
     * 新实现：零字节离机 ⇒ 终态仍是「交回端侧」⇒ 告知与终态都是回落档。
     */
    @Test
    fun preRetryReopenDenied_stillFallsBackAndSaysSo() = runTest {
        val permits = Permits(failOpenFrom = 2) // 第一次开成功，重试那次开不出来

        val announcements = mutableListOf<FallbackAnnouncement>()
        val coordinator = DefaultEgressCoordinator(permits, EgressAnnouncementSink { announcements += it })
        val result = coordinator.execute(ctx(), DECISION) { _ ->
            CloudCallOutcome.Failed(CloudFailure.ConnectFailed, 0)
        }

        assertThat(result).isInstanceOf(EgressResult.FellBackToLocal::class.java)
        assertThat(announcements.single().kind).isEqualTo(AnnouncementKind.FELL_BACK_TO_LOCAL)
        // ★ 本次出境账已收敛（重试失败也不能把 PENDING 留在库里）
        assertThat(permits.closed).containsExactly(AuditOutcome.NOT_SENT)
    }

    /** 对照：POST 重试时 permit 重开失败 ⇒ 字节已出境 ⇒ 交回用户，绝不回落。 */
    @Test
    fun postRetryReopenDenied_neverFallsBack() = runTest {
        val permits = Permits(failOpenFrom = 2)

        val announcements = mutableListOf<FallbackAnnouncement>()
        val coordinator = DefaultEgressCoordinator(permits, EgressAnnouncementSink { announcements += it })
        val result = coordinator.execute(ctx(), DECISION) { _ ->
            CloudCallOutcome.Failed(CloudFailure.ServerError, 512)
        }

        assertThat(result).isInstanceOf(EgressResult.NeedsUserDecision::class.java)
        assertThat(announcements.single().kind).isEqualTo(AnnouncementKind.ALREADY_SENT)
        assertThat(announcements.single().host).isNotEmpty()
    }

    // ═══════ ③ 全称命题：终态表与 `phase` 恒一致 ═══════

    /**
     * ★★ `PRE ⇒ FALL_BACK_TO_DEVICE`、`POST ⇒ ASK_USER`，**一个例外都不许有**。
     *
     * 这条是「将来若出现 PRE 但不许回落的形态」的警报器：那种形态需要一个
     * 「既没出境、本机也不会接手」的告知形态，而 `AnnouncementKind` 今天只有两个值
     * ⇒ 那一轮必须先在 `:core:ports` 加第三个值，本测试就是要求它的证据。
     */
    @Test
    fun terminalActionIsAlwaysDeterminedByWhetherBytesLeftTheDevice() {
        for (f in allFailures()) {
            val expected = if (f.phase == EgressPhase.PRE) {
                TerminalAction.FALL_BACK_TO_DEVICE
            } else {
                TerminalAction.ASK_USER
            }
            assertThat(FallbackPolicy.terminalFor(f)).isEqualTo(expected)
        }
    }

    /** `announcementFor` 的 `kind` / `host` **只**由终态派生（与 `phase` 无关）。 */
    @Test
    fun announcementKindIsDerivedFromTheTerminalActionAlone() {
        val pre = CloudFailure.AuthRejected
        val post = CloudFailure.ServerError

        // 同一终态 ⇒ 同一 kind，不管失败形态本身是哪一相
        val asFallback = FallbackPolicy.announcementFor(TerminalAction.FALL_BACK_TO_DEVICE, post, host = "gw.example.com")
        assertThat(asFallback.kind).isEqualTo(AnnouncementKind.FELL_BACK_TO_LOCAL)
        assertThat(asFallback.host).isNull() // ★ host 被终态吞掉，不会漏出去

        val asAsk = FallbackPolicy.announcementFor(TerminalAction.ASK_USER, pre, host = "gw.example.com")
        assertThat(asAsk.kind).isEqualTo(AnnouncementKind.ALREADY_SENT)
        assertThat(asAsk.host).isEqualTo("gw.example.com")
    }

    // ═══════ ④ 审计：每次尝试都收敛，PRE 的自报字节恒 0 ═══════

    @Test
    fun everyAttemptIsClosedAndPreNeverClaimsDeliveredBytes() = runTest {
        for (f in allFailures()) {
            val permits = Permits()
            drive(f, permits)

            val attempts = 1 + FallbackPolicy.maxRetries(f)
            assertThat(permits.closed).hasSize(attempts)
            assertThat(permits.closed).doesNotContain(AuditOutcome.PENDING)
            // ★ PRE：一步都没发出去 ⇒ 自报字节必须是 0（写非 0 会污染「是否允许回落」的判据）
            if (f.phase == EgressPhase.PRE) {
                assertThat(permits.delivered).containsExactly(*Array(attempts) { 0 })
            } else {
                assertThat(permits.delivered).containsExactly(*Array(attempts) { 512 })
            }
        }
    }
}
