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
 * ## 现在的状态（2026-10）
 *
 * 版本已到 2，[ALL] 里挂着一条**真实**迁移（[MIGRATION_1_2]：新增两张配置表）。
 * 下面另留两条样板，供下一个人照抄形状而不必重新查写法。
 *
 * 开发期 schema 天天变，正式做法是 build flag：
 * `if (BuildConfig.DEV_DESTRUCTIVE_MIGRATION) builder.fallbackToDestructiveMigration()`，
 * 且 **release 构建绝不允许出现这个 flag**。
 */
/**
 * ★★ 纪律（2026-10 · 宋砚秋，docs/09 §1.3）：**表重建一律用 `defer_foreign_keys`。**
 *
 * `ChatMigration2To3.MEMORY_CARD_REBUILD` 的头尾是
 * ```
 *   PRAGMA foreign_keys = OFF
 *   … 12 步重建 …
 *   PRAGMA foreign_keys = ON
 * ```
 * ⚠⚠ **它是错的，本轮不改（不在范围），但绝不要把它当范本抄。**
 *
 * 实测（SQLite 3.45.1）：`PRAGMA foreign_keys` **在事务内是静默 no-op** ——
 * 读数不变，且删父行时 `ON DELETE SET NULL` 仍被触发（行为证据，不是读数）。
 * sqlite.org：`This pragma is a no-op within a transaction`。而 Room 的迁移很可能跑在事务里。
 *
 * 它今天没炸，只因为 `memory_card` 是子表、没人 REFERENCES 它。
 * **下一次重建一张「被别人 REFERENCES 的表」就会炸，而它那条测试仍然亮绿。**
 *
 * ⇒ 正确写法见 `CloudMigration3To4.DEFER_FOREIGN_KEYS_ON`（`defer_foreign_keys`
 *    在事务内**可**切换，且把约束检查推迟到 COMMIT —— 正是重建需要的语义）。
 */
object AkeMigrations {

    // ──────────────────────────────────────────── 真实迁移

    /**
     * 1 → 2：**新增 `mcp_server` / `app_settings` 两张配置表**（`McpServerStorePort` /
     * `SettingsStorePort` 从 `:ui` 的 `org.json` 过渡实现手里接过来的落点）。
     *
     * ## 三条硬要求（照 [SAMPLE_MIGRATION_2_3] 的样板形状）
     *
     *  ① **幂等**：建表/建索引一律 `IF NOT EXISTS`。迁移可能被重跑
     *     （升级中断、备份恢复、以及「开发期反复装同一个 Debug 包」）。
     *  ② **走 `SQLiteConnection` 而不是 `SupportSQLiteDatabase`**（§9.5）。
     *  ③ **建表语句必须与 Room 生成的 schema 逐列一致** ——
     *     表名、列名、类型、NOT NULL、主键都错不得。
     *     手工迁移的 SQL **不会被 Room 编译期校验**（校验只对 AutoMigration 生效），
     *     写错了的症状是迁移当天不报错、等到第一次查询才 `no such column`，
     *     而那时用户的数据已经在新版本里了。
     *     ⇒ 核对方法：打开 `data/schemas/com.ake.data.persist.db.AkeDatabase/2.json`，
     *       与本文件的 DDL 逐列比对。**改了 Entity 就必须重跑一次这个比对。**
     *
     * ## 为什么**不做**数据回填
     *
     * `:ui` 的过渡实现把数据写在 `filesDir/mcp/servers.json` 与
     * `filesDir/settings/ui_local.json`。技术上可以读出来回填进新表，但刻意不做：
     *  - 回填代码要认识 `org.json` 的形状，而那份形状本就该随过渡实现一起删掉 ——
     *    为了一次回填而把它固化在迁移里，等于让一个临时格式活到下一个版本；
     *  - 这是内部开发者的配置（几条手录的 localhost server URL），
     *    重录一次的成本远低于「迁移里留一段永远无法被测试覆盖的兼容代码」。
     *  `:ui` 侧删除过渡实现时那两个文件一并失去引用，由 `WipeEverythingUseCase` ⑤b 段兜住清理。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `mcp_server` (
                    `id` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `url` TEXT NOT NULL,
                    `headers_json` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `transport` TEXT NOT NULL,
                    `call_timeout_ms` INTEGER NOT NULL,
                    `connect_timeout_ms` INTEGER NOT NULL,
                    `max_reconnect_attempts` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_mcp_enabled` ON `mcp_server` (`enabled`)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `app_settings` (
                    `id` INTEGER NOT NULL,
                    `allow_hosts_json` TEXT NOT NULL,
                    `max_steps` INTEGER,
                    `show_dom_payload` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

    // ──────────────────────────────────────────── 真实迁移（2 → 3）

    val MIGRATION_2_3: Migration = ChatMigration2To3

    // ──────────────────────────────────────────── 真实迁移（3 → 4）

    /**
     * 3 → 4：云端模型推出（docs/09 §3）。
     *
     *  - 1 张新表 `cloud_credential`（凭证元数据 + 密文，A5 裁决落 Room 不落文件）；
     *  - `route_decision` 加 13 列（审计闭环：outcome / attempt_phase / 幂等键 / 凭证引用…）；
     *  - `chat_session` 加 3 列（model_target / cloud_credential_id / cloud_consent_at）；
     *  - 3 个索引。
     *
     * ★ **全 Additive**：无 DROP / RENAME / 重建 / 回填。
     */
    val MIGRATION_3_4: Migration = CloudMigration3To4

    // ──────────────────────────────────────────── 样板（**不进 [ALL]**）

    /**
     * 4 → 5 的样板：加列 + 建索引。真实改动时照抄这个形状。
     *
     *  ① **必须幂等**：建索引一律 `IF NOT EXISTS`；删列先查 `pragma_table_info`。
     *  ② **不要依赖 `SupportSQLiteDatabase`**：本文全部走 `SQLiteConnection`，
     *     这样向 Room 3 迁移时不用改签名（§9.5）。
     *  ③ **大表加列用默认值而不是 UPDATE**：`ALTER TABLE ... ADD COLUMN x INTEGER NOT NULL DEFAULT 0`
     *     是 O(1)，而 `ADD COLUMN` + `UPDATE` 全表是 O(n)，在 trace_event 上会是几秒的卡顿。
     *
     * ★ 2026-10：原「2 → 3 样板」已**转正**为 [MIGRATION_2_3]（聊天化 Agent），
     *   样板顺延到 4 → 5，避免同一个版本号挂着两条 `Migration(2, 3)`。
     */
    val SAMPLE_MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE task ADD COLUMN budget_profile TEXT",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_step_action_key ON step (task_id, action_key)",
            )
        }
    }

    /** 5 → 6 的样板：带数据回填。 */
    val SAMPLE_MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "UPDATE task SET budget_profile = 'DEFAULT' WHERE budget_profile IS NULL",
            )
        }
    }

    // ──────────────────────────────────────────── 汇总

    /**
     * 交给 `Room.databaseBuilder(...).addMigrations(*AkeMigrations.ALL)` 的那一份。
     *
     * ★ **必须写在文件末尾**：它引用上面那些 `val`，而 Kotlin 的 object 初始化是**按声明顺序**的，
     *   把它放在最前面会对上 `Variable 'MIGRATION_1_2' must be initialized`（本轮真编译踩到）。
     *   `arrayOf(...)` 在这里是取值而不是延迟引用 —— 它是 `Array`，不是 `Lazy`。
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
