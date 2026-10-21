package com.ake.agent.loop

import com.ake.core.common.TaskId
import com.ake.core.contract.StepAction
import com.ake.core.contract.TaskSuspension
import com.ake.core.contract.ToolResult

/**
 * 主循环产生的可观测事件流（供 UI 实时渲染轨迹）。
 *
 * **为什么用 Flow 而不是回调**：端侧 Agent 一步 4.5 秒、一个任务 8–25 步，
 * UI 上「卡在第 5 步不动」与「第 5 步正在等人工确认」是两种完全不同的体验。
 * Flow 让 UI 层自己决定 collect 的生命周期与背压策略，Loop 不需要知道 UI 是否存在。
 *
 * ## ★ 流的最后一条一定是「终结语」，且它有两种
 *
 * ```
 *  … → [TaskEvent.Terminal]    任务真的结束了（不可恢复）
 *  … → [TaskEvent.Suspended]   任务被挂起（可恢复，状态是 PAUSED / WAITING_HUMAN）
 * ```
 *
 * 这两条**互斥且穷尽**了流的终点，所以 UI 一个 `collect` 就能表达「跑到结束」，
 * 不需要再配对一个 `Result` 返回值（配对返回值是回调风格的典型泄漏）。
 *
 * ### 为什么「暂停」不是一条 `Terminal(USER_ABORT, detail="paused:xxx")`
 *
 * 那是本文件曾经的实现，已废弃。它有三个无法回避的问题：
 *
 *  1. **语义说谎**：`USER_ABORT` 的落库 status 是 `ABORTED`（终态），而任务其实还活着。
 *     于是「查所有已结束任务」会把暂停中的任务一起捞出来。
 *  2. **靠字符串前缀区分真假**：调用方只能写 `detail.startsWith("paused:")` 去丢弃它。
 *     这类「用字符串约定补类型系统的洞」是最容易在重构里被静默删掉的代码。
 *  3. **UI 无法在 `when` 里穷尽**：暂停被迫退化成终止的一个分支，
 *     「显示继续按钮」还是「显示已结束」要在分支内部再判一次字符串。
 *
 * 现在：挂起一律走 [TaskEvent.Paused]（渲染用，带全量展示信息）+
 * [TaskEvent.Suspended]（流终点，携带 `core:contract` 的 [TaskSuspension]）。
 * `TaskSuspension` 的构造器 `require` 了 status 必须是挂起态，
 * 所以「拿一个终态当暂停发出来」在构造点就崩，不会静默流到屏幕上。
 *
 * 注意所有文本字段都必须是**已脱敏的**：这些事件会一路流到通知栏与 UI。
 * 脱敏发生在采集点（`:core:common/Redactor`）与闸口（`PolicyPort.sanitize`），不在这里。
 */
sealed interface TaskEvent {

    val taskId: TaskId

    data class Started(override val taskId: TaskId, val resumedFromStep: Int, val goalPreview: String) : TaskEvent

    data class StatusChanged(override val taskId: TaskId, val status: String) : TaskEvent

    /** 感知完成。digest 用于 UI 判断「页面到底变了没」。 */
    data class Observed(
        override val taskId: TaskId,
        val seq: Int,
        val url: String?,
        /** 页面标题（已过闸 1 脱敏，与 `ObservationSummary.titleRedacted` 同口径）。 */
        val titleRedacted: String?,
        val pageDigest: String,
        val candidates: Int,
        val observationId: String,
    ) : TaskEvent

    /**
     * 模型输出的**增量片段**（真正的流式 token 展示）。
     *
     * ## 为什么它必须是独立事件，而不是塞进 [Planned]
     *
     * [Planned] 是「解析成功之后」的事件 —— 它只在模型吐完、且能被解析成
     * [StepAction] 时才发出。而流式展示要的是「解码到第 3 个 token 时屏幕上就该有字」，
     * 那时候还不知道这一步能不能解析成功。**解析失败的那一步恰恰最需要看到原文**
     * （否则用户只看到「输出无法解析」，没有任何线索）。这两个时机不可能合并。
     *
     * ## 当前后端只支持「整段交付」
     *
     * `ModelSession.generate()` 是一次性返回 `GenerationResult`（`core:model-api` 还没有
     * 逐 token 通道），所以眼下每步只会发**一条**携带完整原文的 [TokenDelta]。
     * 这不是假的流式 —— 事件语义（「又多了一段输出」）在两种后端下都成立，
     * 只是分段粒度由引擎决定。等 `ModelSession` 拿到流式能力，
     * 改的是 `:agent` 的 [com.ake.agent.loop.TokenStream] 实现，本事件与 UI 侧都不用动。
     *
     * @param seq 所属步序号 —— UI 需要知道这段增量该追加到哪一步
     * @param delta **增量**文本（不是累计全文）。UI 侧直接 `append`，不要用它覆盖。
     */
    data class TokenDelta(
        override val taskId: TaskId,
        val seq: Int,
        val delta: String,
    ) : TaskEvent

    /** 模型已产出动作。 */
    data class Planned(
        override val taskId: TaskId,
        val seq: Int,
        val action: StepAction,
        val promptTokens: Int,
        val prefillTokens: Int,
        val decodeTokens: Int,
    ) : TaskEvent

