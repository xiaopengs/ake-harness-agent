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
    /** KV cache 上限。未指定时由设备档位决定。 */
    val maxNumTokens: Int? = null,
    /** GPU 静默失败时是否允许降级到 CPU（§6.3 探针 + 降级阶梯） */
    val allowFallback: Boolean = true,
)

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

    public fun severityOf(code: String): Severity = when (code) {
        LOAD_FAILED, OUT_OF_MEMORY, DOWNLOAD_NO_SPACE -> Severity.HIGH
        CONSTRAINT_UNSUPPORTED -> Severity.LOW
        DOWNLOAD_FAILED, CHECKSUM_MISMATCH -> Severity.HIGH
        SESSION_DEAD -> Severity.MEDIUM
        else -> Severity.MEDIUM
    }
}
