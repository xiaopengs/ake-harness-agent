package com.ake.cloudai

import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.SessionId
import com.ake.core.common.Severity
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.InferenceStats
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.ModelCapabilities
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.PromptContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.ake.core.modelapi.ModelRuntimeState

/**
 * ★★ 云端对话入口（架构 08 §2.4 / §5.2 锁 A）。
 *
 * ## ★ 它**只** implements [DialogModelPort]，**不** implements `DeviceModelPort`
 *
 * ⇒ `AgentLoop(model = this)` **编译不过** ⇒ 「浏览器任务用了云端模型」在类型上构造不出来。
 * 由 `:inference` 的 `DomLockTest`（反射 + 扫全仓）机械盯住。
 *
 * ## ★ 锁 C：`PromptContext.WithPageContext` ⇒ 硬失败，一个字节都不发
 *
 * 见 [openDialog] 里那个**没有 else 分支**的 `when`。
 *
 * ## ★★ 秘密的边界（§6.3）
 *
 *  · 本类**不持有** BaseURL 原文或密钥原文，只在 [openRemote] 里**一次性**取出
 *    [EndpointConfig] 与 [PlainSecret]，封进 [CloudDialogSession]；
 *  · 两者**都不进** [GenerationResult] / [Outcome.Err] / 日志中的任何一个；
 *  · `PlainSecret.toString()` 恒为 `***` ⇒ 即便被误传进字符串插值也只是 `***`。
 */
public class OpenAiCompatibleDialogPort internal constructor(
    private val credentials: CloudCredentialPort,
    private val transport: CloudTransport,
    private val config: CloudRuntimeConfig,
    private val clock: Clock,
) : DialogModelPort {

    public constructor(
        credentials: CloudCredentialPort,
        engine: HttpClientEngine = OkHttp.create(),
        config: CloudRuntimeConfig = CloudRuntimeConfig.DEFAULT,
        clock: Clock,
    ) : this(credentials, KtorCloudTransport(engine), config, clock)

    override val capabilities: ModelCapabilities
        get() = ModelCapabilities.REMOTE_DEFAULT.copy(
            contextLimit = com.ake.core.ports.ContextLimit.Declared(
                tokens = config.declaredContextWindow,
                source = com.ake.core.ports.DeclaredSource.DEFAULT,
            ),
            systemRole = config.systemRole,
        )

    /**
     * 云端估算：本地估算 × 1.15（§2.8 ③）。
     *
     * ★ 契约要求「必须快、禁止跑模型」—— 云端不能为了估算去问服务端。
     * 服务端 `usage.prompt_tokens` 回来后在 `CloudUsage` 里给出真值供校准。
     */
    override fun estimateTokens(text: String): Int {
        val perToken = CHARS_PER_TOKEN.coerceAtLeast(1)
        val base = ((text.length + perToken - 1) / perToken).coerceAtLeast(1)
        return (base * CLOUD_ESTIMATE_FACTOR).toInt().coerceAtLeast(1)
    }

    override suspend fun load(config: com.ake.core.modelapi.ModelLoadConfig): Outcome<Unit> =
        Outcome.Ok(Unit)

    override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)

    override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)

    /**
     * ★ 无 permit ⇒ 无云端会话。这是硬失败，不是「回落到本地」。
     */
    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> =
        openDialog(spec, permit = null, context = PromptContext.TextOnly)

    override suspend fun openDialog(
        spec: SessionSpec,
        permit: EgressPermit?,
        context: PromptContext,
    ): Outcome<ModelSession> {
        // ★★ 锁 C：sealed `when`，**没有 else**。带页面上下文 ⇒ 硬失败（PRE，未发字节）。
        return when (context) {
            is PromptContext.WithPageContext -> Outcome.Err(
                AppError(
                    code = com.ake.core.ports.CloudFailure.PageContextRefused.code,
                    reasonKey = com.ake.core.ports.CloudFailure.PageContextRefused.reasonKey,
                    severity = Severity.MEDIUM,
                ),
            )

            is PromptContext.TextOnly -> openRemote(spec, permit)
        }
    }

    private suspend fun openRemote(
        spec: SessionSpec,
        permit: EgressPermit?,
    ): Outcome<ModelSession> {
        if (permit == null) return fail(com.ake.core.ports.CloudFailure.NoPermit)

        val cfg = when (val r = credentials.configOf(permit.credentialRef)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return Outcome.Err(r.error)
        }
        val secret = when (val r = credentials.secretOf(permit.credentialRef)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return Outcome.Err(r.error)
        }

        val endpoint = CloudEndpoints.parse(cfg.baseUrl)
            ?: return fail(com.ake.core.ports.CloudFailure.InsecureEndpoint)
        if (CloudEndpointPolicy.check(endpoint.scheme, endpoint.host, cfg.tlsMode)
            !is CloudEndpointPolicy.Verdict.Allow
        ) {
            return fail(com.ake.core.ports.CloudFailure.InsecureEndpoint)
        }

        // ★ 端点 host 与 permit 钉死的 host 必须一致 ⇒ 防止「换了端点还用旧 permit」
        if (endpoint.host != permit.endpointHost) {
            return fail(com.ake.core.ports.CloudFailure.CredentialMissing)
        }

        return Outcome.Ok(
            CloudDialogSession(
                sessionId = SessionId(spec.taskId),
                spec = spec,
                cfg = cfg,
                secret = secret,
                endpoint = endpoint,
                transport = transport,
                config = config,
                clock = clock,
            ),
        )
    }

    private fun fail(f: com.ake.core.ports.CloudFailure): Outcome<Nothing> =
        Outcome.Err(AppError(code = f.code, reasonKey = f.reasonKey, severity = Severity.MEDIUM))

    public companion object {
        /** 本地估算的字符/token 比（保守，中文偏多估）。 */
        public const val CHARS_PER_TOKEN: Int = 3

        /** §2.8 ③：云端估算的保守系数。 */
        public const val CLOUD_ESTIMATE_FACTOR: Double = 1.15
    }
}
