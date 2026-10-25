package com.ake.data.persist.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * 2 → 3：聊天化 Agent（`docs/06-chat-database.md` §6.2 / §6.4）。
 *
 * ## 三条硬要求（照 [AkeMigrations.MIGRATION_1_2] 的样板形状）
 *
 *  ① **幂等**：建表/建索引一律 `IF NOT EXISTS`。迁移可能被重跑
 *     （升级中断、备份恢复、开发期反复装同一个 Debug 包）；
 *     而本轮有 `DROP TABLE`（`memory_card` 重建）⇒ 重跑一次的后果与「新建」完全不同。
 *     ★ 因此幂等性有专门一条断言（`ChatMigrationTest.migrationIsIdempotent`）。
 *  ② **走 `SQLiteConnection`** 而不是 `SupportSQLiteDatabase`（§9.5：向 Room 3 迁移时不用改签名）。
 *  ③ **建表语句必须与 Room 生成的 schema 逐列一致** ——
 *     表名、列名、类型、NOT NULL、DEFAULT、主键都错不得。
 *     手工迁移的 SQL **不会被 Room 编译期校验**（校验只对 AutoMigration 生效），
 *     写错了的症状是迁移当天不报错、等到第一次查询才 `no such column`。
 *     ⇒ 本轮把这条纪律从「人肉比对」升级成**一条可跑的断言**：
 *       `data/src/test/.../AkeMigrationSchemaTest` 用 [STATEMENTS] 与
 *       `data/schemas/com.ake.data.persist.db.AkeDatabase/3.json` 逐列比对。
 *     **改了 Entity 就必须重跑一次那个测试。**
 *
 * ## ★ 本轮唯一不能用 ADD COLUMN 完成的一处：`memory_card`
 *
 * 人格隔离要求 `UNIQUE(agent_id, key)`，而 SQLite 的 `ALTER TABLE ADD COLUMN`
 * **不能带 UNIQUE**，`ALTER TABLE` 也不支持「增删既有 UNIQUE 约束」
 * （sqlite.org/lang_altertable.html §4/§5）⇒ 只能走 12 步重建，见 [MEMORY_CARD_REBUILD]。
 * 这是上游「v2 → v3 纯加表加列」断言被推翻的地方（§9-U1）🔴。
 *
 * ## ★ `agent_session.chat_session_id` 的 REFERENCES 子句
 *
 * sqlite.org §4：带 REFERENCES 的 ADD COLUMN，**默认值必须是 NULL**。本列恰好可空 ⇒ 合法。
 * ⚠ 是否比对外键列表取决于 Room 2.8 的迁移校验（§0.2 R-2，未实测）；
 *   若校验失败，改走 §6.6 的 AutoMigration 备选 —— **在跑通之前这一段不许改**。
 *
 * ## 为什么把 SQL 抽成 [STATEMENTS] 而不是直接写在 `migrate()` 里
 *
 * `migrate()` 只在真机 / 仪器化测试里被执行，而「DDL 与 Entity 是否一致」
 * 是一个**纯静态**的性质 —— 它不该等到能跑 SQLite 的环境才被检查。
 * 抽成数据之后，JVM 单测（`./gradlew :data:test`）就能守住它。
 */
internal object ChatMigration2To3 : Migration(2, 3) {

    // ──────────────────────────────── ① 加列（既有表）

    /**
     * 加列段。**全部可空或带 DEFAULT，无回填**（§6.1）。
     *
     * ⚠ `ALTER TABLE` 加的列一定落在表的最末，而不是 ER 图里的位置。
     * Room 的 schema 校验按列名集合比对、不比列序 ⇒ 不要为了让列序好看去重建既有表：
     * 那会把一次零风险迁移变成一次有风险迁移（§6.3）。
     */
    val ADD_COLUMNS: List<String> = listOf(
        // `agent_session`：挂到对话会话。★ SET NULL —— 删对话不连带销毁任务轨迹（主理人 U3 裁决）
        "ALTER TABLE agent_session ADD COLUMN chat_session_id TEXT " +
            "REFERENCES `chat_session`(`id`) ON DELETE SET NULL ON UPDATE NO ACTION",
        "CREATE INDEX IF NOT EXISTS `idx_agent_session_chat` ON `agent_session` (`chat_session_id`)",

        // `task`：追溯「这句话派生了这个任务」。历史任务全部来自 UI 入口 ⇒ DEFAULT 'UI'
        "ALTER TABLE task ADD COLUMN chat_message_id TEXT",
        "ALTER TABLE task ADD COLUMN trigger_source TEXT NOT NULL DEFAULT 'UI'",
        "ALTER TABLE task ADD COLUMN skill_ref TEXT",
        "CREATE INDEX IF NOT EXISTS `idx_task_chat_message` ON `task` (`chat_message_id`)",

        // `task_checkpoint`：CheckpointEnvelope 外置【预留接口：MVP 只建列，不生成文件】
        "ALTER TABLE task_checkpoint ADD COLUMN envelope_file TEXT",
        "ALTER TABLE task_checkpoint ADD COLUMN envelope_schema_v INTEGER",

        // `policy_decision`：聊天场景的审计增强（上游 §8 S9）
        "ALTER TABLE policy_decision ADD COLUMN agent_id TEXT",
        "ALTER TABLE policy_decision ADD COLUMN chat_session_id TEXT",
        // ★ §10.4：只建 chat_session_id 这一条索引（V3 用例按会话查）。
        //   policy_decision 是既有热写表，不给它加没必要的新索引。
        "CREATE INDEX IF NOT EXISTS `idx_pol_chat_session` ON `policy_decision` (`chat_session_id`)",
    )

