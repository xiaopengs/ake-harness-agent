package com.ake.data.persist.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ake.data.persist.db.dao.CheckpointDao
import com.ake.data.persist.db.dao.MemoryDao
import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.dao.PolicyDao
import com.ake.data.persist.db.dao.SessionDao
import com.ake.data.persist.db.dao.TaskDao
import com.ake.data.persist.db.dao.ToolRegistryDao
import com.ake.data.persist.db.dao.TraceDao
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
 * Room 数据库。**11 张表**（数据库 §3.5）。
 *
 * `exportSchema = true` 是硬要求不是建议：没有 schema JSON，Room 的迁移校验
 * 与迁移测试都无法工作（§9.1）。
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

    companion object {
        const val DB_FILE_NAME: String = "ake_agent.db"

        /** §9.1：首个交付给真人的 schema 就是 1。不用 0，也不用 100 起步。 */
        const val VERSION: Int = 1

        /** §4.4 配额硬限。DB 含 -wal/-shm。 */
        const val QUOTA_HARD_BYTES: Long = 256L * 1024 * 1024
        const val QUOTA_SOFT_BYTES: Long = 192L * 1024 * 1024

        /** trace/ 目录配额（外置快照）。 */
        const val TRACE_QUOTA_SOFT_BYTES: Long = 192L * 1024 * 1024
        const val TRACE_QUOTA_HARD_BYTES: Long = 384L * 1024 * 1024
    }
}
