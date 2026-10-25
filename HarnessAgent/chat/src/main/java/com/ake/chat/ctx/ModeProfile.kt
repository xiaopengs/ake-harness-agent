package com.ake.chat.ctx

import com.ake.agent.context.ContextBudget
import com.ake.agent.context.SegmentCaps
import com.ake.agent.context.SegmentId
import com.ake.core.ports.LoopMode
import com.ake.core.ports.MemoryTier

/**
 * # 循环模式剖面（`docs/05-chat-agents-design.md` §3.4）
 *
 * **结论先说：`ContextBudget` 由设备档位唯一决定（共享），`SegmentCaps` 按模式剖面（分离）。**
 *
 * 为什么不能彻底独立：两种循环共享同一条 native Conversation（F3）⇒ 只有一个 KV cache 窗口，
 * 「聊天历史 4096 + 任务历史 4096」这种加法物理上不存在。
 * 为什么不能简单共用一个剖面：聊天要 `HISTORY/RECENT` 很大、`OBSERVATION` 为零；
 * 任务正好相反。同一个窗口 + 不同剖面，让「总窗口不变」这个硬事实被两个循环按需共享。
 *
 * ## 本文件与设计文档 §3.4 的两处**必读差异**
 *
 * ① 文档写的是 `ModeProfile(SegmentCaps.CHAT, …)` —— 但 `:agent` 的 `SegmentCaps`
 *    **只有 NARROW / STANDARD / WIDE 三张任务剖面**，没有 `CHAT` / `TASK_IN_CHAT`。
 *    按「文档与现有代码冲突时以现有代码为准」的纪律，这两套剖面定义在**本模块**
 *    （[ModeProfileRegistry]），并复用 `SegmentCaps` 这个数据类与它的构造期断言传统 ——
 *    没有第二套 cap 类型，也没有第二套求和口径。
 *
 * ② 文档的 CHAT 表把 `TASK` 记 0、`HISTORY_COMPACT` 记 3712。实现里这两格换了位置，原因：
 *    `ContextAssembler` 的 `HISTORY_COMPACT` 段**只接受 `StepRecord`**（任务是「步」，
 *    聊天是「轮」）。硬把聊天消息伪造成 `StepRecord` 需要编造 `StepAction` 与 taskId ——
 *    那正是 `DeterministicCompactor.compactLine(index, action, result)` 注释里明令禁止的事。
 *    于是聊天的「轮」走 `RECENT_TRACE`（它本来就接受 `List<Pair<seq, String>>`，
 *    渲染成 `#seq: …` 正好是聊天该有的样子），`HISTORY_COMPACT` 在 CHAT 剖面留 0；
 *    到了 `TASK_IN_CHAT`，`HISTORY_COMPACT` 才被打开并装**任务步**的压缩行 ——
 *    这时它与文档说的「双源」是同一件事，只是两个源各占一段而不是共享一个 cap。
 *
 * ③ `TASK_IN_CHAT` 下聊天历史只剩 `RECENT_TRACE` 那 512（STANDARD）：这是**有意的代价**，
 *    文档 §3.4 自己也写了「进入/退出任务模式会丢一部分很早之前聊天历史的细节」。
 *    而 Answer Turn 真正需要的是「用户刚问的那句 + 技能返回」，两者都在预算内。
 *
 * ## 一条不许破的纪律
 *
 * 9 张表（3 档 × 3 模式）每一张都必须**精确求和**到该档的 `usablePromptTokens`。
 * 差的那几十 token 在任务里是「系统段被静默挤压」，在聊天里等价物是**人格被悄悄挤掉** ——
 * 那是最难复现的一类线上投诉。所以下面每一张表都由 [ModeProfileRegistry] 构造期断言，
 * 并由 `ModeProfileRegistryTest` 逐张卡住。
 */
public enum class ModeKind {
    /** 纯对话：无观察段、无任务段。 */
    CHAT,

    /** 纯任务（`:agent` 既有剖面）：聊天不需要它，留着是为了三模式完整的 9 张表与对照。 */
    TASK,

    /** 聊天里派生出来的任务：观察段与任务段回来了，但聊天历史仍要留一点。 */
    TASK_IN_CHAT,
}

/** [ModeKind] ↔ 契约里的 `LoopMode`（`ChatEvent.ModeSwitched` 用的是后者）。 */
public fun ModeKind.loopMode(): LoopMode = when (this) {
    ModeKind.CHAT -> LoopMode.REPLY
    ModeKind.TASK, ModeKind.TASK_IN_CHAT -> LoopMode.TASK
}