    /** 护栏判定。decision 取值见 `core/contract/PolicyDecision`。 */
    data class Guarded(
        override val taskId: TaskId,
        val seq: Int,
        val decision: String,
        val reasonKey: String?,
        val riskClass: String?,
    ) : TaskEvent

    /** 已挂起等待 UI 响应（HumanGate）。UI 必须响应，否则 Loop 会一直挂在这里。 */
    data class ConfirmRequested(
        override val taskId: TaskId,
        val seq: Int,
        val action: StepAction,
        val reasonKey: String,
        val args: List<String>,
    ) : TaskEvent

    data class Executed(
        override val taskId: TaskId,
        val seq: Int,
        val result: ToolResult,
        val durationMs: Long,
    ) : TaskEvent

    /** 输出不可解析，已生成修正提示。 */
    data class RepairIssued(
        override val taskId: TaskId,
        val seq: Int,
        val failureCode: String,
        val attempt: Int,
    ) : TaskEvent

    /** 终止保护触发（§3.5 的 8 类每触发一次都要留证据）。 */
    data class GuardTriggered(
        override val taskId: TaskId,
        val seq: Int,
        val kind: String,
        val detail: String,
    ) : TaskEvent

    /** 感知降级或失败。trace 上必须能看出来「这一步其实没看到页面」。 */
    data class PerceptionFault(
        override val taskId: TaskId,
        val seq: Int,
        val code: String,
    ) : TaskEvent

    /**
     * ★ 已挂起（**可恢复，不是终态**）。渲染用事件。
     *
     * 这一条把 UI 需要的**全部**展示信息一次带齐，让 UI 不必再拿 `status` 字符串去反推：
     *
     * @property status 挂起态，`PAUSED` 或 `WAITING_HUMAN`（= [SuspendReason.status]）。
     *           冗余存一份是为了让 UI 不需要先 import `SuspendReason` 才知道怎么写库/怎么选相位。
     * @property stepSeq **恢复起点**。恢复时从这一步继续，预算累计从断点带回来。
     * @property checkpointSaved 断点是否真的存住了。`false` ⇒ UI 必须禁用「继续」。
     *           **不允许硬编码 `true`** —— 「显示了继续按钮但点了恢复不了」比「直接说不能继续」糟得多。
     * @property resumable 是否可以「点继续」。= `checkpointSaved && reason != HUMAN_GATE`
     *           （`HUMAN_GATE` 的解锁动作是去点确认框，不是点继续）。
     * @property resumeHint 可直接展示的人话。为 `null` 时 UI 回落到自己的文案。
     * @property atMillis 挂起时刻（`Clock.nowMillis()`），通知栏与「已暂停 3 分钟」要用。
     */
    data class Paused(
        override val taskId: TaskId,
        val reason: SuspendReason,
        val status: String,
        val stepSeq: Int,
        val checkpointSaved: Boolean,
        val resumable: Boolean,
        val resumeHint: String? = null,
        val atMillis: Long,
    ) : TaskEvent

    data class CheckpointSaved(override val taskId: TaskId, val stepSeq: Int) : TaskEvent

    /**
     * ★ **流终点之一（可恢复挂起）**。与 [Terminal] 互斥，二者必有且仅有一个出现在流末尾。
     *
     * 携带 `core:contract` 的 [TaskSuspension] —— 它是「已暂停」这一事实的领域载体，
     * 构造器 `require(status in TaskStatus.SUSPENDED)`，所以终态塞不进来。
     *
     * UI 在这一条上做的事与 [Terminal] 分支对称：**停掉计时器、刷新「还能继续」列表**，
     * 但**不要**把状态改成终态（那是 [Paused] 分支的职责）。
     */
    data class Suspended(
        override val taskId: TaskId,
        val suspension: TaskSuspension,
    ) : TaskEvent

    /** 终态。**流里的最后一条。** */
    data class Terminal(
        override val taskId: TaskId,
        val reason: TerminalReason,
        val outcome: TaskOutcome?,
        val detail: String? = null,
    ) : TaskEvent {
        val isSuccess: Boolean get() = reason.isSuccess
    }
}

/**
 * 便于 UI 折叠事件：只有这几类值得在通知栏出现。
 *
 * [TaskEvent.TokenDelta] 刻意不在列 —— 它是每步一条（未来每 token 一条）的高频事件，
 * 拿它去刷通知栏会把通知栏变成噪音源。
 */
fun TaskEvent.isUserVisible(): Boolean = when (this) {
    is TaskEvent.Started,
    is TaskEvent.ConfirmRequested,
    is TaskEvent.Paused,
    is TaskEvent.Suspended,
    is TaskEvent.Terminal,
    is TaskEvent.GuardTriggered,
    -> true

    else -> false
}

/**
 * ★ 流终点判定。UI 的 `collect` 收尾逻辑（停计时器、刷新可恢复列表）挂在它上面。
 *
 * 与「`status` 是不是终态」是**两个问题**：`Suspended` 也是流的终点，但它不代表任务结束。
 */
fun TaskEvent.isEndOfStream(): Boolean = this is TaskEvent.Terminal || this is TaskEvent.Suspended
