package com.ake.data.persist.fake

import com.ake.core.common.FieldCipher
import com.ake.data.persist.db.dao.AgentProfileDao
import com.ake.data.persist.db.dao.CapabilityGrantDao
import com.ake.data.persist.db.dao.ChatMessageDao
import com.ake.data.persist.db.dao.ChatSessionDao
import com.ake.data.persist.db.dao.MemoryDao
import com.ake.data.persist.db.dao.SessionDao
import com.ake.data.persist.db.dao.TemplateDao
import com.ake.data.persist.db.entity.AgentCapabilityGrantEntity
import com.ake.data.persist.db.entity.AgentProfileEntity
import com.ake.data.persist.db.entity.ChatMessageEntity
import com.ake.data.persist.db.entity.ChatSessionEntity
import com.ake.data.persist.db.entity.MemoryCardEntity
import com.ake.data.persist.db.entity.SessionEntity
import com.ake.data.persist.db.entity.TemplateRegistryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 聊天化 Agent 那几张表的内存 DAO（`chat` 面）。与 `PersistenceFakes.kt` 同一纪律：
 *
 *  - **不用 mock**：mock 会把「复合主键去重」「IGNORE 不覆盖既有行」这类守卫一起 mock 掉，
 *    而那正是本轮要回归的东西。
 *  - 未被本轮触达的方法一律 [unsupported] 而不是返回空值 —— 空值会让
 *    「覆盖到了」变成假象。
 */

/**
 * 测试用 `FieldCipher`：明文前后各加一个标记字节。
 *
 * 它**不是**加密（单测不验密码学），但它保住了两件测试关心的事：
 * `sealField` 不会因为「没有 cipher」而抛，`openField` 能取回原文。
 */
internal class FakeFieldCipher : FieldCipher {
    override fun seal(plain: ByteArray, aad: ByteArray?): ByteArray =
        byteArrayOf(MARK) + plain + (aad ?: ByteArray(0))

    override fun open(blob: ByteArray, aad: ByteArray?): ByteArray? {
        if (blob.isEmpty() || blob.first() != MARK) return null
        val tail = (aad ?: ByteArray(0)).size
        return blob.copyOfRange(1, blob.size - tail)
    }

    private companion object {
        const val MARK: Byte = 0x5A
    }
}

/**
 * `template_registry` 的内存实现。
 *
 * ★ [insert] 保留真实 DAO 的 `OnConflictStrategy.ABORT`：`TemplateBootstrap` 先 `byId` 判空
 *   再 `insert`，如果两者不一致（例如并发 bootstrap），这条 ABORT 是唯一会喊出来的地方。
 */
internal class FakeTemplateDao : TemplateDao {

    val rows: LinkedHashMap<String, TemplateRegistryEntity> = LinkedHashMap()

    /** 写动作总序，用来断言「先判空再插入」而不是无脑覆盖。 */
    val journal: MutableList<String> = ArrayList()

    private val flow = MutableStateFlow(0)

    fun put(row: TemplateRegistryEntity) {
        rows[row.templateId] = row
        flow.value += 1
    }

    override fun observeAll(): Flow<List<TemplateRegistryEntity>> =
        flow.map { rows.values.sortedWith(compareBy({ it.source }, { it.name })) }

    override suspend fun builtins(): List<TemplateRegistryEntity> =
        rows.values.filter { it.source == "BUILTIN" }.sortedBy { it.name }

    override suspend fun byId(templateId: String): TemplateRegistryEntity? = rows[templateId]

    override suspend fun insert(row: TemplateRegistryEntity) {
        check(!rows.containsKey(row.templateId)) { "ABORT: template_registry 主键冲突 ${row.templateId}" }
        journal += "insert:${row.templateId}"
        put(row)
    }

    override suspend fun upsert(row: TemplateRegistryEntity) {
        journal += "upsert:${row.templateId}"
        put(row)
    }

