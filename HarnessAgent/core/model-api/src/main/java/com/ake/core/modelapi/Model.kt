package com.ake.core.modelapi

import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.common.SessionId
import com.ake.core.common.StepId
import kotlinx.coroutines.flow.Flow

/**
 * 模型推理能力抽象（架构 §2.2）—— 彻底隔离 LiteRT-LM。
 *
 * **为什么这一层不能省**：R5 要求 `EngineConfig` / `Conversation` / `Backend` 这些名字
 * 不允许出现在 L4/L5/L6 的任何文件里。这不是洁癖 —— 是我们换推理引擎的唯一保险。
 * 端侧推理引擎是一年换一代的东西，而 prompt / 预算 / 压缩这些业务逻辑要活三年。
 *
 * 本文件形态**严格按 `agent/EXPECTED_CORE_API.md` §1 原文**，未改名、未加字段、未移包路径。
 */

/** 推理后端偏好。刻意与 LiteRT-LM 的 `Backend` 无关，避免 SDK 类型泄漏到上层。 */
public enum class InferenceBackend { CPU, GPU, NPU, GOOGLE_TENSOR }

public sealed interface OutputShape {
    /** 约束到 StepAction JSON Schema（主循环唯一形态） */
    public data object AgentStep : OutputShape

    /** 自由文本，仅用于最终答案渲染 */
    public data object FreeText : OutputShape

    /** 工具内部二次抽取用 */
    public data class JsonSchema(val schema: String) : OutputShape
}

public sealed interface ThinkingBudget {
    public data object Off : ThinkingBudget
    public data class Capped(val tokens: Int) : ThinkingBudget
}

/** 一次受约束的生成请求。不含对话历史 —— 历史由引擎内部的 Session 持有。 */
public data class GenerationRequest(
    val stepId: StepId,
    /** 本轮全部内容，已由 ContextAssembler 裁好 */
    val userTurn: String,
    val outputShape: OutputShape,
    val maxOutputTokens: Int,
    val thinking: ThinkingBudget,
    /** 超时由调用方管理，引擎不自行判断 */
    val deadlineMs: Long,
)

public enum class FinishReason { STOP, LENGTH, PREEMPTED, ERROR }

/** 结构等价 LiteRT-LM 的 `Message.toolCalls` */
public data class RawToolCall(
    val name: String,
    val argumentsJson: String,
    val callId: String?,
)

public data class InferenceStats(
    val prefillTokens: Int,
    val decodeTokens: Int,
    val ttftMs: Int,
    val decodeTps: Double,
)

public data class GenerationResult(
    val rawText: String,
    val toolCalls: List<RawToolCall>,
    val finishReason: FinishReason,
    val stats: InferenceStats,
)

/** 加载配置。字段名对齐架构 §6.2 的 `config.path` / `config.backend` / `config.cacheDir` 用法。 */
public data class ModelLoadConfig(
    val modelId: String,
    /** 必须是设备文件系统上的绝对路径（官方明示：never in assets or in the APK） */
    val path: String,
    /** ★ 务必设置，官方明确可显著改善二次及以后的加载用时 */
    val cacheDir: String? = null,
    val backend: InferenceBackend = InferenceBackend.GPU,
    /** NPU 需要厂商原生库目录；且必须是该 SoC 变体的模型文件 */
    val npuLibraryDir: String? = null,
    /**
     * KV cache 上限（**input + output 的总上限**，不是输出上限）。
     *
     * 三条语义（`docs/05-chat-agents-design.md` §R2 / F4）：
     *  1. `null` ⇒ 由实现按设备内存档位取值（`:llm` 是 4096 / 8192 / 16384）。
     *  2. 非 null 也**不是免检**：实现会把超过设备档位上限的值夹到上限并记一条 warning ——
     *     传一个 32K 的理论窗口进 8GB 机型，换来的不是更长的上下文，而是 native 层 OOM。
     *  3. 它在 LiteRT-LM 里是 **Engine 级**参数（`EngineConfig`），换档 = 重建 Engine，
     *     不是换 Session。所以这个值在 `load()` 之后对同一 Engine 恒定。
     */
    val maxNumTokens: Int? = null,
    /** GPU 静默失败时是否允许降级到 CPU（§6.3 探针 + 降级阶梯） */
    val allowFallback: Boolean = true,
)

