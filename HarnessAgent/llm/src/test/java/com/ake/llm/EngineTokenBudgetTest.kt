package com.ake.llm

import com.ake.core.modelapi.ConversationTurn
import com.ake.core.modelapi.TurnRole
import com.ake.core.ports.MemoryTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `EngineTokenBudget` —— `maxNumTokens` 的分档、夹紧与拟合判定（`docs/05-chat-agents-design.md` §R2 / §3.4）。
 *
 * ## 这里钉的五件事
 *
 *  1. **三档数字与 `ContextBudget.forTier(tier).workingWindow` 逐字相等**（4096 / 8192 / 16384）。
 *     两者不等就是 F4：prompt 预算按 8192 组装，native 只给 4096 的 KV cache ⇒ 长会话崩。
 *     这条断言是「两个常量不许各自漂移」的唯一机器守卫（`:llm` 看不见 `:agent`，只能钉数字）。
 *  2. **显式传入的 32K 会被夹到档位上限**，并且 `clampedFrom` 留痕（不静默截断）。
 *  3. **过小的值被抬到下限**：一个装不下「system + 一轮」的引擎没有意义。
 *  4. **null / 0 / 负数 = 未指定**，取档位上限（不是取 0）。
 *  5. **拟合判定是「prompt + 预留输出 vs 上限」**，不是「prompt vs 上限」——
 *     漏掉预留输出这一项，模型就会在最后一句话上撞 native。
 */
@RunWith(JUnit4::class)
class EngineTokenBudgetTest {

    @Test
    fun `三档上限与 ContextBudget 的 workingWindow 逐字相等`() {
        // 对照 :agent/context/ContextBudget.kt:
        //   NARROW = ContextBudget(4_096, 384, …) / STANDARD = (8_192, 896, …) / WIDE = (16_384, 1_024, …)
        assertThat(EngineTokenBudget.ceilingFor(MemoryTier.LOW)).isEqualTo(4_096)
        assertThat(EngineTokenBudget.ceilingFor(MemoryTier.MID)).isEqualTo(8_192)
        assertThat(EngineTokenBudget.ceilingFor(MemoryTier.HIGH)).isEqualTo(16_384)
    }

    @Test
    fun `未指定时取档位上限`() {
        val plan = EngineTokenBudget.plan(requested = null, tier = MemoryTier.MID)

        assertThat(plan.maxNumTokens).isEqualTo(8_192)
        assertThat(plan.clamped).isFalse()
        assertThat(plan.clampedFrom).isNull()
    }

    @Test
    fun `零与负数按未指定处理，不会退化成 0`() {
        assertThat(EngineTokenBudget.plan(0, MemoryTier.LOW).maxNumTokens).isEqualTo(4_096)
        assertThat(EngineTokenBudget.plan(-1, MemoryTier.LOW).maxNumTokens).isEqualTo(4_096)
    }

    @Test
    fun `32K 的理论窗口在 8GB 机型上被夹到 8K 并留痕`() {
        // ModelLifecycle 现在传的正是 ModelCatalog.CONTEXT_WINDOW = 32_768（模型能力上限），
        // 而 8GB 机型是 MID 档。32K 的 KV cache 不会让上下文变长，只会让 LMK 杀掉 WebView。
        val plan = EngineTokenBudget.plan(requested = 32_768, tier = MemoryTier.MID)

        assertThat(plan.maxNumTokens).isEqualTo(8_192)
        assertThat(plan.clamped).isTrue()
        assertThat(plan.clampedFrom).isEqualTo(32_768)
    }

    @Test
    fun `档位允许范围内的显式值原样采纳`() {
        val plan = EngineTokenBudget.plan(requested = 6_144, tier = MemoryTier.MID)

        assertThat(plan.maxNumTokens).isEqualTo(6_144)
        assertThat(plan.clamped).isFalse()
    }

    @Test
    fun `小于下限的值被抬到下限`() {
        val plan = EngineTokenBudget.plan(requested = 256, tier = MemoryTier.HIGH)

        assertThat(plan.maxNumTokens).isEqualTo(EngineTokenBudget.FLOOR_TOKENS)
        assertThat(plan.clamped).isTrue()
        assertThat(plan.clampedFrom).isEqualTo(256)
    }

    @Test
    fun `拟合判定：prompt 加预留输出装得下`() {
        val fit = EngineTokenBudget.fit(cap = 8_192, promptTokens = 6_000, reserveOutputTokens = 512)

        assertThat(fit).isEqualTo(EngineTokenBudget.Fit.Fits)
    }

    @Test
    fun `拟合判定：只超在预留输出那一项上也算溢出`() {
        // 这条是 F4 的真正判据：只看 prompt 会漏掉「最后一句话没地方生成」，
        // 而那正是 native 层 abort 的时刻。
        val fit = EngineTokenBudget.fit(cap = 8_192, promptTokens = 8_000, reserveOutputTokens = 512)

        assertThat(fit).isInstanceOf(EngineTokenBudget.Fit.Overflow::class.java)
        fit as EngineTokenBudget.Fit.Overflow
        assertThat(fit.needed).isEqualTo(8_512)
        assertThat(fit.cap).isEqualTo(8_192)
        assertThat(fit.overflow).isEqualTo(320)
    }

    @Test
    fun `复水成本含 preface 与每条消息的模板开销`() {
        // estimate 用「字符数」代替真实 tokenizer，好让算式可核对
        val cost = EngineTokenBudget.prefillCostOf(
            systemInstruction = "abcd",
            initialMessages = listOf(
                ConversationTurn(TurnRole.USER, "ab"),
                ConversationTurn(TurnRole.MODEL, "abc"),
            ),
            estimate = { it.length },
        )

        // (4 + 16) + (2 + 8) + (3 + 8)
        assertThat(cost).isEqualTo(41)
    }

    @Test
    fun `没有 system 也不复水时成本为 0`() {
        assertThat(
            EngineTokenBudget.prefillCostOf(null, emptyList(), { it.length }),
        ).isEqualTo(0)
        assertThat(
            EngineTokenBudget.prefillCostOf("", emptyList(), { it.length }),
        ).isEqualTo(0)
    }
}
