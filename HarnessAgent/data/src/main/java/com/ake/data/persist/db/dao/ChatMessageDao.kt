package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 消息表 DAO（`docs/06-chat-database.md` §3.7）。
 *
 * ## ★★ 三条最容易被写错的地方，写在最前面
 *
 *  1. **流式快照只能 UPDATE，绝不能 REPLACE**（§3.7 / §10.1 P6）。
 *     `@Insert(onConflict = REPLACE)` 的语义是「DELETE 旧行 + INSERT 新行」，
 *     它会**触发 `route_decision.chat_message_id` 的 ON DELETE SET NULL** ——
 *     于是每 400 ms 一次快照就把这条消息的路由审计引用清一次。
 *     这是一个只有在「消息既有路由审计又在流式生成」时才出现的、
 *     极难复现的静默数据损坏。单列 UPDATE 不触碰主键、不触发任何 FK 动作。
 *  2. **`seq` 的分配与 `chat_session.message_count` 的推进必须在同一事务里**
 *     （§3.7 `appendWithin` 的注释）。本 DAO **不**提供那个复合方法 ——
 *     事务组合落在 `store/ChatSessionStoreImpl`，因为 Room 的 DAO 之间
 *     不能互相注入；把它放进 Store 还能用内存 fake 单测（真机跑不了 Room）。
 *  3. **本 DAO 里没有任何以 `task_id` 为参数的 DELETE**（§8.2 B1）：
 *     删会话时顺手删任务的写法是从 `purgeTask` 抄形状抄出来的，
 *     而它的后果是「任务轨迹凭空消失」。这里从 DAO 层就把边界钉死。
 */
@Dao
interface ChatMessageDao {

    // ─────────────────────────────── 写入

    /** 幂等插入。`-1L` 表示被 `client_request_id` 的 UNIQUE 冲突忽略（重试复用已有行）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(msg: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(msg: ChatMessageEntity): Long

    /**
     * ★★ 流式快照：**只能 UPDATE，绝不能 REPLACE。**
     *
     * `WHERE status = 'STREAMING'` 这条守卫同时是「槽位该不该清」的信号：
     * 返回 0 行意味着该消息已经不处于 STREAMING（已被冷启 sweep 收敛或已提交），
     * `ChatMessageWriter` 据此丢掉那个槽而不是反复重试。
     */
    @Query(
        """
        UPDATE chat_message
           SET content_enc = :contentEnc,
               content_preview = :preview,
               updated_at = :now
         WHERE id = :messageId AND status = 'STREAMING'
        """,
    )
    suspend fun writeSnapshot(messageId: String, contentEnc: ByteArray, preview: String, now: Long): Int

    /**
     * ★ 终态提交：把 STREAMING 收敛，并一次性记账 token。
     *
     * `WHERE status = 'STREAMING'` 这一条守卫很重要：它让「重复提交」变成 0 行受影响，
     * 而不是把一条已经 ABORTED 的消息又写回 COMPLETE（§2.5 纪律：中断不是无效）。
     */
    @Query(
        """
        UPDATE chat_message
           SET status = :status,
               content_enc = :contentEnc,
               content_preview = :preview,
               payload_state = :payloadState,
               payload_file = :payloadFile,
               payload_codec = :payloadCodec,
               payload_bytes = :payloadBytes,
               prompt_tokens = :promptTokens,
               decode_tokens = :decodeTokens,
               kv_cache_tokens_after = :kvAfter,
               redaction_count = :redactionCount,
               updated_at = :now
         WHERE id = :messageId AND status = 'STREAMING'
        """,
    )
    suspend fun commit(
        messageId: String,
        status: String,
        contentEnc: ByteArray?,
        preview: String,
        payloadState: String,
        payloadFile: String?,
        payloadCodec: String?,
        payloadBytes: Int,
        promptTokens: Int,
        decodeTokens: Int,
        kvAfter: Int,
        redactionCount: Int,
        now: Long,
    ): Int

    /**
     * 非 STREAMING 行的收尾（用户消息 / SKILL_CALL / 注记类在 INSERT 时就是终态，
     * 只有「事后才知道失败」的场景用到它）。
     */
    @Query("UPDATE chat_message SET status = :status, updated_at = :now WHERE id = :messageId")
    suspend fun setStatus(messageId: String, status: String, now: Long): Int

    @Query("UPDATE chat_message SET task_id = :taskId WHERE id = :messageId")
    suspend fun bindTask(messageId: String, taskId: String?): Int

    // ─────────────────────────────── 读取

