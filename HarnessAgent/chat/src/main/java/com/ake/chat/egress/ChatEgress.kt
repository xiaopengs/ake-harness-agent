package com.ake.chat.egress

import com.ake.core.common.AppError
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.ports.DataClass
import com.ake.core.ports.EgressGate
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.OutboundPayload
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.RoutingPort
import com.ake.core.ports.ScreenedPayload

/**
 * # 端云协同：`RoutingPort` + `EgressGate`（§6.2 / §9 M9 / Q4）
 *
 * **MVP 恒裁决 `LOCAL_ONLY`。** 这不是「先不做」，而是**把开关做出来但不打开**：
 * 未来上云是「放开一个开关」，不是「补一个功能」。
 *
 * 于是本轮真正要交付的只有三件东西：
 *  ① 决策**永远**是 LOCAL_ONLY，且**失败也只能落到 LOCAL_ONLY**（deny-by-default）；
 *  ② 每一次「有人想出云」都必须留下审计痕迹（T12：没有审计的路由承诺是空话）；
 *  ③ `OutboundPayload` 的构造点被挡在决策之后 —— **D0 类数据在物理上没机会被装进信封**。
 *
 * ## ③ 为什么是结构性的而不是纪律性的
 *
 * `OutboundPayload` 的密封层次本来就只允许 D2（能力清单 / 抽象计划 / 版本统计），
 * `PocketDom` 原文**在类型上**不是 `OutboundPayload`。本类的做法是再收紧一层：
 * 调用方传的是 `payload: () -> OutboundPayload`（**懒构造**），
 * 裁决不是 CLOUD_ASSISTED 时这个 lambda 一次都不会被调 ——
 * 于是「构造了但没发出去」这种中间态也不存在，审计里也不可能出现 payload 内容。
 */
public class ChatEgress(
    private val routing: RoutingPort,
    private val gate: EgressGate,
    private val audit: AuditSink,
    private val clock: Clock,
) {

    /** 本轮的路由裁决。**永远先问再干**，且把裁决交给审计。 */
    public suspend fun decide(ctx: RouteContext): RoutingDecision {
        val decision = routing.decide(ctx)
        audit.record(ctx, decision, chatMessageId = null, payloadBytes = 0, egressGateVerdict = null)
        return decision
    }

    /**
     * 尝试出云。**MVP 下这条路径必然在第一步就返回 `Denied`。**
     *
     * @param payload 懒构造。只有裁决允许出云时才会被调用（见类注释 ③）。
     */
    public suspend fun attempt(ctx: RouteContext, payload: () -> OutboundPayload): EgressOutcome {
        val decision = decide(ctx)
        if (decision.target != InferenceTarget.CLOUD_ASSISTED) {
            return EgressOutcome.Denied(decision.reasonKey)
        }
        return when (val screened = gate.screen(payload(), decision)) {
            is Outcome.Ok -> {
                audit.record(ctx, decision, null, screened.value.bytes, VERDICT_SCREENED)
                EgressOutcome.Allowed(screened.value)
            }

            is Outcome.Err -> {
                audit.record(ctx, decision, null, 0, VERDICT_GATE_DENIED)
                EgressOutcome.Denied(screened.error.reasonKey)
            }
        }
    }

    public sealed interface EgressOutcome {
        public data class Allowed(val screened: ScreenedPayload) : EgressOutcome

        public data class Denied(val reasonKey: String) : EgressOutcome
    }

    /** 审计落点。`:app` 侧用 `:data` 的 `RouteDecisionSink` 实现，本模块看不见 Room。 */
    public fun interface AuditSink {
        public suspend fun record(
            ctx: RouteContext,
            decision: RoutingDecision,
            chatMessageId: String?,
            payloadBytes: Int,
            egressGateVerdict: String?,
        )

        /** 不接审计时的空实现。**只允许在单测里用** —— 生产环境空审计等于 T12 失守。 */
        public companion object {
            public val NONE: AuditSink = AuditSink { _, _, _, _, _ -> }
        }
    }

    public companion object {
        public const val TAG: String = "ChatEgress"

        public const val VERDICT_SCREENED: String = "SCREENED"
        public const val VERDICT_GATE_DENIED: String = "GATE_DENIED"
    }
}

