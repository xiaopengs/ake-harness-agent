package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 护栏审计表。
 *
 * **所有策略决策必须落盘**：当用户问「它为什么点了这个」时，`policy_decision` 是唯一的答案来源。
 * 写入走三级 Channel 的 P0 队列（不可丢），但**落盘失败不阻断判定** ——
 * 审计写失败不该拖慢主循环。
 */
@Dao
interface PolicyDao {

    @Insert
    suspend fun insert(items: List<PolicyDecisionEntity>)

    /** 安全回归核心查询：某任务被拒过的所有决策（命中 idx_pol_task_created）。 */
    @Query("SELECT * FROM policy_decision WHERE task_id = :taskId ORDER BY created_at ASC")
    suspend fun ofTask(taskId: String): List<PolicyDecisionEntity>

    @Query("SELECT * FROM policy_decision WHERE task_id = :taskId ORDER BY created_at ASC")
    fun observeTask(taskId: String): Flow<List<PolicyDecisionEntity>>

    /** 「这段时间里一共拒了多少次」—— 用于安全回归测试断言（命中 idx_pol_decision_created）。 */
    @Query("SELECT COUNT(*) FROM policy_decision WHERE decision = :decision AND created_at >= :since")
    suspend fun countDecisionSince(decision: String, since: Long): Int

    @Query(
        """
        SELECT * FROM policy_decision
         WHERE decision = 'DENIED' AND created_at >= :since
         ORDER BY created_at DESC LIMIT :limit
        """,
    )
    suspend fun recentDenials(since: Long, limit: Int = 100): List<PolicyDecisionEntity>

    /** §4.5 清理：DIGEST 级保留 —— 审计行比轨迹行更值钱，只在整任务删除时才清。 */
    @Query("DELETE FROM policy_decision WHERE task_id = :taskId")
    suspend fun deleteOfTask(taskId: String)

    @Query("DELETE FROM policy_decision WHERE task_id NOT IN (SELECT id FROM task)")
    suspend fun deleteOrphans(): Int
}
