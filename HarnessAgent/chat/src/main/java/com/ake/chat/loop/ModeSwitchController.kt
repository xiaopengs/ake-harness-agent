package com.ake.chat.loop

import com.ake.chat.ctx.ModeKind
import com.ake.chat.ctx.ModeProfile
import com.ake.chat.ctx.ModeProfileRegistry
import com.ake.chat.ctx.loopMode
import com.ake.core.ports.LoopMode
import com.ake.core.ports.MemoryTier

/**
 * # 模式切换 + epoch 记账（§2.7 第 3 条）
 *
 * > **模式切换的成本必须显式可见。** 切换剖面必然伴随一次整段重排 ⇒ 一次额外 prefill。
 * > 把它做成 `ChatEvent.ModeSwitched(epoch, prefillTokens)`，成本就进了预算总账，
 * > 也进了用户视野。**藏起来的成本会在压测时以「怎么突然慢了 2 秒」的形式回到脸上。**
 *
 * ## 为什么 epoch 必须单调递增且落库
 *
 * `chat_session.mode_epoch` 是「本轮 prompt 是按第几版剖面排的」的唯一证据。
 * 冷启动复水时如果 epoch 对不上，说明历史是在另一套剖面下压的 —— 那时要么整段重排，
 * 要么承认记忆变淡。没有 epoch，这两件事都无从判断。
 *
 * ## 谁负责触发
 *
 * 只有 `ChatLoop`：路由裁决出 SKILL_CALL → `to(TASK_IN_CHAT)`；技能结束回到
 * `to(CHAT)`。**用户不能手动切**（MVP），因为「切到任务模式」需要一份可执行的任务，
 * 而那份任务只能来自路由裁决（§2.6 纪律 2：TaskSpec 由 SkillContext 构造）。
 */
public class ModeSwitchController(
    private val tier: MemoryTier,
    private val registry: ModeProfileRegistry,
    initialKind: ModeKind = ModeKind.CHAT,
    initialEpoch: Int = 0,
) {
    public var kind: ModeKind = initialKind
        private set

    /** 冷启动时从 `chat_session.mode_epoch` 带回来，不重置为 0 —— 重置会让「同一个 epoch」出现两次。 */
    public var epoch: Int = initialEpoch
        private set

    public fun profile(): ModeProfile = registry.profile(kind, tier)

    public fun loopMode(): LoopMode = kind.loopMode()

    /**
     * 切到目标模式。**同模式返回 null**（不记账、不发事件）——
     * 否则每次技能内部的小切换都会刷一条 `ModeSwitched`，用户会以为 Agent 在抽风。
     *
     * @param prefillTokens 这次切换要多付的 prefill 估算。由调用方按「重排后的 prompt」估，
     *        因为只有它手里有组装好的 sections。
     */
    public fun to(target: ModeKind, prefillTokens: Int): ModeTransition? {
        if (target == kind) return null
        val from = kind
        epoch += 1
        kind = target
        return ModeTransition(
            fromKind = from,
            toKind = target,
            from = from.loopMode(),
            to = target.loopMode(),
            epoch = epoch,
            prefillTokens = prefillTokens.coerceAtLeast(0),
        )
    }

    public data class ModeTransition(
        val fromKind: ModeKind,
        val toKind: ModeKind,
        val from: LoopMode,
        val to: LoopMode,
        val epoch: Int,
        val prefillTokens: Int,
    )
}