/**
 * 唯一的 `RoutingPort` 实现（MVP）。
 *
 * ## 三条纪律
 *
 * ① **deny-by-default**：任何异常 / 未知状态 ⇒ `LOCAL_ONLY`。抓异常也不外抛 ——
 *    路由失败不该让一整轮对话挂掉，但**必须让用户看见「没出网」**（reasonKey 会说）。
 * ② `allowedClasses` 恒空：既然不出网，就不存在「允许离开设备的类别」。
 * ③ `redactionApplied = true`：即使在本地，结论也是「按最严处理」，避免 UI 误读。
 *
 * ★ 它**不写库**：审计在 [ChatEgress] 那一层做。分开的理由是「决策」与「记账」
 * 是两件事 —— 谁都能实现一个 `RoutingPort`，但审计只该有一个入口。
 */
public class LocalOnlyRoutingPort(
    private val clock: Clock,
) : RoutingPort {

    override suspend fun decide(ctx: RouteContext): RoutingDecision = try {
        // 本轮唯一允许的答案。用户授权与否都不改变它 —— Q4 裁决：本轮不开云端。
        RoutingDecision(
            target = InferenceTarget.LOCAL_ONLY,
            reasonKey = REASON_LOCAL_ONLY,
            allowedClasses = emptySet<DataClass>(),
            redactionApplied = true,
            decidedAt = clock.nowMillis(),
        )
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        AkeLog.w(TAG, "routing failed, fallback LOCAL_ONLY: ${t.message}")
        RoutingDecision(
            target = InferenceTarget.LOCAL_ONLY,
            reasonKey = REASON_LOCAL_ONLY,
            allowedClasses = emptySet(),
            redactionApplied = true,
            decidedAt = clock.nowMillis(),
        )
    }

    public companion object {
        public const val TAG: String = "LocalOnlyRouting"

        /** UI 直接用它做 i18n：`route_local_only` = 「本轮全部在本机完成」。 */
        public const val REASON_LOCAL_ONLY: String = "route_local_only"
    }
}

/**
 * 唯一的 `EgressGate` 实现（MVP）。
 *
 * 最后一道闸不是正则而是**类型断言**（§6.2 / S6）：`OutboundPayload` 只有三种 D2 形态，
 * D0 在类型系统上就装不进去。这里做的是第二层：**任何裁决不是 CLOUD_ASSISTED 的出网请求一律拒绝**。
 *
 * 为什么它还叫 Gate 而不是直接删掉：`RoutingPort` 说「这轮不出网」，`EgressGate` 说
 * 「就算有人绕过了路由，这里还有一道」。两道都保留，才有资格在将来把开关打开。
 */
public class LocalOnlyEgressGate : EgressGate {

    override fun screen(payload: OutboundPayload, decision: RoutingDecision): Outcome<ScreenedPayload> {
        if (decision.target != InferenceTarget.CLOUD_ASSISTED) {
            return Outcome.Err(
                AppError(
                    code = CODE_LOCAL_ONLY,
                    reasonKey = "err_egress_local_only",
                    severity = Severity.LOW,
                ),
            )
        }
        if (decision.allowedClasses.isEmpty()) {
            return Outcome.Err(
                AppError(
                    code = CODE_NO_ALLOWED_CLASS,
                    reasonKey = "err_egress_no_allowed_class",
                    severity = Severity.MEDIUM,
                ),
            )
        }
        val bytes = sizeOf(payload)
        if (bytes > MAX_BYTES) {
            return Outcome.Err(
                AppError(code = CODE_TOO_LARGE, reasonKey = "err_egress_too_large", severity = Severity.MEDIUM),
            )
        }
        return Outcome.Ok(ScreenedPayload(bytes))
    }

    /**
     * 体积只按信封自身的可见内容算，**不序列化、不解引用别的对象** ——
     * 「算出大小」这件事本身不该成为一条把数据搬进内存的路径。
     */
    private fun sizeOf(payload: OutboundPayload): Int = when (payload) {
        is OutboundPayload.CapabilityCatalog -> payload.json.length
        is OutboundPayload.AbstractPlan -> payload.steps.sumOf { it.length + 2 }
        is OutboundPayload.TelemetryFreeStats -> 16
    }

    public companion object {
        public const val CODE_LOCAL_ONLY: String = "EGRESS_LOCAL_ONLY"
        public const val CODE_NO_ALLOWED_CLASS: String = "EGRESS_NO_ALLOWED_CLASS"
        public const val CODE_TOO_LARGE: String = "EGRESS_TOO_LARGE"

        /** 单条信封体积上限。D2 全是小小的东西，超过 32KB 只可能是有人在塞别的。 */
        public const val MAX_BYTES: Int = 32 * 1024
    }
}
