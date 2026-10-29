package com.ake.data.persist.db

import com.ake.core.ports.EgressVerdict
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ★★ `egress_gate_verdict` 常量收口（docs/09 §5.2）—— 历史上有**三套**，不是两套。
 *
 * | # | 出处 | 值 |
 * |---|---|---|
 * | 1 | `:data` 的 `RouteDecisionEntity.EGRESS_PASS/BLOCKED` | PASS / BLOCKED |
 * | 2 | `:chat` 的 `ChatEgress.VERDICT_*` | SCREENED / GATE_DENIED |
 * | 3 | **`:core:ports/EgressVerdict` 里混进了 `NOT_SENT`/`DELIVERED`/`PARTIAL`** | ← 属于另一个轴 |
 *
 * 第 3 条是收口动作本身引入的。本测试把「闸裁决轴」与「尝试状态轴」钉成**正交**的两个轴。
 */
class RouteDecisionVerdictTest {

    // ───────────────────────────── ① 规范值引用唯一来源

    @Test
    fun canonicalValuesComeFromCorePortsNotRedefinedHere() {
        assertThat(RouteDecisionVerdict.SCREENED).isEqualTo(EgressVerdict.SCREENED)
        assertThat(RouteDecisionVerdict.GATE_DENIED).isEqualTo(EgressVerdict.GATE_DENIED)
        assertThat(RouteDecisionVerdict.SCREENED).isEqualTo("SCREENED")
        assertThat(RouteDecisionVerdict.GATE_DENIED).isEqualTo("GATE_DENIED")
    }

    // ───────────────────────────── ② 存量：保留字面量、读时规范化（docs/09 §5.3）

    @Test
    fun legacyLiteralsAreNormalisedOnRead() {
        assertThat(RouteDecisionVerdict.normalise("PASS")).isEqualTo(EgressVerdict.SCREENED)
        assertThat(RouteDecisionVerdict.normalise("BLOCKED")).isEqualTo(EgressVerdict.GATE_DENIED)
    }

    @Test
    fun canonicalValuesPassThrough() {
        for (v in RouteDecisionVerdict.ALL) {
            assertThat(RouteDecisionVerdict.normalise(v)).isEqualTo(v)
        }
    }

    /** ★ 未知值返回 null 而不是抛 —— 审计读路径不得因为脏值把主流程炸掉。 */
    @Test
    fun unknownValueBecomesNullRatherThanThrowing() {
        assertThat(RouteDecisionVerdict.normalise("WHATEVER")).isNull()
        assertThat(RouteDecisionVerdict.normalise(null)).isNull()
    }

    // ───────────────────────────── ③ ★★ 两轴正交（负向用例）

    /**
     * ★★ **负向用例**：尝试状态轴的值**不得**被当成闸裁决接受。
     *
     * 这是第 3 套常量的检测器 —— 若哪天 `EgressVerdict` 里的 `NOT_SENT`/`DELIVERED`/
     * `PARTIAL` 被人当作 `egress_gate_verdict` 写进库，两个轴就重新混在一起，
     * 「过闸了但网络挂」与「被拦下、零字节」将无法区分。
     */
    @Test
    fun outcomeAxisValuesAreRejectedAsGateVerdicts() {
        for (v in listOf("PENDING", "NOT_SENT", "SENT", "DELIVERED", "PARTIAL", "FAILED", "UNKNOWN")) {
            assertThat(RouteDecisionVerdict.isOutcomeAxisValue(v)).isTrue()
            assertThat(RouteDecisionVerdict.normalise(v)).isNull()
        }
    }

    /** ★ 反向：闸裁决的值也不属于尝试状态轴。 */
    @Test
    fun gateVerdictsAreNotOutcomeAxis() {
        for (v in RouteDecisionVerdict.ALL) {
            assertThat(RouteDecisionVerdict.isOutcomeAxisValue(v)).isFalse()
        }
    }

    /**
     * ★ 已知偏差（需报主理人 → `:core:ports`）：`EgressVerdict` 目前**仍然**带着
     *   三个尝试状态轴的值。本断言把这件事钉成一条会红的标记，
     *   等 `:core:ports` 把它们移走后，本断言会红 ⇒ 提醒同步删除。
     */
    @Test
    fun documentsThatCorePortsEgressVerdictStillMixesAxes() {
        assertThat(EgressVerdict.NOT_SENT).isEqualTo("NOT_SENT")
        assertThat(EgressVerdict.DELIVERED).isEqualTo("DELIVERED")
        assertThat(EgressVerdict.PARTIAL).isEqualTo("PARTIAL")
    }

    // ───────────────────────────── ④ Entity 侧不再自带字面量

    /**
     * ★ `EGRESS_PASS` / `EGRESS_BLOCKED` 的**声明**必须已经从 Entity 上删掉。
     *
     * ⚠ 断言的是 `const val EGRESS_PASS` 这个**声明形式**，不是那个裸名字 ——
     *   Entity 的 KDoc 里会**提到**这两个名字来解释为什么删（那是有价值的注释），
     *   断言裸名字会把注释当成残留而假失败。
     */
    @Test
    fun entityNoLongerDeclaresItsOwnLiterals() {
        val src = File("src/main/java/com/ake/data/persist/db/entity/ChatEntities.kt").takeIf { it.isFile }
            ?: File("data/src/main/java/com/ake/data/persist/db/entity/ChatEntities.kt")
        val text = src.readText()
        assertThat(text).doesNotContain("const val EGRESS_PASS")
        assertThat(text).doesNotContain("const val EGRESS_BLOCKED")
        assertThat(text).doesNotContain("EGRESS_PASS:")
        assertThat(text).doesNotContain("EGRESS_BLOCKED:")
    }
}
