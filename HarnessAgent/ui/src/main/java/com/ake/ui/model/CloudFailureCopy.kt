package com.ake.ui.model

import androidx.annotation.StringRes
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.EgressPhase
import com.ake.ui.R

// ===========================================================================
// 云端失败 → 人话（架构 08 §4.4 / §6.3）
//
// ## 为什么这张表必须存在，而不是跟着 AppError.reasonKey 走
//
// `CloudFailure.reasonKey` 是 i18n 常量（"err_cloud_auth_rejected" 之类）。
// 若 UI 拿着 `reasonKey` 去查表，新增一种失败形态就是**静默**的：常量的字符串变了，
// Kotlin 一侧一个字都不动，界面落到某个兜底分支上照常显示。
// 这里直接对 `CloudFailure` 的密封变体穷尽 `when`，编译器在第 18 种出现时当场拦住。
// 理由与 `ChatCopy.kt` 对 `ChatTerminalReason` 的处理完全同源。
//
// ## ★★ 这张表是「红线落地」的一半，写法上有两条铁律
//
// ① **PRE 与 POST 不许共用一个字来描述“有没有出去”**。
//    PRE  = 一个字节都没发出去 ⇒ 允许回落到本机，但必须说出「已改由本机模型回答」；
//    POST = 请求体已经发出       ⇒ 禁止回落，且必须说出「已经发给 <归一化 host>」。
//
//    §4.2 那句「禁止静默回落」落到 UI 上的全部内容就是这两句话：
//    用户读完必须能回答「我的数据出去了吗」，答不上来的失败提示等于没有提示。
//
// ② **host 是这张表唯一的外部输入，而且必须是 `Hosts.normalise` 之后的裸域名**。
//    自建网关常把 key 塞在 query 里，完整 URL 本身就是秘密（§6.3 ③）。
//    `CloudFailure` 类型里没有 url / body / message / cause 字段，
//    于是这张表在结构上就不可能把那些东西拼出去了 —— 它只能拿到 host。
// ===========================================================================

public data class CloudFailureCopy(
    /** 一句话结论。不许拼任何服务端文本（§6.3 ⑦）。 */
    @param:StringRes val titleRes: Int,
    /** 用户下一步能做什么 + 这次到底出没出去。正是 §4.4 那句“必须告知”的落点。 */
    @param:StringRes val nextRes: Int,
    /** PRE / POST。它同时决定宿主该用什么颜色，以及能不能回落到本机。 */
    val phase: EgressPhase,
)

/**
 * ★ PRE 组共有的那个短语。
 *
 * 抽成常量不是为了少写字，是为了让 `CloudFailureCopyTest` 能用一个名字盯住它：
 * 「写了回落却不说这句话」和「压根没写回落」在用户眼里是同一个灾难。
 */
public const val PHRASE_FELL_BACK_TO_DEVICE: String = "已改由本机模型回答"

/** ★ POST 组共有前缀。§4.4 总原则：POST 失败提示的字面里必须出现它 + 归一化 host。 */
public const val PHRASE_ALREADY_SENT: String = "已经发给"

/**
 * 把归一化 host 填进带 `%1$s` 的文案。
 *
 * ★ 主流程与单测共用这一份：测试断言「确实出现了『已经发给 gw.internal』」，
 *   就得走和界面同一条插值路径 —— 各插值各的等于把渲染的正确性排除在测试之外。
 */
public fun formatCloudCopy(template: String, host: String): String = String.format(template, host)

/**
 * ★ 穷尽映射。**不许加 `else`**：第 18 种失败形态出现时必须在这里编译失败，
 * 逼作者回答「它是 PRE 还是 POST、要不要告知」。默认答案不存在。
 */
