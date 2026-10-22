package com.ake.data.persist.recovery

import com.ake.core.common.ManualClock
import com.ake.core.contract.TaskStatus
import com.ake.data.persist.fake.FakeCheckpointDao
import com.ake.data.persist.fake.FakeTaskDao
import com.ake.data.persist.write.PersistenceMetrics
import com.ake.data.persist.write.TaskLivenessRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `TaskReconciler` 的回归测试（2026-10）。
 *
 * ## 它要守住的是哪条性质
 *
 * **单靠一条更结实的写入队列做不到「永不永久 RUNNING」，只有收敛式对账可以。**
 * 写入侧（`TaskStateWriter`）保证「正常情况下不丢」；这里保证「异常情况下也不会卡死」。
 *
 * 所以下面每一条断言都指向一个具体的**失败形态**，而不是指向代码行数：
 *
 * | 用例 | 对应的真实故障 |
 * |---|---|
 * | `无断点收敛为 ABORTED 且必须写 finished_at` | 保留策略以 `finished_at IS NOT NULL` 为入口；不写就把「永久 RUNNING」换成「永久僵尸」 |
 * | `有断点收敛为 PAUSED 且 finished_at 保持 NULL` | 误杀一个可恢复任务，比暂时留着一个孤儿糟糕得多 |
 * | `活任务跳过` | 模型加载 10 s / 磁盘抖动会让心跳变旧 ⇒ 不许误杀 |
 * | `终态与暂停态不误伤` | `ACTIVE` 只有 `CREATED/RUNNING`；扫到别人就是状态机被掀回中间态 |
 * | `幂等` | 冷启动扫一轮 + WorkManager 周期复查，两轮之间不该互相干扰 |
 * | `BATCH 上限` | 冷启动对账不该吃掉用户可感知的几百毫秒 |
 * | `RACED` | 扫描与收敛之间隔着挂起点，正常的 finish 恰好那时发生 —— 那不是错误 |
 * | `DB 异常返回全零` | 冷启动不该被一段 DB 异常堵死 |
 *
 * ## 为什么这些测试**不**验证 SQL
 *
 * 两条 UPDATE 的 `AND status = ?` 守卫在内存 DAO 里被重新实现了一遍
 * （见 `fake/PersistenceFakes.kt` 的类注释），这里验证的是**调用方与守卫之间的契约**。
 * 「SQLite 真的执行了这条 UPDATE」属于 `androidTest` 的职责，本轮没有仪器化环境，
 * 已在回传里列为仍需真机验证的项。
 */
class TaskReconcilerTest {

    private val clock = ManualClock(1_000_000L)
    private val liveness = TaskLivenessRegistry()

    /**
     * `runBlocking` 的 Unit 包装。
     *
     * ★ 不能写成 `@Test fun x() = runBlocking { ... }`：那样方法的返回类型会被推断成
     *   块内最后一个表达式的类型（通常是 Truth 的 `Subject`），
     *   JUnit4 直接报 `Method x() should be void`（本轮真跑踩到）。
     */
    private fun loop(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = block)

    private fun fixture(
        tasks: FakeTaskDao = FakeTaskDao(),
        checkpoints: FakeCheckpointDao = FakeCheckpointDao(),
    ): TaskReconciler = TaskReconciler(
        taskDao = tasks,
        checkpointDao = checkpoints,
        liveness = liveness,
        clock = clock,
        metrics = PersistenceMetrics(),
    )

    // ──────────────────────────────────────────── ① 无断点 ⇒ ABORTED

    @Test
    fun `无断点的孤儿收敛为 ABORTED 且必须写 finished_at`() = loop {
        val tasks = FakeTaskDao().apply { put("t1", TaskStatus.RUNNING, lastActivityAt = clock.nowMillis() - 999_999L) }
        val r = fixture(tasks).sweep()

        assertThat(r.scanned).isEqualTo(1)
        assertThat(r.convergedAborted).isEqualTo(1)
        assertThat(r.convergedToPaused).isEqualTo(0)

        val row = tasks.rows["t1"]!!
        assertThat(row.status).isEqualTo(TaskStatus.ABORTED)
        assertThat(row.terminalReason).isEqualTo(TaskReconciler.REASON_ORPHANED_STALE_RUNNING)
        // ★ 这一行是整条用例的重点：不写 finished_at，`pickForDigest` 永远看不到它
        assertThat(row.finishedAt).isEqualTo(clock.nowMillis())
    }

    @Test
    fun `从没启动过的 CREATED 行带 NEVER_STARTED 原因`() = loop {
        val tasks = FakeTaskDao().apply { put("t0", TaskStatus.CREATED, lastActivityAt = 0L) }
        val r = fixture(tasks).sweep()

        assertThat(r.convergedAborted).isEqualTo(1)
        assertThat(tasks.rows["t0"]!!.terminalReason)
            .isEqualTo(TaskReconciler.REASON_ORPHANED_NEVER_STARTED)
    }

