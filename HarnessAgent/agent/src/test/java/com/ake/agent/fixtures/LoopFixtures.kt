package com.ake.agent.fixtures

import com.ake.agent.context.InjectionIsolator
import com.ake.agent.context.PocketDomSerializer
import com.ake.agent.context.ContextAssembler
import com.ake.agent.guard.ContentSanitizer
import com.ake.agent.planner.DefaultSystemCharter
import com.ake.agent.planner.StepPromptBuilder
import com.ake.agent.planner.StepActionParser
import com.ake.agent.tools.CompositeToolRegistry
import com.ake.agent.tools.ResultCollector
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.ManualClock
import com.ake.core.common.Outcome
import com.ake.core.common.SessionId
import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.common.Url
import com.ake.core.contract.BrowserAction
import com.ake.core.contract.DomStats
import com.ake.core.contract.ExecutionResult
import com.ake.core.contract.LoopCheckpoint
import com.ake.core.contract.ObservationSummary
import com.ake.core.contract.PageChangeKind
import com.ake.core.contract.PageDigest
import com.ake.core.contract.PageEvent
import com.ake.core.contract.PageHandle
import com.ake.core.contract.PageState
import com.ake.core.contract.PocketDom
import com.ake.core.contract.RecordTable
import com.ake.core.contract.ScrollInfo
import com.ake.core.contract.StepAction
import com.ake.core.contract.SuccessCriteria
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.ToolCall
import com.ake.core.contract.ToolResult
import com.ake.core.contract.UiElement
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.InferenceStats
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.ActionContext
import com.ake.core.ports.BatteryState
import com.ake.core.ports.BrowserExecutorPort
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.ContentSource
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.DomPerceptionPort
import com.ake.core.ports.ExtractHint
import com.ake.core.ports.HumanGate
import com.ake.core.ports.MemoryTier
import com.ake.core.ports.ObserveOptions
import com.ake.core.ports.ObservationStorePort
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.Resolved
import com.ake.core.ports.Sanitized
import com.ake.core.ports.SocProfile
import com.ake.core.ports.TaskStorePort
import com.ake.core.ports.ThermalState
import com.ake.core.ports.TraceStorePort
import com.ake.core.contract.TaskBudgetPatch
import com.ake.core.contract.TaskRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `AgentLoop` 单测用的夹具（2026-10）。
 *
 * ## 一条纪律：**只替换能力，不替换规则**
 *
 * 被 fake 掉的只有「设备 / 模型 / 浏览器 / 存储」这四类**能力**，
 * 而下面这些**全部用真实实现**：
 *
 *  - `DefaultVerifier`（三重判定、十二终态、循环检测判据）
 *  - `StepActionParser` + `JsonTolerant`（容错解析）
 *  - `StepPromptBuilder` + `ContextAssembler`（分段配额与降级）
 *  - `InjectionIsolator` + `ContentSanitizer`（闸 1 / 闸 2）
 *  - `CompositeToolRegistry`（工具发现与 §7.2 的 12 个上限）
 *
 * 这不是偷懒的折中 —— 恰恰相反：**端侧 Agent 项目死于无法定位失败原因的比例，
 * 远高于死于单点能力不足**（`Verifier` 的类注释原话）。把规则层换成 mock，
 * 测出来的就不是这个系统的行为，而是一段「我以为它会怎么跑」的想象。
 *
 * 于是 `AgentLoopTest` 里跑一次任务，走的是真实的状态机、真实的护栏编排、
 * 真实的 prompt 组装 —— 只有「模型说了什么」和「浏览器做了什么」是可编程的。
 */

// ────────────────────────────────────────────────────────────────
// 领域对象
// ────────────────────────────────────────────────────────────────

internal const val TEST_URL: String = "https://shop.example.com/list?q=phone"

internal fun testTask(
    goal: String = "找出价格最低的三款手机",
    criteria: SuccessCriteria = SuccessCriteria(),
): TaskSpec = TaskSpec(
    id = TaskId("task-test-0001"),
    goal = goal,
    successCriteria = criteria,
    startUrl = Url(TEST_URL),
    allowHosts = setOf("shop.example.com"),
    maxSteps = 25,
    createdAtMillis = 0L,
)

internal fun testElement(id: Int, text: String, tag: String = "button", role: String? = "button"): UiElement =
    UiElement(
        id = id,
        tag = tag,
        role = role,
        text = text,
        attrs = emptyMap(),
        states = emptySet(),
        rect = null,
        depth = 3,
        score = 1.0f,
        stableKey = "stable-$id",
        selector = "#el-$id",
        xpath = "/html/body/div[$id]",
    )

