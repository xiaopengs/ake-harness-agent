package com.ake.inference

import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.common.SessionId
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.ModelCapabilities
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.PromptContext
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.TlsMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// =============================================================================
// `:inference` 单测的替身。**刻意不引入任何 mock 框架**（零新坐标）。
//
// ★ 设计要点：每个替身都带**调用计数**。
//   「有没有被调用」比「返回了什么」更能钉死行为 ——
//   例如「页面上下文时云端一个字节都没发」只能靠 `calls == 0` 证明。
// =============================================================================

internal const val CODE_TEST_NOT_USED: String = "TEST_NOT_USED"

internal fun notUsed(): Outcome<Nothing> =
    Outcome.Err(AppError(code = CODE_TEST_NOT_USED, reasonKey = "err_test_not_used", severity = Severity.LOW))

internal val TEST_CLOCK: Clock = Clock { 1_700_000_000_000L }

internal fun testSpec(modelId: String = "local-1"): SessionSpec =
    SessionSpec(modelId = modelId, taskId = "task-1")

internal fun testPermit(host: String = "gw.example.com", ref: String = "svc-a"): EgressPermit =
    EgressPermit(
        auditRowId = "row-1",
        decidedAt = TEST_CLOCK.nowMillis(),
        endpointHost = host,
        credentialRef = CredentialRef(ref),
    )

internal fun testEndpoint(
    baseUrl: String = "https://gw.example.com/v1",
    window: Int? = null,
): EndpointConfig = EndpointConfig(
    baseUrl = baseUrl,
    modelName = "gpt-4o-mini",
    tlsMode = TlsMode.REQUIRED,
    declaredContextWindow = window,
    label = "test",
)

/** 端侧引擎替身。★ 它是 `DeviceModelPort` —— 这是它能被塞进 `DefaultModelRouter` 的唯一理由。 */
internal class FakeDevice(
    override val capabilities: ModelCapabilities = ModelCapabilities.DEVICE_DEFAULT,
) : DeviceModelPort {

    public var openSessionCalls: Int = 0
        private set
    public var lastSpec: SessionSpec? = null
        private set

    override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)

    override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)

    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> {
        openSessionCalls++
        lastSpec = spec
        return Outcome.Ok(FakeSession())
    }

    override fun estimateTokens(text: String): Int = text.length
}

/**
 * ★★ 只实现 `DialogModelPort`，**不是** `DeviceModelPort`。
 *
 * 它存在的意义是给「DOM 锁 A」当**负向**证据：
 * 云端适配器拿不到「端侧专属能力」这个类型，所以
 * `AgentLoop(model = cloudPort)` 这类调用在编译期就不成立。
 */
internal class FakeCloudDialog : DialogModelPort {

    public var openDialogCalls: Int = 0
        private set
    public var seenPermit: EgressPermit? = null
        private set
    public var seenContext: PromptContext? = null
        private set

    override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)

    override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)

    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = notUsed()

    override fun estimateTokens(text: String): Int = text.length

    override suspend fun openDialog(
        spec: SessionSpec,
        permit: EgressPermit?,
        context: PromptContext,
    ): Outcome<ModelSession> {
        openDialogCalls++
        seenPermit = permit
        seenContext = context
        return Outcome.Ok(FakeSession())
    }
}

internal class FakeSession(
    override val sessionId: SessionId = SessionId("sess-1"),
) : ModelSession {

    public var closed: Boolean = false
        private set

    override fun close() {
        closed = true
    }

    override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> = notUsed()

    override suspend fun currentCacheTokens(): Int = 0
}

internal class FakeCredentials(
    private val existing: MutableSet<CredentialRef> = mutableSetOf(CredentialRef("svc-a")),
    private val endpoint: EndpointConfig = testEndpoint(),
    private val secret: PlainSecret = PlainSecret.of("sk-test-000"),
) : CloudCredentialPort {

    /** ★ 明文读取次数。`DefaultModelRouter` 只该判 `exists()`，**一次都不该读 secret**。 */
    public var secretReads: Int = 0
        private set

    override suspend fun upsert(
        ref: CredentialRef,
        cfg: EndpointConfig,
        secret: PlainSecret,
    ): Outcome<Unit> {
        existing += ref
        return Outcome.Ok(Unit)
    }

    override fun refs(): Flow<List<CredentialRef>> = flowOf(existing.toList())

    override suspend fun configOf(ref: CredentialRef): Outcome<EndpointConfig> = Outcome.Ok(endpoint)

    override suspend fun secretOf(ref: CredentialRef): Outcome<PlainSecret> {
        secretReads++
        return Outcome.Ok(secret)
    }

    override suspend fun delete(ref: CredentialRef): Outcome<Unit> {
        existing -= ref
        return Outcome.Ok(Unit)
    }

    override suspend fun exists(ref: CredentialRef): Boolean = ref in existing
}

/** 审计替身。★ `open` 调用次数 = 「申请了几次出境」。 */
internal class FakePermitSink : EgressPermitSink {

    public val opened: MutableList<RoutingDecision> = mutableListOf()
    public val closed: MutableList<AuditOutcome> = mutableListOf()
    public var failOpen: Boolean = false

    override suspend fun open(ctx: RouteContext, decision: RoutingDecision): Outcome<EgressPermit> {
        if (failOpen) return notUsed()
        opened += decision
        return Outcome.Ok(testPermit(ref = decision.target.code))
    }

    override suspend fun close(
        permit: EgressPermit,
        outcome: AuditOutcome,
        phase: EgressPhase,
        deliveredBytes: Int,
        finishReasonRaw: String?,
    ): Outcome<Unit> {
        closed += outcome
        return Outcome.Ok(Unit)
    }

    override suspend fun sweepPending(olderThanMs: Long): Int = 0
}
