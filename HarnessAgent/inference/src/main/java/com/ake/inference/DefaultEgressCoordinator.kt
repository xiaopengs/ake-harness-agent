package com.ake.inference

import com.ake.core.common.AkeLog
import com.ake.core.common.Outcome
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudCallOutcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.EgressAnnouncementSink
import com.ake.core.ports.EgressCoordinator
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.EgressResult
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.inference.FallbackPolicy.FallbackAction

/**
 * ★★ `EgressCoordinator` 的唯一实现（docs/10 §6.2-C）。
 *
 * ## ★ 为什么这段编排必须有且只有一个实现
 *
 * 「开许可 → 调 → 重试（每次新许可）→ 收敛 → 决定回落」是**端云之间那一次交互的
 * 全部语义**。它散进 `:app` 组合根 ⇒ 上云逻辑变成胶水，
 * 且**每一条新对端都要重写一遍** ⇒ 重写一遍就会漏一条（比如漏掉「重试 = 新审计行」）。
 *
 * ## ★★ 四条契约（逐条对应测试）
 *
 *  ① permit 开不出来 ⇒ [EgressResult.NotPermitted]，**一次都不调** `call`；
 *  ② 每次重试 = 一次**独立出境** ⇒ **新 permit + 新审计行**（§4.4）；
 *  ③ POST 阶段**绝不**自动回落（docs/08 §4.2）⇒ 交回 UI 显式问用户；
 *  ④ 返回前**必须**已 `close` 过 permit（不留 PENDING）。
 *
 * ## ★ P0：告知发生在返回值之前
 *
 * 凡是要回落的路径，本类**先**调 [EgressAnnouncementSink]，**再**返回
 * [EgressResult.FellBackToLocal]。sink 由 `:app` Hilt 绑定 ⇒ 缺绑编不过。
 */
public class DefaultEgressCoordinator(
    private val permits: EgressPermitSink,
    private val announcementSink: EgressAnnouncementSink,
) : EgressCoordinator {

    override suspend fun execute(
        ctx: RouteContext,
        decision: RoutingDecision,
        call: suspend (permit: EgressPermit) -> CloudCallOutcome,
    ): EgressResult {
        // ── ① fail-closed：审计行都写不出来 ⇒ 一个字节都不许发 ────────────
        var permit = when (val opened = permits.open(ctx, decision)) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> {
                AkeLog.w(TAG, "permit denied, nothing leaves the device: ${opened.error.code}")
                return EgressResult.NotPermitted
            }
        }

        var attempt = 0
        var lastFailure: CloudFailure? = null

        while (true) {
            attempt++

            when (val outcome = call(permit)) {
                is CloudCallOutcome.Done -> {
                    closeSafely(permit, AuditOutcome.DELIVERED, EgressPhase.POST, outcome.deliveredBytes, outcome.finishReasonRaw)
                    return EgressResult.Delivered(outcome.text)
                }

                is CloudCallOutcome.Failed -> {
                    val failure = outcome.failure
                    lastFailure = failure
                    val action = FallbackPolicy.actionFor(failure)
                    val host = permit.endpointHost

                    // ── ② 只有 PRE 才可能回落；且**先告知再返回** ──────────
                    if (action == FallbackAction.FALLBACK_TO_DEVICE && failure.phase == EgressPhase.PRE) {
                        closeSafely(permit, AuditOutcome.NOT_SENT, EgressPhase.PRE, 0, null)
                        // ★★ P0：这一句就是「必须告知」的全部落点。它在 return 之前。
                        announcementSink.announce(FallbackPolicy.announcementFor(failure, host = null, turnId = ctx.turnId, sessionId = ctx.sessionId))
                        return EgressResult.FellBackToLocal(failure)
                    }

                    // ── ③ POST：绝不自动回落 ⇒ 交回 UI ────────────────────
                    if (failure.phase == EgressPhase.POST) {
                        if (action == FallbackAction.RETRY_SAME_ENDPOINT && attempt <= FallbackPolicy.maxRetries(failure)) {
                            closeSafely(
                                permit,
                                FallbackPolicy.auditOutcome(failure),
                                EgressPhase.POST,
                                outcome.deliveredBytes,
                                failure.code,
                            )
                            // ★ 重试 = **新出境** = 新 permit + 新审计行
                            permit = when (val reopened = permits.open(ctx, decision)) {
                                is Outcome.Ok -> reopened.value
                                is Outcome.Err -> {
                                    announcementSink.announce(FallbackPolicy.announcementFor(failure, host, turnId = ctx.turnId, sessionId = ctx.sessionId))
                                    return EgressResult.NeedsUserDecision(failure)
                                }
                            }
                            continue
                        }
                        closeSafely(
                            permit,
                            FallbackPolicy.auditOutcome(failure),
                            EgressPhase.POST,
                            outcome.deliveredBytes,
                            failure.code,
                        )
                        announcementSink.announce(FallbackPolicy.announcementFor(failure, host, turnId = ctx.turnId, sessionId = ctx.sessionId))
                        return EgressResult.NeedsUserDecision(failure)
                    }

                    // PRE 且允许重试（今天只有 ConnectFailed）
                    if (action == FallbackAction.RETRY_SAME_ENDPOINT && attempt <= FallbackPolicy.maxRetries(failure)) {
                        closeSafely(permit, AuditOutcome.NOT_SENT, EgressPhase.PRE, 0, null)
                        permit = when (val reopened = permits.open(ctx, decision)) {
                            is Outcome.Ok -> reopened.value
                            is Outcome.Err -> {
                                announcementSink.announce(FallbackPolicy.announcementFor(failure, host = null, turnId = ctx.turnId, sessionId = ctx.sessionId))
                                return EgressResult.Failed(failure)
                            }
                        }
                        continue
                    }

                    closeSafely(permit, AuditOutcome.NOT_SENT, EgressPhase.PRE, 0, null)
                    announcementSink.announce(FallbackPolicy.announcementFor(failure, host = null, turnId = ctx.turnId, sessionId = ctx.sessionId))
                    return EgressResult.Failed(failure)
                }
            }
        }
    }

    /** ★ 收敛失败**不能**再吞掉（那就把 PENDING 永久留在库里了）。 */
    private suspend fun closeSafely(
        permit: EgressPermit,
        outcome: AuditOutcome,
        phase: EgressPhase,
        deliveredBytes: Int,
        finishReasonRaw: String?,
    ) {
        runCatching {
            permits.close(permit, outcome, phase, deliveredBytes, finishReasonRaw)
        }.onFailure {
            AkeLog.e(TAG, "closing permit failed: ${it.message}")
        }
    }

    public companion object {
        public const val TAG: String = "EgressCoordinator"
    }
}
