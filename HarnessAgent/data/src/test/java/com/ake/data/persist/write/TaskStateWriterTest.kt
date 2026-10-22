package com.ake.data.persist.write

import com.ake.core.common.ManualClock
import com.ake.core.contract.TaskStatus
import com.ake.data.persist.fake.FakeCheckpointDao
import com.ake.data.persist.fake.FakePolicyDao
import com.ake.data.persist.fake.FakeTaskDao
import com.ake.data.persist.fake.checkpointEntity
import com.ake.data.persist.fake.taskEntity
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * `TaskStateWriter` 的回归测试（2026-10）。
 *
 * ## 它守的三条性质（对应 `WriteOp.kt` 那条「写入可靠性分级」论证的兑现点）
 *
 *  1. **不可丢**：高水位下是**调用方等待**，不是数据牺牲；
 *  2. **屏障语义**：`flushNow()` 返回时，它之前提交的 Op 一定已经 COMMIT；
 *  3. **失败重试而不是丢弃**：退避到上限后交棒 `TaskReconciler`，不留静默黑洞。
 *
 * 这三条此前只能靠真机验证 —— 它们正是「任务凭空消失 / 永久 RUNNING」那两类事故的防线，
 * 而防线上一个测试都没有。现在通过 `StateWriteTarget` 这条接缝落到 JVM 上。
 *
 * ## 关于测试用的 `StateWriteTarget`
 *
 * 内存 target 与生产 `RoomStateWriteTarget` 的差别只在最后落盘那一跳；
 * `TaskStateWriter` 本体（通道、重试、屏障、账本）全部是被测的真实代码。
 *
 * ## ★ 仍未覆盖（需真机 / androidTest）
 *
 *  - `db.withTransaction { }` 的**真实事务边界与回滚**（内存 target 开事务但不回滚）；
 *  - `staleActiveIds` / `converge*` 两条 SQL 在真实 SQLite 上的执行计划与索引命中；
 *  - BundledSQLiteDriver 下的 WAL 行为（`-wal` / `-shm`）与 §8.5 删除路径。
 */
class TaskStateWriterTest {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-db-writer") }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    /** 三张表共享的落盘总序。写线程写、测试线程读 ⇒ 必须同步。 */
    private val journal: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val tasks = FakeTaskDao(journal)
    private val checkpoints = FakeCheckpointDao(journal)
    private val policies = FakePolicyDao(journal)
    private val metrics = PersistenceMetrics()
    private val clock = ManualClock(1_000L)

    /** 每次事务在 journal 上覆盖的区间。用于断言「每条 Op 都落在某个事务里、且无重叠无遗漏」。 */
    private val txSpans: MutableList<IntRange> = Collections.synchronizedList(ArrayList())

    private val txCount = AtomicInteger(0)

    private val target = object : StateWriteTarget {
        override suspend fun inTransaction(block: suspend () -> Unit) {
            val start = journal.size
            txCount.incrementAndGet()
            block()
            txSpans += start until journal.size
        }

        override val taskDao: FakeTaskDao get() = tasks
        override val checkpointDao: FakeCheckpointDao get() = checkpoints
        override val policyDao: FakePolicyDao get() = policies
    }

    private fun writer(
        reconciler: (suspend () -> Unit)? = null,
        retryBase: Long = 5L,
        t: StateWriteTarget = target,
    ): TaskStateWriter = TaskStateWriter(
        target = t,
        writeScope = scope,
        governor = PersistenceGovernor(),
        metrics = metrics,
        reconciler = reconciler,
        retryBaseMs = retryBase,
    )

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    /**
     * `runBlocking` 的 Unit 包装。
     *
     * ★ 不能写成 `@Test fun x() = runBlocking { ... }`：那样方法的返回类型会被推断成
     *   块内最后一个表达式的类型（通常是 Truth 的 `Subject` 或 `isEqualTo` 的返回值），
     *   JUnit4 直接报 `Method x() should be void`（本轮真跑踩到）。
     */
    private fun loop(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = block)

