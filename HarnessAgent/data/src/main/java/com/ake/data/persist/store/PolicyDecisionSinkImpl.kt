package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.contract.PolicyDecisionRecord
import com.ake.core.ports.PolicyDecisionSink
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers
import com.ake.data.persist.write.Op
import com.ake.data.persist.write.TraceBufferWriter

/**
 * `PolicyDecisionSink` 实现 —— 护栏审计的落点。
 *
 * ## ★ `record()` 是**非挂起**的
 *
 * 与 `ObservationStorePort.append` 同一条理由（数据库 §6.3）：
 * 审计写失败不能拖慢主循环。所以是 `trySend`，返回 false 表示丢弃。
 *
 * ## 但它是 P0（不可丢）
 *
 * 理由与轨迹相反：**审计行比轨迹行更值钱**。
 * 当用户问「它为什么点了这个」时，这一行是唯一的答案来源；
 * 少一行审计会直接导致一个安全事件无法归因。
 * 所以给它 P0 队列（容量 64，且健康状态下深度恒为 0）。
 */
public class PolicyDecisionSinkImpl(
    private val db: AkeDatabase,
    private val writer: TraceBufferWriter,
) : PolicyDecisionSink {

    override fun record(d: PolicyDecisionRecord): Boolean =
        writer.append(Op.PolicyDecision(EntityMappers.policyToEntity(d)))

    /** 安全回归测试入口：某任务被拒过几次。 */
    suspend fun denialsOf(taskId: String): List<PolicyDecisionRecord> =
        runCatching { db.policyDao().ofTask(taskId) }.getOrDefault(emptyList())
            .map(EntityMappers::policyToDomain)

    suspend fun countDeniedSince(since: Long): Int =
        runCatching { db.policyDao().countDecisionSince("DENIED", since) }.getOrDefault(0)

    companion object {
        const val TAG: String = "PolicySink"
    }
}
