package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.common.Ulid
import com.ake.core.ports.DataClass
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.data.persist.db.dao.RouteDecisionDao
import com.ake.data.persist.db.entity.RouteDecisionEntity

/**
 * 路由审计的落点（§5）。
 *
 * ## ★ 写行判据：只在「有人想出云」时写行（§5.3）
 *
 * `route_decision` 是审计表。若每轮无脑写一行，它会与 `chat_message` 同量级增长，
 * 而它的每一行在 MVP 下都是**同一行内容**（`decided=LOCAL_ONLY`）——
 * 一万行相同内容的审计记录不增加任何信息，只增加配额压力与 `idx_route_decided` 的维护成本。
 *
 * 三个判据取或：
 *  ① 有人**请求**出云（`ctx.requested != LOCAL_ONLY`）—— 覆盖 V5 用例；
 *  ② **实际**出云了（`decision.target != LOCAL_ONLY`）—— 正常态下不该发生；
 *  ③ debug 包强制记账开关 —— **给自己的**：恒空的表无法证明链路通。
 *
 * ★ 这个判据必须写成**一个函数**、有一个开关，而不是散落在调用点的 `if`。
 *   否则「为什么这张表是空的」会在半年后变成一个没人敢回答的问题。
 *
 * ## ★★ 本表没有任何一列能装 payload 原文（§5.1）
 *
 * `payload_bytes` 是大小（数字），`*_classes_json` 是**类别白名单**（枚举集合）。
 * 这不是疏漏，是设计：让「顺手记一份」在物理上做不到。
 */
public class RouteDecisionSink internal constructor(
    private val dao: RouteDecisionDao,
    private val clock: Clock,
    /**
     * ★ 强制记账开关（release 恒 false）。
     *
     * 上游写的是 `BuildConfig.CHAT_ROUTE_FORCE_AUDIT`。本模块把它做成**注入参数**而不是
     * 编译期常量：注入让它可以在 JVM 单测里被覆盖（真机跑不了 Room，
     * 而这条判据恰恰是最需要被单测卡住的一条）。`:app` 侧用
     * `BuildConfig.DEBUG` 绑进来即可 —— 语义一致，可测性更好。
     */
    private val forceAudit: Boolean = false,
) {

    /** ★ 唯一的判据函数。改动它之前先读上面的类注释。 */
    public fun shouldAudit(ctx: RouteContext, decision: RoutingDecision): Boolean =
        ctx.requested != InferenceTarget.LOCAL_ONLY ||
            decision.target != InferenceTarget.LOCAL_ONLY ||
            forceAudit

    /**
     * 落一行审计。
     * @return 新行的 id；`null` 表示按判据**不记**（这不是失败）。
     */
    public suspend fun record(
        ctx: RouteContext,
        decision: RoutingDecision,
        chatMessageId: String?,
        payloadBytes: Int = 0,
        egressGateVerdict: String? = null,
    ): String? {
        if (!shouldAudit(ctx, decision)) return null
        val now = clock.nowMillis()
        val row = RouteDecisionEntity(
            id = Ulid.next(now),
            chatMessageId = chatMessageId,
            agentId = ctx.agentId,
            sessionId = ctx.sessionId,
            turnId = ctx.turnId,
            requested = ctx.requested.name,
            requestedClassesJson = encodeClasses(ctx.payloadClasses),
            decided = decision.target.name,
            reasonKey = decision.reasonKey,
            allowedClassesJson = encodeClasses(decision.allowedClasses),
            egressGateVerdict = egressGateVerdict,
            redactionApplied = if (decision.redactionApplied) 1 else 0,
            payloadBytes = payloadBytes,
            revokedAt = null,
            decidedAt = now,
            schemaV = RouteDecisionEntity.SCHEMA_VERSION,
        )
        return runCatching { dao.insert(row) }
            .fold(
                onSuccess = { row.id },
                onFailure = { t ->
                    // ★ 审计写失败**不**阻断主流程，但一定要留痕：
                    //   「审计缺失」本身就是 §8 T12 点名的风险，静默吞掉等于自己制造它。
                    AkeLog.w(TAG, "route_decision insert failed: ${t.message}")
                    null
                },
            )
    }

    /** 用户撤回本次出云（上游 §6.2 裁决 3）。 */
    public suspend fun revoke(id: String): Int =
        runCatching { dao.revoke(id, clock.nowMillis()) }.getOrDefault(0)

    /** §5.6：90 天 + 行数上限 5,000。**审计表与数据表的保留策略必须解耦**。 */
    public suspend fun retentionSweep(retentionMs: Long = RETENTION_MS, maxRows: Int = MAX_ROWS): Int {
        val removed = runCatching { dao.deleteBefore(clock.nowMillis() - retentionMs) }.getOrDefault(0)
        val trimmed = runCatching { dao.deleteKeepingNewest(maxRows) }.getOrDefault(0)
        return removed + trimmed
    }

    public fun encodeClasses(classes: Set<DataClass>): String = classes.joinToString(",") { it.name }

    public companion object {
        public const val TAG: String = "RouteDecisionSink"

        /** §5.6：90 天。 */
        public const val RETENTION_MS: Long = 90L * 24 * 60 * 60 * 1000

        /** §5.6：行数上限 5,000。 */
        public const val MAX_ROWS: Int = 5_000
    }
}
