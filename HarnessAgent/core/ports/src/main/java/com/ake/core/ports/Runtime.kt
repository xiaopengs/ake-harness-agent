package com.ake.core.ports

import com.ake.core.common.TaskId
import com.ake.core.contract.LoopCheckpoint
import com.ake.core.contract.RiskClass
import com.ake.core.contract.StepAction
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.TaskStatus
import com.ake.core.contract.TaskSuspension
import com.ake.core.contract.ToolResult
import kotlinx.coroutines.flow.Flow

/**
 * # 主循环运行时契约：**UI 与 ReAct 主循环之间唯一合法的那组类型**
 *
 * 本文件是为「`:ui` 跨层依赖整改」第一批补进来的契约（2026-10，邝随行）。
 *
 * ## ★★ 迁移说明：这些类型与 `:agent` 里的同名类型是**同一批东西的两个副本**
 *
 * 它们此前只存在于 `:agent`：
 * [TaskEvent] 在 `agent/loop/AgentEvents.kt`、[TerminalReason] 与 [SuspendReason] 在
 * `agent/loop/TerminalReason.kt`、[RunOptions] 在 `agent/loop/AgentLoop.kt`、
 * [TaskOutcome] 在 `agent/loop/LoopState.kt`。
 * UI 为了拿到它们不得不 `implementation(project(":agent"))`，而那条 Gradle 边一旦存在，
 * `:agent` 的每个 public 类都对 UI 可见 —— 分层是拦不住的。
 *
 * **本文件的定位是「新的唯一事实来源」**，落地方式是——由 `:agent` 侧把这几个类型的
 * 主体**下移到这里**（不是复制第二份）：
 *   1. 删除 `agent/loop/AgentEvents.kt` 与 `agent/loop/TerminalReason.kt` 里的
 *      `TaskEvent` / `TerminalReason` / `SuspendReason`，改 `import com.ake.core.ports.*`；
 *   2. `AgentLoop` 的 `RunOptions` 与 `LoopState.kt` 的 `TaskOutcome` 同理；
 *   3. `AgentLoop` 加 `: TaskRunnerPort`，`run()` 加 `override`（当前 `TaskStorePort`
 *      那条也是这个打法，见 `Persistence.kt` 里 `observeRecent` 的处理）。
 * 改完之后 UI 侧改成只依赖本模块，`:ui` 的 Gradle 边才能真正摘掉。
 *
 * ⚠ **在第 1–3 步完成之前，不要移除 `ui/build.gradle.kts` 的 `:agent` 依赖** ——
 *   那会让 UI 同时看见两套 `TaskEvent`，`when` 分支直接编译失败。
 *   「先加契约 → 再改实现 → 最后摘 Gradle 边」这个顺序不能颠倒。
 *
 * ## 为什么这组类型是 Port 而不是 `:core:contract`
 *
 * 与本模块既有约定一致：`PolicyVerdict` / `Resolved` / `ActionContext`（Policy.kt）、
 * `MemoryTier` / `BatteryState`（Device.kt）这些「跟随某个 Port 家族的数据形状」
 * 本来就住在 `core:ports`。`:core:ports` 的构建脚本也只写了
 * 「Port 只定义接口 + data class / sealed interface，不承载行为」这条规矩。
 */

/**
 * 默认模型标识。
 *
 * 此前这个值在三个地方各硬编码一次：`AgentLoop.RunOptions.modelId`、
 * `UiRuntimeModule` 注不进去的地方、以及 `AgentSessionCoordinator.DEFAULT_MODEL_ID`。
 * 收敛到 Port 常量后，`ModelCatalog.GEMMA4_E2B_ID` 也应指向这里 ——
 * 三处模型 id 不同步的症状是「UI 显示用的是 A 模型，实际加载的是 B」。
 */
public const val DEFAULT_MODEL_ID: String = "gemma-4-E2B-it"

