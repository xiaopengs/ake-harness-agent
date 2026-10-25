package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.RouteDecisionEntity

/**
 * 路由审计 DAO（§3.8 / §5）。
 *
 * ## ★ 写行判据不在这里，在 `store/RouteDecisionSink.shouldAudit()`
 *
 * MVP 恒裁决 LOCAL，若每轮无脑写一行，本表会与 `chat_message` 同量级增长，
 * 而每一行的内容都相同 —— 一万行相同内容的审计记录不增加任何信息，
 * 只增加配额压力与 `idx_route_decided` 的维护成本（§5.3）。
 */
@Dao
interface RouteDecisionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(decision: RouteDecisionEntity)

    /** ★ 「这台机器有没有把数据送出去过」—— 未来放开云端后的第一查询。 */
    @Query("SELECT COUNT(*) FROM route_decision WHERE decided = 'CLOUD_ASSISTED'")
    suspend fun countCloudAssisted(): Int

    @Query("SELECT * FROM route_decision WHERE turn_id = :turnId ORDER BY decided_at DESC")
    suspend fun byTurn(turnId: String): List<RouteDecisionEntity>

    @Query("SELECT * FROM route_decision WHERE chat_message_id IS :messageId ORDER BY decided_at DESC")
    suspend fun byMessage(messageId: String?): List<RouteDecisionEntity>

    /** 用户撤回本次出云（上游 §6.2 裁决 3）。只改第一次，重复撤回返回 0 行。 */
    @Query("UPDATE route_decision SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
    suspend fun revoke(id: String, now: Long): Int

    /**
     * §5.6 独立保留：**90 天 + 行数上限 5,000**（超出删最老）。
     *
     * ★ 审计表与数据表的保留策略必须**解耦**：直觉是「消息都没了，审计留着干嘛」，
     * 但审计行天然不含 D0/D1 原文（只有类别白名单与 reason_key），留着它不增加隐私风险；
     * 删掉它，「这台机器到底有没有把数据送出去过」在 90 天后就无从回答 —— 那才是合规缺口。
     */
    @Query("DELETE FROM route_decision WHERE decided_at < :before")
    suspend fun deleteBefore(before: Long): Int

    /**
     * 行数上限段：只保留最新的 [keep] 行。
     *
     * `LIMIT -1 OFFSET :keep` 是 SQLite 的写法（负 LIMIT = 不限）：
     * 先按 `decided_at DESC` 排，跳过最新的 keep 行，剩下的全部删掉。
     */
    @Query(
        """
        DELETE FROM route_decision
         WHERE id IN (
             SELECT id FROM route_decision ORDER BY decided_at DESC LIMIT -1 OFFSET :keep
         )
        """,
    )
    suspend fun deleteKeepingNewest(keep: Int): Int

    @Query("SELECT COUNT(*) FROM route_decision")
    suspend fun count(): Int
}
