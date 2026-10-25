package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 轨迹类五张表：step / tool_invocation / observation / trace_event / policy_decision。
 *
 * 它们是全库写放大最严重的几张表（每步 10.6 行），所以：
 *  - 索引**一律显式命名**（Room 自动命名在迁移与 EXPLAIN 断言里都难对齐）；
 *  - 每张表的索引数量都按数据库 §10.3 裁过 —— 每多一个索引，每行写入就多一次 B-tree 维护。
 */

@Entity(
    tableName = "step",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_step_task_seq", value = ["task_id", "seq"], unique = true),
        Index(name = "idx_step_task_created", value = ["task_id", "created_at"]),
    ],
)
data class StepEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    /** 任务内序号，与 ULID 时间序一致 */
    @ColumnInfo(name = "seq") val seq: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,

    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "action_kind") val actionKind: String?,
    /** §4.5 确定性压缩行："#3 click([12]销量)→ok [dom+18]" */
    @ColumnInfo(name = "action_summary") val actionSummary: String,
    /** 工具名+归一化参数+目标 —— §3.5 完全重复检测 */
    @ColumnInfo(name = "action_key") val actionKey: String?,
    /**
     * ★ **对数据库 §3.4 的一处增补**（详见回传第 4 条）。
     *
     * `core:ports/TraceStorePort.load(taskId): List<StepRecord>` 要求能**还原出 `StepAction`**，
     * 而 `StepRecord.action` 是非空的密封接口 —— 光靠 `action_kind` + `action_summary`（压缩行）
     * 无法无损还原（Click(12) 与 Type(12,"x") 的压缩行可能只差几个字符）。
     *
     * 断点恢复要用这段历史做上下文摘要（§3.5 最低实现），所以这里存一份**紧凑 JSON 原文**。
     * 只增列、不改既有列，schema v1 尚未发布 ⇒ 不需要迁移。
     */
    @ColumnInfo(name = "action_json") val actionJson: String?,
    @ColumnInfo(name = "model_raw_redacted") val modelRawRedacted: String?,
    @ColumnInfo(name = "repair_attempts") val repairAttempts: Int,

    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "prefill_tokens") val prefillTokens: Int,
    @ColumnInfo(name = "decode_tokens") val decodeTokens: Int,
    @ColumnInfo(name = "ttft_ms") val ttftMs: Int?,
    @ColumnInfo(name = "decode_tps") val decodeTps: Double?,
    @ColumnInfo(name = "kv_cache_tokens_after") val kvCacheTokensAfter: Int?,
    @ColumnInfo(name = "prompt_tokens_estimate") val promptTokensEstimate: Int,
    @ColumnInfo(name = "budget_remaining_pct") val budgetRemainingPct: Int?,

    @ColumnInfo(name = "observation_id") val observationId: String?,
    @ColumnInfo(name = "page_changed") val pageChanged: String?,
    @ColumnInfo(name = "page_digest") val pageDigest: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_hint") val errorHint: String?,
)

@Entity(
    tableName = "tool_invocation",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = StepEntity::class,
            parentColumns = ["id"],
            childColumns = ["step_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_toolinv_task_created", value = ["task_id", "created_at"]),
        Index(name = "idx_toolinv_step", value = ["step_id"]),
    ],
)
data class ToolInvocationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "step_id") val stepId: String,
    @ColumnInfo(name = "seq_in_step") val seqInStep: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,

    @ColumnInfo(name = "tool_id") val toolId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "risk_class") val riskClass: String,
    @ColumnInfo(name = "mcp_server_id") val mcpServerId: String?,

    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_message_redacted") val errorMessageRedacted: String?,
    @ColumnInfo(name = "attempts") val attempts: Int,

    // ⚠ R1 加密区
    @ColumnInfo(name = "input_args_enc") val inputArgsEnc: ByteArray?,
    /** 明文：仅 key 列表，脱敏后仍可读结构 */
    @ColumnInfo(name = "input_schema_hint") val inputSchemaHint: String?,
    @ColumnInfo(name = "output_text_enc") val outputTextEnc: ByteArray?,
    @ColumnInfo(name = "output_truncated") val outputTruncated: Int,
    @ColumnInfo(name = "output_bytes") val outputBytes: Int,

    @ColumnInfo(name = "confirm_required") val confirmRequired: Int,
    @ColumnInfo(name = "user_decision") val userDecision: String?,
)

/**
 * ★ 数据库 §12.3 标记「上游缺失，A11 依赖」的一张表。
 * 没有它，「每步可查看当时的 PocketDom 快照」这条验收项无法完成。
 */