    /** Q-M1b：进入会话页（尾部 N 条）。命中 idx_chat_msg_session_seq，反向扫描。 */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE session_id = :sessionId
         ORDER BY seq DESC LIMIT :limit
        """,
    )
    fun observeTail(sessionId: String, limit: Int = 50): Flow<List<ChatMessageEntity>>

    /**
     * Q-M1：历史回滚（keyset 分页）。`beforeSeq` = 当前最老一条的 seq，首次传 `Int.MAX_VALUE`。
     *
     * ★ 为什么必须是 keyset 而不是 `OFFSET`（§4.7）：`OFFSET 5000` 要扫描并丢弃 5,000 行，
     * 而聊天会话是「越用越长」的。keyset 每次都是一次索引定位，代价与深度无关。
     * 代价是只能顺序翻页 —— 聊天历史没有跳页需求，这是白赚的。
     */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE session_id = :sessionId AND seq < :beforeSeq
         ORDER BY seq DESC LIMIT :limit
        """,
    )
    suspend fun pageBefore(sessionId: String, beforeSeq: Int, limit: Int = 50): List<ChatMessageEntity>

    /** 复水：最近 N 条原文（上游 §3.5 `keepVerbatim`）。同一索引。 */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE session_id = :sessionId
         ORDER BY seq DESC LIMIT :limit
        """,
    )
    suspend fun recentForRehydrate(sessionId: String, limit: Int = 256): List<ChatMessageEntity>

    /** ★ 按 seq 升序取（复水组装需要正序，`recentForRehydrate` 是倒序）。 */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE session_id = :sessionId AND seq >= :fromSeq
         ORDER BY seq ASC LIMIT :limit
        """,
    )
    suspend fun fromSeqAscending(sessionId: String, fromSeq: Int, limit: Int = 256): List<ChatMessageEntity>

    /**
     * 上游 §3.5 + §4.6③：压缩边界行的落点 ——
     * `DeterministicCompactor` 产出的压缩行就是一条 `kind='COMPACT_BOUNDARY'` 的消息，
     * `chat_session.compacted_upto_seq` 是指向它的**游标**，不是第二份存储。
     */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE session_id = :sessionId AND kind = 'COMPACT_BOUNDARY'
         ORDER BY seq DESC LIMIT 1
        """,
    )
    suspend fun latestBoundary(sessionId: String): ChatMessageEntity?

    /** 冷启动 sweep：把所有卡在 STREAMING 的收敛为 ABORTED（§2.5 纪律 2）。 */
    @Query("UPDATE chat_message SET status = 'ABORTED' WHERE status = 'STREAMING'")
    suspend fun sweepStreaming(): Int

    /**
     * Q-SW：冷启要知道「上次哪个会话没聊完」（§2.6）。
     * 全表扫，万级行 3–8 ms，冷启一次。★ 刻意不为它建 status 索引：
     * 稳态下 `status='STREAMING'` 是 0 行，为 0 行给最热的写表加一份常驻写放大不划算。
     */
    @Query("SELECT DISTINCT session_id FROM chat_message WHERE status = 'STREAMING'")
    suspend fun sessionsWithStreaming(): List<String>

    /** ★ 上游 Q7：一轮内的技能调用次数。库侧可断言 ≤ 3。 */
    @Query(
        """
        SELECT COUNT(*) FROM chat_message
         WHERE turn_id = :turnId AND kind = 'SKILL_CALL'
        """,
    )
    suspend fun skillCallsInTurn(turnId: String): Int

    @Query("SELECT * FROM chat_message WHERE id = :id")
    suspend fun byId(id: String): ChatMessageEntity?

    /** 幂等查询：同一 `clientRequestId` 是否已落过行。 */
    @Query("SELECT * FROM chat_message WHERE client_request_id IS :clientRequestId")
    suspend fun byClientRequestId(clientRequestId: String?): ChatMessageEntity?

    // ─────────────────────────────── 清理

    /** 删除会话时逐表删（v2 §2.3 纪律：不依赖 CASCADE）。事务外再删文件。 */
    @Query("DELETE FROM chat_message WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String): Int

    /** 删会话前先把外置文件清单取出来（事务外才能删文件，§8.2 ④）。 */
    @Query(
        """
        SELECT payload_file FROM chat_message
         WHERE session_id = :sessionId AND payload_state = 'EXTERNAL' AND payload_file IS NOT NULL
        """,
    )
    suspend fun externalPayloadsOf(sessionId: String): List<String>

    /** ★ 配额降级第一步（§8.3 L1）：只把外置文件的指针标成 DROPPED，行与 preview 保留。 */
    @Query(
        """
        UPDATE chat_message
           SET payload_state = 'DROPPED', payload_file = NULL, payload_bytes = 0
         WHERE session_id = :sessionId
           AND payload_state = 'EXTERNAL'
           AND seq < :beforeSeq
        """,
    )
    suspend fun dropPayloadRefsBefore(sessionId: String, beforeSeq: Int): Int

    /** ★ 孤儿文件 GC 的反向核对（v2 §4.5 纪律 3）：DB 引用了但文件没了。 */
    @Query(
        """
        UPDATE chat_message SET payload_state = 'MISSING'
         WHERE payload_state = 'EXTERNAL' AND payload_file IN (:missing)
        """,
    )
    suspend fun markPayloadMissing(missing: List<String>): Int

    /** §8.2 B1：任务被 DIGEST / 删除时只解绑，绝不删消息。 */
    @Query("UPDATE chat_message SET task_id = NULL WHERE task_id = :taskId")
    suspend fun unbindTask(taskId: String): Int

    @Query("SELECT COUNT(*) FROM chat_message WHERE session_id = :sessionId")
    suspend fun countOf(sessionId: String): Int

}
