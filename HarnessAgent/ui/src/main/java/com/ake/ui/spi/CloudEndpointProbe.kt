package com.ake.ui.spi

import com.ake.core.ports.CloudFailure
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.PlainSecret

// ===========================================================================
// 连通性自检的入口（架构 08 §6.2）
//
// ## ★ 为什么 `CloudCredentialPort` 里**没有** testConnection
//
// §6.2 原文：「验证必须真的发一次请求（走 ModelRouter 的 probe），
//            否则『验证』会成为**第二条出境路径**。」
//
// 也就是说，一次「自检」本身就是一次真实出境 —— 它必须有明确的是否发起、发给谁、
// 失败了算哪一相（PRE / POST）。把它放进凭证 Store，等于给「 secretly 发一次请求」
// 开了一个不受 `EgressPermit` 管的口子。
//
// ## ★★ 今天这个能力是缺的
//
// `ModelRouter`（`:core:ports/Dialog.kt`）只有 `dialogFor(sessionId)`，
// **没有** probe —— §6.2 注释里那句「走 ModelRouter 的 probe」目前是设计意图，不是代码。
// 所以这里把它定义成 `:ui` 自己的 Port，由 `@BindsOptionalOf` 兜住：
// 图里没有 ⇒ [CloudProbeResult.NotWired] ⇒ 界面明说「自检还没接上」，
// 而不是用「能存进去」冒充「能连上」—— 那是最容易被伪造成绿的一种假。
//
// TODO(霍承钧): 给 `ModelRouter` 加一个 probe（签名建议见下注释），本接口随即删除。
//   suspend fun probe(permit: EgressPermit, ref: CredentialRef): Outcome<Unit>
//   EgressPermit 不能省 —— 自检也是出境，也要有审计行（§7.2）。
// ===========================================================================

public sealed interface CloudProbeResult {

    /** 握手通过。 */
    public data object Ok : CloudProbeResult

    /**
     * 失败。★ 参数类型是 [CloudFailure]，而它**结构上**没有 message / cause / body / url
     * 四个字段 ⇒ 这个返回值里装不下任何秘密（§6.3）。
     */
    public data class Failed(val failure: CloudFailure) : CloudProbeResult

    /** 能力缺失（`:inference` 的 probe 还没接上）。明说出来，不是假装 Ok。 */
    public data object NotWired : CloudProbeResult
}

/**
 * ★ 一次自检 = 一次真实出境 ⇒ 必须显式调用，不是一个不带参数的「校验」方法。
 *
 * @param secret 明文只在调用期间存在：[CloudProbeResult] 里没有任何一行会带上它。
 */
public interface CloudEndpointProbe {
    public suspend fun probe(endpoint: EndpointConfig, secret: PlainSecret): CloudProbeResult
}
