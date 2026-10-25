package com.ake.app.startup

import com.ake.chat.ports.ChatServices
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.data.persist.bootstrap.TemplateBootstrap
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.redact.PersistenceRedactionGate
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * # 冷启动的一次性收尾工作（2026-10 补，关千锤 P0-2）
 *
 * ## 它解决的是「方法写得对，但从来没人调」
 *
 * 两个兜底在上一轮**生产调用点为 0**，后果都是静默的：
 *
 * | 方法 | 不调的后果 |
 * |---|---|
 * | `TemplateBootstrap.bootstrap()` | 模板市场永远空白；存量人格的授权基线从不回填 ⇒ 那些 Agent 永久「只回文字」 |
 * | `ChatLoop.onColdStart()` | 上一轮进程被杀留下的 `STREAMING` 消息永不收敛 ⇒ 气泡永远转圈 |
 *
 * 两者都有单测、都过期过评审 —— 因为**没有人从启动路径上喊它们**。
 * 所以本类的第一职责不是「实现」，而是**存在**：让 `:app` 真的有一个被 `onCreate`
 * 解引用的调用点，并且这件事要能被**行为断言**守住
 * （`app/src/test/.../ColdStartWiringTest`：构造本类、断言两个依赖各被解引用一次）。
 *
 * ⚠ 上一版这里写的是「让本类有一个可以被**源码扫描**断言的调用点」。那句话现在删了：
 *   源码扫描挡不住注释（关千锤 P1-2 的负向验证：把调用点注释掉，4 条测试全绿），
 *   一句"我们有守护测试"配上那样的测试，比没有守护测试更糟 —— 它让人**以为**这个
 *   静默失败已经被挡住了。
 *
 * ## 为什么是「单例 init 自触发」而不是在 `Application` 里顺序调用
 *
 * 这与任务侧接对了的先例 `TaskReconciler` 完全一致：`TaskStateWriter` 在自己的
 * `init { writeScope.launch { reconciler(); drainLoop() } }` 里跑对账，
 * **不需要任何人记得调它** —— 只要这个单例被装配，对账就发生了。
 * 本类刻意复制这个形状（关千锤要求「用同款自触发方式，不要发明第二种」）：
 *
 * ```
 *   TaskStateWriter.init  { writeScope.launch { reconciler?.invoke(); … } }
 *   ColdStartCoordinator.init { scope.launch { bootstrap(); onColdStart() } }
 * ```
 *
 * `AkeApp` 只负责 `.get()` 一下让它被创建（和它处理 `memoryReleaser` 的 `Provider` 手法相同）。
 *
 * ## 时序：为什么不等它跑完，也不能 `runBlocking`
 *
 * 要求是「bootstrap 在 UI 首次读模板表**之前**完成」+「**不能**阻塞冷启」。
 * 这两条在 `Application.onCreate` 里不可能同时靠「等」满足，所以本类的解法是：
 *
 *  ① 在 `AkeApp.onCreate()` 里**立即启动**（Activity / Compose 都还没有跑），
 *     写库走 `:data` 的**单线程 writer scope** —— §6.2 的单写者纪律因此不被破坏
 *     （模板 / 授权 / 消息表与任务状态表在同一根写线程上排队，而不是各开一条）；
 *  ② 需要**严格**先后顺序的调用方（将来某个首屏）`awaitReady()` 就好 ——
 *     它 suspends，不阻塞主线程；
 *  ③ 不需要严格顺序的调用方（今天的模板市场）本来就是 Room `Flow`：
 *     INSERT 提交后自动再发一次 emission，空态最多存在一瞬。
 *
 * 也就是说：**启动不阻塞、顺序可等待、最终一致由 Flow 兜底**。
 *
 * ## 幂等（这条是硬要求，不是「实现里有了就行」）
 *
 * 冷启会重复发生（每次进程起来一次），所以：
 *  - 模板：`ensureBuiltins` 以 `(template_id, revision)` 为键，已是最新则**零写入**；
 *  - 授权回填：只补「授权表**完全为空**」的人格，且走 `grantAllIfAbsent`（INSERT OR IGNORE）
 *    ⇒ 重复冷启不会刷新 `granted_at`（那是审计证据），也不会把用户撤销的能力装回去；
 *  - `sweepStreaming`：`UPDATE … WHERE status='STREAMING'`，稳态命中 0 行。
 *
 * 本类**不做**任何自己的「已跑过」标记 —— 加了就等于给自己造第二份真相，
 * 而上面三条幂等性才是真正的判据。
 */
