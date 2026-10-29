package com.ake.cloudai

import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.SessionId
import com.ake.core.common.Severity
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.InferenceStats
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.PlainSecret

/**
 * 云端运行时配置。
 *
 * ★★ **三段超时必须分开**（§4.4 第一段）：它们归属的 phase 不同。
 * 把它们并成一个总 timeout，就会有人把「首字节超时」按「没连上」处理
 * ⇒ 那一刻用户的整段历史已经在对方服务器上了，而我们告诉用户「没上云」。
 */
public data class CloudRuntimeConfig(
    /** PRE：含 DNS / TCP / TLS 握手。 */
    val connectTimeoutMs: Long = 8_000L,
    /** POST：请求体写完 → 收到第一个 SSE 字节。★ 属于 POST。 */
    val firstByteTimeoutMs: Long = 15_000L,
    /** POST：流中两个 chunk 之间的最大间隔。 */
    val streamIdleTimeoutMs: Long = 30_000L,
    /** 云端声明窗口（**软**上限，已过 `EndpointConfig.clampWindow`）。 */
    val declaredContextWindow: Int = EndpointConfig.DEFAULT_CONTEXT_WINDOW,
    /** 每轮最大重放 token（§2.8 ②）。 */
    val maxReplayTokens: Int = DEFAULT_MAX_REPLAY_TOKENS,
    /** 是否加 `response_format: {"type":"json_object"}`（§2.6 兜底档）。 */
    val jsonConstraint: Boolean = true,
    /** system 角色名（OpenAI o 系列要求 `developer`）。 */
    val systemRole: com.ake.core.ports.SystemRoleMode = com.ake.core.ports.SystemRoleMode.IN_MESSAGES,
) {
    public companion object {
        /** §2.8 ②：默认 4096。 */
        public const val DEFAULT_MAX_REPLAY_TOKENS: Int = 4_096

        /** §2.8 ②：硬顶 16384。 */
        public const val HARD_MAX_REPLAY_TOKENS: Int = 16_384

        public val DEFAULT: CloudRuntimeConfig = CloudRuntimeConfig()
    }
}

/**
 * 一次云端对话会话的句柄（架构 08 §2.7）。
 *
 * ## ★ 复水：云端是 REPLAY_EACH_TURN
 *
 * `spec.initialMessages` **每一轮都要全量重放** ⇒
 *  · 历史越长 ⇒ 每轮出境字节越多 ⇒ 受 [CloudRuntimeConfig.maxReplayTokens] 硬顶约束；
 *  · 超过硬顶 ⇒ 由 `:inference` 调 `DeterministicCompactor` 压一轮再发（§2.8 ②）。
 *
 * ## 取消（§2.5）
 *
 * 协程被取消 ⇒ `CancellationException` 原样透传；`execute{}` 退出时关闭响应体
 * ⇒ 断开 TCP ⇒ 服务端停止生成。**不是**只停止收集。
 */
