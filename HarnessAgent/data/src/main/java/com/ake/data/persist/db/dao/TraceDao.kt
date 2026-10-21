package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.TraceEventEntity

/**
 * 轨迹四表。
 *
 * ★ **全部写方法由 `TraceBufferWriter` 批量调用，不允许单个写点自己开事务。**
 * 这条纪律是写入性能的全部来源（数据库 §6.4：一个事务、多张表、一次 COMMIT）。
 *
 * 全部插入走 `@Insert`（无 onConflict 声明 ⇒ ABORT）。**刻意不用 REPLACE**：
 * 轨迹是追加写的，重复主键意味着上游逻辑错了；让它抛出来、由 writer 记一条 IO_ERROR，
 * 好过静默覆盖掉一条已经写进去的证据。
 */
@Dao
interface TraceDao {

    // ── 批量写 ────────────────────────────────────
    @Insert
    suspend fun insertSteps(items: List<StepEntity>)

    @Insert
    suspend fun insertToolInvocations(items: List<ToolInvocationEntity>)

    @Insert
    suspend fun insertObservations(items: List<ObservationEntity>)

    @Insert
    suspend fun insertEvents(items: List<TraceEventEntity>)

    // ── Q3：某任务全部步骤（命中 idx_step_task_seq） ──
    @Query("SELECT * FROM step WHERE task_id = :taskId ORDER BY seq ASC")
    suspend fun stepsOf(taskId: String): List<StepEntity>

    @Query("SELECT * FROM step WHERE task_id = :taskId ORDER BY seq ASC LIMIT :limit OFFSET :offset")
    suspend fun stepsPage(taskId: String, limit: Int, offset: Int): List<StepEntity>

    @Query("SELECT * FROM step WHERE id = :stepId")
    suspend fun step(stepId: String): StepEntity?

    /** §3.5 定位伪失败的回顾：该任务上出现过哪些 NOT_FOUND 的 action_key。 */
    @Query(
        """
        SELECT * FROM step
         WHERE task_id = :taskId AND error_code IN ('NOT_FOUND','STALE')
         ORDER BY seq DESC LIMIT :limit
        """,
    )
    suspend fun locateFailures(taskId: String, limit: Int = 8): List<StepEntity>

    // ── Q4：某步的工具调用 ────────────────────────
    @Query("SELECT * FROM tool_invocation WHERE step_id = :stepId ORDER BY seq_in_step ASC")
    suspend fun toolsOfStep(stepId: String): List<ToolInvocationEntity>

    @Query("SELECT * FROM tool_invocation WHERE task_id = :taskId ORDER BY created_at ASC")
    suspend fun toolsOfTask(taskId: String): List<ToolInvocationEntity>

    // ── 观察 ──────────────────────────────────────
    @Query("SELECT * FROM observation WHERE id = :id")
    suspend fun observation(id: String): ObservationEntity?

    @Query("SELECT * FROM observation WHERE task_id = :taskId ORDER BY seq ASC")
    suspend fun observationsOf(taskId: String): List<ObservationEntity>

    /** Q7：域名访问历史（命中 idx_obs_host_created）。 */
    @Query("SELECT * FROM observation WHERE host = :host ORDER BY created_at DESC LIMIT :limit")
    suspend fun byHost(host: String, limit: Int = 50): List<ObservationEntity>

    // ── 事件 ──────────────────────────────────────
    @Query("SELECT * FROM trace_event WHERE task_id = :taskId ORDER BY seq ASC")
    suspend fun eventsOf(taskId: String): List<TraceEventEntity>

    @Query(
        """
        SELECT * FROM trace_event
         WHERE task_id = :taskId AND level IN ('WARN','ERROR')
         ORDER BY seq ASC LIMIT :limit
        """,
    )
    suspend fun problemsOf(taskId: String, limit: Int = 200): List<TraceEventEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM trace_event WHERE task_id = :taskId")
    suspend fun maxEventSeq(taskId: String): Long

    // ── §4.4 保留降级：FULL → DIGEST ───────────────

    /** 先清掉工具入/出参密文（数据库 §4.4 DIGEST 级定义）。 */
    @Query(
        """
        UPDATE tool_invocation
           SET input_args_enc = NULL, output_text_enc = NULL, input_schema_hint = NULL
         WHERE task_id = :taskId
        """,
    )
    suspend fun dropInvocationPayloads(taskId: String)

    /** 只留 WARN/ERROR 事件。 */
    @Query("DELETE FROM trace_event WHERE task_id = :taskId AND level IN ('DEBUG','INFO')")
    suspend fun dropVerboseEvents(taskId: String)

    /** DB 侧先把快照指针置 DROPPED，**之后**才由 RetentionJob 删文件（§4.5 纪律 1）。 */
    @Query(
        """
        UPDATE observation
           SET payload_state = 'DROPPED', payload_file = NULL,
               payload_codec = NULL, payload_stored_bytes = 0
         WHERE task_id = :taskId
        """,
    )
    suspend fun dropPayloadRefs(taskId: String)

    // ── §4.5 清理：显式逐表删除（不依赖 CASCADE） ──
    @Transaction
    suspend fun purgeTask(taskId: String) {
        deleteEvents(taskId)
        deleteObservations(taskId)
        deleteToolInvocations(taskId)
        deleteSteps(taskId)
    }

    @Query("DELETE FROM trace_event WHERE task_id = :taskId")
    suspend fun deleteEvents(taskId: String)

    @Query("DELETE FROM observation WHERE task_id = :taskId")
    suspend fun deleteObservations(taskId: String)

    @Query("DELETE FROM tool_invocation WHERE task_id = :taskId")
    suspend fun deleteToolInvocations(taskId: String)

    @Query("DELETE FROM step WHERE task_id = :taskId")
    suspend fun deleteSteps(taskId: String)

    /** §2.3：孤儿行兜底（外键在 SQLite 默认不生效）。 */
    @Query("DELETE FROM trace_event WHERE task_id NOT IN (SELECT id FROM task)")
    suspend fun deleteOrphanEvents(): Int

    @Query("DELETE FROM tool_invocation WHERE task_id NOT IN (SELECT id FROM task)")
    suspend fun deleteOrphanInvocations(): Int
}
