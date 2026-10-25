package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话 DAO（§3.8）。**会话列表页的全部数据源，一条 join 都没有。**
 */
@Dao
interface ChatSessionDao {

    // ─────────────────────────────── 写入

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    // ─────────────────────────────── 读取

    /**
     * Q-S1 ★ 首页对话列表（最近消息 + 时间 + 未读）。命中 `idx_chat_sess_active_last`。
     *
     * 期望执行计划：`SEARCH chat_session USING INDEX idx_chat_sess_active_last (archived=?)`。
     * 索引首列等值命中、次列有序 ⇒ **不需要临时 B-tree 排序**（SQLite 可反向扫描 ASC
     * 索引来满足 `ORDER BY ... DESC`）。若计划里出现 `USE TEMP B-TREE FOR ORDER BY`
     * ⇒ 判为退化（v2 §7.5 既有纪律）。
     *
     * ★ 刻意**不建 DESC 索引**（§4.7）：反向扫描已经等价，且它会让
     * 「schema JSON ↔ 手工迁移 DDL」的比对多一处易错点。
     */
    @Query(
        """
        SELECT * FROM chat_session
         WHERE archived = 0
         ORDER BY last_message_at DESC
         LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int = 50): Flow<List<ChatSessionEntity>>

    @Query(
        """
        SELECT * FROM chat_session
         WHERE agent_id = :agentId AND archived = 0
         ORDER BY last_message_at DESC
        """,
    )
    fun observeByAgent(agentId: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_session WHERE id = :id")
    suspend fun byId(id: String): ChatSessionEntity?

    // ─────────────────────────────── 每轮更新

    /**
     * ★ 与消息 INSERT **同一事务**调用（`ChatSessionStoreImpl.appendWithin`）。
     *
     * 三个冗余列（`last_message_at` / `message_count` / `preview_redacted`）都是可从
     * `chat_message` 推导的量，冗余它们唯一的理由是首页列表不能 join 一张万级表
     * ⇒ 代价就是它们必须与消息写入在同一事务里更新（§4.5 纪律 3）。
     */
    @Query(
        """
        UPDATE chat_session
           SET last_message_at = :now,
               updated_at      = :now,
               message_count   = :nextSeq,
               preview_redacted = :preview,
               unread_count    = unread_count + :unreadDelta
         WHERE id = :sessionId
        """,
    )
    suspend fun touchForNewMessage(
        sessionId: String,
        now: Long,
        preview: String?,
        nextSeq: Int,
        unreadDelta: Int,
    )

    /**
     * ★ 助手消息建占位行时也要推进 `message_count`，但不算未读
     * （助手说话不会让「你没读」变多 —— 未读计的是**别人说的、你还没看的**，
     *   而助手回答时用户就在屏幕上看着它流式生成）。
     */
    @Query(
        """
        UPDATE chat_session
           SET last_message_at = :now,
               updated_at      = :now,
               message_count   = :nextSeq
         WHERE id = :sessionId
        """,
    )
    suspend fun touchForAssistant(sessionId: String, now: Long, nextSeq: Int)

    /** 草稿：由 UI 在 onStop / 文本变更节流后写入（§1.2 第 17 项 / §9-U2）。 */
    @Query("UPDATE chat_session SET draft_enc = :draftEnc, draft_updated_at = :now WHERE id = :sessionId")
    suspend fun saveDraft(sessionId: String, draftEnc: ByteArray?, now: Long): Int

    /** ★ 未读清零。`MAX` 防回退（多标签 / Flow 重发可能导致乱序）。 */
    @Query(
        """
        UPDATE chat_session
           SET unread_count = 0,
               last_read_seq = MAX(last_read_seq, :seq)
         WHERE id = :sessionId
        """,
    )
    suspend fun markRead(sessionId: String, seq: Int)

    /**
     * ★ 焦点释放时写复水基准（§4.6 / §9-U11）。**同步写一次 UPDATE**（< 0.5 ms），
     * 不走可丢泳道：这份快照是复水预算的**唯一基准**，丢了它 `ChatRehydrator`
     * 只能按 `compact_level` 猜，而猜错只在真正复水那一秒才暴露。
     */
    @Query(
        """
        UPDATE chat_session
           SET last_kv_cache_tokens = :kvTokens,
               last_rehydrate_tokens = :rehydrateTokens,
               last_mode = :mode,
               mode_epoch = :epoch,
               context_profile = :profile,
               updated_at = :now
         WHERE id = :sessionId
        """,
    )
    suspend fun recordFocusRelease(
        sessionId: String,
        kvTokens: Int,
        rehydrateTokens: Int,
        mode: String,
        epoch: Int,
        profile: String,
        now: Long,
    )

    @Query("UPDATE chat_session SET title = :title, updated_at = :now WHERE id = :sessionId")
    suspend fun rename(sessionId: String, title: String, now: Long)

    @Query(
        """
        UPDATE chat_session SET compacted_upto_seq = :uptoSeq, compact_level = :level, updated_at = :now
         WHERE id = :sessionId
        """,
    )
    suspend fun markCompacted(sessionId: String, uptoSeq: Int, level: Int, now: Long)

    // ─────────────────────────────── 删除

    @Query("UPDATE chat_session SET archived = 1, updated_at = :now WHERE id = :sessionId")
    suspend fun archive(sessionId: String, now: Long)

    /** ★ 只删会话自己一行。子表的删除由 `ChatSessionStoreImpl.deleteSession()` 显式枚举（§8.2）。 */
    @Query("DELETE FROM chat_session WHERE id = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM chat_session WHERE agent_id = :agentId")
    suspend fun deleteByAgent(agentId: String): Int

    /** ★ 删人格时逐表枚举用（不依赖 CASCADE）：先取会话 id，再删消息。 */
    @Query("SELECT id FROM chat_session WHERE agent_id = :agentId")
    suspend fun idsOfAgent(agentId: String): List<String>

    /** §8 清理：只挑最老的会话做 payload 降级，按 last_message_at 排序。 */
    @Query(
        """
        SELECT id FROM chat_session
         ORDER BY last_message_at ASC
         LIMIT :limit
        """,
    )
    suspend fun oldest(limit: Int): List<String>
}