public data class ModeProfile(
    val kind: ModeKind,
    val tier: MemoryTier,
    val caps: SegmentCaps,
    /** 该模式自己的动刀顺序。SYSTEM 永不在列（§3.4 不变量 2）。 */
    val degradationOrder: List<SegmentId>,
    /** 仅 `TASK_IN_CHAT` 使用：历史双源里「聊天历史」应得的比例（§3.4）。 */
    val historySplit: Float? = null,
) {
    init {
        require(SegmentId.SYSTEM !in degradationOrder) {
            "SYSTEM 段永不可裁剪：人格/宪章被裁掉会让 Agent「失忆变人格」（§3.4 不变量 2）"
        }
        require(historySplit == null || kind == ModeKind.TASK_IN_CHAT) {
            "historySplit 只对 TASK_IN_CHAT 有意义，$kind 传了 ${historySplit}"
        }
        require(historySplit == null || historySplit in 0f..1f) { "historySplit 必须落在 0..1，实际 $historySplit" }
    }

    /** 供 `recordFocusRelease` / 日志用的稳定短名，形如 `CHAT_STANDARD`。 */
    val name: String get() = "${kind.name}_${tier.name}"
}

/**
 * 剖面注册表。**无状态、可并发读** —— 表全部是 `val`，构造期断言一次。
 *
 * ★ 不允许运行时动态算 cap（§10-R5 缓解 ③）：动态计算意味着没人能预测这一轮会裁掉什么。
 */
public class ModeProfileRegistry {

    public fun profile(kind: ModeKind, tier: MemoryTier): ModeProfile = when (kind) {
        ModeKind.CHAT -> when (tier) {
            MemoryTier.LOW -> CHAT_LOW
            MemoryTier.MID -> CHAT_MID
            MemoryTier.HIGH -> CHAT_HIGH
        }

        ModeKind.TASK -> when (tier) {
            MemoryTier.LOW -> TASK_LOW
            MemoryTier.MID -> TASK_MID
            MemoryTier.HIGH -> TASK_HIGH
        }

        ModeKind.TASK_IN_CHAT -> when (tier) {
            MemoryTier.LOW -> TASK_IN_CHAT_LOW
            MemoryTier.MID -> TASK_IN_CHAT_MID
            MemoryTier.HIGH -> TASK_IN_CHAT_HIGH
        }
    }

    /** 全部 9 张表。单测逐张求和用；也供 `:app` 的诊断页展示。 */
    public fun all(): List<ModeProfile> = listOf(
        CHAT_LOW, CHAT_MID, CHAT_HIGH,
        TASK_LOW, TASK_MID, TASK_HIGH,
        TASK_IN_CHAT_LOW, TASK_IN_CHAT_MID, TASK_IN_CHAT_HIGH,
    )

