package com.ake.llm

import com.ake.core.modelapi.ConversationTurn
import com.ake.core.ports.MemoryTier

/**
 * KV cache 上限的分档与拟合判定（`docs/05-chat-agents-design.md` §3.4 / §R2 · F4）。
 *
 * ## 为什么这个参数是 P0，而不是「调一下参数」
 *
 * `EngineConfig.maxNumTokens` 是 **input + output 的 KV cache 总上限**。原来的实现根本没设它
 * （引擎默认值偏小），而聊天场景与「一问一答跑完就结束」的单任务完全不同：
 * 几十轮历史灌进**同一个** Conversation，KV cache 随轮次线性增长。
 * 超限后不是抛异常，是 native 层 OOM / abort —— **进程直接没了，没有任何 catch 能接住**。
 *
 * ## 三档的数字从哪来
 *
 * 与 `:agent` 的 `ContextBudget.NARROW / STANDARD / WIDE` 的 `workingWindow` **逐字对齐**
 * （4096 / 8192 / 16384），并且同样键在本层的 `MemoryTier` 上。
 * 这不是两份预算，而是同一份预算的两端：
 *
 * ```
 *   ContextBudget.workingWindow = 8192   ← 组装 prompt 时的天花板
 *   EngineConfig.maxNumTokens   = 8192   ← native KV cache 的天花板
 * ```
 *
 * 二者必须相等。若 `maxNumTokens` 小于 `workingWindow`，`:chat` 按预算组装出的 prompt
 * 会在 native 侧装不下（这正是 F4 的崩溃）；若大于，多出来的那段 KV cache 是**白占的内存**
 * —— 2.6 GB 模型 + 一份用不到的 KV cache，在中端机上挤掉的是 WebView。
 * 当前 `ModelLifecycle` 传的是模型清单里的 `contextWindow = 32768`，属于后者：
 * 8 GB 机型按 32K 开 KV cache，随后被 LMK 杀掉的是执行动作的 WebView。
 * 所以本对象的作用是**把上限夹回设备真正吃得下的档位**，并把「夹了」这件事显式化。
 *
 * ## 档位读的是物理内存
 *
 * `DeviceHealthPort.memoryTier()`（`:app` 的 `AndroidDeviceHealth` 读
 * `ActivityManager.MemoryInfo.totalMem`，等价于 `/proc/meminfo` 的 MemTotal）：
 * < 6 GB ⇒ LOW，6–11 GB ⇒ MID，≥ 11 GB ⇒ HIGH。三档的分界线已在那个实现里定死，
 * 这里**不重新发明阈值**，只消费结果。
 */
public object EngineTokenBudget {

    /**
     * 下限：低于它的 KV cache 连「system + 一轮对话 + 一句回答」都装不下，
     * 建出来的会话第一条消息就必然溢出。与其开一个注定失败的引擎，不如夹到下限并告警。
     */
    public const val FLOOR_TOKENS: Int = 2_048

    /** 每轮 chat template 的固定开销（`<start_of_turn>role\n` + `<end_of_turn>\n` 等）。保守取 8。 */
    public const val TURN_OVERHEAD_TOKENS: Int = 8

    /** 整段 preface（system instruction 的模板包裹）的固定开销。保守取 16。 */
    public const val PREFACE_OVERHEAD_TOKENS: Int = 16

    /** 设备档位 ⇒ KV cache 上限。与 `ContextBudget.forTier(tier).workingWindow` 必须相等。 */
    public fun ceilingFor(tier: MemoryTier): Int = when (tier) {
        MemoryTier.LOW -> 4_096
        MemoryTier.MID -> 8_192
        MemoryTier.HIGH -> 16_384
    }

    /**
     * 一次 `load()` 的最终决议。
     *
     * @param requested 调用方（`ModelLoadConfig.maxNumTokens`）给的值，`null` = 没给
     * @param maxNumTokens **真正写进 `EngineConfig` 的值**，永远不会超过档位上限
     * @param clampedFrom 非 null ⇒ 被夹过，值是被夹之前的原始请求（用于 warning 与可观测）
     */
    public data class Plan(
        val tier: MemoryTier,
        val requested: Int?,
        val maxNumTokens: Int,
        val clampedFrom: Int? = null,
    ) {
        public val clamped: Boolean get() = clampedFrom != null
    }

    /**
     * 决议最终上限。**夹紧而不是拒绝**的理由：`load()` 的返回类型只有成功/失败两态，
     * 而「你要 32K、这台机器只给 8K」不是失败 —— 用户要的是能用，不是拿到 32K。
     * 但夹紧必须留痕（warning 日志 + [Plan.clamped]），否则就是文档警告过的「静默截断」。
     */
    public fun plan(requested: Int?, tier: MemoryTier): Plan {
        val ceiling = ceilingFor(tier)
        val asked = requested?.takeIf { it > 0 }
        return when {
            asked == null -> Plan(tier, null, ceiling)
            asked > ceiling -> Plan(tier, asked, ceiling, clampedFrom = asked)
            asked < FLOOR_TOKENS -> Plan(tier, asked, FLOOR_TOKENS, clampedFrom = asked)
            else -> Plan(tier, asked, asked)
        }
    }

    /**
     * 一个会话开场就占掉的 KV 量：preface + 全部复水消息 + 每条模板开销。
     *
     * 用**保守估算**（`TokenEstimator` 自带 ×1.06），宁可高估：
     * 高估只是少复水几轮，低估是把 native 撞崩。
     */
    public fun prefillCostOf(
        systemInstruction: String?,
        initialMessages: List<ConversationTurn>,
        estimate: (String) -> Int,
    ): Int {
        var total = if (systemInstruction.isNullOrEmpty()) 0 else estimate(systemInstruction) + PREFACE_OVERHEAD_TOKENS
        for (turn in initialMessages) {
            total += estimate(turn.text) + TURN_OVERHEAD_TOKENS
        }
        return total
    }

    /** 拟合判定：`prompt + 预留输出` 是否还在 KV cache 上限之内。 */
    public sealed interface Fit {
        public data object Fits : Fit

        /** @param needed prompt + reserve；@param cap 上限；@param overflow 超出多少 */
        public data class Overflow(val needed: Int, val cap: Int, val overflow: Int) : Fit
    }

    public fun fit(cap: Int, promptTokens: Int, reserveOutputTokens: Int): Fit {
        val needed = promptTokens.coerceAtLeast(0) + reserveOutputTokens.coerceAtLeast(0)
        return if (needed > cap) Fit.Overflow(needed, cap, needed - cap) else Fit.Fits
    }
}
