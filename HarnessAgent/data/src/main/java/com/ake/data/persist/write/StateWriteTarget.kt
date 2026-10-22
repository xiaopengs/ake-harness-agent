package com.ake.data.persist.write

import androidx.room.withTransaction
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.dao.CheckpointDao
import com.ake.data.persist.db.dao.PolicyDao
import com.ake.data.persist.db.dao.TaskDao

/**
 * `TaskStateWriter` 的落盘目标（2026-10，为可测性引入的**唯一一处**接缝）。
 *
 * ## 为什么要有它
 *
 * `TaskStateWriter` 原先直接在 `commitOnce()` 里写 `db.withTransaction { db.taskDao()... }`。
 * 这在生产里没有任何问题，但它让这条泳道的三个关键性质 ——
 * **不丢**、**屏障语义**、**失败重试到上限后交棒对账** ——
 * 都只能靠真机或仪器化测试验证，而仪器化测试在本项目里**完全不存在**。
 *
 * `AkeDatabase` 是 Room 的抽象类：JVM 单测构造不出它的实例，
 * 而不该为了可测性去 mock 一个 `RoomDatabase`（那要连带伪造事务与 DAO 的全部语义）。
 *
 * 于是把「事务 + 三张表的入口」收成一个窄接口。它只有四个成员，
 * 且**全部来自既有生产代码的那一行** —— 没有为可测性引入任何新的生产分支。
 *
 * ## 为什么是「事务 + DAO」而不是只抽「apply 一批 Op」
 *
 * 只抽 `apply(batch)` 也能测，但「这批 Op 是不是在同一个事务里」这个性质就丢了 ——
 * 而它恰恰是 `commitOnce` 注释里那条最关键的正确性要求
 * （`task_checkpoint.task_id → task.id` 的外键要求 task 行先写）。
 * 保留 `inTransaction` 让单测能断言「一个事务、多张表、一次 COMMIT」。
 */
public interface StateWriteTarget {

    /** 一个事务。必须串行执行（§6.2 单写者），实现方负责。 */
    public suspend fun inTransaction(block: suspend () -> Unit)

    public val taskDao: TaskDao
    public val checkpointDao: CheckpointDao
    public val policyDao: PolicyDao
}

/**
 * 生产实现：包 `AkeDatabase`。
 *
 * 与 [TaskStateWriter] 里原来那行 `db.withTransaction { ... }` 逐字等价。
 */
public class RoomStateWriteTarget(private val db: AkeDatabase) : StateWriteTarget {

    override suspend fun inTransaction(block: suspend () -> Unit) {
        db.withTransaction(block)
    }

    override val taskDao: TaskDao get() = db.taskDao()
    override val checkpointDao: CheckpointDao get() = db.checkpointDao()
    override val policyDao: PolicyDao get() = db.policyDao()
}
