package com.ake.data.persist.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ake.data.persist.db.dao.AgentProfileDao
import com.ake.data.persist.db.dao.CapabilityGrantDao
import com.ake.data.persist.db.dao.ChatMessageDao
import com.ake.data.persist.db.dao.ChatSessionDao
import com.ake.data.persist.db.dao.CheckpointDao
import com.ake.data.persist.db.dao.CloudCredentialDao
import com.ake.data.persist.db.dao.ConfigDao
import com.ake.data.persist.db.dao.MemoryDao
import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.dao.PolicyDao
import com.ake.data.persist.db.dao.RouteDecisionDao
import com.ake.data.persist.db.dao.SessionDao
import com.ake.data.persist.db.dao.TaskDao
import com.ake.data.persist.db.dao.TemplateDao
import com.ake.data.persist.db.dao.ToolRegistryDao
import com.ake.data.persist.db.dao.TraceDao
import com.ake.data.persist.db.entity.AgentCapabilityGrantEntity
import com.ake.data.persist.db.entity.AgentProfileEntity
import com.ake.data.persist.db.entity.AppSettingsEntity
import com.ake.data.persist.db.entity.ChatMessageEntity
import com.ake.data.persist.db.entity.ChatSessionEntity
import com.ake.data.persist.db.entity.CloudCredentialEntity
import com.ake.data.persist.db.entity.McpServerEntity
import com.ake.data.persist.db.entity.MemoryCardEntity
import com.ake.data.persist.db.entity.ModelAssetEntity
import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import com.ake.data.persist.db.entity.SessionEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.TaskCheckpointEntity
import com.ake.data.persist.db.entity.TaskEntity
import com.ake.data.persist.db.entity.RouteDecisionEntity
import com.ake.data.persist.db.entity.TemplateRegistryEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.ToolRegistryEntity
import com.ake.data.persist.db.entity.TraceEventEntity

/**
 * Room 数据库。**19 张表** = v2 的 13 张 + v3 新增 6 张（聊天化 Agent）。
 *
 * `exportSchema = true` 是硬要求不是建议：没有 schema JSON，Room 的迁移校验
 * 与迁移测试都无法工作（§9.1）。
 *
 * ## 版本 2：新增 `mcp_server` / `app_settings`（`McpServerStorePort` / `SettingsStorePort`）
 *
 * 这两张表此前由 `:ui` 用 `org.json` 暂存在 `filesDir/mcp` 与 `filesDir/settings`。
 * 落地到 Room 是一次**加表**改动 ⇒ 版本 1 → 2，迁移见 `AkeMigrations.MIGRATION_1_2`。
 *
 * ⚠ 版本纪律（`AkeMigrations` 头部注释）：「版本号只在**已发布的 schema** 上递增」。
 * 本 schema 尚未发布给真人，严格说可以走破坏性迁移而不 +1。
 * **仍然选择 +1**：因为 `:ui` 那份过渡实现会先把数据写进文件，
 * 两条边切换期间**真机上可能存在已经落了 `filesDir/settings/ui_local.json` 的设备**
 * （本项目自己人就在跑 Debug 包）。给一条真实迁移比给自己人清一次数据便宜，
 * 而且它顺带把「迁移到底怎么写」从样板变成了一次实战验证。
 *
 * ## 版本 3：聊天化 Agent（`docs/06-chat-database.md`）
 *
 *  - 6 张新表：`agent_profile` / `chat_session` / `chat_message` /
 *    `agent_capability_grant` / `template_registry` / `route_decision`；
 *  - 5 处加列（`agent_session` / `task` ×3 / `task_checkpoint` ×2 / `policy_decision` ×2 /
 *    `memory_card`）；
 *  - **1 处表重建**：`memory_card` —— 人格隔离要求 `UNIQUE(agent_id, key)`，
 *    而 `ALTER TABLE ADD COLUMN` 不能带 UNIQUE（§6.4）⇒ 12 步重建。
 *    ★ 这是 v2 → v3 **唯一**有 `DROP TABLE` 的动作，也是唯一可能真的丢数据的地方。
 *
 * ⚠ 建表语句必须与 `data/schemas/.../3.json` **逐列一致**（`AkeMigrations.MIGRATION_2_3`
 * 头部的纪律 + 本轮新增的 `AkeMigrationSchemaTest` 把它变成了一条可跑的断言）。
 */
