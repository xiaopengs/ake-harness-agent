package com.ake.data.persist.recovery

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.contract.TaskStatus
import com.ake.data.persist.db.dao.CheckpointDao
import com.ake.data.persist.db.dao.TaskDao
import com.ake.data.persist.write.PersistenceMetrics
import com.ake.data.persist.write.TaskLivenessRegistry

/**
 * # 孤儿任务对账（清除「永久 RUNNING」的最后一道）
 *
 * ## 为什么它必须存在
 *
 * 即使状态写入通道已经做到「不丢」，下面三类情况仍然会让一行停在 RUNNING 且永不收敛：
 *
 *  1. **进程被杀**：LMK 杀进程 / 用户划掉任务卡片 / 系统升级重启 ⇒
 *     队列里的 `TaskFinish` 随内存一起消失，行却留在库里；
 *  2. **写入确实失败**：磁盘满、`commitWithRetry` 退避到上限（`metrics.stateGiveUp`）；
 *  3. **历史脏行**：本次缺陷在被修复**之前**已经产出了一批永久 RUNNING。
 *
 * 前两类是写入侧无论如何也补不了的。**写入侧保证的是「正常情况下不丢」，
 * 这里保证的是「异常情况下也不会卡死」。** 这是本轮最关键的架构结论：
 * 单靠一条更结实的写入队列做不到「永不永久 RUNNING」，只有**收敛式对账**可以。
 *
 * ## 判定：孤儿 ⟺ 本进程没人在跑它 **且** 心跳已过期
 *
 * ```
 *   status ∈ {CREATED, RUNNING}          ← 活跃态才可能被卡住（终态/暂停都是合法停留）
 * ∧ last_activity_at < now - STALE       ← 心跳停了（BudgetAdvance 每步刷新它）
 * ∧ ¬liveness.isLive(taskId)             ← 本进程确实没有它的循环在跑
 * ```
 *
 * ### 为什么要 conjunction，而不是只看心跳
 *
 * 一次合法的长时间停顿会让心跳变旧：模型加载约 10 s、磁盘抖动、`commitWithRetry`
 * 正在退避。**误杀一个活任务比暂时留着一个孤儿糟糕得多** ——
 * 前者用户眼睁睁看着任务被打断，后者只是历史列表里多一行、下轮 sweep 再收。
 *
 * ### 为什么阈值取 10 分钟
 *
 * `LoopBudget` 里单步预算上界 `perStepMs = 10~12 s`、单任务墙上时钟上界
 * `maxWallClockMs = 150~420 s`。取 10 分钟约为**最坏心跳间隔的 50 倍**：
 * 模型加载、一次页面超时、一次 GC 停顿都远不足以触发它。
 * 即便真的漏判一次，下一轮（15 分钟后）还会再来。
 *
 * ## 收敛方向：能恢复的别杀，不能恢复的必须带着 `finished_at` 走
 *
 * | 有断点 | 收敛为 | 理由 |
 * |---|---|---|
 * | 有 | `PAUSED`，`finished_at` 保持 NULL | 四个预算累计都在断点里，用户点「继续」还能接着跑。把可恢复的任务判死刑是冤案 |
 * | 无 | `ABORTED` + `terminal_reason=ORPHANED_*` + `finished_at=now` | 无断点即不可恢复；**必须写 `finished_at`**，否则保留策略（`pickForDigest` 以 `finished_at IS NOT NULL` 为入口）永远看不到它 —— 那样它虽然不再是 RUNNING，却仍是一条永不回收的僵尸行 |
 *
 * ## 与写入侧的边界（再强调一次）
 *
 * 这里**不补写正确数据、不猜测终态**（不会去猜它本该 SUCCEEDED 还是 FAILED）。
 * 职责只有一个：**把状态机从一个卡死的中间态推进到一个合法后继态**，
 * 剩下的交给保留策略。
 */
/**
 * @param taskDao / @param checkpointDao 直接收 DAO 而不是 `AkeDatabase`：
 *   对账只用到两张表的三个操作，把它和 Room 的门户对象解耦之后，
 *   这条（最容易出事的）逻辑可以在**纯 JVM 上用假 DAO 跑起来**，不必等到仪器化测试。
 */
