package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * 任务表。
 *
 * ## `advance` 为什么用全列 UPDATE 而不是 `@Update`
 *
 * Room 的 `@Update` 会把 **null 字段也写回去**。而 `TaskBudgetPatch` 的语义是
 * 「`null` = 本次不动这一列」—— 用 `@Update` 会静默清零预算，这正好是我们最怕的那类 bug。
 * 所以这里写显式 `@Query`，把四个预算列**一次性按累计终值写入**。
 *
 * 调用方（`TaskStoreImpl`）保证传进来的都是累计终值，语义与 `LoopCheckpoint` 同口径。
 */
@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM task WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    /** Q2：某会话的任务列表。命中 idx_task_session_seq。 */
    @Query("SELECT * FROM task WHERE session_id = :sessionId ORDER BY seq_in_session ASC")
    fun ofSession(sessionId: String): Flow<List<TaskEntity>>

    /** Q5：按完成时间倒序的历史。命中 idx_task_finished。 */
    @Query("SELECT * FROM task WHERE finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT :limit")
    fun finished(limit: Int = 50): Flow<List<TaskEntity>>

    // ── 预算累计推进（★ 累计终值，不是增量）
    @Query(
        """
        UPDATE task
           SET steps_done = :stepsDone,
               prefill_tokens_total = :prefillTotal,
               decode_tokens_total = :decodeTotal,
               elapsed_ms_total = :elapsedTotal,
               last_activity_at = :lastActivityAt
         WHERE id = :id
        """,
    )
    suspend fun advance(
        id: String,
        stepsDone: Int,
        prefillTotal: Long,
        decodeTotal: Long,
        elapsedTotal: Long,
        lastActivityAt: Long,
    )

    @Query("UPDATE task SET kv_cache_peak = :peak WHERE id = :id AND kv_cache_peak < :peak")
    suspend fun raiseKvCachePeak(id: String, peak: Int)

    @Query("UPDATE task SET redaction_count = redaction_count + :delta WHERE id = :id")
    suspend fun addRedactionCount(id: String, delta: Int)

    @Query("UPDATE task SET started_at = :at, status = 'RUNNING', last_activity_at = :at WHERE id = :id")
    suspend fun markRunning(id: String, at: Long)

    @Query(
        """
        UPDATE task
           SET status = :status,
               terminal_reason = :reason,
               finished_at = :finishedAt,
               answer_enc = :answerEnc,
               answer_preview = :answerPreview,
               last_activity_at = :finishedAt
         WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        status: String,
        reason: String?,
        finishedAt: Long,
        answerEnc: ByteArray?,
        answerPreview: String?,
    )

    @Query("UPDATE task SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Int)

    // ── §4.5 清理作业 ────────────────────────────

    /** FULL → DIGEST 的候选：按最后活动排序，小批量（每批 20）。 */
    @Query(
        """
        SELECT id FROM task
         WHERE starred = 0
           AND retention_class = 'FULL'
           AND finished_at IS NOT NULL
           AND last_activity_at < :before
         ORDER BY last_activity_at ASC
         LIMIT :limit
        """,
    )
    suspend fun pickForDigest(before: Long, limit: Int): List<String>

    @Query(
        """
        SELECT id FROM task
         WHERE starred = 0
           AND retention_class = 'DIGEST'
           AND last_activity_at < :before
         ORDER BY last_activity_at ASC
         LIMIT :limit
        """,
    )
    suspend fun pickExpired(before: Long, limit: Int): List<String>

    @Query("UPDATE task SET retention_class = 'DIGEST' WHERE id IN (:ids)")
    suspend fun setRetentionClass(ids: List<String>)

    /** 硬配额突破时的最后一道：从最老开始删，收藏除外。 */
    @Query(
        """
        SELECT id FROM task
         WHERE starred = 0
         ORDER BY last_activity_at ASC
         LIMIT :limit
        """,
    )
    suspend fun pickOldest(limit: Int): List<String>

    /** 显式逐表删除，**不依赖 CASCADE**（数据库 §2.3）。由 RetentionJob 在一个事务里调用。 */
    @Query("DELETE FROM task WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM task")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM task WHERE retention_class = 'FULL'")
    suspend fun countFull(): Int
}
