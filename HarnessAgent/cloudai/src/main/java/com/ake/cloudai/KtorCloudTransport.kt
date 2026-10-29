package com.ake.cloudai

import com.ake.core.ports.TlsMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// =============================================================================
// ktor / OkHttp 传输实现
//
// ## 超时三段必须分开配置（§4.4）—— 因为它们归属的 phase 不同
//
// | 超时                  | 默认   | phase |
// |---|---|---|
// | `connectTimeoutMs`    | 8 s    | PRE   |（含 DNS 与 TCP 与 TLS 握手）
// | `firstByteTimeoutMs` | 15 s   | POST  |（请求体写完 → 收到第一个 SSE 字节）
// | `streamIdleTimeoutMs`| 30 s   | POST  |（流中两个 chunk 之间的最大间隔）
// | `deadlineMs`          | 调用方给 | POST |
//
// ⇒ ktor 自带的 `requestTimeout` / `socketTimeout` 一律设为 `INFINITE`：
//    总时长与空闲判定由我们自己在读写循环上掐，否则「首字节超时」会被 ktor 的
//    socketTimeout 抢先判成「连接失败」⇒ **phase 从 POST 退化成 PRE** ⇒
//    那正是 §4.3 点名的「最容易搞错的」那一条。
//
// ## ★★ 秘密的边界（§6.3）
//
//  · `Authorization` 头**只在本文件的 [CloudTransport.stream] 实现里被拼装一次**；
//  · 它**不进任何返回值**、**不进任何 data class**、**不进日志**；
//  · ktor 的异常（[Throwable.message] 常含完整 URL）**一律不外带** ⇒
//    `CloudFailure` 刻意没有 `cause` 字段。
// =============================================================================

internal class KtorCloudTransport(
    engine: io.ktor.client.engine.HttpClientEngine = OkHttp.create(),
    private val defaultConnectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val defaultFirstByteTimeoutMs: Long = DEFAULT_FIRST_BYTE_TIMEOUT_MS,
    private val defaultStreamIdleTimeoutMs: Long = DEFAULT_STREAM_IDLE_TIMEOUT_MS,
) : CloudTransport {

    private val client = HttpClient(engine) {
        install(HttpTimeout) {
            connectTimeoutMillis = defaultConnectTimeoutMs
            // ★★ 总时长与空闲判定由我们自己在读写循环上掐（见本文件头部注释）。
            //    交给 ktor 的 request/socket timeout，会把「首字节超时」误判成「连接失败」。
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
        }
        expectSuccess = false
    }

    override suspend fun stream(
        req: CloudRequest,
        onBytes: suspend (ByteArray) -> Unit,
    ): CloudTransportOutcome = try {
        client.prepareRequest(req.url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            // ★★ 全仓唯一一处把明文密钥取出来的地方（D4 断言盯住）
            req.secret.useAsBearer { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(req.body)
        }.execute { response ->
            consume(req, response, onBytes)
        }
    } catch (t: Throwable) {
        // ★ 取消原样透传，不包成 Outcome.Err（§2.5）
        if (t is CancellationException) throw t
        // ★★ 绝不外带 `t` 本身 —— ktor 的 message 里可能有带 key 的完整 URL（§6.3 ⑤）
        CloudTransportOutcome.ConnectFailed
    }

    private suspend fun consume(
        req: CloudRequest,
        response: HttpResponse,
        onBytes: suspend (ByteArray) -> Unit,
    ): CloudTransportOutcome {
        val status = response.status.value
        if (status !in 200..299) {
            return CloudTransportOutcome.HttpStatus(
                status = status,
                retryAfterMs = parseRetryAfter(response.headers[HttpHeaders.RetryAfter]),
                contentType = response.headers[HttpHeaders.ContentType],
                bodyHint = null,   // ★ 错误体原文不外带；需要机器码时再按需抽
            )
        }
        if (!isEventStream(response)) {
            // 2xx 但不是 SSE ⇒ 当成畸形（多数是网关返回了 HTML 页面）
            return CloudTransportOutcome.Truncated
        }

        val channel: ByteReadChannel = response.bodyAsChannel()
        val buf = ByteArray(READ_BUFFER_BYTES)
        var sawAnyByte = false

        while (!channel.isClosedForRead) {
            val timeout = if (sawAnyByte) req.streamIdleTimeoutMs else req.firstByteTimeoutMs
            val n = withTimeoutOrNull(timeout) { channel.readAvailable(buf, 0, buf.size) }

            when {
                n == null -> return if (sawAnyByte) {
                    CloudTransportOutcome.StreamIdleTimeout
                } else {
                    CloudTransportOutcome.FirstByteTimeout   // ★ POST，不是 PRE
                }

                n <= 0 -> break
                else -> {
                    sawAnyByte = true
                    onBytes(buf.copyOf(n))
                }
            }
        }
        return CloudTransportOutcome.Delivered
    }

    private fun isEventStream(response: HttpResponse): Boolean {
        val ct = response.headers[HttpHeaders.ContentType]?.lowercase().orEmpty()
        return ct.contains("text/event-stream") || ct.contains("application/x-ndjson") || ct.isEmpty()
    }

    /** `Retry-After` 支持秒与 HTTP-date 两种；解析不了 ⇒ null（不猜）。 */
    internal fun parseRetryAfter(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return it * 1000L }
        return runCatching {
            java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - System.currentTimeMillis()
        }.getOrNull()?.coerceAtLeast(0L)
    }

    public fun close(): Unit = client.close()

    public companion object {
        public const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 8_000L
        public const val DEFAULT_FIRST_BYTE_TIMEOUT_MS: Long = 15_000L
        public const val DEFAULT_STREAM_IDLE_TIMEOUT_MS: Long = 30_000L
        private const val READ_BUFFER_BYTES: Int = 8 * 1024
    }
}