    // ──────────────────────────────── ② 新表（6 张）

    /**
     * 6 张新表的 `CREATE TABLE`。
     *
     * 顺序是**给人看的，不是给 SQLite 看的**：`agent_session.chat_session_id` 的
     * REFERENCES 指向 `chat_session`，而 SQLite 在 `ALTER TABLE` 时**不校验目标表存在**
     * （外键在运行时才检查）。但按 `agent_profile → chat_session → chat_message` 的顺序写，
     * 读到 SQL 的人不会困惑（§6.3）。
     */
    val CREATE_TABLES: List<String> = listOf(
        // ── N1 agent_profile（先建：chat_session 的外键指向它）
        """
            CREATE TABLE IF NOT EXISTS `agent_profile` (
                `id` TEXT NOT NULL,
                `agent_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `avatar_uri` TEXT,
                `avatar_kind` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `persona_charter_enc` BLOB,
                `persona_charter_preview` TEXT NOT NULL,
                `charter_hash` TEXT,
                `working_dir_uri` TEXT,
                `model_id` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `trust_tier` TEXT NOT NULL,
                `template_id` TEXT,
                `template_revision` INTEGER,
                `sign_key_id` TEXT,
                `scan_verdict` TEXT NOT NULL,
                `scan_hits_json` TEXT NOT NULL,
                `scanner_version` INTEGER NOT NULL,
                `always_confirm_skills` INTEGER NOT NULL,
                `max_enabled_skills` INTEGER NOT NULL DEFAULT 6,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_used_at` INTEGER,
                `archived` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`template_id`) REFERENCES `template_registry`(`template_id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent(),

        // ── N2 chat_session（★ 刻意没有 status 列，见 §2.6）
        """
            CREATE TABLE IF NOT EXISTS `chat_session` (
                `id` TEXT NOT NULL,
                `agent_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_message_at` INTEGER NOT NULL,
                `message_count` INTEGER NOT NULL DEFAULT 0,
                `preview_redacted` TEXT,
                `unread_count` INTEGER NOT NULL DEFAULT 0,
                `last_read_seq` INTEGER NOT NULL DEFAULT 0,
                `draft_enc` BLOB,
                `draft_updated_at` INTEGER,
                `compacted_upto_seq` INTEGER NOT NULL DEFAULT 0,
                `compact_level` INTEGER NOT NULL DEFAULT 0,
                `context_profile` TEXT NOT NULL,
                `last_mode` TEXT NOT NULL DEFAULT 'REPLY',
                `mode_epoch` INTEGER NOT NULL DEFAULT 0,
                `last_kv_cache_tokens` INTEGER NOT NULL DEFAULT 0,
                `last_rehydrate_tokens` INTEGER NOT NULL DEFAULT 0,
                `archived` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`agent_id`) REFERENCES `agent_profile`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent(),

        // ── N3 chat_message（★ turn_id / call_id / status / client_request_id）
        """
            CREATE TABLE IF NOT EXISTS `chat_message` (
                `id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `seq` INTEGER NOT NULL,
                `turn_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `client_request_id` TEXT,
                `call_id` TEXT,
                `content_enc` BLOB,
                `content_preview` TEXT NOT NULL,
                `payload_state` TEXT NOT NULL DEFAULT 'INLINE',
                `payload_file` TEXT,
                `payload_codec` TEXT,
                `payload_bytes` INTEGER NOT NULL DEFAULT 0,
                `task_id` TEXT,
                `skill_ref` TEXT,
                `model_namespace` TEXT NOT NULL DEFAULT 'local',
                `prompt_tokens` INTEGER NOT NULL DEFAULT 0,
                `decode_tokens` INTEGER NOT NULL DEFAULT 0,
                `kv_cache_tokens_after` INTEGER NOT NULL DEFAULT 0,
                `compaction_level` INTEGER NOT NULL DEFAULT 0,
                `redaction_count` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`session_id`) REFERENCES `chat_session`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent(),

        // ── N4 agent_capability_grant（复合主键；只存 granted=1 的行）
        """
            CREATE TABLE IF NOT EXISTS `agent_capability_grant` (
                `agent_id` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `ref_id` TEXT NOT NULL,
                `granted` INTEGER NOT NULL DEFAULT 1,
                `granted_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`agent_id`, `kind`, `ref_id`),
                FOREIGN KEY(`agent_id`) REFERENCES `agent_profile`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent(),

        // ── N5 template_registry
        """
            CREATE TABLE IF NOT EXISTS `template_registry` (
                `template_id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `avatar_asset` TEXT,
                `summary` TEXT NOT NULL,
                `tags_json` TEXT NOT NULL,
                `charter_hash` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `sign_key_id` TEXT,
                `scan_verdict` TEXT NOT NULL,
                `payload_file` TEXT,
                `disclaimer_enc` BLOB,
                `installed_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`template_id`)
            )
        """.trimIndent(),

        // ── N6 route_decision（★ 没有任何一列能装 payload 原文，§5.1）
        """
            CREATE TABLE IF NOT EXISTS `route_decision` (
                `id` TEXT NOT NULL,
                `chat_message_id` TEXT,
                `agent_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `turn_id` TEXT NOT NULL,
                `requested` TEXT NOT NULL,
                `requested_classes_json` TEXT NOT NULL,
                `decided` TEXT NOT NULL,
                `reason_key` TEXT NOT NULL,
                `allowed_classes_json` TEXT NOT NULL,
                `egress_gate_verdict` TEXT,
                `redaction_applied` INTEGER NOT NULL DEFAULT 0,
                `payload_bytes` INTEGER NOT NULL DEFAULT 0,
                `revoked_at` INTEGER,
                `decided_at` INTEGER NOT NULL,
                `schema_v` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`chat_message_id`) REFERENCES `chat_message`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent(),
    )