/**
 * 一页测试 DOM。
 *
 * `records` 里的单元格值会被 `AnswerGrounding` 拿去做溯源，
 * 所以 `finish` 用例的 answer 必须能在这几行里找到 —— 这不是巧合，是契约。
 */
internal fun testDom(
    digest: String = "digest-1",
    state: PageState = PageState.READY,
    records: RecordTable? = testRecords(),
): PocketDom = PocketDom(
    url = TEST_URL,
    host = "shop.example.com",
    title = "手机 - 示例商城",
    pageState = state,
    scroll = ScrollInfo(0, 1200),
    outline = "div.list > div.item ×3",
    candidates = listOf(
        testElement(1, "加入购物车"),
        testElement(2, "销量排序"),
        testElement(3, "搜索框", tag = "input", role = "textbox"),
    ),
    records = records,
    signals = emptySet(),
    warnings = emptySet(),
    digest = PageDigest(digest),
    stats = DomStats(rawNodeCount = 12_000, prunedNodeCount = 900, compressionRatio = 13.3f, serializedChars = 7_800),
)

internal fun testRecords(): RecordTable = RecordTable(
    schemaHint = "div.product-card",
    columns = listOf("name", "price", "shop"),
    rows = listOf(
        mapOf("name" to "Phone A", "price" to "199", "shop" to "官方旗舰店"),
        mapOf("name" to "Phone B", "price" to "299", "shop" to "官方旗舰店"),
    ),
    totalEstimate = 60,
    truncated = false,
)

/**
 * `ExecutionResult` 的测试构造。
 *
 * 它带 `before` / `after` 两个 digest，是给上层做「这一步到底有没有改变页面」的判定依据；
 * `AgentLoop` 目前只消费 `change` / `addedCandidates` / `url`，
 * 所以这里给其余字段一个确定的默认值，不让每个用例重复抄一遍无关参数。
 */
internal fun execResult(
    change: PageChangeKind,
    addedCandidates: Int = 0,
    url: Url? = null,
): ExecutionResult = ExecutionResult(
    observationId = null,
    before = null,
    after = null,
    url = url,
    pageState = PageState.READY,
    change = change,
    addedCandidates = addedCandidates,
)

// ────────────────────────────────────────────────────────────────
// 能力替身
// ────────────────────────────────────────────────────────────────

internal class FakeModelPort(
    /** 第 n 次 `generate` 返回的原始文本（n 从 0 开始）。 */
    var script: (step: Int) -> String = { """{"act":"finish","answer":"199","evidenceRefs":["rec[1].price"]}""" },
    var openFails: Boolean = false,
) : ModelPort {

    val generateCalls: MutableList<GenerationRequest> = ArrayList()
    var closed: Int = 0

    override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)

    override val runtimeState: Flow<ModelRuntimeState> =
        flowOf(ModelRuntimeState.Ready("gemma-4-E2B-it", com.ake.core.modelapi.InferenceBackend.CPU, 8192))

    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> {
        // ★ 让出一次调度：`AgentLoop` 用 `health.thermal()/battery()` 的 `launchIn` 是**异步**的，
        //   而真实的 LiteRT 开会话要耗几十毫秒到十秒 —— 生产里那段时间足够这两个 Flow emit。
        //   这里用一次 `yield()` 复现那个时序，否则测出来的行为取决于「刚好还没 emit」。
        kotlinx.coroutines.yield()
        return if (openFails) {
            Outcome.Err(AppError(code = "MODEL_LOAD_FAILED", reasonKey = "err_model_load_failed"))
        } else {
            Outcome.Ok(FakeModelSession(this))
        }
    }

    override fun estimateTokens(text: String): Int = (text.length / 3).coerceAtLeast(1)

    internal class FakeModelSession(private val owner: FakeModelPort) : ModelSession {
        override val sessionId: SessionId = SessionId("sess-test")

        override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> {
            owner.generateCalls += req
            val raw = owner.script(owner.generateCalls.size - 1)
            return Outcome.Ok(
                GenerationResult(
                    rawText = raw,
                    toolCalls = emptyList(),
                    finishReason = FinishReason.STOP,
                    stats = InferenceStats(
                        prefillTokens = 1_200,
                        decodeTokens = 24,
                        ttftMs = 180,
                        decodeTps = 52.0,
                    ),
                ),
            )
        }

        override suspend fun currentCacheTokens(): Int = 2_048

        override fun close() {
            owner.closed++
        }
    }
}

