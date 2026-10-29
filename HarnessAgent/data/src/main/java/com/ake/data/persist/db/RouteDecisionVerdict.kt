package com.ake.data.persist.db

import com.ake.core.ports.EgressVerdict

/**
 * ★★ `route_decision.egress_gate_verdict` 的**闸裁决轴**归一化（docs/09 §5.2）。
 *
 * ## 为什么需要它：历史上实际是**三套**，不是两套
 *
 * | # | 出处 | 值 |
 * |---|---|---|
 * | 1 | `:data` 的 `RouteDecisionEntity.EGRESS_PASS/BLOCKED` | PASS / BLOCKED |
 * | 2 | `:chat` 的 `ChatEgress.VERDICT_SCREENED/GATE_DENIED` | SCREENED / GATE_DENIED |
 * | 3 | `:core:ports/EgressVerdict` **里混进了 `NOT_SENT`/`DELIVERED`/`PARTIAL`** | ← 这三个是**另一个轴** |
 *
 * 第 3 条是收口动作本身引入的：`docs/08` §7.5 在统一 1 与 2 时，把属于「尝试状态轴」
 * 的三个值塞进了「闸裁决轴」的对象里 —— 于是「过闸了但网络挂」与「被拦下、零字节」
 * 在类型上变得可以互相冒充。
 *
 * ## ★ 两个轴正交，不许合并
 *
 * | 列 | 回答什么 | 取值来源 |
 * |---|---|---|
 * | `egress_gate_verdict` | EgressGate 对「这份 payload 够不够格过闸」的判断 | 本文件（闸裁决轴） |
 * | `outcome` | 这一次尝试最终发生/没发生什么 | `AuditOutcome.name`（尝试状态轴） |
 *
 * `SCREENED + FAILED` 与 `GATE_DENIED + NOT_SENT` 是两个完全不同的事实。
 *
 * ## ★ 存量：保留字面量、读时规范化、**不重写**
 *
 * docs/09 §5.3：本表今日恒空（见 `RouteDecisionDao.probeTotalRows` 的 S9 自检）
 * ⇒ 迁移里重写存量值的 UPDATE 是**永远不会被执行的代码**。
 * 于是：老字面对所有读路径表现为规范值，原始字面量仍留在库里（留痕），零 UPDATE。
 *
 * ⚠ **本文件不定义新的规范常量** —— 规范值一律引用 `EgressVerdict`。
 *    唯一"新增"的是 `UNSCREENED`，用于「明确走了本地路由、闸没运行」这一态。
 *    TODO(handoff → :core:ports)：`UNSCREENED` 与 `EgressVerdict` 里那三个
 *    尝试状态轴的值应由 `:core:ports` 统一收口；本轮 `:core:ports` 不在授权范围内。
 */
public object RouteDecisionVerdict {

    /** 未过闸（本地路由 / 从未走到出口这一步）。也可表达为 NULL。 */
    public const val UNSCREENED: String = "UNSCREENED"

    /** 过闸放行。 */
    public const val SCREENED: String = EgressVerdict.SCREENED

    /** 过闸拦下。 */
    public const val GATE_DENIED: String = EgressVerdict.GATE_DENIED

    /** ★ 规范取值集合。 */
    public val ALL: Set<String> = setOf(UNSCREENED, SCREENED, GATE_DENIED)

    /**
     * ★ 历史字面量 → 规范值。**只读方向的兼容层，不是第三套常量。**
     *
     * 这两个条目是 `:data` 侧 `EGRESS_PASS`/`EGRESS_BLOCKED` 的墓碑 ——
     * 它们只服务「老设备上升���来的行」，新写入路径不可能产出这两个值。
     */
    private val LEGACY: Map<String, String> = mapOf(
        "PASS" to SCREENED,
        "BLOCKED" to GATE_DENIED,
    )

    /**
     * 读时规范化。**未知值返回 null 而不是抛** —— 审计读路径不得因为脏值把主流程炸掉。
     */
    public fun normalise(raw: String?): String? = when (raw) {
        null -> null
        in ALL -> raw
        else -> LEGACY[raw]
    }

    /**
     * ★ 是否属于「尝试状态轴」的值被误写进了闸裁决列。
     *
     * 这是第 3 套常量的检测器：`docs/09 §5.2` 的正交拆分要求 `outcome` 的值
     * 绝不出现在 `egress_gate_verdict` 列里。
     */
    private val OUTCOME_AXIS: Set<String> =
        setOf("PENDING", "NOT_SENT", "SENT", "DELIVERED", "PARTIAL", "FAILED", "UNKNOWN")

    public fun isOutcomeAxisValue(raw: String?): Boolean = raw in OUTCOME_AXIS
}
