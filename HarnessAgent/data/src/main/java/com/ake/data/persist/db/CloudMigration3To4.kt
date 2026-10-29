package com.ake.data.persist.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * 3 → 4：云端模型推出（docs/09-cloud-data-and-secrets.md §3）。
 *
 * ## ★★ 性质：全 Additive —— 这是本迁移最重要的一条性质
 *
 * 没有 `DROP` / `RENAME` / 表重建 / 数据回填 ⇒ **结构上不可能删掉或修改既有行的值**。
 * 「升级不丢数据」因此不是一句承诺，而是从语句清单就能读出来的性质（docs/09 §3.1）。
 *
 * ## ★★★ 为什么这里**没有** `PRAGMA foreign_keys = OFF`
 *
 * 既有 `ChatMigration2To3.MEMORY_CARD_REBUILD` 的头尾是
 * `PRAGMA foreign_keys = OFF` … `PRAGMA foreign_keys = ON`。**不要照抄它。**
 *
 * 实测（SQLite 3.45.1，脚本见 docs/09 §1.3）：
 * ```
 *   事务外：before=(1,) → OFF → (0,)                  ★ 生效
 *   事务内：before=(1,) → OFF → (1,)                  ★ no-op
 *           删除父行后子行仍被 ON DELETE SET NULL 改写  ← 行为证据：约束没被关掉
 * ```
 * sqlite.org/pragma.html#pragma_foreign_keys 原文：
 * 「This pragma is a no-op within a transaction; foreign key constraint enforcement
 *   may only be enabled or disabled when there is no pending BEGIN or SAVEPOINT.」
 * 而 Room 的迁移**很可能**跑在事务里（docs/09 §U3 给了实证办法）⇒ 那段 OFF 形同虚设。
 *
 * 它今天没炸，只是因为 `memory_card` 是**子表**（`memory_card.source_task_id
 * REFERENCES task(id)`），没人 REFERENCES 它 ⇒ DROP 不触发任何 CASCADE。
 * **下一次有人重建一张「被别人 REFERENCES 的表」，它就会炸，而它那条断言仍然亮绿。**
 *
 * ### 正确的写法（下一轮重建表时用这个）
 *
 * ```
 * PRAGMA defer_foreign_keys = ON     ← ★ 事务内**可**切换（实测：0 → 1）
 * … 重建 …
 * COMMIT 时才检查约束                 ← 重建过程的中间态不一致是被允许的
 * ```
 * `defer_foreign_keys` 是 SQLite 3.20+ 专门为「重建过程中必须暂时不一致」这个场景设计的，
 * 且**在事务内可用**。它同时消掉了「Room 到底包不包事务」这个不确定性 —— 两种情况下都对。
 *
 * ### 本轮为什么**不需要**它
 * v4 没有表重建（全 Additive）⇒ 没有任何中间态会违反外键 ⇒ 不需要 defer，也不需要 OFF。
 * 加一条用不上的 PRAGMA 本身也是噪音。需要重建时用 [DEFER_FOREIGN_KEYS_ON]。
 */
internal object CloudMigration3To4 : Migration(3, 4) {

    // ───────────────────────────── ① 新表

    /**
     * 新表。**必须先于任何指向它的 ALTER**（虽然 SQLite 在 ALTER 时不校验目标表存在，
     * 但按依赖顺序写，读到 SQL 的人不会困惑 —— `ChatMigration2To3` 的同一条纪律）。
     */
    val CREATE_TABLES: List<String> = listOf(
        """
            CREATE TABLE IF NOT EXISTS `cloud_credential` (
                `cred_id` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `endpoint_host` TEXT NOT NULL,
                `base_url_enc` BLOB NOT NULL,
                `secret_enc` BLOB,
                `model_name` TEXT NOT NULL,
                `tls_mode` TEXT NOT NULL DEFAULT 'REQUIRED',
                `declared_context_window` INTEGER,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_used_at` INTEGER,
                `deleted_at` INTEGER,
                `schema_v` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`cred_id`)
            )
        """.trimIndent(),
    )

    // ───────────────────────────── ② 加列

    /**
     * ★ 全部可空或带 DEFAULT，**无回填**。
     *
     * ⚠ `ALTER TABLE ADD COLUMN` 加的列一定落在表最末 —— 不要为了列序好看去重建表。
     * ⚠ 带 REFERENCES 的 ADD COLUMN，**默认值必须是 NULL**
     *   （sqlite.org/lang_altertable.html §4）⇒ `credential_id` / `cloud_credential_id`
     *   必须可空 —— 它们本来就应该是可空的。
     *
     * ★ 大表加列用 DEFAULT 而不是 UPDATE：`ADD COLUMN x INTEGER NOT NULL DEFAULT 0`
     *   是 O(1)，`ADD COLUMN` + `UPDATE` 全表是 O(n)（docs/09 §3 沿用 `AkeMigrations` 的纪律 ③）。
     */
    val ADD_COLUMNS: List<String> = listOf(
        // ── N1 route_decision（13 列）
        "ALTER TABLE route_decision ADD COLUMN outcome TEXT",
        "ALTER TABLE route_decision ADD COLUMN attempt_phase TEXT",
        "ALTER TABLE route_decision ADD COLUMN attempt_no INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE route_decision ADD COLUMN retry_of TEXT",
        "ALTER TABLE route_decision ADD COLUMN idempotency_key TEXT",
        "ALTER TABLE route_decision ADD COLUMN credential_id TEXT " +
            "REFERENCES `cloud_credential`(`cred_id`) ON DELETE SET NULL ON UPDATE NO ACTION",
        "ALTER TABLE route_decision ADD COLUMN endpoint_host TEXT",
        "ALTER TABLE route_decision ADD COLUMN model_name TEXT",
        "ALTER TABLE route_decision ADD COLUMN request_id TEXT",
        "ALTER TABLE route_decision ADD COLUMN delivered_bytes INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE route_decision ADD COLUMN finish_reason_raw TEXT",
        "ALTER TABLE route_decision ADD COLUMN policy_profile TEXT",
        "ALTER TABLE route_decision ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",

        // ── N2 chat_session（3 列）
        // ★ 默认值 'LOCAL' = `DialogTargetCodec.encode(DialogTarget.Device)`
        //   —— 用既有 codec 的词汇，不另起一套（由 `CloudMigration3To4Test` 钉住）。
        //   ⚠ docs/09 §2.4 原文写的是 'LOCAL_ONLY'，落地时据此更正，理由见 docs/09 附录说明。
        "ALTER TABLE chat_session ADD COLUMN model_target TEXT NOT NULL DEFAULT 'LOCAL'",
        "ALTER TABLE chat_session ADD COLUMN cloud_credential_id TEXT " +
            "REFERENCES `cloud_credential`(`cred_id`) ON DELETE SET NULL ON UPDATE NO ACTION",
        "ALTER TABLE chat_session ADD COLUMN cloud_consent_at INTEGER",
    )

