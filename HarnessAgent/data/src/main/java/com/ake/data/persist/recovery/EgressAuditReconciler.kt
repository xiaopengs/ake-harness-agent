package com.ake.data.persist.recovery

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.data.persist.db.dao.RouteDecisionDao
import com.ake.data.persist.write.PersistenceMetrics

/**
 * ★ 冷启 sweep：把超时未收敛的审计行推进到 `UNKNOWN`（docs/09 §6.3）。
 *
 * ## 为什么它必须存在
 *
 * `EgressPermitSink.close()` 的 UPDATE 失败 / 进程在 ④⑤ 之间被杀 ⇒ 行停在
 * PENDING 或 SENT 且永不收敛。**写入侧无论如何也补不了这个** —— 与 `TaskReconciler`
 * 面对「永久 RUNNING」是同一个问题，用同一个形状的解：**收敛式对账**。
 *
 * ## ★★ 与 `TaskReconciler` 的**三处刻意差异**（改回去就会坏）
 *
 * | 差异 | `TaskReconciler` | 本类 | 为什么 |
 * |---|---|---|---|
 * | 判据列 | `last_activity_at` | **`updated_at`** | 判「有没有被改动过」；`outcome` 的中途更新会刷新它 |
 * | liveness 检查 | ✅ `¬liveness.isLive(id)` | ❌ **不要** | ★ **最该被 sweep 的那批 permit 来自上一个已被杀掉的进程**，它们在 `TaskLivenessRegistry` 里根本不存在 ⇒ 加 liveness 等于把目标全跳过。这是三处里最容易照抄错的一处。 |
 * | UNKNOWN 是否终态 | `ABORTED` 是终态 | ❌ **UNKNOWN 可回改** | 迟到的 `close()` 仍可把它改回 DELIVERED/PARTIAL/FAILED（docs/09 §6.1） |
 *
 * ## ★ UNKNOWN 是「缺口可见」，不是「无缺口」
 *
 * 架构师坦白 `kill -9` 窗口无法消除，设计成 fail-closed：拿不到 permit 就发不出字节。
 * 数据侧能做的是把剩下的缺口**变成一条可见的行**，而不是假装它不存在。
 * ⇒ UI **不要**隐藏 UNKNOWN 计数。
 */
public class EgressAuditReconciler(
    private val dao: RouteDecisionDao,
    private val clock: Clock,
    private val metrics: PersistenceMetrics,
    private val staleAfterMs: Long = PENDING_TTL_MS,
) {

    /** 一轮扫描的结果（既是观测面，也是单测的断言面）。 */
    public data class SweepReport(
        val scanned: Int,
        val convergedUnknown: Int,
        val raced: Int,
    )

    /**
     * 扫一轮并收敛。
     *
     * ★ **幂等**：`convergeToUnknown` 的守卫 `outcome IN ('PENDING','SENT','UNKNOWN')`
     *   含 UNKNOWN 自身 ⇒ 重复扫、或与正常 `close()` 撞在一起，都不会把已到达的终态掀回中间态。
     *
     * @return 报告。DB 不可用时返回全零报告 —— **冷启动不该被一段 DB 异常堵死**。
     */
    public suspend fun sweep(): SweepReport {
        val now = clock.nowMillis()
        val before = now - staleAfterMs
        val ids = runCatching { dao.staleOpenIds(before, BATCH) }
            .onFailure { AkeLog.w(TAG, "stale scan failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (ids.isEmpty()) return SweepReport(0, 0, 0)

        var unknown = 0
        var raced = 0
        for (id in ids) {
            val rows = runCatching { dao.convergeToUnknown(id, now) }.getOrDefault(0)
            if (rows == 0) raced++ else unknown++
        }
        if (unknown > 0) {
            AkeLog.w(TAG, "sweep: $unknown egress audit row(s) -> UNKNOWN (raced=$raced)")
        }
        return SweepReport(scanned = ids.size, convergedUnknown = unknown, raced = raced)
    }

    public companion object {
        public const val TAG: String = "EgressAuditReconciler"
        public const val BATCH: Int = 20   // 与 TaskReconciler.BATCH 同口径

        /**
         * ★ TTL 取 15 分钟，**比 `EgressPermitSink.PENDING_TTL_MS`（10 分钟）更宽松**，
         *   这是刻意的不一致，不是笔误：
         *   SSE 长流可以合法地持续十几分钟；过严会把「还在跑」误标成 UNKNOWN。
         *
         * ★ 但误标的代价已经被「UNKNOWN 可回改」降到很低 —— 两道防线，
         *   而不是把 TTL 调到某个「正确值」就完事（docs/09 §6.3）。
         */
        public const val PENDING_TTL_MS: Long = 15L * 60L * 1000L
    }
}
