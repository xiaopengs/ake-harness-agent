package com.ake.agent.loop

import com.ake.core.common.Outcome
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.ModelSession

/**
 * 解码增量的投递通道（`AgentLoop` 第 3 环的**唯一**模型出口）。
 *
 * ## 为什么要有这一层
 *
 * `ModelSession.generate()`（`core:model-api`）是一次性返回 `GenerationResult` 的：
 * 引擎解码完整个序列才返回，调用方拿到的只有最终全文。
 * 而 UI 要的「流式 token 展示」需要在**解码过程中**拿到片段。
 *
 * 这两者之间的缺口不在 `:agent` —— 它在 `ModelSession` 缺少一个流式入口。
 * 但 `core:model-api` 与 `:llm` 不在本模块的责任范围里，
 * 所以在 `:agent` 这一侧先把**接缝**留出来，而不是等到上游好了再回来改主循环：
 *
 * ```
 *  现在：  AgentLoop → TokenStream.SINGLE_SHOT → ModelSession.generate()   （每步 1 条 TokenDelta）
 *  将来：  AgentLoop → app 提供的实现        → ModelSession.generateStream()（每 token 1 条）
 * ```
 *
 * 两种形态下 [TaskEvent.TokenDelta] 的语义完全一致（「又多了一段输出」），
 * 所以 **UI 侧与本事件都不需要为「真流式上线」改一行代码** —— 这就是留接缝的价值。
 *
 * ## 为什么是 `fun interface` 而不是给 `AgentLoop` 加一个 `onDelta: (String) -> Unit`
 *
 * 后者会诱导实现在解码结束后「把全文拆成假 token 分批发」——
 * 那是**表演出来的流式**，用户看到的是打字机特效，不是模型真的在想。
 * 一旦出现这种实现，「屏幕上在动」与「模型在跑」就脱钩了，
 * 排查「卡住了还是还在想」时这个信号会彻底失效。
 * 把生成与投递绑在同一个 suspend 函数里，假流式就无处安放。
 */
fun interface TokenStream {

    /**
     * @param onDelta **增量**回调，必须是「新增的那一段」而不是累计全文。
     *                允许从任意线程/协程调用（引擎的解码回调通常在它自己的线程上）。
     */
    suspend fun generate(
        session: ModelSession,
        req: GenerationRequest,
        onDelta: suspend (String) -> Unit,
    ): Outcome<GenerationResult>

    companion object {

        /**
         * 默认实现：整段交付。
         *
         * 生成结束后把 `rawText` 作为**一条**增量投出。它不伪装成逐 token ——
         * 一次调用一条事件，调用方拿到的就是「本步模型说了这些」，与真实流式同构。
         *
         * 空原文不投：约束解码下引擎可能只吐 tool-call 而无正文，
         * 发一条空 delta 会让 UI 白跑一次重组。
         */
        val SINGLE_SHOT: TokenStream = TokenStream { session, req, onDelta ->
            val r = session.generate(req)
            if (r is Outcome.Ok && r.value.rawText.isNotEmpty()) onDelta(r.value.rawText)
            r
        }
    }
}
