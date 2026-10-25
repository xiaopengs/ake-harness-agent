package com.ake.app.startup

import com.ake.app.AkeApp
import com.ake.chat.ports.ChatServices
import com.ake.core.common.Clock
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * # 冷启接线的**行为**守卫（关千锤 P1-2：上一版是纸做的）
 *
 * ## 上一版为什么是假的
 *
 * 它读 `ColdStartCoordinator.kt` / `AkeApp.kt` 的源码文本，断言其中出现了
 * `"coldStart.get()"`、`"TemplateBootstrap.bootstrap("` 这些字符串。
 * 关千锤的负向验证一次击杀：**把 `coldStart.get()` 整行注释掉，4 条测试全绿** ——
 * 因为注释里的字符串还在。注释/条件化/挪进 `if (false)` 都拦不住，
 * 而它唯一的价值就是"下次重构把线剪了要红"。
 *
 * ## 现在断的是行为
 *
 * | 测试 | 断言的是运行结果，不是文本 |
 * |---|---|
 * | [applicationOnCreateActuallyResolvesTheCoordinator] | `AkeApp.onCreate()` 真的调了 `coldStart.get()`：注入一个记账 `Provider`，看它被 `get()` 了**几次**。整行注释掉 ⇒ 0 次 ⇒ 红 |
 * | [constructingTheCoordinatorAloneRunsBothHooks] | 只 `new` 一个 `ColdStartCoordinator`、**不调任何方法**，然后 `awaitReady()`：两个 `Lazy` 依赖各自被解引用一次 ⇒ 证明 `init` 自触发且两条 hook 都在那条路径上。删掉 `TemplateBootstrap.bootstrap` 或 `loop.onColdStart()` 任一条 ⇒ 对应 `Lazy` 0 次 ⇒ 红；删掉 `init { scope.launch }` ⇒ `awaitReady()` 超时 ⇒ 红 |
 *
 * ## 为什么要靠反射塞 `Provider`
 *
 * `AkeApp` 的成员是 `@Inject lateinit var`，只有 Hilt 的生成代码会填它。
 * 单测里没有 Hilt，于是手工塞一个**记账**的 `Provider`：
 * 这恰好是"`onCreate` 有没有解引用它"最直接的观测面。
 * 比起起一个 Robolectric + `@HiltAndroidTest`，这不需要往构建里加任何新依赖，
 * 也不会因为 Hilt 版本升级而腐化。
 *
 * ## 为什么第二个测试直接构造真实 `ColdStartCoordinator`
 *
 * 没有用 fake 顶替它 —— 那会把这个测试变成"测 fake"。真实构造 + 两个
 * **会记账的** `Lazy`：只要 `run()` 里那两行被删，「解引用」就不会发生。
 * `Lazy` 被解引用后立刻抛异常，是为了让 `run()` 走完上半段（`runCatching` 会吞掉），
 * 不必真的连上 Room —— 这一步断的是**接线**，不是 `TemplateBootstrap` 的写库行为
 * （那有 `:data` 自己的单测）。
 */
class ColdStartWiringTest {

    @Test
    fun applicationOnCreateActuallyResolvesTheCoordinator() {
        val app = AkeApp()

        // ★ 为什么要调用两次 onCreate（这不是笔误）
        //
        // `@HiltAndroidApp` 的生成基类 `Hilt_AkeApp.onCreate()` 第一件事就是
        // `hiltInternalInject()`：它建 Dagger 组件，并把**真实**的
        // `Provider<ColdStartCoordinator>` 写进 `coldStart` 字段 —— 也就是说，
        // 只有在注入发生过之后，我们塞进去的记账 Provider 才不会被覆盖。
        // Hilt 用 `injected` 布尔保证注入只做一次，所以：
        //
        //   第 1 次 onCreate  → 完成注入（顺带用真实 provider 建了真 coordinator）
        //   塞入记账 Provider
        //   第 2 次 onCreate  → 注入被跳过 ⇒ 被测的就是 `coldStart.get()` 那一行
        //
        // 另一种写法（不调 onCreate、直接反射 `hiltInternalInject()`）能少建一次图，
        // 但那就把测试钉在 Hilt 的**私有方法名**上了；调两次 onCreate 只依赖公开契约。
        app.onCreate()

        val recording = RecordingProvider { inertCoordinator() }
        app.injectProvider("coldStart", recording)

        app.onCreate()

        // 1 次 = 接线在；0 次 = 那行被注释/删掉了（上一版源码扫描的漏洞正是这个）。
        assertThat(recording.gets).isEqualTo(1)
    }