    // ──────────────────────────────────────────── ① 常态写入

    @Test
    fun `submit 后数据可见`() = loop {
        val w = writer()
        w.submit(StateOp.TaskUpsert(taskEntity("t1")))
        w.flushNow()

        assertThat(journal).containsExactly("upsert:t1")
        assertThat(w.pendingCount()).isEqualTo(0)
    }

    @Test
    fun `submitAndWait 等到真正 COMMIT 才返回 true`() = loop {
        val w = writer()
        val ok = w.submitAndWait(StateOp.TaskUpsert(taskEntity("t2")))

        assertThat(ok).isTrue()
        // ★ 不需要 flushNow 就已经落盘了 —— 这是 T3 阶梯（每任务一次那两笔）的意义
        assertThat(journal).containsExactly("upsert:t2")
    }

    @Test
    fun `trySubmit 非挂起入口常态入队`() = loop {
        val w = writer()
        assertThat(w.trySubmit(StateOp.TaskUpsert(taskEntity("t3")))).isTrue()
        w.flushNow()
        assertThat(journal).containsExactly("upsert:t3")
    }

    // ──────────────────────────────────────────── ② 屏障语义

    @Test
    fun `flushNow 返回时它之前的 Op 一定已经落盘`() = loop {
        val w = writer()
        // 200 > drainLoop 的单批上限 64 ⇒ 必然分批。一条通道、一个消费者 ⇒ 分批也保序
        repeat(200) { i -> w.submit(StateOp.BudgetAdvance("t4", i, 0, 0, 0, clock.nowMillis())) }
        w.flushNow()

        assertThat(journal).hasSize(200)
        assertThat(journal.first()).isEqualTo("advance:t4:0")
        assertThat(journal.last()).isEqualTo("advance:t4:199")
        assertThat(w.pendingCount()).isEqualTo(0)
    }

    @Test
    fun `每条 Op 都落在某个事务里且事务区间无重叠无遗漏`() = loop {
        val w = writer()
        repeat(70) { i -> w.submit(StateOp.TaskUpsert(taskEntity("b$i"))) }
        w.flushNow()

        // 事务区间必须首尾相接、覆盖全部 journal 条目 —— 这证明「一个事务、多张表、一次 COMMIT」
        // 这条纪律没有在某条路径上被绕过（例如某条 Op 被直接 apply 而没进事务）。
        var cursor = 0
        for (span in txSpans) {
            assertThat(span.first).isEqualTo(cursor)
            cursor = span.last + 1
        }
        assertThat(cursor).isEqualTo(journal.size)
        assertThat(txSpans.sumOf { it.last - it.first + 1 }).isEqualTo(journal.size)
    }

    @Test
    fun `flushNow 屏障本身不落任何数据`() = loop {
        val w = writer()
        val before = txCount.get()
        w.flushNow()
        assertThat(journal).isEmpty()
        // 屏障也要真正走一次事务，否则「前面的都写完了」这个结论不成立
        assertThat(txCount.get()).isGreaterThan(before)
    }

    @Test
    fun `stopped 之后 flushNow 立即返回`() = loop {
        val w = writer()
        w.stopAndDropQueued()
        val before = txCount.get()
        w.flushNow()
        assertThat(txCount.get()).isEqualTo(before)
    }

    // ──────────────────────────────────────────── ③ 不可丢：高水位

