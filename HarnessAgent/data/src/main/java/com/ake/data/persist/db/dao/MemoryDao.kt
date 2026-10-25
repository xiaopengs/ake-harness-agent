package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.MemoryCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: MemoryCardEntity)

    @Query("SELECT * FROM memory_card WHERE id = :id")
    suspend fun byId(id: String): MemoryCardEntity?

    @Query("SELECT * FROM memory_card WHERE `key` = :key")
    suspend fun byKey(key: String): MemoryCardEntity?

    /**
     * ★ v3 人格隔离后的按键定位（命中 `idx_mem_agent_key` 或 `idx_mem_host_used`）。
     *
     * `agent_id IS :agentId` 而不是 `= :agentId`：这样同一个方法既能查「某人格的 key」
     * 也能查「全局记忆」（传 null 时绑定为 `IS NULL`），两种查法共用一条语句。
     *
     * ★ 它同时是 §6.4 那个**已知缺口**的补法：`UNIQUE(agent_id, key)` 对
     *   `agent_id IS NULL` 的全局记忆不生效，所以全局记忆的唯一性要由 Store 层
     *   在写入前显式查一次 —— 用的就是本方法。
     */
    @Query("SELECT * FROM memory_card WHERE agent_id IS :agentId AND `key` = :key")
    suspend fun byAgentKey(agentId: String?, key: String): MemoryCardEntity?

    /**
     * ★ 删人格时连同它的记忆一起删（§8.5 选项 A：**溯源可以断，归属不能悬空**）。
     *
     * ⚠ 这是一个**用户可见**的破坏性动作：`AgentProfileStoreImpl.deleteProfile()`
     * 调用它之前，UI 的二次确认必须明示「会同时删除它记住的东西」。
     */
    @Query("DELETE FROM memory_card WHERE agent_id IS :agentId")
    suspend fun deleteByAgent(agentId: String): Int

    /** 当前域名相关记忆 + 全局记忆（命中 idx_mem_host_used）。 */
    @Query(
        """
        SELECT * FROM memory_card
         WHERE scope_host IS NULL OR scope_host = :host
         ORDER BY pinned DESC, last_used_at DESC
         LIMIT :limit
        """,
    )
    suspend fun relevant(host: String, limit: Int = 8): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_card ORDER BY updated_at DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<MemoryCardEntity>>

    @Query("UPDATE memory_card SET last_used_at = :at, use_count = use_count + 1 WHERE id = :id")
    suspend fun markUsed(id: String, at: Long)

    @Query("SELECT COUNT(*) FROM memory_card")
    suspend fun count(): Int

    /** 配额硬限（§4.4：1000 软 / 2000 硬）：从最不常用开始删，pinned 除外。 */
    @Query(
        """
        SELECT id FROM memory_card
         WHERE pinned = 0
         ORDER BY last_used_at ASC
         LIMIT :limit
        """,
    )
    suspend fun pickEvictable(limit: Int): List<String>

    @Query("DELETE FROM memory_card WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM memory_card WHERE expires_at IS NOT NULL AND expires_at < :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM memory_card")
    suspend fun deleteAll()
}
