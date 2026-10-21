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
}
