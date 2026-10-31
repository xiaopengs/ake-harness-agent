package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.DefaultTargetEntity

/**
 * 全局默认目标的 DAO（v5）。
 *
 * ## ★ 为什么写入用 `REPLACE` 而读取容忍"没有这一行"
 *
 *  · 写入：单行表 + 覆盖语义 ⇒ `REPLACE` 正好是"有则覆盖、无则插入"，
 *    不需要先 SELECT 再决定 INSERT/UPDATE（那两步在并发下还得靠事务兜）。
 *  · 读取：**没有这一行是合法状态**（= 从未设置过），不是异常。
 *    ⇒ `modelTargetOf()` 返回 `String?`，**不**在无行时补一行。
 *    「读一次不该有写副作用」—— 与 `SettingsStoreImpl.values()` 同一条纪律，
 *    否则「打开设置页」这个动作会凭空产生一条记录，
 *    「用户到底设没设过」就再也问不出来了。
 *
 * ## ★ 刻意没有 `Flow`
 *
 * 本表的值只在设置页被改，改动后 UI 自己知道。加一个 `Flow` 就要顺带回答
 * 「谁来 invalidate」，而目前没有第二个写入方 ⇒ 不建这套机制，等真有需要再加。
 */
@Dao
interface DefaultTargetDao {

    /** 覆盖式写入（单行表）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DefaultTargetEntity)

    /** @return 落库文本；`null` ⇒ 从未设置过（**不是** `LOCAL`）。 */
    @Query("SELECT model_target FROM default_dialog_target WHERE id = :rowId")
    suspend fun modelTargetOf(rowId: Int): String?

    /** 自检 / 测试用：表里有没有这一行（区分「从未设置」与「显式设为端侧」）。 */
    @Query("SELECT COUNT(*) FROM default_dialog_target WHERE id = :rowId")
    suspend fun rowCount(rowId: Int): Int

    /** ★ 一键清除：物理删行，回到「未初始化」而不是「变成默认值」。 */
    @Query("DELETE FROM default_dialog_target")
    suspend fun deleteAll(): Int
}
