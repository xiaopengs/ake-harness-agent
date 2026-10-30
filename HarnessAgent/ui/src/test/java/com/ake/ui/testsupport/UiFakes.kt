package com.ake.ui.testsupport

import com.ake.core.common.AppError
import com.ake.core.common.LogLevel
import com.ake.core.common.Outcome
import com.ake.core.contract.RiskClass
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.AgentProfile
import com.ake.core.ports.AgentProfileDraft
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.AgentProfileSummary
import com.ake.core.ports.AgentTemplate
import com.ake.core.common.LogSink
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.AppendMessage
import com.ake.core.ports.BatteryState
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetStore
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.ModelCapabilities
import com.ake.core.ports.ModelRouter
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.PromptContext
import com.ake.core.ports.TlsMode
import com.ake.ui.spi.CloudEndpointProbe
import com.ake.ui.spi.CloudProbeResult
import com.ake.ui.spi.TurnEgressNotice
import com.ake.ui.spi.TurnEgressNoticeSource
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatRunnerPort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.ChatSessionSummary
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.LocalSettings
import com.ake.core.ports.LoopBudgetFacts
import com.ake.core.ports.LoopBudgetPort
import com.ake.core.ports.LoopMode
import com.ake.core.ports.MemoryTier
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.core.ports.ProfileOrigin
import com.ake.core.ports.ProfileOverrides
import com.ake.core.ports.SettingsStorePort
import com.ake.core.ports.SkillCostClass
import com.ake.core.ports.SkillConfirmPolicy
import com.ake.core.ports.SkillSpec
import com.ake.core.ports.SocProfile
import com.ake.core.ports.TemplateStorePort
import com.ake.core.ports.TemplateTrust
import com.ake.core.ports.ThermalState
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.core.ports.TrustScanVerdict
import com.ake.core.ports.TrustTier
import com.ake.core.ports.UsageTotals
import com.ake.core.ports.ActionRiskPort
import com.ake.ui.spi.PrivacyPort
import com.ake.ui.spi.StorageUsage
import com.ake.ui.spi.WipeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * ## 为什么 ViewModel 单测需要一个统一的入口
 *
 * `ViewModel.viewModelScope` 跑在 `Dispatchers.Main` 上，而纯 JVM 单测里没有 Android 的
 * Main 实现 —— 不替换就抛 "Module with the Main dispatcher is missing"。
 *
 * 这里统一用 `StandardTestDispatcher`（**不是** `UnconfinedTestDispatcher`）：
 * 什么时候执行哪一段协程，由测试显式推进（`runCurrent()` / `advanceUntilIdle()`），
 * 才能复现「`stop()` 之后立刻再发一句」这种**顺序敏感**的窗口 ——
 * 自动一路跑完会把那个窗口跨过去，测试就退化成装饰。
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun runUiTest(block: suspend TestScope.() -> Unit) {
    runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
            // ★ 收尾必须把 ViewModel 里还没跑完的任务排空。
            //   TestCoroutineScheduler 的线程池是**全局共享**的：留在队列里的任务会在下一个
            //   测试里被执行，那时 `resetMain()` 已经跑过，报错会算到完全不相干的那条用例头上
            //   （"failed: Dispatchers.Main was accessed when the platform dispatcher was absent"
            //   出现在一条连 Main 都没碰过的用例上，是本项目真实踩到过的坑）。
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 会话存储
// ═════════════════════════════════════════════════════════════════════════════

/**
 * 记账式 `ChatSessionStorePort`。
 *
 * ★ `ensureCalls` 这类计数器不是装饰：判定"到底有没有发生"时用**调用次数**做断言，
 * 比断言某个状态字段更难糊弄 —— 状态可以被别的代码顺手写对，调用次数不能。
 */
public class FakeChatSessionStore(
    public val messages: MutableStateFlow<List<ChatMessageView>> = MutableStateFlow(emptyList()),
    public val summaries: List<ChatSessionSummary> = emptyList(),
    public val ensureResult: Outcome<String> = Outcome.Ok("session-1"),
) : ChatSessionStorePort {

    public var ensureCalls: Int = 0
        private set

    override fun sessions(agentId: String?, includeArchived: Boolean): Flow<List<ChatSessionSummary>> =
        flowOf(summaries)

    override suspend fun ensureForNewTurn(agentId: String, titleHint: String?): Outcome<String> {
        ensureCalls++
        return ensureResult
    }

    override fun messages(sessionId: String, limit: Int): Flow<List<ChatMessageView>> = messages

    override suspend fun append(msg: AppendMessage): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun beginAssistant(
        sessionId: String,
        turnId: String,
        messageId: String,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun commitAssistant(
        messageId: String,
        status: MessageStatus,
        text: String,
        promptTokens: Int,
        decodeTokens: Int,
        kvCacheTokensAfter: Int,
        redactionCount: Int,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun sweepStreaming(): Int = 0

    override suspend fun recordFocusRelease(
        sessionId: String,
        kvTokens: Int,
        rehydrateTokens: Int,
        mode: LoopMode,
        epoch: Int,
        profile: String,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun markCompacted(sessionId: String, uptoSeq: Int, level: Int): Unit = Unit

    override suspend fun markRead(sessionId: String, seq: Int): Unit = Unit

    override suspend fun saveDraft(sessionId: String, draft: String?): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun archive(sessionId: String): Unit = Unit

    override suspend fun deleteSession(sessionId: String): Outcome<Int> = Outcome.Ok(1)
}

// ═════════════════════════════════════════════════════════════════════════════
// 一轮回答的事件下发（`ChatRunnerPort`）
// ═════════════════════════════════════════════════════════════════════════════

/**
 * 「由测试按自己的节奏吐事件」的 `ChatRunnerPort`。
 *
 * 用 `Channel(UNLIMITED)` + `receiveAsFlow` 而不是 `MutableSharedFlow`：
 *  - 事件先于 collect 起来也只是进 Channel 缓冲，不会被丢，
 *    于是测试不必为「VM 的协程起来了没有」做时序安排；
 *  - `receiveAsFlow` 在订阅者被取消时**不会连带关闭通道**
 *    （`consumeAsFlow` 会），所以「上一轮被 `stop()` 取消、下一轮接着用」这条路径测得下去。
 */
public class ScriptedChatRunner(
    public val sessionId: String = "session-1",
) : ChatRunnerPort {

    /** 每收到一次 send()/retry() 就在末尾记一笔，用来断言「连点到底起了几轮」。 */
    public val requests: MutableList<ChatTurnRequest> = mutableListOf()

    private val channel = Channel<ChatEvent>(Channel.UNLIMITED)

    override fun turn(req: ChatTurnRequest): Flow<ChatEvent> {
        requests += req
        return channel.receiveAsFlow()
    }

    public fun emit(event: ChatEvent) {
        check(channel.trySend(event).isSuccess) { "Channel 写失败" }
    }

    public fun emit(vararg events: ChatEvent): Unit = events.forEach(::emit)

    /** 流正常结束 ⇒ VM 的 collect 返回，`finally` 里 `endTurn()` 释放闸。 */
    public fun endStream() {
        channel.close()
    }

    /** 流以异常结束（`receive` 会把 t 重新抛出）⇒ 走 VM 的 `catch (t: Throwable)` 分支。 */
    public fun failWith(t: Throwable) {
        channel.close(t)
    }

    // ── 事件语法糖

    public fun delta(messageId: String, text: String): ChatEvent.AssistantDelta =
        ChatEvent.AssistantDelta(sessionId = sessionId, messageId = messageId, delta = text)

    public fun terminal(messageId: String?, reason: ChatTerminalReason): ChatEvent.Terminal =
        ChatEvent.Terminal(sessionId = sessionId, messageId = messageId, reason = reason)
}

// ═════════════════════════════════════════════════════════════════════════════
// 授权 / 人格 / 模板
// ═════════════════════════════════════════════════════════════════════════════

public class FakeGrants(
    private val flow: Flow<Map<CapabilityRef, Boolean>>,
) : CapabilityGrantPort {

    public var grantedOfCalls: Int = 0
        private set

    override suspend fun setGranted(agentId: String, ref: CapabilityRef, granted: Boolean): Unit = Unit

    override fun grantsOf(agentId: String): Flow<Map<CapabilityRef, Boolean>> {
        grantedOfCalls++
        return flow
    }
}

public class FakeProfiles(
    public var profile: AgentProfile? = null,
    public val upsertResult: Outcome<String> = Outcome.Ok("profile-1"),
) : AgentProfileStorePort {

    public val upserts: MutableList<AgentProfileDraft> = mutableListOf()

    override fun profiles(includeArchived: Boolean): Flow<List<AgentProfileSummary>> = emptyFlow()

    override suspend fun byId(id: String): Outcome<AgentProfile> =
        profile?.let { Outcome.Ok(it) }
            ?: Outcome.Err(AppError.unknown("err_profile_not_found"))

    override suspend fun byAgentId(agentId: String): Outcome<AgentProfile> = byId(agentId)

    override suspend fun upsert(draft: AgentProfileDraft): Outcome<String> {
        upserts += draft
        return upsertResult
    }

    override suspend fun touchUsed(id: String): Unit = Unit
    override suspend fun archive(id: String): Unit = Unit
    override suspend fun delete(id: String): Outcome<Unit> = Outcome.Ok(Unit)
}

public class FakeTemplates(
    public var template: AgentTemplate? = null,
) : TemplateStorePort {

    public val instantiations: MutableList<Pair<String, ProfileOverrides>> = mutableListOf()

    override fun catalog(): Flow<List<AgentTemplate>> = emptyFlow()

    override suspend fun byId(templateId: String): Outcome<AgentTemplate> =
        template?.let { Outcome.Ok(it) }
            ?: Outcome.Err(AppError.unknown("err_template_not_found"))

    override suspend fun instantiate(
        templateId: String,
        overrides: ProfileOverrides,
    ): Outcome<String> {
        instantiations += templateId to overrides
        return Outcome.Ok("profile-1")
    }

    override suspend fun readCharter(templateId: String): Outcome<String> = Outcome.Ok("")

    override suspend fun import(signedBundlePath: String): Outcome<String> =
        Outcome.Err(AppError.unknown("err_unsupported"))
}

/** 风险分级与工具权限：这里只被 `UiHumanGate` 在构造/放行时碰到，给个够用的定值即可。 */
public class FixedRiskPort(private val risk: RiskClass = RiskClass.READ_ONLY) : ActionRiskPort {
    override fun classify(action: StepAction): RiskClass = risk
}

public object NoopPermissions : ToolPermissionStorePort {
    override suspend fun isEnabled(toolId: ToolId): Boolean = true
    override suspend fun isAlwaysAllow(toolId: ToolId): Boolean = false
    override suspend fun grantAlwaysAllow(toolId: ToolId): Unit = Unit
    override suspend fun revokeAlwaysAllow(toolId: ToolId): Unit = Unit
    override suspend fun setEnabled(toolId: ToolId, enabled: Boolean): Unit = Unit
    override suspend fun upsertDiscovered(tools: List<ToolSpec>): Unit = Unit
    override fun catalog(): Flow<List<ToolSpec>> = emptyFlow()
}

// ═════════════════════════════════════════════════════════════════════════════
// 设置页（SettingsViewModel）
// ═════════════════════════════════════════════════════════════════════════════

/**
 * 记账式 `SettingsStorePort`。
 *
 * ★ `updates` 记的是**每次写入的参数**，不是「最后剩下的值」：白名单那类用例要
 *   断言的是「这一次到底写进去了什么」，只看终态区分不出「写了一次」和「写了两次」。
 */
public class FakeSettingsStore(
    initial: LocalSettings = LocalSettings(),
) : SettingsStorePort {

    public val updates: MutableList<LocalSettings> = mutableListOf()

    private val state = MutableStateFlow(initial)

    override fun values(): Flow<LocalSettings> = state

    override suspend fun update(v: LocalSettings) {
        updates += v
        state.value = v
    }

    override suspend fun clearAll() {
        updates += LocalSettings()
        state.value = LocalSettings()
    }
}

/**
 * 什么都不做的 `PrivacyPort`。
 *
 * 存储占用一律给 0 而不是编几个"看起来合理"的数字 —— 假数字会让「用占用数算百分比」
 * 这类断言变成在断言 fake 自己。
 */
public object NoopPrivacy : PrivacyPort {
    override suspend fun usage(): StorageUsage = StorageUsage(0L, 0L, 0L, 0L, 0L, 0L)

    override suspend fun wipe(deleteModelFiles: Boolean): WipeReport =
        WipeReport(
            keystoreEntriesDeleted = 0,
            databasesDeleted = 0,
            webViewCleared = true,
            modelFilesDeleted = false,
            failures = emptyList(),
        )

    override suspend fun disconnectTools(): Unit = Unit
}

public class FixedDeviceHealth(
    private val tier: MemoryTier = MemoryTier.MID,
) : DeviceHealthPort {
    override fun memoryTier(): MemoryTier = tier
    override fun thermal(): Flow<ThermalState> = flowOf(ThermalState.NOMINAL)
    override fun battery(): Flow<BatteryState> = flowOf(BatteryState.unknown())
    override fun socProfile(): SocProfile = SocProfile.unknown()
}

/** 预算只读查询给定值 —— 设置页只把它显示成「只读」，不参与任何判断。 */
public class FixedBudgets(
    private val facts: LoopBudgetFacts = budgetFacts(),
) : LoopBudgetPort {
    override fun forTier(tier: MemoryTier): LoopBudgetFacts = facts
    override fun remainingPct(tier: MemoryTier, used: UsageTotals): Int = 100
}

public fun budgetFacts(maxSteps: Int = 12): LoopBudgetFacts = LoopBudgetFacts(
    memoryTier = "MID",
    maxSteps = maxSteps,
    maxWallClockMs = 60_000L,
    perStepMs = 5_000L,
    decodeMs = 1_500L,
    actionMs = 2_000L,
    maxPrefillTokens = 8_192L,
    maxDecodeTokens = 1_024L,
    currentTopK = 8,
    workingWindowTokens = 32_768,
    outputReserveTokens = 1_024,
    usablePromptTokens = 31_744,
)

// ═════════════════════════════════════════════════════════════════════════════
// 领域对象构造糖
// ═════════════════════════════════════════════════════════════════════════════

public const val TEST_NOW: Long = 1_700_000_000_000L

public fun agentProfile(
    id: String = "profile-1",
    shortName: String = "ag-abcdefghij",
    name: String = "阿客",
): AgentProfile = AgentProfile(
    id = id,
    agentId = shortName,
    name = name,
    avatarUri = null,
    description = "测试人格",
    personaCharter = "你是一个测试用的助理。",
    workingDirUri = null,
    modelId = "gemma-4",
    origin = ProfileOrigin.USER_DIRECT,
    trustTier = TrustTier.USER_AUTHORED,
    templateId = null,
    templateRevision = null,
    signatureKeyId = null,
    trustScan = TrustScanVerdict.pass(now = TEST_NOW, version = 1),
    alwaysConfirmSkills = false,
    maxEnabledSkills = 6,
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
    lastUsedAt = null,
    archived = false,
)

public fun chatSummary(id: String = "session-1", agentId: String = "profile-1"): ChatSessionSummary =
    ChatSessionSummary(
        id = id,
        agentId = agentId,
        title = "测试会话",
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW,
        lastMessageAt = TEST_NOW,
        messageCount = 0,
        previewRedacted = null,
        unreadCount = 0,
        lastReadSeq = 0,
        compactedUptoSeq = 0,
        lastMode = LoopMode.REPLY,
        modeEpoch = 0,
        archived = false,
    )

public fun chatMessage(
    id: String,
    sessionId: String = "session-1",
    seq: Int = 1,
    turnId: String = "turn-1",
    role: MessageRole = MessageRole.ASSISTANT,
    kind: MessageKind = MessageKind.TEXT,
    status: MessageStatus = MessageStatus.STREAMING,
    text: String = "",
): ChatMessageView = ChatMessageView(
    id = id,
    sessionId = sessionId,
    seq = seq,
    turnId = turnId,
    role = role,
    kind = kind,
    status = status,
    textRedacted = text,
    createdAt = TEST_NOW,
)

public fun skillSpec(
    id: String = "builtin.browser_task",
    kind: CapabilityKind = CapabilityKind.BUILTIN,
    name: String = "网页任务",
    description: String = "打开网页并按目标取数据",
    riskClass: RiskClass = RiskClass.PAGE_MUTATING,
): SkillSpec = SkillSpec(
    ref = CapabilityRef(kind, id),
    name = name,
    description = description,
    jsonSchema = "{}",
    riskClass = riskClass,
    costClass = SkillCostClass.CHEAP_SYNC,
    confirmPolicy = SkillConfirmPolicy.FIRST_USE_PER_AGENT,
    maxWallClockMs = null,
    enabled = true,
)

public fun template(
    templateId: String = "tpl-1",
    suggested: List<CapabilityRef>,
): AgentTemplate = AgentTemplate(
    templateId = templateId,
    revision = 1,
    name = "测试模板",
    avatarAsset = null,
    summary = "模板摘要",
    tags = listOf("test"),
    charter = "模板人格正文",
    suggestedCapabilities = suggested,
    disclaimer = null,
    trust = TemplateTrust(
        source = com.ake.core.ports.TemplateSource.BUILTIN,
        signatureKeyId = null,
        scan = TrustScanVerdict.pass(now = TEST_NOW, version = 1),
    ),
)

// ═════════════════════════════════════════════════════════════════════════════
// 云端推理（`:core:ports` 的假实现）
//
// ★ 这几个 fake 的共同纪律：**不在 fake 里替被测代码做决定**。
//   它们只负责「把某个答案原样交回去」，以及「记下被调用过几次、参数是什么」。
//   一旦 fake 内部开始判断（比如“Router 返回设备侧”），被测的 UI 逻辑就变成了
//   在断言 fake 自己的聪明 —— 那正是 ':app' ColdStartWiringTest 上一版被一枪打死的原因。
// ═════════════════════════════════════════════════════════════════════════════

/**
 * 记账式 `CloudCredentialPort`。
 *
 * ★ `upserts` 记的是 `(ref, cfg)`，`secrets` 单独记 `PlainSecret` 本体 ——
 *   分开存是有意的：断言「Key 有没有被转手」时，`PlainSecret.toString()` 必须是 `***`，
 *   而不是顺手把明文搬出来。
 */
public class FakeCloudCredentials(
    initial: Map<CredentialRef, Pair<EndpointConfig, String>> = emptyMap(),
) : CloudCredentialPort {

    public val upserts: MutableList<Pair<CredentialRef, EndpointConfig>> = mutableListOf()
    public val deletes: MutableList<CredentialRef> = mutableListOf()
    /** ★ 收下的 secret 本体。断言时只允许 `toString()`，不调 `useAsBearer`。 */
    public val secrets: MutableList<PlainSecret> = mutableListOf()
    public var enableDeletion: Boolean = true

    private val store: LinkedHashMap<CredentialRef, EndpointConfig> =
        LinkedHashMap(initial.mapValues { it.value.first })

    private val refsFlow = MutableStateFlow(store.keys.toList())

    override suspend fun upsert(ref: CredentialRef, cfg: EndpointConfig, secret: PlainSecret): Outcome<Unit> {
        upserts += ref to cfg
        secrets += secret
        store[ref] = cfg
        refsFlow.value = store.keys.toList()
        return Outcome.Ok(Unit)
    }

    override fun refs(): Flow<List<CredentialRef>> = refsFlow

    override suspend fun configOf(ref: CredentialRef): Outcome<EndpointConfig> =
        store[ref]?.let { Outcome.Ok(it) }
            ?: Outcome.Err(AppError.unknown("err_cred_not_found"))

    override suspend fun secretOf(ref: CredentialRef): Outcome<PlainSecret> =
        secrets.lastOrNull()?.let { Outcome.Ok(it) }
            ?: Outcome.Err(AppError.unknown("err_cred_not_found"))

    override suspend fun delete(ref: CredentialRef): Outcome<Unit> {
        deletes += ref
        if (!enableDeletion) return Outcome.Err(AppError.unknown("err_wipe_failed"))
        store.remove(ref)
        refsFlow.value = store.keys.toList()
        return Outcome.Ok(Unit)
    }

    override suspend fun exists(ref: CredentialRef): Boolean = store.containsKey(ref)
}

/** 记账式 `DialogTargetStore`。★ `selects` 逐次记录 —— 「写没写」与「写了几次」要能分开断言。 */
public class FakeDialogTargetStore(
    initial: Map<String, DialogTarget> = emptyMap(),
) : DialogTargetStore {

    public val selects: MutableList<Pair<String, DialogTarget>> = mutableListOf()
    public var selectResult: Outcome<Unit> = Outcome.Ok(Unit)
    public val ofCalls: MutableList<String> = mutableListOf()

    private val map: LinkedHashMap<String, DialogTarget> = LinkedHashMap(initial)

    override suspend fun of(sessionId: String): DialogTarget? {
        ofCalls += sessionId
        return map[sessionId]
    }

    override suspend fun select(sessionId: String, target: DialogTarget): Outcome<Unit> {
        selects += sessionId to target
        if (selectResult !is Outcome.Ok) return selectResult
        map[sessionId] = target
        return Outcome.Ok(Unit)
    }
}

/**
 * 一个只会报告自己的 `DialogModelPort`。
 *
 * @param locality 它自称在哪 —— 测试用它模拟「路由器此刻给出的答案」。
 */
public class FakeDialogPort(
    override val capabilities: ModelCapabilities = ModelCapabilities.DEVICE_DEFAULT,
    public var routerResult: Outcome<DialogModelPort>? = null,
) : DialogModelPort {

    override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)
    override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)
    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> =
        Outcome.Err(AppError.unknown("err_no_session"))
    override fun estimateTokens(text: String): Int = text.length / 3

    override suspend fun openDialog(
        spec: SessionSpec,
        permit: EgressPermit?,
        context: PromptContext,
    ): Outcome<ModelSession> = Outcome.Err(AppError.unknown("err_dialog_closed"))
}

/**
 * 记账式 `ModelRouter`。
 *
 * ★ 默认返回 `Err(NO_TARGET)` 而不是某种「合理默认值」：
 *   `DefaultModelRouter` 遇到没登记的目标就**不猜**（见 `Dialog.kt`），
 *   fake 跟着这一条走，UI 才不会在没有数据时把一个假答案当真的。
 *
 * @param port 要交出去的那个端口；为 null 时返回 [err]。
 */
public class FakeModelRouter(
    public var port: DialogModelPort? = null,
    public var err: Outcome<DialogModelPort> = Outcome.Err(AppError.unknown("err_no_target")),
) : ModelRouter {

    public val calls: MutableList<String> = mutableListOf()

    override suspend fun dialogFor(sessionId: String): Outcome<DialogModelPort> {
        calls += sessionId
        return port?.let { Outcome.Ok(it) } ?: err
    }
}

/** 「由测试决定吐什么事件」的回落 / 出境事实源。 */
public class FakeTurnEgressNoticeSource(
    initial: List<TurnEgressNotice> = emptyList(),
) : TurnEgressNoticeSource {

    public val flow: MutableStateFlow<List<TurnEgressNotice>> = MutableStateFlow(initial)

    override fun notices(sessionId: String): Flow<List<TurnEgressNotice>> = flow
}

/** 「按剧本给答案」的自检。记录每一次调用，用来断言到底发没发请求。 */
public class ScriptedCloudProbe(
    public var result: CloudProbeResult = CloudProbeResult.NotWired,
) : CloudEndpointProbe {

    public val calls: MutableList<Pair<String, Int>> = mutableListOf()

    override suspend fun probe(endpoint: EndpointConfig, secret: PlainSecret): CloudProbeResult {
        // ★ 只记 host 与掩码长度，不记内容：fake 自己也不该成为一条泄漏路径。
        calls += endpoint.hostOf() to secret.toString().length
        return result
    }
}

/** ★ 截获日志。负向断言要能证明「这条路确实打了日志」，就不能只提供一个空捕获。 */
public class CapturingLogSink : LogSink {
    public val lines: MutableList<Pair<String, String>> = mutableListOf()

    override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
        lines += tag to message
    }
}

public fun cloudEndpoint(
    baseUrl: String = "https://api.example.com/v1",
    modelName: String = "test-model",
): EndpointConfig = EndpointConfig(
    baseUrl = baseUrl,
    modelName = modelName,
    tlsMode = TlsMode.REQUIRED,
    declaredContextWindow = null,
    label = modelName,
)
