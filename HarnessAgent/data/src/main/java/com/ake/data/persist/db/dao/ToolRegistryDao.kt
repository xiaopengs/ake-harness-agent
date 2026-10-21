package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.ToolRegistryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 工具注册表缓存 + **逐工具授权**（§8.5 / 数据库 F7）。
 *
 * 落 Room 而不是 DataStore 的理由（F7 原文）：「所有 MCP 工具的 Always Allow 授权
 * 必须能在设置里按工具粒度撤销」。Proto DataStore 是整体重写文件，
 * 200 个工具里撤销 1 个要重写整个文件，低端机上有可感知延迟；
 * 单列 UPDATE 是毫秒级，且天然可审计。
 */
@Dao
interface ToolRegistryDao {

    /** discover 是完整行 ⇒ REPLACE 语义正好（也避开了 §9.4 对 `@Upsert` 的 SQLite 版本约束）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ToolRegistryEntity>)

    @Query("SELECT * FROM tool_registry WHERE tool_id = :toolId")
    suspend fun byId(toolId: String): ToolRegistryEntity?

    @Query("SELECT * FROM tool_registry ORDER BY origin ASC, name ASC")
    fun catalog(): Flow<List<ToolRegistryEntity>>

    @Query("SELECT * FROM tool_registry WHERE enabled = 1 ORDER BY origin ASC, name ASC")
    fun enabledCatalog(): Flow<List<ToolRegistryEntity>>

    @Query("SELECT * FROM tool_registry WHERE origin = :origin AND server_id = :serverId")
    suspend fun ofServer(origin: String, serverId: String): List<ToolRegistryEntity>

    // ── 逐工具授权（单列 UPDATE，毫秒级） ──────────

    @Query("UPDATE tool_registry SET enabled = :enabled WHERE tool_id = :toolId")
    suspend fun setEnabled(toolId: String, enabled: Int)

    @Query(
        """
        UPDATE tool_registry
           SET always_allow = :allow,
               granted_at = CASE WHEN :allow = 1 THEN :at ELSE NULL END
         WHERE tool_id = :toolId
        """,
    )
    suspend fun setAlwaysAllow(toolId: String, allow: Int, at: Long)

    @Query("SELECT enabled FROM tool_registry WHERE tool_id = :toolId")
    suspend fun enabledOf(toolId: String): Int?

    @Query("SELECT always_allow FROM tool_registry WHERE tool_id = :toolId")
    suspend fun alwaysAllowOf(toolId: String): Int?

    @Query("SELECT COUNT(*) FROM tool_registry WHERE enabled = 1")
    suspend fun enabledCount(): Int

    @Query("UPDATE tool_registry SET risk_class_override = :override WHERE tool_id = :toolId")
    suspend fun setRiskOverride(toolId: String, override: String?)

    /** discover 后消失的工具：标 missing_since，UI 提示「该工具已不可达」。 */
    @Query("UPDATE tool_registry SET missing_since = :at WHERE tool_id IN (:ids)")
    suspend fun markMissing(ids: List<String>, at: Long)

    @Query("UPDATE tool_registry SET missing_since = NULL WHERE tool_id IN (:ids)")
    suspend fun clearMissing(ids: List<String>)

    @Query("DELETE FROM tool_registry WHERE last_seen_at < :before AND always_allow = 0")
    suspend fun deleteStale(before: Long): Int

    @Query("DELETE FROM tool_registry")
    suspend fun deleteAll()
}
