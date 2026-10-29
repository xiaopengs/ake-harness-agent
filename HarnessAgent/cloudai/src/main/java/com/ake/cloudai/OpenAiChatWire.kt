package com.ake.cloudai

import com.ake.core.modelapi.ConversationTurn
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.TurnRole
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.SystemRoleMode
import com.ake.core.ports.TokenSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// =============================================================================
// OpenAI `/chat/completions` 的请求/响应模型（架构 08 §2.5 / §2.6）
//
// ## ★★ 请求里**永远不出现**的字段
//
//  · `tools`  —— 既有铁律 `automaticToolCalling=false`（它会绕过 `PolicyPort`
//                且不产生 trace）。云端沿用 ⇒ 工具的解析与执行永远在我们手里（§2.6）。
//  · `response_format: {"type":"json_schema"}` —— 各厂商支持度最差（Kimi 部分版本
//    不支持、SiliconFlow 开源模型不支持）。只用 `json_object` 兜底档。
// =============================================================================

@Serializable
internal data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
internal data class ResponseFormatDto(
    val type: String,
)

@Serializable
internal data class StreamOptionsDto(
    @SerialName("include_usage") val includeUsage: Boolean,
)

/**
 * 请求体。★ 字段集合是**故意截断**的：
 * 没有 `tools`，没有 `tool_choice`，没有 `functions`（§2.6）。
 */
@Serializable
internal data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("response_format") val responseFormat: ResponseFormatDto? = null,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
)