internal class CloudDialogSession(
    override val sessionId: SessionId,
    private val spec: SessionSpec,
    private val cfg: EndpointConfig,
    private val secret: PlainSecret,
    private val endpoint: CloudEndpoints.Endpoint,
    private val transport: CloudTransport,
    private val config: CloudRuntimeConfig,
    private val clock: Clock,
) : ModelSession {

    /** 上一轮实际发出的请求体字节数（审计用）。★ 只有真的发出去之后才被赋值。 */
    @Volatile
    public var lastDeliveredBytes: Int = 0
        private set

    /** 上一轮服务端返回的 `id`（对账用，§9.4 ③）。★ 不是秘密，可落审计。 */
    @Volatile
    public var lastRequestId: String? = null
        private set

    /** 上一轮服务端返回的 finish_reason **原文**。 */
    @Volatile
    public var lastFinishReasonRaw: String? = null
        private set

    /** 上一轮是否走到了 `data: [DONE]`。 */
    @Volatile
    public var lastCompleted: Boolean = false
        private set

    /** 上一轮的重放 token 估算（≈ 已占用窗口）。 */
    @Volatile
    public var lastReplayTokens: Int = 0
        private set

    private val startedAt: Long = clock.nowMillis()

    override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> {
        val body = bodyFor(req, stream = false)
        return call(body, req, onDelta = { })
    }

    override suspend fun generateStream(
        req: GenerationRequest,
        onDelta: suspend (String) -> Unit,
    ): Outcome<GenerationResult> {
        val body = bodyFor(req, stream = true)
        return call(body, req, onDelta)
    }

    private fun bodyFor(req: GenerationRequest, stream: Boolean): String = CloudRequestBody.build(
        cfg = cfg,
        spec = spec,
        currentTurn = req.userTurn,
        maxOutputTokens = req.maxOutputTokens,
        stream = stream,
        jsonConstraint = shouldConstrain(req),
    )

    private suspend fun call(
        body: String,
        req: GenerationRequest,
        onDelta: suspend (String) -> Unit,
    ): Outcome<GenerationResult> {
        val request = CloudRequest(
            url = endpoint.url,
            secret = secret,
            body = body,
            connectTimeoutMs = config.connectTimeoutMs,
            firstByteTimeoutMs = config.firstByteTimeoutMs,
            streamIdleTimeoutMs = config.streamIdleTimeoutMs,
            deadlineMs = req.deadlineMs,
        )
        val result = CloudChatRunner(transport).runOnce(request, onDelta)

        lastDeliveredBytes = result.deliveredBytes()
        lastRequestId = result.requestId()
        lastFinishReasonRaw = result.finishReasonRaw()
        lastCompleted = result is CloudCallResult.Delivered

        return when (result) {
            is CloudCallResult.Delivered -> {
                lastCompleted = true
                lastRequestId = result.requestId()
                lastFinishReasonRaw = result.finishReasonRaw()
                lastReplayTokens = result.usage()?.promptTokens
                    ?: CloudRequestBody.estimateTokens(body)
                Outcome.Ok(
                    GenerationResult(
                        rawText = result.text,
                        toolCalls = emptyList(),
                        finishReason = FinishReasonCodec.of(result.finishReasonRaw()),
                        stats = statsOf(result.usage(), result.text, req),
                    ),
                )
            }

            // ★ 流断了 ⇒ ERROR 但**内容照常返回**（§4.4「已收到的内容保留」）
            is CloudCallResult.Partial -> {
                lastCompleted = false
                lastRequestId = result.requestId()
                lastReplayTokens = result.usage()?.promptTokens
                    ?: CloudRequestBody.estimateTokens(body)
                Outcome.Ok(
                    GenerationResult(
                        rawText = result.text,
                        toolCalls = emptyList(),
                        finishReason = FinishReason.ERROR,
                        stats = statsOf(result.usage(), result.text, req),
                    ),
                )
            }

            is CloudCallResult.Failed -> Outcome.Err(
                AppError(
                    code = result.failure.code,
                    reasonKey = result.failure.reasonKey,
                    severity = Severity.MEDIUM,
                ),
            )
        }
    }

    /** 云端没有 KV cache ⇒ 报告的是**上一轮的重放体积**（§2.7）。 */
    override suspend fun currentCacheTokens(): Int = lastReplayTokens

    override fun close(): Unit = Unit

    /**
     * ★ 只有 `OutputShape.JsonSchema` 才加 `response_format`，
     *   且**只取 `json_object` 兜底档**（`json_schema` 兼容最差，§2.6）。
     */
    private fun shouldConstrain(req: GenerationRequest): Boolean =
        config.jsonConstraint && req.outputShape is OutputShape.JsonSchema

    private fun statsOf(
        usage: CloudUsage?,
        text: String,
        req: GenerationRequest,
    ): InferenceStats {
        val prefill = usage?.promptTokens ?: CloudRequestBody.estimateTokens(spec.systemInstruction.orEmpty() + req.userTurn)
        val decode = usage?.completionTokens ?: CloudRequestBody.estimateTokens(text)
        val ttft = (clock.nowMillis() - startedAt).toInt().coerceAtLeast(0)
        val tps = if (ttft <= 0) 0.0 else decode.toDouble()
        return InferenceStats(
            prefillTokens = prefill,
            decodeTokens = decode,
            ttftMs = ttft,
            decodeTps = tps,
        )
    }
}

/**
 * ★ finish_reason 原文 → 枚举（§4.4 最后一行）。
 *
 * ★ 映射不到 ⇒ 按 `STOP` 处理（Kimi 等私有值不报错、不丢信息，原文落审计）。
 */
public object FinishReasonCodec {
    public fun of(raw: String?): FinishReason = when (raw?.trim()?.lowercase()) {
        "length" -> FinishReason.LENGTH
        "preempted" -> FinishReason.PREEMPTED
        "error" -> FinishReason.ERROR
        else -> FinishReason.STOP
    }
}
