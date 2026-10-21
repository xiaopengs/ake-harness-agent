package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.contract.MemoryCard
import com.ake.core.contract.TaskSpec
import com.ake.core.ports.MemoryStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers
import com.ake.data.persist.redact.PersistenceRedactionGate

/**
 * `MemoryStorePort` 实现。
 *
 * ## R1：记忆内容是加密的
 *
 * 记忆卡里装的正是「模型在页面上看到的东西」（站点结构笔记、用户偏好），
 * 它长期存在且跨任务复用 —— 泄露面比单条轨迹大得多。所以 `content_enc` 加密，
 * 只留 `content_preview`（≤160 字符）给 UI 列表直接显示。
 *
 * ## MVP 只做「最近 N 条 + host 过滤」
 *
 * 数据库 §10.2 明确不做向量检索，也不建 FTS 表。
 * `relevantTo()` 的口径：**作用域是全局的 或 命中当前域名**，按 pinned → 最近使用排序。
 */
public class MemoryStoreImpl(
    private val db: AkeDatabase,
    private val gate: PersistenceRedactionGate,
    private val maxRelevant: Int = 8,
    private val hardLimit: Int = 2_000,
) : MemoryStorePort {

    override suspend fun put(card: MemoryCard) {
        val enc = gate.sealField(card.text, card.id.toByteArray()).blob
        runCatching { db.memoryDao().upsert(EntityMappers.memoryToEntity(card, enc)) }
            .onFailure { AkeLog.w(TAG, "put failed: ${it.message}") }
        // 配额硬限（§4.4）：超了从最不常用开始删，pinned 除外
        val count = runCatching { db.memoryDao().count() }.getOrDefault(0)
        if (count > hardLimit) {
            val victims = runCatching { db.memoryDao().pickEvictable(count - hardLimit) }.getOrDefault(emptyList())
            if (victims.isNotEmpty()) runCatching { db.memoryDao().delete(victims) }
        }
    }

    override suspend fun relevantTo(task: TaskSpec): List<MemoryCard> {
        val host = task.startUrl?.host.orEmpty()
        val rows = runCatching { db.memoryDao().relevant(host, maxRelevant) }
            .onFailure { AkeLog.w(TAG, "relevantTo failed: ${it.message}") }
            .getOrDefault(emptyList())
        return rows.mapNotNull { row ->
            val text = row.contentEnc?.let { gate.openField(it, row.id.toByteArray()) }
            if (text == null) null else EntityMappers.memoryToDomain(row, text)
        }
    }

    suspend fun touch(id: String) {
        runCatching { db.memoryDao().markUsed(id, System.currentTimeMillis()) }
    }

    suspend fun deleteExpired(now: Long = System.currentTimeMillis()): Int =
        runCatching { db.memoryDao().deleteExpired(now) }.getOrDefault(0)

    suspend fun deleteAll() {
        runCatching { db.memoryDao().deleteAll() }
    }

    companion object {
        const val TAG: String = "MemoryStore"
    }
}
