package com.ake.data.persist.maintenance

import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.ports.MemoryTier
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.payload.PayloadStore
import com.ake.data.persist.write.PersistenceGovernor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 保留策略与清理作业（数据库 §4.4 / §4.5）。
 *
 * 三级：**FULL → DIGEST → 删除，没有反向。**
 *
 * ## 五条必须遵守的实现纪律（逐条都是踩过的坑）
 *
 *  1. **「先改 DB 指针，再删文件」，永远不要反过来。** 反过来的后果是「行指向不存在的文件」，
 *     会让回放 UI 崩；而「留几个孤儿文件」最多是浪费空间，且 ③ 会收拾它。
 *  2. **文件删除放在事务之外。** 一次 `rm` 几百个小文件可能是上百毫秒同步 IO；
 *     包进 SQLite 事务等于长时间持写锁，会拖住正在执行任务的 UI 查询。
 *  3. **孤儿文件 GC 必须双向核对。** DB 引用了但文件没了 → 行改 `MISSING`；
 *     文件在但 DB 无引用 → 删文件。这是唯一能和「进程被杀 / 磁盘满」和平共处的方案。
 *  4. **日常不跑 `VACUUM`。** SQLite 的删除不归还空间给文件系统，而是进 freelist 被后续插入复用 ——
 *     这正是我们想要的（写多删多场景下 freelist 复用是最优路径）。
 *  5. **清理作业不得依赖 CASCADE。** 显式逐表 `DELETE`（见 `TraceDao.purgeTask`）。
 *
 * ## 时间预算
 *
 * 每批 20 个任务、总预算 2 秒。超预算就 `Result.retry()` 让下一轮继续 ——
 * 一次清理不该把前台任务的 IO 吃掉。
 */
