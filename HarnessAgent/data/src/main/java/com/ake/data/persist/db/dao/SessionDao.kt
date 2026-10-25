package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    /** Q1：会话列表。命中 idx_agent_session_updated_at。 */
    @Query("SELECT * FROM agent_session ORDER BY updated_at DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<SessionEntity>>

    @Query("SELECT * FROM agent_session WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM agent_session WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query(
        """
        UPDATE agent_session
           SET updated_at = :updatedAt,
               last_task_id = :lastTaskId,
               last_url = :lastUrl,
               task_count = task_count + 1
         WHERE id = :id
        """,
    )
    suspend fun touch(id: String, updatedAt: Long, lastTaskId: String?, lastUrl: String?)

    @Query("UPDATE agent_session SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, updatedAt: Long)

    /** §4.5 清理作业：显式逐表删除，不依赖 CASCADE（数据库 §2.3）。 */
    @Query("DELETE FROM agent_session WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM agent_session WHERE status = 'ARCHIVED' AND updated_at < :before")
    suspend fun deleteArchivedBefore(before: Long): Int

    // ── ★ v3：与 chat_session 的关系（2026-10 · 霍承钧）
    //
    // `DELETE` 一个 chat_session 时**不得**连带删除 `agent_session`
    // （主理人 U3 裁决：SET NULL —— 删对话不销毁任务轨迹，§8.2 B3）。
    // ★ 因此这里只提供「解绑」，**刻意不提供** `deleteByChatSession`：
    // 「解绑」与「删除」分成两个名字，是为了让调用方不可能顺手把执行容器也删了。

    /** ★ 解绑而不是删除：会话没了，任务容器与它的轨迹继续走 v2 §4.4 自己的时效。 */
    @Query("UPDATE agent_session SET chat_session_id = NULL WHERE chat_session_id = :sessionId")
    suspend fun unbindChatSession(sessionId: String): Int

    @Query("SELECT * FROM agent_session WHERE chat_session_id IS :sessionId")
    suspend fun byChatSession(sessionId: String?): List<SessionEntity>
}
