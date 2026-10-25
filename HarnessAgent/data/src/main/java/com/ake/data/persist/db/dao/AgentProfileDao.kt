package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.AgentProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * 人格 DAO（§3.8）。
 *
 * ★ 彻底删除的**完整枚举**不在本 DAO 里（Room 的 DAO 之间不能互相注入），
 * 在 `store/AgentProfileStoreImpl.deleteProfile()`：授权 → 会话 → 消息 → 记忆 → 人格。
 */
@Dao
interface AgentProfileDao {

    /** Q-C1：人格列表（未归档在前，最近使用在前）。 */
    @Query(
        """
        SELECT * FROM agent_profile
         WHERE archived = 0
         ORDER BY COALESCE(last_used_at, created_at) DESC
        """,
    )
    fun observeActive(): Flow<List<AgentProfileEntity>>

    @Query("SELECT * FROM agent_profile WHERE archived = 1 ORDER BY updated_at DESC")
    fun observeArchived(): Flow<List<AgentProfileEntity>>

    @Query("SELECT * FROM agent_profile WHERE id = :id")
    suspend fun byId(id: String): AgentProfileEntity?

    /**
     * 入站反查：MCP / AppFunctions 拿 `agent_id` 短名来找人（命中 idx_profile_agent_id）。
     *
     * ## ★ 为什么这里也接受主键 `id`（2026-10）
     *
     * 本表有两个 id 空间：`id`（ULID 主键）与 `agent_id`（对外短名 `ag-xxxxxxxxxx`）。
     * 所有**外键**（`chat_session.agent_id`、`agent_capability_grant.agent_id`）指向的是
     * **主键**（见 `ChatMigration2To3` 的 `REFERENCES agent_profile(id)`），
     * 而 MCP / AppFunctions / UI 路由手里拿的是**短名**。
     *
     * 于是「从会话摘要反查人格」这条路会同时遇到两种形态：
     *  - `ChatSessionSummary.agentId` ← `chat_session.agent_id` ⇒ **主键**；
     *  - 路由参数 / MCP 入站 ⇒ **短名**。
     *
     * 只认短名时，前者恒返回 null，表现是**一整轮对话在进模型之前就被判死**
     * （`ChatLoop.resolveScope` 返回 null ⇒ `Terminal(POLICY_DENIED)`，用户视角是"发送没反应"）。
     *
     * 所以在**读**这一侧放宽成「两个空间都认」。放宽是安全的：两者都是唯一键，
     * 不会产生歧义命中；写入侧仍然只有 Store 会写，且只写规范形态。
     */
    @Query("SELECT * FROM agent_profile WHERE agent_id = :agentId OR id = :agentId LIMIT 1")
    suspend fun byAgentId(agentId: String): AgentProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AgentProfileEntity)

    @Query(
        """
        UPDATE agent_profile
           SET last_used_at = :now, updated_at = :now
         WHERE id = :id
        """,
    )
    suspend fun touchUsed(id: String, now: Long)

    @Query("UPDATE agent_profile SET archived = 1, updated_at = :now WHERE id = :id")
    suspend fun archive(id: String, now: Long)

    /** ★ 彻底删除：只删人格自己一行（子表由 Store 显式枚举，不依赖 CASCADE）。 */
    @Query("DELETE FROM agent_profile WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT id FROM agent_profile WHERE id = :id")
    suspend fun exists(id: String): String?

    /**
     * ★ 从某个模板实例化出来的**全部**人格主键（含已归档）。
     *
     * 只有一个用途：`TemplateBootstrap` 的授权回填 —— 「模板声明了哪些语义必需能力」
     * 这件事在 v3 之前没有落到任何人身上，于是那一批人格的 `agent_capability_grant` 是空的
     * （= 哑 Agent）。回填按模板聚合，因此需要这一条反查。
     *
     * ★ 不按 `archived` 过滤：归档只是「不在列表里显示」，取消归档之后它仍然是那个人格，
     *   该有的授权不该等下一次冷启才补上。
     */
    @Query("SELECT id FROM agent_profile WHERE template_id = :templateId")
    suspend fun idsOfTemplate(templateId: String): List<String>

    @Query("SELECT COUNT(*) FROM agent_profile WHERE archived = 0")
    suspend fun countActive(): Int
}