    @Test
    fun constructingTheCoordinatorAloneRunsBothHooks() = runBlocking {
        val db = RecordingLazy<AkeDatabase>()
        val chat = RecordingLazy<ChatServices>()
        val gate = RecordingLazy<PersistenceRedactionGate>()

        // ★ 只有构造。没有任何人调 run()/bootstrap()/onColdStart()。
        val coordinator = ColdStartCoordinator(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            chat = chat,
            db = db,
            gate = gate,
            clock = FixedClock,
        )

        // 自触发是"构造完就已经在跑"：没有它这里会一直等 ⇒ 超时 ⇒ 红。
        val report = withTimeout(5_000) { coordinator.awaitReady() }

        // ① 模板 / 授权回填那条：先要拿到 AkeDatabase
        assertThat(db.accesses).isEqualTo(1)
        // ② STREAMING 收敛那条：先要拿到 ChatServices（再取 .loop.onColdStart()）
        assertThat(chat.accesses).isEqualTo(1)
        // 两条都因为"解引用即抛"而记进 failures —— 顺带证明它们**各自独立**，
        // 一条炸了不会把另一条跳过（这是冷启兜底的硬要求）。
        assertThat(report.failures).hasSize(2)
    }

    // ─────────────────────────────────────── 测试替身

    /**
     * 会记账的 `Provider`：`get()` 调了几次就是几。
     *
     * 返回的对象刻意是"哑"的：本测试只关心 `AkeApp` 有没有解引用它，
     * 不关心 coordinator 干了什么（由第二个测试负责）。
     */
    private class RecordingProvider<T : Any>(private val value: () -> T) : Provider<T> {
        var gets: Int = 0
            private set

        override fun get(): T {
            gets++
            return value()
        }
    }

    /**
     * 会记账的 `dagger.Lazy`：解引用即抛。
     *
     * ★ 注意这里实现的是 `dagger.Lazy`（`get()`），**不是** `kotlin.Lazy`（`value`）——
     * `ColdStartCoordinator` 的构造参数就是 Dagger 的那个（它要跟 Hilt 的注入方式一致）。
     * 两者同名不同物，写错时的报错是「`value` overrides nothing」。
     *
     * 抛异常是刻意的 —— 一旦让它返回真对象，这个测试就得连 Room / 整个 `:chat` 依赖树，
     * 而它要断的只是"这两行代码还在不在 init 触发的那条路径上"。
     * `ColdStartCoordinator` 内部对两个依赖都用了 `runCatching`，所以抛出会被记成
     * `failures` 而不是让协程炸掉。
     */
    private class RecordingLazy<T> : Lazy<T> {
        var accesses: Int = 0
            private set

        override fun get(): T {
            accesses++
            throw IllegalStateException("this test only asserts the wiring, not the DB access")
        }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 1_000L
    }

    /**
     * 一个**构造即用**的 coordinator，给"`AkeApp` 有没有解引用"那条测试用。
     *
     * 它的 `scope` 用一个永不执行的 dispatcher：本测试不关心它跑不跑，只关心它被不被创建。
     * 这里用 `Job()` 单独一棵协程树，避免泄漏到测试进程里。
     */
    private fun inertCoordinator(): ColdStartCoordinator = ColdStartCoordinator(
        scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        chat = RecordingLazy(),
        db = RecordingLazy(),
        gate = RecordingLazy(),
        clock = FixedClock,
    )

    /** 反射塞 Hilt 注入点。字段名在字节码里不会被 mangle，所以按名字取是稳的。 */
    private fun AkeApp.injectProvider(field: String, provider: Provider<*>) {
        val f = AkeApp::class.java.getDeclaredField(field)
        f.isAccessible = true
        f.set(this, provider)
    }
}
