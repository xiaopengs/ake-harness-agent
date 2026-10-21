package com.ake.data.persist.store

import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolResult

/**
 * `StepRecord` → `step` 行所需的三件「L4 知识」。
 *
 * ## 为什么它是一个接口而不是在本模块里写死
 *
 * `step.action_key`、`step.action_json`、`step.action_summary` 三列的口径
 * 全部来自 `:agent`（分别是 `LoopState.actionKeyOf`、`StepActionCodec`、
 * `DeterministicCompactor.compactLine`）。**:data（L3）不该认识它们** ——
 * 否则改一次 Action DSL 或压缩行格式就要同时改存储层。
 *
 * ## 依赖方向
 *
 * 架构 §1.2 与数据库 §3.1 的分层图都是 **L4 → L3**（`:agent` 依赖 `:data`），
 * 所以由 `:agent` 实现本接口、`:app` 在装配时注入，方向是顺的。
 * 本接口**不含任何 Room 类型**，因此不构成抽象泄漏。
 *
 * 实现方：`com.ake.agent.planner.AgentStepTraceCodec`。
 */
interface StepTraceCodec {

    /** 动作 → 可还原的紧凑 JSON（`step.action_json`）。 */
    fun encodeAction(action: StepAction): String

    /** 反向。返回 null 表示该行历史不可用（调用方跳过，不伪造）。 */
    fun decodeAction(json: String): StepAction?

    /** §3.5 完全重复检测的归一化键（`step.action_key`）。 */
    fun actionKey(action: StepAction): String

    /** §4.5 确定性压缩行（`step.action_summary`）。约 15–25 token/行。 */
    fun actionSummary(index: Int, action: StepAction, result: ToolResult): String
}
