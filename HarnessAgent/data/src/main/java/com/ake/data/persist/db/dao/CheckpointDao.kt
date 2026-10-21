package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.TaskCheckpointEntity

/**
 * 断点表（1:1 挂 task，CASCADE）。
 *
 * 用 `REPLACE` 而不是 `@Upsert`：这里每次写入都是**完整快照**（没有「只更新某几列」的场景），
 * REPLACE 在语义上就是我们要的，且不依赖 SQLite 3.24+ 的 UPSERT 语法（数据库 §9.4）。
 */
@Dao
interface CheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(cp: TaskCheckpointEntity)

    @Query("SELECT * FROM task_checkpoint WHERE task_id = :taskId")
    suspend fun byTask(taskId: String): TaskCheckpointEntity?

    /**
     * 可恢复清单：断点存在 **且** 任务仍处于中间态。
     *
     * 「进程被杀后下次启动改写为 CRASH_RECOVERABLE」的判据就是这两条同时成立 ——
     * 只查其一都会误判（终态任务的断点会在 `keepCheckpoint` 时故意留着给用户手动重试）。
     */
    @Query(
        """
        SELECT task_id FROM task_checkpoint
         WHERE task_id IN (
               SELECT id FROM task
                WHERE status IN ('RUNNING','PAUSED','WAITING_HUMAN')
             )
         ORDER BY updated_at DESC
        """,
    )
    suspend fun findResumable(): List<String>

    @Query("DELETE FROM task_checkpoint WHERE task_id = :taskId")
    suspend fun delete(taskId: String)

    @Query("DELETE FROM task_checkpoint WHERE updated_at < :before")
    suspend fun deleteStale(before: Long): Int
}