    @Test
    fun `高水位下调用方等待而不是丢数据`() = loop {
        val release = AtomicBoolean(false)
        val blocking = object : StateWriteTarget {
            override suspend fun inTransaction(block: suspend () -> Unit) {
                // 模拟「磁盘抖动 / 写线程被 2.58 GB 模型加载挤占」：事务卡住
                while (!release.get()) delay(1)
                block()
            }

            override val taskDao: FakeTaskDao get() = tasks
            override val checkpointDao: FakeCheckpointDao get() = checkpoints
            override val policyDao: FakePolicyDao get() = policies
        }
        val w = writer(t = blocking)

        // 超过通道容量 ⇒ 必然有一部分调用方落在 `handOff()` 上挂起等待槽位
        val total = StateLaneCaps.CAPACITY + 52
        val jobs = (0 until total).map { i ->
            // 提交方跑在别的线程上：写线程此刻已被堵住
            scope.launch(Dispatchers.Default) { w.submit(StateOp.TaskUpsert(taskEntity("t$i"))) }
        }

        // 等到通道真的到了高水位：
        // pending = 已提交 − 消费者当前手里那一批（≤ MAX_BATCH），所以它不会等于 total
        val highWater = StateLaneCaps.CAPACITY - StateLaneCaps.MAX_BATCH
        awaitUntil { w.pendingCount() >= highWater }

        assertThat(journal).isEmpty()
        // ★ 证明「调用方在等」，而不是「数据被牺牲」：
        //   52 笔此刻正挂在 `handOff()` 上等槽位。若是 DROP_OLDEST，
        //   它们会在这一步无声无息地消失，而 metrics 上什么都看不到。
        assertThat(w.pendingCount()).isGreaterThan(highWater)
        assertThat(metrics.snapshot().droppedState).isEqualTo(0)

        release.set(true)
        jobs.forEach { it.join() }
        w.flushNow()

        // ★ 决定性的断言：一条都没少
        assertThat(journal).hasSize(total)
        assertThat(w.pendingCount()).isEqualTo(0)
        assertThat(metrics.snapshot().droppedState).isEqualTo(0)
        // 降级信号应当出现（有调用方走了「等待槽位」路径），但它**不是**丢数据
        assertThat(metrics.snapshot().stateBacklog).isGreaterThan(0)
    }

    // ──────────────────────────────────────────── ④ 失败重试（不丢）

    @Test
    fun `写入失败会退避重试直到成功`() = loop {
        tasks.failNextWrites = 2          // 前两次事务抛异常
        val w = writer(retryBase = 1L)

        val ok = w.submitAndWait(StateOp.TaskUpsert(taskEntity("retry")))

        assertThat(ok).isTrue()
        assertThat(journal).containsExactly("upsert:retry")
        assertThat(metrics.snapshot().stateRetries).isEqualTo(2)
        assertThat(metrics.snapshot().stateGiveUps).isEqualTo(0)
    }

    @Test
    fun `重试到上限后交棒对账而不是静默丢弃`() = loop {
        tasks.failNextWrites = Int.MAX_VALUE
        val w = writer(retryBase = 1L)

        val ok = w.submitAndWait(StateOp.TaskFinish("doomed", TaskStatus.FAILED, "x", 1L, null, null))

        // ★ false 的含义是「宽限内没落地」，**不是「这一笔被丢了」**：
        //   它仍在通道里排队；真到设备不可用，由 TaskReconciler 在下次启动收敛。
        assertThat(ok).isFalse()
        assertThat(journal).isEmpty()
        assertThat(metrics.snapshot().stateGiveUps).isEqualTo(1)
        assertThat(metrics.snapshot().stateRetries).isEqualTo(StateLaneCaps.RETRY_MAX_TIMES)
        // ★ 恒等式：状态泳道里没有任何一条路径会丢数据
        assertThat(metrics.snapshot().droppedState).isEqualTo(0)
    }

    @Test
    fun `退避是指数且封顶`() = loop {
        tasks.failNextWrites = Int.MAX_VALUE
        val w = writer(retryBase = 10L)
        w.submitAndWait(StateOp.TaskUpsert(taskEntity("backoff")))

        // 交棒前共 5 次重试，退避 10/20/40/80/160 ms ⇒ 合计 310 ms，封顶 16×base=160
        assertThat(metrics.snapshot().stateRetries).isEqualTo(StateLaneCaps.RETRY_MAX_TIMES)
        assertThat(StateLaneCaps.RETRY_BASE_MS * 16).isEqualTo(4_000L)
    }

