package com.ake.data.persist.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * 4 → 5：全局默认目标（`docs/10` §6.2-A 的遗留缺口，本轮补齐）。
 *
 * ## ★★ 形状：与 `CloudMigration3To4` 完全一致 —— **全 Additive，且没有 PRAGMA**
 *
 * 只有一条 `CREATE TABLE IF NOT EXISTS`，没有 ALTER、没有 DROP/RENAME、
 * 没有数据回填 ⇒ **结构上不可能动到任何既有表的一行**。
 *
 * ## ★★★ 重申（本文件是第二个照抄点，最容易把坏范本带过来）
 *
 * `ChatMigration2To3.MEMORY_CARD_REBUILD` 的头尾是
 * `PRAGMA foreign_keys = OFF` … `PRAGMA foreign_keys = ON`。**不要照抄它。**
 * 实测（SQLite 3.45.1）证明它在事务内是**静默 no-op**；它今天没炸只因为
 * `memory_card` 是子表。要重建表时用 [DEFER_FOREIGN_KEYS_ON]
 * （`defer_foreign_keys` 在事务内**可**切换，且把检查推迟到 COMMIT）。
 *
 * 本轮不需要任何 PRAGMA：新表没有外键、也没有中间态 ⇒ 加了反而是噪音。
 *
 * ## ★ 为什么 `model_target` 是**可空**的
 *
 * `NULL` = 从未设置过，`'LOCAL'` = 用户显式选了端侧。这两件事**必须分得开**
 * —— 否则将来要做「你还没选过后端」的引导时，就只能靠猜。
 * 若写成 `NOT NULL DEFAULT 'LOCAL'`，迁移当天就会给每一行灌一个"用户选过"的假事实。
 */
internal object DefaultTargetMigration4To5 : Migration(4, 5) {

    val CREATE_TABLES: List<String> = listOf(
        """
            CREATE TABLE IF NOT EXISTS `default_dialog_target` (
                `id` INTEGER NOT NULL,
                `model_target` TEXT,
                `updated_at` INTEGER NOT NULL,
                `schema_v` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`id`)
            )
        """.trimIndent(),
    )

    /** 本轮没有加列。★ 留空列表而不是删掉这个字段：让"v5 是加表不是加列"一眼可读。 */
    val ADD_COLUMNS: List<String> = emptyList()

    /** 本轮没有索引（单行表，全表扫描就是一行）。 */
    val CREATE_INDEXES: List<String> = emptyList()

    /** 完整执行序，也是测试与 Python oracle 的比对输入。 */
    val STATEMENTS: List<String> = CREATE_TABLES + ADD_COLUMNS + CREATE_INDEXES

    override fun migrate(connection: SQLiteConnection) {
        // ① CREATE 天然幂等（IF NOT EXISTS）
        for (sql in CREATE_TABLES) connection.execSQL(sql)

        // ② ALTER 守卫（本轮为空，保留形状是为了下一个人照抄时不会漏掉这段）
        for (sql in ADD_COLUMNS) {
            val parsed = parseAddColumn(sql)
            if (parsed == null || columnMissing(connection, parsed.table, parsed.column)) {
                connection.execSQL(sql)
            }
        }

        // ③ 索引天然幂等
        for (sql in CREATE_INDEXES) connection.execSQL(sql)

        // ④ 真验证：迁移后外键必须没被破坏（本轮新表无外键，但 `route_decision`
        //    /`chat_session` 指向 `cloud_credential` 的 FK 在 v4 已建 ⇒ 这里仍会真扫一遍）。
        assertNoForeignKeyViolation(connection)
    }

    // ───────────────────────────── 内部（与 CloudMigration3To4 同形）

    private data class AddColumn(val table: String, val column: String)

    private fun parseAddColumn(sql: String): AddColumn? {
        val m = ADD_COLUMN_RE.find(sql.trim()) ?: return null
        return AddColumn(m.groupValues[1], m.groupValues[2])
    }

    private val ADD_COLUMN_RE: Regex = Regex(
        "^ALTER\\s+TABLE\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+ADD\\s+COLUMN\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?",
        RegexOption.IGNORE_CASE,
    )

    private fun columnMissing(connection: SQLiteConnection, table: String, column: String): Boolean {
        val stmt: SQLiteStatement = connection.prepare("PRAGMA table_info(`$table`)")
        return try {
            var found = false
            while (stmt.step()) {
                if (stmt.getText(1) == column) { found = true; break }
            }
            !found
        } finally {
            stmt.close()
        }
    }

    /**
     * `PRAGMA foreign_key_check` —— 返回 0 行才是对的。
     *
     * 用 `prepare` 而不是 `execSQL`：后者拿不到结果行 ⇒ 「有违反」与「没违反」
     * 在返回值上完全一样，这个断言就形同虚设。**看不见的失败才是失败。**
     */
    private fun assertNoForeignKeyViolation(connection: SQLiteConnection) {
        val violations = ArrayList<String>()
        val statement: SQLiteStatement = connection.prepare("PRAGMA foreign_key_check")
        try {
            while (statement.step()) {
                val table = runCatching { statement.getText(0) }.getOrDefault("?")
                val rowId = runCatching { statement.getText(1) }.getOrDefault("?")
                val parent = runCatching { statement.getText(2) }.getOrDefault("?")
                violations += "$table(rowid=$rowId) -> $parent"
            }
        } finally {
            statement.close()
        }
        require(violations.isEmpty()) {
            "MIGRATION_4_5: foreign_key_check 报 ${violations.size} 处违反：" +
                violations.joinToString("; ")
        }
    }

    const val DEFER_FOREIGN_KEYS_ON: String = "PRAGMA defer_foreign_keys = ON"
}
