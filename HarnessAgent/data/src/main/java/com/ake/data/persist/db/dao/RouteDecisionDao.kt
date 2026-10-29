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

    // ══════════════════════════════════════════════════════════════════════
    // ★ v4：审计闭环（docs/09 §6）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ★ 幂等插入：键冲突时**什么都不做**（返回 -1），不覆盖既有行。
     *
     * 配合 [byIdempotencyKey] 构成「同一次尝试 ⇒ 同一行 ⇒ 同一个 permit」的结构性保证
     * （docs/09 §6.2）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tryInsert(decision: RouteDecisionEntity): Long

    @Query("SELECT * FROM route_decision WHERE idempotency_key = :key")
    suspend fun byIdempotencyKey(key: String): RouteDecisionEntity?

    @Query("SELECT * FROM route_decision WHERE id = :id")
    suspend fun byId(id: String): RouteDecisionEntity?

    /** ★ 同一 turn 内已落的行数 ⇒ `open()` 用它推导 attempt_no / retry_of。 */
    @Query("SELECT COUNT(*) FROM route_decision WHERE turn_id = :turnId")
    suspend fun countByTurn(turnId: String): Int

    /** ★ 同一 turn 最近一行的 id（重试链）。NULL ⇒ 首次尝试。 */
    @Query("SELECT id FROM route_decision WHERE turn_id = :turnId ORDER BY decided_at DESC LIMIT 1")
    suspend fun lastIdByTurn(turnId: String): String?

    /**
     * ★★ 收敛终态。
     *
     * ★ 守卫 `outcome IN ('PENDING','SENT','UNKNOWN')` 三条（docs/09 §6.1）：
     *   ① 重复 close ⇒ 0 行（幂等）；
     *   ② sweep 标成 UNKNOWN 之后，**迟到的 close 仍可改回 DELIVERED/PARTIAL/FAILED**
     *      —— UNKNOWN **不是终态**，这是应对「长流式响应被误判」的关键设计。
     */
    @Query(
        """
        UPDATE route_decision
           SET outcome = :outcome,
               attempt_phase = :phase,
               delivered_bytes = :deliveredBytes,
               finish_reason_raw = :finishReasonRaw,
               request_id = :requestId,
               updated_at = :now
         WHERE id = :id
           AND outcome IN ('PENDING','SENT','UNKNOWN')
        """,
    )
    suspend fun converge(
        id: String,
        outcome: String,
        phase: String,
        deliveredBytes: Int,
        finishReasonRaw: String?,
        requestId: String?,
        now: Long,
    ): Int

    /** ★ 流式传输期间刷新心跳，避免活着的长响应被 sweep 误标 UNKNOWN。 */
    @Query("UPDATE route_decision SET updated_at = :now WHERE id = :id AND outcome = 'SENT'")
    suspend fun heartbeat(id: String, now: Long): Int

    /**
     * ★ 冷启 sweep 的扫描面。
     *
     * ★★ 刻意**没有** liveness 条件 —— 与 `TaskReconciler` 的三处差异之一
     * （docs/09 §6.3）：最该被 sweep 的那批 permit 来自**上一个已被杀掉的进程**，
     * 它们在 `TaskLivenessRegistry` 里根本不存在，加 liveness 等于把目标全跳过。
     */
    @Query(
        """
        SELECT id FROM route_decision
         WHERE outcome IN ('PENDING','SENT','UNKNOWN')
           AND updated_at < :before
         ORDER BY updated_at ASC
         LIMIT :limit
        """,
    )
    suspend fun staleOpenIds(before: Long, limit: Int): List<String>

    /**
     * ★ sweep 收敛：PENDING/SENT/UNKNOWN → UNKNOWN。
     *
     * ★ 守卫里含 UNKNOWN 自身 ⇒ **重复 sweep 幂等**（第二次影响 0 行）。
     */
    @Query(
        """
        UPDATE route_decision SET outcome = 'UNKNOWN', updated_at = :now
         WHERE id = :id AND outcome IN ('PENDING','SENT','UNKNOWN')
        """,
    )
    suspend fun convergeToUnknown(id: String, now: Long): Int

    /** UI 显示「上次有 N 次出云记录未完成」（docs/08 §7.4）。 */
    @Query("SELECT COUNT(*) FROM route_decision WHERE outcome = 'UNKNOWN'")
    suspend fun countUnknown(): Int

    /**
     * ★ T13 交叉验证的查询语句。
     *
     * ★ 刻意写成**不经过我们的常量**的字面 SQL —— 真机 / Python oracle 直接跑同一句，
     *   与 DAO 的自证互为交叉验证（外部 oracle：SQLite 本体）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM route_decision
         WHERE outcome IN ('NOT_SENT','SENT','DELIVERED','PARTIAL','FAILED','UNKNOWN')
        """,
    )
    suspend fun countConvergedOrSent(): Int

    /**
     * ★★ S9 自检：**「route_decision 今日恒为空」这条推理的可执行验证**。
     *
     * docs/09 §1.2-N1 用三条代码事实推出「全量设备上本表行数为 0」，而这条结论支撑了
     * §5.3「存量保留字面量、不重写」的全部取舍。它是全文**唯一靠推理而非实测**的前提
     * ⇒ 必须有东西能直接证伪它。三个出口：
     *  ① 本方法（真机 / 启动自检直接读）；
     *  ② `AkeDatabaseProbe.PROBE_SQL`（纯 SQL 串，device-verify 或 adb 可直接跑）；
     *  ③ `scripts` 侧对拉下来的 db 跑同一句。
     */
    @Query("SELECT COUNT(*) FROM route_decision")
    suspend fun probeTotalRows(): Int

    /**
     * ★ S9 自检第二步：**非规范 `egress_gate_verdict` 字面量计数**。
     *
     * 期望 0（docs/09 §5.3）。若非 0 ⇒ 说明存量里有 `PASS`/`BLOCKED`，
     * 「保留字面量 + 读时规范化」的取舍需要重新评估（先诊断，再清洗，不就地 UPDATE）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM route_decision
         WHERE egress_gate_verdict IS NOT NULL
           AND egress_gate_verdict NOT IN ('UNSCREENED','SCREENED','GATE_DENIED')
        """,
    )
    suspend fun probeNonCanonicalVerdicts(): Int
}