internal class FakePerception(
    var result: () -> Outcome<PocketDom> = { Outcome.Ok(testDom()) },
) : DomPerceptionPort {

    var calls: Int = 0

    override suspend fun observe(opts: ObserveOptions): Outcome<PocketDom> {
        calls++
        return result()
    }

    override suspend fun extractRecords(hint: ExtractHint): Outcome<RecordTable> = Outcome.Ok(testRecords())
}

internal class FakeBrowser(
    var settled: Outcome<PageState> = Outcome.Ok(PageState.READY),
    /** 执行结果。默认「页面变了」 ⇒ 有进展（不计入 stalledSteps）。 */
    var exec: (BrowserAction) -> Outcome<ExecutionResult> = {
        Outcome.Ok(execResult(PageChangeKind.DOM_CHANGED, addedCandidates = 1))
    },
) : BrowserExecutorPort {

    var executed: Int = 0

    override suspend fun execute(action: BrowserAction): Outcome<ExecutionResult> {
        executed++
        return exec(action)
    }

    override suspend fun open(url: Url): Outcome<PageHandle> =
        Outcome.Err(AppError.unsupported("open"))

    override suspend fun waitForSettled(timeoutMs: Long): Outcome<PageState> = settled

    override fun pageEvents(): Flow<PageEvent> = flowOf()
}

internal class FakePolicy : PolicyPort {
    var verdict: (StepAction) -> PolicyVerdict = { PolicyVerdict.Allow }
    var sanitizeImpl: (String, ContentSource) -> Sanitized =
        { t, _ -> Sanitized(text = t, redacted = false, flagged = false, tripwires = 0) }

    override suspend fun authorize(action: StepAction, ctx: ActionContext): Outcome<PolicyVerdict> =
        Outcome.Ok(verdict(action))

    override fun sanitize(text: String, source: ContentSource): Sanitized = sanitizeImpl(text, source)
}

internal class FakeHealth(
    private val tier: MemoryTier = MemoryTier.MID,
    private val thermalStates: List<ThermalState> = listOf(ThermalState.NOMINAL),
    private val batteryStates: List<BatteryState> = listOf(
        BatteryState(88, isCharging = false, isPowerSave = false, isLow = false),
    ),
) : DeviceHealthPort {
    override fun memoryTier(): MemoryTier = tier
    override fun thermal(): Flow<ThermalState> = flowOf(*thermalStates.toTypedArray())
    override fun battery(): Flow<BatteryState> = flowOf(*batteryStates.toTypedArray())
    override fun socProfile(): SocProfile = SocProfile.unknown()
}

internal class FakeHumanGate(
    var resolve: (PolicyVerdict) -> Resolved = { Resolved.Proceed },
) : HumanGate {
    var calls: Int = 0

    override suspend fun resolve(verdict: PolicyVerdict, action: StepAction): Resolved {
        calls++
        return resolve(verdict)
    }
}

internal class FakeTraceStore : TraceStorePort {
    val appended: MutableList<com.ake.core.contract.StepRecord> = ArrayList()

    override suspend fun append(step: com.ake.core.contract.StepRecord) {
        appended += step
    }

    override suspend fun load(taskId: TaskId): List<com.ake.core.contract.StepRecord> = appended
}

internal class FakeObservationStore : ObservationStorePort {
    var calls: Int = 0

    override fun append(summary: ObservationSummary, payload: String?): Boolean {
        calls++
        return true
    }

    override suspend fun loadPayload(observationId: String): String? = null
}

internal class FakeCheckpointStore : CheckpointStorePort {
    val saved: MutableList<LoopCheckpoint> = ArrayList()
    val cleared: MutableList<String> = ArrayList()

    /** 让 `save` 抛异常，用于验证「断点没存住时 UI 必须禁用继续按钮」。 */
    var failNextSaves: Int = 0

    override suspend fun save(cp: LoopCheckpoint) {
        if (failNextSaves > 0) {
            failNextSaves--
            error("simulated checkpoint save failure")
        }
        saved += cp
    }

    override suspend fun load(taskId: TaskId): LoopCheckpoint? = saved.lastOrNull { it.taskId == taskId }
    override suspend fun clear(taskId: TaskId) {
        cleared += taskId.value
    }

    override suspend fun findResumable(): List<TaskId> = saved.map { it.taskId }
}

internal class FakeTaskStore : TaskStorePort {
    val finishes: MutableList<String> = ArrayList()
    val advances: MutableList<TaskBudgetPatch> = ArrayList()

    override suspend fun create(rec: TaskRecord) = Unit
    override suspend fun advance(taskId: TaskId, patch: TaskBudgetPatch) {
        advances += patch
    }

