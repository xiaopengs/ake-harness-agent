package com.ake.core.ports

import com.ake.core.contract.StepAction
import com.ake.core.contract.TaskStatus
import com.ake.core.contract.TaskSuspension
import com.ake.core.contract.ToolId

import com.ake.core.contract.ToolSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★ **「这批新 Port 够不够 UI 用」的编译期证明**（2026-10，邝随行）。
 *
 * 它不是业务逻辑测试，是一份**可执行的一致性夹具**：
 *
 *  - 本文件里的每一行引用，都是从 `:ui` 当前源码里逐条摘出来的真实用法
 *    （`AgentSessionCoordinator.reduce()` / `SettingsViewModel.refreshBudget()` /
 *     `UiHumanGate.resolve()` / `McpViewModel` / `AndroidPrivacyPort.wipe()`）；
 *  - 本文件的 import 清单里**没有任何 `:agent` / `:mcp` / `:data` 的包名**；
 *  - 因此「本文件能通过编译」⇔「把这些 UI 用法改成只依赖 `core:ports` 之后也能编译」。
 *
 * 反过来它也锁住了回归：**谁动了这个契约的字段名，这里会先红**，
 * 而不是等到苏映棠改完 11 个 UI 文件才发现差一个字段。
 */
internal class UiFacingPortSufficiencyTest {

    // ── 假实现：只为把形状撑起来，不含行为 ──────────────────────────────

    private val runner = object : TaskRunnerPort {
        override fun run(task: com.ake.core.contract.TaskSpec, opts: RunOptions): Flow<TaskEvent> = flow {
            val id = task.id
            emit(TaskEvent.Started(id, 0, task.goal.take(64)))
            emit(TaskEvent.StatusChanged(id, TaskStatus.RUNNING))
            emit(TaskEvent.CheckpointSaved(id, 0))
            emit(TaskEvent.Terminal(id, TerminalReason.COMPLETED, null, null))
        }
    }

    private val budgets = object : LoopBudgetPort {
        override fun forTier(tier: MemoryTier): LoopBudgetFacts = LoopBudgetFacts(
            memoryTier = tier.name,
            maxSteps = 25, maxWallClockMs = 240_000L,
            perStepMs = 10_000L, decodeMs = 6_000L, actionMs = 2_000L,
            maxPrefillTokens = 150_000L, maxDecodeTokens = 10_000L,
            currentTopK = 48,
            workingWindowTokens = 8_192, outputReserveTokens = 896, usablePromptTokens = 7_296,
        )

        override fun remainingPct(tier: MemoryTier, used: UsageTotals): Int =
            (100 - used.stepsDone).coerceIn(0, 100)
    }

    private val risks = object : ActionRiskPort {
        override fun classify(action: StepAction): com.ake.core.contract.RiskClass =
            com.ake.core.contract.RiskClass.READ_ONLY
    }

    private val wipe = object : WipePort {
        override suspend fun wipeEverything(deleteModelFiles: Boolean): WipeResult =
            WipeResult(modelsDeleted = deleteModelFiles)
    }

    private object MemSettings : SettingsStorePort {
        private var cur = LocalSettings()
        override fun values(): Flow<LocalSettings> = flowOf(cur)
        override suspend fun update(v: LocalSettings) { cur = v }
        override suspend fun clearAll() { cur = LocalSettings() }
    }

    private object MemMcpServers : McpServerStorePort {
        private val items = LinkedHashMap<String, McpServerConfig>()
        override suspend fun load(): List<McpServerConfig> = items.values.toList()
        override suspend fun save(list: List<McpServerConfig>) { items.clear(); list.forEach { items[it.id] = it } }
        override suspend fun upsert(config: McpServerConfig) { items[config.id] = config }
        override suspend fun remove(id: String) { items.remove(id) }
        override suspend fun clearAll() { items.clear() }
    }

    private val mcp = object : McpConnectionPort {
        override suspend fun configure(servers: List<McpServerConfig>) = Unit
        override suspend fun discover(): List<ToolSpec> = emptyList()
        override suspend fun states(): List<McpServerState> =
            listOf(McpServerState("s", "label", McpServerState.Phase.DEAD, lastError = null))
        override suspend fun setEnabled(toolId: ToolId, enabled: Boolean) = Unit
        override suspend fun shutdown() = Unit
    }