// ─────────────────────────────────────────────────────────────────────────────
// 一、主循环驱动
// ─────────────────────────────────────────────────────────────────────────────

/**
 * UI 驱动主循环的**唯一入口**。实现落在 `:agent`（`AgentLoop`）。
 *
 * 刻意只暴露一个 `run`：UI 需要的「跑起来 / 在中途停止」分别由它本身和
 * [AbortSignal] 表达，不需要额外的 `pause()` / `stop()` —— 多一个方法就多一条
 * 「调用它时循环到底处在什么状态」的隐含前提。
 */
public interface TaskRunnerPort {

    /**
     * 跑一次任务。**返回冷 Flow，最后一条事件必落终结语**。
     *
     * ` Terminal` 与 `Suspended` 互斥且穷尽（可恢复挂起 ≠ 终止），
     * 所以调用方一个 `collect` 就能表达「跑到结束」，不需要再配对一个返回值。
     */
    public fun run(task: TaskSpec, opts: RunOptions = RunOptions()): Flow<TaskEvent>
}

/** 一次运行的入参。不可变纯数据，便于黄金轨迹回放。 */
public data class RunOptions(
    val abort: AbortSignal = NoAbortSignal,
    val resumeFrom: LoopCheckpoint? = null,
    val modelId: String = DEFAULT_MODEL_ID,
    /** 每步结束是否落一次断点。落盘是 try-send，成本可忽略，MVP 默认每步都落。 */
    val checkpointEveryStep: Boolean = true,
)

// ─────────────────────────────────────────────────────────────────────────────
// 二、事件流
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 主循环产生的可观测事件流（供 UI 实时渲染轨迹）。
 *
 * ## ★ 流的最后一条一定是终结语，而且有两种
 *
 * ```
 *  … → [TaskEvent.Terminal]    任务真的结束了（不可恢复）
 *  … → [TaskEvent.Suspended]   任务被挂起（可恢复，state 是 PAUSED / WAITING_HUMAN）
 * ```
 *
 * 二者**互斥且穷尽**，UI 因此可以用 `when` 穷尽而不是拿 `status` 字符串去猜。
 *
 * ## 为什么不用回调
 *
 * 端侧一步 4.5 秒、一个任务 8–25 步，「卡在第 5 步」与「第 5 步在等人工确认」
 * 是两种完全不同的体验。Flow 让消费方自己决定生命周期与背压，Loop 不知道 UI 是否存在。
 *
 * **所有文本字段都必须是已脱敏的**：这些事件会一路流到通知栏与屏幕上。
 * 脱敏发生在采集点与闸口，不在这里。
 */
public sealed interface TaskEvent {

    public val taskId: TaskId

    public data class Started(
        override val taskId: TaskId,
        val resumedFromStep: Int,
        val goalPreview: String,
    ) : TaskEvent

    public data class StatusChanged(override val taskId: TaskId, val status: String) : TaskEvent

    /** 感知完成。`pageDigest` 用于 UI 判断「页面到底变了没」。 */
    public data class Observed(
        override val taskId: TaskId,
        val seq: Int,
        val url: String?,
        /** 页面标题（已过闸脱敏，与 `ObservationSummary.titleRedacted` 同口径）。 */
        val titleRedacted: String?,
        val pageDigest: String,
        val candidates: Int,
        val observationId: String,
    ) : TaskEvent

    /**
     * 模型输出的**增量片段**。`delta` 是增量而非累计，UI 侧直接 append。
     *
     * 它必须独立于 [Planned]：[Planned] 只在「吐完且能解析成 StepAction」时发出，
     * 而**解析失败那一步恰恰最需要看到原文** —— 两个时机不可能合并。
     */
    public data class TokenDelta(
        override val taskId: TaskId,
        val seq: Int,
        val delta: String,
    ) : TaskEvent

