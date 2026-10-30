package com.ake.chat.egress

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.ports.DataClass
import com.ake.core.ports.EgressGate
import com.ake.core.ports.EgressVerdict
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.OutboundPayload
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.ScreenedPayload

/**
 * ★★ 真正会放行的 `EgressGate`（2026-10 端云接线）。
 *
 * ## ★ 为什么它不是「把 `LocalOnlyEgressGate` 的 deny 删掉」
 *
 * `LocalOnlyEgressGate` 至今**从未真正放行过一次**：它的 `screen()` 第一行
 * （`decision.target != CLOUD_ASSISTED`）恒真 ⇒ 后面三道检查（类别白名单 /
 * 体积 / D2 分级）**从来没被执行过**，是死代码。
 * 「删掉第一行就完事」会把三道**从未跑过**的检查直接放进生产 ——
 * 那不是接线，是拆掉保险丝后第一次通电。所以这里重写并逐条给出判据。
 *
 * ## ★ D0 / D1 / D2 的分级在这里怎么做
 *
 * **第一道（类型层，不可绕过）**：`OutboundPayload` 只有三个变体，
 * 全是 D2（能力清单 / 抽象计划 / 无实体参数的统计）。
 * `PocketDom` 原文**在类型系统上**装不进 `OutboundPayload` ⇒
 * D0 不需要一个运行时的正则去拦，它根本进不来。
 *
 * **第二道（本类，运行时）**：给每个变体一个**显式的类别判定**。
 * 它不是「因为类型如此所以必然安全」的省略，而是把「这个变体属于 D2」
 * 这件事**写成一句可被改错的代码** —— 将来若有人加第四个变体，
 * `when` 编译期红，他必须在这里回答「它属于哪一类」。
 *
 * **第三道**：体积上限。D2 都是小小的东西，超限只可能是有人在塞别的。
 *
 * ## ★ 为什么「类别白名单为空」不再直接 deny
 *
 * `DialogRoutingPort.decide()` 恒返回 `allowedClasses = emptySet()`
 * （§5.7：未授权 ⇒ 恒空；授权后的白名单由 permit 侧决定）。
 * 若继续按「空 ⇒ deny」判，本轮接线后**依然一次都放不出去**，
 * 等于换个地方再锁一次。⇒ 本类的判据改为：
 *  · 白名单为空 ⇒ 只允许**D2**（默认姿态，今天唯一可能的取值）；
 *  · 白名单非空 ⇒ 载荷类别必须在白名单内。
 */
public class CloudCapableEgressGate : EgressGate {

    override fun screen(payload: OutboundPayload, decision: RoutingDecision): Outcome<ScreenedPayload> {
        // ① 决策必须是「允许出云」。★ 用 `is CloudRequested` 而不是比较常量。
        if (decision.target !is InferenceTarget.CloudRequested &&
            decision.target !is InferenceTarget.CloudAssisted
        ) {
            return denied(CODE_NOT_CLOUD, "err_egress_not_cloud")
        }

        // ② ★ D0 / D1 的显式分级（第二道）
        val cls = dataClassOf(payload)
        if (cls != DataClass.GREEN) {
            return denied(CODE_CLASS_NOT_ALLOWED, "err_egress_class_not_allowed")
        }

        // ③ 白名单：空 ⇒ 只允许 D2；非空 ⇒ 必须是成员
        if (decision.allowedClasses.isNotEmpty() && cls !in decision.allowedClasses) {
            return denied(CODE_NO_ALLOWED_CLASS, "err_egress_no_allowed_class")
        }

        // ④ 体积
        val bytes = sizeOf(payload)
        if (bytes > MAX_BYTES) {
            return denied(CODE_TOO_LARGE, "err_egress_too_large")
        }

        return Outcome.Ok(ScreenedPayload(bytes))
    }

    /**
     * ★★ **唯一的**「载荷 → 数据类别」映射。
     *
     * ★ 三个变体全判成 `GREEN`（D2）不是偷懒：它们是这个 sealed 接口**全部的**构造方式。
     *   将来新增变体 ⇒ 本 `when` **编译期红** ⇒ 新变体的作者必须在这里显式表态。
     *   这就是「分级」从类型保证升级成「有人负责」的地方。
     */
    public fun dataClassOf(payload: OutboundPayload): DataClass = when (payload) {
        is OutboundPayload.CapabilityCatalog -> DataClass.GREEN
        is OutboundPayload.AbstractPlan -> DataClass.GREEN
        is OutboundPayload.TelemetryFreeStats -> DataClass.GREEN
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

    private fun denied(code: String, reasonKey: String): Outcome<ScreenedPayload> =
        Outcome.Err(AppError(code = code, reasonKey = reasonKey, severity = Severity.MEDIUM))

    public companion object {
        public const val CODE_NOT_CLOUD: String = "EGRESS_NOT_CLOUD"
        public const val CODE_CLASS_NOT_ALLOWED: String = "EGRESS_CLASS_NOT_ALLOWED"
        public const val CODE_NO_ALLOWED_CLASS: String = "EGRESS_NO_ALLOWED_CLASS"
        public const val CODE_TOO_LARGE: String = "EGRESS_TOO_LARGE"

        /** 单条信封体积上限。D2 全是小小的东西，超过 32KB 只可能是有人在塞别的。 */
        public const val MAX_BYTES: Int = 32 * 1024

        /** 与 `EgressVerdict` 同源：放行时写库的裁决常量。 */
        public const val VERDICT_SCREENED: String = EgressVerdict.SCREENED
        public const val VERDICT_GATE_DENIED: String = EgressVerdict.GATE_DENIED
    }
}