public fun cloudFailureCopyOf(failure: CloudFailure): CloudFailureCopy = when (failure) {
    // ── PRE：一个字节都没发出去 ⇒ 允许回落，但必须说出来 ──────────────────
    CloudFailure.NoPermit -> CloudFailureCopy(
        titleRes = R.string.err_cloud_no_permit_t,
        nextRes = R.string.err_cloud_no_permit_n,
        phase = EgressPhase.PRE,
    )

    CloudFailure.CredentialMissing -> CloudFailureCopy(
        titleRes = R.string.err_cloud_cred_missing_t,
        nextRes = R.string.err_cloud_cred_missing_n,
        phase = EgressPhase.PRE,
    )

    CloudFailure.ConnectFailed -> CloudFailureCopy(
        titleRes = R.string.err_cloud_connect_failed_t,
        nextRes = R.string.err_cloud_connect_failed_n,
        phase = EgressPhase.PRE,
    )

    CloudFailure.AuthRejected -> CloudFailureCopy(
        titleRes = R.string.err_cloud_auth_rejected_t,
        nextRes = R.string.err_cloud_auth_rejected_n,
        phase = EgressPhase.PRE,
    )

    CloudFailure.ProxyAuthRequired -> CloudFailureCopy(
        titleRes = R.string.err_cloud_proxy_auth_t,
        nextRes = R.string.err_cloud_proxy_auth_n,
        phase = EgressPhase.PRE,
    )

    CloudFailure.InsecureEndpoint -> CloudFailureCopy(
        titleRes = R.string.err_cloud_insecure_endpoint_t,
        nextRes = R.string.err_cloud_insecure_endpoint_n,
        phase = EgressPhase.PRE,
    )

    /**
     * ★ 锁 C 在界面上的样子（§5.4）：本会话带页面上下文 ⇒ 云端硬失败。
     *
     * 它是一个 PRE ⇒ 允许回落 —— 而且**必须**回落，因为此刻一个字节都没出去。
     * 这里不自己判断「要不要回落」（那是 `FallbackPolicy` 的事），
     * 只负责把「改由本机回答」这句话说出口。
     */
    CloudFailure.PageContextRefused -> CloudFailureCopy(
        titleRes = R.string.err_cloud_page_context_refused_t,
        nextRes = R.string.err_cloud_page_context_refused_n,
        phase = EgressPhase.PRE,
    )

    // ── POST：请求体已经发出去了 ⇒ 禁止回落，必须说“已经发给谁” ──────────
    CloudFailure.ModelNotFound -> CloudFailureCopy(
        titleRes = R.string.err_cloud_model_not_found_t,
        nextRes = R.string.err_cloud_model_not_found_n,
        phase = EgressPhase.POST,
    )

    CloudFailure.ContextTooLong -> CloudFailureCopy(
        titleRes = R.string.err_cloud_context_too_long_t,
        nextRes = R.string.err_cloud_context_too_long_n,
        phase = EgressPhase.POST,
    )

    CloudFailure.RateLimited -> CloudFailureCopy(
        titleRes = R.string.err_cloud_rate_limited_t,
        nextRes = R.string.err_cloud_rate_limited_n,
        phase = EgressPhase.POST,
    )

    CloudFailure.ServerError -> CloudFailureCopy(
        titleRes = R.string.err_cloud_server_error_t,
        nextRes = R.string.err_cloud_server_error_n,
        phase = EgressPhase.POST,
    )

    /**
     * ★ 最容易写错的一档：它叫「超时」，听着像“没连上”，但请求体早就发完了。
     * 把[CloudFailure.FirstByteTimeout]放错组（比如说成“没出去”），
     * 用户的整段历史已经在对方服务器上了，界面却告诉他没事 —— 那正是静默降级。
     */
    CloudFailure.FirstByteTimeout -> CloudFailureCopy(
        titleRes = R.string.err_cloud_first_byte_timeout_t,
        nextRes = R.string.err_cloud_first_byte_timeout_n,
        phase = EgressPhase.POST,
    )

    CloudFailure.StreamIdleTimeout -> CloudFailureCopy(
        titleRes = R.string.err_cloud_stream_idle_timeout_t,
        nextRes = R.string.err_cloud_stream_idle_timeout_n,
        phase = EgressPhase.POST,
    )

    CloudFailure.StreamTruncated -> CloudFailureCopy(
        titleRes = R.string.err_cloud_stream_truncated_t,
        nextRes = R.string.err_cloud_stream_truncated_n,
        phase = EgressPhase.POST,
    )

    CloudFailure.DeadlineExceeded -> CloudFailureCopy(
        titleRes = R.string.err_cloud_deadline_exceeded_t,
        nextRes = R.string.err_cloud_deadline_exceeded_n,
        phase = EgressPhase.POST,
    )

    /**
     * ★ 「云端空了就用本地补一段」是静默降级的变体（§4.4），这里不说“补”，
     *   说的是它回了空 —— 差别在于前者会让用户以为这段字是本机写的。
     */
    CloudFailure.EmptyDelta -> CloudFailureCopy(
        titleRes = R.string.err_cloud_empty_delta_t,
        nextRes = R.string.err_cloud_empty_delta_n,
        phase = EgressPhase.POST,
    )

    CloudFailure.MalformedResponse -> CloudFailureCopy(
        titleRes = R.string.err_cloud_malformed_response_t,
        nextRes = R.string.err_cloud_malformed_response_n,
        phase = EgressPhase.POST,
    )
}
