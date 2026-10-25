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
     * 而不是把一条已经 `ABORTED` / `FAILED`（冷启收敛过）的消息又写回 COMPLETE
     * （§2.5 纪律：中断不是无效 —— 终态一旦落定，只能被看见，不能被抹平）。
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

    /**
     * 冷启动 sweep：把所有卡在 `STREAMING` 的收敛为 **`FAILED`**（§2.5 纪律 2）。
     *
     * ## ★ 为什么是 `FAILED` 而不是 `ABORTED`（2026-10 语义修复 · 霍承钧）
     *
     * 两个词的分界线不是措辞偏好，而是**这个事件该不该被关注**：
     *
     * | 终态 | 语义 | 谁造成的 | 要不要排查 |
     * |---|---|---|---|
     * | `ABORTED` | 用户按了停止按钮 | **用户**（正常行为） | ❌ 不该进缺陷统计 |
     * | `FAILED` | 链路没走完 | **系统**（缺陷） | ✅ 必须能被捞出来 |
     *
     * 冷启时还留着的 `STREAMING` 行，成因只有一个：**上一轮进程没走到终态提交就走了**
     * （LMK / native abort / 崩溃）。把它们标成 `ABORTED`，等于把每一次「进程死了」
     * 都伪装成「用户主动停的」—— 于是将来查「这条消息为什么没回」时，
     * 这类事件在数据里**根本不可见**。这不只是措辞问题，是把一类缺陷从统计中擦掉。
     *
     * ## ★ 关于「失败原因」字段：本表**没有**这一列，本方法也**刻意不加**
     *
     * `chat_message` 里没有任何一列能装失败原因。`commit()` 只收到 `MessageStatus`
     * 三个值（`COMPLETE` / `ABORTED` / `FAILED`，见 `:chat` 的 `ChatLoop` 终态映射），
     * 而 `ChatTerminalReason` 那 9 个值（`MODEL_FAILED` / `BUDGET_EXHAUSTED` /
     * `POLICY_DENIED` / `SKILL_FAILED` / `MODEL_NOT_LOADED` / `REQUEST_STORE_FAILED` /
     * `STORE_FAILED` …）**从来没有落过库** —— 它们只活在 Port 层的事件与日志里。
     * ⇒「swept 行缺一个原因」不是它独有的缺口，而是本表**每一行** `FAILED` 的一致形态：
     *   要填也没地方填，不是「允许为空」，是**这个字段不存在**。
     *
     * 加列 `failure_reason` 在本轮不成立，三条理由：
     *  ① 本表是全工程**写放大最热**的表（流式快照每 400 ms 一次 UPDATE），
     *     加列 ⇒ v3→v4 手写迁移 + `4.json` + `AkeMigrationSchemaTest` 逐列比对，
     *     为一条冷启路径去动最热写入表的 schema，代价与收益倒挂；
     *  ② 唯一能给这一列填有意义值的地方都在 `:chat`（本轮硬约束：不可改），
     *     于是新列对**除 sweep 外的所有** `FAILED` 行恒为 NULL ——
     *     一个「看着该有值却永远没有」的列比没有这一列更糟
     *     （`task.terminal_reason` 就是被逼出来的：那一侧把 `ABORTED` 复用成了垃圾桶，
     *     才需要补一列把 `ORPHANED_*` 捞回来。**本表有 4 个状态，不必重走那条路**）；
     *  ③ 判据是「这类缺陷**能被等值查询发现**」，`status = 'FAILED'` 已经满足；
     *     再细分「是哪种失败」是另一个命题，见下。
     *
     * ⇒ 裁决：不加列。**「我们不知道它为什么停」这件事本身，就是不写原因的诚实表达。**
     *   要补原因，应当由 `:chat` 把 `ChatTerminalReason` 一起落库、一次改齐所有产出方 +
     *   一条 v3→v4 迁移，而不是在这里先开一个只有 1 个产出方有值的洞。
     *   在此之前，「这批 `FAILED` 里哪些是 sweep 产生的」由冷启日志区分 ——
     *   见 `ChatSessionStoreImpl.sweepStreaming()` 里那条带 `PROCESS_KILLED` 的 WARN。
     *
     * ## ★ 两处刻意不动
     *
     *  - **`WHERE status = 'STREAMING'` 守卫保留**：已经 `ABORTED`（用户主动停）或
     *    `COMPLETE` 的行一行都不会被碰到 —— 这正是「用户 stop() 的路径仍然是 ABORTED」
     *    的结构性保证（不靠调用方自觉）。
     *  - **不动 `updated_at`**：它记的是「最后一次快照落盘时间」，是判断
     *    「中断了多久 / 流了多久」的唯一证据。收敛状态不该顺手把它抹掉。
     */
    @Query(ChatMessageDao.SWEEP_SQL)
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

    public companion object {
        /**
         * sweep 的收敛目标态。
         *
         * ★ 抽成常量不是为了「好改」，是为了**能被 JVM 单测断言**：
         * `:data` 是 androidLibrary，纯 JVM 单测起不了真 Room/SQLite（见
         * `AkeMigrationSchemaTest` 类注释的同一困境），而「sweep 收敛到哪个状态」
         * 是这条链路上**唯一真正决定语义**的字符串。写成常量之后，
         * `ChatSweepStreamingTest` 可以对 Room 将来实际执行的那条 SQL 做等值断言，
         * 而不是让测试里的假 DAO 自己复述一遍答案（那样改错了也照样绿）。
         *
         * 与 `ChatMigration2To3.STATEMENTS` 是同一条纪律：**把要断言的 SQL 变成数据**。
         */
        public const val SWEEP_TERMINAL_STATUS: String = "FAILED"

        /**
         * 冷启收敛语句。
         *
         * 三条要素缺一不可：目标态 `FAILED`（缺陷）、来源态 `STREAMING`（中间态）、
         * 以及 `WHERE` 守卫（只碰中间态，绝不覆盖已成终态的行）。
         */
        public const val SWEEP_SQL: String =
            "UPDATE chat_message SET status = '" + SWEEP_TERMINAL_STATUS + "' WHERE status = 'STREAMING'"
    }
}