    override suspend fun finish(
        taskId: TaskId,
        status: String,
        reason: String?,
        answerEnc: ByteArray?,
        answerPreview: String?,
    ) {
        finishes += "$status|${reason.orEmpty()}"
    }

    override suspend fun load(taskId: TaskId): TaskRecord? = null
    override fun observe(taskId: TaskId): Flow<TaskRecord?> = flowOf(null)
    override suspend fun recent(limit: Int): List<TaskRecord> = emptyList()
    override fun observeRecent(limit: Int): Flow<List<TaskRecord>> = flowOf(emptyList())
}

// ────────────────────────────────────────────────────────────────
// 装配
// ────────────────────────────────────────────────────────────────

/**
 * 一次可运行的 `AgentLoop` 装配。
 *
 * @param outputs 第 n 步模型吐出的原文。默认 `finish`。
 * @param abort 中止信号，可由测试按条件翻转。
 */
internal class LoopHarness(
    outputs: (step: Int) -> String = { FINISH_JSON },
    val tier: MemoryTier = MemoryTier.MID,
    thermal: List<ThermalState> = listOf(ThermalState.NOMINAL),
    battery: List<BatteryState> = listOf(BatteryState(88, false, false, false)),
    var abort: () -> Boolean = { false },
) {
    val model = FakeModelPort(script = outputs)
    val perception = FakePerception()
    val browser = FakeBrowser()
    val policy = FakePolicy()
    val gate = FakeHumanGate()
    val trace = FakeTraceStore()
    val observations = FakeObservationStore()
    val checkpoints = FakeCheckpointStore()
    val tasks = FakeTaskStore()
    val health = FakeHealth(tier = tier, thermalStates = thermal, batteryStates = battery)
    val collector = ResultCollector()
    val clock: ManualClock = ManualClock(1_000L)

    /** 真实的规则层：护栏 / 判定器 / 解析器 / 上下文组装 / 注入隔离 */
    val parser = StepActionParser()
    val isolator = InjectionIsolator(sanitizer = policy)
    val promptBuilder = StepPromptBuilder(
        ContextAssembler(
            estimate = model::estimateTokens,
            isolator = isolator,
            domSerializer = PocketDomSerializer(),
            charter = DefaultSystemCharter(),
        ),
    )
    val verifier = com.ake.agent.loop.DefaultVerifier()
    val tools = CompositeToolRegistry(sources = emptySet())

    val loop: com.ake.agent.loop.AgentLoop = com.ake.agent.loop.AgentLoop(
        model = model,
        perception = perception,
        browser = browser,
        tools = tools,
        guard = policy,
        verifier = verifier,
        promptBuilder = promptBuilder,
        parser = parser,
        trace = trace,
        observationStore = observations,
        checkpointStore = checkpoints,
        taskStore = tasks,
        health = health,
        humanGate = gate,
        clock = clock,
        collector = collector,
    )

    fun opts(
        resumeFrom: LoopCheckpoint? = null,
        checkpointEveryStep: Boolean = true,
    ): com.ake.core.ports.RunOptions = com.ake.core.ports.RunOptions(
        // `AbortSignal` 是普通 Kotlin interface（不是 `fun interface`），没有 SAM 转换
        abort = object : AbortSignal {
            override fun isAborted(): Boolean = abort()
        },
        resumeFrom = resumeFrom,
        checkpointEveryStep = checkpointEveryStep,
    )

    companion object {
        /** answer "199" 能在 `testRecords()` 的 price 列里找到 ⇒ 通过 `AnswerGrounding` 溯源。 */
        const val FINISH_JSON: String =
            """{"act":"finish","answer":"199","evidenceRefs":["rec[1].price"]}"""
    }
}

internal fun checkpoint(
    taskId: TaskId = TaskId("task-test-0001"),
    stepSeq: Int = 0,
    prefill: Long = 0L,
    decode: Long = 0L,
    elapsed: Long = 0L,
): LoopCheckpoint = LoopCheckpoint(
    taskId = taskId,
    updatedAt = 0L,
    statusSnapshot = "RUNNING",
    currentStepSeq = stepSeq,
    prefillTokensTotal = prefill,
    decodeTokensTotal = decode,
    elapsedMsTotal = elapsed,
    seenDigests = emptyList(),
    scratchpad = null,
    lastUrl = TEST_URL,
    lastObservationId = null,
    lastKvCacheTokens = null,
    compactionLastStep = null,
    humanGatePending = false,
    humanGateJson = null,
)
