package com.ake.data.persist.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * 5 → 6：计划版本（V1.0 M2）。
 *
 * ## 形状：全 Additive，一条建表 + 一条索引
 *
 * 没有 ALTER、没有 DROP/RENAME、没有回填 ⇒ **结构上不可能动到既有表的任何一行**。
 *
 * ## 为什么 `plan_revision` 不建外键指向 `task`
 *
 *  · `task` 表的主键是单列 ULID，复合主键表可以建 FK，但 Room 的 `@Entity` 一旦声明
 *    `foreignKeys`，`ON DELETE CASCADE` 就会在**每次删任务**时扫这张表 —— 这是好事；
 *  · 真正的理由是**迁移顺序**：v3 的 `chat_message` → `task` 已经证明过，
 *    给存量表加 FK 要走重建（12 步），而新表加 FK 只能在「task 已存在且被引用列有
 *    唯一约束」时成立。当前 `task.id` 是主键，满足；但**一旦将来有人改 task 主键**，
 *    这条 FK 会挡住迁移。
 *  ⇒ 选择"应用层删"：`PlanDao.deleteOfTask` 在任务删除时显式调用。
 *    代价是可能留孤儿行（几 KB），收益是迁移路径永远简单。
 *
 * ## 索引只有一条
 *
 * `idx_plan_task_created (task_id, created_at)` 服务两件事：查最新一版（`ORDER BY revision DESC`
 * 走主键）、以及将来按时间清理旧版本。**不为 `status` 建索引** —— 那个列的基数只有 3，
 * 建了也不会被选中，纯属给写入加开销。
 */
internal object PlanRevisionMigration5To6 : Migration(5, 6) {

    val CREATE_TABLES: List<String> = listOf(
        """
            CREATE TABLE IF NOT EXISTS `plan_revision` (
                `task_id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `goal` TEXT NOT NULL,
                `steps_text` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `schema_v` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`task_id`, `revision`)
            )
        """.trimIndent(),
    )

    val ADD_COLUMNS: List<String> = emptyList()

    val CREATE_INDEXES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_plan_task_created` ON `plan_revision` (`task_id`, `created_at`)",
    )

    val STATEMENTS: List<String> = CREATE_TABLES + ADD_COLUMNS + CREATE_INDEXES

    override fun migrate(connection: SQLiteConnection) {
        for (sql in CREATE_TABLES) connection.execSQL(sql)
        for (sql in CREATE_INDEXES) connection.execSQL(sql)
        assertNoForeignKeyViolation(connection)
    }

    private fun assertNoForeignKeyViolation(connection: SQLiteConnection) {
        val violations = ArrayList<String>()
        val statement: SQLiteStatement = connection.prepare("PRAGMA foreign_key_check")
        try {
            while (statement.step()) {
                val table = runCatching { statement.getText(0) }.getOrDefault("?")
                val rowId = runCatching { statement.getText(1) }.getOrDefault("?")
                violations += "$table(rowid=$rowId)"
            }
        } finally {
            statement.close()
        }
        require(violations.isEmpty()) {
            "MIGRATION_5_6: foreign_key_check 报 ${violations.size} 处违反：${violations.joinToString("; ")}"
        }
    }
}
