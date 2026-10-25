package com.ake.chat.fixtures

import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.SessionId
import com.ake.core.common.TaskId
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
import com.ake.core.contract.StepAction
import com.ake.core.contract.StepRecord
import com.ake.core.contract.TaskSpec
import com.ake.core.ports.ActionContext
import com.ake.core.ports.AgentProfile
import com.ake.core.ports.AgentProfileDraft
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.AgentProfileSummary
import com.ake.core.ports.AppendMessage
import com.ake.core.ports.BatteryState
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.ChatSessionSummary
import com.ake.core.ports.ContentSource
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.HumanGate
import com.ake.core.ports.LoopMode
import com.ake.core.ports.MemoryTier
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.ProfileOrigin
import com.ake.core.ports.Resolved
import com.ake.core.ports.RunOptions
import com.ake.core.ports.Sanitized
import com.ake.core.ports.SocProfile
import com.ake.core.ports.TaskEvent
import com.ake.core.ports.TaskOutcome
import com.ake.core.ports.TaskRunnerPort
import com.ake.core.ports.TerminalReason
import com.ake.core.ports.ThermalState
import com.ake.core.ports.TraceStorePort
import com.ake.core.ports.TrustScanVerdict
import com.ake.core.ports.TrustTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `:chat` 单测用的假件。**刻意都写在这个文件里**：
 * 一个测试用的 `ModelPort` 实现被复制到三个测试类里，是「三个假件逐渐不同步」的开始。
 */

class FakeClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis(): Long = now
}

/** 闸 1 的透传实现：只保证「一定被调用过」，不做清洗（清洗本身有 `:agent` 的单测）。 */
class RecordingPolicy : PolicyPort {
    var sanitizeCalls: Int = 0
    override suspend fun authorize(action: StepAction, ctx: ActionContext): Outcome<PolicyVerdict> =
        Outcome.Ok(PolicyVerdict.Allow)

    override fun sanitize(text: String, source: ContentSource): Sanitized {
        sanitizeCalls++
        return Sanitized(text = text, redacted = false)
    }
}

class FakeModelPort(
    /** Router 的产出脚本：按顺序消费，用尽后重复最后一条。 */
    private val routerScript: List<String> = listOf("""{"mode":"reply"}"""),
    private val answerText: String = "好的，我在本地帮你查过了。",
) : ModelPort {

    var opened: Int = 0
    var lastSpec: SessionSpec? = null
    var lastSession: FakeModelSession? = null

    /** 置上之后 `generate()` 会在这里挂住，用于测试「同一会话并发轮次」这类时序。 */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /**
     * 置上之后 `generate()` 直接抛 `RuntimeException`。
     *
     * 用于覆盖 `ChatLoop.turn` 里 `catch (t: Throwable)` 那条路径 ——
     * 它和三条 `return` 一样，是「必须复位 in-flight 标记」的退出路径之一。
     * ★ 刻意抛「非 Outcome.Err」：`Outcome.Err` 走的是返回值分支，不是异常分支。
     */
    var throwOnGenerate: Boolean = false

    /** 置上之后 `openSession()` 返回 `Err`（模拟模型没装好 / native 拒绝建会话）。 */
    var failOpenSession: Boolean = false

    override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)
    override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)

    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> {
        opened++
        lastSpec = spec
        if (failOpenSession) return Outcome.Err(AppError.invalidInput("fake_open_session_failed"))
        return Outcome.Ok(
            FakeModelSession(routerScript, answerText, gate) { throwOnGenerate }.also { lastSession = it },
        )
    }

    /** 端侧估算是本地启发式：4 字符 ≈ 1 token，与 `:llm` 的量级一致即可。 */
    override fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}

