package com.ake.core.contract

import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.common.Ulid

/**
 * 持久化层与 Agent 层之间的**领域记录**（L2）。
 *
 * 铁律（数据库 §3.1）：这些类型里**不得出现任何 Room Entity**；Entity ↔ 本文件的映射
 * 只能在 `:data:persist` 内部完成（`toDomain()` / `fromDomain()`）。
 * 这条一旦破掉，Room 注解就会顺着依赖树流进 L4，将来换存储实现时无处可藏。
 *
 * 字段口径基本 1:1 镜像数据库 §3 的表，这样 `:data:persist` 的映射是纯粹的交通 diameter conversion，
 * 不会有「这条该不该放」的判断题 —— 判断题是最容易出错且最难 review 的一类代码。
 */

// ────────────────────────────────────────────────────────────── task

public data class TaskRecord(
    val id: TaskId,
    val sessionId: String,
    val seqInSession: Int = 0,
    val createdAt: Long = 0L,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** 清理作业的唯一排序键 */
    val lastActivityAt: Long = 0L,
    val status: String = TaskStatus.CREATED,
    val terminalReason: String? = null,
    val flavor: String? = null,
    val memoryTier: String? = null,
    val contextWindow: Int = 0,
    val modelId: String? = null,
    val backend: String? = null,
    val stepsDone: Int = 0,
    val prefillTokensTotal: Long = 0L,
    val decodeTokensTotal: Long = 0L,
    val elapsedMsTotal: Long = 0L,
    /** R1 加密区：用户原始意图 —— 全库最私密的一列 */
    val goalEnc: ByteArray? = null,
    val goalPreview: String? = null,
    val answerEnc: ByteArray? = null,
    val answerPreview: String? = null,
    val starred: Boolean = false,
    val retentionClass: String = RetentionClass.FULL,
    val redactionCount: Int = 0,
    val kvCachePeak: Int = 0,
    val schemaV: Int = 1,
)

public object RetentionClass {
    public const val FULL: String = "FULL"
    public const val DIGEST: String = "DIGEST"
}

/**
 * `advance()` 的补丁。
 *
 * ⚠ **语义必须明确为「累计终值」而不是「增量」**：这四个字段与 `LoopCheckpoint` 里的
 * `prefillTokensTotal / decodeTokensTotal / elapsedMsTotal` 同口径，
 * 便于任务被杀后直接从 checkpoint 恢复。 `null` = 本次不动这一列。
 *
 * 【待仲裁】霍承钧未在 EXPECTED_CORE_API.md 给出 `TaskBudgetPatch` 的形态，这里按
 * 「累计终值 + null 表示不动」实现。若你原意是增量 delta，告诉我，改的是
 * `:data:persist` 的 update SQL（把 `=` 换成 `= 列 + delta`），Port 签名不用动。
 */
public data class TaskBudgetPatch(
    val stepsDone: Int? = null,
    val prefillTokensTotal: Long? = null,
    val decodeTokensTotal: Long? = null,
    val elapsedMsTotal: Long? = null,
    val kvCachePeak: Int? = null,
    val lastActivityAt: Long? = null,
    val redactionCount: Int? = null,
)

// ────────────────────────────────────────────────────────────── checkpoint

/** 断点恢复快照（架构 §3.5）。**字段严格按 EXPECTED_CORE_API.md §4 原文**，一字未改。 */
public data class LoopCheckpoint(
    val taskId: TaskId,
    val updatedAt: Long,
    val statusSnapshot: String,
    val currentStepSeq: Int,
    val prefillTokensTotal: Long,
    val decodeTokensTotal: Long,
    val elapsedMsTotal: Long,
    val seenDigests: List<String>,
    val scratchpad: String?,
    val lastUrl: String?,
    val lastObservationId: String?,
    val lastKvCacheTokens: Int?,
    val compactionLastStep: Int?,
    val humanGatePending: Boolean,
    val humanGateJson: String?,
)

// ────────────────────────────────────────────────────────────── observation

/**
 * 观察摘要。镜像 `observation` 表。
 *
 * ★ 注意：**进入持久化的必须是这种显式投影过的领域对象，绝不许直接收下 `PocketDom`**
 * （数据库 §3.1）—— 那样「随手把整个 PocketDom 塞进库」在编译期就不可行。
 */
public data class ObservationSummary(
    val id: String,
    val taskId: TaskId,
    val stepId: StepId?,
    val seq: Int,
    val createdAt: Long,
    /** 小写化裸域名，明文 —— 域名策略要精确等值查询，加密会破坏这件事（数据库 §8.1） */
    val host: String,
    val urlPath: String,
    val titleRedacted: String?,
    val pageState: String,
    val warningFlags: Int,
    val signalFlags: Int,
    val candidateCount: Int,
    val recordRowCount: Int,
    val rawNodeCount: Int,
    val prunedNodeCount: Int,
    val serializedChars: Int,
    val pageDigest: String,
    /** §8.3(e) 脱敏自证 —— 回放时能区分「脱敏干的」还是「模型失败」 */
    val redactionCount: Int,
    val redactionTypes: List<String>,
    val payloadCodec: String?,
    val payloadFile: String?,
    val payloadRawBytes: Int,
    val payloadStoredBytes: Int,
    val payloadState: String,
) {
    public companion object {
        public fun newId(): String = Ulid.next()
    }
}

