package com.ake.ui.runtime

import com.ake.agent.context.ContextBudget
import com.ake.agent.loop.AgentLoop
import com.ake.agent.loop.LoopBudget
import com.ake.agent.loop.RunOptions
import com.ake.agent.loop.TaskEvent
import com.ake.agent.loop.TerminalReason
import com.ake.agent.loop.UsageTotals
import com.ake.core.common.Clock
import com.ake.core.common.SessionId
import com.ake.core.common.TaskId
import com.ake.core.common.Url
import com.ake.core.contract.StepAction
import com.ake.core.contract.TaskPhase
import com.ake.core.contract.TaskRecord
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.TaskStatus
import com.ake.core.contract.TaskSuspension
import com.ake.core.contract.ToolResult
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.ObservationStorePort
import com.ake.core.ports.TaskStorePort
import com.ake.ui.model.MetricsUi
import com.ake.ui.model.ObservationUi
import com.ake.ui.model.RepairUi
import com.ake.ui.model.RunState
import com.ake.ui.model.StepPhase
import com.ake.ui.model.StepUi
import com.ake.ui.model.TaskOutcomeUi
import com.ake.ui.model.TaskSessionUi
import com.ake.ui.model.ToolCallUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 任务会话的编排者 —— UI 与 `AgentLoop` 之间**唯一**的那道桥。
 *
 * ## 为什么它必须存在
 *
 * `AgentLoop.run()` 返回的是一条**冷 Flow**，而且「最后一条事件一定是 Terminal」。
 * 这条设计让「跑到结束」可以用一个 `collect` 表达，但它同时意味着：
 * 事件流本身是无状态的、一次性的，而屏幕需要一个**可随时重建的当前状态**
 * （旋转屏幕、退到后台再回来、切到设置页再切回来）。
 * 把 Flow 归约成一个 `StateFlow<TaskSessionUi>` 就是本类唯一的职责。
 *
 * ## ★ 流的终点有两个，UI 必须都接
 *
 * `AgentLoop.pause()` 曾经在 `Paused` 之后补发一条伪 `Terminal(USER_ABORT, null, "paused:xxx")`，
 * 让 UI 只能靠 `detail.startsWith("paused:")` 去丢弃它 —— 那套字符串补丁已随 `:agent` 的根治删除。
 * 现在挂起一律是「`Paused`（渲染用）→ `StatusChanged` → **`Suspended`（流终点）**」，
 * 与 `Terminal` **互斥且穷尽**（`AgentEvents.isEndOfStream`）。
 *
 * 所以这里在 `when` 里对两者**对称收尾**：都停计时器、都刷新可恢复列表，
 * 区别只在 `Suspended` 不改 `runState`（改状态是 `Paused` 分支的职责）。
 * 少了 `Suspended` 分支，暂停后计时器协程就会一直活着 —— 这是补上它的唯一理由。
 */
