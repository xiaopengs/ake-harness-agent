package com.ake.cloudai

import com.ake.core.ports.CloudFailure
import com.ake.core.ports.TokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

// =============================================================================
// 单次云端调用的执行器（架构 08 §4.5）
//
// ## ★ 它**不做重试**
//
// 重试次数与 permit 的关系由 `:inference` 的 `FallbackPolicy` / `EgressCoordinator` 管。
// 原因：「每一次重试都是一个**独立的出境** ⇒ 每一次都要一张新 permit + 一行新审计行」
// （§4.4 429 行）。把重试封在传输层内部，permit 计数就会与实际出境次数对不上 ——
// 那恰好是 §9.4 交叉验证要抓的东西。
// =============================================================================

internal sealed interface CloudCallResult {

    public fun deliveredBytes(): Int = when (this) {
        is Delivered -> deliveredBytes
        is Partial -> deliveredBytes
        is Failed -> deliveredBytes
    }

    public fun requestId(): String? = when (this) {
        is Delivered -> requestId
        is Partial -> requestId
        is Failed -> requestId
    }

    public fun finishReasonRaw(): String? = when (this) {
        is Delivered -> finishReasonRaw
        is Partial -> failure.code    // ★ 只带机器码，不带服务端原文
        is Failed -> failure.code
    }

    public fun usage(): CloudUsage? = when (this) {
        is Delivered -> usage
        is Partial -> null
        is Failed -> null
    }
    /** 完整收到 `data: [DONE]`。 */
    public data class Delivered(
        val text: String,
        val finishReasonRaw: String?,
        val usage: CloudUsage?,
        val requestId: String?,
        val deliveredBytes: Int,
    ) : CloudCallResult

    /** 流断了，但已收到的内容**保留**（§4.4：已收文本照常落盘）。 */
    public data class Partial(
        val text: String,
        val failure: CloudFailure,
        val requestId: String?,
        val deliveredBytes: Int,
    ) : CloudCallResult

    /** POST 阶段失败，且一个字都没收到。 */
    public data class Failed(
        val failure: CloudFailure,
        val requestId: String?,
        val deliveredBytes: Int,
    ) : CloudCallResult
}

internal class CloudChatRunner(private val transport: CloudTransport) {

    /**
     * 发一次 `/chat/completions`（流式），把**新增片段**逐个交给 [onDelta]。
     *
     * ★ 语义 ①：`onDelta` 在**调用方的协程上下文**里被调用 ——
     *   网络读循环跑在 ktor 的 IO dispatcher 上，事件经 `channelFlow` 搬回本协程后再派发。
     * ★ 语义 ②：只投非空的**新增**片段（首个只有 `delta.role` 的 chunk 不投）。
     * ★ 取消：`CancellationException` 原样透传，且响应体被真正关闭
     *   （断开 TCP ⇒ 服务端停止生成），而不是只停止收集（§2.5）。
     */
    public suspend fun runOnce(
        request: CloudRequest,
        onDelta: suspend (String) -> Unit,
    ): CloudCallResult {
        val decoder = SseDecoder()
        var outcome: CloudTransportOutcome = CloudTransportOutcome.ConnectFailed
        val text = StringBuilder()
        var finishReasonRaw: String? = null
        var usage: CloudUsage? = null
        var malformed: String? = null
        var sawDone = false

        try {
            channelFlow<SseEvent> {
                outcome = transport.stream(request) { bytes ->
                    decoder.push(bytes).forEach { send(it) }
                }
                // ★ 流结束：flush 残行；若从未见过 [DONE] ⇒ 产出 EOF_WITHOUT_DONE 标记
                decoder.finish().forEach { send(it) }
            }.collect { ev ->
                when (ev) {
                    is SseEvent.Delta -> {
                        text.append(ev.text)
                        onDelta(ev.text)
                    }

                    is SseEvent.Finish -> finishReasonRaw = ev.reasonRaw
                    is SseEvent.Usage -> {
                        usage = CloudUsage(ev.promptTokens, ev.completionTokens, TokenSource.REPORTED)
                    }

                    is SseEvent.Done -> sawDone = true
                    is SseEvent.EmptyChunk -> Unit
                    is SseEvent.Malformed ->
                        if (ev.shape != SseDecoder.SHAPE_EOF_WITHOUT_DONE && malformed == null) {
                            malformed = ev.shape
                        }
                }
            }
        } catch (t: Throwable) {
            // ★ 取消原样透传，不包成 Outcome.Err（§2.5）
            if (t is CancellationException) throw t
            return CloudCallResult.Failed(CloudFailure.ConnectFailed, null, 0)
        }

        // ★ 局部变量快照：`outcome` 被 channelFlow 闭包写入 ⇒ 直接 smart cast 不成立
        val settled: CloudTransportOutcome = outcome
        val bytes = if (settled is CloudTransportOutcome.ConnectFailed) 0 else request.bodyBytes()
        val requestId = decoder.requestId

        return when (settled) {
            CloudTransportOutcome.Delivered -> when {
                // 畸形载荷（含 2xx 但内容是 HTML / 坏 JSON）
                malformed != null -> CloudCallResult.Failed(CloudFailure.MalformedResponse, requestId, bytes)
                // ★★ 空 delta ⇒ FAILED，且**禁止**用本地补一段（§4.4 倒数第 4 行）
                text.isEmpty() -> CloudCallResult.Failed(CloudFailure.EmptyDelta, requestId, bytes)
                else -> CloudCallResult.Delivered(
                    text = text.toString(),
                    finishReasonRaw = finishReasonRaw,
                    usage = usage,
                    requestId = requestId,
                    deliveredBytes = bytes,
                )
            }

            is CloudTransportOutcome.HttpStatus ->
                CloudCallResult.Failed(CloudFailureMapper.ofStatus(settled), requestId, bytes)

            is CloudTransportOutcome.ConnectFailed ->
                CloudCallResult.Failed(CloudFailure.ConnectFailed, requestId, 0)

            CloudTransportOutcome.FirstByteTimeout ->
                CloudCallResult.Failed(CloudFailure.FirstByteTimeout, requestId, bytes)

            CloudTransportOutcome.StreamIdleTimeout ->
                halt(text, CloudFailure.StreamIdleTimeout, requestId, bytes)

            CloudTransportOutcome.DeadlineExceeded ->
                halt(text, CloudFailure.DeadlineExceeded, requestId, bytes)

            CloudTransportOutcome.Truncated ->
                halt(text, CloudFailure.StreamTruncated, requestId, bytes)
        }
    }

    /** 已收到内容 ⇒ PARTIAL（内容保留）；一个字都没有 ⇒ FAILED。 */
    private fun halt(
        text: StringBuilder,
        failure: CloudFailure,
        requestId: String?,
        bytes: Int,
    ): CloudCallResult = if (text.isEmpty()) {
        CloudCallResult.Failed(failure, requestId, bytes)
    } else {
        CloudCallResult.Partial(text.toString(), failure, requestId, bytes)
    }
}