    override suspend fun delete(templateId: String): Int {
        journal += "delete:$templateId"
        return if (rows.remove(templateId) != null) 1 else 0
    }

    override suspend fun deleteInstalled(): Int {
        val victims = rows.values.filter { it.source == "INSTALLED" }.map { it.templateId }
        victims.forEach { rows.remove(it) }
        return victims.size
    }

    override suspend fun count(): Int = rows.size
}

/** `agent_profile` 的内存实现：本轮只关心「谁是从哪个模板来的」。 */
internal class FakeAgentProfileDao : AgentProfileDao {

    val rows: LinkedHashMap<String, AgentProfileEntity> = LinkedHashMap()

    fun put(row: AgentProfileEntity) {
        rows[row.id] = row
    }

    override suspend fun idsOfTemplate(templateId: String): List<String> =
        rows.values.filter { it.templateId == templateId }.map { it.id }.sorted()

    override suspend fun upsert(profile: AgentProfileEntity) {
        rows[profile.id] = profile
    }

    override suspend fun byId(id: String): AgentProfileEntity? = rows[id]

    override suspend fun byAgentId(agentId: String): AgentProfileEntity? =
        rows.values.firstOrNull { it.agentId == agentId || it.id == agentId }

    override suspend fun exists(id: String): String? = if (rows.containsKey(id)) id else null

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun touchUsed(id: String, now: Long): Unit = unsupported("touchUsed")
    override suspend fun archive(id: String, now: Long): Unit = unsupported("archive")
    override suspend fun countActive(): Int = rows.values.count { it.archived == 0 }
    override fun observeActive(): Flow<List<AgentProfileEntity>> = unsupported("observeActive")
    override fun observeArchived(): Flow<List<AgentProfileEntity>> = unsupported("observeArchived")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("FakeAgentProfileDao.$name 未被本轮测试覆盖")
}

/**
 * `agent_capability_grant` 的内存实现 —— ★ 两条真实语义被刻意保留：
 *
 *  ① **复合主键** `(agent_id, kind, ref_id)`：REPLACE 覆盖、IGNORE 跳过；
 *  ② **[grantAllIfAbsent] 不覆盖既有行的 `granted_at`** ——
 *     这正是回填幂等且不抹掉审计证据的判据（`TemplateBootstrap` 每次冷启都会调它）。
 */
internal class FakeCapabilityGrantDao : CapabilityGrantDao {

    private data class Key(val agentId: String, val kind: String, val refId: String)

    private val rows: LinkedHashMap<Key, AgentCapabilityGrantEntity> = LinkedHashMap()

    /** 全部行（只读快照）。用来断言 `granted_at` 这类**不该被重跑改写**的列。 */
    val allRows: List<AgentCapabilityGrantEntity> get() = rows.values.toList()

    /** 每次写入了哪些 key，供断言「重跑不写入」用。 */
    val insertedKeys: MutableList<String> = ArrayList()

    private var nextRowId: Long = 1

    override suspend fun grant(row: AgentCapabilityGrantEntity) {
        val key = Key(row.agentId, row.kind, row.refId)
        rows[key] = row
        insertedKeys += "${row.agentId}/${row.kind}/${row.refId}"
    }

    override suspend fun grantAll(rows: List<AgentCapabilityGrantEntity>) {
        rows.forEach { grant(it) }
    }

    override suspend fun grantAllIfAbsent(rows: List<AgentCapabilityGrantEntity>): List<Long> =
        rows.map { row ->
            val key = Key(row.agentId, row.kind, row.refId)
            if (this.rows.containsKey(key)) {
                -1L
            } else {
                grant(row)
                nextRowId++
            }
        }

    override suspend fun revoke(agentId: String, kind: String, refId: String): Int =
        if (rows.remove(Key(agentId, kind, refId)) != null) 1 else 0

    override suspend fun revokeAll(agentId: String): Int {
        val victims = rows.keys.filter { it.agentId == agentId }
        victims.forEach { rows.remove(it) }
        return victims.size
    }