    private val tasks = object : TaskStorePort {
        override suspend fun create(rec: com.ake.core.contract.TaskRecord) = Unit
        override suspend fun advance(taskId: com.ake.core.common.TaskId, patch: com.ake.core.contract.TaskBudgetPatch) = Unit
        override suspend fun finish(
            taskId: com.ake.core.common.TaskId,
            status: String,
            reason: String?,
            answerEnc: ByteArray?,
            answerPreview: String?,
        ) = Unit
        override suspend fun load(taskId: com.ake.core.common.TaskId): com.ake.core.contract.TaskRecord? = null
        override fun observe(taskId: com.ake.core.common.TaskId): Flow<com.ake.core.contract.TaskRecord?> = flowOf(null)
        override suspend fun recent(limit: Int): List<com.ake.core.contract.TaskRecord> = emptyList()
        override fun observeRecent(limit: Int): Flow<List<com.ake.core.contract.TaskRecord>> = flowOf(emptyList())
    }

    // ── 一、主循环：能否覆盖 `AgentSessionCoordinator` 的全部用法 ──────────

    /**
     * UI 的核心用法：`loop.run(spec, RunOptions(...)).collect { reduce(it) }`。
     * 这里复刻 `reduce()` 的**穷尽 when**——两个缺口的 Metro 都已经补上：
     * TerminalReason / SuspendReason / TaskEvent 都不再从 `:agent` import。
     */
    @Test
    fun `reduce covers every TaskEvent branch without importing agent`() = runBlocking {
        val spec = com.ake.core.contract.TaskSpec(
            id = com.ake.core.common.TaskId("01JTEST00000000000000000000"),
            goal = "帮我查一下今天杭州的天气",
            createdAtMillis = 0L,
        )
        val seen = ArrayList<String>()

        runner.run(spec, RunOptions(abort = NoAbortSignal, resumeFrom = null, modelId = DEFAULT_MODEL_ID))
            .collect { e ->
                // ★ 与 UI 完全一致：最后的 assert 依赖 Terminal/Suspended 互斥且穷尽
                val end = e is TaskEvent.Terminal || e is TaskEvent.Suspended
                seen += "${e::class.simpleName}:$end"
            }

        assertThat(seen).hasSize(4)
        assertThat(seen.last()).isEqualTo("Terminal:true")
    }

    /** UI 的 `UiState.kt` / `TerminalCopy.kt` 需要遍历两个枚举做文案映射。 */
    @Test
    fun `terminal and suspend enums are exhaustive for UI copy tables`() {
        assertThat(TerminalReason.ALL).hasSize(12)
        assertThat(TerminalReason.entries.map { it.reasonKey }).containsNoDuplicates()
        assertThat(TerminalReason.parseOrNull("COMPLETED")).isEqualTo(TerminalReason.COMPLETED)
        assertThat(TerminalReason.COMPLETED.isSuccess).isTrue()
        assertThat(TerminalReason.USER_ABORT.reasonKey).isEqualTo("terminal_user_abort")

        assertThat(SuspendReason.entries).hasSize(3)
        assertThat(SuspendReason.THERMAL.reasonKey).isEqualTo("suspend_thermal")
        assertThat(SuspendReason.HUMAN_GATE.status).isEqualTo(TaskStatus.WAITING_HUMAN)
        assertThat(SuspendReason.THERMAL.status).isEqualTo(TaskStatus.PAUSED)
    }

    /** `UiState.suspendReasonOf()`：由 reasonKey 反查挂起原因。 */
    @Test
    fun `suspend reason is resolvable from persisted reasonKey`() {
        val found = SuspendReason.entries.firstOrNull { it.reasonKey == "suspend_battery_low" }
        assertThat(found).isEqualTo(SuspendReason.BATTERY_LOW)
    }

    // ── 二、预算：能否覆盖 `SettingsViewModel.refreshBudget()` ────────────

    /** 设置页 BudgetFacts 的 12 个字段，逐个取一遍 —— 少一个这里就编译不过。 */
    @Test
    fun `budget facts cover every field the settings screen renders`() {
        val tier = MemoryTier.MID
        val b = budgets.forTier(tier)
        val facts = listOf(
            b.memoryTier, b.maxSteps, b.maxWallClockMs, b.perStepMs, b.decodeMs, b.actionMs,
            b.maxPrefillTokens, b.maxDecodeTokens, b.currentTopK,
            b.workingWindowTokens, b.outputReserveTokens, b.usablePromptTokens,
        )
        assertThat(facts).hasSize(12)
        assertThat(b.memoryTier).isEqualTo("MID")
        assertThat(b.usablePromptTokens).isEqualTo(b.workingWindowTokens - b.outputReserveTokens)
    }

    /** 任务页 MetricsUi：`remainingPct` + 上下文占用百分比。 */
    @Test
    fun `remaining budget pct is computable from usage totals`() {
        val used = UsageTotals(
            stepsDone = 3,
            prefillTokensTotal = 1_000L,
            decodeTokensTotal = 200L,
            elapsedMsTotal = 9_000L,
        )
        assertThat(budgets.remainingPct(MemoryTier.MID, used)).isEqualTo(97)

        val window = budgets.forTier(MemoryTier.MID).usablePromptTokens
        assertThat(((2_048.0 / window) * 100).toInt().coerceIn(0, 100)).isAtMost(100)
    }

