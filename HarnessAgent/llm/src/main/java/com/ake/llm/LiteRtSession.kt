package com.ake.llm

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.SessionId
import com.ake.core.common.Severity
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.InferenceStats
import com.ake.core.modelapi.ModelErrorCodes
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.RawToolCall
import com.ake.core.modelapi.SessionSpec
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ModelSession` 的 LiteRT-LM 实现 —— 一个薄薄的 `Conversation` 封装。
 *
 * **四件事决定了它的形状：**
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
 * 4. **KV cache 上限是硬闸门**（§R2 · F4）。
 *    [maxCacheTokens] 是所在 Engine 的 `maxNumTokens`。超限在 native 层是 OOM / abort
 *    —— catch 不到、进程直接没。所以每次生成**之前**先做一次拟合判定，
 *    装不下就返回 `CONTEXT_OVERFLOW`，把「压缩历史」的决定权交回上层。
 *
 * @param conversation 由 [com.ake.llm.LiteRtModelPort.openSession] 创建，交由此对象持有到 close
 * @param singleThread LiteRT-LM **非线程安全**：所有 native 调用都要在这个单线程 dispatcher 上串行
 * @param maxCacheTokens 所在 Engine 的 KV cache 总上限（`EngineConfig.maxNumTokens`）
 */
internal class LiteRtSession(
    override val sessionId: SessionId,
    private val spec: SessionSpec,
    private val conversation: Conversation,
    private val singleThread: CoroutineDispatcher,
    private val maxCacheTokens: Int,
    /**
     * **引擎级**生成互斥（由 `LiteRtModelPort` 持有、注入）。
     *
     * 同步路径靠「`withContext(singleThread)` 把整段解码压在一条线程上」天然串行化；
     * 但 `sendMessageAsync` 是**立刻返回**的 —— 解码跑在 native 自己的线程上，
     * 我们的单线程这时是空的。于是两个会话可以同时流式解码、把 token 交错串到一起
     * （架构 §6.2 的「两个任务互相吐对方的字」）。这把锁补上这一层：
     * **流式期间锁住的是「这一次生成」，不是那条线程**（持锁方只要挂起，别处就能拿到线程）。
     *
     * 锁必须在 `withContext(singleThread)` **之外**获取：先在引擎线程上阻塞、
     * 再去抢锁，会出现「持线程等锁」的经典死锁。
     */
    private val generationLock: Mutex,
    private val timeoutsEnabled: Boolean = true,
    private val estimate: (String) -> Int = TokenEstimator::estimate,
) : ModelSession {

    @Volatile
    private var closed = false

    @Volatile
    private var generationRunning = false

    override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> {
        if (closed) return dead()

        return generationLock.withLock {
            // 预算前置拒绝：native 之外的地方说「不」，是唯一不崩的说「不」方式。
            val precheck = withContext(singleThread) { overflowError(req, promptTokens = promptSoFar(req)) }
            precheck?.let { return@withLock Outcome.Err(it) }

            withContext(singleThread) {
                generationRunning = true
                try {
                    val message = if (timeoutsEnabled && req.deadlineMs > 0) {
                        withTimeout(req.deadlineMs) { send(req) }
                    } else {
                        send(req)
                    }
                    Outcome.Ok(buildResult(message.textOf(), message.toolCalls.toRawToolCalls(), req, benchmark()))
                } catch (te: TimeoutCancellationException) {
                    // ★ 顺序不能反：TimeoutCancellationException 是 CancellationException 的子类，
                    //   先 catch 父类会让这条分支永远进不来 —— 表现为「超时被当成用户取消，
                    //   错误原样抛出而不是返回 err_model_timeout」。
                    cancelNative()
                    Outcome.Err(AppError(ModelErrorCodes.GENERATE_FAILED, "err_model_timeout"))
                } catch (ce: CancellationException) {
                    // ★ 用户中止：必须原样透传，不能包成 AppError
                    cancelNative()
                    throw ce
                } catch (e: Throwable) {
                    Outcome.Err(
                        AppError(ModelErrorCodes.GENERATE_FAILED, "err_model_generate_failed", e),
                    )
                } finally {
                    generationRunning = false
                }
            }
        }
    }

    /**
     * 真流式（§3.3）。
     *
     * ## 结构：native 回调线程 ⇄ 协程上下文之间隔一条 Channel
     *
     * ```
     *   [native 解码线程]                    [调用方的协程上下文]
     *   MessageCallback.onMessage ──trySend──▶ Channel ──receive──▶ onDelta
     * ```
     *
     * 三条纪律，每条都对应 §R4 列出的真实坑：
     *
     *  ① **绝不在回调线程里直接调 `onDelta`**。回调跑在 litertlm 自己的工作线程上，
     *    在那里碰 UI state / 非线程安全 collector 是数据竞争。Channel 是这个边界的唯一通道。
     *  ② **跨 chunk 的 special token 不在这一层处理**。Gemma 的 turn 标记会在 chunk 边界被切开，
     *    只有持有「整轮文本」的一侧（`:chat` 的 `DeltaScrubber`）能正确缓冲；
     *    这里只做「累计语义 → 增量语义」的差分（见 [StreamingDeltaAssembler]）。
     *  ③ **取消必须走 `conversation.cancelProcess()`**。只停 Flow 不会让 native 停下来 ——
     *    GPU 上会继续烧几十秒，且下一轮生成会和它抢同一个 `Conversation`（F3：一个 Engine
     *    同时只允许一个 Conversation），表现为「停止后依然吐字 / 下一轮乱码」。
     *  ④ **`benchmarkInfo` 只在流结束后读**。它是 native 的「最后一轮」统计，
     *    解码途中读出来是上一轮的或半截的值。
     *
     * `Chunk` 的通道容量是 `UNLIMITED`：`trySend` 在回调线程上**不能阻塞**（会卡死 native），
     * 而实际条数被 `maxOutputTokens` 天然封顶（≤ 数百条短串），不需要背压。
     */
    override suspend fun generateStream(
        req: GenerationRequest,
        onDelta: suspend (String) -> Unit,
    ): Outcome<GenerationResult> {
        if (closed) return dead()

        // ★ 锁在 withContext(singleThread) 之外拿：先占线程再抢锁 = 持线程等锁的死锁。
        return generationLock.withLock {
            val precheck = withContext(singleThread) { overflowError(req, promptTokens = promptSoFar(req)) }
            precheck?.let { return@withLock Outcome.Err(it) }

            coroutineScope {
                val chunks = Channel<StreamSignal>(capacity = Channel.UNLIMITED)
                val assembler = StreamingDeltaAssembler()
                val toolCalls = ArrayList<RawToolCall>()

                generationRunning = true
                // native 的发起落在单线程上：与同步路径共用同一条串行化纪律
                val sender = launch(singleThread) {
                    try {
                        conversation.sendMessageAsync(
                            text = req.userTurn,
                            callback = callbackInto(chunks),
                            maxOutputToken = req.maxOutputTokens,
                            thinkingConfig = req.thinking.toLiteRt(),
                            responseFormat = if (spec.enableJsonConstraint) req.outputShape.toResponseFormatOrNull() else null,
                        )
                    } catch (t: Throwable) {
                        // 发起就失败（会话已死 / JNI 异常）：把它送到消费侧，由消费侧统一收敛
                        chunks.trySend(StreamSignal.Failed(t))
                    }
                }

                try {
                    val consume: suspend () -> Unit = {
                        var done = false
                        while (!done) {
                            when (val signal = chunks.receive()) {
                                is StreamSignal.Partial -> {
                                    val message = signal.message
                                    if (message.toolCalls.isNotEmpty()) {
                                        toolCalls.clear()
                                        toolCalls += message.toolCalls.toRawToolCalls()
                                    }
                                    val delta = assembler.feed(message.textOf())
                                    if (delta.isNotEmpty()) onDelta(delta)
                                }

                                StreamSignal.Done -> done = true
                                is StreamSignal.Failed -> throw signal.error
                            }
                        }
                    }
                    if (timeoutsEnabled && req.deadlineMs > 0) {
                        withTimeout(req.deadlineMs) { consume() }
                    } else {
                        consume()
                    }
                    // ★ 统计只在 Done 之后读（§R4 坑 ④）：解码途中读出来是上一轮/半截的值。
                    val result = withContext(singleThread) {
                        buildResult(assembler.text, toolCalls, req, benchmark())
                    }
                    Outcome.Ok(result)
                } catch (te: TimeoutCancellationException) {
                    cancelNative()
                    Outcome.Err(AppError(ModelErrorCodes.GENERATE_FAILED, "err_model_timeout"))
                } catch (ce: CancellationException) {
                    cancelNative()
                    throw ce
                } catch (t: Throwable) {
                    cancelNative()
                    Outcome.Err(
                        AppError(ModelErrorCodes.GENERATE_FAILED, "err_model_generate_failed", t),
                    )
                } finally {
                    generationRunning = false
                    // sender 在 onDone 之前不应被取消；正常路径下它早就返回了。
                    // 异常路径下取消它 + cancelNative()，避免 native 在后台继续解码。
                    sender.cancel()
                    chunks.close()
                }
            }
        }
    }

    private fun callbackInto(chunks: SendChannel<StreamSignal>): MessageCallback = object : MessageCallback {
        // ★ 三个回调都只做 trySend：不阻塞（会卡死 native）、不抛（会打断 JNI 层）、不碰上层状态。
        override fun onMessage(message: Message) {
            chunks.trySend(StreamSignal.Partial(message))
        }

        override fun onDone() {
            chunks.trySend(StreamSignal.Done)
        }

        override fun onError(throwable: Throwable) {
            chunks.trySend(StreamSignal.Failed(throwable))
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

    // ────────────────────────────────────────────────────────────── 内部

    /**
     * 当前 KV cache 已占量 + 本轮输入 + 模板开销。
     *
     * `getTokenCount()` 的读数包含 preface 与被复水的历史（§3.5）；若某机型上它不含，
     * 这个估算也是偏保守的（只在 native 之外多估一点，不会少估）。
     */
    private fun promptSoFar(req: GenerationRequest): Int {
        val cached = runCatching { conversation.getTokenCount() }.getOrDefault(0)
        return cached + estimate(req.userTurn) + EngineTokenBudget.TURN_OVERHEAD_TOKENS
    }

    /** 装不下就返回 [ModelErrorCodes.CONTEXT_OVERFLOW]；装得下返回 null。 */
    private fun overflowError(req: GenerationRequest, promptTokens: Int): AppError? {
        val reserve = req.maxOutputTokens.coerceAtLeast(1)
        return when (val fit = EngineTokenBudget.fit(maxCacheTokens, promptTokens, reserve)) {
            EngineTokenBudget.Fit.Fits -> null
            is EngineTokenBudget.Fit.Overflow -> {
                // 一条 warning 而不是静默：这条日志是「为什么这轮没答」的唯一现场证据。
                AkeLog.w(
                    TAG,
                    "context overflow: prompt=$promptTokens + reserve=$reserve = ${fit.needed} > " +
                        "maxNumTokens=$maxCacheTokens (超 ${fit.overflow}) session=$sessionId",
                )
                AppError(
                    code = ModelErrorCodes.CONTEXT_OVERFLOW,
                    reasonKey = "err_model_context_overflow",
                    severity = Severity.MEDIUM,
                )
            }
        }
    }

    /**
     * 真中断 native 解码。
     *
     * 用 `launch(singleThread) + join()` 而不是 `withContext(singleThread)` / `runBlocking`：
     * 取消路径可能**已经在** singleThread 上（同步 `generate()` 的 catch 就在那里），
     * 那时任何「阻塞等待这个线程」的写法都会自锁死 —— 而 `join()` 是挂起，不是阻塞。
     * `NonCancellable` 保证取消路径本身被执行完（此时 scope 通常已经是取消态）。
     */
    private suspend fun cancelNative() {
        withContext(NonCancellable) {
            launch(singleThread) { runCatching { conversation.cancelProcess() } }.join()
        }
    }

    /**
     * `Conversation.getBenchmarkInfo()` —— TTFT / prefill / decode 的**唯一权威来源**。
     *
     * ⚠ 它是 `@ExperimentalApi`（0.18.0 的 `@RequiresOptIn(ERROR)`），官方措辞是
     * "may change or be removed without notice"。仍然用它，理由是：
     *  - 自己掐表算 TTFT 得到的数字与它不可比（是否含 prefill 的定义在 native 侧）；
     *  - 这些数字确实在花钱：`:chat` 用它记 `promptTokens / decodeTokens`，
     *    `:agent` 用它做预算反馈。恒返回 0 不是「保守」，是**账本全错**。
     *  - 它被移除时是**编译期错误**（只落在 `:llm` 一个文件里），不是线上静默失效。
     *
     * `getTokenCount()` 不是实验 API（既有代码已在用），所以只有这一处需要 opt-in。
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun benchmark(): com.google.ai.edge.litertlm.BenchmarkInfo? =
        runCatching { conversation.getBenchmarkInfo() }.getOrNull()

    private fun dead(): Outcome<GenerationResult> =
        Outcome.Err(AppError(ModelErrorCodes.SESSION_DEAD, "err_model_session_dead"))

    private companion object {
        const val TAG = "LiteRtSession"
    }
}

/** 流式通道上的三种信号。刻意不含「取消」—— 取消走协程，不排队。 */
internal sealed interface StreamSignal {
    data class Partial(val message: Message) : StreamSignal
    data object Done : StreamSignal
    data class Failed(val error: Throwable) : StreamSignal
}

/** `Message` 的纯文本部分（`Content.Text` 拼接，多模态内容忽略）。 */
internal fun Message.textOf(): String = buildString {
    for (c in contents.contents) {
        if (c is Content.Text) append(c.text)
    }
}

/**
 * LiteRT-LM 的 `ToolCall` → 本项目 `RawToolCall`。
 *
 * ★ `arguments` 是 `Map<String, Any?>`，不是 JSON 字符串。这里必须自己序列化 ——
 *   这也是上层参数统一为 String 的原因：上层对 tool call 参数只有一种形态 JSON text，
 *   不必分支处理类型。
 */
internal fun List<com.google.ai.edge.litertlm.ToolCall>.toRawToolCalls(): List<RawToolCall> =
    map { tc ->
        RawToolCall(
            name = tc.name,
            argumentsJson = toJsonObject(tc.arguments).toString(),
            callId = null,
        )
    }

/**
 * 一次生成的结果装配（同步与流式**共用**，否则两条路的统计口径会开始漂移）。
 *
 * @param rawText 同步路径取 `Message` 的正文；流式路径取差分后的全文
 * @param benchmark `Conversation.benchmarkInfo` —— TTFT 的定义（是否含 prefill）在 native 侧才是权威的，
 *                  自己掐表得到的数字与它不可比。
 */
internal fun buildResult(
    rawText: String,
    toolCalls: List<RawToolCall>,
    req: GenerationRequest,
    benchmark: com.google.ai.edge.litertlm.BenchmarkInfo?,
): GenerationResult {
    val stats = if (benchmark == null) ZERO_STATS else InferenceStats(
        prefillTokens = benchmark.lastPrefillTokenCount,
        decodeTokens = benchmark.lastDecodeTokenCount,
        ttftMs = (benchmark.timeToFirstTokenInSecond * 1000.0).toInt(),
        decodeTps = benchmark.lastDecodeTokensPerSecond,
    )
    // 被 maxOutputTokens 截断必须能看出来：否则「模型只说了半句」会被当成模型能力问题。
    // 10% 的余量：decode 计数与 maxOutputToken 未必逐 token 对齐（模板 token 计入方式不同）。
    val truncated = req.maxOutputTokens > 0 && stats.decodeTokens >= (req.maxOutputTokens * 0.9).toInt()
    return GenerationResult(
        rawText = rawText,
        toolCalls = toolCalls,
        finishReason = if (truncated) FinishReason.LENGTH else FinishReason.STOP,
        stats = stats,
    )
}

private val ZERO_STATS = InferenceStats(prefillTokens = 0, decodeTokens = 0, ttftMs = 0, decodeTps = 0.0)

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