@Singleton
public class AgentSessionCoordinator @Inject constructor(
    private val loop: AgentLoop,
    private val tasks: TaskStorePort,
    private val checkpoints: CheckpointStorePort,
    private val observations: ObservationStorePort,
    private val health: DeviceHealthPort,
    private val gate: UiHumanGate,
    private val clock: Clock,
) {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()) + kotlinx.coroutines.Dispatchers.Default

    private val _state = MutableStateFlow(TaskSessionUi())
    public val state: StateFlow<TaskSessionUi> = _state.asStateFlow()

    /** 挂起请求的只读视图（由 `UiHumanGate` 持有，避免 UI 直接碰 `PolicyVerdict`）。 */
    public val pendingGate: StateFlow<UiHumanGate.PendingGate?> = gate.pending

    private val abortFlag = AtomicBoolean(false)
    private val abortSignal: AbortSignal = object : AbortSignal {
        override fun isAborted(): Boolean = abortFlag.get()
    }

    private var runJob: Job? = null
    private var clockJob: Job? = null
    private var taskJob: Job? = null

    /** 当前任务的规格，供「继续」时复用。 */
    private var currentSpec: TaskSpec? = null

    /** 最近一次观察的宿主域名 —— 确认弹窗要显示「这个动作会去哪个域名」。 */
    private var lastHost: String = ""

    private val tier get() = health.memoryTier()
    private val budget: LoopBudget get() = LoopBudget.forTier(tier)

    // ──────────────────────────────────────────── 启动 / 恢复 / 停止

    /**
     * 下达一个新任务。
     *
     * 模型就绪检查放在这里而不是 `AgentLoop` 里：2.6 GB 的下载是一个分钟级的、
     * 有独立进度 UI 的过程，把它藏进主循环会让用户在「已暂停 / 下载中 / 推理中」之间失去分辨能力。
     */
    public fun start(goal: String, startUrl: String?, maxSteps: Int?, allowHosts: Set<String> = emptySet()) {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return

        runJob?.cancel(CancellationException("new task started"))
        clockJob?.cancel()
        taskJob?.cancel()
        gate.cancelPending()
        gate.resetTaskGrants()
        abortFlag.set(false)

        val id = TaskId.new()
        val spec = TaskSpec(
            id = id,
            goal = trimmed,
            startUrl = startUrl?.trim()?.takeIf { it.isNotBlank() }?.let { Url(it) },
            // ★ 设置页的白名单在这里真正生效：`DomainPolicy.isInSessionScope` 会消费它，
            //   白名单内的域名不再触发「跨域名需确认」，等于把打扰次数交还给用户。
            allowHosts = allowHosts,
            maxSteps = (maxSteps ?: budget.maxSteps).coerceAtMost(budget.maxSteps),
            createdAtMillis = clock.nowMillis(),
        )
        currentSpec = spec

        _state.update {
            TaskSessionUi(
                runState = RunState.Preparing("正在准备模型与页面…"),
                taskId = id.value,
                goal = trimmed,
                status = TaskStatus.CREATED,
                phase = TaskPhase.ACTIVE,
                steps = emptyList(),
                metrics = freshMetrics(),
            )
        }

        scope.launch {
            runCatching {
                tasks.create(
                    TaskRecord(
                        id = id,
                        sessionId = SessionId.new().value,
                        createdAt = spec.createdAtMillis,
                        lastActivityAt = spec.createdAtMillis,
                        goalPreview = trimmed.take(120),
                        status = TaskStatus.CREATED,
                        modelId = DEFAULT_MODEL_ID,
                        contextWindow = ContextBudget.forTier(tier).workingWindow,
                        memoryTier = tier.name,
                    ),
                )
            }
            startClock()
            observeTaskRecord(id)
            loop.run(spec, RunOptions(abort = abortSignal, modelId = DEFAULT_MODEL_ID))
                .collect { event -> reduce(event) }
        }.also { runJob = it }
    }

    /**
     * ★ 从暂停态继续。
     *
     * 预算累计从 checkpoint 带回来（`LoopState.restore` 已实现），
     * 所以「继续」不等于「重跑」—— 这一点在 UI 文案里必须说清楚。
     */
    public fun resume(taskId: String) {
        val spec = currentSpec
        val id = TaskId(taskId)
        scope.launch {
            val cp = runCatching { checkpoints.load(id) }.getOrNull()
            if (spec == null) {
                // 冷启动恢复：没有内存里的 TaskSpec，用库里的记录重建一个最小规格
                val rec = runCatching { tasks.load(id) }.getOrNull() ?: return@launch
                val rebuilt = TaskSpec(
                    id = id,
                    goal = rec.goalPreview ?: "（已脱敏，原文不可读）",
                    maxSteps = budget.maxSteps,
                    createdAtMillis = rec.createdAt,
                )
                currentSpec = rebuilt
                gate.resetTaskGrants()
                abortFlag.set(false)
                _state.update {
                    TaskSessionUi(
                        runState = RunState.Preparing("正在从断点恢复…"),
                        taskId = id.value,
                        goal = rebuilt.goal,
                        status = TaskStatus.RUNNING,
                        phase = TaskPhase.ACTIVE,
                        metrics = freshMetrics(stepsDone = rec.stepsDone),
                    )
                }
                startClock()
                observeTaskRecord(id)
                loop.run(rebuilt, RunOptions(abort = abortSignal, resumeFrom = cp, modelId = DEFAULT_MODEL_ID))
                    .collect { event -> reduce(event) }
                return@launch
            }

            gate.resetTaskGrants()
            abortFlag.set(false)
            _state.update { it.copy(runState = RunState.Preparing("正在从断点恢复…"), status = TaskStatus.RUNNING) }
            startClock()
            observeTaskRecord(id)
            loop.run(spec, RunOptions(abort = abortSignal, resumeFrom = cp, modelId = DEFAULT_MODEL_ID))
                .collect { event -> reduce(event) }
        }.also { runJob = it }
    }

    /**
     * ★ 放弃一个暂停中的任务：它从「可恢复」变成真正的终止。
     *
     * 与 [abort] 的区别是语气与落点：这里是用户在暂停卡片上主动做了决定，
     * 所以直接写终态并清掉断点 —— 不清掉的话，下次冷启动会一直看到一个能「继续」的幽灵任务。
     */
    public fun abandon(taskId: String) {
        val id = TaskId(taskId)
        scope.launch {
            runCatching { checkpoints.clear(id) }
            runCatching {
                tasks.finish(id, TaskStatus.ABORTED, TerminalReason.USER_ABORT.reasonKey, null, null)
            }
            _state.update {
                it.copy(
                    runState = RunState.Terminated(TerminalReason.USER_ABORT, null, "用户放弃了这次暂停"),
                    status = TaskStatus.ABORTED,
                    phase = TaskPhase.TERMINAL,
                )
            }
            runJob?.cancel(CancellationException("abandoned"))
            gate.cancelPending()
            refreshResumable()
        }
    }

    /** 中止正在跑的任务。`AbortSignal` 刻意是同步查询，Loop 每步开头都会看它。 */
    public fun abort() {
        abortFlag.set(true)
        gate.cancelPending()
        _state.update { it.copy(runState = RunState.Preparing("正在停止…")) }
    }

    /** 冷启动后扫描「还能继续」的任务。 */
    public fun refreshResumable() {
        scope.launch {
            val ids = runCatching { checkpoints.findResumable() }.getOrDefault(emptyList())
            _state.update { it.copy(resumableTaskIds = ids.map { t -> t.value }) }
        }
    }

    /** 清空结果区，回到可执行新任务的状态（不动库里的数据）。 */
    public fun dismiss() {
        runJob?.cancel(CancellationException("dismissed"))
        clockJob?.cancel()
        taskJob?.cancel()
        gate.cancelPending()
        currentSpec = null
        _state.update { TaskSessionUi(runState = RunState.Idle, resumableTaskIds = it.resumableTaskIds) }
    }

    /** 取某一步 Agent 实际看到的 DOM 摘要（不是 UI 猜的，是 `PocketDomSerializer` 落盘的原文）。 */
    public suspend fun domPayloadOf(observationId: String): String? =
        runCatching { observations.loadPayload(observationId) }.getOrNull()

    // ──────────────────────────────────────────── 事件归约

    private suspend fun reduce(e: TaskEvent) {
        when (e) {
            is TaskEvent.Started -> {
                _state.update {
                    it.copy(runState = RunState.Running(e.resumedFromStep, StepPhase.PERCEIVING))
                }
            }

            is TaskEvent.StatusChanged -> {
                _state.update {
                    it.copy(status = e.status, phase = TaskStatus.phaseOf(e.status))
                }
            }

            is TaskEvent.Observed -> {
                lastHost = e.url?.let { Url(it).host }.orEmpty()
                val obs = ObservationUi(
                    seq = e.seq,
                    url = e.url,
                    host = lastHost,
                    pageDigest = e.pageDigest,
                    candidateCount = e.candidates,
                    observationId = e.observationId,
                )
                _state.update { s ->
                    s.copy(
                        observation = obs,
                        steps = s.steps.upsert(e.seq) { it.copy(observation = obs, phase = StepPhase.PLANNING) },
                        runState = if (s.runState is RunState.Suspended) s.runState
                        else RunState.Running(e.seq, StepPhase.PLANNING),
                    )
                }
            }

            is TaskEvent.Planned -> {
                // 工具调用：CallTool 是唯一会离开浏览器的动作，入参必须留痕
                val plannedAction: StepAction = e.action
                if (plannedAction is StepAction.CallTool) {
                    val call = plannedAction
                    _state.update { s ->
                        s.copy(
                            toolCalls = s.toolCalls + ToolCallUi(
                                seq = e.seq,
                                name = call.toolId.value,
                                origin = if (call.toolId.value.startsWith("mcp.")) "MCP" else "本地",
                                argsSummary = if (call.args.isEmpty()) "（无入参）"
                                else call.args.entries.joinToString(", ") { (k, v) -> "$k=$v" }.take(200),
                                resultSummary = null,
                                durationMs = null,
                            ),
                        )
                    }
                }
                _state.update { s ->
                    s.copy(
                        steps = s.steps.upsert(e.seq) {
                            it.copy(
                                phase = StepPhase.GUARDING,
                                action = e.action,
                                actionTitle = actionTitleOf(e.action),
                                actionDetail = e.action.compactArgs(),
                                promptTokens = e.promptTokens,
                                prefillTokens = e.prefillTokens,
                                decodeTokens = e.decodeTokens,
                            )
                        },
                        metrics = s.metrics.copy(promptTokensLastStep = e.promptTokens),
                        runState = RunState.Running(e.seq, StepPhase.GUARDING),
                    )
                }
            }

            is TaskEvent.Guarded -> {
                _state.update { s ->
                    s.copy(
                        steps = s.steps.upsert(e.seq) {
                            it.copy(guardDecision = e.decision, guardReasonKey = e.reasonKey, riskClass = e.riskClass)
                        },
                    )
                }
            }

            is TaskEvent.TokenDelta -> {
                _state.update { s ->
                    s.copy(steps = s.steps.upsert(e.seq) { it.appendModelDelta(e.delta) })
                }
            }

            is TaskEvent.ConfirmRequested -> {
                _state.update { s ->
                    s.copy(
                        steps = s.steps.upsert(e.seq) { it.copy(phase = StepPhase.EXECUTING) },
                        runState = RunState.Suspended(
                            TaskSuspension.of(
                                taskId = TaskId(e.taskId.value),
                                status = TaskStatus.WAITING_HUMAN,
                                reasonKey = e.reasonKey,
                                stepSeq = e.seq,
                                checkpointSaved = true,
                            ),
                        ),
                        status = TaskStatus.WAITING_HUMAN,
                        phase = TaskPhase.SUSPENDED,
                    )
                }
            }

            is TaskEvent.Executed -> {
                _state.update { s ->
                    s.copy(toolCalls = s.toolCalls.closeLast(e.seq, resultTextOf(e.result), e.durationMs))
                }
                _state.update { s ->
                    s.copy(
                        steps = s.steps.upsert(e.seq) {
                            it.copy(
                                result = e.result,
                                resultText = resultTextOf(e.result),
                                durationMs = e.durationMs,
                                finished = true,
                                denied = e.result is ToolResult.Error,
                            )
                        },
                    )
                }
            }

            is TaskEvent.RepairIssued -> {
                _state.update { s ->
                    s.copy(steps = s.steps.upsert(e.seq) { it.copy(repair = RepairUi(e.failureCode, e.attempt)) })
                }
            }

            is TaskEvent.PerceptionFault -> {
                _state.update { s ->
                    s.copy(steps = s.steps.upsert(e.seq) { it.copy(perceptionFaultCode = e.code) })
                }
            }

            is TaskEvent.GuardTriggered -> {
                // 终止保护每触发一次都要留证据（§3.5），UI 上以一步内的警示行呈现
                _state.update { s ->
                    s.copy(steps = s.steps.upsert(e.seq) { it.copy(perceptionFaultCode = "${e.kind}: ${e.detail}") })
                }
            }

            is TaskEvent.CheckpointSaved -> Unit

            is TaskEvent.Paused -> {
                // 渲染用的挂起事件：全量展示信息由事件自带，UI 不再反推。
                // ★ checkpointSaved 直接用事件里的值 —— 硬编码 true 会让「点了继续却恢复不了」。
                _state.update { s ->
                    s.copy(
                        runState = RunState.Suspended(
                            TaskSuspension.of(
                                taskId = TaskId(e.taskId.value),
                                status = e.reason.status,
                                reasonKey = e.reason.reasonKey,
                                stepSeq = e.stepSeq,
                                checkpointSaved = e.checkpointSaved,
                            ),
                        ),
                        status = e.reason.status,
                        phase = TaskPhase.SUSPENDED,
                    )
                }
                refreshResumable()
            }

            is TaskEvent.Suspended -> {
                // ★ 流终点之一（可恢复挂起）。与 Terminal 分支对称：停计时器 + 刷新可恢复列表，
                //   但不改写 runState —— 那是 Paused 分支已经写完的事。
                //   少了这一支，clockJob 在暂停后没有任何人取消，计时器协程会一直空转。
                clockJob?.cancel()
                refreshResumable()
            }

            is TaskEvent.Terminal -> {
                _state.update { s ->
                    s.copy(
                        runState = RunState.Terminated(
                            reason = e.reason,
                            outcome = e.outcome?.let {
                                TaskOutcomeUi(it.answer, it.evidenceRefs, it.exported)
                            },
                            detail = e.detail,
                        ),
                        status = e.reason.status,
                        phase = TaskPhase.TERMINAL,
                    )
                }
                clockJob?.cancel()
                refreshResumable()
            }
        }
    }

    // ──────────────────────────────────────────── 指标

    private fun startClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                delay(500L)
                _state.update { s ->
                    val running = s.runState is RunState.Running || s.runState is RunState.Preparing
                    if (!running) return@update s
                    val elapsed = s.metrics.elapsedMs + TICK_MS
                    s.copy(metrics = s.metrics.copy(elapsedMs = elapsed).withBudget(budget))
                }
            }
        }
    }

    /** 库里的预算累计是权威来源（`TaskStorePort.advance` 每步推进），UI 只做展示。 */
    private fun observeTaskRecord(id: TaskId) {
        taskJob?.cancel()
        taskJob = scope.launch {
            runCatching {
                tasks.observe(id).collect { rec ->
                    if (rec == null) return@collect
                    _state.update { s ->
                        s.copy(
                            metrics = MetricsUi(
                                stepsDone = rec.stepsDone,
                                maxSteps = budget.maxSteps,
                                prefillTokens = rec.prefillTokensTotal,
                                decodeTokens = rec.decodeTokensTotal,
                                elapsedMs = rec.elapsedMsTotal.coerceAtLeast(s.metrics.elapsedMs),
                                maxWallClockMs = budget.maxWallClockMs,
                                budgetRemainingPct = budget.remainingPct(
                                    UsageTotals(
                                        stepsDone = rec.stepsDone,
                                        prefillTokensTotal = rec.prefillTokensTotal,
                                        decodeTokensTotal = rec.decodeTokensTotal,
                                        elapsedMsTotal = rec.elapsedMsTotal,
                                    ),
                                ),
                                contextUsedPct = contextUsedPct(s.metrics.promptTokensLastStep),
                                contextWindowTokens = ContextBudget.forTier(tier).workingWindow,
                                promptTokensLastStep = s.metrics.promptTokensLastStep,
                                memoryTier = tier.name,
                            ),
                            status = rec.status,
                            phase = TaskStatus.phaseOf(rec.status),
                        )
                    }
                }
            }
        }
    }

    private fun contextUsedPct(promptTokens: Int): Int {
        val window = ContextBudget.forTier(tier).usablePromptTokens
        if (window <= 0) return 0
        return ((promptTokens.toDouble() / window.toDouble()) * 100).toInt().coerceIn(0, 100)
    }

    private fun freshMetrics(stepsDone: Int = 0): MetricsUi = MetricsUi(
        stepsDone = stepsDone,
        maxSteps = budget.maxSteps,
        maxWallClockMs = budget.maxWallClockMs,
        contextWindowTokens = ContextBudget.forTier(tier).workingWindow,
        memoryTier = tier.name,
    )

    private fun MetricsUi.withBudget(b: LoopBudget): MetricsUi = copy(
        maxSteps = b.maxSteps,
        maxWallClockMs = b.maxWallClockMs,
        budgetRemainingPct = b.remainingPct(
            UsageTotals(
                stepsDone = stepsDone,
                prefillTokensTotal = prefillTokens,
                decodeTokensTotal = decodeTokens,
                elapsedMsTotal = elapsedMs,
            ),
        ),
    )

    // ──────────────────────────────────────────── 展示辅助

    public fun currentHost(): String = lastHost

    public companion object {
        /**
         * 与 `RunOptions.modelId` 的默认值、`ModelCatalog.GEMMA4_E2B_ID` 同值。
         * 这里不 import `:llm`，是为了不让 UI 依赖推理引擎模块。
         */
        public const val DEFAULT_MODEL_ID: String = "gemma-4-E2B-it"

        private const val TICK_MS: Long = 500L
    }
}

