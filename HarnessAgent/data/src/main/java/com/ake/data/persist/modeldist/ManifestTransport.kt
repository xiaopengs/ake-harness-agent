package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * manifest 的**传输接缝**。
 *
 * ## 为什么抽这一层（而不是在 `ModelManifestSource` 里直接 `URL(...).openConnection()`）
 *
 *  1. **可测**：JVM 单测没有网络，而「拉到了但验签不过」这类分支恰恰是最该被测到的。
 *     抽成接口后，`ManifestTransports.fixture(...)` 一行就能喂任意字节。
 *  2. **可换**：今天用 `HttpURLConnection`，明天要换成带证书钉扎的 OkHttp / 走代理，
 *     只改这个文件，不动验签与缓存逻辑。
 *  3. **边界清晰**：**接口只负责「取回字节」，不负责判断字节对不对** ——
 *     判断归 `ModelManifestParser`。两条职责混在一个函数里，
 *     就会出现「空body 也算成功」这种只在生产才暴露的洞。
 *
 * ## 为什么不用 OkHttp / Ktor
 *
 * `:data` 当前是纯 Room + 文件模块，为一个 30 KB 的 JSON 拉一条 OkHttp 依赖进来
 * 会把连接池、拦截器栈一起带进这个模块。manifest 一天拉一次、失败就退缓存，
 * `HttpURLConnection` 完全够用；等真需要 HTTP/2 或代理链时再换（换点只有这一处）。
 */
public fun interface ManifestTransport {
    /**
     * 取回 manifest 原始文本。
     *
     * @return 失败一律 [Outcome.Err]，**不抛异常** —— 抛异常会让「网络挂了」
     *   与「manifest 内容坏了」在调用方混成同一种东西，而它们的处理完全不同
     *   （前者退缓存，后者必须拒绝）。
     */
    public fun fetch(url: String): Outcome<String>
}

public object ManifestTransports {

    /** 本地 / 测试用：恒返回固定文本。用于「缓存与网络同源」类断言。 */
    public fun fixture(raw: String): ManifestTransport = ManifestTransport { Outcome.Ok(raw) }

    /** 测试用：恒失败。用于「网络挂了必须退缓存、且不能污染缓存」。 */
    public fun failing(
        code: String = ModelDistErrorCodes.MANIFEST_FETCH_FAILED,
        reasonKey: String = "err_manifest_fetch_failed",
    ): ManifestTransport = ManifestTransport {
        Outcome.Err(AppError(code, reasonKey, null, Severity.MEDIUM))
    }

    /** 测试用：按次数回放一串结果，用尽后重复最后一个。用于「刷新节奏」类断言。 */
    public fun scripted(vararg results: Outcome<String>): ManifestTransport {
        val list = results.toList()
        var n = 0
        return ManifestTransport { list[(n++).coerceAtMost(list.lastIndex)] }
    }

    /**
     * 真·HTTPS 传输。
     *
     * @param maxBytes 响应体上限。**这不是性能优化，是安全边界**：
     *   manifest 是几十 KB 的配置，一个几 MB 的响应只可能是劫持或服务端配错；
     *   不设上限的话，攻击者可以用一个巨大的 body 把进程内存吃穿（端侧 R1 风险）。
     */
    public fun http(
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): ManifestTransport = HttpManifestTransport(connectTimeoutMs, readTimeoutMs, maxBytes)

    public const val CONNECT_TIMEOUT_MS: Int = 15_000
    public const val READ_TIMEOUT_MS: Int = 30_000
    public const val DEFAULT_MAX_BYTES: Int = 1 shl 20 // 1 MiB
}

internal class HttpManifestTransport(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val maxBytes: Int,
) : ManifestTransport {

    override fun fetch(url: String): Outcome<String> {
        // ★ 与解析器里那条 `url 必须 https` 是同一条规则的两道闸：
        //   配置可能来自运维下发的串，不能指望它一定是 https。
        if (!url.startsWith("https://")) {
            return Outcome.Err(
                AppError(
                    ModelDistErrorCodes.MANIFEST_FETCH_FAILED,
                    "err_manifest_url_not_https",
                    null,
                    Severity.HIGH,
                ),
            )
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // ★ 不跟随重定向：manifest 的 URL 是钉死的信任边界，
                //   一次 302 就能把它带到另一个域（甚至 http）去；
                //   这里让 3xx 落到下面的 `else` 分支当失败处理，由运维把 URL 改对。
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                // ★ 不让任何中间缓存替我们决定「是不是最新」：manifest 是安全相关文件，
                //   缓存层一旦把它 TTL 拉长，服务端紧急回滚就到不了端上。
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            conn.connect()

            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_MODIFIED ->
                    return Outcome.Err(
                        AppError(
                            ModelDistErrorCodes.MANIFEST_NOT_MODIFIED,
                            "err_manifest_not_modified",
                            null,
                            Severity.LOW,
                        ),
                    )
                else ->
                    return Outcome.Err(
                        AppError(
                            ModelDistErrorCodes.MANIFEST_FETCH_FAILED,
                            "err_manifest_fetch_failed",
                            IllegalStateException("HTTP $code for $url"),
                            Severity.MEDIUM,
                        ),
                    )
            }

            val body = readCapped(conn)
                ?: return Outcome.Err(
                    AppError(
                        ModelDistErrorCodes.MANIFEST_TOO_LARGE,
                        "err_manifest_too_large",
                        IllegalStateException("manifest exceeds $maxBytes bytes"),
                        Severity.HIGH,
                    ),
                )
            Outcome.Ok(body)
        } catch (t: Throwable) {
            Outcome.Err(
                AppError(
                    ModelDistErrorCodes.MANIFEST_FETCH_FAILED,
                    "err_manifest_fetch_failed",
                    t,
                    Severity.MEDIUM,
                ),
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 读到上限为止；超限返回 `null`。
     *
     * 不用 `conn.contentLength` 先判断：那个头是可以撒谎的（chunked 响应下甚至是 -1），
     * 真正的边界只能在**读的时候**把住。
     */
    private fun readCapped(conn: HttpURLConnection): String? {
        val out = ByteArrayOutputStream(DEFAULT_BUFFER)
        conn.inputStream.use { input ->
            val buf = ByteArray(DEFAULT_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (out.size() + n > maxBytes) return null
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val DEFAULT_BUFFER: Int = 8 * 1024
        const val USER_AGENT: String = "ake-harness-agent"
    }
}
