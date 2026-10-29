package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Hosts
import com.ake.core.common.Outcome
import com.ake.core.common.Ulid
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.data.persist.db.DbTransaction
import com.ake.data.persist.db.dao.ChatSessionDao
import com.ake.data.persist.db.dao.CloudCredentialDao
import com.ake.data.persist.db.dao.RouteDecisionDao
import com.ake.data.persist.db.entity.RouteDecisionEntity
import com.ake.data.persist.recovery.EgressAuditReconciler

/**
 * ★★ `EgressPermitSink` 的 Room 实现（docs/09 §6）。
 *
 * ## 方向与架构师的设计一致：**先落 PENDING，再允许发字节**
 *
 * ```
 *   旧  决策 → 记账        ⇒ 中间崩了：有决策、无记账（缺口**不可见**）
 *   新  记账(PENDING) → 决策 → 执行 → UPDATE 终态
 *        ⇒ 中间崩了：有一行 PENDING，而 PENDING 可被发现、可被报警、可被 sweep
 * ```
 * 全部收益就在这里：**把「审计缺口」变成「一行未收敛的 PENDING」**。
 *
 * ## ★ fail-closed（数据侧怎么支持它）
 *
 * `open()` 失败 ⇒ 返回 `Err` ⇒ 拿不到 `EgressPermit` ⇒ 构造不出
 * `InferenceTarget.CloudAssisted`（它**必须**带 permit）⇒ **一个字节都发不出去**。
 * ⇒ 「上云了却没记账」这个方向在结构上被堵死；剩下的只是「没能上云」。
 *
 * ## ★ 为什么事务走 `DbTransaction` 接缝而不是直接 `db.withTransaction`
 *
 * `RoomDatabase.withTransaction` 依赖真开库才存在的 `internalTransactionExecutor`
 * ⇒ 直接调用它的代码在 JVM 单测里**整条不可测**。而这段逻辑（幂等键、fail-closed）
 * 错了的表现是「数据出去了但没记账」且不可见 ⇒ 必须可测。见 `DbTransaction`。
 *
 * ## ★ 幂等键：并发写不产生重复行
 *
 * 键 = `"<turnId>:<decidedAt>"`，配 `UNIQUE(idx_route_idem)` +
 *      `INSERT OR IGNORE` + 读回 ⇒ **同一次尝试的重复 `open()` 返回同一个 permit**。
 *
 * ⚠ 为什么不是 docs/09 §6.2 写的 `"<turnId>:<attemptNo>"`：`RouteContext` **没有**
 *    attemptNo 字段，且 `EgressPermitSink.open(ctx, decision)` 的签名属 `:core:ports`、
 *    本轮不可改。改用 `decision.decidedAt` 判别「同一次尝试」：
 *    重播 ⇒ 同一个 decision ⇒ 同键；重试 ⇒ 新 decision（新时间戳）⇒ 新键 ⇒ 独立审计行。
 *    ★ 已知弱化：**同一毫秒内的两次重试会被去重**。实际重试必经网络失败，间隔远大于 1 ms。
 *
 * ## ★ UNKNOWN 不是终态
 *
 * `converge` 的守卫含 UNKNOWN ⇒ sweep 把行标成 UNKNOWN 之后，
 * **迟到的 `close()` 仍可把它改回 DELIVERED/PARTIAL/FAILED**（docs/09 §6.1）。
 */
