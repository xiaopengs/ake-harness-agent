package com.ake.agent.planner

import com.ake.agent.context.DeterministicCompactor
import com.ake.agent.loop.LoopState
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolResult
import com.ake.data.persist.store.StepTraceCodec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `StepTraceCodec` 的 `:agent` 侧实现。
 *
 * 三列口径分别来自三个**各自权威**的出处，本类只做汇聚，不重新定义：
 *  - `action_json`  ← [StepActionCodec]（Action DSL 的编解码）
 *  - `action_key`   ← [LoopState.actionKeyOf]（§3.5 完全重复检测的归一化键）
 *  - `action_summary` ← [DeterministicCompactor.compactLine]（§4.5 确定性压缩行）
 *
 * **不在 `:data` 里重写这三段逻辑**是刻意的：它们的形态会随 Action DSL 与压缩策略演进，
 * 而存储层不该跟着动。依赖方向 `:agent`（L4）→ `:data`（L3）与架构 §1.2 一致。
 */
@Singleton
class AgentStepTraceCodec @Inject constructor() : StepTraceCodec {

    override fun encodeAction(action: StepAction): String = StepActionCodec.encode(action)

    override fun decodeAction(json: String): StepAction? = StepActionCodec.decode(json)

    override fun actionKey(action: StepAction): String = LoopState.actionKeyOf(action)

    override fun actionSummary(index: Int, action: StepAction, result: ToolResult): String =
        DeterministicCompactor.compactLine(index, action, result)
}