class RetentionJob(
    private val db: AkeDatabase,
    private val payloads: PayloadStore,
    private val governor: PersistenceGovernor,
    private val clock: Clock,
    private val tier: () -> MemoryTier,
) {

    /** 供 WorkManager 包装。约束：idle + 非低存储（由 enqueue 方设定）。 */
    class Worker(
        appContext: android.content.Context,
        params: WorkerParameters,
        private val job: RetentionJob,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = job.runOnce()
    }

    private fun thresholds(): Thresholds = when (tier()) {
        MemoryTier.LOW -> Thresholds(fullTasks = 10, fullDays = 3, digestDays = 14)
        MemoryTier.MID -> Thresholds(fullTasks = 50, fullDays = 14, digestDays = 30)
        MemoryTier.HIGH -> Thresholds(fullTasks = 100, fullDays = 30, digestDays = 60)
    }

    suspend fun runOnce(): androidx.work.ListenableWorker.Result {
        if (!governor.isMaintenanceAllowed()) {
            AkeLog.i(TAG, "maintenance gate closed (model loading), skip")
            return androidx.work.ListenableWorker.Result.success()
        }
        val t = thresholds()
        val now = clock.nowMillis()
        val deadline = now + TIME_BUDGET_MS
        var dirty = false

        // ① FULL → DIGEST
        val toDigest = runCatching { db.taskDao().pickForDigest(now - days(t.fullDays), BATCH) }
            .getOrDefault(emptyList())
        // 任务数超上限的部分也一起降级（保留最新的 N 个 FULL 之外的）
        val overflow = runCatching {
            val full = db.taskDao().countFull()
            if (full > t.fullTasks) db.taskDao().pickOldest(full - t.fullTasks) else emptyList()
        }.getOrDefault(emptyList())

        for (id in (toDigest + overflow).distinct()) {
            db.withTransaction {
                db.traceDao().dropPayloadRefs(id)      // DB 侧先置 DROPPED
                db.traceDao().dropInvocationPayloads(id)
                db.traceDao().dropVerboseEvents(id)
                db.taskDao().setRetentionClass(listOf(id))
            }
            // ★ 再删文件（事务外）
            withContext(Dispatchers.IO) { payloads.purgeTask(id) }
            dirty = true
            if (clock.nowMillis() > deadline) return retry()
        }

        // ② DIGEST → 删除
        val toDelete = runCatching { db.taskDao().pickExpired(now - days(t.digestDays), BATCH) }
            .getOrDefault(emptyList())
        for (id in toDelete) {
            db.withTransaction {
                db.traceDao().purgeTask(id)
                db.policyDao().deleteOfTask(id)
                db.checkpointDao().delete(id)
                db.taskDao().delete(id)
            }
            withContext(Dispatchers.IO) { payloads.purgeTask(id) }
            dirty = true
            if (clock.nowMillis() > deadline) return retry()
        }

        // ③ 孤儿行兜底（外键在 SQLite 默认不生效）
        runCatching { db.traceDao().deleteOrphanEvents() }
        runCatching { db.traceDao().deleteOrphanInvocations() }
        runCatching { db.policyDao().deleteOrphans() }

        // ④ 硬配额突破时额外一轮
        if (overHardQuota()) {
            val victims = runCatching { db.taskDao().pickOldest(BATCH) }.getOrDefault(emptyList())
            for (id in victims) {
                db.withTransaction {
                    db.traceDao().purgeTask(id)
                    db.policyDao().deleteOfTask(id)
                    db.checkpointDao().delete(id)
                    db.taskDao().delete(id)
                }
                withContext(Dispatchers.IO) { payloads.purgeTask(id) }
                dirty = true
            }
        }

        // ⑤ 空闲维护：checkpoint + optimize。**刻意放在这里，绝不在任务执行期间做**
        if (dirty) idleMaintenance()

        return if (clock.nowMillis() > deadline) retry() else androidx.work.ListenableWorker.Result.success()
    }

    private suspend fun overHardQuota(): Boolean {
        val dbBytes = dbFileBytes()
        val traceBytes = runCatching { payloads.bytesUsed() }.getOrDefault(0L)
        return dbBytes > AkeQuotas.DB_HARD || traceBytes > AkeQuotas.TRACE_HARD
    }

    /**
     * `PRAGMA wal_checkpoint(PASSIVE)` 与 `PRAGMA optimize`。
     *
     * checkpoint 是**唯一发 fsync 的地方**，会造成单次数百毫秒抖动 ——
     * 所以只在空闲维护期做，任务进行中绝不触发。
     */
    private suspend fun idleMaintenance() = withContext(Dispatchers.IO) {
        runCatching {
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(PASSIVE)")
            db.openHelper.writableDatabase.execSQL("PRAGMA optimize")
        }.onFailure { AkeLog.w(TAG, "idle maintenance failed: ${it.message}") }
    }

    private fun dbFileBytes(): Long {
        val path = db.openHelper.writableDatabase.path ?: return 0L
        val f = java.io.File(path)
        val wal = java.io.File("$path-wal")
        val shm = java.io.File("$path-shm")
        return f.length() + wal.length() + shm.length()
    }

    private fun days(n: Int): Long = n.toLong() * 24 * 60 * 60 * 1000

    private fun retry(): androidx.work.ListenableWorker.Result = androidx.work.ListenableWorker.Result.retry()

    private data class Thresholds(val fullTasks: Int, val fullDays: Int, val digestDays: Int)

    companion object {
        const val TAG: String = "RetentionJob"

        /** 每批任务数：小批量才能控制在一次 WAL checkpoint 内完成。 */
        const val BATCH: Int = 20

        const val TIME_BUDGET_MS: Long = 2_000L
    }
}

/** 配额（数据库 §4.4）。异常场景的最后一道，不是常态约束。 */
object AkeQuotas {
    const val DB_SOFT: Long = 192L * 1024 * 1024
    const val DB_HARD: Long = 256L * 1024 * 1024
    const val TRACE_SOFT: Long = 192L * 1024 * 1024
    const val TRACE_HARD: Long = 384L * 1024 * 1024
    const val MEMORY_CARDS_SOFT: Int = 1_000
    const val MEMORY_CARDS_HARD: Int = 2_000
}