    override suspend fun grantedRows(agentId: String): List<AgentCapabilityGrantEntity> =
        rows.values.filter { it.agentId == agentId }

    override fun observe(agentId: String): Flow<List<AgentCapabilityGrantEntity>> =
        MutableStateFlow(grantedRowsSync(agentId))

    override suspend fun countOf(agentId: String): Int = rows.values.count { it.agentId == agentId }

    override suspend fun revokeToolEverywhere(toolId: String): Int {
        val victims = rows.keys.filter { it.kind == "MCP_TOOL" && it.refId == toolId }
        victims.forEach { rows.remove(it) }
        return victims.size
    }

    /** 便于断言的可读形态：`BUILTIN:builtin.browser_task` 之类的集合。 */
    fun grantedRefs(agentId: String): List<String> =
        grantedRowsSync(agentId).map { "${it.kind}:${it.refId}" }

    /** `observe()` 是 Flow，测试要立即取值 ⇒ 这里的同步快照与它同一数据源。 */
    private fun grantedRowsSync(agentId: String): List<AgentCapabilityGrantEntity> =
        rows.values.filter { it.agentId == agentId }
}

// ────────────────────────────────────────────────────────────────
// 与「授权写入」无关的三张表：本轮不覆盖，一律显式 unsupported
// ────────────────────────────────────────────────────────────────

internal class UnsupportedChatSessionDao : ChatSessionDao {
    override suspend fun insert(session: ChatSessionEntity): Unit = unsupported("insert")
    override suspend fun upsert(session: ChatSessionEntity): Unit = unsupported("upsert")
    override fun observeRecent(limit: Int): Flow<List<ChatSessionEntity>> = unsupported("observeRecent")
    override fun observeByAgent(agentId: String): Flow<List<ChatSessionEntity>> = unsupported("observeByAgent")
    override suspend fun byId(id: String): ChatSessionEntity? = unsupported("byId")
    override suspend fun modelTargetOf(sessionId: String): String? = unsupported("modelTargetOf")
    override suspend fun touchForNewMessage(
        sessionId: String,
        now: Long,
        preview: String?,
        nextSeq: Int,
        unreadDelta: Int,
    ): Unit = unsupported("touchForNewMessage")

    override suspend fun touchForAssistant(sessionId: String, now: Long, nextSeq: Int): Unit =
        unsupported("touchForAssistant")

    override suspend fun saveDraft(sessionId: String, draftEnc: ByteArray?, now: Long): Int = unsupported("saveDraft")
    override suspend fun markRead(sessionId: String, seq: Int): Unit = unsupported("markRead")
    override suspend fun recordFocusRelease(
        sessionId: String,
        kvTokens: Int,
        rehydrateTokens: Int,
        mode: String,
        epoch: Int,
        profile: String,
        now: Long,
    ): Unit = unsupported("recordFocusRelease")

    override suspend fun rename(sessionId: String, title: String, now: Long): Unit = unsupported("rename")
    override suspend fun markCompacted(sessionId: String, uptoSeq: Int, level: Int, now: Long): Unit =
        unsupported("markCompacted")

    override suspend fun archive(sessionId: String, now: Long): Unit = unsupported("archive")
    override suspend fun delete(sessionId: String): Unit = unsupported("delete")
    override suspend fun deleteByAgent(agentId: String): Int = unsupported("deleteByAgent")
    override suspend fun idsOfAgent(agentId: String): List<String> = unsupported("idsOfAgent")
    override suspend fun cloudCredentialIdOf(sessionId: String): String? =
        unsupported("cloudCredentialIdOf")
    override suspend fun bindCredential(
        sessionId: String,
        credId: String?,
        modelTarget: String,
        now: Long,
    ): Int = unsupported("bindCredential")
    override suspend fun setCloudConsent(sessionId: String, now: Long): Int =
        unsupported("setCloudConsent")
    override suspend fun oldest(limit: Int): List<String> = unsupported("oldest")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("UnsupportedChatSessionDao.$name 未被本轮测试覆盖")
}