    public companion object {

        /** `usablePromptTokens` 的**唯一来源**：与任务侧同一个 `ContextBudget.forTier`。 */
        public fun usableOf(tier: MemoryTier): Int = ContextBudget.forTier(tier).usablePromptTokens

        // ★ 这两张表必须声明在下面那些 `val` **之前**：Kotlin 的 class-init 按声明顺序执行，
        //   写在后面的私有 val 在被引用时还是 null（首跑就是在这里炸的）。

        /** 聊天模式动刀顺序：先砍最近原文（有压缩行兜底）→ 再砍技能返回 → 最后才动便签。 */
        private val CHAT_DEGRADE: List<SegmentId> = listOf(
            SegmentId.RECENT_TRACE,
            SegmentId.TOOL_RETURN,
            SegmentId.SCRATCHPAD,
        )

        /** 聊天内派生任务：观察段与任务段可砍，聊天历史与技能返回尽量留。 */
        private val TASK_IN_CHAT_DEGRADE: List<SegmentId> = listOf(
            SegmentId.OBSERVATION,
            SegmentId.RECENT_TRACE,
            SegmentId.TOOL_RETURN,
            SegmentId.HISTORY_COMPACT,
            SegmentId.SCRATCHPAD,
            SegmentId.TASK,
        )

        // ── CHAT：无观察段、无任务段；预算几乎全给「聊天历史 + 最近原文」
        //
        //  STANDARD：1024 + 256 + 0 + 1024 + 0 + 4096 + 896 = 7296 = 8192 - 896 ✅
        public val CHAT_MID: ModeProfile = chat(
            MemoryTier.MID,
            system = 1_024, task = 256, observation = 0,
            toolReturn = 1_024, historyCompact = 0, recentTrace = 4_096, scratchpad = 896,
        )

        //  NARROW：512 + 128 + 0 + 512 + 0 + 2_176 + 384 = 3712 = 4096 - 384 ✅
        public val CHAT_LOW: ModeProfile = chat(
            MemoryTier.LOW,
            system = 512, task = 128, observation = 0,
            toolReturn = 512, historyCompact = 0, recentTrace = 2_176, scratchpad = 384,
        )

        //  WIDE：2176 + 512 + 0 + 2176 + 0 + 8704 + 1792 = 15360 = 16384 - 1024 ✅
        public val CHAT_HIGH: ModeProfile = chat(
            MemoryTier.HIGH,
            system = 2_176, task = 512, observation = 0,
            toolReturn = 2_176, historyCompact = 0, recentTrace = 8_704, scratchpad = 1_792,
        )

        // ── TASK：直接复用 `:agent` 既有剖面（NARROW/STANDARD/WIDE 本身已是任务剖面）。
        //    它们的和由 `SegmentCaps` 的类注释与既有单测保证；这里只做一次复核。
        public val TASK_MID: ModeProfile = task(MemoryTier.MID, SegmentCaps.STANDARD)
        public val TASK_LOW: ModeProfile = task(MemoryTier.LOW, SegmentCaps.NARROW)
        public val TASK_HIGH: ModeProfile = task(MemoryTier.HIGH, SegmentCaps.WIDE)

        // ── TASK_IN_CHAT：文档 §3.4 那张表的数字，逐格照抄
        //
        //  STANDARD：1152 + 256 + 2304 + 768 + 1792 + 512 + 512 = 7296 ✅
        public val TASK_IN_CHAT_MID: ModeProfile = taskInChat(
            MemoryTier.MID,
            system = 1_152, task = 256, observation = 2_304,
            toolReturn = 768, historyCompact = 1_792, recentTrace = 512, scratchpad = 512,
        )

        //  NARROW：576 + 128 + 1408 + 384 + 768 + 256 + 192 = 3712 ✅
        public val TASK_IN_CHAT_LOW: ModeProfile = taskInChat(
            MemoryTier.LOW,
            system = 576, task = 128, observation = 1_408,
            toolReturn = 384, historyCompact = 768, recentTrace = 256, scratchpad = 192,
        )

        //  WIDE：2304 + 512 + 5760 + 1536 + 3584 + 1024 + 640 = 15360 ✅
        public val TASK_IN_CHAT_HIGH: ModeProfile = taskInChat(
            MemoryTier.HIGH,
            system = 2_304, task = 512, observation = 5_760,
            toolReturn = 1_536, historyCompact = 3_584, recentTrace = 1_024, scratchpad = 640,
        )

        private fun chat(
            tier: MemoryTier,
            system: Int, task: Int, observation: Int, toolReturn: Int,
            historyCompact: Int, recentTrace: Int, scratchpad: Int,
        ): ModeProfile = checked(
            ModeProfile(
                kind = ModeKind.CHAT, tier = tier,
                caps = SegmentCaps(system, task, observation, toolReturn, historyCompact, recentTrace, scratchpad),
                degradationOrder = CHAT_DEGRADE,
            ),
        )

        private fun taskInChat(
            tier: MemoryTier,
            system: Int, task: Int, observation: Int, toolReturn: Int,
            historyCompact: Int, recentTrace: Int, scratchpad: Int,
        ): ModeProfile = checked(
            ModeProfile(
                kind = ModeKind.TASK_IN_CHAT, tier = tier,
                caps = SegmentCaps(system, task, observation, toolReturn, historyCompact, recentTrace, scratchpad),
                degradationOrder = TASK_IN_CHAT_DEGRADE,
                historySplit = HISTORY_SPLIT_CHAT,
            ),
        )

        private fun task(tier: MemoryTier, caps: SegmentCaps): ModeProfile = checked(
            ModeProfile(kind = ModeKind.TASK, tier = tier, caps = caps, degradationOrder = caps.degradationOrder),
        )

        /** ★ 构造期断言：这张表必须精确求和到该档的 `usablePromptTokens`。 */
        private fun checked(p: ModeProfile): ModeProfile {
            val usable = usableOf(p.tier)
            require(p.caps.total == usable) {
                "${p.name} 的 cap 求和 ${p.caps.total} ≠ usablePromptTokens $usable —— " +
                    "差的那几十 token 会在长会话里变成人格被静默挤掉（§3.4 不变量 1）"
            }
            return p
        }

        /** §3.4：`TASK_IN_CHAT` 的历史双源配额 = 60% 聊天历史 / 40% 任务步。 */
        public const val HISTORY_SPLIT_CHAT: Float = 0.6f
    }
}
