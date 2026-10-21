package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.contract.PolicyDecisionRecord
import com.ake.core.ports.PolicyDecisionSink
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers
import com.ake.data.persist.write.StateOp
import com.ake.data.persist.write.TaskStateWriter

/**
 * `PolicyDecisionSink` 实现 —— 护栏审计的落点。
 *
 * ## ★ `record()` 是**非挂起**的
 *
 * 与 `ObservationStorePort.append` 同一条理由（数据库 §6.3）：
 * 审计写失败不能拖慢主循环，所以它必须是「调用即返回」的形态。
 *
 * ## 但它**不可丢弃**
 *
 * 理由与轨迹相反：**审计行比轨迹行更值钱**。
 * 当用户问「它为什么点了这个」时，这一行是唯一的答案来源；
 * 少一行审计会直接导致一个安全事件无法归因。
 *
 * 所以它走的是 `TaskStateWriter` 而不是原来的 P0 队列 ——
 * 那个 P0 通道同样是 `DROP_OLDEST`，「健康状态下深度恒为 0」只是一句愿望。
 *
 * ## 非挂起是怎么和「不可丢」共存的
 *
 * `TaskStateWriter.trySubmit()`：常态一次 `trySend` 就入队（不可丢）；
 * 极端高水位下才转成后台协程投递（`false` 表示「已转后台」，**不是「已丢弃」**）。
 * 也就是说这里用「可能晚一点落盘」换来了「绝不丢失」—— 审计行各自带 ULID 与任务内 seq，
 * 不靠彼此的相对顺序表达正确性，所以这个交换是成立的。
 */
public class PolicyDecisionSinkImpl(
    private val db: AkeDatabase,
    private val state: TaskStateWriter,
) : PolicyDecisionSink {

    override fun record(d: PolicyDecisionRecord): Boolean =
        state.trySubmit(StateOp.PolicyDecision(EntityMappers.policyToEntity(d)))

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
