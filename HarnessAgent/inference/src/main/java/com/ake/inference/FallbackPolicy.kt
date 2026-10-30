package com.ake.inference

import com.ake.core.ports.AnnouncementKind
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.FallbackAnnouncement

// =============================================================================
// ★★ 降级判定 —— **全仓只有这一份**（架构 08 §4.4 / §4.5）
//
// ## 裁决（§4.2）：禁止静默回落
//
// > **云端失败时禁止「静默回落本地」。**
// > 分界线**不是错误类型**，而是**字节有没有发出去**。
//
// 三条理由（按严重性）：
//  ① 用户心智被破坏：用户看到的终态是「本地完成了」。若字节已出境，这句话是**假的**，
//     而且**用户没有任何办法发现它是假的**。这比「云端失败了报错」糟一个数量级。
//  ② 它会腐蚀审计：静默回落会产生一条 `decided=CLOUD_ASSISTED`、
//     用户却以为没出境的 `route_decision` 行。半年后没人能回答为什么。
//  ③ `docs/05` §6.2 裁决 4（「云不可用时自动降级 LOCAL」）的前提是
//     「云 = 我们自己的服务」。现在云是**用户自己填的第三方端点**，
//     我们也不知道它把数据留了多久。前提变了，结论必须跟着变。
//
// ## ★ 最容易搞错的一条
//
// **「首字节超时」是 POST，不是 PRE。** 请求体早就发完了，服务端只是没开始吐字。
// 很多人会本能地把它当「没连上」而回落本地 —— 那一刻用户的整段历史
// 已经在对方的服务器上了。
//
// 本对象是**纯函数**（`CloudFailure -> FallbackAction`），无状态、无 IO
// ⇒ 每一种失败形态都能在 JVM 上毫秒级断言（§9.4）。
// =============================================================================

public object FallbackPolicy {

    public enum class FallbackAction {
        /**
         * 同一端点重试。
         *
         * ★★ **每次重试都是一个独立的出境** ⇒
         *    每次都要**新 permit + 新审计行**（`retry_of` 指向上一行，§4.4 429 行）。
         */
        RETRY_SAME_ENDPOINT,

        /**
         * 回落到端侧推理。★ **只有 PRE 阶段才允许**，且必须告知用户。
         */
        FALLBACK_TO_DEVICE,

        /**
         * 大声失败。★ **POST 阶段的任何失败都必须走这条**，
         * 且 UI 上必须出现一句话，其字面包含「已经发给 <归一化 host>」。
         */
        FAIL_LOUD,
    }

    /**
     * ★§4.4 精确语义表的机器可读版本。
     *
     * | 情形 | phase | 自动重试 | 允许回落？ | 必须告知 |
     * |---|---|---|---|---|
     * | DNS/连接/TLS 失败      | PRE  | 1 次 | ✅ | ✅ |
     * | 401 / 403             | PRE  | 0    | ✅ | ✅ |
     * | 407                   | PRE  | 0    | ✅ | ✅ |
     * | 404 / model_not_found | POST | 0    | ❌ | ✅ |
     * | 400 context_length    | POST | 0    | ❌ | ✅ |
     * | 429                   | POST | ≤2  | ❌ | ✅ |
     * | 5xx                   | POST | ≤1  | ❌ | ✅ |
     * | 首字节超时            | POST | 0    | ❌ | ✅ |
     * | 流空闲超时            | POST | 0    | ❌ | ✅ |
     * | 流截断                | POST | 0    | ❌ | ✅ |
     * | 空 delta              | POST | 0    | ❌ | ✅ |
     */
    public fun actionFor(failure: CloudFailure): FallbackAction = when (failure) {
        // ── PRE：一个字节都没发出去 ⇒ 允许回落本地（但必须告知）
        CloudFailure.NoPermit -> FallbackAction.FALLBACK_TO_DEVICE
        CloudFailure.CredentialMissing -> FallbackAction.FALLBACK_TO_DEVICE
        CloudFailure.ConnectFailed -> FallbackAction.RETRY_SAME_ENDPOINT   // ≤1 次，仍 PRE
        CloudFailure.AuthRejected -> FallbackAction.FALLBACK_TO_DEVICE
        CloudFailure.ProxyAuthRequired -> FallbackAction.FALLBACK_TO_DEVICE
        CloudFailure.InsecureEndpoint -> FallbackAction.FALLBACK_TO_DEVICE
        // ★ 锁 C：一个字节都没发出去 ⇒ PRE ⇒ 允许（且必须）告知后回落
        CloudFailure.PageContextRefused -> FallbackAction.FALLBACK_TO_DEVICE

        // ── POST：请求体已经发出去了 ⇒ **禁止回落**
        CloudFailure.ModelNotFound -> FallbackAction.FAIL_LOUD
        CloudFailure.ContextTooLong -> FallbackAction.FAIL_LOUD
        CloudFailure.RateLimited -> FallbackAction.RETRY_SAME_ENDPOINT  // ≤2，尊重 Retry-After
        CloudFailure.ServerError -> FallbackAction.RETRY_SAME_ENDPOINT  // ≤1
        CloudFailure.FirstByteTimeout -> FallbackAction.FAIL_LOUD
        CloudFailure.StreamIdleTimeout -> FallbackAction.FAIL_LOUD
        CloudFailure.StreamTruncated -> FallbackAction.FAIL_LOUD
        CloudFailure.EmptyDelta -> FallbackAction.FAIL_LOUD
        CloudFailure.MalformedResponse -> FallbackAction.FAIL_LOUD
        CloudFailure.DeadlineExceeded -> FallbackAction.FAIL_LOUD
    }

