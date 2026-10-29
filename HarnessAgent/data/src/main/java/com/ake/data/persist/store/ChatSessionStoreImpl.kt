package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.ports.AppendMessage
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.ChatSessionSummary
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.ake.core.ports.LoopMode
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.data.persist.db.dao.AgentProfileDao
import com.ake.data.persist.db.dao.ChatMessageDao
import com.ake.data.persist.db.dao.ChatSessionDao
import com.ake.data.persist.db.dao.SessionDao
import com.ake.data.persist.db.dao.TaskDao
import com.ake.data.persist.db.entity.ChatMessageEntity
import com.ake.data.persist.db.entity.ChatSessionEntity
import com.ake.data.persist.payload.PayloadStore
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.write.ChatMessageWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `ChatSessionStorePort` 的实现（§3.7 / §4.4 / §8.2）。
 *
 * ## ★ 三条泳道在本类的落点（§4.4）
 *
 * | 泳道 | 方法 | 失败后果 |
 * |---|---|---|
 * | ① 同步（不可丢） | [append] / [beginAssistant] / [commitAssistant] / [recordFocusRelease] | **必须让用户看见**（UI 报错，不开始生成） |
 * | ② 非挂起覆盖（可丢） | `writer.offerSnapshot()`（**不在本 Port 上**，见下） | 丢最后一次快照之后的增量，终态提交兜底 |
 * | ③ 后台（可丢） | [markRead] / [saveDraft] | 无感 |
 *
 * ★ ② 泳道的方法**刻意不出现在本 Port 上**：流式落盘是 `:data` 的内部实现细节，
 * `:chat` 与 `:ui` 都不该看见它。`:chat` 只把 delta 交给 `ChatRunnerPort` 的 Flow，
 * 由 `:data` 侧订阅 `AssistantDelta` 分支调 `offerSnapshot()` —— **Port 一行不改**（§4.4）。
 *
 * ## ★ `seq` 的分配必须和消息 INSERT 在同一个事务里
 *
 * `seq = chat_session.message_count`，随后 `message_count + 1`。
 * 本工程所有写都走单写者线程（v2 §6.2），但**事务仍然要写** ——
 * 靠「恰好串行」来保正确性是靠运气（§3.7）。
 *
 * ## ★ 删除会话的完整枚举（§8.2）
 *
 * 顺序不能反：
 *  ① 先把派生的 task / agent_session 解绑（SET NULL）—— **它们不在删除范围内**
 *  ② 再删消息行  ③ 最后删会话行  ④ **事务外**删外置文件
 *
 * ❌ 照抄 `purgeTask(taskId)` 的形状会顺手把 `task` 也删了 ⇒ 「任务轨迹凭空消失」，
 *    而那条数据是用户没要求删的。
 */