@Serializable
internal data class ChatChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
internal data class ChunkChoice(
    val index: Int? = null,
    val delta: ChunkDelta? = null,
    /** ★ 原文。Kimi 等会返回 OpenAI 枚举外的取值 ⇒ 不映射，落审计。 */
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

/** 非流式（`stream=false`）响应体。 */
@Serializable
internal data class ChatCompletionDto(
    val id: String? = null,
    val model: String? = null,
    val choices: List<CompletionChoice> = emptyList(),
    val usage: UsageDto? = null,
    val error: JsonElement? = null,
)

@Serializable
internal data class CompletionChoice(
    val index: Int? = null,
    val message: CompletionMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class CompletionMessage(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
internal data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/** 一次调用结束后的用量真值。★ 只在内存里流转，不落盘、不进日志。 */
internal data class CloudUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val source: TokenSource,
)

/**
 * ★★ 唯一的 messages 组装实现（架构 08 §2.7）。
 *
 * ## 为什么必须只有一份
 *
 * 端侧（[com.ake.core.ports.RehydrationMode.PREFILL_ONCE]）与云端
 * （[com.ake.core.ports.RehydrationMode.REPLAY_EACH_TURN]）的**代价模型相反**：
 *  · 端侧：initialMessages 在 `openSession` 时灌一次建 KV，之后每轮只付增量；
 *  · 云端：**每一轮都要把 system + initialMessages + 本轮全量重放**。
 * ⇒ 若两端各写一份，历史越长，两者对「这一轮到底发了多少字节」的认知差距越大 ——
 *    这正是 `docs/05` §2.2 理由 2 警告的「UI 显示一套预算、主循环跑另一套」。
 *
 * ## ★★ 入参只有三个 —— 没有任何一个能携带 DOM / 网页原文（锁 D）
 *
 * 出口形状也只有两个：[OutboundMessage.System] 与 [OutboundMessage.Turn]，
 * 且 `Turn` 只装 `role` + `text`。**没有第三个变体能装别的东西。**
 */
public object ChatCompletionsMessages {

    /**
     * @param system      `SessionSpec.systemInstruction`
     * @param history     `SessionSpec.initialMessages`（仅此一处来源）
     * @param currentTurn `GenerationRequest.userTurn`
     */
    public fun of(
        system: String?,
        history: List<ConversationTurn>,
        currentTurn: String,
    ): List<OutboundMessage> {
        val out = ArrayList<OutboundMessage>(history.size + 2)

        // ① system：trim 后非空 ⇒ 首条
        val sys = system?.trim().orEmpty()
        if (sys.isNotEmpty()) out += OutboundMessage.System(sys)

        // ② 历史：先丢弃空白 text（多数网关对空 content 直接 400），再合并连续同角色
        val turns = ArrayList<OutboundMessage.Turn>()
        for (turn in history) {
            val candidate = OutboundMessage.Turn(turn.role, turn.text)
            if (candidate.text.isBlank()) continue
            val last = turns.lastOrNull()
            if (last != null && last.role == candidate.role) {
                turns[turns.size - 1] = last.copy(text = "${last.text}\n${candidate.text}")
            } else {
                turns += candidate
            }
        }

        // ③ 首条必须是 system 或 user。system 为空而首条是 assistant ⇒ 丢弃该轮
        var start = 0
        if (sys.isEmpty() && turns.firstOrNull()?.role == TurnRole.MODEL) start = 1
        for (i in start until turns.size) out += turns[i]

        // ④ 本轮 user：非空才追加
        if (currentTurn.isNotBlank()) out += OutboundMessage.Turn(TurnRole.USER, currentTurn)

        return out
    }

    /** ★ 出口只有这两个形状。**没有第三个变体能装别的东西**。 */
    public sealed interface OutboundMessage {
        public data class System(val text: String) : OutboundMessage

        public data class Turn(val role: TurnRole, val text: String) : OutboundMessage
    }
}

/** 组装请求体。★ 全仓只有这一处构造 `/chat/completions` 的 body。 */
internal object CloudRequestBody {

    public fun build(
        cfg: EndpointConfig,
        spec: SessionSpec,
        currentTurn: String,
        maxOutputTokens: Int?,
        stream: Boolean,
        jsonConstraint: Boolean,
    ): String {
        val messages = ChatCompletionsMessages.of(
            system = spec.systemInstruction,
            history = spec.initialMessages,
            currentTurn = currentTurn,
        ).map { it.toDto() }

        val request = ChatRequestDto(
            model = cfg.modelName,
            messages = messages,
            stream = stream,
            temperature = spec.temperature,
            topP = spec.topP,
            maxTokens = maxOutputTokens ?: spec.maxOutputTokens,
            responseFormat = if (jsonConstraint) ResponseFormatDto(TYPE_JSON_OBJECT) else null,
            streamOptions = if (stream) StreamOptionsDto(includeUsage = true) else null,
        )
        return WireJson.encodeToString(request)
    }

    private fun ChatCompletionsMessages.OutboundMessage.toDto(): ChatMessageDto = when (this) {
        is ChatCompletionsMessages.OutboundMessage.System ->
            ChatMessageDto(role = ROLE_SYSTEM, content = text)

        is ChatCompletionsMessages.OutboundMessage.Turn ->
            ChatMessageDto(
                role = when (role) {
                    TurnRole.USER -> ROLE_USER
                    TurnRole.MODEL -> ROLE_ASSISTANT
            },
                content = text,
            )
    }

    public const val TYPE_JSON_OBJECT: String = "json_object"
    public const val ROLE_SYSTEM: String = "system"
    public const val ROLE_USER: String = "user"
    public const val ROLE_ASSISTANT: String = "assistant"

    /** `developer` 供 OpenAI o 系列等要求该角色的网关使用（按 `capabilities.systemRole`）。 */
    public fun systemRoleName(mode: SystemRoleMode): String = when (mode) {
        SystemRoleMode.SEPARATE_FIELD -> ROLE_SYSTEM
        SystemRoleMode.IN_MESSAGES -> "developer"
    }

    /**
     * 估算一段文本在**云端**侧的 token 数（§2.8 ③）。
     *
     * ★ 契约是「必须快、禁止跑模型」⇒ 不能为了估算去问服务端。
     *   这里用字符数 / 3，再乘保守系数 1.15（各家 tokenizer 与中英混排差异）。
     *   服务端 `usage.prompt_tokens` 回来后再用真值校准。
     */
    public fun estimateTokens(text: String): Int {
        val base = ((text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN).coerceAtLeast(1)
        return (base * CLOUD_ESTIMATE_FACTOR).toInt().coerceAtLeast(1)
    }

    public const val CHARS_PER_TOKEN: Int = 3
    public const val CLOUD_ESTIMATE_FACTOR: Double = 1.15
}

internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