    /** §4.4「自动重试」列的次数上限。 */
    public fun maxRetries(failure: CloudFailure): Int = when (failure) {
        CloudFailure.ConnectFailed -> 1
        CloudFailure.RateLimited -> 2
        CloudFailure.ServerError -> 1
        else -> 0
    }

    /**
     * ★§4.4 总原则：`POST` 阶段的任何失败，UI 上必须出现一句话，
     * 其字面包含「已经发给 <归一化 host>」。不显示这句话的失败提示**等于没提示**。
     */
    public fun mustSayAlreadySent(failure: CloudFailure): Boolean =
        failure.phase == EgressPhase.POST

    // ═════════════════════════════════════════════════════════════════════════
    // ★★ P0：PRE 回落「必须告知」的承载（2026-10 主理人裁决）
    //
    // ## 缺口
    //
    // §4.4 要求 PRE 阶段「允许回落 ∧ 必须告知」。此前 API 上只有
    // [mustSayAlreadySent]（"已经发给 host"），它**只对 POST 有意义** ——
    // PRE 回落那句「未上云，已由本机完成」没有任何承载 ⇒ 全靠 UI 自觉 ⇒ 可静默降级。
    //
    // ## 承载的形状：三段，缺一不可
    //
    //  ① [mustAnnounceFallback] —— 判据（这个失败形态**要不要**说）；
    //  ② [announcementFor]      —— 内容（说**什么**：i18n key + 归一化 host）；
    //  ③ [com.ake.core.ports.EgressAnnouncementSink] —— **出口**（谁去说）。
    //
    // ## ★ 为什么「给个 Boolean 字段」不够
    //
    // 字段可以被忽略：调用方拿到 `mustAnnounce = true` 然后什么都不做，
    // 编译期不会有任何反应。于是承载必须让「说」这件事**发生在拿到结果之前**，
    // 并且让「不说」在装配期就红 ⇒ 见 [DefaultEgressCoordinator]：
    // 它在返回 `FellBackToLocal` **之前**先调 sink，而 sink 由 `:app` 用 Hilt 绑
    // ⇒ 缺绑 = `[Dagger/MissingBinding]` = 编译失败。
    // ═════════════════════════════════════════════════════════════════════════

    /** ★ 这个失败形态**是否必须**告知用户「已回落到本机」。等价于「决议是回落」。 */
    public fun mustAnnounceFallback(failure: CloudFailure): Boolean =
        actionFor(failure) == FallbackAction.FALLBACK_TO_DEVICE

    /**
     * ★ 生成告知内容。
     *
     * @param host 已过 `Hosts.normalise` 的端点 host；PRE 阶段允许为空（确实没发出去）。
     */
    public fun announcementFor(
        failure: CloudFailure,
        host: String?,
        turnId: String? = null,
        sessionId: String? = null,
    ): FallbackAnnouncement =
        FallbackAnnouncement(
            failure = failure,
            kind = if (mustSayAlreadySent(failure)) {
                AnnouncementKind.ALREADY_SENT
            } else {
                AnnouncementKind.FELL_BACK_TO_LOCAL
            },
            host = host,
            turnId = turnId,
            sessionId = sessionId,
        )

    /** §7.3：写进 `route_decision.outcome` 的终态。 */
    public fun auditOutcome(failure: CloudFailure): AuditOutcome = when (failure) {
        CloudFailure.NoPermit, CloudFailure.CredentialMissing, CloudFailure.ConnectFailed,
        CloudFailure.AuthRejected, CloudFailure.ProxyAuthRequired, CloudFailure.InsecureEndpoint,
        CloudFailure.PageContextRefused -> AuditOutcome.NOT_SENT

        CloudFailure.StreamIdleTimeout, CloudFailure.StreamTruncated,
        CloudFailure.DeadlineExceeded -> AuditOutcome.PARTIAL

        CloudFailure.ModelNotFound, CloudFailure.ContextTooLong, CloudFailure.RateLimited,
        CloudFailure.ServerError, CloudFailure.FirstByteTimeout, CloudFailure.EmptyDelta,
        CloudFailure.MalformedResponse -> AuditOutcome.FAILED
    }
}
