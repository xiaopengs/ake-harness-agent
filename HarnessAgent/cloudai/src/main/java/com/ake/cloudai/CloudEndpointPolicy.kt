package com.ake.cloudai

import com.ake.core.common.Hosts
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.TlsMode

// =============================================================================
// 云端端点：解析 / 拼接 / 安全判定（架构 08 §6.5）
//
// ## ★ 这三件事各只有**一份**实现
//
// ```
// ① 唯一归一化：Hosts.normalise(baseUrl)                  ← :core:common，全仓唯一
// ② 唯一拼接：  CloudEndpoints.chatCompletionsUrl(baseUrl) ← 本文件
// ③ 唯一安全判定：CloudEndpointPolicy.check(...)           ← 本文件
// ```
//
// 由 `:inference` 的 `EndpointUniquenessTest`（扫全仓，断言份数）机械盯住。
// 理由直接来自 `Hosts.kt` 头部注释：**「实现的份数要被机械地盯住，而不是靠注释担保。」**
// 这是本次唯一一处「不这么做就会重演那个真实安全 bug」的地方 ——
// 域名黑名单被大写/尾点绕过的根因就是多份不等价实现；
// 现在多了「云端端点」这个新的安全判定面，份数必须同样被盯住。
// =============================================================================

public object CloudEndpoints {

    public const val CHAT_COMPLETIONS_PATH: String = "/chat/completions"

    /**
     * 端点解析结果。
     *
     * ★ **不含密钥**，且 [host] 已过 [Hosts.normalise] ⇒ 可安全进日志与审计表。
     * ⚠ [raw] 与 [url] **含完整 URL**（可能带 query 里的 key）⇒ **禁止对外、禁止落盘、禁止 log**。
     */
    public data class Endpoint internal constructor(
        val scheme: String,
        val host: String,
        val path: String,
        val url: String,
    )

    /**
     * 解析 BaseURL。
     *
     * ★ fail-closed：拿不到 scheme / host ⇒ 返回 `null`（调用方必须按「不出网」处理）。
     */
    public fun parse(baseUrl: String): Endpoint? {
        val raw = baseUrl.trim()
        val marker = raw.indexOf("://")
        if (marker <= 0) return null
        val scheme = raw.substring(0, marker).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val rest = raw.substring(marker + 3)
        val authorityEnd = indexOfAny(rest, charArrayOf('/', '?', '#'))
        val pathRaw = if (authorityEnd < 0) "" else rest.substring(authorityEnd)

        val host = Hosts.normalise(raw)          // ★ 唯一归一化实现
        if (host.isEmpty()) return null

        val path = normalizeChatPath(pathRaw)
        return Endpoint(
            scheme = scheme,
            host = host,
            path = path,
            url = "$scheme://" + raw.substring(marker + 3).substringBefore('#').let { authorityAndPath ->
                val authority = authorityAndPath.substringBefore('/').substringBefore('?')
                authority + path
            },
        )
    }

    /**
     * ★ 唯一拼接。**幂等**：已经以 `/chat/completions` 结尾 ⇒ 不重复加。
     *
     * 各家的路径前缀不一致（DeepSeek `…/v1`、通义 `…/compatible-mode/v1`、
     * 智谱 `…/api/paas/v4`、Kimi `…/v1`），用户填的可能已含 `/v1` 也可能不含。
     * ⇒ 本函数**刻意不去猜厂商前缀** —— 猜 = 第二份且必然 diverge 的实现
     * （正是 `Hosts.kt` 那个真实 bug 的成因）。
     */
    public fun chatCompletionsUrl(baseUrl: String): String = parse(baseUrl)?.url.orEmpty()

    /** ★ 唯一允许对外暴露的端点标识。 */
    public fun hostOf(baseUrl: String): String = Hosts.normalise(baseUrl)

    /**
     * 路径归一：去掉尾斜杠与 query/fragment；若尚未以 `/chat/completions` 结尾 ⇒ 补上。
     *
     * ⚠ query 里的 key 必须被丢掉 —— 它只是「拼接用的残留」，
     *   而秘密的收纳口在 `:cloudai` 内部的 Authorization 头，不在这里。
     */
    private fun normalizeChatPath(pathRaw: String): String {
        var p = pathRaw.substringBefore('?').substringBefore('#')
        while (p.endsWith('/')) p = p.dropLast(1)
        if (p.endsWith(CHAT_COMPLETIONS_PATH)) return p
        return p + CHAT_COMPLETIONS_PATH
    }

    private fun indexOfAny(s: String, chars: CharArray): Int {
        for (i in s.indices) if (s[i] in chars) return i
        return -1
    }

    /** 便于测试与审计：不带 query 的可打印端点标识。 */
    public fun displayOf(baseUrl: String): String {
        val e = parse(baseUrl) ?: return ""
        return "${e.host}${e.path}"
    }
}

/**
 * ★ 唯一的云端端点**安全判定**。
 *
 * 规则（§6.5 ③）：
 *  · `https` ⇒ 通过；
 *  · `http`  ⇒ 仅当 `tlsMode = ALLOW_INSECURE_LAN` **且** host 属于
 *              RFC1918 / `.local` / loopback 时才通过；
 *  · 其余（无 scheme、host 为空、非 http(s) 协议）⇒ 拒绝。
 *
 * ★ 拒绝发生在**发任何字节之前** ⇒ phase 恒为 PRE。
 */
public object CloudEndpointPolicy {

    public sealed interface Verdict {
        /** 允许发请求。 */
        public data object Allow : Verdict

        /** 拒绝。★ [CloudFailure.phase] 恒为 PRE ⇒ 允许回落本地。 */
        public data class Refuse(val failure: CloudFailure) : Verdict
    }

    /**
     * @param scheme 小写后的协议名（`http` / `https` / 其它）
     * @param host   **必须**是 [Hosts.normalise] 的输出
     */
    public fun check(scheme: String, host: String, tlsMode: TlsMode): Verdict {
        if (scheme != "http" && scheme != "https") {
            return Verdict.Refuse(CloudFailure.InsecureEndpoint)
        }
        if (host.isEmpty()) {
            return Verdict.Refuse(CloudFailure.InsecureEndpoint)
        }
        if (scheme == "https") return Verdict.Allow
        // 走到这里 ⇒ http
        return if (tlsMode == TlsMode.ALLOW_INSECURE_LAN && isLanHost(host)) {
            Verdict.Allow
        } else {
            Verdict.Refuse(CloudFailure.InsecureEndpoint)
        }
    }

    /**
     * LAN 判定。★ 只认 RFC1918 / `.local` / loopback / link-local，
     * 其余一律按「公网 http」处理 ⇒ 拒绝。
     */
    public fun isLanHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local")) return true
        if (h == "::1" || h.startsWith("fd") || h.startsWith("fe80")) return true

        // IPv4 点分十进制
        val parts = h.split('.')
        if (parts.size == 4) {
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            if (octets[0] == 10) return true
            if (octets[0] == 127) return true
            if (octets[0] == 192 && octets[1] == 168) return true
            if (octets[0] == 172 && octets[1] in 16..31) return true
            if (octets[0] == 169 && octets[1] == 254) return true
        }
        return false
    }
}
