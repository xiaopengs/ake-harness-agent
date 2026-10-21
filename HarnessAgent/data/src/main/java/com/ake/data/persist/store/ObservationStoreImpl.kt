package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.contract.ObservationSummary
import com.ake.core.contract.PayloadState
import com.ake.core.ports.ObservationStorePort
import com.ake.data.persist.db.mapper.EntityMappers
import com.ake.data.persist.payload.PayloadStore
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.write.TraceBufferWriter
import com.ake.data.persist.write.TraceOp

/**
 * `ObservationStorePort` 实现。
 *
 * ## ★ `append()` 是**非挂起**的（core:ports 的硬要求，数据库 §6.3 / F9）
 *
 * 主循环各处都有 `withTimeout(budget.perceiveMs / decodeMs / actionMs)`。
 * 若这里是阻塞式 DB 写入，**这些 timeout 会被落盘 IO 吃掉**，
 * 而落盘不属于任何一个环节的职责 —— 于是超时归因彻底失效。
 * 所以实现必须是 `trySend`；返回 `false` 表示队列满了、这一条被丢弃。
 *
 * ## url_path 是 R1
 *
 * host 刻意**留明文**（域名策略要精确等值查询，加密会破坏这件事，数据库 §8.1），
 * 但 path/query 里可能有 token 之类的东西 ⇒ 加密。
 */
public class ObservationStoreImpl(
    private val writer: TraceBufferWriter,
    private val gate: PersistenceRedactionGate,
    private val payloads: PayloadStore,
    /** 行反查函数。由 `PersistenceModule` 注入 `traceDao::observation` 的映射版本。 */
    private val findRow: suspend (String) -> ObservationRow?,
) : ObservationStorePort {

    /**
     * @return false = 队列满，这一条被丢弃。
     *
     * ★ 丢弃落在**轨迹泳道**，是有意的背压出口；writer 会在排水时按任务聚合写一条
     * `TRACE_DROPPED` 自证（自证本身不会丢 —— 它是在事务里生成的）。
     */
    override fun append(summary: ObservationSummary, payload: String?): Boolean {
        val row = EntityMappers.observationToEntity(summary).run {
            // urlPath 加密；host 保持明文
            if (summary.urlPath.isNotBlank()) {
                copy(urlPathEnc = gate.sealField(summary.urlPath, summary.taskId.value.toByteArray()).blob)
            } else {
                this
            }
        }
        val withState = if (payload == null) {
            row.copy(payloadState = PayloadState.DROPPED, payloadCodec = null)
        } else {
            row.copy(payloadState = PayloadState.EXTERNAL, payloadCodec = "gzip")
        }
        return writer.append(TraceOp.Observation(withState, payload))
    }

    override suspend fun loadPayload(observationId: String): String? {
        // 需要 taskId 才能定位目录 —— 反查一次行（低频路径，直接走 DAO 是可以的）
        val row = runCatching { findRow(observationId) }.getOrNull()
        val file = row?.payloadFile
        if (file == null) {
            AkeLog.w(TAG, "no payload file for observation $observationId")
            return null
        }
        return payloads.read(row.taskId, file)
    }

    companion object {
        const val TAG: String = "ObservationStore"
    }
}

/**
 * 行反查的最小投影。
 *
 * 特意**不是 Entity** —— 它会被 `PersistenceModule` 从 DAO 结果映射出来，
 * 这样 store 层不认识 Room 类型（数据库 §3.1 铁律 1 的延伸）。
 */
public data class ObservationRow(val taskId: String, val payloadFile: String?)
