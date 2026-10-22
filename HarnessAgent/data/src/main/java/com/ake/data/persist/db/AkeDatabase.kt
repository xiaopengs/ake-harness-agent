package com.ake.data.persist.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ake.data.persist.db.dao.CheckpointDao
import com.ake.data.persist.db.dao.ConfigDao
import com.ake.data.persist.db.dao.MemoryDao
import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.dao.PolicyDao
import com.ake.data.persist.db.dao.SessionDao
import com.ake.data.persist.db.dao.TaskDao
import com.ake.data.persist.db.dao.ToolRegistryDao
import com.ake.data.persist.db.dao.TraceDao
import com.ake.data.persist.db.entity.AppSettingsEntity
import com.ake.data.persist.db.entity.McpServerEntity
import com.ake.data.persist.db.entity.MemoryCardEntity
import com.ake.data.persist.db.entity.ModelAssetEntity
import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import com.ake.data.persist.db.entity.SessionEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.TaskCheckpointEntity
import com.ake.data.persist.db.entity.TaskEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.ToolRegistryEntity
import com.ake.data.persist.db.entity.TraceEventEntity

/**
 * Room 数据库。**13 张表**（数据库 §3.5 + 2026-10 新增的配置两张）。
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
 */
@Database(
    entities = [
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

    companion object {
        const val DB_FILE_NAME: String = "ake_agent.db"

        /**
         * §9.1：首个交付给真人的 schema 就是 1。不用 0，也不用 100 起步。
         *
         * 2 = 新增 `mcp_server` / `app_settings` 两张配置表（见本文件类注释）。
         */
        const val VERSION: Int = 2

        /** §4.4 配额硬限。DB 含 -wal/-shm。 */
        const val QUOTA_HARD_BYTES: Long = 256L * 1024 * 1024
        const val QUOTA_SOFT_BYTES: Long = 192L * 1024 * 1024

        /** trace/ 目录配额（外置快照）。 */
        const val TRACE_QUOTA_SOFT_BYTES: Long = 192L * 1024 * 1024
        const val TRACE_QUOTA_HARD_BYTES: Long = 384L * 1024 * 1024
    }
}
