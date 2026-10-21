package com.ake.data.persist.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 迁移路径（数据库 §9）。
 *
 * ## 版本纪律
 *  - **首个可演进版本 = 1**。不要用 0，也不要 100 起步 ——
 *    Room 的迁移是按整数路径找的，人为抬高起点只会带来困惑。
 *  - **版本号只在「已发布的 schema」上递增。** 这个 schema 没发布给真人之前，
 *    改了就走破坏性迁移，不必 +1。
 *  - **900–999 保留给紧急通道**，正常使用不得占用。
 *
 * ## MVP 的实际状态
 *
 * `version = 1` 就是首版，因此 [ALL] 目前是空数组。**这里放的是「第二个版本要怎么写」的样板**，
 * 目的是让下一个人不必重新查一遍写法（写迁移的坑几乎全在样板里）。
 *
 * 开发期 schema 天天变，正式做法是 build flag：
 * `if (BuildConfig.DEV_DESTRUCTIVE_MIGRATION) builder.fallbackToDestructiveMigration()`，
 * 且 **release 构建绝不允许出现这个 flag**。
 */
object AkeMigrations {

    val ALL: Array<Migration> = emptyArray()

    // ──────────────────────────────────────────── 样板

    /**
     * 1 → 2 的样板。真实改动时照抄这个形状。
     *
     * 三条来自数据库的硬要求：
     *  ① **必须幂等**：迁移可能被重跑（升级中断、备份恢复）。
     *     建表/建索引一律 `IF NOT EXISTS`；删列先查 `pragma_table_info`。
     *  ② **不要依赖 `SupportSQLiteDatabase`**：本文全部走 `SQLiteConnection`，
     *     这样向 Room 3 迁移时不用改签名（§9.5）。
     *  ③ **大表加列用默认值而不是 UPDATE**：`ALTER TABLE ... ADD COLUMN x INTEGER NOT NULL DEFAULT 0`
     *     是 O(1)，而 `ADD COLUMN` + `UPDATE` 全表是 O(n)，在 trace_event 上会是几秒的卡顿。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE task ADD COLUMN budget_profile TEXT",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_step_action_key ON step (task_id, action_key)",
            )
        }
    }

    /** 2 → 3 的样板：带数据回填。 */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "UPDATE task SET budget_profile = 'DEFAULT' WHERE budget_profile IS NULL",
            )
        }
    }
}
