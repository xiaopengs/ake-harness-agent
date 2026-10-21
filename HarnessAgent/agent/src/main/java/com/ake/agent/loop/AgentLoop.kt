package com.ake.agent.loop

import com.ake.agent.context.ActionSupport
import com.ake.agent.context.PocketDomSerializer
import com.ake.agent.guard.SensitiveActionPolicy
import com.ake.agent.planner.StepActionParser
import com.ake.agent.planner.StepPromptBuilder
import com.ake.agent.planner.StepPromptInput
import com.ake.agent.planner.TaskScriptFlavor
import com.ake.agent.planner.toToolCall
import com.ake.agent.tools.CompositeToolRegistry
import com.ake.agent.tools.ResultCollector
import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.StepId
import com.ake.core.common.Url
import com.ake.core.contract.BrowserAction
import com.ake.core.contract.ErrorCode
import com.ake.core.contract.ExecutionResult
import com.ake.core.contract.LoopCheckpoint
import com.ake.core.contract.ObservationSummary
import com.ake.core.contract.PageChangeKind
import com.ake.core.contract.PocketDom
import com.ake.core.contract.PolicyDecision
import com.ake.core.contract.StepAction
import com.ake.core.contract.StepRecord
import com.ake.core.contract.StepStatus
import com.ake.core.contract.TaskBudgetPatch
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.TaskStatus
import com.ake.core.contract.TaskSuspension
import com.ake.core.contract.ToolCall
import com.ake.core.contract.ToolResult
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ThinkingBudget
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.ActionContext
import com.ake.core.ports.BatteryState
import com.ake.core.ports.BrowserExecutorPort
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.DomPerceptionPort
import com.ake.core.ports.HumanGate
import com.ake.core.ports.NoAbortSignal
import com.ake.core.ports.ObserveOptions
import com.ake.core.ports.ObservationStorePort
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.Resolved
import com.ake.core.ports.TaskStorePort
import com.ake.core.ports.ThermalState
import com.ake.core.ports.TraceStorePort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/** 一次运行的入参。刻意做成不可变纯数据，便于黄金轨迹回放。 */
data class RunOptions(
    val abort: AbortSignal = NoAbortSignal,
    val resumeFrom: LoopCheckpoint? = null,
    val modelId: String = "gemma-4-E2B-it",
    /** 每步结束是否落一次断点。落盘是 try-send，成本可忽略，MVP 默认每步都落。 */
    val checkpointEveryStep: Boolean = true,
)

/**
 * # 单步 ReAct 主循环（架构 §3.2）
 *
 * 七环：**感知 → 压缩 → 规划 → 工具调用 → 执行 → 观察 → 验证**。
 *
 * ## 全流程只有第 3 环调用一次模型
 *
 * 其余全是确定性 Kotlin。这不是保守，是可调试性的前提 ——
 * 一个 10 步失败的任务，你能精确指出是第 4 步的 DOM 裁剪丢了元素，还是第 6 步的护栏误拒。
 * **端侧 Agent 项目死于无法定位失败原因的比例，远高于死于单点能力不足。**
 *
 * ## Session 全程复用（§4.3）
 *
 * 每步重建 Conversation 会把 KV cache 优势丢光。session 在 `run()` 开头开一次；
 * 断点恢复时重建并从 `lastUrl` 继续（§3.5 最低实现）。
 *
 * ## 事件流
 *
 * [run] 返回冷 Flow，**最后一条事件一定是终结语**：正常运行结束是 [TaskEvent.Terminal]，
 * 被挂起（暂停 / 等人工确认）是 [TaskEvent.Suspended]，二者互斥且穷尽（`AgentEvents.isEndOfStream`）。
 * 这让 UI 用一个 `collect` 就能表达「跑到结束」，不需要再配对一个 Result 返回值
 * （配对返回值是回调风格的典型泄漏）。
 *
 * ## 中止必须随时可用
 *
 * [AbortSignal.isAborted] 在每步开头、每次 `withTimeoutOrNull` 之后都会被查。
 * 它刻意不是挂起函数 —— 用户按停止时，不该出现「先等一圈再停」的中间态。
 */