@Entity(
    tableName = "observation",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        // ★ step_id 可空 + SET_NULL：感知在 observe 时就超时是合法状态（§3.5 Fault.Perception），
        //   此时「有 observation 无 step」，不要建模成必有。
        ForeignKey(
            entity = StepEntity::class,
            parentColumns = ["id"],
            childColumns = ["step_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_obs_task_seq", value = ["task_id", "seq"], unique = true),
        Index(name = "idx_obs_host_created", value = ["host", "created_at"]),
        Index(name = "idx_obs_step", value = ["step_id"]),
    ],
)
data class ObservationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "step_id") val stepId: String?,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,

    // host 明文（域名策略要精确等值查询，加密会破坏这件事 §8.1）；path/query 加密
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "url_path_enc") val urlPathEnc: ByteArray?,
    @ColumnInfo(name = "title_redacted") val titleRedacted: String?,

    @ColumnInfo(name = "page_state") val pageState: String,
    @ColumnInfo(name = "warning_flags") val warningFlags: Int,
    @ColumnInfo(name = "signal_flags") val signalFlags: Int,
    @ColumnInfo(name = "candidate_count") val candidateCount: Int,
    @ColumnInfo(name = "record_row_count") val recordRowCount: Int,
    @ColumnInfo(name = "raw_node_count") val rawNodeCount: Int,
    @ColumnInfo(name = "pruned_node_count") val prunedNodeCount: Int,
    @ColumnInfo(name = "serialized_chars") val serializedChars: Int,
    /** §3.5 NoProgress 的判定依据 */
    @ColumnInfo(name = "page_digest") val pageDigest: String,

    // 脱敏自证（§8.3(e)）
    @ColumnInfo(name = "redaction_count") val redactionCount: Int,
    @ColumnInfo(name = "redaction_types") val redactionTypes: String?,

    @ColumnInfo(name = "payload_codec") val payloadCodec: String?,
    @ColumnInfo(name = "payload_file") val payloadFile: String?,
    @ColumnInfo(name = "payload_raw_bytes") val payloadRawBytes: Int,
    @ColumnInfo(name = "payload_stored_bytes") val payloadStoredBytes: Int,
    @ColumnInfo(name = "payload_state") val payloadState: String,
)

/** §3.5 的 8 类终止保护每触发一次都要留下可诊断证据。也是全库写放大最严重的表。 */
@Entity(
    tableName = "trace_event",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = StepEntity::class,
            parentColumns = ["id"],
            childColumns = ["step_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_event_task_seq", value = ["task_id", "seq"]),
        Index(name = "idx_event_level_created", value = ["level", "created_at"]),
        Index(name = "idx_event_kind_created", value = ["kind", "created_at"]),
    ],
)
data class TraceEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "step_id") val stepId: String?,
    /** 任务内单调递增，**刻意不做 unique**：丢弃一行不应该报错 */
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "level") val level: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "message_redacted") val messageRedacted: String,
    @ColumnInfo(name = "kv_json") val kvJson: String?,
)

/** 护栏审计。所有策略决策必须落盘 —— 用户问「它为什么点了这个」时这是唯一的答案来源。 */
@Entity(
    tableName = "policy_decision",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = StepEntity::class,
            parentColumns = ["id"],
            childColumns = ["step_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_pol_task_created", value = ["task_id", "created_at"]),
        Index(name = "idx_pol_decision_created", value = ["decision", "created_at"]),
        Index(name = "idx_pol_reason_created", value = ["reason_key", "created_at"]),
        // ★ v3：聊天场景的可审计性（上游 §8 S9）。
        //   §10.4 的建议是**只建 chat_session_id**（V3 用例要按会话查），
        //   `agent_id` 的查询需求出现时再建 —— 这条表是既有热写表（每步 1–2 行），
        //   不给它加没必要的新索引。
        Index(name = "idx_pol_chat_session", value = ["chat_session_id"]),
    ],
)
data class PolicyDecisionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "step_id") val stepId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "decision") val decision: String,
    @ColumnInfo(name = "severity") val severity: String?,
    @ColumnInfo(name = "reason_key") val reasonKey: String?,
    @ColumnInfo(name = "reason_args_json") val reasonArgsJson: String?,
    @ColumnInfo(name = "action_kind") val actionKind: String,
    @ColumnInfo(name = "target_host") val targetHost: String?,
    @ColumnInfo(name = "risk_class") val riskClass: String?,

    // ── ★ v3：聊天场景的审计增强（上游 §8 S9）
    /** 由哪个人格发起的（技能调用场景才有）。 */
    @ColumnInfo(name = "agent_id") val agentId: String?,
    /** 由哪个会话发起的。 */
    @ColumnInfo(name = "chat_session_id") val chatSessionId: String?,
)
