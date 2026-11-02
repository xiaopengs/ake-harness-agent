package com.ake.core.ports

import com.ake.core.common.TaskId
import com.ake.core.contract.PlanRevision
import com.ake.core.contract.PlanStep
import kotlinx.coroutines.flow.Flow

/**
 * 计划版本存储（V1.0 M2）。
 *
 * ## 为什么"确认"是一次 CAS 而不是一个 boolean 字段
 *
 * 计划确认页可能同时存在于两个地方（任务详情、通知跳转），用户在两处都点了"开始"。
 * 如果确认只是 `UPDATE status='CONFIRMED'`，第二次点击会返回成功 —— 于是执行器会收到
 * 两次"开始执行"，而端侧只有一个推理焦点。CAS（只有 DRAFT 能变 CONFIRMED）
 * 让第二次点击**明确失败**，上层可以据此什么都不做。
 *
 * ## 为什么没有 `update(plan)` 这种通用写
 *
 * 见 `PlanDao` 的注释：允许原地改步骤会让"用户确认的是哪一版"这个前提失效。
 * 要改就重新生成一版（`saveDraft` 自动 +1）。
 */
public interface PlanStorePort {

    /**
     * 追加一版草稿，并把同一任务的旧草稿置为 `SUPERSEDED`。
     *
     * @return 落库后的版本（`revision` 已由实现分配）。
     */
    public suspend fun saveDraft(taskId: TaskId, goal: String, steps: List<PlanStep>): PlanRevision

    /**
     * 确认某一版。
     *
     * @return true = 这次点击真正把它从草稿变成了已确认；
     *         false = 已经不是草稿（已被确认 / 已被取代 / 不存在）—— 调用方**不该**报错。
     */
    public suspend fun confirm(taskId: TaskId, revision: Int): Boolean

    public suspend fun latest(taskId: TaskId): PlanRevision?

    public fun observeLatest(taskId: TaskId): Flow<PlanRevision?>
}
