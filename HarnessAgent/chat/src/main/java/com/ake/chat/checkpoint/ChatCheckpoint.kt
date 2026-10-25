package com.ake.chat.checkpoint

import com.ake.core.ports.ChatSessionSummary

/**
 * # 聊天侧检查点 —— **本轮只预留接口，不实现**（§6.4 / §9.2）
 *
 * ## 为什么这一轮不做
 *
 * 任务循环的检查点（`LoopCheckpoint`）解决的是「跑到第 12 步进程被杀，回来能不能接着跑」。
 * 聊天**不需要**这个语义：
 *
 *  ① 聊天唯一的「挂起」是等用户确认（`ChatEvent.ConfirmRequested`），Q6 已裁决 MVP
 *     只做终态 —— 它没有「冷启动后还能恢复」的语义（用户关掉 App 再回来，
 *     那个确认问题已经过期了，正确的行为是**重新问**，而不是恢复一个三天前的确认框）。
 *  ② 消息本身已经是检查点：用户消息同步落盘、助手消息在 turn 结束落盘。
 *     冷启动要做的是**复水**（`ChatRehydrator`），不是**续跑**。这两件事经常被混为一谈，
 *     区别在于：复水只重建上下文、不重放副作用；续跑要保证副作用不重复发生。
 *     聊天没有副作用（副作用在 Task 里，Task 有它自己的检查点）。
 *  ③ 一旦加了「聊天可续跑」，就要回答「断在哪一步、已经发出去的那半句话算不算」——
 *     而 chat_message 的 STREAMING 中间态在 MVP 里被明确收敛成 ABORTED（§2.5 纪律 2），
 *     没有可续的中间态。
 *
 * ## 这个接口留着的作用
 *
 * 让 Phase 2 的「跨设备任务迁移 / 任意时刻断点」有一个**已经想清楚落点**的地方：
 * 迁移的粒度是「会话 + 最后一条消息 + 复水基准」，即 [ChatCheckpointSnapshot]
 * 那五个字段 —— 它们**全部已经在 `chat_session` 表里**（`last_kv_cache_tokens` /
 * `last_rehydrate_tokens` / `compact_level` / `context_profile` / `mode_epoch`）。
 * 也就是说：真要实现时，本接口的实现体大概率只是「把这五列读出来」，
 * 而不是一个新的持久化结构。**没有新增表，也没有新增列** —— 这是它现在就能被冻结的原因。
 */
public interface ChatCheckpointPlanner {

    /** 这个会话此刻能否被迁移 / 恢复。`false` 时 [reasonKey] 说明为什么。 */
    public suspend fun canCheckpoint(sessionId: String): Checkpointability

    /**
     * 产出一份快照。**MVP 不会有人调它** —— 实现方（Phase 2）必须自己保证
     * 快照里的 `kvTokens` 与 `rehydrateTokens` 在同一时刻采样，否则复水基准会自相矛盾。
     */
    public suspend fun snapshot(sessionId: String): ChatCheckpointSnapshot?
}

public data class Checkpointability(val allowed: Boolean, val reasonKey: String?) {
    public companion object {
        public val ALLOWED: Checkpointability = Checkpointability(true, null)

        public fun denied(reasonKey: String): Checkpointability = Checkpointability(false, reasonKey)
    }
}

/**
 * 一份可以被搬走的会话状态。**刻意只放「重建上下文所需」的东西**：
 * 多放一个字段，就多一个「迁移过去之后与本地事实不一致」的机会。
 */
public data class ChatCheckpointSnapshot(
    val sessionId: String,
    val agentId: String,
    /** 已经紧凑到哪条 seq。复水从这里往后读。 */
    val compactedUptoSeq: Int,
    /** 关闭时的 `currentCacheTokens()` 快照。 */
    val kvTokens: Int,
    /** 上次复水花了多少 token（用来校准 `rehydrateTokenCap`）。 */
    val rehydrateTokens: Int,
    val modeEpoch: Int,
    val contextProfile: String,
    val takenAt: Long,
) {
    public companion object {
        /** 从列表项造快照的便捷函数：这五列 `ChatSessionSummary` 全都有，不需要新查询。 */
        public fun of(summary: ChatSessionSummary, kvTokens: Int, takenAt: Long): ChatCheckpointSnapshot =
            ChatCheckpointSnapshot(
                sessionId = summary.id,
                agentId = summary.agentId,
                compactedUptoSeq = summary.compactedUptoSeq,
                kvTokens = kvTokens,
                rehydrateTokens = 0,
                modeEpoch = summary.modeEpoch,
                contextProfile = summary.lastMode.name,
                takenAt = takenAt,
            )
    }
}