    // ──────────────────────────────────────────── ② 有断点 ⇒ PAUSED

    @Test
    fun `有断点的孤儿收敛为 PAUSED 且 finished_at 保持 NULL`() = loop {
        val tasks = FakeTaskDao().apply { put("t2", TaskStatus.RUNNING, lastActivityAt = 0L) }
        val checkpoints = FakeCheckpointDao().apply { put("t2") }
        val r = fixture(tasks, checkpoints).sweep()

        assertThat(r.convergedToPaused).isEqualTo(1)
        assertThat(r.convergedAborted).isEqualTo(0)

        val row = tasks.rows["t2"]!!
        assertThat(row.status).isEqualTo(TaskStatus.PAUSED)
        assertThat(row.terminalReason).isNull()
        // ★ 挂起 ≠ 结束。写了 finished_at 它就会被清理作业当死任务捞走
        assertThat(row.finishedAt).isNull()
    }

    @Test
    fun `断点查询失败时保守地判为无断点`() = loop {
        val tasks = FakeTaskDao().apply { put("t3", TaskStatus.RUNNING, lastActivityAt = 0L) }
        // 不应该抛：一次查询失败不该让整轮对账崩掉。
        // ★ 失败方向是「保守地当作没有断点 ⇒ ABORTED + finished_at」：
        //   行仍然会离开 RUNNING，只是失去「可恢复」这个好处 ——
        //   让它停在 RUNNING 等下一轮，比错杀它安全，但比判死它更符合「永不永久 RUNNING」。
        val broken = FakeCheckpointDao().also { it.countThrows = true }
        val r = fixture(tasks, broken).sweep()
        assertThat(r.convergedAborted).isEqualTo(1)
        assertThat(tasks.rows["t3"]!!.finishedAt).isEqualTo(clock.nowMillis())
    }

    // ──────────────────────────────────────────── ③ 活任务跳过

    @Test
    fun `本进程还在跑的任务即使心跳很旧也绝不收敛`() = loop {
        val tasks = FakeTaskDao().apply { put("live", TaskStatus.RUNNING, lastActivityAt = 0L) }
        liveness.markLive("live", 0L)

        val r = fixture(tasks).sweep()

        assertThat(r.scanned).isEqualTo(1)
        assertThat(r.skippedLive).isEqualTo(1)
        assertThat(r.total).isEqualTo(0)
        assertThat(tasks.rows["live"]!!.status).isEqualTo(TaskStatus.RUNNING)
        assertThat(tasks.convergeCalls).isEmpty()
    }

    // ──────────────────────────────────────────── ④ 终态 / 暂停态不误伤

    @Test
    fun `终态与暂停态不在扫描范围内`() = loop {
        val tasks = FakeTaskDao().apply {
            put("done", TaskStatus.SUCCEEDED, lastActivityAt = 0L)
            put("failed", TaskStatus.FAILED, lastActivityAt = 0L)
            put("paused", TaskStatus.PAUSED, lastActivityAt = 0L)
            put("waiting", TaskStatus.WAITING_HUMAN, lastActivityAt = 0L)
            put("aborted", TaskStatus.ABORTED, lastActivityAt = 0L)
        }

        val r = fixture(tasks).sweep()

        assertThat(r.scanned).isEqualTo(0)
        assertThat(r.total).isEqualTo(0)
        assertThat(tasks.rows.values.map { it.status })
            .containsExactly(
                TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.PAUSED,
                TaskStatus.WAITING_HUMAN, TaskStatus.ABORTED,
            )
    }

    @Test
    fun `心跳还没过期的活跃任务不进入候选`() = loop {
        val now = clock.nowMillis()
        val tasks = FakeTaskDao().apply {
            put("fresh", TaskStatus.RUNNING, lastActivityAt = now - TaskReconciler.STALE_RUNNING_MS + 1)
        }
        assertThat(fixture(tasks).sweep().scanned).isEqualTo(0)
    }

    // ──────────────────────────────────────────── ⑤ 幂等

    @Test
    fun `连扫两轮第二轮零收敛`() = loop {
        val tasks = FakeTaskDao().apply {
            put("a", TaskStatus.RUNNING, lastActivityAt = 0L)
            put("b", TaskStatus.CREATED, lastActivityAt = 0L)
        }
        val r = fixture(tasks)

        assertThat(r.sweep().total).isEqualTo(2)
        val second = r.sweep()
        assertThat(second.scanned).isEqualTo(0)
        assertThat(second.total).isEqualTo(0)
        assertThat(tasks.convergeCalls).hasSize(2)
    }

