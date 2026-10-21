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

@Entity(
    tableName = "agent_session",      // 不叫 session：避开 SQLite session 扩展的同名词
    indices = [
        Index(name = "idx_agent_session_updated_at", value = ["updated_at"]),
        Index(name = "idx_agent_session_status_updated", value = ["status", "updated_at"]),
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
)

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
)
