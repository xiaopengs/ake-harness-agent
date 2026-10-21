package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.TaskId
import com.ake.core.contract.StepRecord
import com.ake.core.ports.TraceStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers
import com.ake.data.persist.write.TraceBufferWriter
import com.ake.data.persist.write.TraceOp

/**
 * `TraceStorePort` 实现。
 *
 * ## 写：一级队列，try-send
 *
 * `step` 是「每步一行」的常规行，走 P1。队列满时**丢弃并自证** ——
 * 丢一行轨迹的代价是回放少一步，而阻塞主循环的代价是超时归因失效，两者不对等。
 *
 * ★ 这条「允许丢弃」的语义 2026-10 之后**仍然成立**，但它只覆盖轨迹类：
 * 任务状态类（含断点与审计）已经整体迁出去，见 `com.ake.data.persist.write.TaskStateWriter`。
 *
 * ## 读：`load()` 是有损的
 *
 * `StepRecord.result`（`ToolResult`）在库里没有完整形态 —— 它是 prompt 形态不是存储形态。
 * 所以 `load()` 还原出来的是**够用于历史摘要**的版本（`isOk` + `hintBrief()` 语义等价）。
 * 这是刻意的取舍：为了「能重放每一步」去存完整的 `ToolResult`，
 * 会让 `step` 表多出几个列，而那个能力 MVP 根本用不到。
 */
public class TraceStoreImpl(
    private val db: AkeDatabase,
    private val writer: TraceBufferWriter,
    private val codec: StepTraceCodec,
) : TraceStorePort {

    override suspend fun append(step: StepRecord) {
        writer.append(
            TraceOp.Step(
                EntityMappers.stepToEntity(
                    rec = step,
                    actionKey = codec.actionKey(step.action),
                    actionJson = runCatching { codec.encodeAction(step.action) }.getOrNull(),
                    actionSummary = codec.actionSummary(step.index, step.action, step.result),
                ),
            ),
        )
    }

    override suspend fun load(taskId: TaskId): List<StepRecord> {
        val rows = runCatching { db.traceDao().stepsOf(taskId.value) }
            .onFailure { AkeLog.w(TAG, "load steps failed: ${it.message}") }
            .getOrDefault(emptyList())
        val out = ArrayList<StepRecord>(rows.size)
        for (r in rows) {
            val rec = EntityMappers.stepToDomain(r, codec::decodeAction)
            if (rec == null) {
                AkeLog.w(TAG, "step ${r.id} history unrecoverable, skipped")
                continue
            }
            out += rec
        }
        return out
    }

    /** 给 UI 的分页读取（避免长任务一次性拉几百行）。 */
    suspend fun page(taskId: TaskId, limit: Int, offset: Int): List<StepRecord> =
        runCatching { db.traceDao().stepsPage(taskId.value, limit, offset) }
            .getOrDefault(emptyList())
            .mapNotNull { EntityMappers.stepToDomain(it, codec::decodeAction) }

    companion object {
        const val TAG: String = "TraceStore"
    }
}
