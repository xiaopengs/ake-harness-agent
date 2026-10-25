package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.AgentCapabilityGrantEntity
import kotlinx.coroutines.flow.Flow

/**
 * Agent 级能力授权 DAO。
 *
 * ## ★★ 本表只存 `granted = 1` 的行
 *
 * 因此「授权」= `INSERT OR REPLACE`（[grant]），「撤销」= `DELETE`（[revoke]）。
 * 表里**不应该**出现 `granted = 0` 的行；[grantedRows] 只返回存在的行，
 * 调用方照旧用 `map[ref] == true`，契约无需改动（§3.2）。
 *
 * ★ 不建到 `tool_registry` 的外键（工具可能被 MCP server 移除 ⇒ 会有孤儿）。
 *   孤儿的处置约定见 §9-U7：`RetentionJob` 按 `tool_registry.missing_since < now-30d`
 *   清理对应 grant 行 —— **本轮未实现**（低频），约定先写在这里。
 */
@Dao
interface CapabilityGrantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun grant(row: AgentCapabilityGrantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun grantAll(rows: List<AgentCapabilityGrantEntity>)

    /**
     * ★ 幂等补齐（`TemplateBootstrap` 的存量回填用它，不改 `granted_at`）。
     *
     * `grantAll` 是 REPLACE ⇒ 重跑会把 `granted_at` 刷成现在，抹掉「这项能力是哪天
     * 交给这个人格的」这条唯一证据。回填是**每次冷启都会跑**的幂等操作，
     * 于是它必须用 IGNORE：已存在的行一动不动，缺的才插。
     *
     * @return 与 `rows` 一一对应的 rowid，被忽略的行为 `-1` ⇒ 调用方据此算「真补了几行」。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun grantAllIfAbsent(rows: List<AgentCapabilityGrantEntity>): List<Long>

    /** 撤销 = 删行。`@return` 被删行数（0 = 本来就没授权）。 */
    @Query(
        """
        DELETE FROM agent_capability_grant
         WHERE agent_id = :agentId AND kind = :kind AND ref_id = :refId
        """,
    )
    suspend fun revoke(agentId: String, kind: String, refId: String): Int

    @Query("DELETE FROM agent_capability_grant WHERE agent_id = :agentId")
    suspend fun revokeAll(agentId: String): Int

    /** ★ `grantsOf()` 的数据源：只对**存在**的行返回 true。 */
    @Query("SELECT * FROM agent_capability_grant WHERE agent_id = :agentId")
    suspend fun grantedRows(agentId: String): List<AgentCapabilityGrantEntity>

    @Query("SELECT * FROM agent_capability_grant WHERE agent_id = :agentId")
    fun observe(agentId: String): Flow<List<AgentCapabilityGrantEntity>>

    /**
     * ★ 数量上限（Q2 ≤ 6，硬顶 12）的库侧计数。
     *
     * 注意**已死的工具也占坑**（孤儿 grant 行）⇒ 这就是 §9-U7 那条清理约定的动机：
     * 「一个已经死了的工具占着活工具的坑」。
     */
    @Query("SELECT COUNT(*) FROM agent_capability_grant WHERE agent_id = :agentId")
    suspend fun countOf(agentId: String): Int

    /** §9-U7 的清理入口：删掉某个已消失工具在所有人格上的授权行。 */
    @Query("DELETE FROM agent_capability_grant WHERE kind = 'MCP_TOOL' AND ref_id = :toolId")
    suspend fun revokeToolEverywhere(toolId: String): Int
}