class FakeModelSession(
    private val routerScript: List<String>,
    private val answerText: String,
    private val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    /** 每次生成时现取：同一个会话（`ChatLoop` 会复用）里允许测试中途把开关关掉。 */
    private val shouldThrow: () -> Boolean = { false },
) : ModelSession {

    override val sessionId: SessionId = SessionId("fake-session")
    val requests: MutableList<GenerationRequest> = mutableListOf()
    var closed: Boolean = false
    var failGenerate: Boolean = false
    private var routerIndex: Int = 0

    override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> {
        requests += req
        gate?.await()
        if (shouldThrow()) throw IllegalStateException("fake_generate_threw")
        if (failGenerate) {
            return Outcome.Err(AppError.invalidInput("fake_generate_failure"))
        }
        val text = when (req.outputShape) {
            is OutputShape.JsonSchema -> {
                val i = routerIndex.coerceAtMost(routerScript.lastIndex)
                routerIndex++
                routerScript[i]
            }
            else -> answerText
        }
        return Outcome.Ok(
            GenerationResult(
                rawText = text,
                toolCalls = emptyList(),
                finishReason = FinishReason.STOP,
                stats = InferenceStats(prefillTokens = 120, decodeTokens = 24, ttftMs = 40, decodeTps = 50.0),
            ),
        )
    }

    override suspend fun currentCacheTokens(): Int = 256
    override fun close() {
        closed = true
    }
}