public class ChatSessionStoreImpl internal constructor(
    private val sessions: ChatSessionDao,
    private val messages: ChatMessageDao,
    private val tasks: TaskDao,
    private val agentSessions: SessionDao,
    /**
     * 只用于 [ensureForNewTurn] 的 id 归一化（短名 → `agent_profile.id`）。
     *
     * 本类**不**读人格内容，也不该读 —— 人格的读写归 `AgentProfileStorePort`。
     * 这里需要的是一个纯查主键的动作，而 `:chat` 看不到 DAO、`ChatLoop` 又必须拿到
     * 与会话行一致的 id 空间，所以只能落在 `:data` 这一层。
     */
    private val profiles: AgentProfileDao,
    private val writer: ChatMessageWriter,
    private val gate: PersistenceRedactionGate,
    private val clock: Clock,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    /** `filesDir/chat/` —— 消息外置 payload + envelope 共用一个根。 */
    private val payloads: PayloadStore? = null,
) : ChatSessionStorePort {

    // ─────────────────────────────── 读取

    override fun sessions(agentId: String?, includeArchived: Boolean): Flow<List<ChatSessionSummary>> {
        val src = if (agentId == null) sessions.observeRecent() else sessions.observeByAgent(agentId)
        return src.map { rows -> rows.filter { includeArchived || it.archived == 0 }.map { it.toSummary() } }
    }

    override fun messages(sessionId: String, limit: Int): Flow<List<ChatMessageView>> =
        messages.observeTail(sessionId, limit).map { rows -> rows.sortedBy { it.seq }.map { it.toView(gate) } }

    // ─────────────────────────────── 写入（泳道 ①）

    override suspend fun ensureForNewTurn(agentId: String, titleHint: String?): Outcome<String> {
        val now = clock.nowMillis()
        // ★ 归一化：`chat_session.agent_id` 的外键是 `REFERENCES agent_profile(id)`，
        //   而 UI 路由 / MCP 入站手里拿的是**对外短名** `ag-xxxxxxxxxx`。
        //   不归一化的后果不是"名字显示不对"，而是 INSERT 直接被外键拒掉
        //   （`PRAGMA foreign_keys = ON`）⇒ 第一条消息发不出去，用户看到的是"点了没反应"。
        //   `profiles.byAgentId` 两个 id 空间都接受（见该 DAO 的注释），所以这里
        //   传短名或传主键都能收敛到同一个 `agent_profile.id`。
        val profileId = runCatching { profiles.byAgentId(agentId)?.id }
            .onFailure { AkeLog.w(TAG, "resolve agent failed agent=$agentId: ${it.message}") }
            .getOrNull()
        if (profileId == null) {
            // 明确的失败方向：宁可让 UI 报"这个 Agent 已经不在了"，
            // 也不要靠外键抛一个用户看不懂的 constraint 错误。
            return Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_agent_not_found"))
        }
        val id = com.ake.core.common.Ulid.next(now)
        val row = ChatSessionEntity(
            id = id,
            agentId = profileId,
            title = titleHint?.take(TITLE_MAX) ?: DEFAULT_TITLE,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = now,
            messageCount = 0,
            previewRedacted = null,
            unreadCount = 0,
            lastReadSeq = 0,
            draftEnc = null,
            draftUpdatedAt = null,
            compactedUptoSeq = 0,
            compactLevel = 0,
            contextProfile = ChatSessionEntity.PROFILE_STANDARD,
            lastMode = ChatSessionEntity.MODE_REPLY,
            modeEpoch = 0,
            lastKvCacheTokens = 0,
            lastRehydrateTokens = 0,
            archived = 0,
            // ★ v4：新建会话的目标恒为「本机」，且**必须走 DialogTargetCodec 的词汇**
            //   （"LOCAL"）—— 不许在库里出现第二套编码（docs/09 §2.4）。
            modelTarget = DialogTargetCodec.encode(DialogTarget.Device),
            cloudCredentialId = null,
            cloudConsentAt = null,
        )
        return runCatching { sessions.insert(row) }
            .fold(
                onSuccess = { Outcome.Ok(id) },
                onFailure = { t ->
                    AkeLog.w(TAG, "ensureForNewTurn failed agent=$agentId: ${t.message}")
                    Outcome.Err(AppError(AppError.CODE_IO, "err_chat_session_create", t))
                },
            )
    }

    /**
     * ★ 用户消息落盘（不可丢泳道）。
     *
     * 幂等：`INSERT OR IGNORE` 命中 `client_request_id` 的 UNIQUE 冲突 ⇒ 直接复用已有行，
     * 重试不产生第二条消息（§3.4 的 U4 收益之一）。
     */
    override suspend fun append(msg: AppendMessage): Outcome<Unit> = writeWithin(msg.toEntity(gate, clock.nowMillis())) {
        // 用户消息不算未读（说话的人就是用户自己）
        unreadDelta = 0
    }

    /** ★ 助手占位行（流式开始前建行 ⇒ 进程被杀时「已中断」有行可渲染，§4.3）。 */
    override suspend fun beginAssistant(
        sessionId: String,
        turnId: String,
        messageId: String,
    ): Outcome<Unit> {
        val now = clock.nowMillis()
        val row = ChatMessageEntity(
            id = messageId,
            sessionId = sessionId,
            seq = 0,                       // 由 appendWithin 覆盖
            turnId = turnId,
            role = MessageRole.ASSISTANT.name,
            kind = MessageKind.TEXT.name,
            status = MessageStatus.STREAMING.name,
            clientRequestId = null,
            callId = null,
            contentEnc = null,
            contentPreview = "",
            payloadState = ChatMessageEntity.PAYLOAD_INLINE,
            payloadFile = null,
            payloadCodec = null,
            payloadBytes = 0,
            taskId = null,
            skillRef = null,
            modelNamespace = MODEL_NAMESPACE_LOCAL,
            promptTokens = 0,
            decodeTokens = 0,
            kvCacheTokensAfter = 0,
            compactionLevel = 0,
            redactionCount = 0,
            createdAt = now,
            updatedAt = now,
        )
        return writeWithin(row) {
            assistant = true
            unreadDelta = 0
        }
    }

    /** ★ 终态提交（不可丢泳道）。**必须在丢槽之后**写，否则快照会覆盖回旧文本（§4.5 纪律 3）。 */
    override suspend fun commitAssistant(
        messageId: String,
        status: MessageStatus,
        text: String,
        promptTokens: Int,
        decodeTokens: Int,
        kvCacheTokensAfter: Int,
        redactionCount: Int,
    ): Outcome<Unit> {
        // ★ 顺序：先清槽（丢掉最后一次未落盘的快照），再提交终态。
        writer.discardSlot(messageId)
        val scrubbed = gate.scrub(text)
        val sealed = gate.sealField(scrubbed.text, messageId.toByteArray())
        val now = clock.nowMillis()
        return runCatching {
            messages.commit(
                messageId = messageId,
                status = status.name,
                contentEnc = sealed.blob,
                preview = scrubbed.text.take(ChatMessageWriter.Throttle.PREVIEW_CHARS),
                payloadState = ChatMessageEntity.PAYLOAD_INLINE,
                payloadFile = null,
                payloadCodec = null,
                payloadBytes = 0,
                promptTokens = promptTokens,
                decodeTokens = decodeTokens,
                kvAfter = kvCacheTokensAfter,
                redactionCount = redactionCount + scrubbed.redactionCount,
                now = now,
            )
        }.fold(
            onSuccess = { rows ->
                if (rows == 0) {
                    AkeLog.w(TAG, "commitAssistant: no STREAMING row for $messageId (already settled)")
                }
                Outcome.Ok(Unit)
            },
            onFailure = { t ->
                AkeLog.w(TAG, "commitAssistant failed $messageId: ${t.message}")
                Outcome.Err(AppError(AppError.CODE_IO, "err_chat_message_commit", t))
            },
        )
    }

    /**
     * 冷启收敛：遗留 `STREAMING` → `FAILED`（成因见 [ChatMessageDao.sweepStreaming] 的注释）。
     *
     * ## ★ 为什么这里要打一条带 `PROCESS_KILLED` 的 WARN
     *
     * `chat_message` 没有 `failure_reason` 列（理由见 DAO 侧注释），所以「这批 FAILED
     * 是进程被杀留下的、不是模型出错」这条信息**不在库里的任何一列**。
     * 它是排查「用户消息为什么没回」时唯一能区分两者的东西，不能连日志也不留：
     *
     *  - 库侧：`SELECT ... WHERE status = 'FAILED'` ⇒ 缺陷**能被发现**（本轮修复的目的）；
     *  - 日志侧：`reason=PROCESS_KILLED` ⇒ 发现之后**能定性**（不是模型坏了）。
     *
     * 只在真的收敛了行时打（稳态下 sweep 恒为 0 行），冷启路径上没有噪音代价。
     *
     * 失败仍返回 0 而不是抛：冷启不该被一次 sweep 失败堵死 —— 但必须留痕，
     * 否则「一条都没收敛」与「根本没执行」在调用方眼里完全一样。
     */
    override suspend fun sweepStreaming(): Int = runCatching { messages.sweepStreaming() }
        .onSuccess { rows ->
            if (rows > 0) {
                AkeLog.w(TAG, "sweepStreaming: $rows 条遗留 STREAMING 收敛为 FAILED (reason=PROCESS_KILLED)")
            }
        }
        .onFailure { AkeLog.w(TAG, "sweepStreaming failed: ${it.message}") }
        .getOrDefault(0)

    /** ★ 焦点释放时的复水基准。**同步写**（§4.6 / §9-U11），失败要进 Outcome 让用户看见。 */
    override suspend fun recordFocusRelease(
        sessionId: String,
        kvTokens: Int,
        rehydrateTokens: Int,
        mode: LoopMode,
        epoch: Int,
        profile: String,
    ): Outcome<Unit> = runCatching {
        sessions.recordFocusRelease(
            sessionId = sessionId,
            kvTokens = kvTokens,
            rehydrateTokens = rehydrateTokens,
            mode = mode.name,
            epoch = epoch,
            profile = profile,
            now = clock.nowMillis(),
        )
    }.fold(
        onSuccess = { Outcome.Ok(Unit) },
        onFailure = { t -> Outcome.Err(AppError(AppError.CODE_IO, "err_chat_focus_release", t)) },
    )

    // ─────────────────────────────── 写入（泳道 ③，可丢）

    override suspend fun markCompacted(sessionId: String, uptoSeq: Int, level: Int) {
        runCatching { sessions.markCompacted(sessionId, uptoSeq, level, clock.nowMillis()) }
            .onFailure { AkeLog.w(TAG, "markCompacted failed: ${it.message}") }
    }

    override suspend fun markRead(sessionId: String, seq: Int) {
        runCatching { sessions.markRead(sessionId, seq) }
            .onFailure { AkeLog.w(TAG, "markRead failed: ${it.message}") }
    }

    override suspend fun saveDraft(sessionId: String, draft: String?): Outcome<Unit> {
        val enc = draft?.let { runCatching { gate.sealField(it, sessionId.toByteArray()).blob }.getOrNull() }
        runCatching { sessions.saveDraft(sessionId, enc, clock.nowMillis()) }
            .onFailure { AkeLog.w(TAG, "saveDraft failed: ${it.message}") }
        return Outcome.Ok(Unit)
    }

    override suspend fun archive(sessionId: String) {
        runCatching { sessions.archive(sessionId, clock.nowMillis()) }
            .onFailure { AkeLog.w(TAG, "archive failed: ${it.message}") }
    }

    // ─────────────────────────────── 删除（完整枚举）

    override suspend fun deleteSession(sessionId: String): Outcome<Int> {
        val files = runCatching { messages.externalPayloadsOf(sessionId) }.getOrDefault(emptyList())
        return runCatching {
            var deleted = 0
            transaction {
                tasks.unbindChatSession(sessionId)              // ① 解绑（不删）
                agentSessions.unbindChatSession(sessionId)      // ① 解绑（不删）
                deleted = messages.deleteBySession(sessionId)   // ②
                sessions.delete(sessionId)                      // ③
            }
            deleted
        }.fold(
            onSuccess = { deleted ->
                // ④ 事务外删文件：先没了 DB 指针，再删本体（v2 §4.5 纪律 1）
                if (payloads != null && files.isNotEmpty()) {
                    payloads.purgeTask(sessionId)
                }
                Outcome.Ok(deleted)
            },
            onFailure = { t ->
                AkeLog.w(TAG, "deleteSession failed $sessionId: ${t.message}")
                Outcome.Err(AppError(AppError.CODE_IO, "err_chat_session_delete", t))
            },
        )
    }

    // ─────────────────────────────── 内部：事务组合

    private class Touch(var unreadDelta: Int = 0, var assistant: Boolean = false)

    /**
     * ★ `appendWithin`：分配 seq + 插消息 + 推进会话冗余列，**同一事务**。
     *
     * 它不在 DAO 里，因为 Room 的 DAO 之间不能互相注入；放在这里还有一个额外收益：
     * 「并发两次 append 会不会撞 seq」这条性质可以用内存 fake 在 JVM 单测里覆盖。
     */
    private suspend fun writeWithin(row: ChatMessageEntity, touch: Touch.() -> Unit): Outcome<Unit> {
        val t = Touch().apply(touch)
        val now = clock.nowMillis()
        return runCatching {
            transaction {
                val session = sessions.byId(row.sessionId)
                    ?: error("session ${row.sessionId} not found")
                val inserted = messages.insertIgnoringConflict(row.copy(seq = session.messageCount))
                if (inserted != -1L) {
                    if (t.assistant) {
                        sessions.touchForAssistant(session.id, now, session.messageCount + 1)
                    } else {
                        sessions.touchForNewMessage(
                            sessionId = session.id,
                            now = now,
                            preview = row.contentPreview,
                            nextSeq = session.messageCount + 1,
                            unreadDelta = t.unreadDelta,
                        )
                    }
                }
            }
        }.fold(
            onSuccess = { Outcome.Ok(Unit) },
            onFailure = { thr ->
                AkeLog.w(TAG, "chat message write failed: ${thr.message}")
                Outcome.Err(AppError(AppError.CODE_IO, "err_chat_message_write", thr))
            },
        )
    }

    public companion object {
        public const val TAG: String = "ChatSessionStore"

        /** ★ 上游 §9.2：会话标题自动生成**不做**（需要一次独立生成 ⇒ 与 F3 冲突）。 */
        public const val DEFAULT_TITLE: String = "新对话"
        public const val TITLE_MAX: Int = 64

        public const val MODEL_NAMESPACE_LOCAL: String = "local"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 映射（★ 全部是**显式投影**：Entity 不出 `:data`，Port 只看见投影后的形状）
// ─────────────────────────────────────────────────────────────────────────────

internal fun ChatSessionEntity.toSummary(): ChatSessionSummary = ChatSessionSummary(
    id = id,
    agentId = agentId,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessageAt = lastMessageAt,
    messageCount = messageCount,
    previewRedacted = previewRedacted,
    unreadCount = unreadCount,
    lastReadSeq = lastReadSeq,
    compactedUptoSeq = compactedUptoSeq,
    lastMode = runCatching { LoopMode.valueOf(lastMode) }.getOrDefault(LoopMode.REPLY),
    modeEpoch = modeEpoch,
    archived = archived != 0,
)

internal fun ChatMessageEntity.toView(gate: PersistenceRedactionGate): ChatMessageView {
    val text = contentEnc?.let { gate.openField(it, id.toByteArray()) } ?: contentPreview
    return ChatMessageView(
        id = id,
        sessionId = sessionId,
        seq = seq,
        turnId = turnId,
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.SYSTEM_NOTE),
        kind = runCatching { MessageKind.valueOf(kind) }.getOrDefault(MessageKind.TEXT),
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE),
        textRedacted = text ?: "",
        taskId = taskId,
        skillRef = skillRef?.let { com.ake.core.ports.CapabilityRef.decode(it) },
        callId = callId,
        createdAt = createdAt,
        tokensEstimate = promptTokens + decodeTokens,
        compactionLevel = compactionLevel,
    )
}

internal fun AppendMessage.toEntity(gate: PersistenceRedactionGate, now: Long): ChatMessageEntity {
    val scrubbed = gate.scrub(text)
    val sealed = gate.sealField(scrubbed.text, id.toByteArray())
    return ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        seq = 0,                      // 由 appendWithin 覆盖
        turnId = turnId,
        role = role.name,
        kind = kind.name,
        status = status.name,
        clientRequestId = clientRequestId,
        callId = callId,
        contentEnc = sealed.blob,
        contentPreview = scrubbed.text.take(ChatMessageWriter.Throttle.PREVIEW_CHARS),
        payloadState = ChatMessageEntity.PAYLOAD_INLINE,
        payloadFile = null,
        payloadCodec = null,
        payloadBytes = 0,
        taskId = taskId,
        skillRef = skillRef?.encode(),
        modelNamespace = ChatSessionStoreImpl.MODEL_NAMESPACE_LOCAL,
        promptTokens = 0,
        decodeTokens = 0,
        kvCacheTokensAfter = 0,
        compactionLevel = 0,
        redactionCount = scrubbed.redactionCount,
        createdAt = createdAt.takeIf { it > 0 } ?: now,
        updatedAt = now,
    )
}
