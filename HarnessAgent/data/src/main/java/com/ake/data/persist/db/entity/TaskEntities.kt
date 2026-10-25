package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话 / 任务 / 断点三张表的 Entity。
 *
 * 四条公共约定（数据库 §3.3）：
 *  - **主键一律 ULID TEXT**：字典序即时间序 ⇒ 主键 B-tree 是**追加写**，不产生随机插入
 *    （这是 SQLite 写入性能最大的单条杠杆）；且可以在进入队列**之前**就生成 id，
 *    异步批量落盘天然成立。
 *  - **时间一律 epoch millis `Long`**：不用 `@TypeConverter` 到 `Instant`（每次读写多一次函数调用，
 *    而这是最热的写路径）。
 *  - **枚举一律存 TEXT**：牺牲编译期校验，换 `adb shell sqlite3` 直接看得懂 ——
 *    端侧 Agent 项目的 debug 效率优先于此。
 *  - **密文统一 `| ver:1B | iv:12B | ciphertext | tag:16B |`**（不对 `_iv` 单独开列）。
 */

/**
 * 任务执行容器（**不是** `chat_session` —— 分表裁决见上游 §1.2 / 数据库 §2.6）。
 *
 * ## ★ `chat_session_id` 的删除语义是 `SET NULL`（主理人 U3 裁决）🔴
 *
 * 上游 ER 画的是 CASCADE。若照它实现：删一个聊天会话 ⇒ `agent_session` 连带删
 * ⇒ `task` 连带删（v2 §2.3）⇒ `step/observation/trace_event` 连带删。
 * 于是「用户不想看这段对话」变成「销毁了一批他没要求删的执行证据」。
 * **改判 SET NULL**：追溯链可断（溯源可以断），但被追溯的对象不能悬空着消失。
 */
@Entity(
    tableName = "agent_session",      // 不叫 session：避开 SQLite session 扩展的同名词
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_session_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_agent_session_updated_at", value = ["updated_at"]),
        Index(name = "idx_agent_session_status_updated", value = ["status", "updated_at"]),
        // ★ 消 Room 的 MISSING_INDEX_ON_FOREIGN_KEY_CHILD warning + 「这个会话派生过哪些任务」
        Index(name = "idx_agent_session_chat", value = ["chat_session_id"]),
    ],
)
data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "last_task_id") val lastTaskId: String?,
    /** 已 sanitize + 去参，断点恢复用 */
    @ColumnInfo(name = "last_url") val lastUrl: String?,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "memory_tier") val memoryTier: String,
    @ColumnInfo(name = "task_count") val taskCount: Int,
    /**
     * ★ 可空：AppFunctions / MCP Server 外部触发的任务可以完全不属于任何对话会话
     * （上游 §1.2 论证 3 的场景，在 §6 里是主流而非边缘）。
     * SET NULL：删会话不删任务容器（§8.2 B3）。
     */
    @ColumnInfo(name = "chat_session_id") val chatSessionId: String? = null,
)

