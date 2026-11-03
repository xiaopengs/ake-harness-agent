package com.ake.agent.planner

import com.ake.core.common.Outcome
import com.ake.core.ports.FocusHolder
import com.ake.core.ports.FocusLease
import com.ake.core.ports.FocusPriority
import com.ake.core.ports.InferenceFocus

/**
 * # 计划模式与推理焦点的交界
 *
 * ## 为什么计划需要租约（这不是"顺手加的保险"）
 *
 * LiteRT-LM 一个 `Engine` 同时只允许一个 `Conversation`。聊天侧早就有
 * `InferenceFocusArbiter` 守着这条线，但**计划是另一条进入 native 的路径**：
 * 生成草稿、每块摘要、抽待办，全都要 `openSession`。如果此时用户正在聊天，
 * 两条路径会同时往引擎里灌 prompt —— 表现是**回答里混进另一段文字**（F3），
 * 而不是一个干净的错误。后者还能查，前者只能靠复现。
 *
 * ## 为什么一律用 `DERIVED_TASK`，不抢 `FOREGROUND_USER`
 *
 * 用户点「生成计划」时确实在等，用 `FOREGROUND_USER` 能立刻拿到槽 —— 代价是
 * **把正在生成的那条聊天回复踢掉**。而仲裁器的抢占是「改版本号」，被抢的一方
 * 下一次查 `isValid()` 才知道，可它此刻正卡在 native 里吐字；等它察觉，字已经混了。
 *
 * 所以这里选保守的一侧：**计划永远不抢聊天**。拿不到就立刻失败（1 秒内告诉用户
 * 「助手正忙，稍后再试」），而不是排队等 —— 排队会变成「点了生成，20 秒没反应」，
 * 那比失败更难解释。
 */
internal suspend fun InferenceFocus.acquireForPlan(holder: FocusHolder): Outcome<FocusLease> =
    acquire(holder, FocusPriority.DERIVED_TASK)

/** 计划里的模型调用共用的持有者标识：同一个任务的所有步骤属于同一次执行。 */
internal fun planFocusHolder(taskId: String): FocusHolder =
    FocusHolder.Task(taskId = taskId, parentSessionId = null)

/**
 * 没有装配仲裁器时的退化实现。
 *
 * 只给单测和「`:agent` 被单独引用」的场景用。生产由 `:app` 注入
 * `ChatServices.focus` —— **必须是同一个实例**：另造一个 `InferenceFocusArbiter`
 * 等于两份互不知情的锁，比没有锁更糟（两边都以为自己拿到了）。
 */
internal object NoInferenceFocus : InferenceFocus {

    override suspend fun acquire(holder: FocusHolder, priority: FocusPriority): Outcome<FocusLease> =
        Outcome.Ok(NoopLease)

    override fun current(): FocusHolder? = null

    private object NoopLease : FocusLease {
        override fun isValid(): Boolean = true
        override fun close(): Unit = Unit
    }
}
