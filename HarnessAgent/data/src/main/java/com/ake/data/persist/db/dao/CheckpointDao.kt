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
     * 只问「有没有」，不给数据。
     *
     * 给对账用的（有无断点决定孤儿收敛成「可恢复的 PAUSED」还是「终态 ABORTED」）。
     * 刻意不用 [byTask]：那个会把 R1 的 `scratchpadEnc` 密文一起拉进内存，
     * 而它在这个路径上一个字节都用不上。
     */
    @Query("SELECT COUNT(*) FROM task_checkpoint WHERE task_id = :taskId")
    suspend fun countOfTask(taskId: String): Int

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