    /** 模型已产出动作。 */
    public data class Planned(
        override val taskId: TaskId,
        val seq: Int,
        val action: StepAction,
        val promptTokens: Int,
        val prefillTokens: Int,
        val decodeTokens: Int,
    ) : TaskEvent

    /** 护栏判定。`decision` 取值见 `core/contract/PolicyDecision`。 */
    public data class Guarded(
        override val taskId: TaskId,
        val seq: Int,
        val decision: String,
        val reasonKey: String?,
        val riskClass: String?,
    ) : TaskEvent

    /** 已挂起等待 UI 响应（HumanGate）。UI 必须响应，否则 Loop 一直停在这里。 */
    public data class ConfirmRequested(
        override val taskId: TaskId,
        val seq: Int,
        val action: StepAction,
        val reasonKey: String,
        val args: List<String>,
    ) : TaskEvent

    public data class Executed(
        override val taskId: TaskId,
        val seq: Int,
        val result: ToolResult,
        val durationMs: Long,
    ) : TaskEvent

    /** 输出不可解析，已生成修正提示。 */
    public data class RepairIssued(
        override val taskId: TaskId,
        val seq: Int,
        val failureCode: String,
        val attempt: Int,
    ) : TaskEvent

    /** 终止保护触发。*每触发一次都要留证据*，UI 上以一步内的警示行呈现。 */
    public data class GuardTriggered(
        override val taskId: TaskId,
        val seq: Int,
        val kind: String,
        val detail: String,
    ) : TaskEvent

    /** 感知降级或失败。trace 上必须能看出「这一步其实没看到页面」。 */
    public data class PerceptionFault(
        override val taskId: TaskId,
        val seq: Int,
        val code: String,
    ) : TaskEvent

    /**
     * ★ 已挂起（**可恢复，不是终态**）。渲染用事件，一次带齐 UI 需要的全部展示信息。
     *
     * @property stepSeq **恢复起点**。恢复时从这里继续，预算累计从断点带回来。
     * @property checkpointSaved 断点是否真的存住了。`false` ⇒ UI 必须禁用「继续」。
     *           **不允许硬编码 `true`** —— 「显示了继续按钮但点了恢复不了」更糟。
     * @property resumable 能否「点继续」。= `checkpointSaved && reason != HUMAN_GATE`
     *           （HUMAN_GATE 的解锁动作是去点确认框，不是点继续）。
     * @property atMillis 挂起时刻，用于「已暂停 3 分钟」这类文案。
     */
    public data class Paused(
        override val taskId: TaskId,
        val reason: SuspendReason,
        val status: String,
        val stepSeq: Int,
        val checkpointSaved: Boolean,
        val resumable: Boolean,
        val resumeHint: String? = null,
        val atMillis: Long,
    ) : TaskEvent

    public data class CheckpointSaved(override val taskId: TaskId, val stepSeq: Int) : TaskEvent

    /**
     * ★ **流终点之一（可恢复挂起）**。与 [Terminal] 互斥，必有且仅有一个出现在流末尾。
     *
     * 携带 `TaskSuspension`（它的构造器 `require` 了 status 必须是挂起态），
     * 所以「拿一个终态当暂停发出来」在构造点就崩，不会静默流到屏幕上。
     */
    public data class Suspended(
        override val taskId: TaskId,
        val suspension: TaskSuspension,
    ) : TaskEvent

    /** 终态。**流里的最后一条。** */
    public data class Terminal(
        override val taskId: TaskId,
        val reason: TerminalReason,
        val outcome: TaskOutcome?,
        val detail: String? = null,
    ) : TaskEvent {
        public val isSuccess: Boolean get() = reason.isSuccess
    }
}

