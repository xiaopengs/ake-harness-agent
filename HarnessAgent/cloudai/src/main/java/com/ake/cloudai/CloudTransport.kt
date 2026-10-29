package com.ake.cloudai

import com.ake.core.ports.CloudFailure

// =============================================================================
// HTTP 传输接缝（架构 08 §4.5）
//
// ## 为什么要有这个接口
//
// `:cloudai` 里真正需要被毫秒级单测的是**协议与失败分相**，
// 而不是 OkHttp 本身。把「发字节」这一层抽成接口 ⇒
// 单测可以用脚本化的字节流喂畸形 SSE（粘包 / 半行 / 缺 `[DONE]` / 空 delta / 超长行），
// 既不需要真机，也不引入 `ktor-client-mock` 这个**新坐标**（红线 2）。
//
// ## 重试的分工（§4.5）
//
//  · `:cloudai` 提供**单次**调用，并如实报告「发生了什么」；
//  · `:inference` 决定「要怎么办」（重试几次 / 是否回落本地），
//    并且**每次重试都必须是一张新的 permit + 一行新的审计行**（§4.4 429 行）。
// =============================================================================

/**
 * 一次云端请求。
 *
 * ★ [secret] 是 `PlainSecret`（`toString()` 恒为 `***`）⇒ 它进日志也只是 `***`。
 * ★★ 但它**必须只在 `KtorCloudTransport.stream()` 里被 `useAsBearer` 打开一次**
 *    （由 D4 断言扫全仓盯住）。
 */
internal data class CloudRequest(
    val url: String,
    val secret: com.ake.core.ports.PlainSecret,
    val body: String,
    val connectTimeoutMs: Long,
    val firstByteTimeoutMs: Long,
    val streamIdleTimeoutMs: Long,
    /** 总 deadline。★ 契约是「超时由调用方管理，引擎不自行判断」⇒ 这里只是兜底。 */
    val deadlineMs: Long,
) {
    /** 请求体字节数 = 出境字节数下界（不含 HTTP 头）。★ 只在真正发出去之后才算数。 */
    public fun bodyBytes(): Int = body.toByteArray(Charsets.UTF_8).size
}

/**
 * 单次调用的终态。**只描述「发生了什么」，不含任何策略判断。**
 */
internal sealed interface CloudTransportOutcome {

    /** 完整收到 `data: [DONE]`。 */
    public data object Delivered : CloudTransportOutcome

    /**
     * HTTP 非 2xx。
     *
     * ★ **请求体已经发出去了** ⇒ phase 恒为 POST（§4.3）。
     *   包括 401/403 —— 服务端必须先收到请求才能拒绝它。
     *
     * @param contentType 只留 content-type（用于识别「返回了 HTML 错误页」）；
     *                    ★★ 响应体原文**不在这里**。
     * @param bodyHint 由 `:cloudai` 内部从响应体里**只抽出机器码**（如 `model_not_found`）
     *                 后即刻丢弃原文；非 2xx 的 `error.message` **绝不外带**。
     */
    public data class HttpStatus(
        val status: Int,
        val retryAfterMs: Long?,
        val contentType: String?,
        val bodyHint: CloudErrorCodeHint?,
    ) : CloudTransportOutcome

    /** DNS / TCP / TLS 失败 ⇒ 一个字节都没发出去。PRE。 */
    public data object ConnectFailed : CloudTransportOutcome

    /** 请求体已写完，但迟迟不见第一个字节。★ POST。 */
    public data object FirstByteTimeout : CloudTransportOutcome

    /** 流中两个 chunk 间隔过久。POST。 */
    public data object StreamIdleTimeout : CloudTransportOutcome

    /** 总 deadline 到了。POST（已收部分照常保留）。 */
    public data object DeadlineExceeded : CloudTransportOutcome

    /** EOF 但从未见过 `data: [DONE]`。POST（已收部分照常保留）。 */
    public data object Truncated : CloudTransportOutcome
}

/** ★ 从响应体里抽出的**机器码**（不是人类可读消息）。 */
public enum class CloudErrorCodeHint {
    MODEL_NOT_FOUND,
    CONTEXT_LENGTH_EXCEEDED,
    INVALID_API_KEY,
    UNKNOWN,
}

internal interface CloudTransport {
    /**
     * 发一次请求，把响应体的字节增量交给 [onBytes]。
     *
     * ★ 取消：调用方协程被取消 ⇒ 实现必须**真正关闭响应体**（断开 TCP ⇒ 服务端停止生成），
     *   而不是只停止收集 —— 只停收集会留下一个后台还在烧钱、
     *   还在把用户文本留在服务端缓冲里的请求（§2.5）。
     *   `CancellationException` 原样透传。
     */
    public suspend fun stream(
        req: CloudRequest,
        onBytes: suspend (ByteArray) -> Unit,
    ): CloudTransportOutcome
}

/**
 * ★ HTTP 状态 / 传输结果 → [CloudFailure] 的**唯一**映射（协议层）。
 *
 * 「失败之后怎么办」是 `:inference` 的 [com.ake.inference.FallbackPolicy]，不在这里。
 */
internal object CloudFailureMapper {

    public fun of(outcome: CloudTransportOutcome): CloudFailure = when (outcome) {
        is CloudTransportOutcome.Delivered -> error("Delivered 不是失败形态")
        is CloudTransportOutcome.ConnectFailed -> CloudFailure.ConnectFailed
        is CloudTransportOutcome.FirstByteTimeout -> CloudFailure.FirstByteTimeout
        is CloudTransportOutcome.StreamIdleTimeout -> CloudFailure.StreamIdleTimeout
        is CloudTransportOutcome.DeadlineExceeded -> CloudFailure.DeadlineExceeded
        is CloudTransportOutcome.Truncated -> CloudFailure.StreamTruncated
        is CloudTransportOutcome.HttpStatus -> ofStatus(outcome)
    }

    public fun ofStatus(http: CloudTransportOutcome.HttpStatus): CloudFailure {
        // ★ HTML 错误页 / 非 JSON ⇒ 畸形响应（§4.4）
        val isHtml = http.contentType?.contains("text/html", ignoreCase = true) == true
        if (isHtml) return CloudFailure.MalformedResponse

        return when (http.status) {
            400 -> when (http.bodyHint) {
                CloudErrorCodeHint.CONTEXT_LENGTH_EXCEEDED -> CloudFailure.ContextTooLong
                CloudErrorCodeHint.MODEL_NOT_FOUND -> CloudFailure.ModelNotFound
                else -> CloudFailure.MalformedResponse
            }

            404 -> CloudFailure.ModelNotFound
            401, 403 -> CloudFailure.AuthRejected
            407 -> CloudFailure.ProxyAuthRequired
            429 -> CloudFailure.RateLimited
            in 500..599 -> CloudFailure.ServerError
            else -> CloudFailure.MalformedResponse
        }
    }
}
