package com.ake.ui.testsupport

import com.ake.core.common.AppError
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
import com.ake.core.ports.AppendMessage
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatRunnerPort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.ChatSessionSummary
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.LoopMode
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.core.ports.ProfileOrigin
import com.ake.core.ports.ProfileOverrides
import com.ake.core.ports.SkillCostClass
import com.ake.core.ports.SkillConfirmPolicy
import com.ake.core.ports.SkillSpec
import com.ake.core.ports.TemplateStorePort
import com.ake.core.ports.TemplateTrust
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.core.ports.TrustScanVerdict
import com.ake.core.ports.TrustTier
import com.ake.core.ports.ActionRiskPort
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