    // ───────────────────────────── ③ 索引

    val CREATE_INDEXES: List<String> = listOf(
        // ⚠ 列序 `updated_at` 而非 docs/09 §2.2 表格的 `decided_at`
        //   —— 理由见 `ChatEntities.RouteDecisionEntity` 的同名注释（§6.3 的 heartbeat 为准）。
        "CREATE INDEX IF NOT EXISTS `idx_route_outcome` ON `route_decision` (`outcome`, `updated_at`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_route_idem` ON `route_decision` (`idempotency_key`)",
        "CREATE INDEX IF NOT EXISTS `idx_chat_sess_cred` ON `chat_session` (`cloud_credential_id`)",
    )

    /** 完整执行序，也是 `AkeMigrationSchemaTest` 与 Python oracle 的比对输入。 */
    val STATEMENTS: List<String> = CREATE_TABLES + ADD_COLUMNS + CREATE_INDEXES

    override fun migrate(connection: SQLiteConnection) {
        // ① CREATE 天然幂等（IF NOT EXISTS）
        for (sql in CREATE_TABLES) connection.execSQL(sql)

        // ② ★★ ALTER 不能带 IF NOT EXISTS ⇒ 必须查 pragma_table_info 守卫。
        //    这是 `ChatMigration2To3` 的**真实缺口**：它的 ADD_COLUMNS 重跑会抛
        //    "duplicate column name"，而它那条 `everyCreateIsIdempotent` 只检查了
        //    CREATE 开头的语句 ⇒ 「迁移幂等」这句注释对 ALTER 从不成立。
        for (sql in ADD_COLUMNS) {
            val parsed = parseAddColumn(sql)
            if (parsed == null || columnMissing(connection, parsed.table, parsed.column)) {
                connection.execSQL(sql)
            }
        }

        // ③ 索引天然幂等
        for (sql in CREATE_INDEXES) connection.execSQL(sql)

        // ④ 真验证：迁移后外键必须没被破坏。**必须放最后**（此时两张表的 FK 都建完了）。
        assertNoForeignKeyViolation(connection)
    }

    // ───────────────────────────── 内部

    private data class AddColumn(val table: String, val column: String)

    private fun parseAddColumn(sql: String): AddColumn? {
        val m = ADD_COLUMN_RE.find(sql.trim()) ?: return null
        return AddColumn(m.groupValues[1], m.groupValues[2])
    }

    private val ADD_COLUMN_RE: Regex = Regex(
        "^ALTER\\s+TABLE\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+ADD\\s+COLUMN\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?",
        RegexOption.IGNORE_CASE,
    )

    /** `PRAGMA table_info` 的第 2 列（index 1）是列名。 */
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
     * 用 `prepare` 而不是 `execSQL`：后者拿不到结果行 ⇒ 「有违反」与「没违反」在返回值上
     * 完全一样，这个断言就形同虚设。**看不见的失败才是失败。**
     */
    private fun assertNoForeignKeyViolation(connection: SQLiteConnection) {
        val violations = ArrayList<String>()
        val statement: SQLiteStatement = connection.prepare("PRAGMA foreign_key_check")
        try {
            while (statement.step()) {
                // 列序：0=table 1=rowid 2=parent 3=fkid
                val table = runCatching { statement.getText(0) }.getOrDefault("?")
                val rowId = runCatching { statement.getText(1) }.getOrDefault("?")
                val parent = runCatching { statement.getText(2) }.getOrDefault("?")
                violations += "$table(rowid=$rowId) -> $parent"
            }
        } finally {
            statement.close()
        }
        require(violations.isEmpty()) {
            "MIGRATION_3_4: foreign_key_check 报 ${violations.size} 处违反：" +
                violations.joinToString("; ")
        }
    }

    /**
     * ★ 给下一轮的备忘常量：**表重建时用这一条，不要用 `PRAGMA foreign_keys = OFF`。**
     * 见本文件类注释。放在这里是为了让它出现在代码里，而不是只待在文档里。
     */
    const val DEFER_FOREIGN_KEYS_ON: String = "PRAGMA defer_foreign_keys = ON"
}
