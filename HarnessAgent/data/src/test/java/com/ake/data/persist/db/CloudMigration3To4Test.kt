package com.ake.data.persist.db

import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ★ v4 迁移（3 → 4）的结构性质（docs/09 §3 / §7.2 的 S1–S3、S11–S12）。
 *
 * 与 `SqliteOracleV4Test` 的分工：
 *  - 本文件：**纯 JVM 静态性质**（不跑 SQLite，毫秒级，盯"形状"与"纪律"）；
 *  - 那个文件：**外部 oracle**（真的在 SQLite 上执行，盯"能不能跑、数据丢不丢"）。
 *
 * ★ 每条正例都配一条**能杀死它的负向用例**：一个永远返回通过的检查器等于没有检查器。
 */
class CloudMigration3To4Test {

    private val statements: List<String> = CloudMigration3To4.STATEMENTS

    // ───────────────────────────── ① 全 Additive（S1）

    @Test
    fun migrationIsAdditive() {
        val forbidden = statements.filter {
            val s = it.trim().uppercase()
            s.startsWith("DROP") || s.startsWith("DELETE") ||
                s.startsWith("UPDATE") || s.contains(" RENAME ") ||
                s.contains("RENAME TO")
        }
        assertThat(forbidden).isEmpty()
    }

    /** ★ 负向：把一条 DROP 塞进去，上面的检查器必须抓到。 */
    @Test
    fun additiveCheckerActuallyCatchesADrop() {
        val sabotaged = statements + "DROP TABLE route_decision"
        val caught = sabotaged.filter {
            val s = it.trim().uppercase()
            s.startsWith("DROP") || s.startsWith("DELETE") || s.startsWith("UPDATE") ||
                s.contains(" RENAME ") || s.contains("RENAME TO")
        }
        assertThat(caught).isNotEmpty()
    }

    // ───────────────────────────── ② ★★ 不复制 foreign_keys=OFF（docs/09 §1.3）

    /**
     * ★★ v4 **绝对没有** `PRAGMA foreign_keys = OFF`。
     *
     * 既有 `ChatMigration2To3.MEMORY_CARD_REBUILD` 的头尾就是这两条，而实测证明
     * `PRAGMA foreign_keys` **在事务内是静默 no-op**（读数不变，删父行时
     * `ON DELETE SET NULL` 仍被触发）。它今天没炸只因为 `memory_card` 是子表。
     * ⇒ 本轮不复制这个模式；下一轮要重建表时用 `CloudMigration3To4.DEFER_FOREIGN_KEYS_ON`。
     */
    @Test
    fun doesNotUseTheBrokenForeignKeysOffPattern() {
        val offenders = statements.filter { it.contains("foreign_keys", ignoreCase = true) }
        assertThat(offenders).isEmpty()
    }

    /** ★ 既有 2→3 **确实**用了那个模式 —— 把它钉住，免得有人以为本测试写错了。 */
    @Test
    fun documentsThatLegacyMigrationStillUsesIt() {
        assertThat(ChatMigration2To3.STATEMENTS)
            .contains("PRAGMA foreign_keys = OFF")
        // ★ 这条断言存在的意义：它是一条"已知的、本轮不修的既有缺陷"的活标记。
        //   当 2→3 被修好（改用 defer_foreign_keys）时，本断言会红 ⇒ 提醒同步更新文档与注释。
    }

    /** ★ 正确的替代品已经作为常量备在那里，且确实是 defer 而不是 off。 */
    @Test
    fun deferForeignKeysConstantIsTheRightOne() {
        assertThat(CloudMigration3To4.DEFER_FOREIGN_KEYS_ON).isEqualTo("PRAGMA defer_foreign_keys = ON")
        assertThat(CloudMigration3To4.DEFER_FOREIGN_KEYS_ON).doesNotContain("foreign_keys = OFF")
    }

    // ───────────────────────────── ③ 幂等（S2）