    // ──────────────────────────────────────────── ⑥ BATCH 上限

    @Test
    fun `一轮最多收敛 BATCH 条其余留到下一轮`() = loop {
        val tasks = FakeTaskDao().apply {
            repeat(TaskReconciler.BATCH + 5) { i -> put("t$i", TaskStatus.RUNNING, lastActivityAt = 0L) }
        }
        val r = fixture(tasks)

        val first = r.sweep()
        assertThat(first.scanned).isEqualTo(TaskReconciler.BATCH)
        assertThat(first.total).isEqualTo(TaskReconciler.BATCH)

        // 剩下的 5 条下一轮收；且第二轮同样守 BATCH 上界
        val second = r.sweep()
        assertThat(second.scanned).isEqualTo(5)
        assertThat(second.total).isEqualTo(5)

        assertThat(r.sweep().scanned).isEqualTo(0)
        assertThat(tasks.rows.values.count { it.status == TaskStatus.ABORTED })
            .isEqualTo(TaskReconciler.BATCH + 5)
    }

    @Test
    fun `CREATED 与 RUNNING 各自独立受 BATCH 限制`() = loop {
        val tasks = FakeTaskDao().apply {
            repeat(TaskReconciler.BATCH + 2) { i -> put("r$i", TaskStatus.RUNNING, lastActivityAt = 0L) }
            repeat(TaskReconciler.BATCH + 2) { i -> put("c$i", TaskStatus.CREATED, lastActivityAt = 0L) }
        }
        val report = fixture(tasks).sweep()

        // 两条 SELECT 各带自己的 LIMIT，所以一轮能收 2×BATCH
        assertThat(report.scanned).isEqualTo(TaskReconciler.BATCH * 2)
    }

    // ──────────────────────────────────────────── ⑦ 竞争（RACED）

    @Test
    fun `扫描之后自己正常收尾的任务不被判为孤儿`() = loop {
        val tasks = FakeTaskDao().apply { put("raced", TaskStatus.RUNNING, lastActivityAt = 0L) }
        // 模拟「扫描与收敛之间，主循环正常 finish 了」
        tasks.beforeConverge = { id -> tasks.rows[id]?.status = TaskStatus.SUCCEEDED }

        val r = fixture(tasks).sweep()

        assertThat(r.total).isEqualTo(0)          // 既不 paused 也不 aborted
        assertThat(tasks.rows["raced"]!!.status).isEqualTo(TaskStatus.SUCCEEDED)
        // ★ 关键：守卫让它一个字节都没改，而不是「改了但改对」
        assertThat(tasks.rows["raced"]!!.terminalReason).isNull()
    }

    // ──────────────────────────────────────────── ⑧ DB 异常

    @Test
    fun `扫描抛异常时返回全零报告而不是让冷启动崩掉`() = loop {
        val tasks = FakeTaskDao().apply { put("x", TaskStatus.RUNNING, lastActivityAt = 0L) }.also {
            it.scanThrows = true
        }
        val r = fixture(tasks).sweep()

        assertThat(r.scanned).isEqualTo(0)
        assertThat(r.total).isEqualTo(0)
        assertThat(tasks.rows["x"]!!.status).isEqualTo(TaskStatus.RUNNING)
    }

    // ──────────────────────────────────────────── ⑨ 常量契约

    @Test
    fun `对账常量与架构口径保持一致`() {
        // 10 分钟 ≈ 最坏心跳间隔（单步上界 10~12 s）的 50 倍
        assertThat(TaskReconciler.STALE_RUNNING_MS).isEqualTo(600_000L)
        // 与 RetentionJob.BATCH 同口径：小批量才塞得进一次 WAL checkpoint
        assertThat(TaskReconciler.BATCH).isEqualTo(20)
        // 合成终态原因带 ORPHANED_ 前缀 —— 它与 12 个主循环终态不共用命名空间
        assertThat(TaskReconciler.REASON_ORPHANED_STALE_RUNNING).startsWith("ORPHANED_")
        assertThat(TaskReconciler.REASON_ORPHANED_NEVER_STARTED).startsWith("ORPHANED_")
    }

    @Test
    fun `两个合成原因都不是主循环的十二终态之一`() {
        val loopReasons = com.ake.core.ports.TerminalReason.ALL.map { it.name }.toSet()
        assertThat(loopReasons).doesNotContain(TaskReconciler.REASON_ORPHANED_STALE_RUNNING)
        assertThat(loopReasons).doesNotContain(TaskReconciler.REASON_ORPHANED_NEVER_STARTED)
        assertThat(loopReasons).hasSize(12)
    }
}
