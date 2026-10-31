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
 *
 * ## ★★★ 告知必须**恒等于事实**（2026-10 主理人裁决）
 *
 * 旧实现的 `while` 循环尾部是这么写的：
 * ```
 *    announce(announcementFor(failure, host = null))   // PRE ⇒ kind = FELL_BACK_TO_LOCAL
 *    return EgressResult.Failed(failure)               // ⇒ :chat 判成 Blocked，本机根本没跑
 * ```
 * 用户于是读到「未上云，已由本机完成」，而本机一条字都没产出。
 * **这不是静默降级，是制造一个错误的安心** —— 比静默降级更糟。
 *
 * 根因是**两个来源各说一半**：`kind` 由 `failure.phase` 推，终态由循环的落点决定，
 * 两者之间没有约束 ⇒ 任何落到 `return Failed` 的路径都有同样的错位
 * （`ConnectFailed` 重试耗尽、PRE 重试时 permit 重开失败，两处）。
 *
 * ⇒ 修法是**结构性**的，不是补一个 case：
 *  ① 终态由 [FallbackPolicy.terminalFor] **一个函数**决定，只有两个值；
 *  ② 告知的 `kind` 由**同一个** [FallbackPolicy.TerminalAction] 派生
 *     （[FallbackPolicy.announcementFor] 的形参就是它）⇒ 物理上无法错位；
 *  ③ 循环里**没有**「落回默认分支」这种写法 —— 每次退出都是 `terminalFor` 的一个显式臂。
 *
 * ⚠★ 于是 [EgressResult.Failed] 不再由本类产出：「既没出境、本机也不会接手」
 *   这一档今天不存在，而它是 `Failed` 唯一诚实的含义。
 *   **若将来 `FallbackPolicy` 出现「PRE 且不许回落」的形态**，`terminalFor` 会要求
 *   给它一个终态，而 `AnnouncementKind` 只有两个值、没有与之相符的那一档 ⇒
 *   必须给 `AnnouncementKind` 加第三个值（属 `:core:ports`，需单独一轮）。
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

        while (true) {
            attempt++

            when (val outcome = call(permit)) {
                is CloudCallOutcome.Done -> {
                    closeSafely(permit, AuditOutcome.DELIVERED, EgressPhase.POST, outcome.deliveredBytes, outcome.finishReasonRaw)
                    return EgressResult.Delivered(outcome.text)
                }

                is CloudCallOutcome.Failed -> {
                    val failure = outcome.failure
                    val host = permit.endpointHost

                    // ── ② 这一次还该不该再试？判据**只**来自 FallbackPolicy ──
                    val shouldRetry =
                        FallbackPolicy.actionFor(failure) == FallbackAction.RETRY_SAME_ENDPOINT &&
                            attempt <= FallbackPolicy.maxRetries(failure)

                    // ── ③ 本次出境账**立即**收敛（重试前不收敛 ⇒ PENDING 留在库里）
                    closeSafely(
                        permit,
                        auditOutcomeOf(failure),
                        failure.phase,
                        // ★ PRE 的自报字节恒为 0：那一步根本没发出去。
                        //   写非 0 会把「审计」变成第二个「是否允许回落」的判据来源。
                        if (failure.phase == EgressPhase.PRE) 0 else outcome.deliveredBytes,
                        if (failure.phase == EgressPhase.PRE) null else failure.code,
                    )

                    if (shouldRetry) {
                        // ★ 重试 = **新出境** = 新 permit + 新审计行（§4.4）
                        val reopened = permits.open(ctx, decision)
                        if (reopened is Outcome.Ok) {
                            permit = reopened.value
                            continue
                        }
                        // 拿不到新 permit ⇒ 试不下去了 ⇒ 落到下面的终态
                        AkeLog.w(TAG, "reopen denied after attempt $attempt: ${(reopened as Outcome.Err).error.code}")
                    }

                    // ══════════════════════════════════════════════════════
                    // ★★★ 唯一的终态出口：告知与 `EgressResult` 由**同一个**
                    //      `TerminalAction` 派生 ⇒ 不可能再出现「说回落却没回落」。
                    //
                    //  `when` 只有两臂、没有 `else`；循环里也没有「落回默认分支」。
                    // ══════════════════════════════════════════════════════
                    return when (val terminal = FallbackPolicy.terminalFor(failure)) {
                        FallbackPolicy.TerminalAction.FALL_BACK_TO_DEVICE -> {
                            // ★★ P0：告知在 return 之前。它在 `:app` 由 Hilt 绑定 ⇒ 缺绑编不过。
                            announcementSink.announce(
                                FallbackPolicy.announcementFor(
                                    terminal, failure, host = null,
                                    turnId = ctx.turnId, sessionId = ctx.sessionId,
                                ),
                            )
                            EgressResult.FellBackToLocal(failure)
                        }

                        FallbackPolicy.TerminalAction.ASK_USER -> {
                            announcementSink.announce(
                                FallbackPolicy.announcementFor(
                                    terminal, failure, host = host,
                                    turnId = ctx.turnId, sessionId = ctx.sessionId,
                                ),
                            )
                            EgressResult.NeedsUserDecision(failure)
                        }
                    }
                }
            }
        }
    }

    /** §7.3：PRE 恒 `NOT_SENT`（一个字节都没出去），POST 才按形态细分。 */
    private fun auditOutcomeOf(failure: CloudFailure): AuditOutcome =
        if (failure.phase == EgressPhase.PRE) {
            AuditOutcome.NOT_SENT
        } else {
            FallbackPolicy.auditOutcome(failure)
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