/** 任务产出。UI 只渲染，不判定 —— 判定在 `:agent` 的 Verifier 里。 */
public data class TaskOutcome(
    val taskId: TaskId,
    val reason: TerminalReason,
    val answer: String?,
    val evidenceRefs: List<String>,
    val stepsDone: Int,
    val prefillTokensTotal: Long,
    val decodeTokensTotal: Long,
    val elapsedMsTotal: Long,
    /** 通过 `local.export_records` 导出的表格文本（md/csv/json）。 */
    val exported: String? = null,
) {
    public val isSuccess: Boolean get() = reason.isSuccess
}

// ─────────────────────────────────────────────────────────────────────────────
// 三、终态与挂起原因
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 十二终态（架构 §3.5）。**由三重判定产生，模型的意见只占一票**：
 * 结构性谓词（主力）+ 模型显式 finish + 终止保护熔断。
 *
 * @property status 落库 `task.status`。取值来自 `core:contract/TaskStatus`，不自创。
 * @property reasonKey 落库 `terminal_reason`，同时是 UI 文案的 i18n key。
 * @property isSuccess 是否算「正常完成」，用于 UI 选颜色与统计口径。
 * @property keepCheckpoint 是否保留断点供用户手动重试（与自动恢复无关）。
 */
public enum class TerminalReason(
    public val status: String,
    public val reasonKey: String,
    public val isSuccess: Boolean,
    public val keepCheckpoint: Boolean,
) {
    // ── Layer 1 + Layer 2：三层判定一致才算完成 ──────────────────────────
    COMPLETED(TaskStatus.SUCCEEDED, "terminal_completed", isSuccess = true, keepCheckpoint = false),

    // ── Layer 2：结构性判定 ─────────────────────────────────────────────
    INFEASIBLE(TaskStatus.IMPOSSIBLE, "terminal_infeasible", isSuccess = false, keepCheckpoint = false),
    FABRICATED_ANSWER(TaskStatus.IMPOSSIBLE, "terminal_fabricated_answer", isSuccess = false, keepCheckpoint = true),

    // ── Layer 0：终止保护 ───────────────────────────────────────────────
    USER_ABORT(TaskStatus.ABORTED, "terminal_user_abort", isSuccess = false, keepCheckpoint = true),
    BATTERY_STOP(TaskStatus.ABORTED, "terminal_battery_stop", isSuccess = false, keepCheckpoint = true),

    BUDGET_EXHAUSTED(TaskStatus.FAILED, "terminal_budget_exhausted", isSuccess = false, keepCheckpoint = false),
    TIMEOUT(TaskStatus.FAILED, "terminal_timeout", isSuccess = false, keepCheckpoint = true),
    LOOP_DETECTED(TaskStatus.FAILED, "terminal_loop_detected", isSuccess = false, keepCheckpoint = false),
    NO_PROGRESS(TaskStatus.FAILED, "terminal_no_progress", isSuccess = false, keepCheckpoint = false),
    OSCILLATION(TaskStatus.FAILED, "terminal_oscillation", isSuccess = false, keepCheckpoint = false),
    LOCATE_FAILED(TaskStatus.FAILED, "terminal_locate_failed", isSuccess = false, keepCheckpoint = true),
    REPAIR_FAILED(TaskStatus.FAILED, "terminal_repair_failed", isSuccess = false, keepCheckpoint = true),
    ;

    public companion object {

        /** 全部取值 = 12。供单测卡住「有人新增了终态却忘了更新 UI 文案表」。 */
        public val ALL: List<TerminalReason> = entries

        public fun parseOrNull(value: String?): TerminalReason? =
            if (value == null) null else entries.firstOrNull { it.name == value }
    }
}

/**
 * 可恢复挂起原因（**不是终态**）。落库 status = `PAUSED`，恢复后回到 `RUNNING`。
 *
 * 与 [TerminalReason] 分开是刻意的：二者在 UI 上的呈现完全不同 ——
 * 「已暂停，点击继续」vs「已结束」。混在一个枚举里必然写出错的 UI 分支。
 */