/**
 * 一轮对话的形态（复水用）。
 *
 * ★ **刻意不复用 `RawToolCall` / `GenerationResult`**：它们描述的是「模型刚吐出来的东西」，
 * 而这里描述的是「我们要灌回引擎的历史」。两者字段交集为空，合成一个类型只会让
 * `openSession` 的调用点开始猜测「这个字段这时候有没有意义」。
 */
public data class ConversationTurn(val role: TurnRole, val text: String)

/** `USER` / `MODEL` —— 与 chat template 的两个角色一一对应。**没有 SYSTEM**：系统提示走 `SessionSpec.systemInstruction`。 */
public enum class TurnRole { USER, MODEL }

public data class SessionSpec(
    val modelId: String,
    val taskId: String,
    val systemInstruction: String? = null,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
    val seed: Int? = null,
    val thinking: ThinkingBudget = ThinkingBudget.Off,
    /**
     * 是否开启引擎侧的 response-format 约束。
     * MVP 主循环用 AgentStep ⇒ 必须 true；当某机型/某个模型给出的 schema 不被支持时降级为 false，
     * 由 StepActionParser 的文本容错通道兜住。
     */
    val enableJsonConstraint: Boolean = true,
    /**
     * ★ 复水载体（`docs/05-chat-agents-design.md` §2.3 / §3.5，本轮 MVP 新增）。
     *
     * 把「压缩后的历史」在**开会话时**一次性灌回引擎，由引擎做一次 prefill 重建 KV cache。
     * 为什么不做成「第一轮对话里把历史拼进 userTurn」：
     *  - 拼进 userTurn ⇒ 历史每一轮都占 prompt 预算，且每一轮都要重新 prefill 一遍；
     *  - 灌进 initialMessages ⇒ 它只付一次，之后是普通的 KV 复用。
     * 端侧 32 轮聊天的原文约 40k token，在 CPU 回退机上等于 **72 秒**冷启 —— 这个差别是数量级的。
     *
     * ⚠ **默认空 ⇒ 对既有调用方零影响**：`AgentLoop` 每次任务开新会话、从来不灌历史。
     * ⚠ :llm 侧的消费（`Conversation` 的 initial messages 参数）属于 §11.2 的交付项，
     *   本字段先定型并开始被 `:chat` 填充；引擎侧接上之前它只是被忽略的入参。
     */
    val initialMessages: List<ConversationTurn> = emptyList(),
)

public sealed interface ModelRuntimeState {
    public data object Unloaded : ModelRuntimeState
    public data class Loading(val modelId: String, val backend: InferenceBackend) : ModelRuntimeState
    public data class Ready(
        val modelId: String,
        val backend: InferenceBackend,
        val contextTokens: Int,
    ) : ModelRuntimeState

    public data class Failed(val modelId: String, val error: com.ake.core.common.AppError) : ModelRuntimeState
}

/**
 * 模型侧抽象。
 *
 * **实现必须用单线程 dispatcher 串行化**（LiteRT-LM 非线程安全，架构 §6.2）：
 * 两个并发生成会把 token 交错串到对方的流里，表现为「两个任务互相吐对方的字」。
 */
public interface ModelPort {
    public suspend fun load(config: ModelLoadConfig): Outcome<Unit>

    public suspend fun unload(): Outcome<Unit>

    public val runtimeState: Flow<ModelRuntimeState>

    /** 会话句柄。同一 Task 内全程复用，严禁每步重建（KV cache！架构 §4.3） */
    public suspend fun openSession(spec: SessionSpec): Outcome<ModelSession>

    /**
     * 估算 token 数。必须快（本地估算），**禁止跑模型** —— 整个上下文预算机制依赖于此。
     *
     * 如果这一步要问模型才能知道一段文本多贵，预算反馈回路的时间常数就会超过单步成本，
     * 整个「压缩 ⇄ 预算」的体系就塌了。
     */
    public fun estimateTokens(text: String): Int
}

public interface ModelSession : AutoCloseable {
    public val sessionId: SessionId

    public suspend fun generate(req: GenerationRequest): Outcome<GenerationResult>

    /** 已占用 KV cache token 数，用于预判压缩时机 */
    public suspend fun currentCacheTokens(): Int