    /**
     * 新表的索引（13 个中的 11 个；另 2 个在 [ADD_COLUMNS] 里随加列一起建）。
     *
     * ★ 为什么 `idx_chat_msg_request` 是普通 UNIQUE 而不是部分索引：
     *   SQLite 的 UNIQUE 把 NULL 视为彼此不同 ⇒ 多行 NULL 不冲突，于是
     *   「只有 USER 消息带幂等键」这件事用一个普通 UNIQUE 索引就能表达。
     *   Room 的 `@Entity` 也不支持部分索引，且部分索引与迁移校验的冲突风险未实测（§0.2 R-3）。
     */
    val CREATE_INDEXES: List<String> = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_profile_agent_id` ON `agent_profile` (`agent_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_profile_active_used` ON `agent_profile` (`archived`, `last_used_at`)",
        "CREATE INDEX IF NOT EXISTS `idx_profile_template` ON `agent_profile` (`template_id`)",

        // ★ 不建 DESC 索引（§4.7）：反向扫描 ASC 索引已能满足 ORDER BY ... DESC
        "CREATE INDEX IF NOT EXISTS `idx_chat_sess_active_last` ON `chat_session` (`archived`, `last_message_at`)",
        "CREATE INDEX IF NOT EXISTS `idx_chat_sess_agent` ON `chat_session` (`agent_id`)",

        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_chat_msg_session_seq` ON `chat_message` (`session_id`, `seq`)",
        "CREATE INDEX IF NOT EXISTS `idx_chat_msg_turn` ON `chat_message` (`turn_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_chat_msg_request` ON `chat_message` (`client_request_id`)",

        "CREATE INDEX IF NOT EXISTS `idx_tpl_source` ON `template_registry` (`source`)",