class FakeChatStore(
    private val agentId: String = "agent-1",
    private val sessionId: String = "sess-1",
) : ChatSessionStorePort {

    val stored: MutableList<ChatMessageView> = mutableListOf()
    val commits: MutableList<Pair<String, MessageStatus>> = mutableListOf()
    val releases: MutableList<String> = mutableListOf()
    var lastMode: LoopMode = LoopMode.REPLY
    var modeEpoch: Int = 0
    var appendFails: Boolean = false

    /**
     * 让 `sessions()` 返回空表 ⇒ `ChatLoop.resolveScope()` 找不到会话 ⇒ 走
     * 「session 不存在或人格不可读」那条提前返回。**可开关**，因为 P0-1 的回归断言
     * 需要「同一条路径失败一次之后，同一会话还能正常发下一条」。
     */
    var sessionVisible: Boolean = true

    /** 让 `beginAssistant()` 失败（落盘失败的另一条入口，`append` 之外）。 */
    var beginFails: Boolean = false

    /** 让 `commitAssistant()` 失败。配合 [commitAttempts] 断言「同一轮内重试过一次」。 */
    var commitFails: Boolean = false

    /** `commitAssistant` 被调了几次（含重试）。 */
    var commitAttempts: Int = 0

    var compactionMarks: MutableList<Pair<String, Int>> = mutableListOf()

    /**
     * 每条 USER 消息带进来的幂等键（按到达顺序）。断言"重试复用同一个键"的观测面。
     */
    val requestKeys: MutableList<String?> = mutableListOf()

    /**
     * 模拟 `chat_message` 表上 `UNIQUE(client_request_id)` + `INSERT OR IGNORE`。
     *
     * ★ 这一行不是"过度建模"，而是让「重试会不会重复落一条用户消息」这个断言**有牙齿**：
     *   没有它，不管 `:chat` 传什么键，假件都会老老实实落一条，测试永远绿 ——
     *   那和线上 `UNIQUE` 形同虚设的样子一模一样（关千锤 P1-4 的原始现象）。
     */
    private val seenKeys: MutableSet<String> = mutableSetOf()

    private var seq: Int = 0

    override fun sessions(agentId: String?, includeArchived: Boolean): Flow<List<ChatSessionSummary>> =
        flowOf(
            if (!sessionVisible) {
                emptyList()
            } else {
                listOf(
                    ChatSessionSummary(
                        id = sessionId,
                        agentId = this.agentId,
                        title = "测试会话",
                        createdAt = 0L,
                        updatedAt = 0L,
                        lastMessageAt = 0L,
                        messageCount = stored.size,
                        previewRedacted = null,
                        unreadCount = 0,
                        lastReadSeq = 0,
                        compactedUptoSeq = 0,
                        lastMode = lastMode,
                        modeEpoch = modeEpoch,
                        archived = false,
                    ),
                ).filter { agentId == null || it.agentId == agentId }
            },
        )

    override suspend fun ensureForNewTurn(agentId: String, titleHint: String?): Outcome<String> =
        Outcome.Ok(sessionId)

    override fun messages(sessionId: String, limit: Int): Flow<List<ChatMessageView>> =
        flowOf(stored.takeLast(limit))

    override suspend fun append(msg: AppendMessage): Outcome<Unit> {
        if (appendFails) return Outcome.Err(AppError.invalidInput("append_failed"))
        requestKeys += msg.clientRequestId
        // ★ 幂等：同一个键第二次到达 ⇒ 0 行受影响，不落第二条（真实实现见 `:data` 的 insertIgnoringConflict）
        val key = msg.clientRequestId
        if (key != null && !seenKeys.add(key)) return Outcome.Ok(Unit)
        seq++
        stored += ChatMessageView(
            id = msg.id,
            sessionId = msg.sessionId,
            seq = seq,
            turnId = msg.turnId,
            role = msg.role,
            kind = msg.kind,
            status = msg.status,
            textRedacted = msg.text,
            taskId = msg.taskId,
            skillRef = msg.skillRef,
            callId = msg.callId,
            createdAt = msg.createdAt,
        )
        return Outcome.Ok(Unit)
    }

    override suspend fun beginAssistant(sessionId: String, turnId: String, messageId: String): Outcome<Unit> {
        if (beginFails) return Outcome.Err(AppError.invalidInput("begin_failed"))
        seq++
        stored += ChatMessageView(
            id = messageId,
            sessionId = sessionId,
            seq = seq,
            turnId = turnId,
            role = MessageRole.ASSISTANT,
            kind = MessageKind.TEXT,
            status = MessageStatus.STREAMING,
            textRedacted = "",
            createdAt = 0L,
        )
        return Outcome.Ok(Unit)
    }

    override suspend fun commitAssistant(
        messageId: String,
        status: MessageStatus,
        text: String,
        promptTokens: Int,
        decodeTokens: Int,
        kvCacheTokensAfter: Int,
        redactionCount: Int,
    ): Outcome<Unit> {
        commitAttempts++
        if (commitFails) return Outcome.Err(AppError.invalidInput("commit_failed"))
        commits += messageId to status
        val i = stored.indexOfLast { it.id == messageId }
        if (i >= 0) stored[i] = stored[i].copy(status = status, textRedacted = text)
        return Outcome.Ok(Unit)
    }

    override suspend fun sweepStreaming(): Int = 0

    override suspend fun recordFocusRelease(
        sessionId: String,
        kvTokens: Int,
        rehydrateTokens: Int,
        mode: LoopMode,
        epoch: Int,
        profile: String,
    ): Outcome<Unit> {
        releases += "$sessionId/$kvTokens/$epoch/$profile"
        return Outcome.Ok(Unit)
    }

    override suspend fun markCompacted(sessionId: String, uptoSeq: Int, level: Int) {
        compactionMarks += sessionId to uptoSeq
    }

    override suspend fun markRead(sessionId: String, seq: Int) = Unit
    override suspend fun saveDraft(sessionId: String, draft: String?): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun archive(sessionId: String) = Unit
    override suspend fun deleteSession(sessionId: String): Outcome<Int> = Outcome.Ok(0)
}

fun fakeProfile(
    id: String = "profile-1",
    agentId: String = "agent-1",
    persona: String = "你是一位视频导演，说话简短。",
): AgentProfile = AgentProfile(
    id = id,
    agentId = agentId,
    name = "视频导演",
    avatarUri = null,
    description = "分镜、运镜、脚本",
    personaCharter = persona,
    workingDirUri = null,
    modelId = "gemma-4-E2B-it",
    origin = ProfileOrigin.USER_DIRECT,
    trustTier = TrustTier.USER_AUTHORED,
    templateId = null,
    templateRevision = null,
    signatureKeyId = null,
    trustScan = TrustScanVerdict.pass(0L, 1),
    alwaysConfirmSkills = false,
    maxEnabledSkills = 6,
    createdAt = 0L,
    updatedAt = 0L,
    lastUsedAt = null,
    archived = false,
)