    /**
     * 流式生成：解码途中把**新增片段**逐个交出去，返回值与 [generate] 完全同构。
     *
     * ## 为什么带默认实现（而不是纯抽象方法）
     *
     * 这个接口已经被 `:agent` / `:chat` 的测试夹具实现（`FakeModelSession`）。
     * 加一个纯抽象方法会让那些**不在本模块**、也不该由本模块改的文件当场编译失败 ——
     * 而它们的语义（一次性吐全文）在新契约下**依然合法**，只是不够快。
     * 所以默认实现就写成「一次性」，由 `:llm` 的实现覆盖成真流式：
     * 契约升级不该以别人的红 CI 为代价，只该以「谁的实现更完整」为区别。
     *
     * ## 调用方必须能依赖的三条语义（实现者不得违反）
     *
     * 1. **`onDelta` 的线程/协程上下文 = 调用方的**。引擎回调通常在它自己的 native 线程上，
     *    实现**必须**先把它搬回调用方上下文（channel / channelFlow / 等价机制）再调 `onDelta`。
     *    直接回调线程 emit 会让 UI 侧在非 Compose 线程上改 state。
     * 2. **`onDelta` 的入参是「新增的那一段」**，不是累计全文；空片段不投（UI 白重组）。
     *    累计语义的引擎（同一个 Message 反复吐全文）由实现负责差分成增量。
     * 3. **跨 chunk 的 special token 清洗不在这里做**。那是 `:chat` 的 `DeltaScrubber` 的职责 ——
     *    两边都做一遍会重复吞字符，且「半个 token 横跨 chunk」只有拥有跨 chunk 缓冲的那一侧能做对。
     *
     * 取消语义：[generateStream] 的协程被取消 ⇒ 实现**必须**真正中断 native 解码
     * （LiteRT-LM 是 `Conversation.cancelProcess()`），而不是只让 Flow 停止收集。
     * 取消以 `CancellationException` 原样透传，不包成 `Outcome.Err`。
     */
    public suspend fun generateStream(
        req: GenerationRequest,
        onDelta: suspend (String) -> Unit,
    ): Outcome<GenerationResult> {
        val r = generate(req)
        if (r is Outcome.Ok && r.value.rawText.isNotEmpty()) onDelta(r.value.rawText)
        return r
    }
}

/** 模型侧错误的统一语义。这是 core 提供的默认值，具体引擎可以再细分 code。 */
public object ModelErrorCodes {
    public const val LOAD_FAILED: String = "MODEL_LOAD_FAILED"
    public const val NOT_LOADED: String = "MODEL_NOT_LOADED"
    public const val OPENCL_MISSING: String = "MODEL_OPENCL_MISSING"
    public const val GENERATE_FAILED: String = "MODEL_GENERATE_FAILED"
    public const val OUT_OF_MEMORY: String = "MODEL_OOM"
    public const val CONSTRAINT_UNSUPPORTED: String = "MODEL_CONSTRAINT_UNSUPPORTED"
    public const val SESSION_DEAD: String = "MODEL_SESSION_DEAD"
    public const val DOWNLOAD_FAILED: String = "MODEL_DOWNLOAD_FAILED"
    public const val DOWNLOAD_NO_SPACE: String = "MODEL_DOWNLOAD_NO_SPACE"
    public const val CHECKSUM_MISMATCH: String = "MODEL_CHECKSUM_MISMATCH"

    /**
     * 上下文装不下：prompt + 预留输出 > KV cache 上限。
     *
     * ★ 它是**可预期的正常分支**，不是引擎故障 —— 上层收到它就压缩历史再重试，
     * 这也是它必须存在的原因：没有这个 code，唯一的出路就是撞 native（进程被杀，
     * 没有任何 code 可以返回）。`docs/05-chat-agents-design.md` §R2 的第 2 条。
     */
    public const val CONTEXT_OVERFLOW: String = "MODEL_CONTEXT_OVERFLOW"

    public fun severityOf(code: String): Severity = when (code) {
        LOAD_FAILED, OUT_OF_MEMORY, DOWNLOAD_NO_SPACE -> Severity.HIGH
        CONSTRAINT_UNSUPPORTED -> Severity.LOW
        DOWNLOAD_FAILED, CHECKSUM_MISMATCH -> Severity.HIGH
        SESSION_DEAD -> Severity.MEDIUM
        // 触顶是预算接管的信号（压缩 / 降档），有明确的下一步动作，因此不是 HIGH。
        CONTEXT_OVERFLOW -> Severity.MEDIUM
        else -> Severity.MEDIUM
    }
}