@Singleton
public class ColdStartCoordinator @Inject constructor(
    /**
     * `:data` 的单线程 writer scope（`PersistenceModule.provideWriteScope`）。
     *
     * ★ 刻意**不**自己 `CoroutineScope(Dispatchers.IO)`：那会让模板 / 授权两张表的写入
     *   绕开单写者，与 `TaskStateWriter` / `TraceBufferWriter` 的事务撞锁。
     */
    private val scope: CoroutineScope,
    /**
     * 用 [Lazy] 而不是直接注入：冷启动期把 `:llm` / `:browser` / `:ui` 整条依赖树拉起来
     * 会直接拖慢首帧 —— 与 `AkeApp.memoryReleaser` 用 `Provider` 的理由同一条。
     * 真正的构造发生在 writer 线程上（[run] 里）。
     */
    private val chat: Lazy<ChatServices>,
    private val db: Lazy<AkeDatabase>,
    private val gate: Lazy<PersistenceRedactionGate>,
    private val clock: Clock,
) {

    /** 这次冷启的结果。观测面 + 单测断言面，两个数字都可以直接进日志。 */
    public data class Report(
        val templatesWritten: Int,
        val grantsBackfilled: Int,
        val streamingSwept: Int,
        val elapsedMs: Long,
        /** 任一步失败。**不会**阻断另一步 —— 冷启不该被一段 DB 异常堵死。 */
        val failures: List<String>,
    )

    private val ready = CompletableDeferred<Report>()

    init {
        // ★ 自触发：只要本单例被装配，下面这件事就一定会发生。
        scope.launch { ready.complete(run()) }
    }

    /**
     * 等这次冷启收尾完成。
     *
     * 给「必须看到模板才能正确渲染第一帧」的调用方用（今天是 `:ui` 的模板市场，它靠 Flow
     * 自动补上，所以没有调用者）。**suspend，不阻塞主线程** —— 别在 `onCreate` 里
     * `runBlocking { awaitReady() }`，那正好是把「不能阻塞冷启」这条要求踩碎。
     */
    public suspend fun awaitReady(): Report = ready.await()

    private suspend fun run(): Report {
        val startedAt = clock.nowMillis()
        val failures = mutableListOf<String>()

        // ── ① 模板 + 存量人格的授权基线回填。顺序在前：它决定模板市场与人格是否「可用」。
        var templates = 0
        var grants = 0
        val boot = runCatching {
            TemplateBootstrap.bootstrap(
                templates = db.get().templateDao(),
                profiles = db.get().agentProfileDao(),
                grants = db.get().capabilityGrantDao(),
                gate = gate.get(),
                clock = clock,
            )
        }.onFailure {
            failures += "bootstrap:${it.message}"
            AkeLog.e(TAG, "TemplateBootstrap.bootstrap failed: ${it.message}", it)
        }.getOrNull()
        if (boot != null) {
            templates = boot.templatesWritten
            grants = boot.grantsBackfilled
        }

        // ── ② 收敛上一轮进程被杀留下的 STREAMING 脏行。
        //    与 ① 各自独立：模板失败不该让「永远转圈」的气泡继续存在。
        var swept = 0
        runCatching { chat.get().loop.onColdStart() }
            .onFailure {
                failures += "sweepStreaming:${it.message}"
                AkeLog.e(TAG, "ChatLoop.onColdStart failed: ${it.message}", it)
            }
            .onSuccess { swept = it }

        val report = Report(
            templatesWritten = templates,
            grantsBackfilled = grants,
            streamingSwept = swept,
            elapsedMs = clock.nowMillis() - startedAt,
            failures = failures,
        )
        // 稳态下（第二次及以后的冷启）这里全是 0 —— 它就该是安静的。
        AkeLog.i(
            TAG,
            "cold start done: templates=$templates grants=$grants swept=$swept " +
                "failures=${failures.size} in ${report.elapsedMs}ms",
        )
        return report
    }

    public companion object {
        public const val TAG: String = "ColdStart"
    }
}