class AgentLoop(
    private val model: ModelPort,
    private val perception: DomPerceptionPort,
    private val browser: BrowserExecutorPort,
    private val tools: CompositeToolRegistry,
    private val guard: PolicyPort,
    private val verifier: Verifier,
    private val promptBuilder: StepPromptBuilder,
    private val parser: StepActionParser,
    private val trace: TraceStorePort,
    private val observationStore: ObservationStorePort,
    private val checkpointStore: CheckpointStorePort,
    private val taskStore: TaskStorePort,
    private val health: DeviceHealthPort,
    private val humanGate: HumanGate,
    private val clock: Clock,
    private val collector: ResultCollector = ResultCollector(),
    private val domSerializer: PocketDomSerializer = PocketDomSerializer(),
    /**
     * 解码增量的投递通道。默认 [TokenStream.SINGLE_SHOT]（整段交付）；
     * 引擎拿到流式 API 后由 `:app` 换实现，主循环与本事件契约都不用动。
     */
    private val tokenStream: TokenStream = TokenStream.SINGLE_SHOT,
) {

    fun run(task: TaskSpec, opts: RunOptions = RunOptions()): Flow<TaskEvent> = flow {
        val budget = LoopBudget.forTier(health.memoryTier())

        // ── 断点恢复：预算累计原样带回（宋砚秋：丢了等于每次暂停白送一张预算卡）
        val cp = opts.resumeFrom
        val loop = if (cp != null) LoopState.restore(task, budget, clock, cp) else LoopState(task, budget, clock)
        if (cp != null) {
            // §3.5 最低实现：恢复时保留 trace 作为历史摘要
            val history = runCatching { trace.load(task.id) }.getOrDefault(emptyList())
            loop.seedTrace(history)
        }

        emit(TaskEvent.Started(task.id, loop.stepSeq, task.goal.take(64)))
        emit(TaskEvent.StatusChanged(task.id, TaskStatus.RUNNING))

        // 设备健康：两个 Flow 在后台持续收，主循环只读最新值。
        // 刻意不每步 collect —— 那会把「读一次热状态」变成一次流订阅。
        var thermal = ThermalState.UNKNOWN
        var battery = BatteryState.unknown()

        var session: ModelSession? = null
        try {
            supervisorScope {
                health.thermal().onEach { thermal = it }.launchIn(this)
                health.battery().onEach { battery = it }.launchIn(this)

                val activeSession: ModelSession = when (val opened = model.openSession(buildSessionSpec(opts, budget, task))) {
                    is Outcome.Ok -> opened.value
                    is Outcome.Err -> {
                        emit(
                            TaskEvent.Terminal(
                                task.id, TerminalReason.BUDGET_EXHAUSTED, null,
                                "openSession failed: ${opened.error.code}",
                            ),
                        )
                        return@supervisorScope
                    }
                }
                session = activeSession

                // ════════════════════════════ 主循环 ════════════════════════════
                while (true) {
                    // ── 0. 先查终止保护：先查熔断再动，别在无药可救时还付一次推理的钱
                    when (val term = verifier.checkTermination(loop, opts.abort.isAborted(), thermal, battery)) {
                        is Termination.Terminal -> {
                            finish(this@flow, loop, term.reason, null)
                            return@supervisorScope
                        }

                        is Termination.Suspend -> {
                            pause(this@flow, loop, term.reason)
                            return@supervisorScope
                        }

                        null -> Unit
                    }

                    val startedAt = clock.nowMillis()

                    // ── 1. 感知
                    val perceptionOutcome = withTimeoutOrNull(budget.perceiveMs) {
                        perception.observe(ObserveOptions(candidateTopK = budget.currentTopK))
                    }
                    if (opts.abort.isAborted()) continue
                    if (perceptionOutcome !is Outcome.Ok) {
                        emit(TaskEvent.PerceptionFault(task.id, loop.stepSeq, "PERCEPTION_TIMEOUT_OR_ERROR"))
                        loop.noteStall()
                        drainBudget(loop)
                        delay(RETRY_BACKOFF_MS)
                        continue
                    }
                    val page: PocketDom = perceptionOutcome.value
                    val stepId: StepId = loop.nextStepId()
                    loop.observePage(page.digest.value, page.url, null)
                    collector.setHost(page.host)
                    emit(
                        TaskEvent.Observed(
                            taskId = task.id,
                            seq = loop.stepSeq,
                            url = page.url,
                            // ★ 与落库的 ObservationSummary.titleRedacted 同口径（同一个函数）。
                            // 这一列会流到通知栏与历史列表，绝不能拿 page.title 原样发。
                            titleRedacted = ObservationProjector.redactTitle(page.title),
                            pageDigest = page.digest.value,
                            candidates = page.candidates.size,
                            observationId = stepId.value,
                        ),
                    )

                    // ── 2. 压缩 + 组装上下文（确定性，不调模型）
                    val flavor = TaskScriptFlavor.detect(task.goal, page)
                    val sections = promptBuilder.build(
                        StepPromptInput(
                            task = task,
                            budget = budget.context,
                            flavor = flavor,
                            dom = page,
                            trace = loop.traceWindow(),
                            recentRaw = loop.recentRaw(),
                            scratchpad = loop.scratchpad,
                            toolReturn = loop.lastToolReturn,
                            enabledTools = tools.enabledTools(),
                            stepIndex = loop.stepSeq,
                            remainingBudgetPct = loop.remainingBudgetPct(),
                            remapWarnings = loop.consumeRemapWarnings(page.candidates),
                            repairHint = loop.peekRepairHint(),
                            pendingDenials = loop.pendingDenials(),
                        ),
                    )
                    val userTurn = sections.render()

                    // ── 3. 规划：全流程唯一一次模型调用
                    val genOutcome = withTimeoutOrNull(budget.decodeMs) {
                        activeSession.generate(
                            GenerationRequest(
                                stepId = stepId,
                                userTurn = userTurn,
                                outputShape = OutputShape.AgentStep,
                                maxOutputTokens = budget.decodeTokens,
                                thinking = ThinkingBudget.Off,
                                deadlineMs = budget.decodeMs,
                            ),
                        )
                    }
                    if (opts.abort.isAborted()) continue
                    if (genOutcome !is Outcome.Ok) {
                        emit(TaskEvent.GuardTriggered(task.id, loop.stepSeq, "INFERENCE_FAULT", "generate failed or timed out"))
                        loop.noteStall()
                        drainBudget(loop)
                        delay(RETRY_BACKOFF_MS)
                        continue
                    }
                    val generation: GenerationResult = genOutcome.value

                    // ── 4. 解析（约束解码保证语法，但仍需在语义上落地）
                    val action: StepAction = when (val parsed = parser.parse(generation, page, loop.consecutiveParseFailures)) {
                        is StepActionParser.ParseResult.Ok -> {
                            loop.clearParseFailures()
                            parsed.action
                        }

                        is StepActionParser.ParseResult.Fail -> {
                            loop.recordParseFailure()
                            loop.setRepairHint(parsed.hint.toModel)
                            emit(TaskEvent.RepairIssued(task.id, loop.stepSeq, parsed.hint.failure.code, loop.consecutiveParseFailures))
                            drainBudget(loop)
                            // 不 commitStep：这一步没有动作发生，不该消耗一步预算
                            continue
                        }
                    }
                    emit(
                        TaskEvent.Planned(
                            taskId = task.id,
                            seq = loop.stepSeq,
                            action = action,
                            promptTokens = sections.totalTokens(),
                            prefillTokens = generation.stats.prefillTokens,
                            decodeTokens = generation.stats.decodeTokens,
                        ),
                    )

                    // ── 5. 安全护栏 —— 没有任何路径绕过这一步
                    val verdictOutcome = guard.authorize(
                        action,
                        ActionContext(
                            currentUrl = Url(page.url),
                            task = task,
                            currentDom = page,
                            stepIndex = loop.stepSeq,
                        ),
                    )
                    if (verdictOutcome !is Outcome.Ok) {
                        emit(TaskEvent.GuardTriggered(task.id, loop.stepSeq, "GUARD_FAULT", "authorize failed"))
                        loop.noteStall()
                        continue
                    }
                    emit(
                        TaskEvent.Guarded(
                            taskId = task.id,
                            seq = loop.stepSeq,
                            decision = decisionOf(verdictOutcome.value),
                            reasonKey = reasonKeyOf(verdictOutcome.value),
                            riskClass = SensitiveActionPolicy.classify(action).name,
                        ),
                    )

                    var effective: StepAction = action
                    when (val v = verdictOutcome.value) {
                        is PolicyVerdict.Denied -> {
                            loop.noteDenial(v.reasonKey)
                            commitDenied(loop, action, v.reasonKey, startedAt)
                            continue
                        }

                        is PolicyVerdict.RequiresConfirm -> {
                            emit(TaskEvent.ConfirmRequested(task.id, loop.stepSeq, action, v.reasonKey, v.args))
                            when (val resolved = humanGate.resolve(v, action)) {
                                Resolved.Proceed -> Unit
                                is Resolved.Amend -> effective = resolved.amended
                                is Resolved.Reject -> {
                                    loop.noteDenial(resolved.reason)
                                    commitDenied(loop, action, "err.user_rejected", startedAt)
                                    continue
                                }

                                Resolved.Abort -> {
                                    finish(this@flow, loop, TerminalReason.USER_ABORT, null)
                                    return@supervisorScope
                                }
                            }
                        }

                        PolicyVerdict.Allow -> Unit
                    }

                    // ── 6. 执行
                    val result: ToolResult = executeStep(effective, page, budget)
                    val durationMs = clock.nowMillis() - startedAt
                    emit(TaskEvent.Executed(task.id, loop.stepSeq, result, durationMs))

                    // ── 6b. 落 step + 预算推进
                    val rec = commitStep(loop, effective, result, generation, durationMs, page.digest.value)
                    loop.lastToolReturn = result.hintBrief()

                    // ── 6c. 错误恢复：失败分类 → 话术 → 是否换路
                    if (!result.isOk) applyRecovery(loop, effective, result, page)

                    // ── 6d. 观察落盘（非挂起 try-send，绝不阻塞主循环）
                    persistObservation(page, task, stepId, rec.index)

                    // ── 7. 验证
                    val judge = verifier.judge(task, loop, result, page, effective)

                    // 模型通过内置工具上报了结果 ⇒ 等同于 finish（唯一一处实现这条规则）
                    val reported = collector.takeAnswer()
                    if (judge is Judge.Continue && reported != null) {
                        val outcome = buildOutcome(loop, TerminalReason.COMPLETED, reported.first, reported.second)
                        finish(this@flow, loop, TerminalReason.COMPLETED, outcome)
                        return@supervisorScope
                    }

                    when (judge) {
                        is Judge.Continue -> {
                            judge.hint?.let { loop.appendScratchpad(it) }
                            if (opts.checkpointEveryStep) checkpoint(this@flow, loop)
                        }

                        is Judge.Done -> {
                            val outcome = buildOutcome(loop, TerminalReason.COMPLETED, judge.answer, judge.evidenceRefs)
                            finish(this@flow, loop, TerminalReason.COMPLETED, outcome)
                            return@supervisorScope
                        }

                        is Judge.Impossible -> {
                            finish(this@flow, loop, judge.reason, null, judge.detail)
                            return@supervisorScope
                        }
                    }
                }
            }
        } finally {
            runCatching { session?.close() }
        }
    }

    // ──────────────────────────────────────────── 执行分发

    private suspend fun executeStep(action: StepAction, page: PocketDom, budget: LoopBudget): ToolResult {
        when (action) {
            is StepAction.Finish -> return ToolResult.Noop          // 由第 7 环处理
            is StepAction.AskHuman -> {
                // 不经注册表：它是「转人工」信号，不是一个工具
                return ToolResult.Text("已向用户提问：${action.question.take(80)}")
            }

            else -> Unit
        }

        val call = action.toToolCall(page)

        // §3.5 定位伪失败：`toToolCall` 返回 null 说明模型引用的编号不在本轮候选里。
        // 这不是「执行失败」，是「引用失效」—— 分开处理才能让模型收到正确的话术。
        if (call == null) {
            return ToolResult.Error(ErrorCode.NOT_FOUND, "id not in this round's candidates")
        }

        val outcome = withTimeoutOrNull(budget.actionMs) {
            when (call) {
                is ToolCall.Browser -> dispatchBrowser(call.action)
                is ToolCall.External -> tools.invoke(call)
            }
        }
        return when (outcome) {
            null -> ToolResult.Error(ErrorCode.TIMEOUT, "execution exceeded ${budget.actionMs}ms")
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> {
                val code = when (outcome.error.code) {
                    AppError.CODE_TIMEOUT -> ErrorCode.TIMEOUT
                    AppError.CODE_INTERRUPTED -> ErrorCode.INTERRUPTED
                    AppError.CODE_UNSUPPORTED -> ErrorCode.UNSUPPORTED
                    else -> ErrorCode.UNKNOWN
                }
                ToolResult.Error(code, outcome.error.code)
            }
        }
    }

    private suspend fun dispatchBrowser(action: BrowserAction): Outcome<ToolResult> {
        // 页面必须先 settled：在未稳定的 DOM 上执行动作是「元素找不到」类故障的最大来源
        if (browser.waitForSettled(SETTLE_TIMEOUT_MS) !is Outcome.Ok) {
            return Outcome.Ok(ToolResult.Error(ErrorCode.BLOCKED, "page not settled"))
        }
        return when (val r = browser.execute(action)) {
            is Outcome.Ok -> Outcome.Ok(toToolResult(r.value))
            is Outcome.Err -> Outcome.Ok(
                ToolResult.Error(
                    when (r.error.code) {
                        AppError.CODE_TIMEOUT -> ErrorCode.TIMEOUT
                        AppError.CODE_INTERRUPTED -> ErrorCode.INTERRUPTED
                        else -> ErrorCode.UNKNOWN
                    },
                    r.error.code,
                ),
            )
        }
    }

    private fun toToolResult(e: ExecutionResult): ToolResult = when (e.change) {
        PageChangeKind.DOM_CHANGED -> ToolResult.DomChanged(e.addedCandidates)
        PageChangeKind.URL_MOVED -> ToolResult.UrlMoved(e.url?.host.orEmpty())
        PageChangeKind.NO_CHANGE -> ToolResult.Noop
    }

    // ──────────────────────────────────────────── 错误恢复

    private fun applyRecovery(loop: LoopState, action: StepAction, result: ToolResult, page: PocketDom) {
        val kind = FailureKind.of(result)
        loop.tallyFailure(kind)

        val min = page.candidates.minOfOrNull { it.id }
        val max = page.candidates.maxOfOrNull { it.id }
        val ids = if (min != null && max != null) min..max else null
        val target = targetKeyOf(action)

        // §3.5 定位伪失败：同一元素失败 2 次 → 强制降级到 extract，不再点它
        if ((kind == FailureKind.STALE || kind == FailureKind.NOT_FOUND) && target.isNotBlank()) {
            loop.recordLocateFailure(target)
            if (loop.locateFailureCount(target) >= LoopState.LOCATE_FAIL_LIMIT && !loop.isDegraded(target)) {
                loop.markDegraded(target)
                loop.setRepairHint(RecoveryAdvice.degradeHint(action))
                return
            }
        }

        loop.setRepairHint(
            RecoveryAdvice.toModel(
                kind = kind,
                detail = (result as? ToolResult.Error)?.message,
                attempt = loop.attemptsOf(kind),
                validIds = ids,
                supported = supportedOf(page, target),
            ),
        )
    }

    /** 目标的稳定标识。用 `kind#id` 而不是 id —— 定位失败计数要跨轮稳定（§3.5）。 */
    private fun targetKeyOf(action: StepAction): String = when (action) {
        is StepAction.Click -> "click#${action.id}"
        is StepAction.Type -> "type#${action.id}"
        is StepAction.Select -> "select#${action.id}"
        else -> ""
    }

    private fun supportedOf(page: PocketDom, key: String): List<String> {
        val id = key.substringAfter('#').toIntOrNull() ?: return emptyList()
        val el = page.candidates.firstOrNull { it.id == id } ?: return emptyList()
        return ActionSupport.of(el).split(',').map { it.trim() }
            .filter { it.isNotBlank() && it != "(read-only)" }
    }

    // ──────────────────────────────────────────── 落盘

    private fun persistObservation(page: PocketDom, task: TaskSpec, stepId: StepId, seq: Int) {
        val payload = runCatching { domSerializer.render(page, page.candidates.size) }.getOrDefault("")
        val summary: ObservationSummary = ObservationProjector.project(
            dom = page,
            taskId = task.id,
            stepId = stepId,
            seq = seq,
            clock = clock,
            payload = payload.ifBlank { null },
            redactionCount = 0,
            redactionTypes = emptyList(),
        )
        val ok = runCatching { observationStore.append(summary, payload.ifBlank { null }) }.getOrDefault(false)
        if (!ok) AkeLog.w(TAG, "observation append dropped at step $seq")
    }

    private suspend fun commitStep(
        loop: LoopState,
        action: StepAction,
        result: ToolResult,
        gen: GenerationResult,
        durationMs: Long,
        digest: String,
    ): StepRecord {
        val status = when {
            !result.isOk -> StepStatus.ERROR
            loop.repairAttempts > 0 -> StepStatus.REPAIRED
            else -> StepStatus.OK
        }
        val rec = loop.commitStep(
            action = action,
            result = result,
            prefillTokens = gen.stats.prefillTokens,
            decodeTokens = gen.stats.decodeTokens,
            kvCacheAfter = null,
            durationMs = durationMs,
            repairAttempts = loop.repairAttempts,
            errorCode = (result as? ToolResult.Error)?.code?.shortName,
            status = status,
            modelRawRedacted = gen.rawText.take(MAX_RAW),
            pageDigest = digest,
        )
        runCatching { trace.append(rec) }
        drainBudget(loop)
        return rec
    }

    private suspend fun commitDenied(loop: LoopState, action: StepAction, reasonKey: String, startedAt: Long) {
        val rec = loop.commitStep(
            action = action,
            result = ToolResult.Error(ErrorCode.DENIED, reasonKey),
            prefillTokens = 0,
            decodeTokens = 0,
            kvCacheAfter = null,
            durationMs = clock.nowMillis() - startedAt,
            repairAttempts = 0,
            errorCode = ErrorCode.DENIED.shortName,
            status = StepStatus.DENIED,
            modelRawRedacted = null,
            pageDigest = null,
        )
        runCatching { trace.append(rec) }
        drainBudget(loop)
    }

    /**
     * 预算累计推进。**传的是累计终值而不是增量**（`TaskBudgetPatch` 的既定语义）。
     *
     * 失败只记 WARN：预算计错会让任务「用不完预算」，但让主循环抛异常会直接弄死一个
     * 本来能跑完的任务 —— 这两者的代价不对等。
     */
    private suspend fun drainBudget(loop: LoopState) {
        val patch = TaskBudgetPatch(
            stepsDone = loop.usage.stepsDone,
            prefillTokensTotal = loop.usage.prefillTokensTotal,
            decodeTokensTotal = loop.usage.decodeTokensTotal,
            elapsedMsTotal = loop.usage.elapsedMsTotal,
            lastActivityAt = clock.nowMillis(),
        )
        runCatching { taskStore.advance(loop.task.id, patch) }
            .onFailure { AkeLog.w(TAG, "budget advance failed: ${it.message}") }
    }

    // ──────────────────────────────────────────── 终态 / 挂起 / 断点

    private suspend fun checkpoint(out: FlowCollector<TaskEvent>, loop: LoopState) {
        val cp = loop.snapshot(TaskStatus.RUNNING, humanGatePending = false, humanGateJson = null)
        runCatching { checkpointStore.save(cp) }
            .onFailure { AkeLog.w(TAG, "checkpoint save failed: ${it.message}") }
        out.emit(TaskEvent.CheckpointSaved(loop.task.id, loop.stepSeq))
    }

    /**
     * 挂起（**不是终止**）。调用后主循环立即 `return`，所以本函数是这条 Flow 的收尾。
     *
     * ## ★ 流的终点是 [TaskEvent.Suspended]，不再补发伪 Terminal
     *
     * 曾经这里在 `Paused` 之后再发一条 `Terminal(USER_ABORT, null, "paused:xxx")` —— 已删除。
     * 它让「暂停」在库里是 `ABORTED`、在 UI 上要靠字符串前缀 `detail.startsWith("paused:")`
     * 才能与真正的终止区分开。现在挂起就是挂起：[TaskEvent.Suspended] 与 [TaskEvent.Terminal]
     * 互斥且穷尽了流的终点（见 `AgentEvents.isEndOfStream`），UI 在 `when` 里就能穷尽。
     *
     * ## 落盘口径
     *
     * `terminal_reason` 传 `null`（`TaskSuspension` 的契约：暂停没有终止原因），
     * 且 `:data` 侧对挂起态不写 `finished_at` —— 否则「历史任务」与清理作业
     * （`pickForDigest` 以 `finished_at IS NOT NULL` 为入口）会把还活着的任务捞走。
     */
    private suspend fun pause(out: FlowCollector<TaskEvent>, loop: LoopState, reason: SuspendReason) {
        val cp = loop.snapshot(reason.status, humanGatePending = reason == SuspendReason.HUMAN_GATE, humanGateJson = null)
        // ★ 不许硬编码 true：「显示了继续按钮但点了恢复不了」比「直接说不能继续」糟得多
        val checkpointSaved = runCatching { checkpointStore.save(cp) }
            .onFailure { AkeLog.w(TAG, "checkpoint save failed on pause: ${it.message}") }
            .isSuccess
        runCatching { taskStore.finish(loop.task.id, reason.status, null, null, null) }
            .onFailure { AkeLog.w(TAG, "task suspend persist failed: ${it.message}") }

        val resumable = checkpointSaved && reason != SuspendReason.HUMAN_GATE
        val atMillis = clock.nowMillis()
        AkeLog.w(
            TAG,
            "task suspended: ${reason.reasonKey} @ step ${loop.stepSeq} " +
                "checkpointSaved=$checkpointSaved resumable=$resumable",
        )

        out.emit(
            TaskEvent.Paused(
                taskId = loop.task.id,
                reason = reason,
                status = reason.status,
                stepSeq = loop.stepSeq,
                checkpointSaved = checkpointSaved,
                resumable = resumable,
                // 文案归 UI（`SuspendReason.humanAdvice()`），引擎只给 key —— 这里刻意留空
                resumeHint = null,
                atMillis = atMillis,
            ),
        )
        out.emit(TaskEvent.StatusChanged(loop.task.id, reason.status))
        // ★ 流终点。TaskSuspension 的构造器 require 了挂起态，终态塞不进来。
        out.emit(
            TaskEvent.Suspended(
                loop.task.id,
                TaskSuspension.of(
                    taskId = loop.task.id,
                    status = reason.status,
                    reasonKey = reason.reasonKey,
                    stepSeq = loop.stepSeq,
                    checkpointSaved = checkpointSaved,
                ),
            ),
        )
    }

    private suspend fun finish(
        out: FlowCollector<TaskEvent>,
        loop: LoopState,
        reason: TerminalReason,
        outcome: TaskOutcome?,
        detail: String? = null,
    ) {
        runCatching {
            taskStore.finish(
                taskId = loop.task.id,
                status = reason.status,
                reason = reason.reasonKey + (detail?.let { " ($it)" } ?: ""),
                answerEnc = null,
                answerPreview = outcome?.answer?.take(200),
            )
        }.onFailure { AkeLog.w(TAG, "task finish persist failed: ${it.message}") }

        if (reason.keepCheckpoint) {
            runCatching { checkpointStore.save(loop.snapshot(reason.status, false, null)) }
        } else {
            runCatching { checkpointStore.clear(loop.task.id) }
        }

        AkeLog.i(TAG, "task finished: ${reason.name} steps=${loop.usage.stepsDone}")
        out.emit(TaskEvent.StatusChanged(loop.task.id, reason.status))
        out.emit(TaskEvent.Terminal(loop.task.id, reason, outcome, detail))
    }

    private fun buildOutcome(
        loop: LoopState,
        reason: TerminalReason,
        answer: String,
        refs: List<String>,
    ): TaskOutcome = TaskOutcome(
        taskId = loop.task.id,
        reason = reason,
        answer = answer,
        evidenceRefs = refs,
        stepsDone = loop.usage.stepsDone,
        prefillTokensTotal = loop.usage.prefillTokensTotal,
        decodeTokensTotal = loop.usage.decodeTokensTotal,
        elapsedMsTotal = loop.usage.elapsedMsTotal,
        exported = collector.takeExport(),
    )

    // ──────────────────────────────────────────── 小工具

    private fun buildSessionSpec(opts: RunOptions, budget: LoopBudget, task: TaskSpec) = SessionSpec(
        modelId = opts.modelId,
        taskId = task.id.value,
        maxOutputTokens = budget.decodeTokens,
        temperature = 0.2,
        topK = 32,
        topP = 0.9,
        thinking = ThinkingBudget.Off,      // §4.4：MVP 关闭
        enableJsonConstraint = true,        // 主循环唯一形态
    )

    private fun decisionOf(v: PolicyVerdict): String = when (v) {
        PolicyVerdict.Allow -> PolicyDecision.ALLOW
        is PolicyVerdict.RequiresConfirm -> PolicyDecision.REQUIRES_CONFIRM
        is PolicyVerdict.Denied -> PolicyDecision.DENIED
    }

    private fun reasonKeyOf(v: PolicyVerdict): String? = when (v) {
        PolicyVerdict.Allow -> null
        is PolicyVerdict.RequiresConfirm -> v.reasonKey
        is PolicyVerdict.Denied -> v.reasonKey
    }

    companion object {
        const val TAG: String = "AgentLoop"

        /** 感知/推理失败后的退避。刻意很短：端侧单步已 4.5 秒，再退避 1 秒用户就该以为卡死了。 */
        const val RETRY_BACKOFF_MS: Long = 300L

        const val SETTLE_TIMEOUT_MS: Long = 3_000L

        /** 落 trace 的模型原文上限。再长会撑爆 DB 且没有回放价值。 */
        const val MAX_RAW: Int = 2_048
    }
}
