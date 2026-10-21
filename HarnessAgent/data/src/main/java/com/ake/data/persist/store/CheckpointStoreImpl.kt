package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.TaskId
import com.ake.core.contract.LoopCheckpoint
import com.ake.core.ports.CheckpointStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.write.Op
import com.ake.data.persist.write.TraceBufferWriter

/**
 * `CheckpointStorePort` 实现。
 *
 * ## 为什么断点走 P0（不可丢）
 *
 * 断点里装着**四个预算累计**。丢一次断点，用户点「继续」后的这个任务
 * 就拿到一整份全新的预算 —— 宋砚秋的原话是「等于每次暂停白送一张预算卡」。
 * 这个后果（用户以为任务被限流了，其实是我们的账记错了）比丢几行轨迹严重得多。
 *
 * ## scratchpad 是 R1
 *
 * 模型自己的笔记里可能有用户数据（它记的正是页面上看到的东西），所以加密。
 * AAD 用 taskId，防止密文被跨行搬移。
 */
public class CheckpointStoreImpl(
    private val db: AkeDatabase,
    private val writer: TraceBufferWriter,
    private val gate: PersistenceRedactionGate,
) : CheckpointStorePort {

    override suspend fun save(cp: LoopCheckpoint) {
        val enc = cp.scratchpad?.takeIf { it.isNotBlank() }
            ?.let { gate.sealField(it, cp.taskId.value.toByteArray()).blob }
        writer.append(Op.Checkpoint(EntityMappers.checkpointToEntity(cp, enc)))
    }

    override suspend fun load(taskId: TaskId): LoopCheckpoint? {
        val row = runCatching { db.checkpointDao().byTask(taskId.value) }
            .onFailure { AkeLog.w(TAG, "load failed: ${it.message}") }
            .getOrNull() ?: return null
        val scratch = row.scratchpadEnc?.let { gate.openField(it, taskId.value.toByteArray()) }
        return EntityMappers.checkpointToDomain(row, scratch)
    }

    override suspend fun clear(taskId: TaskId) {
        runCatching { db.checkpointDao().delete(taskId.value) }
            .onFailure { AkeLog.w(TAG, "clear failed: ${it.message}") }
    }

    override suspend fun findResumable(): List<TaskId> =
        runCatching { db.checkpointDao().findResumable() }
            .getOrDefault(emptyList())
            .map { TaskId(it) }

    /**
     * 暂停/终止前必须调用：**显式等待断点真正落盘**。
     * 这是全模块唯一一处「刻意阻塞主流程」的写入，因为正确性依赖它。
     */
    suspend fun flushBeforeSuspend() {
        writer.flushNow()
    }

    companion object {
        const val TAG: String = "CheckpointStore"
    }
}
