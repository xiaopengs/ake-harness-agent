package com.ake.llm

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.SessionId
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.InferenceStats
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.RawToolCall
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ModelErrorCodes
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ModelSession` 的 LiteRT-LM 实现 —— 一个薄薄的 `Conversation` 封装。
 *
 * **三件事决定了它的形状：**
 *
 * 1. **Conversation 全程复用，严禁每步重建**（架构 §4.3）。
 *    每一步重建意味着每步都要把整个 prompt 重新 prefill 一遍 —— 而 prefill 是单步的主要成本项。
 *    复用 Conversation 才能让 KV cache 生效。这个类是 KV cache 生命周期的边界。
 *
 * 2. **`automaticToolCalling = false`**（§6.1）。
 *    LiteRT-LM 默认会「模型产出 tool call → 自动调你的函数 → 把结果喂回模型」。
 *    本项目必须关掉：自动回路**绕过 PolicyPort**（注入诱导的动作直接执行、没有确认环节），
 *    且不经过 AgentLoop ⇒ 无法 trace、无法统计 token、无法做循环检测、无法回放。
 *
 * 3. **`CancellationException` 必须放行**（§6.2 坑 3）。
 *    `catch (e: Exception)` 会吃掉用户点「停止」的取消异常，导致已停止的生成被当成引擎故障。
 *
 * @param conversation 由 [com.ake.llm.LiteRtModelPort.openSession] 创建，交由此对象持有到 close
 * @param singleThread LiteRT-LM **非线程安全**：所有 native 调用都要在这个单线程 dispatcher 上串行
 */
internal class LiteRtSession(
    override val sessionId: SessionId,
    private val spec: SessionSpec,
    private val conversation: Conversation,
    private val singleThread: CoroutineDispatcher,
    private val timeoutsEnabled: Boolean = true,
) : ModelSession {

    @Volatile
    private var closed = false

    @Volatile
    private var generationRunning = false

    override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> {
        if (closed) {
            return Outcome.Err(
                AppError(ModelErrorCodes.SESSION_DEAD, "err_model_session_dead"),
            )
        }
        return withContext(singleThread) {
            generationRunning = true
            try {
                val deadline = req.deadlineMs
                val message = if (timeoutsEnabled && deadline > 0) {
                    withTimeout(deadline) { send(req) }
                } else {
                    send(req)
                }
                Outcome.Ok(message.toGenerationResult())
            } catch (ce: CancellationException) {
                // ★ 用户中止：必须原样透传，不能包成 AppError
                conversation.cancelProcess()
                throw ce
            } catch (te: TimeoutCancellationException) {
                conversation.cancelProcess()
                Outcome.Err(AppError(ModelErrorCodes.GENERATE_FAILED, "err_model_timeout"))
            } catch (e: Throwable) {
                Outcome.Err(
                    AppError(ModelErrorCodes.GENERATE_FAILED, "err_model_generate_failed", e),
                )
            } finally {
                generationRunning = false
            }
        }
    }

    private fun send(req: GenerationRequest): Message = conversation.sendMessage(
        req.userTurn,
        maxOutputToken = req.maxOutputTokens,
        thinkingConfig = req.thinking.toLiteRt(),
        responseFormat = if (spec.enableJsonConstraint) req.outputShape.toResponseFormatOrNull() else null,
    )

    /**
     * 已占用 KV cache token 数（§4.3）。
     *
     * 这是 `Conversation.tokenCount` 的直接读数 —— 它是「要不要开始压缩」的唯一可靠依据。
     * 用 prompt 字符数去猜会严重偏差，因为 KV cache 还包括历史所有轮次的 tool response。
     */
    override suspend fun currentCacheTokens(): Int = withContext(singleThread) {
        if (closed) return@withContext 0
        runCatching { conversation.getTokenCount() }.getOrDefault(0)
    }

    override fun close() {
        if (closed) return
        closed = true
        // §6.2 坑 4：close() 二次调用抛 IllegalStateException。
        // 上层可能既有显式 close 又有 use{}，所以这里必须吞掉。
        runCatching { conversation.close() }
    }

    fun isBusy(): Boolean = generationRunning
}

/**
 * LiteRT-LM `Message` → 本项目 `GenerationResult`。
 *
 * **统计口径直接取自引擎的真实 benchmark**（`Conversation.benchmarkInfo`），
 * 而不是自己掐表 —— TTFT 的定义（是否包含 prefill）在 native 侧才是权威的。
 */
internal fun Message.toGenerationResult(): GenerationResult {
    // ★ litertlm 是 **Kotlin** 库：Kotlin 属性在 Kotlin 源码里只能用属性语法访问，
    //   写 `getContents()` / `getName()` 会直接 unresolved（javap 里看到的 getXxx
    //   只是字节码层面留给 Java 用的合成方法）。下面统一改成属性访问。
    val text = buildString {
        for (c in contents.contents) {
            if (c is Content.Text) append(c.text)
        }
    }
    return GenerationResult(
        rawText = text,
        toolCalls = toolCalls.map { tc ->
            RawToolCall(
                name = tc.name,
                // ★ LiteRT-LM 的 arguments 是 Map<String, Any?>，不是 JSON 字符串。
                //   这里必须自己序列化 —— 这也是 ToolCall 上层参数是 String 的原因
                //   这让上层对 tool call 参数只有一种形态 JSON text，不必分支处理类型。
                argumentsJson = toJsonObject(tc.arguments).toString(),
                callId = null,
            )
        },
        finishReason = FinishReason.STOP,
        stats = InferenceStats(
            prefillTokens = 0,
            decodeTokens = 0,
            ttftMs = 0,
            decodeTps = 0.0,
        ),
    )
}

/** 用 `Conversation.benchmarkInfo` 回填真实统计（Engine 侧已知函数，放在 session 里补）。 */
internal fun GenerationResult.withBenchmark(bi: com.google.ai.edge.litertlm.BenchmarkInfo): GenerationResult =
    copy(
        stats = InferenceStats(
            prefillTokens = bi.lastPrefillTokenCount,
            decodeTokens = bi.lastDecodeTokenCount,
            ttftMs = (bi.timeToFirstTokenInSecond * 1000.0).toInt(),
            decodeTps = bi.lastDecodeTokensPerSecond,
        ),
    )

// ──────────────────────────────────────────────────────────────
// Map<String, Any?> → org.json
// 用 Android SDK 自带的 org.json，不引入 gson —— 避免与 litertlm 传递依赖的 gson 2.14 抢 classpath。
// ──────────────────────────────────────────────────────────────

internal fun toJsonObject(map: Map<String, Any?>): JSONObject = JSONObject().apply {
    for ((k, v) in map) put(k, toJsonValue(v))
}

private fun toJsonValue(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
        for ((k, value) in v) put(k.toString(), toJsonValue(value))
    }

    is Collection<*> -> JSONArray().apply {
        v.forEach { put(toJsonValue(it)) }
    }

    is Array<*> -> JSONArray().apply {
        v.forEach { put(toJsonValue(it)) }
    }

    else -> v
}