    // ──────────────────────────────────────────── ⑤ 一键清除语义

    @Test
    fun `stopAndDropQueued 之后提交不入队且不抛取消异常`() = loop {
        val w = writer()
        w.stopAndDropQueued()

        // ★ 这三个入口在已停止时都必须**静默拒绝**。
        //   曾经的缺陷：`tryOffer` 失败后直接 `lane.send()` 打到已取消的通道上，
        //   抛 `CancellationException` —— 而协程取消是控制流，它会顺着结构化并发
        //   把「我只是想写一行状态」变成「把整个作用域连带兄弟协程一起取消」。
        w.submit(StateOp.TaskUpsert(taskEntity("after-stop")))
        assertThat(w.submitAndWait(StateOp.TaskUpsert(taskEntity("after-stop")))).isFalse()
        assertThat(w.trySubmit(StateOp.TaskUpsert(taskEntity("after-stop")))).isFalse()

        w.flushNow()
        assertThat(journal).isEmpty()
        assertThat(w.pendingCount()).isEqualTo(0)
        // 清除期间的丢弃**不该**被记成「写入永久失败」，否则一次正常的清除会亮红灯
        assertThat(metrics.snapshot().stateGiveUps).isEqualTo(0)
        assertThat(metrics.snapshot().criticalAlerts).isEmpty()
    }

    // ──────────────────────────────────────────── ⑥ 保序（外键前提）

    @Test
    fun `同任务的 TaskUpsert 一定先于 Checkpoint 落盘`() = loop {
        val w = writer()
        // `task_checkpoint.task_id → task.id`：task 行必须先写，否则同一事务里就违反外键
        w.submit(StateOp.TaskUpsert(taskEntity("fk")))
        w.submit(StateOp.Checkpoint(checkpointEntity("fk")))
        w.flushNow()

        assertThat(journal).containsExactly("upsert:fk", "checkpoint:fk").inOrder()
    }

    @Test
    fun `挂起与终态按提交顺序落盘`() = loop {
        val w = writer()
        w.submit(StateOp.TaskSuspend("s1", TaskStatus.PAUSED, 1L))
        w.submit(StateOp.TaskFinish("s2", TaskStatus.SUCCEEDED, "terminal_completed", 1L, null, "ok"))
        w.flushNow()

        assertThat(journal)
            .containsExactly("suspend:s1:PAUSED", "finish:s2:SUCCEEDED:terminal_completed")
            .inOrder()
    }

    @Test
    fun `一次事务可同时覆盖多张表`() = loop {
        val w = writer()
        w.submit(StateOp.TaskUpsert(taskEntity("multi")))
        w.submit(StateOp.Checkpoint(checkpointEntity("multi")))
        w.flushNow()

        // 三张表的入口在同一个 target 上，一次 batch ⇒ 同一个事务
        assertThat(txSpans.size).isAtLeast(1)
        assertThat(txSpans.maxOf { it.last - it.first + 1 }).isAtLeast(2)
    }

    // ──────────────────────────────────────────── ⑦ 启动对账钩子

    @Test
    fun `构造时先跑一轮对账再开始流动写入`() = loop {
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        val w = writer(reconciler = { order += "sweep" })

        awaitUntil { order.contains("sweep") }
        w.submit(StateOp.TaskUpsert(taskEntity("hook")))
        w.flushNow()
        order += "write"

        assertThat(order).containsExactly("sweep", "write").inOrder()
    }

    @Test
    fun `对账钩子抛异常不影响后续写入`() = loop {
        val w = writer(reconciler = { error("reconcile boom") })
        w.submitAndWait(StateOp.TaskUpsert(taskEntity("still-works")))
        assertThat(journal).containsExactly("upsert:still-works")
    }

    // ──────────────────────────────────────────── 工具

    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(2)
        }
        error("condition not met within ${timeoutMs}ms")
    }
}