@Entity(
    tableName = "task",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_task_session_seq", value = ["session_id", "seq_in_session"], unique = true),
        Index(name = "idx_task_finished", value = ["finished_at"]),
        Index(name = "idx_task_status_finished", value = ["status", "finished_at"]),
        Index(name = "idx_task_last_activity", value = ["last_activity_at"]),
        // ★ 「这句话派生了哪个任务」的反查（删消息时用它解绑，§8.2 B1）
        Index(name = "idx_task_chat_message", value = ["chat_message_id"]),
    ],
)
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "seq_in_session") val seqInSession: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    /** 清理作业的唯一排序键 */
    @ColumnInfo(name = "last_activity_at") val lastActivityAt: Long,

    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "terminal_reason") val terminalReason: String?,

    @ColumnInfo(name = "flavor") val flavor: String?,
    @ColumnInfo(name = "memory_tier") val memoryTier: String?,
    @ColumnInfo(name = "context_window") val contextWindow: Int,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "backend") val backend: String?,

    // ── 预算累计 ★（§3.4 的持久化载体，见数据库 F1）
    @ColumnInfo(name = "steps_done") val stepsDone: Int,
    @ColumnInfo(name = "prefill_tokens_total") val prefillTokensTotal: Long,
    @ColumnInfo(name = "decode_tokens_total") val decodeTokensTotal: Long,
    @ColumnInfo(name = "elapsed_ms_total") val elapsedMsTotal: Long,

    // ── ⚠ 加密区（R1，§8.3）
    @ColumnInfo(name = "goal_enc") val goalEnc: ByteArray?,
    @ColumnInfo(name = "goal_preview") val goalPreview: String?,
    @ColumnInfo(name = "answer_enc") val answerEnc: ByteArray?,
    @ColumnInfo(name = "answer_preview") val answerPreview: String?,

    @ColumnInfo(name = "starred") val starred: Int,
    @ColumnInfo(name = "retention_class") val retentionClass: String,
    @ColumnInfo(name = "redaction_count") val redactionCount: Int,
    @ColumnInfo(name = "kv_cache_peak") val kvCachePeak: Int,
    @ColumnInfo(name = "schema_v") val schemaV: Int,

    // ── ★ v3：追溯到「哪句话派生了这个任务」（上游 §7.1）
    /** SET NULL：任务轨迹的生命周期由 v2 §4.4 管，不由会话管（§8.2 B1）。 */
    @ColumnInfo(name = "chat_message_id") val chatMessageId: String? = null,
    /**
     * ★ UI | CHAT | APP_FUNCTION | MCP_SERVER。历史行一律 'UI'
     * （NOT NULL + DEFAULT 成对出现，SQLite 的 ADD COLUMN 要求如此）。
     */
    @ColumnInfo(name = "trigger_source", defaultValue = "'UI'") val triggerSource: String = TRIGGER_UI,
    /** 由哪个技能派生（如 `builtin.browser_task`）。 */
    @ColumnInfo(name = "skill_ref") val skillRef: String? = null,
) {
    companion object {
        const val TRIGGER_UI: String = "UI"
        const val TRIGGER_CHAT: String = "CHAT"
        const val TRIGGER_APP_FUNCTION: String = "APP_FUNCTION"
        const val TRIGGER_MCP_SERVER: String = "MCP_SERVER"
    }
}

/**
 * §3.5「LoopState 落盘」的唯一持久化载体（数据库 F1：上游架构里它根本不存在）。
 *
 * 没有它，热节流/低电恢复会让**预算被重置** —— 一次暂停等于白送一张预算卡。
 */
@Entity(
    tableName = "task_checkpoint",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(name = "idx_cp_updated", value = ["updated_at"])],
)
data class TaskCheckpointEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "status_snapshot") val statusSnapshot: String,

    // §3.4 预算累计
    @ColumnInfo(name = "current_step_seq") val currentStepSeq: Int,
    @ColumnInfo(name = "prefill_tokens_total") val prefillTokensTotal: Long,
    @ColumnInfo(name = "decode_tokens_total") val decodeTokensTotal: Long,
    @ColumnInfo(name = "elapsed_ms_total") val elapsedMsTotal: Long,

    @ColumnInfo(name = "seen_digest_json") val seenDigestJson: String?,
    /** ⚠ R1：模型自己的笔记，可能含用户数据 */
    @ColumnInfo(name = "scratchpad_enc") val scratchpadEnc: ByteArray?,
    @ColumnInfo(name = "last_url") val lastUrl: String?,
    @ColumnInfo(name = "last_observation_id") val lastObservationId: String?,
    @ColumnInfo(name = "last_kv_cache_tokens") val lastKvCacheTokens: Int?,
    @ColumnInfo(name = "compaction_last_step") val compactionLastStep: Int?,

    // HumanGate 挂起态：UI 冷启动后还能弹出那个对话框
    @ColumnInfo(name = "human_gate_pending") val humanGatePending: Int,
    @ColumnInfo(name = "human_gate_json") val humanGateJson: String?,

    // ── ★ v3：CheckpointEnvelope 外置（上游 §6.4）【预留接口：MVP 只建列，不生成文件】
    /**
     * `filesDir/chat/envelope/<taskId>-v<n>.env.json`。
     * ★ 文件名**必须含 taskId**（§9-U13）：孤儿文件 GC 要「文件在但 DB 无引用 ⇒ 删文件」，
     *   若文件名不含归属键，GC 就得读每个文件的 manifest 才能反查 ⇒ 一次 IO 放大。命名即索引。
     */
    @ColumnInfo(name = "envelope_file") val envelopeFile: String? = null,
    @ColumnInfo(name = "envelope_schema_v") val envelopeSchemaV: Int? = null,
)