/** 按 seq 就地更新一步；没有就追加。步骤流天然按 seq 单调递增。 */
/** 回填最近一条同序号工具调用的耗时与结果。找不到就原样返回（内置浏览器动作不算工具调用）。 */
private fun List<ToolCallUi>.closeLast(seq: Int, result: String, durationMs: Long): List<ToolCallUi> {
    val idx = indexOfLast { it.seq == seq && it.resultSummary == null }
    if (idx < 0) return this
    val out = toMutableList()
    out[idx] = out[idx].copy(resultSummary = result, durationMs = durationMs)
    return out
}

private inline fun List<StepUi>.upsert(seq: Int, block: (StepUi) -> StepUi): List<StepUi> {
    val idx = indexOfFirst { it.seq == seq }
    val out = toMutableList()
    if (idx >= 0) out[idx] = block(out[idx]) else out.add(block(StepUi(seq = seq, phase = StepPhase.PERCEIVING)))
    return out
}

private fun actionTitleOf(a: StepAction): String = when (a) {
    is StepAction.Click -> "点击元素 ${a.id}"
    is StepAction.Type -> "输入文本到 ${a.id}${if (a.submit) " 并回车提交" else ""}"
    is StepAction.Select -> "在 ${a.id} 中选择「${a.value}」"
    is StepAction.Scroll -> "滚动页面 ${a.dir} ${a.px}px"
    is StepAction.Navigate -> "打开 ${a.url}"
    is StepAction.Extract -> "抽取数据（最多 ${a.limit} 条）"
    is StepAction.CallTool -> "调用工具 ${a.toolId.value}"
    is StepAction.AskHuman -> "向你提问：${a.question}"
    is StepAction.Finish -> "报告完成"
}

private fun resultTextOf(r: ToolResult): String = when (r) {
    is ToolResult.DomChanged -> "页面已变化，新增 ${r.addedCandidates} 个可交互元素"
    is ToolResult.UrlMoved -> "页面已跳转到 ${r.host}"
    is ToolResult.RecordsGot -> "取到 ${r.count} 条记录"
    is ToolResult.Noop -> "执行成功，但页面没有变化"
    is ToolResult.Text -> r.value
    is ToolResult.Error -> "失败：${r.hintBrief()}"
}
