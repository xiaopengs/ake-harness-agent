package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.AppSettingsEntity
import com.ake.data.persist.db.entity.McpServerEntity
import kotlinx.coroutines.flow.Flow

/**
 * 配置类两张表的 DAO（`mcp_server` / `app_settings`）。
 *
 * ## 为什么两张表共用一个 DAO
 *
 * 它们同属「低频、行数极少、整体读写」这一档，与轨迹表（高频、量大、允许丢）
 * 是完全相反的两种访问形态。放进 `TaskDao` / `ToolRegistryDao` 都会把两种形态混在一起，
 * 而混在一起的后果是：后来者照着旁边的写法给配置行加上「批量 upsert + 队列」这类
 * 只属于热路径的复杂度。
 *
 * ## 写入纪律
 *
 * **配置类写不进两条泳道** —— 它们不是任务数据，也不是轨迹：
 *  - 量与步数无关（用户手录），`StateLaneCaps` 那条「有上界所以能不丢」的论证套不上；
 *  - 它们必须**立刻生效**：用户在设置页关掉一个 MCP server，下一次工具发现就得看不见它。
 *    走 200 ms 合并队列会让「关了开关但工具还在」这个状态存在一帧，那是安全问题不是体验问题。
 *
 * 所以这里**直接调 DAO**（同步语义由调用方的 suspend 承担），
 * 与 `ToolPermissionStoreImpl` 同一条口径。
 *
 * ## 用 REPLACE 而不是 `@Upsert`
 *
 * 与 `CheckpointDao` 同一条纪律：这两张表每次写入都是**完整行**（没有「只更新某几列」的场景），
 * REPLACE 语义上就是我们想要的，且不依赖 SQLite 3.24+ 的 UPSERT 语法（数据库 §9.4）。
 */
@Dao
interface ConfigDao {

    // ── MCP 服务器清单 ────────────────────────────────────────

    /** 全量清单。按 `updated_at` 升序，保证 UI 列表顺序在多次装载之间稳定。 */
    @Query("SELECT * FROM mcp_server ORDER BY updated_at ASC, id ASC")
    suspend fun mcpServers(): List<McpServerEntity>

    /** 同上，但随写入自动刷新（`McpViewModel` 用它替代手工 `refresh()`）。 */
    @Query("SELECT * FROM mcp_server ORDER BY updated_at ASC, id ASC")
    fun observeMcpServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_server WHERE id = :id")
    suspend fun mcpServer(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMcpServers(rows: List<McpServerEntity>)

    @Query("DELETE FROM mcp_server WHERE id = :id")
    suspend fun deleteMcpServer(id: String)

    /** §8.5 一键清除的落点之一。它必须存在：靠「删库文件」兜底意味着清除范围有两处判据。 */
    @Query("DELETE FROM mcp_server")
    suspend fun clearMcpServers()

    // ── 本地设置（单行表） ────────────────────────────────────

    /** 主键恒为 `AppSettingsEntity.SINGLE_ROW_ID`；未写过任何设置时返回 `null`。 */
    @Query("SELECT * FROM app_settings WHERE id = :rowId")
    suspend fun settings(rowId: Int): AppSettingsEntity?

    /**
     * `SettingsStorePort.values()` 的落点。
     *
     * ★ **返回可空 Flow 而不是默认值**：Room 的 Flow 在事务提交后重发，
     * 「库里还没有这一行」与「有一行但全是默认值」是**两件不同的事**，
     * 在 DAO 层抹平会让上层分不清「用户没设置过」与「用户设成了默认」。
     */
    @Query("SELECT * FROM app_settings WHERE id = :rowId")
    fun observeSettings(rowId: Int): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(row: AppSettingsEntity)

    @Query("DELETE FROM app_settings")
    suspend fun clearSettings()
}
