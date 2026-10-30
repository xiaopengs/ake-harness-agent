package com.ake.cloudai

import com.ake.core.common.Outcome
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.CloudCallOutcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CloudFailureCodes
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.PromptContext

/**
 * ★★ `DialogModelPort` → [CloudCallOutcome] 的**唯一**适配层。
 *
 * ## 为什么它必须在 `:cloudai` 而不是 `:inference`
 *
 * 它需要把 `AppError.code` 反解成 [CloudFailure]，而这个映射**只有 `:cloudai` 有**
 * （`CloudFailureMapper` 是 `internal`，判定只有一份）。若 `:inference` 照着
 * `CloudFailureCodes` 再写一遍映射，就会出现第二份「401 到底算不算 PRE」的判据
 * —— 那正是本轮红线要消灭的东西。
 *
 * ## 它把哪两端接起来
 *
 * ```
 *   EgressCoordinator.execute { permit -> CloudCallAdapter.once(remote, permit, …) }
 *                              ↑ :inference 编排           ↑ :cloudai 真正的 HTTP
 * ```
 *
 * ★ **不含任何策略**：不重试、不回落、不决定是否告知 —— 那些全在
 *   `FallbackPolicy` / `DefaultEgressCoordinator`。这里只回答「发生了什么」。
 */
public object CloudCallAdapter {

    /**
     * 执行一次云端调用。
     *
     * @param onDelta 逐段交给上层（**原样透传**，不在此处做跨 chunk 清洗 ——
     *   那是 `:chat` 的 `DeltaScrubber` 的职责）。
     */
    public suspend fun once(
        remote: DialogModelPort,
        permit: EgressPermit,
        spec: SessionSpec,
        req: GenerationRequest,
        context: PromptContext = PromptContext.TextOnly,
        onDelta: suspend (String) -> Unit = {},
    ): CloudCallOutcome {
        // ① 开会话。失败发生在这里 ⇒ **一个字节都没发出去**（真正的 PRE）。
        val opened = remote.openDialog(spec, permit, context)
        val session = when (opened) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> return CloudCallOutcome.Failed(
                failure = codeToFailure(opened.error.code),
                deliveredBytes = 0,
            )
        }

        // ② 生成。★ deliveredBytes 取会话**自报**的已投递字节（不含 HTTP 头）。
        val text = StringBuilder()
        val result = session.generateStream(req) { delta ->
            text.append(delta)
            onDelta(delta)
        }

        val sentBytes = (session as? CloudDialogSession)?.lastDeliveredBytes ?: 0
        val finishRaw = (session as? CloudDialogSession)?.lastFinishReasonRaw

        return when (result) {
            is Outcome.Ok -> CloudCallOutcome.Done(
                text = text.toString(),
                finishReasonRaw = finishRaw,
                deliveredBytes = sentBytes,
            )

            is Outcome.Err -> CloudCallOutcome.Failed(
                failure = codeToFailure(result.error.code),
                deliveredBytes = sentBytes,
            )
        }
    }

    /**
     * ★ 机器码 → 失败形态。
     *
     * ⚠ 认不出的码 ⇒ [CloudFailure.MalformedResponse]（**POST**，不回落）
     * 而不是猜一个 PRE —— 猜错方向的代价是「数据已出境却告诉用户没出去」。
     */
    public fun codeToFailure(code: String): CloudFailure = when (code) {
        CloudFailureCodes.NO_PERMIT -> CloudFailure.NoPermit
        CloudFailureCodes.CREDENTIAL_MISSING -> CloudFailure.CredentialMissing
        CloudFailureCodes.CONNECT_FAILED -> CloudFailure.ConnectFailed
        CloudFailureCodes.AUTH_REJECTED -> CloudFailure.AuthRejected
        CloudFailureCodes.PROXY_AUTH_REQUIRED -> CloudFailure.ProxyAuthRequired
        CloudFailureCodes.INSECURE_ENDPOINT -> CloudFailure.InsecureEndpoint
        CloudFailureCodes.PAGE_CONTEXT_REFUSED -> CloudFailure.PageContextRefused
        CloudFailureCodes.MODEL_NOT_FOUND -> CloudFailure.ModelNotFound
        CloudFailureCodes.CONTEXT_TOO_LONG -> CloudFailure.ContextTooLong
        CloudFailureCodes.RATE_LIMITED -> CloudFailure.RateLimited
        CloudFailureCodes.SERVER_ERROR -> CloudFailure.ServerError
        CloudFailureCodes.FIRST_BYTE_TIMEOUT -> CloudFailure.FirstByteTimeout
        CloudFailureCodes.STREAM_IDLE_TIMEOUT -> CloudFailure.StreamIdleTimeout
        CloudFailureCodes.STREAM_TRUNCATED -> CloudFailure.StreamTruncated
        CloudFailureCodes.DEADLINE_EXCEEDED -> CloudFailure.DeadlineExceeded
        CloudFailureCodes.EMPTY_DELTA -> CloudFailure.EmptyDelta
        else -> CloudFailure.MalformedResponse
    }
}