public class TaskReconciler(
    private val taskDao: TaskDao,
    private val checkpointDao: CheckpointDao,
    private val liveness: TaskLivenessRegistry,
    private val clock: Clock,
    private val metrics: PersistenceMetrics,
    private val staleAfterMs: Long = STALE_RUNNING_MS,
) {

    /** 一轮扫描的结果（既是观测面，也是单测的断言面）。 */
    public data class SweepReport(
        val scanned: Int,
        val convergedToPaused: Int,
        val convergedAborted: Int,
        val skippedLive: Int,
    ) {
        public val total: Int get() = convergedToPaused + convergedAborted
    }

    /**
     * 扫一轮并收敛。
     *
     * ★ **幂等**：两条 UPDATE 都带 `AND status = ?` 守卫，重复扫、或者与正常 finish 撞在一起，
     * 都不会把已经到达的终态掀回中间态。
     *
     * @return 报告。DB 不可用时返回全零报告 —— **冷启动不该被一段 DB 异常堵死**。
     */
    public suspend fun sweep(): SweepReport {
        val now = clock.nowMillis()
        val before = now - staleAfterMs

        val running = scan(TaskStatus.RUNNING, before)
        val created = scan(TaskStatus.CREATED, before)
        if (running.isEmpty() && created.isEmpty()) return SweepReport(0, 0, 0, 0)

        var paused = 0
        var aborted = 0
        var skipped = 0
        var raced = 0

        for (id in running) {
            when (converge(id, now, REASON_ORPHANED_STALE_RUNNING)) {
                Outcome.PAUSED -> paused++
                Outcome.ABORTED -> aborted++
                Outcome.SKIPPED_LIVE -> skipped++
                Outcome.RACED -> raced++
            }
        }
        for (id in created) {
            when (converge(id, now, REASON_ORPHANED_NEVER_STARTED)) {
                Outcome.PAUSED -> paused++
                Outcome.ABORTED -> aborted++
                Outcome.SKIPPED_LIVE -> skipped++
                Outcome.RACED -> raced++
            }
        }

        if (raced > 0) metrics.recordSweepRace(raced)
        val report = SweepReport(
            scanned = running.size + created.size,
            convergedToPaused = paused,
            convergedAborted = aborted,
            skippedLive = skipped,
        )
        if (report.total > 0) {
            AkeLog.w(
                TAG,
                "reconciled ${report.total} orphan task(s): paused=$paused aborted=$aborted skipped(live)=$skipped",
            )
        }
        metrics.recordSweep(paused, aborted)
        return report
    }

    // ──────────────────────────────────────────── 内部

    private suspend fun scan(status: String, before: Long): List<String> =
        runCatching { taskDao.staleActiveIds(status, before, BATCH) }
            .onFailure { AkeLog.w(TAG, "stale scan failed (status=$status): ${it.message}") }
            .getOrDefault(emptyList())

    private suspend fun converge(id: String, now: Long, reason: String): Outcome {
        // ① 本进程还在跑 ⇒ 只是心跳旧了，不碰（见类注释：误杀比留孤儿更糟）
        if (liveness.isLive(id)) {
            metrics.recordSweepSkippedLive()
            return Outcome.SKIPPED_LIVE
        }
        // ② 有断点 ⇒ 收敛为可恢复的 PAUSED，要不要继续交给用户
        val hasCheckpoint = runCatching { checkpointDao.countOfTask(id) }.getOrDefault(0) > 0
        val rows = if (hasCheckpoint) {
            runCatching { taskDao.convergeToPaused(id, now) }.getOrDefault(0)
        } else {
            runCatching { taskDao.convergeAborted(id, now, reason) }.getOrDefault(0)
        }
        return when {
            rows == 0 -> {
                // UPDATE 打空 ⟹ 在扫描与处理之间它已经正常收尾了。这是好消息，不是错误。
                Outcome.RACED
            }

            hasCheckpoint -> Outcome.PAUSED
            else -> Outcome.ABORTED
        }
    }

    private enum class Outcome { PAUSED, ABORTED, SKIPPED_LIVE, RACED }

    public companion object {
        public const val TAG: String = "TaskReconciler"

        /**
         * 心跳过期阈值（推导见类注释：约为最坏心跳间隔的 50 倍）。
         * 它是可配置项而不是常量崇拜：很差的 ROM 上可以调大，不破坏正确性。
         */
        public const val STALE_RUNNING_MS: Long = 10L * 60L * 1000L

        /**
         * 每轮上限。
         *
         * 与 `RetentionJob.BATCH` 同口径：小批量才塞得进一次 WAL checkpoint，
         * 冷启动对账不该吃掉用户可感知的几百毫秒。
         */
        public const val BATCH: Int = 20

        // ── 合成终态原因。刻意**不加进** `:agent` 的 `TerminalReason` 枚举：
        //    那 12 个终态是主循环自己的终止机判产出（`:ui` 对它写了穷举 when），
        //    往里塞常量直接就把苏映棠那一侧编译炸了。这里写的是 `terminal_reason` 列的
        //    TEXT 值 —— 语义上「非主循环路径产生」，靠 `ORPHANED_` 前缀即可区分。
        //    TODO(ownership)：需要 UI 显示这两条文案时，由 `:ui` 加 i18n key。
        public const val REASON_ORPHANED_STALE_RUNNING: String = "ORPHANED_STALE_RUNNING"
        public const val REASON_ORPHANED_NEVER_STARTED: String = "ORPHANED_NEVER_STARTED"
    }
}