class FakeProfileStore(private var profile: AgentProfile = fakeProfile()) : AgentProfileStorePort {

    /** 允许测试中途换人格（验证「改人格 ⇒ 必须开新会话」）。 */
    fun update(newProfile: AgentProfile) {
        profile = newProfile
    }

    override fun profiles(includeArchived: Boolean): Flow<List<AgentProfileSummary>> = flowOf(emptyList())

    override suspend fun byId(id: String): Outcome<AgentProfile> =
        if (id == profile.id) Outcome.Ok(profile) else Outcome.Err(AppError.invalidInput("no_profile"))

    override suspend fun byAgentId(agentId: String): Outcome<AgentProfile> =
        if (agentId == profile.agentId) Outcome.Ok(profile) else Outcome.Err(AppError.invalidInput("no_profile"))

    override suspend fun upsert(draft: AgentProfileDraft): Outcome<String> = Outcome.Ok(profile.id)
    override suspend fun touchUsed(id: String) = Unit
    override suspend fun archive(id: String) = Unit
    override suspend fun delete(id: String): Outcome<Unit> = Outcome.Ok(Unit)
}

class FakeGrants(initial: Map<CapabilityRef, Boolean> = emptyMap()) : CapabilityGrantPort {

    val state: MutableMap<CapabilityRef, Boolean> = initial.toMutableMap()

    override suspend fun setGranted(agentId: String, ref: CapabilityRef, granted: Boolean) {
        state[ref] = granted
    }

    override fun grantsOf(agentId: String): Flow<Map<CapabilityRef, Boolean>> = flowOf(state.toMap())
}

class FakeHealth(private val tier: MemoryTier = MemoryTier.MID) : DeviceHealthPort {
    override fun memoryTier(): MemoryTier = tier
    override fun thermal(): Flow<ThermalState> = flowOf(ThermalState.NOMINAL)
    override fun battery(): Flow<BatteryState> = flowOf(BatteryState.unknown())
    override fun socProfile(): SocProfile = SocProfile.unknown()
}

class FakeHumanGate(private val answer: Resolved = Resolved.Proceed) : HumanGate {
    var asked: Int = 0
    override suspend fun resolve(verdict: PolicyVerdict, action: StepAction): Resolved {
        asked++
        return answer
    }
}

class FakeTaskRunner(private val events: List<TaskEvent>? = null) : TaskRunnerPort {
    var lastTask: TaskSpec? = null
    var lastOptions: RunOptions? = null

    override fun run(task: TaskSpec, opts: RunOptions): Flow<TaskEvent> {
        lastTask = task
        lastOptions = opts
        val scripted = events ?: listOf(
            TaskEvent.Started(task.id, 0, "goal"),
            TaskEvent.Observed(task.id, 1, "https://example.com", "示例站", "digest", 12, "obs-1"),
            TaskEvent.Planned(task.id, 1, StepAction.Scroll("down", 800), 600, 10, 12),
            TaskEvent.Terminal(
                task.id,
                TerminalReason.COMPLETED,
                TaskOutcome(
                    taskId = task.id,
                    reason = TerminalReason.COMPLETED,
                    answer = "前三条：鼠标 A/39 元/店铺甲；鼠标 B/49 元/店铺乙；鼠标 C/59 元/店铺丙",
                    evidenceRefs = emptyList(),
                    stepsDone = 3,
                    prefillTokensTotal = 1_200,
                    decodeTokensTotal = 120,
                    elapsedMsTotal = 4_000,
                ),
            ),
        )
        return flowOf(*scripted.toTypedArray())
    }
}

class FakeTraceStore : TraceStorePort {
    val appended: MutableList<StepRecord> = mutableListOf()
    override suspend fun append(step: StepRecord) {
        appended += step
    }

    override suspend fun load(taskId: TaskId): List<StepRecord> = emptyList()
}