        "CREATE INDEX IF NOT EXISTS `idx_route_message` ON `route_decision` (`chat_message_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_route_decided_at` ON `route_decision` (`decided_at`)",
        "CREATE INDEX IF NOT EXISTS `idx_route_decided` ON `route_decision` (`decided`, `decided_at`)",
    )

    // ──────────────────────────────── ③ memory_card 重建（12 步）

    /**
     * `memory_card` 的 12 步重建（sqlite.org/lang_altertable.html §8）。
     *
     * ★ 为什么要重建：人格隔离要求 `UNIQUE(agent_id, key)`，而既有约束是 `UNIQUE(key)`。
     *   SQLite 的 ALTER TABLE 既不能给新列带 UNIQUE，也不能增删既有 UNIQUE 约束。
     *   `memory_card` 上限 2,000 行（v2 §4.4 配额硬限）⇒ 重建是毫秒级，风险可控。
     *
     * 三个必须照做的点：
     *  ① **先 `PRAGMA foreign_keys=OFF` 再动**，最后 `PRAGMA foreign_key_check` 验证。
     *     本工程由 `AkePragmaDriver` 在每条连接上开了 `foreign_keys=ON`（v2 §3.6），
     *     开着外键做 DROP TABLE 会让 CASCADE/SET NULL 真的执行 —— 那就不是重建，是删数据。
     *  ② **索引在 COPY 之后重建**：带着索引插入 2,000 行比插完再建索引慢，
     *     而且 `task → memory_card` 的 SET NULL 外键在旧表被 DROP 时可能触发写。
     *  ③ **`INSERT INTO ... SELECT` 必须显式写列名**，不能用 `SELECT *`
     *     —— 新旧表的列序不同（agent_id 插在 key 之前），`SELECT *` 会静默错位。
     *
     * ★ 老行一律 `agent_id = NULL`（= 全局共享，与上游 S7 的「可空 = 全局」一致）。
     */
    val MEMORY_CARD_REBUILD: List<String> = listOf(
        "PRAGMA foreign_keys = OFF",

        """
            CREATE TABLE IF NOT EXISTS `memory_card_new` (
                `id` TEXT NOT NULL,
                `agent_id` TEXT,
                `key` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `scope` TEXT NOT NULL,
                `scope_host` TEXT,
                `title` TEXT NOT NULL,
                `content_enc` BLOB,
                `content_preview` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_used_at` INTEGER NOT NULL,
                `use_count` INTEGER NOT NULL,
                `pinned` INTEGER NOT NULL,
                `source_task_id` TEXT,
                `expires_at` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`source_task_id`) REFERENCES `task`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
        """.trimIndent(),

        """
            INSERT INTO `memory_card_new`
                (`id`, `agent_id`, `key`, `kind`, `scope`, `scope_host`, `title`,
                 `content_enc`, `content_preview`, `created_at`, `updated_at`,
                 `last_used_at`, `use_count`, `pinned`, `source_task_id`, `expires_at`)
            SELECT `id`, NULL, `key`, `kind`, `scope`, `scope_host`, `title`,
                 `content_enc`, `content_preview`, `created_at`, `updated_at`,
                 `last_used_at`, `use_count`, `pinned`, `source_task_id`, `expires_at`
              FROM `memory_card`
        """.trimIndent(),

        "DROP TABLE `memory_card`",
        "ALTER TABLE `memory_card_new` RENAME TO `memory_card`",

        // 索引重建（含新的 agent_id 维度 + 新的联合唯一）
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_mem_agent_key` ON `memory_card` (`agent_id`, `key`)",
        "CREATE INDEX IF NOT EXISTS `idx_mem_agent_host_used` ON `memory_card` (`agent_id`, `scope_host`, `last_used_at`)",
        // 旧索引保留：无 agent 的全局记忆仍走它们
        "CREATE INDEX IF NOT EXISTS `idx_mem_kind_updated` ON `memory_card` (`kind`, `updated_at`)",
        "CREATE INDEX IF NOT EXISTS `idx_mem_host_used` ON `memory_card` (`scope_host`, `last_used_at`)",
        "CREATE INDEX IF NOT EXISTS `idx_mem_expires` ON `memory_card` (`expires_at`)",

        // ① 的收尾：把外键开关还回去
        "PRAGMA foreign_keys = ON",
    )

    /**
     * 完整执行序。**也是 `AkeMigrationSchemaTest` 的比对输入。**
     *
     * ★ 顺序不能改：`memory_card` 的重建必须最后做（它需要 `foreign_keys=OFF` 这个全局开关，
     *   一旦关掉，前面的加列/建表若涉及外键就会静默失效）。
     */
    val STATEMENTS: List<String> = ADD_COLUMNS +
        CREATE_TABLES +
        CREATE_INDEXES +
        MEMORY_CARD_REBUILD

    override fun migrate(connection: SQLiteConnection) {
        for (sql in STATEMENTS) {
            connection.execSQL(sql)
        }
        // ★ `MEMORY_CARD_REBUILD` 末尾的 `PRAGMA foreign_keys = ON` 只是「还回去」，
        //   真正的验证是这一句：重建之后外键必须**真的**没被破坏（§6.4 ①）。
        assertNoForeignKeyViolation(connection)
    }

    /**
     * `PRAGMA foreign_key_check` —— 返回 0 行才是对的。
     *
     * 用 `prepare` 而不是 `execSQL`：后者拿不到结果行，于是「有违反」和「没违反」
     * 在返回值上完全一样，这个断言就形同虚设。**看不见的失败才是失败。**
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
            "MIGRATION_2_3: memory_card 重建后 foreign_key_check 报 ${violations.size} 处违反：" +
                violations.joinToString("; ")
        }
    }
}
