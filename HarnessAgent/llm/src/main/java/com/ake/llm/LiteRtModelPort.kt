package com.ake.llm

import android.content.Context
import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.modelapi.InferenceBackend
import com.ake.core.modelapi.ModelErrorCodes
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.DeviceHealthPort
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * ★ 探针走的是**异步回调**路径（`sendMessageAsync` + `MessageCallback`），不是同步 `sendMessage`。
 * 理由：聊天侧现在真正跑的是异步流式（`generateStream`），而异步路径多一层 JNI 回调 +
 * own 线程的边。同步探针通过、异步路径失败，是「加载成功但永远不吐字」的同一类故障的
 * 更深一层版本 —— 探针必须覆盖产品真正走的那条路。
 *
 * ### KV cache 上限（§R2 · F4）
 *
 * `EngineConfig.maxNumTokens` 是 **input + output 的 KV cache 总上限**，不是输出上限。
 * 它在 LiteRT-LM 里是 **Engine 级**参数（`ConversationConfig` 只有 `maxOutputToken`，
 * 没有任何会话级的 KV 上限）⇒ **换档必然重建 Engine**，代价是重新 `initialize()`
 * 加上重灌一次权重（本类把耗时记在 [lastEngineBuildMs] 里，可直接用于「切 Agent 要不要等」的决策）。
 * 因此这里刻意让「同模型 + 同档位」直接复用既有 Engine：切人格只换 Conversation，不重建 Engine。
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

    /**
     * **引擎级生成互斥**，注入给每个会话。
     *
     * 为什么不能只靠 singleThread dispatcher：同步 `sendMessage` 会把整段解码压在那条线程上，
     * 天然串行；而 `sendMessageAsync` 立刻返回，解码跑在 native 自己的线程上 ——
     * 流式上线后，单线程不再天然挡住并发。两个会话同时流式 = token 交错串到对方流里
     * （架构 §6.2 的原话）。这把锁在**生成期间**持有，锁的是「一次生成」而不是那条线程。
     */
    private val generationLock = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var activeBackend: InferenceBackend? = null

    @Volatile
    private var loadedModelId: String? = null

    /** 当前 Engine 真实生效的 KV cache 上限（夹紧之后的）。会话侧拿它做溢出判定。 */
    @Volatile
    private var loadedMaxTokens: Int = 0

    private val _runtimeState = MutableStateFlow<ModelRuntimeState>(ModelRuntimeState.Unloaded)
    override val runtimeState: Flow<ModelRuntimeState> = _runtimeState.asStateFlow()

    private var sessionCounter = 0

    /**
     * 最近一次 Engine 重建的耗时（毫秒，含 `initialize()` 与预热探针）。
     *
     * 直接服务一个产品问题：**切 Agent 要不要显示「正在唤醒…」并等待**。
     * 换人格只重建 Conversation（毫秒级），换档/换模型才会走到这里（秒级）。
     */
    @Volatile
    public var lastEngineBuildMs: Long = 0L
        private set

    override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = engineLock.withLock {
        // ★ 档位在读盘/初始化之前就定死：它决定了 Engine 的 KV cache 尺寸，
        //   而 KV cache 是「一开始就按 maxNumTokens 分配」的，事后再想改只能重建 Engine。
        val plan = EngineTokenBudget.plan(config.maxNumTokens, deviceHealth.memoryTier())
        if (plan.clamped) {
            AkeLog.w(
                TAG,
                "maxNumTokens 请求 ${plan.clampedFrom} 被夹到 ${plan.maxNumTokens}" +
                    "（档位 ${plan.tier} 上限）—— 超过设备可承受的 KV cache 不会让上下文变长，只会 OOM",
            )
        }

        // 同模型 + 同档位 ⇒ 复用。这是「切人格不必等 Engine」的实现依据。
        if (engine != null && loadedModelId == config.modelId && loadedMaxTokens == plan.maxNumTokens) {
            return Outcome.Ok(Unit)
        }

        val soc = deviceHealth.socProfile()
        val ladder = BackendPolicy.ladder(config.backend, soc, config.allowFallback)
        val failures = ArrayList<Throwable>(ladder.size)

        Engine.setNativeMinLogSeverity(LogSeverity.ERROR) // 别让 native 刷屏 logcat

        for (candidate in ladder) {
            _runtimeState.value = ModelRuntimeState.Loading(config.modelId, candidate)
            try {
                // ★ 先放掉旧 Engine，再建新的。顺序不能反：
                //   2.6 GB 权重的两份实例 + 两份 KV cache 同时在场，等于自己把进程送到 LMK 刀下。
                //   代价是「新 Engine 建失败时没有旧的可回退」—— 但旧的那个本来就带着错误的
                //   maxNumTokens（这正是我们要重建的原因），留着它也不该继续服务。
                if (engine != null) withContext(singleThread) { closeQuietly() }

                val startedAt = System.nanoTime()
                val built = withContext(singleThread) {
                    createEngine(config, candidate, plan.maxNumTokens)
                }
                if (built == null) {
                    failures += IllegalStateException("init failed: $candidate")
                    continue
                }
                // createEngine 内部已经 initialize() + 探针；这里只需接管所有权
                engine = built
                activeBackend = candidate
                loadedModelId = config.modelId
                loadedMaxTokens = plan.maxNumTokens
                lastEngineBuildMs = (System.nanoTime() - startedAt) / 1_000_000
                AkeLog.i(
                    TAG,
                    "engine ready: backend=$candidate maxNumTokens=${plan.maxNumTokens} " +
                        "build=${lastEngineBuildMs}ms",
                )
                _runtimeState.value = ModelRuntimeState.Ready(
                    modelId = config.modelId,
                    backend = candidate,
                    // ★ 报的是**夹紧之后**的真实值：UI/排障看到 8192 就知道 32K 的请求被降了档，
                    //   而不是以为「窗口已经是 32K」。
                    contextTokens = plan.maxNumTokens,
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
        // 一条候选都没成：Engine 已经不在（closeQuietly 在每轮开始时清掉），
        // 这里必须把"当前生效值"一并归零，否则 currentMaxTokens / loadedModelId 会继续
        // 报告一个已经不存在的事实 —— 而会话侧的溢出判定正是拿它当基准。
        loadedModelId = null
        loadedMaxTokens = 0
        _runtimeState.value = ModelRuntimeState.Failed(config.modelId, error)
        return Outcome.Err(error)
    }

    override suspend fun unload(): Outcome<Unit> = engineLock.withLock {
        withContext(singleThread) { closeQuietly() }
        loadedModelId = null
        activeBackend = null
        loadedMaxTokens = 0
        _runtimeState.value = ModelRuntimeState.Unloaded
        Outcome.Ok(Unit)
    }

    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = engineLock.withLock {
        val current = engine
            ?: return Outcome.Err(AppError(ModelErrorCodes.NOT_LOADED, "err_model_not_loaded"))

        val cap = loadedMaxTokens
        val reserve = spec.maxOutputTokens ?: DEFAULT_MAX_OUTPUT

        // ★ 复水成本前置判定（§R2 的第 2 条：超限必须**可预期**）。
        //   这里装不下就在 native 之外拒绝 —— 一旦交给引擎，超限的表现是 native OOM/abort，
        //   进程直接消失，没有任何 Outcome 可以返回。
        val prefill = EngineTokenBudget.prefillCostOf(spec.systemInstruction, spec.initialMessages, ::estimateTokens)
        val fit = EngineTokenBudget.fit(cap, prefill, reserve)
        if (fit is EngineTokenBudget.Fit.Overflow) {
            AkeLog.w(
                TAG,
                "openSession 拒绝：prefill=$prefill + 预留输出=$reserve = ${fit.needed} > " +
                    "maxNumTokens=$cap（超 ${fit.overflow}）model=${spec.modelId} task=${spec.taskId}",
            )
            return Outcome.Err(
                AppError(
                    code = ModelErrorCodes.CONTEXT_OVERFLOW,
                    reasonKey = "err_model_context_overflow",
                    severity = Severity.MEDIUM,
                ),
            )
        }

        return try {
            withContext(singleThread) {
                val conversation = current.createConversation(
                    ConversationConfig(
                        systemInstruction = spec.systemInstruction?.let { Contents.of(it) },
                        // ★ 复水：把压缩后的历史在**开会话时**一次性灌回引擎，由它做一次 prefill
                        //   重建 KV cache（§3.5）。之后每轮只是普通增量 prefill。
                        //   这里传空列表的旧版本 = 每次切回历史会话模型都失忆。
                        initialMessages = RehydrationMessages.toLiteRt(spec.initialMessages),
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
                        // 会话侧的所有溢出判定都对着这个数字，而不是自己猜一个窗口
                        maxCacheTokens = cap,
                        generationLock = generationLock,
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

    /** 当前生效的 KV cache 上限（夹紧后）。会话/预算侧可据此对齐自己的 workingWindow。 */
    val currentMaxTokens: Int get() = loadedMaxTokens

    // ────────────────────────────────────────────────────────────── 内部

    /**
     * 建 Engine + `initialize()` + **预热探针**。返回 null 表示这个候选不可用。
     *
     * 注意 §6.2 坑 4：**只有在 `initialize()` 成功之后才允许持有 Engine**。
     * 对 `initialize()` 抛过异常的 Engine 调 `close()` 也会抛异常（它没持有资源）。
     *
     * @param maxNumTokens 已按设备档位夹紧的 KV cache 上限，**必填**（不给引擎自己决定的机会）
     */
    private fun createEngine(config: ModelLoadConfig, backend: InferenceBackend, maxNumTokens: Int): Engine? {
        val nativeDir = config.npuLibraryDir ?: appContext.applicationInfo.nativeLibraryDir
        val built = Engine(
            EngineConfig(
                modelPath = config.path,
                backend = backend.toLiteRt(nativeDir),
                // ★ F4：这是本模块唯一能挡住「聊天几十轮后 native OOM」的地方。
                //   `EngineConfig` 的其它参数一律留默认 —— 没有实测就不动。
                maxNumTokens = maxNumTokens,
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
     *
     * ★ 走**异步回调**路径（与 [LiteRtSession.generateStream] 同一条）：
     *   流式上线后，产品的第一条消息就是异步的。只用同步 `sendMessage` 探针，
     *   等于「探针通过、产品路径未验证」—— 缺 `libOpenCL.so` 声明那类故障会从
     *   「加载即失败」退化成「加载成功但永远不吐字」。
     *
     * ⚠ 这里会**阻塞** singleThread 上最长 [PROBE_TIMEOUT_MS]：这是刻意的 ——
     *   原本就要求「探针跑完才允许宣告 Ready」，而且 native 回调来自它自己的工作线程，
     *   不依赖这条线程，不存在互相等待。
     */
    private fun probe(engine: Engine): Boolean {
        val conv: Conversation = try {
            engine.createConversation(
                ConversationConfig(maxOutputToken = PROBE_MAX_OUTPUT, automaticToolCalling = false),
            )
        } catch (e: LiteRtLmJniException) {
            AkeLog.w(TAG, "probe failed to create conversation", e)
            return false
        } catch (_: Throwable) {
            return false
        }

        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val gotToken = AtomicBoolean(false)

        return try {
            conv.sendMessageAsync(
                text = PROBE_PROMPT,
                callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (message.textOf().isNotEmpty()) gotToken.set(true)
                    }

                    override fun onDone() {
                        done.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        failure.set(throwable)
                        done.countDown()
                    }
                },
                maxOutputToken = PROBE_MAX_OUTPUT,
            )
            val finished = done.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                AkeLog.w(TAG, "probe timed out after ${PROBE_TIMEOUT_MS}ms")
                runCatching { conv.cancelProcess() }
            }
            val err = failure.get()
            if (err != null) {
                AkeLog.w(TAG, "probe failed with $err", err)
            }
            finished && err == null && gotToken.get()
        } catch (e: LiteRtLmJniException) {
            AkeLog.w(TAG, "probe failed with JNI error", e)
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: Throwable) {
            false
        } finally {
            try { conv.close() } catch (_: Throwable) { }
        }
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
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_MAX_OUTPUT = 256

        /** 探针输出长度：够验证「能出 token」，又不至于让一次 load 多花几秒。 */
        private const val PROBE_MAX_OUTPUT = 4
        private const val PROBE_PROMPT = "hi"

        /**
         * 探针超时。4 个 token 在任何能跑的机型上都远快于此；超过它基本可以判定
         * 「这条 backend 装死」而不是「设备慢」—— 这条判断的价值是别让一个坏 backend
         * 把 load() 拖到 60 秒，因为 load() 之后紧接着就是用户在等第一条消息。
         */
        private const val PROBE_TIMEOUT_MS = 20_000L
    }
}
