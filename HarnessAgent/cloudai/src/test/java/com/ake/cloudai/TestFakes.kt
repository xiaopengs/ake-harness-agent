package com.ake.cloudai

import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.common.StepId
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ThinkingBudget
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.PromptContext
import com.ake.core.ports.TlsMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FixedClock(private val now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

/** 最小可用的 `CloudCredentialPort` 假件。**单测专用**。 */
internal class FakeCredentials(
    private val ref: CredentialRef = CredentialRef("cred-1"),
    private val cfg: EndpointConfig = EndpointConfig(
        baseUrl = "https://gw.example.com/v1",
        modelName = "test-model",
        tlsMode = TlsMode.REQUIRED,
        declaredContextWindow = 8_192,
        label = "gw",
    ),
    private val secret: PlainSecret = PlainSecret.of("sk-live-abcdef123456"),
) : CloudCredentialPort {

    public var secretReadCount: Int = 0
        private set

    override suspend fun upsert(
        ref: CredentialRef,
        cfg: EndpointConfig,
        secret: PlainSecret,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override fun refs(): Flow<List<CredentialRef>> = flowOf(listOf(ref))

    override suspend fun configOf(ref: CredentialRef): Outcome<EndpointConfig> =
        if (ref == this.ref) Outcome.Ok(cfg) else Outcome.Err(
            com.ake.core.common.AppError(
                code = com.ake.core.ports.CloudFailureCodes.CREDENTIAL_MISSING,
                reasonKey = "err_cloud_cred_missing",
                severity = Severity.MEDIUM,
            ),
        )

    override suspend fun secretOf(ref: CredentialRef): Outcome<PlainSecret> {
        secretReadCount++
        return if (ref == this.ref) Outcome.Ok(secret) else Outcome.Err(
            com.ake.core.common.AppError(
                code = com.ake.core.ports.CloudFailureCodes.CREDENTIAL_MISSING,
                reasonKey = "err_cloud_cred_missing",
                severity = Severity.MEDIUM,
            ),
        )
    }

    override suspend fun delete(ref: CredentialRef): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun exists(ref: CredentialRef): Boolean = ref == this.ref

    /** 测试用：把假件里那份配置暴露出来做断言。 */
    public fun exposedConfig(): EndpointConfig = cfg
}

internal fun testSpec(): SessionSpec = SessionSpec(modelId = "test-model", taskId = "task-1")

internal fun testPermit(host: String = "gw.example.com"): EgressPermit = EgressPermit(
    auditRowId = "row-1",
    decidedAt = 0L,
    endpointHost = host,
    credentialRef = CredentialRef("cred-1"),
)

internal fun testRequest(turn: String = "hi"): GenerationRequest = GenerationRequest(
    stepId = StepId("step-1"),
    userTurn = turn,
    outputShape = OutputShape.FreeText,
    maxOutputTokens = 256,
    thinking = ThinkingBudget.Off,
    deadlineMs = 60_000L,
)

/**
 * 脚本化传输：按给定的字节块依次推送，或直接以一个终态收尾。
 * ★ 它**记录每一次被调用**，于是「一个字节都没发出去」可以被断言为 `calls == 0`。
 */
internal class ScriptedTransport(
    private val chunks: List<ByteArray> = emptyList(),
    private val failWith: CloudTransportOutcome? = null,
    private val raise: Throwable? = null,
) : CloudTransport {

    public var calls: Int = 0
        private set

    override suspend fun stream(
        req: CloudRequest,
        onBytes: suspend (ByteArray) -> Unit,
    ): CloudTransportOutcome {
        calls++
        raise?.let { throw it }
        failWith?.let { return it }
        chunks.forEach { onBytes(it) }
        return CloudTransportOutcome.Delivered
    }
}

internal val TEST_PROMPT_CONTEXT: PromptContext = PromptContext.TextOnly

internal fun FakeCredentials(baseUrl: String): FakeCredentials = FakeCredentials(
    cfg = EndpointConfig(
        baseUrl = baseUrl,
        modelName = "test-model",
        tlsMode = TlsMode.REQUIRED,
        declaredContextWindow = 8_192,
        label = "gw",
    ),
)