internal open class UnsupportedChatMessageDao : ChatMessageDao {
    override suspend fun insertIgnoringConflict(msg: ChatMessageEntity): Long = unsupported("insertIgnoringConflict")
    override suspend fun insert(msg: ChatMessageEntity): Long = unsupported("insert")
    override suspend fun writeSnapshot(
        messageId: String,
        contentEnc: ByteArray,
        preview: String,
        now: Long,
    ): Int = unsupported("writeSnapshot")

    override suspend fun commit(
        messageId: String,
        status: String,
        contentEnc: ByteArray?,
        preview: String,
        payloadState: String,
        payloadFile: String?,
        payloadCodec: String?,
        payloadBytes: Int,
        promptTokens: Int,
        decodeTokens: Int,
        kvAfter: Int,
        redactionCount: Int,
        now: Long,
    ): Int = unsupported("commit")

    override suspend fun setStatus(messageId: String, status: String, now: Long): Int = unsupported("setStatus")
    override suspend fun bindTask(messageId: String, taskId: String?): Int = unsupported("bindTask")
    override fun observeTail(sessionId: String, limit: Int): Flow<List<ChatMessageEntity>> = unsupported("observeTail")
    override suspend fun pageBefore(sessionId: String, beforeSeq: Int, limit: Int): List<ChatMessageEntity> =
        unsupported("pageBefore")

    override suspend fun recentForRehydrate(sessionId: String, limit: Int): List<ChatMessageEntity> =
        unsupported("recentForRehydrate")

    override suspend fun fromSeqAscending(sessionId: String, fromSeq: Int, limit: Int): List<ChatMessageEntity> =
        unsupported("fromSeqAscending")

    override suspend fun latestBoundary(sessionId: String): ChatMessageEntity? = unsupported("latestBoundary")
    override suspend fun sweepStreaming(): Int = unsupported("sweepStreaming")
    override suspend fun sessionsWithStreaming(): List<String> = unsupported("sessionsWithStreaming")
    override suspend fun skillCallsInTurn(turnId: String): Int = unsupported("skillCallsInTurn")
    override suspend fun byId(id: String): ChatMessageEntity? = unsupported("byId")
    override suspend fun byClientRequestId(clientRequestId: String?): ChatMessageEntity? =
        unsupported("byClientRequestId")

    override suspend fun deleteBySession(sessionId: String): Int = unsupported("deleteBySession")
    override suspend fun externalPayloadsOf(sessionId: String): List<String> = unsupported("externalPayloadsOf")
    override suspend fun dropPayloadRefsBefore(sessionId: String, beforeSeq: Int): Int =
        unsupported("dropPayloadRefsBefore")

    override suspend fun markPayloadMissing(missing: List<String>): Int = unsupported("markPayloadMissing")
    override suspend fun unbindTask(taskId: String): Int = unsupported("unbindTask")
    override suspend fun countOf(sessionId: String): Int = unsupported("countOf")

    protected fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("UnsupportedChatMessageDao.$name 未被本轮测试覆盖")
}

/**
 * `agent_session` 的「没有实现」版本。
 *
 * 它存在的唯一理由：`ChatSessionStoreImpl` 的构造要一个 `SessionDao`，
 * 而绝大多数用例（sweep / 消息写入 / 会话读取）根本不碰它。
 * **返回空值会伪造「这里已被覆盖」的假象**，所以一律抛（与上面同一条纪律）。
 */
internal class UnsupportedSessionDao : SessionDao {
    override suspend fun upsert(session: SessionEntity): Unit = unsupported("upsert")
    override fun recent(limit: Int): Flow<List<SessionEntity>> = unsupported("recent")
    override suspend fun byId(id: String): SessionEntity? = unsupported("byId")
    override fun observe(id: String): Flow<SessionEntity?> = unsupported("observe")
    override suspend fun touch(id: String, updatedAt: Long, lastTaskId: String?, lastUrl: String?): Unit =
        unsupported("touch")