public enum class SuspendReason(public val reasonKey: String) {
    /** `ThermalState >= SEVERE`：降温后可恢复。 */
    THERMAL("suspend_thermal"),

    /** 电量 < 15% 且未充电。 */
    BATTERY_LOW("suspend_battery_low"),

    /** HumanGate 挂起等待 UI 响应。status 是 `WAITING_HUMAN` 而不是 `PAUSED`。 */
    HUMAN_GATE("suspend_human_gate"),
    ;

    /** HumanGate 走独立 status，其余走 PAUSED。 */
    public val status: String
        get() = if (this == HUMAN_GATE) TaskStatus.WAITING_HUMAN else TaskStatus.PAUSED
}

// ─────────────────────────────────────────────────────────────────────────────
// 四、预算（只读事实）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 本机硬预算的**只读事实**。
 *
 * 刻意是扁平的一张事实表，而不是把 `:agent` 的 `LoopBudget` / `ContextBudget`
 * 整个搬过来：那两个类还带着降级阶梯、分段配额等 UI **永远不会调用**的实现，
 * 搬过来等于把「构造一张预算表」的能力塞进契约层，谁都能改 UI 侧的预算算法了。
 *
 * 字段取自 UI 实际在显示的东西（设置页 `BudgetFacts` + 任务页 `MetricsUi`），一个不多。
 *
 * **没有 setter**：它们由设备内存档位决定，UI 不能靠自己存一份来假装能调 ——
 * 那会让用户在设置里调了参数、任务却跑在另一套预算上，是最难排查的一类不一致。
 */
public data class LoopBudgetFacts(
    val memoryTier: String,
    val maxSteps: Int,
    val maxWallClockMs: Long,
    val perStepMs: Long,
    val decodeMs: Long,
    val actionMs: Long,
    val maxPrefillTokens: Long,
    val maxDecodeTokens: Long,
    /** OBSERVATION 每轮候选数的满配值。 */
    val currentTopK: Int,
    /** 上下文工作窗口总 token（`ContextBudget.workingWindow`）。 */
    val workingWindowTokens: Int,
    /** 为输出预留的 token（`ContextBudget.outputReserve`）。 */
    val outputReserveTokens: Int,
    /** 可用于 prompt 的 token = 工作窗口 − 输出预留。UI 用它算上下文占用百分比。 */
    val usablePromptTokens: Int,
)

/** 预算累计。**四个字段都必须与 `LoopCheckpoint` / `TaskBudgetPatch` 同口径**（累计终值而非增量）。 */
public data class UsageTotals(
    val stepsDone: Int = 0,
    val prefillTokensTotal: Long = 0L,
    val decodeTokensTotal: Long = 0L,
    val elapsedMsTotal: Long = 0L,
)

/** 预算只读查询。实现落在 `:agent`（内部仍用 `LoopBudget.forTier` 算）。 */
public interface LoopBudgetPort {

    public fun forTier(tier: MemoryTier): LoopBudgetFacts

    /** 剩余预算百分比 0..100。取四项里消耗最快的那一项 —— 不是取平均。 */
    public fun remainingPct(tier: MemoryTier, used: UsageTotals): Int
}

// ─────────────────────────────────────────────────────────────────────────────
// 五、风险分级
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 动作的风险分级。**判定必须只发生在护栏一侧** ——
 * 架构原话：「不要在 UI 层或 Agent 层判断风险，那是护栏的职责」。
 * 散落的判定一定会漏，而安全代码是唯一不能出错的那类代码。
 *
 * UI 用它给确认弹窗打 riskClass：目标是让它显示成不可误点的样式，而不是替用户做决定。
 *
 * 之所以**不**做成给现有 [PolicyPort] 加一个方法：加方法的代价是让 `:agent` 的
 * `PolicyEngine` 立刻编译失败；新增一个窄接口对该实现方是零打扰。
 */
public interface ActionRiskPort {
    public fun classify(action: StepAction): RiskClass
}
