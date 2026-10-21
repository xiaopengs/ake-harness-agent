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
