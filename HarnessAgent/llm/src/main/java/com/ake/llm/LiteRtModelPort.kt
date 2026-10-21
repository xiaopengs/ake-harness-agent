package com.ake.llm

import android.content.Context
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.modelapi.InferenceBackend
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ModelErrorCodes
import com.ake.core.ports.DeviceHealthPort
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ModelPort` 的 LiteRT-LM 实现（架构 §6.1 / §6.2 / §6.3）。
 *
 * ### 单线程串行化是**硬**要求，不是优化
 *
 * LiteRT-LM 的 native 实现不是线程安全的：两个并发生成会把 token 交错串到对方的流里。
 * 所以这里用「单线程 dispatcher + Mutex」双保险 —— dispatcher 提供执行语义，Mutex 提供显式互斥，
 * 任何一条漏 elsewhere 都不会立刻变成「偶发乱码」这种最难查的 bug。
 *
 * ### 为什么必须「探针 + 降级」而不是直接 try
 *
 * GPU backend 的失败是**静默**的：`initialize()` 成功，第一条消息才报
 * `Can not find OpenCL library on this device`（Android 12+ 限制加载未声明的厂商原生库）。
 * 于是 `load()` 的流程是：按 [BackendPolicy.ladder] 逐个候选 → `initialize()` → **探针** →
 * 失败就 `close()` 并换下一个。
 *
 * ### 资源管理
 *
 * 一个常驻 2.58 GB 的进程在中端机上会持续挤压 LMK，被杀掉的往往是我们赖以执行动作的 WebView。
 * 所以 [unload] 与低内存回调里的释放是**保护 WebView 的手段**，不是省电优化（§6.4）。
 */
@Singleton
// ★ 必须 public：`:app` 的组合根要 `@Binds` 它到 `ModelPort`。
//   `internal` 会让 `:app` 抛 "Cannot access 'class LiteRtModelPort': it is internal in file"
//   —— 这是「模块封装」与「组合根必须看见实现类」之间唯一的、必要的破口。
public class LiteRtModelPort @Inject constructor(
    private val appContext: Context,
    private val deviceHealth: DeviceHealthPort,
) : ModelPort {

    private val singleThread: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { r -> Thread(r, THREAD_NAME) }
        .asCoroutineDispatcher()

    private val engineLock = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var activeBackend: InferenceBackend? = null

    @Volatile
    private var loadedModelId: String? = null

    private val _runtimeState = MutableStateFlow<ModelRuntimeState>(ModelRuntimeState.Unloaded)
    override val runtimeState: Flow<ModelRuntimeState> = _runtimeState.asStateFlow()

    private var sessionCounter = 0

    override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = engineLock.withLock {
        val current = engine
        if (current != null && loadedModelId == config.modelId) {
            return Outcome.Ok(Unit)
        }

        val soc = deviceHealth.socProfile()
        val ladder = BackendPolicy.ladder(config.backend, soc, config.allowFallback)
        val failures = ArrayList<Throwable>(ladder.size)

        Engine.setNativeMinLogSeverity(LogSeverity.ERROR) // 别让 native 刷屏 logcat

        for (candidate in ladder) {
            _runtimeState.value = ModelRuntimeState.Loading(config.modelId, candidate)
            try {
                val built = withContext(singleThread) {
                    createEngine(config, candidate)
                }
                if (built == null) {
                    failures += IllegalStateException("init failed: $candidate")
                    continue
                }
                withContext(singleThread) { closeQuietly() }
                engine = built
                activeBackend = candidate
                loadedModelId = config.modelId
                _runtimeState.value = ModelRuntimeState.Ready(
                    modelId = config.modelId,
                    backend = candidate,
                    contextTokens = config.maxNumTokens ?: DEFAULT_CONTEXT_TOKENS,
                )
                return Outcome.Ok(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failures += t
            }
        }

        val error = AppError(
            code = ModelErrorCodes.LOAD_FAILED,
            reasonKey = "err_model_load_failed",
            cause = failures.firstOrNull(),
            severity = Severity.HIGH,
        )
        _runtimeState.value = ModelRuntimeState.Failed(config.modelId, error)
        return Outcome.Err(error)
    }

    override suspend fun unload(): Outcome<Unit> = engineLock.withLock {
        withContext(singleThread) { closeQuietly() }
        loadedModelId = null
        activeBackend = null
        _runtimeState.value = ModelRuntimeState.Unloaded
        Outcome.Ok(Unit)
    }

    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = engineLock.withLock {
        val current = engine
            ?: return Outcome.Err(AppError(ModelErrorCodes.NOT_LOADED, "err_model_not_loaded"))
        return try {
            withContext(singleThread) {
                val conversation = current.createConversation(
                    ConversationConfig(
                        systemInstruction = spec.systemInstruction?.let { Contents.of(it) },
                        initialMessages = emptyList(),
                        tools = emptyList(),
                        samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(
                            topK = spec.topK ?: DEFAULT_TOP_K,
                            topP = spec.topP ?: DEFAULT_TOP_P,
                            temperature = spec.temperature ?: DEFAULT_TEMPERATURE,
                            seed = spec.seed ?: 0,
                        ),
                        // ★ 本项目必须关掉自动工具回路：它会绕过 PolicyPort 且不产生 trace（§6.1）
                        automaticToolCalling = false,
                        maxOutputToken = spec.maxOutputTokens ?: DEFAULT_MAX_OUTPUT,
                        thinkingConfig = spec.thinking.toLiteRt(),
                        enableResponseFormat = spec.enableJsonConstraint,
                    ),
                )
                sessionCounter++
                Outcome.Ok<ModelSession>(
                    LiteRtSession(
                        sessionId = com.ake.core.common.SessionId("${spec.modelId}#$sessionCounter"),
                        spec = spec,
                        conversation = conversation,
                        singleThread = singleThread,
                    ),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Outcome.Err(
                AppError(ModelErrorCodes.SESSION_DEAD, "err_model_session_create_failed", t),
            )
        }
    }

    override fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)

    /** 内存压力下的强制释放（§11 R1）。不需要 lock 保证正确性：字段都是 volatile，且幂等。 */
    suspend fun releaseUnderMemoryPressure() {
        unload()
    }

    val currentBackend: InferenceBackend? get() = activeBackend

    // ────────────────────────────────────────────────────────────── 内部

    /**
     * 建 Engine + `initialize()` + **预热探针**。返回 null 表示这个候选不可用。
     *
     * 注意 §6.2 坑 4：**只有在 `initialize()` 成功之后才允许持有 Engine**。
     * 对 `initialize()` 抛过异常的 Engine 调 `close()` 也会抛异常（它没持有资源）。
     */
    private fun createEngine(config: ModelLoadConfig, backend: InferenceBackend): Engine? {
        val nativeDir = config.npuLibraryDir ?: appContext.applicationInfo.nativeLibraryDir
        val built = Engine(
            EngineConfig(
                modelPath = config.path,
                backend = backend.toLiteRt(nativeDir),
                cacheDir = config.cacheDir ?: File(appContext.cacheDir, "litertlm").absolutePath,
            ),
        )
        return try {
            built.initialize()
            if (!probe(built)) {
                // 探针失败 ⇒ 这条 backend 真的跑不了（典型就是缺 libOpenCL.so 声明）
                try { built.close() } catch (_: Throwable) { }
                return null
            }
            built
        } catch (t: Throwable) {
            try { built.close() } catch (_: Throwable) { }
            null
        }
    }

    /**
     * 预热探针（§6.3）：初始化后跑一条极短消息，验证这条 backend **真的能出 token**。
     *
     * 为什么值得这么麻烦：GPU 路径能在某些机型上「初始化成功但一半失败」，
     * 而 GPU vs CPU 是 6.8 倍 prefill 差距 —— 这不是「能不能跑」，是「能不能交付」的区别。
     */
    private fun probe(engine: Engine): Boolean = try {
        val conv: Conversation = engine.createConversation(
            ConversationConfig(maxOutputToken = 4, automaticToolCalling = false),
        )
        try {
            conv.sendMessage("hi")
            true
        } finally {
            try { conv.close() } catch (_: Throwable) { }
        }
    } catch (e: LiteRtLmJniException) {
        com.ake.core.common.AkeLog.w(TAG, "probe failed with JNI error", e)
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: Throwable) {
        false
    }

    private fun closeQuietly() {
        engine?.let { e ->
            try { e.close() } catch (_: Throwable) { }
        }
        engine = null
    }

    companion object {
        private const val TAG = "LiteRtModelPort"
        private const val THREAD_NAME = "litert-engine"
        private const val DEFAULT_CONTEXT_TOKENS = 8_192
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_MAX_OUTPUT = 256
    }
}
