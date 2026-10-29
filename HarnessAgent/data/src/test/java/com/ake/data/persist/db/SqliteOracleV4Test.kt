package com.ake.data.persist.db

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempFile
import org.junit.Assume
import org.junit.Test

/**
 * ★★ `MIGRATION_3_4` 的**外部 oracle**：用 python3 的 SQLite 把迁移**真跑一遍**（docs/09 §7.1 L2）。
 *
 * ## 为什么不是 `AkeMigrationSchemaTest` 那种静态比对
 *
 * 静态比对只能证明「DDL 文本与 Entity 一致」，证明不了「这段 SQL 在 SQLite 上真的能执行」。
 * 而手工迁移的失败形态恰恰是：**当天不报错、等到第一次查询才 `no such column`。**
 *
 * ## 为什么用 python3 而不是 `room-testing` / `MigrationTestHelper`
 *
 * | 方案 | 为什么不用 |
 * |---|---|
 * | `androidx.room:room-testing` | 需要新增坐标（catalog 不在本轮可改范围）+ 需要 instrumented 测试（真机/模拟器，docs/07 记的冷启 4.7 分钟） |
 * | `sqlite3` CLI | 本机未安装，且 `apt` 源里没有（`E: Unable to locate package sqlite3`） |
 * | **python3 + `sqlite3` 模块** | ✅ 已确认可用（SQLite 3.45.1），独立于本项目，毫秒级 |
 *
 * ⚠ **版本 skew**：oracle 是 SQLite 3.45.1，真机是 `sqlite-bundled` 内置版本（未确证）。
 *    本测试因此**不能替代真机 L3**，只把风险从「上线后才发现」提前到「改代码的同一分钟」。
 *
 * ## ★ 负向用例
 *
 * [oracleRejectsADestructiveStatement] 故意往语句清单里塞一条 `DROP TABLE`，
 * 断言 oracle **必须失败** —— 否则「oracle 说通过」这句话本身没有意义
 * （一个永远返回通过的检查器等于没有检查器）。
 */
class SqliteOracleV4Test {

    private fun python3(): String? = listOf("python3", "python").firstOrNull { bin ->
        runCatching { ProcessBuilder(bin, "--version").start().waitFor() == 0 }.getOrDefault(false)
    }

    private fun oracleScript(): File {
        val candidates = listOf(
            File("src/test/resources/oracle/v4_migration_oracle.py"),
            File("data/src/test/resources/oracle/v4_migration_oracle.py"),
            File("../data/src/test/resources/oracle/v4_migration_oracle.py"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 oracle 脚本：${candidates.map { it.path }}")
    }

    private fun schemaDir(): File {
        val rel = "schemas/com.ake.data.persist.db.AkeDatabase"
        val candidates = listOf(File(rel), File("data/$rel"), File("../data/$rel"))
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: error("找不到 Room schema 目录：$rel")
    }

    /** 跑一次 oracle，返回 (exitCode, stdout)。 */
    private fun runOracle(statements: List<String>): Pair<Int, String> {
        val bin = python3()
        Assume.assumeNotNull(bin)
        val stmts = createTempFile("v4-stmts", ".json").toFile()
        stmts.writeText(jsonArray(statements))
        val proc = ProcessBuilder(
            bin!!,
            oracleScript().absolutePath,
            "--schema-dir", schemaDir().absolutePath,
            "--statements", stmts.absolutePath,
            "--json",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return code to out
    }

    private fun jsonArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        }

    @Test
    fun migrationRunsOnRealSqliteAndPreservesRows() {
        val (code, out) = runOracle(CloudMigration3To4.STATEMENTS)
        // ★★ 失败时把 oracle 原文打出来 —— 否则只有一句 "expected to contain" 无法归因。
        //    外部进程的 stdout 是唯一的证据来源，吞掉它就等于放弃了归因能力。
        com.google.common.truth.Truth.assertWithMessage("oracle 输出：\n$out").that(code).isEqualTo(0)
        assertThat(out).contains("\"sqlite_version\"")
        assertThat(out).contains("\"rows_preserved\": true")
        assertThat(out).contains("\"integrity_check\": \"ok\"")
        assertThat(out).contains("\"foreign_key_violations\": 0")
        assertThat(out).contains("\"v4_schema_mismatches\": []")
        assertThat(out).contains("\"idempotent_rerun\": true")
    }

    /**
     * ★★ **负向用例**：往清单里塞一条 `DROP TABLE chat_message`，oracle **必须红**。
     *
     * 它证明的是检查器本身有效 —— 没有这条，上一个测试的「通过」可能是假阳性
     * （例如 oracle 脚本根本没跑到断言就异常退出了，而我们把退出码读错了）。
     */
    @Test
    fun oracleRejectsADestructiveStatement() {
        val sabotaged = CloudMigration3To4.STATEMENTS + "DROP TABLE chat_message"
        val (code, out) = runOracle(sabotaged)
        assertThat(code).isNotEqualTo(0)
        // 它具体应该因为「行数没保住」或「缺表 / 列集不一致」/「迁移执行报错」而失败
        val rejected = out.contains("\"rows_preserved\": false")
            || out.contains("缺表")
            || out.contains("列集不一致")
            || out.contains("\"migration_error\": \"sqlite3")
        assertThat(rejected).isTrue()
    }

    /**
     * ★ 负向用例 2：去掉 `cloud_credential` 的建表语句 ⇒ v4 schema 必须对不上。
     *
     * 这条盯的是「迁移与 Entity 的一致性」那半，而不是「SQLite 能不能跑」那半。
     */
    @Test
    fun oracleRejectsMissingCredentialTable() {
        val withoutCredTable = CloudMigration3To4.STATEMENTS.filterNot {
            it.contains("cloud_credential") && it.trimStart().startsWith("CREATE TABLE")
        }
        val (code, out) = runOracle(withoutCredTable)
        assertThat(code).isNotEqualTo(0)
        assertThat(out).contains("缺表 cloud_credential")
    }
}
