package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.PlanRevisionEntity
import kotlinx.coroutines.flow.Flow

/**
 * `plan_revision` 的读写。
 *
 * ## 为什么没有 `@Update`
 *
 * 这张表的写只有两种：**追加一版**（[insert]）和**改状态**（[confirm] / [supersedeDrafts]）。
 * 给一个通用 `@Update` 就等于允许"把某一版计划的步骤改掉而 revision 不变"——
 * 那会让「用户确认的是哪一版」这个前提失效。
 */
@Dao
interface PlanDao {

    /**
     * 追加一版。
     *
     * `IGNORE` 而不是 `REPLACE`：并发下两个协程可能算出同一个 `revision`，
     * `REPLACE` 会静默覆盖先写进去的那一版（用户的确认记录就没了）。
     * 返回 `-1` 表示这一版已存在，由调用方重算 revision 后重试。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PlanRevisionEntity): Long

    @Query("SELECT COALESCE(MAX(revision), 0) FROM plan_revision WHERE task_id = :taskId")
    suspend fun maxRevision(taskId: String): Int

    @Query("SELECT * FROM plan_revision WHERE task_id = :taskId ORDER BY revision DESC LIMIT 1")
    suspend fun latest(taskId: String): PlanRevisionEntity?

    @Query("SELECT * FROM plan_revision WHERE task_id = :taskId ORDER BY revision DESC LIMIT 1")
    fun observeLatest(taskId: String): Flow<PlanRevisionEntity?>

    /**
     * CAS：只有「这一版还在草稿态」时才置为已确认。
     *
     * @return 1 = 抢到了；0 = 这一版已经被确认过 / 已被取代 / 根本不存在。
     *         调用方**必须**把 0 当成"用户在另一个界面已经处理过"而不是错误。
     */
    @Query(
        "UPDATE plan_revision SET status = 'CONFIRMED' " +
            "WHERE task_id = :taskId AND revision = :revision AND status = 'DRAFT'",
    )
    suspend fun confirm(taskId: String, revision: Int): Int

    /** 生成新一版时把旧的草稿作废（旧版本本身保留，只是不再可确认）。 */
    @Query("UPDATE plan_revision SET status = 'SUPERSEDED' WHERE task_id = :taskId AND status = 'DRAFT'")
    suspend fun supersedeDrafts(taskId: String): Int

    /** 任务被删时连带清掉计划。task 表没有外键级联到这张表（复合主键不便建 FK）。 */
    @Query("DELETE FROM plan_revision WHERE task_id = :taskId")
    suspend fun deleteOfTask(taskId: String): Int
}
