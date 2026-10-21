package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 目录类三张表：memory_card / model_asset / tool_registry。
 *
 * 与轨迹表的区别：**行数少、写入少、读取多**，所以索引可以比轨迹表宽松。
 */

/**
 * 长期记忆。数据库 §10.2 明确不做向量检索（只做简单键值 + 最近 N 条）⇒ MVP 不建 FTS 表。
 * `source_task_id` 用 SET_NULL：记忆生命周期长于任务，任务删了记忆还在，
 * 否则「记住偏好」毫无意义。
 */
@Entity(
    tableName = "memory_card",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_task_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_mem_key", value = ["key"], unique = true),
        Index(name = "idx_mem_kind_updated", value = ["kind", "updated_at"]),
        Index(name = "idx_mem_host_used", value = ["scope_host", "last_used_at"]),
        Index(name = "idx_mem_expires", value = ["expires_at"]),
    ],
)
data class MemoryCardEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "scope_host") val scopeHost: String?,

    /** 明文：UI 列表要直接显示 */
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "content_enc") val contentEnc: ByteArray?,
    @ColumnInfo(name = "content_preview") val contentPreview: String,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
    @ColumnInfo(name = "use_count") val useCount: Int,
    @ColumnInfo(name = "pinned") val pinned: Int,
    @ColumnInfo(name = "source_task_id") val sourceTaskId: String?,
    /** null = 永不过期 */
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
)

/**
 * ★ 数据库 §12.3 标记「上游缺失，A2 依赖」。
 * A2 验收项「中断 10 次后仍能完成下载」在**跨 App 重启**的前提下
 * 必须有持久化的 `bytes_downloaded` 与 `state` —— 架构 §6.4 只写了逻辑，没给落点。
 */
@Entity(
    tableName = "model_asset",
    indices = [
        Index(name = "idx_model_id_variant", value = ["model_id", "variant"], unique = true),
        Index(name = "idx_model_state", value = ["state"]),
    ],
)
data class ModelAssetEntity(
    /** ULID。业务键是 (model_id, variant) */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "variant") val variant: String,
    @ColumnInfo(name = "remote_url") val remoteUrl: String,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "expected_bytes") val expectedBytes: Long,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "context_window") val contextWindow: Int,
    @ColumnInfo(name = "min_ram_gb") val minRamGb: Int,
    @ColumnInfo(name = "capabilities_json") val capabilitiesJson: String,

    @ColumnInfo(name = "state") val state: String,
    /** ★ Range 续传的生命线 */
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long,
    @ColumnInfo(name = "download_started_at") val downloadStartedAt: Long?,
    @ColumnInfo(name = "download_finished_at") val downloadFinishedAt: Long?,
    /** *.part 名字，用于孤儿清理 */
    @ColumnInfo(name = "temp_file") val tempFile: String?,

    @ColumnInfo(name = "verify_attempts") val verifyAttempts: Int,
    @ColumnInfo(name = "verify_last_result") val verifyLastResult: String?,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long?,
    @ColumnInfo(name = "actual_bytes") val actualBytes: Long?,
    @ColumnInfo(name = "actual_sha256") val actualSha256: String?,

    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "last_error_at") val lastErrorAt: Long?,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
    @ColumnInfo(name = "load_count") val loadCount: Int,
    @ColumnInfo(name = "last_backend") val lastBackend: String?,
    @ColumnInfo(name = "last_load_ms") val lastLoadMs: Long?,
)

/**
 * ★ 数据库 §12.3 标记「上游缺失，§8.5 依赖」。
 *
 * **为什么逐工具授权落 Room 而不是 DataStore**（数据库 F7）：
 * 「所有 MCP 工具的 Always Allow 授权必须能在设置里按工具粒度撤销」。
 * Proto DataStore 是**整体重写文件**，200 个工具里撤销 1 个要重写整个文件，
 * 在低端机上有可感知延迟；Room 单列 UPDATE 是毫秒级，且天然可审计。
 */
@Entity(
    tableName = "tool_registry",
    indices = [
        Index(name = "idx_toolreg_origin_server", value = ["origin", "server_id"]),
        Index(name = "idx_toolreg_enabled", value = ["enabled"]),
        Index(name = "idx_toolreg_last_seen", value = ["last_seen_at"]),
    ],
)
data class ToolRegistryEntity(
    @PrimaryKey @ColumnInfo(name = "tool_id") val toolId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "json_schema") val jsonSchema: String,
    /** discover 时比对，变了要重新注入 SYSTEM 段 */
    @ColumnInfo(name = "schema_hash") val schemaHash: String,

    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "server_id") val serverId: String?,
    @ColumnInfo(name = "declared_risk_class") val declaredRiskClass: String,
    /** PolicyPort 可覆写（§7.1） */
    @ColumnInfo(name = "risk_class_override") val riskClassOverride: String?,

    /** §7.2 硬限制：同时启用 ≤ 12 个 */
    @ColumnInfo(name = "enabled") val enabled: Int,
    @ColumnInfo(name = "always_allow") val alwaysAllow: Int,
    @ColumnInfo(name = "granted_at") val grantedAt: Long?,

    @ColumnInfo(name = "discovered_at") val discoveredAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "missing_since") val missingSince: Long?,
)