public class EgressPermitSinkImpl internal constructor(
    private val tx: DbTransaction,
    private val routeDao: RouteDecisionDao,
    private val sessions: ChatSessionDao,
    private val credentials: CloudCredentialDao,
    private val reconciler: EgressAuditReconciler,
    private val clock: Clock,
) : EgressPermitSink {

    override suspend fun open(
        ctx: RouteContext,
        decision: RoutingDecision,
    ): Outcome<EgressPermit> = runCatching {
        val now = clock.nowMillis()
        val key = idempotencyKeyOf(ctx, decision)

        val permit = tx.run {
            // ① 同键已存在 ⇒ 直接复用（幂等：不产生第二行，返回同一个 auditRowId）
            val existing = routeDao.byIdempotencyKey(key)
            if (existing != null) return@run permitOf(existing)

            // ② 端点 / 凭证：只能从**会话已登记的目标**取（签名里没有 credentialRef）
            val credId = sessions.cloudCredentialIdOf(ctx.sessionId)
                ?: return@run null
            val cred = credentials.byId(credId)
                ?: return@run null
            if (cred.deletedAt != null) return@run null

            val attemptNo = routeDao.countByTurn(ctx.turnId)
            val retryOf = routeDao.lastIdByTurn(ctx.turnId)
            val row = RouteDecisionEntity(
                id = Ulid.next(now),
                chatMessageId = null,
                agentId = ctx.agentId,
                sessionId = ctx.sessionId,
                turnId = ctx.turnId,
                requested = ctx.requested.code,
                requestedClassesJson = ctx.payloadClasses.joinToString(",") { it.name },
                decided = decision.target.code,
                reasonKey = decision.reasonKey,
                allowedClassesJson = decision.allowedClasses.joinToString(",") { it.name },
                egressGateVerdict = null,
                redactionApplied = if (decision.redactionApplied) 1 else 0,
                payloadBytes = 0,
                revokedAt = null,
                decidedAt = decision.decidedAt,
                outcome = AuditOutcome.PENDING.name,
                attemptPhase = null,
                attemptNo = attemptNo,
                retryOf = retryOf,
                idempotencyKey = key,
                credentialId = cred.credId,
                endpointHost = cred.endpointHost,
                modelName = cred.modelName,
                requestId = null,
                deliveredBytes = 0,
                finishReasonRaw = null,
                policyProfile = RouteDecisionSink.POLICY_PROFILE,
                updatedAt = now,
                schemaV = RouteDecisionEntity.SCHEMA_VERSION,
            )
            // INSERT OR IGNORE：键冲突 ⇒ -1，什么都不改（不覆盖既有行）
            routeDao.tryInsert(row)
            val stored = routeDao.byIdempotencyKey(key) ?: return@run null
            permitOf(stored)
        }

        permit ?: return@runCatching Outcome.Err(noPermit())
        Outcome.Ok(permit)
    }.fold(
        onSuccess = { it },
        onFailure = { t ->
            // ★ fail-closed：任何异常都表现为「拿不到 permit」⇒ 发不出字节。
            AkeLog.w(TAG, "open failed (fail-closed): ${t.message}")
            Outcome.Err(noPermit())
        },
    )

    override suspend fun close(
        permit: EgressPermit,
        outcome: AuditOutcome,
        phase: EgressPhase,
        deliveredBytes: Int,
        finishReasonRaw: String?,
    ): Outcome<Unit> {
        val requestId = permit.auditRowId
        val rows = runCatching {
            routeDao.converge(
                id = permit.auditRowId,
                outcome = outcome.name,
                phase = phase.name,
                deliveredBytes = deliveredBytes,
                finishReasonRaw = finishReasonRaw,
                requestId = requestId,
                now = clock.nowMillis(),
            )
        }.getOrNull()
        return if (rows == null) {
            Outcome.Err(AppError(AppError.CODE_IO, "err_egress_close"))
        } else {
            Outcome.Ok(Unit)
        }
    }

    override suspend fun sweepPending(olderThanMs: Long): Int =
        reconciler.sweep().convergedUnknown

    /** ★ 流式传输期间刷新心跳，避免活着的长响应被 sweep 误标 UNKNOWN。 */
    public suspend fun heartbeat(permit: EgressPermit): Int =
        runCatching { routeDao.heartbeat(permit.auditRowId, clock.nowMillis()) }.getOrDefault(0)

    public suspend fun countUnknown(): Int =
        runCatching { routeDao.countUnknown() }.getOrDefault(0)

    private fun idempotencyKeyOf(ctx: RouteContext, decision: RoutingDecision): String =
        "${ctx.turnId}:${decision.decidedAt}"

    private fun permitOf(row: RouteDecisionEntity): EgressPermit = EgressPermit(
        auditRowId = row.id,
        decidedAt = row.decidedAt,
        // ★ 已是 `Hosts.normalise` 的产物（落库时就归一过），不再重算。
        endpointHost = row.endpointHost ?: Hosts.normalise(""),
        credentialRef = CredentialRef(row.credentialId ?: ""),
        allowedAttempts = 1,
    )

    private fun noPermit(): AppError = AppError(
        code = com.ake.core.ports.CloudFailureCodes.NO_PERMIT,
        reasonKey = CloudFailure.NoPermit.reasonKey,
        severity = com.ake.core.common.Severity.MEDIUM,
    )

    public companion object {
        public const val TAG: String = "EgressPermitSink"
    }
}