public object PayloadState {
    public const val EXTERNAL: String = "EXTERNAL"
    public const val MISSING: String = "MISSING"
    public const val DROPPED: String = "DROPPED"
}

// ────────────────────────────────────────────────────────────── tool invocation

public data class ToolInvocationSummary(
    val id: String,
    val taskId: TaskId,
    val stepId: StepId,
    val seqInStep: Int,
    val createdAt: Long,
    val durationMs: Long? = null,
    val toolId: String,
    val toolName: String,
    val origin: String,
    val riskClass: String,
    val mcpServerId: String? = null,
    val status: String = InvocationStatus.SUCCESS,
    val errorCode: String? = null,
    val errorMessageRedacted: String? = null,
    val attempts: Int = 1,
    /** R1 加密区 */
    val inputArgsEnc: ByteArray? = null,
    /** 明文：仅 key 列表，脱敏后仍可读结构 */
    val inputSchemaHint: String? = null,
    val outputTextEnc: ByteArray? = null,
    val outputTruncated: Boolean = false,
    val outputBytes: Int = 0,
    val confirmRequired: Boolean = false,
    val userDecision: String? = null,
)

public object InvocationStatus {
    public const val QUEUED: String = "QUEUED"
    public const val EXECUTING: String = "EXECUTING"
    public const val SUCCESS: String = "SUCCESS"
    public const val TIMEOUT: String = "TIMEOUT"
    public const val FAILED: String = "FAILED"
    public const val DENIED: String = "DENIED"
    public const val CANCELLED: String = "CANCELLED"
}

// ────────────────────────────────────────────────────────────── policy decision

/** **字段严格按 EXPECTED_CORE_API.md §4 原文**，一字未改。审计：所有策略决策必须落盘。 */
public data class PolicyDecisionRecord(
    val id: String,
    val taskId: TaskId,
    val stepId: StepId?,
    val createdAt: Long,
    /** ALLOW | REQUIRES_CONFIRM | DENIED */
    val decision: String,
    val severity: String?,
    val reasonKey: String?,
    val reasonArgs: List<String>,
    val actionKind: String,
    val targetHost: String?,
    val riskClass: String?,
)

public object PolicyDecision {
    public const val ALLOW: String = "ALLOW"
    public const val REQUIRES_CONFIRM: String = "REQUIRES_CONFIRM"
    public const val DENIED: String = "DENIED"
}

// ────────────────────────────────────────────────────────────── trace event

/**
 * §3.5 的 8 类终止保护每触发一次都要留下可诊断证据。
 *
 * 注意它是全库写放大最严重的表 —— 数据库 §6.3 明确「压力大时优先丢弃 trace_event」，
 * 所以这里的 `Message` 必须是**已脱敏**的（`messageRedacted` 名字里就写明了）。
 */
public data class TraceEventRecord(
    val id: String,
    val taskId: TaskId,
    val stepId: StepId?,
    /** 任务内单调递增，**不做 unique** —— 丢弃一行不应该报错 */
    val seq: Long,
    val createdAt: Long,
    val level: String,
    val kind: String,
    val messageRedacted: String,
    val kvJson: String?,
) {
    public companion object {
        public fun newId(): String = Ulid.next()
    }
}

public object TraceLevel {
    public const val DEBUG: String = "DEBUG"
    public const val INFO: String = "INFO"
    public const val WARN: String = "WARN"
    public const val ERROR: String = "ERROR"
}

public object TraceEventKind {
    public const val PERCEPTION_FAULT: String = "PERCEPTION_FAULT"
    public const val REPAIR: String = "REPAIR"
    public const val TRIPWIRE: String = "TRIPWIRE"
    public const val THERMAL_PAUSE: String = "THERMAL_PAUSE"
    public const val BATTERY_PAUSE: String = "BATTERY_PAUSE"
    public const val USER_STOP: String = "USER_STOP"
    public const val COMPACTION: String = "COMPACTION"
    public const val GUARD_TRIGGERED: String = "GUARD_TRIGGERED"
    public const val HUMAN_GATE: String = "HUMAN_GATE"
    public const val IO_ERROR: String = "IO_ERROR"
    public const val TRACE_DROPPED: String = "TRACE_DROPPED"
}

// ────────────────────────────────────────────────────────────── model asset

/**
 * 模型资源的下载状态机（数据库 F4/A2：中断 10 次后仍能完成下载，跨 App 重启）。
 *
 * 【待仲裁】EXPECTED_CORE_API.md 只给了 `ModelAssetStorePort.currentReady()` 的签名，
 * 未给出 `ModelAssetRecord` 的形态。这里按数据库 §2 的 model_asset 表补齐。
 */
public data class ModelAssetRecord(
    val id: String,
    val variant: String,
    val remoteUrl: String,
    val localPath: String?,
    val expectedBytes: Long,
    val bytesDownloaded: Long,
    val sha256: String,
    val state: String,
    val contextWindow: Int,
    val minRamGb: Int,
    val updatedAt: Long,
)

public object ModelAssetState {
    public const val NOT_PRESENT: String = "NOT_PRESENT"
    public const val DOWNLOADING: String = "DOWNLOADING"
    public const val PAUSED: String = "PAUSED"
    public const val VERIFYING: String = "VERIFYING"
    public const val READY: String = "READY"
    public const val CORRUPTED: String = "CORRUPTED"
}