    override suspend fun setStatus(id: String, status: String, updatedAt: Long): Unit = unsupported("setStatus")
    override suspend fun delete(id: String): Unit = unsupported("delete")
    override suspend fun deleteArchivedBefore(before: Long): Int = unsupported("deleteArchivedBefore")
    override suspend fun unbindChatSession(sessionId: String): Int = unsupported("unbindChatSession")
    override suspend fun byChatSession(sessionId: String?): List<SessionEntity> = unsupported("byChatSession")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("UnsupportedSessionDao.$name 未被本轮测试覆盖")
}

internal class UnsupportedMemoryDao : MemoryDao {
    override suspend fun upsert(card: MemoryCardEntity): Unit = unsupported("upsert")
    override suspend fun byId(id: String): MemoryCardEntity? = unsupported("byId")
    override suspend fun byKey(key: String): MemoryCardEntity? = unsupported("byKey")
    override suspend fun byAgentKey(agentId: String?, key: String): MemoryCardEntity? = unsupported("byAgentKey")
    override suspend fun deleteByAgent(agentId: String): Int = unsupported("deleteByAgent")
    override suspend fun relevant(host: String, limit: Int): List<MemoryCardEntity> = unsupported("relevant")
    override fun recent(limit: Int): Flow<List<MemoryCardEntity>> = unsupported("recent")
    override suspend fun markUsed(id: String, at: Long): Unit = unsupported("markUsed")
    override suspend fun count(): Int = unsupported("count")
    override suspend fun pickEvictable(limit: Int): List<String> = unsupported("pickEvictable")
    override suspend fun delete(ids: List<String>): Unit = unsupported("delete")
    override suspend fun deleteExpired(now: Long): Int = unsupported("deleteExpired")
    override suspend fun deleteAll(): Unit = unsupported("deleteAll")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("UnsupportedMemoryDao.$name 未被本轮测试覆盖")
}

// ────────────────────────────────────────────────────────────────
// 行构造
// ────────────────────────────────────────────────────────────────

/** 一行 `agent_profile`。只填「谁、从哪个模板来」这两件本轮关心的事。 */
internal fun agentProfileEntity(
    id: String,
    templateId: String?,
    archived: Int = 0,
): AgentProfileEntity = AgentProfileEntity(
    id = id,
    agentId = "ag-" + id.takeLast(8),
    name = id,
    avatarUri = null,
    avatarKind = AgentProfileEntity.AVATAR_IDENTICON,
    description = "",
    personaCharterEnc = null,
    personaChararterPreview = "",
    charterHash = null,
    workingDirUri = null,
    modelId = com.ake.core.ports.DEFAULT_MODEL_ID,
    origin = if (templateId == null) {
        AgentProfileEntity.ORIGIN_USER_DIRECT
    } else {
        AgentProfileEntity.ORIGIN_TEMPLATE_BUILTIN
    },
    trustTier = "USER_AUTHORED",
    templateId = templateId,
    templateRevision = null,
    signKeyId = null,
    scanVerdict = "PASS",
    scanHitsJson = "",
    scannerVersion = 1,
    alwaysConfirmSkills = 0,
    maxEnabledSkills = 6,
    createdAt = 0L,
    updatedAt = 0L,
    lastUsedAt = null,
    archived = archived,
)

/** 一行 `template_registry`。 */
internal fun templateRegistryEntity(
    templateId: String,
    source: String = "BUILTIN",
    revision: Int = 1,
    installedAt: Long = 0L,
): TemplateRegistryEntity = TemplateRegistryEntity(
    templateId = templateId,
    revision = revision,
    name = templateId,
    avatarAsset = null,
    summary = "",
    tagsJson = "",
    charterHash = "",
    source = source,
    signKeyId = null,
    scanVerdict = "PASS",
    payloadFile = null,
    disclaimerEnc = null,
    installedAt = installedAt,
    updatedAt = installedAt,
)
