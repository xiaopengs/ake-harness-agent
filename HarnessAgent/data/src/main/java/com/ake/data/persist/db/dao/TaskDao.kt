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

    /**
     * `TaskStorePort.recent` 的落点：**按最后活动倒序**，命中 idx_task_last_activity。
     *
     * 与 [finished] 只差一个 `finished_at IS NOT NULL` 谓词，而这个差别正是重点 ——
     * 「暂停中、等着用户点继续」的任务**必须**出现在历史列表里，否则用户以为它丢了。
     */
    @Query("SELECT * FROM task ORDER BY last_activity_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TaskEntity>

    /** 同上，但能随写入自动刷新（`Flow` 版，供冷启动后的常驻列表）。 */
    @Query("SELECT * FROM task ORDER BY last_activity_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TaskEntity>>

    /**
     * ★ 挂起（PAUSED / WAITING_HUMAN）**不写 `finished_at`、不写 `terminal_reason`**。
     *
     * 走 [finish] 会把 `finished_at` 置上，后果是「暂停中的任务」被 [finished] 与
     * `pickForDigest`（都以 `finished_at IS NOT NULL` 为入口）当成已结束的任务捞走 ——
     * 用户点「继续」时它已经被降级成 DIGEST 甚至被清理了。
     * 任务还活着，库里就不能说它结束了。
     */
    @Query(
        """
        UPDATE task
           SET status = :status,
               terminal_reason = NULL,
               finished_at = NULL,
               last_activity_at = :at
         WHERE id = :id
        """,
    )
    suspend fun markSuspended(id: String, status: String, at: Long)

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

    // ── 孤儿对账（`TaskReconciler`，2026-10 P1） ─────────────────────
    //
    // ★ **为什么判定必须写在 UPDATE 的 WHERE 里，而不是「先查出来再 update」**：
    // 「判定」和「写入」之间隔着一次主页 yielded 的空隙，而正常的 finish 可能正好在那时发生。
    // 下面两条 UPDATE 自带守卫，幂等，且不可能把已经到达的终态掀回中间态 ——
    // 把活着的任务误判成孤儿，是比漏扫严重得多的后果。

    /**
     * 孤儿候选：**活跃态 + 心跳过期**。命中 idx_task_last_activity。
     *
     * @param expectedStatus 只收 `{CREATED, RUNNING}`（由调用方从 `TaskStatus.ACTIVE` 取）。
     * @return 按心跳升序（最老的先收敛），每轮上限见 `TaskReconciler.BATCH`。
     */
    @Query(
        """
        SELECT id FROM task
         WHERE status = :expectedStatus
           AND last_activity_at < :before
         ORDER BY last_activity_at ASC
         LIMIT :limit
        """,
    )
    suspend fun staleActiveIds(expectedStatus: String, before: Long, limit: Int): List<String>

    /**
     * 有断点的孤儿 → **可恢复的暂停**（`finished_at` 保持 NULL，所以不会被
     * `pickForDigest` / `finished()` 当成已结束的捞走 —— 它还活着，用户还能点继续）。
     *
     * @return 受影响行数；0 = 它在扫描与处理之间已经正常收尾了。
     */
    @Query(
        """
        UPDATE task
           SET status = 'PAUSED',
               terminal_reason = NULL,
               finished_at = NULL,
               last_activity_at = :at
         WHERE id = :id
           AND status IN ('CREATED','RUNNING')
        """,
    )
    suspend fun convergeToPaused(id: String, at: Long): Int

    /**
     * 无断点的孤儿 → 终态 ABORTED，**必须写 `finished_at`**：
     * 这是它能被保留策略（`pickForDigest` 以 `finished_at IS NOT NULL` 为入口）接管的唯一凭证。
     * 只写 status 不写 finished_at，等于把一个「永久 RUNNING」换成一个「永久僵尸」。
     */
    @Query(
        """
        UPDATE task
           SET status = 'ABORTED',
               terminal_reason = :reason,
               finished_at = :at,
               last_activity_at = :at
         WHERE id = :id
           AND status IN ('CREATED','RUNNING')
        """,
    )
    suspend fun convergeAborted(id: String, at: Long, reason: String): Int

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

    // ── ★ v3：与 chat_message 的关系（2026-10 · 霍承钧）
    //
    // §8.2 B1 是最容易写错的一条：`task` 被 DIGEST / 删除时**不得**删除 `chat_message`；
    // 反过来，删 `chat_session` 时也**不得**删除 `task`。
    // 于是两个方向都只提供「解绑」：把 `chat_message_id` 置 NULL，消息行照旧活着
    // （会话拥有它，任务轨迹的生命周期由 v2 §4.4 自己管）。

    /** 任务被 DIGEST / 删除时解绑（B1 方向）。 */
    @Query("UPDATE task SET chat_message_id = NULL WHERE chat_message_id = :messageId")
    suspend fun unbindChatMessage(messageId: String): Int

    /** 删除会话时解绑该会话派生出的全部任务（B3 方向）。 */
    @Query(
        """
        UPDATE task SET chat_message_id = NULL
         WHERE chat_message_id IN (SELECT id FROM chat_message WHERE session_id = :sessionId)
        """,
    )
    suspend fun unbindChatSession(sessionId: String): Int

    /** 「这句话派生了哪个任务」的反查（命中 idx_task_chat_message）。 */
    @Query("SELECT * FROM task WHERE chat_message_id IS :messageId")
    suspend fun byChatMessage(messageId: String?): List<TaskEntity>
}