    // ── 三、风险分级：能否覆盖 `UiHumanGate` ──────────────────────────────

    /** `UiHumanGate.PendingGate.riskClass` 由护栏给，UI 不自己判断。 */
    @Test
    fun `risk class comes from the guard not the UI`() {
        val action = StepAction.Scroll(dir = "down", px = 200)
        assertThat(risks.classify(action).name).isNotEmpty()
    }

    // ── 四、清痕：能否覆盖 `AndroidPrivacyPort.wipe()` ────────────────────

    @Test
    fun `wipe port reports per-step failures so UI stops guessing`() = runBlocking {
        val r: WipeResult = wipe.wipeEverything(deleteModelFiles = true)
        assertThat(r.modelsDeleted).isTrue()
        // ★ 这条补的正是 `:ui` 侧指出的「诚实性缺口」：失败项要能逐条说出来
        assertThat(r.failures).isEmpty()
        assertThat(r.ok).isTrue()
    }

    @Test
    fun `settings store drives the allowlist and survives a wipe`() = runBlocking {
        MemSettings.update(LocalSettings(allowHosts = listOf("example.com"), maxSteps = 10, showDomPayload = false))
        val v: LocalSettings = MemSettings.values().first()
        assertThat(v.allowHosts).containsExactly("example.com")
        assertThat(v.maxSteps).isEqualTo(10)
        assertThat(v.showDomPayload).isFalse()

        // ★ WipeEverythingUseCase 的 TODO 就是等这条：清除范围必须与实现一致
        MemSettings.clearAll()
        assertThat(MemSettings.values().first().allowHosts).isEmpty()
    }

    // ── 五、MCP：能否覆盖 `McpViewModel` ──────────────────────────────────

    @Test
    fun `mcp server round trip covers every field the screen edits`() = runBlocking {
        val cfg = McpServerConfig(
            id = "srv-1",
            label = "本机 音乐索引",
            url = "http://127.0.0.1:8000/mcp",
            headers = mapOf("Authorization" to "Bearer x"),
            enabled = true,
            transport = TransportKind.STREAMABLE_HTTP,
            callTimeoutMs = 8_000L,
            connectTimeoutMs = 6_000L,
            maxReconnectAttempts = 3,
        )
        assertThat(cfg.isLoopback()).isTrue()

        MemMcpServers.upsert(cfg)
        assertThat(MemMcpServers.load()).hasSize(1)
        MemMcpServers.remove("srv-1")
        assertThat(MemMcpServers.load()).isEmpty()

        mcp.configure(MemMcpServers.load())
        assertThat(mcp.discover()).isEmpty()
        assertThat(mcp.states().single().phase).isEqualTo(McpServerState.Phase.DEAD)
        mcp.setEnabled(ToolId("mcp.srv-1.search"), true)
        mcp.shutdown()
        MemMcpServers.clearAll()
    }

    /** 非回环地址必须能被 UI 前置警示（`McpViewModel.save` 依赖这个判断）。 */
    @Test
    fun `non loopback server is detectable before saving`() {
        val remote = McpServerConfig(id = "srv-2", label = "公网", url = "https://mcp.example.com/sse")
        assertThat(remote.isLoopback()).isFalse()
        assertThat(McpServerConfig.DEFAULT_LOOPBACK_ONLY).isTrue()
    }

    // ── 六、历史列表：`TaskStorePort` 的挂起版与 Flow 版都在 ─────────────

    @Test
    fun `history list can be both snapshotted and observed`() = runBlocking {
        assertThat(tasks.recent(20)).isEmpty()
        assertThat(tasks.observeRecent(20).first()).isEmpty()
    }

    /**
     * 最后核对一件容易被忽略的事实：
     * `TaskSuspension` 仍然是 `core:contract` 的领域对象（同事已实现），
     * 它担在流终点上，不会因为本次补契约而搬家。
     */
    @Test
    fun `suspension remains a contract type not a ports type`() {
        val s = TaskSuspension.of(
            taskId = com.ake.core.common.TaskId("01JTEST00000000000000000000"),
            status = TaskStatus.PAUSED,
            reasonKey = SuspendReason.THERMAL.reasonKey,
            stepSeq = 3,
            checkpointSaved = true,
        )
        assertThat(TaskEvent.Suspended(s.taskId, s).suspension).isSameInstanceAs(s)
        assertThat(s.needsUserAction).isFalse()
    }
}

/** `TaskSuspension` 的字段名核对：UI 的 `RunState.Suspended` 直接包它。 */