    @Test
    fun everyCreateIsIdempotent() {
        val creates = statements.filter {
            it.trimStart().startsWith("CREATE TABLE") ||
                it.trimStart().startsWith("CREATE INDEX") ||
                it.trimStart().startsWith("CREATE UNIQUE INDEX")
        }
        assertThat(creates).isNotEmpty()
        for (sql in creates) assertThat(sql).contains("IF NOT EXISTS")
    }

    /**
     * ★ ALTER 不能带 `IF NOT EXISTS` ⇒ 幂等性必须靠 `pragma_table_info` 守卫。
     * 本测试断言：`migrate()` 的实现里确实有那条守卫路径（用源码级特征检查）。
     *
     * ⚠ 这是**源码文本断言**而不是行为断言 —— 强度不如 oracle 那条
     *   （`SqliteOracleV4Test.migrationRunsOnRealSqliteAndPreservesRows` 会真的重跑一遍）。
     *   两者互补：这条快且能指出"守卫被删了"，那条慢但证明"真的幂等"。
     */
    @Test
    fun addColumnsAreGuardedByTableInfo() {
        val src = File("src/main/java/com/ake/data/persist/db/CloudMigration3To4.kt").takeIf { it.isFile }
            ?: File("data/src/main/java/com/ake/data/persist/db/CloudMigration3To4.kt")
        val text = src.readText()
        assertThat(text).contains("columnMissing")
        assertThat(text).contains("PRAGMA table_info")
    }

    // ───────────────────────────── ④ 语句内容（S11 / S12）

    @Test
    fun addColumnsCountMatchesDesign() {
        val alters = statements.count { it.trimStart().startsWith("ALTER TABLE") }
        // route_decision 13 列 + chat_session 3 列
        assertThat(alters).isEqualTo(16)
    }

    @Test
    fun notNullAddColumnsCarryDefaults() {
        for (sql in statements.filter { it.trimStart().startsWith("ALTER TABLE") }) {
            val s = sql.trim()
            if (s.contains("NOT NULL")) {
                assertThat(s).contains("DEFAULT")
            }
        }
    }

    /**
     * ★ 带 REFERENCES 的 ADD COLUMN **默认值必须是 NULL**
     * （sqlite.org/lang_altertable.html §4）⇒ 那两列必须可空（不能写 NOT NULL）。
     */
    @Test
    fun referencingAddColumnsMustBeNullable() {
        val referencing = statements.filter {
            it.trimStart().startsWith("ALTER TABLE") && it.contains("REFERENCES")
        }
        assertThat(referencing).hasSize(2)
        for (sql in referencing) {
            assertThat(sql.uppercase()).doesNotContain("NOT NULL")
        }
    }

    /**
     * ★ `model_target` 的默认值必须**与 `DialogTargetCodec` 的词汇一致**。
     *
     * docs/09 §2.4 原文写的是 `'LOCAL_ONLY'`，落地时更正为 `'LOCAL'` —— 因为
     * `DialogTargetCodec.encode(DialogTarget.Device) == "LOCAL"`。
     * **同一个概念不能有两套编码**（历史上因此出过真实的黑名单绕过安全 bug）。
     */
    @Test
    fun modelTargetDefaultUsesTheDialogTargetCodecVocabulary() {
        assertThat(DialogTargetCodec.encode(DialogTarget.Device)).isEqualTo("LOCAL")
        val stmt = statements.first { it.contains("model_target") }
        assertThat(stmt).contains("DEFAULT 'LOCAL'")
        // 反向：codec 解出来的也必须是 Device（fail-closed 的另一半）
        assertThat(DialogTargetCodec.decode("LOCAL")).isEqualTo(DialogTarget.Device)
    }

    // ───────────────────────────── ⑤ 接线

    @Test
    fun migrationIsRegistered() {
        assertThat(AkeDatabase.VERSION).isEqualTo(4)
        assertThat(AkeMigrations.ALL.map { "${it.startVersion}->${it.endVersion}" })
            .containsExactly("1->2", "2->3", "3->4").inOrder()
        assertThat(AkeMigrations.ALL.toList()).contains(CloudMigration3To4)
    }
}
