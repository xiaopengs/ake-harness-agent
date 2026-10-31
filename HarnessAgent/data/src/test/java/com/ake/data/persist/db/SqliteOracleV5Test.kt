package com.ake.data.persist.db

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * ★★ `MIGRATION_4_5` 的**外部 oracle**（与 3→4 同一份 python 脚本，见 `SqliteOracleRunner`）。
 *
 * ## 为什么迁移必须有一个"不经过我们 DAO"的验证
 *
 * 手工迁移的失败形态是**当天不报错、等到第一次查询才 `no such column`**。
 * 只做「DDL 文本 vs `5.json`」的静态比对证明不了"这段 SQL 在 SQLite 上真能跑"。
 * oracle 用 **python3 的 sqlite3 模块（SQLite 3.45.1）**——独立于本项目的 SQLite 实现，
 * 在**事务内**真跑一遍，再断言行数不丢 / `integrity_check=ok` / `foreign_key_check=0` /
 * 目标 schema 逐列一致 / 重跑幂等。
 *
 * ## ★ 负向用例（本文件三条）
 *
 *  ① 删掉建表语句 ⇒ oracle 必须报"缺表"；
 *  ② 塞一条 `DROP TABLE` ⇒ 必须红；
 *  ③ ★★ **给新表灌一行种子** ⇒ 必须红 —— 这条盯的是「迁移不回填」：
 *     若有人顺手 `INSERT ... 'LOCAL'`，「从未设置过」就与「显式选了端侧」
 *     再也分不开，而所有其它断言**仍然全绿**。
 */
class SqliteOracleV5Test {

    @Test
    fun migrationRunsOnRealSqliteAndPreservesRows() {
        val (code, out) = SqliteOracleRunner.run(DefaultTargetMigration4To5.STATEMENTS, from = 4, to = 5)
        assertWithMessage("oracle 输出：\n$out").that(code).isEqualTo(0)
        assertThat(out).contains("\"from_version\": 4")
        assertThat(out).contains("\"to_version\": 5")
        assertThat(out).contains("\"rows_preserved\": true")
        assertThat(out).contains("\"integrity_check\": \"ok\"")
        assertThat(out).contains("\"foreign_key_violations\": 0")
        assertThat(out).contains("\"idempotent_rerun\": true")
        // ★ 新表被建出来，且是**空的**（未设置过 = 没有这一行，不是有一行 'LOCAL'）
        assertThat(out).contains("default_dialog_target")
    }

    /** ★ 负向 ①：去掉建表语句 ⇒ 目标 schema 必须对不上。 */
    @Test
    fun oracleRejectsMissingDefaultTargetTable() {
        val without = DefaultTargetMigration4To5.STATEMENTS.filterNot {
            it.contains("default_dialog_target") && it.trimStart().startsWith("CREATE TABLE")
        }
        assertThat(without).isEmpty()   // ★ 顺带证明：过滤条件真的命中了（否则这条测试是自欺）
        val (code, out) = SqliteOracleRunner.run(without, from = 4, to = 5)
        assertThat(code).isNotEqualTo(0)
        assertThat(out).contains("缺表 default_dialog_target")
    }

    /** ★ 负向 ②：塞一条破坏性语句 ⇒ 必须红。 */
    @Test
    fun oracleRejectsADestructiveStatement() {
        val sabotaged = DefaultTargetMigration4To5.STATEMENTS + "DROP TABLE chat_message"
        val (code, out) = SqliteOracleRunner.run(sabotaged, from = 4, to = 5)
        assertThat(code).isNotEqualTo(0)
        val rejected = out.contains("\"rows_preserved\": false")
            || out.contains("缺表")
            || out.contains("列集不一致")
            || out.contains("\"migration_error\": \"sqlite3")
        assertThat(rejected).isTrue()
    }

    /**
     * ★★ 负向 ③：**给新表灌一行 `'LOCAL'` 种子** ⇒ oracle 必须红。
     *
     * 它盯的是「迁移不回填」这条纪律。写种子的人通常是好意（"给个默认值省得判空"），
     * 但那一行会让「用户从未设置过默认后端」这个事实**永远消失**，
     * 且**任何一条正常断言都不会红** —— 只能靠"新表必须是空的"这一条抓住。
     */
    @Test
    fun oracleRejectsSeedingTheDefaultTargetRow() {
        val seeded = DefaultTargetMigration4To5.STATEMENTS +
            "INSERT INTO default_dialog_target (id, model_target, updated_at, schema_v) " +
            "VALUES (0, 'LOCAL', 0, 1)"
        val (code, out) = SqliteOracleRunner.run(seeded, from = 4, to = 5)
        assertThat(code).isNotEqualTo(0)
        assertThat(out).contains("\"rows_preserved\": true")   // 行数没丢（不是崩了）
        assertThat(out).contains("\"ok\": false")              // 但因为"新表非空"被判失败
    }
}
