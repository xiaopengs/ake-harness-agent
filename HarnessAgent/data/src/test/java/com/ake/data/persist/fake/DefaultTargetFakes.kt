package com.ake.data.persist.fake

import com.ake.data.persist.db.dao.DefaultTargetDao
import com.ake.data.persist.db.entity.DefaultTargetEntity

/**
 * `DefaultTargetDao` 的内存替身。
 *
 * ★ [upsert] 用 `REPLACE` 语义（覆盖整行）—— 与真实 DAO 一致；
 *   [modelTargetOf] 在**没有这一行**时返回 `null`，**不**补行
 *   （「读一次不该有写副作用」）。这两条正是 store 层不变量 ② 的载体，
 *   替身必须如实复现，否则测试会绿而真机不绿。
 */
internal class FakeDefaultTargetDao : DefaultTargetDao {

    val rows = LinkedHashMap<Int, DefaultTargetEntity>()

    /** ★ 抛异常开关：验「写失败表现为 Err，不抛出去」。 */
    var throwOnWrite: Boolean = false

    override suspend fun upsert(row: DefaultTargetEntity) {
        if (throwOnWrite) error("boom")
        rows[row.id] = row
    }

    override suspend fun modelTargetOf(rowId: Int): String? = rows[rowId]?.modelTarget

    override suspend fun rowCount(rowId: Int): Int = if (rowId in rows) 1 else 0

    override suspend fun deleteAll(): Int {
        val n = rows.size
        rows.clear()
        return n
    }
}