@Database(
    entities = [
        // ── v2 既有 13 张（顺序不动）
        SessionEntity::class,
        TaskEntity::class,
        StepEntity::class,
        ToolInvocationEntity::class,
        ObservationEntity::class,
        TraceEventEntity::class,
        PolicyDecisionEntity::class,
        TaskCheckpointEntity::class,
        MemoryCardEntity::class,
        ModelAssetEntity::class,
        ToolRegistryEntity::class,
        McpServerEntity::class,
        AppSettingsEntity::class,
        // ── ★ v3 新增 6 张
        AgentProfileEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        AgentCapabilityGrantEntity::class,
        TemplateRegistryEntity::class,
        RouteDecisionEntity::class,
        // ── ★ v4 新增 1 张
        CloudCredentialEntity::class,
    ],
    version = AkeDatabase.VERSION,
    exportSchema = true,
)
abstract class AkeDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun taskDao(): TaskDao
    abstract fun traceDao(): TraceDao
    abstract fun policyDao(): PolicyDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun memoryDao(): MemoryDao
    abstract fun modelAssetDao(): ModelAssetDao
    abstract fun toolRegistryDao(): ToolRegistryDao

    /** 配置类两张表（`McpServerStorePort` / `SettingsStorePort` 的落点）。 */
    abstract fun configDao(): ConfigDao

    // ── v3 聊天
    abstract fun agentProfileDao(): AgentProfileDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun capabilityGrantDao(): CapabilityGrantDao
    abstract fun templateDao(): TemplateDao
    abstract fun routeDecisionDao(): RouteDecisionDao

    /** ★ v4：云端凭证（A5 裁决：密文落 Room，不落独立文件）。 */
    abstract fun cloudCredentialDao(): CloudCredentialDao

    companion object {
        const val DB_FILE_NAME: String = "ake_agent.db"

        /**
         * §9.1：首个交付给真人的 schema 就是 1。不用 0，也不用 100 起步。
         *
         *  - 2 = 新增 `mcp_server` / `app_settings` 两张配置表（见本文件类注释）。
         *  - 3 = 聊天化 Agent：6 张新表 + 5 处加列 + `memory_card` 重建。
         *  - 4 = 云端模型推出（docs/09）：`cloud_credential` 新表 +
         *        `route_decision` 加 13 列 + `chat_session` 加 3 列 + 3 个索引。
         *        ★ **全 Additive**，无 DROP / RENAME / 重建 / 回填。
         */
        const val VERSION: Int = 4

        /** §4.4 配额硬限。DB 含 -wal/-shm。★ §8.4 重估后**不变**（v3 稳态 ≈34 MB，仍有 ≈7× 余量）。 */
        const val QUOTA_HARD_BYTES: Long = 256L * 1024 * 1024
        const val QUOTA_SOFT_BYTES: Long = 192L * 1024 * 1024

        /** trace/ 目录配额（外置快照）。 */
        const val TRACE_QUOTA_SOFT_BYTES: Long = 192L * 1024 * 1024
        const val TRACE_QUOTA_HARD_BYTES: Long = 384L * 1024 * 1024

        /** ★ v3 新增两条子配额（§8.4）：`filesDir/chat/`（消息 payload + envelope 共用一根）。 */
        const val CHAT_PAYLOAD_SOFT_BYTES: Long = 64L * 1024 * 1024
        const val CHAT_PAYLOAD_HARD_BYTES: Long = 128L * 1024 * 1024

        /** ★ `filesDir/agents/`（人格头像等资产）。 */
        const val AGENT_ASSET_SOFT_BYTES: Long = 32L * 1024 * 1024
        const val AGENT_ASSET_HARD_BYTES: Long = 64L * 1024 * 1024
    }
}
