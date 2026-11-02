package com.ake.data.persist.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ★ v5 迁移（4 → 5）的结构性质。**与 `CloudMigration3To4Test` 同一套判据**，
 * 因为 v5 是 v4 的第二个照抄点 —— 而照抄点正是坏范本（`PRAGMA foreign_keys=OFF`）
 * 最容易混进来的地方。
 */
class DefaultTargetMigration4To5Test {

    private val statements: List<String> = DefaultTargetMigration4To5.STATEMENTS

    @Test
    fun migrationIsAdditive() {
        val forbidden = statements.filter {
            val s = it.trim().uppercase()
            s.startsWith("DROP") || s.startsWith("DELETE") || s.startsWith("UPDATE") ||
                s.contains(" RENAME ") || s.contains("RENAME TO")
        }
        assertThat(forbidden).isEmpty()
    }

    /** ★ 负向：确认上面的检查器真的能抓到 DROP（否则它只是摆设）。 */
    @Test
    fun additiveCheckerActuallyCatchesADrop() {
        val sabotaged = statements + "DROP TABLE default_dialog_target"
        val caught = sabotaged.filter {
            val s = it.trim().uppercase()
            s.startsWith("DROP") || s.startsWith("DELETE") || s.startsWith("UPDATE") ||
                s.contains(" RENAME ") || s.contains("RENAME TO")
        }
        assertThat(caught).isNotEmpty()
    }

    /**
     * ★★ v5 **绝对没有** `PRAGMA foreign_keys = OFF`。
     *
     * 这是本轮硬纪律 3 的可执行形式：`ChatMigration2To3` 那个模式在事务内是
     * **静默 no-op**（已实证），本轮不改它，但绝不能把它抄到新迁移里。
     */
    @Test
    fun doesNotUseTheBrokenForeignKeysOffPattern() {
        assertThat(statements.filter { it.contains("foreign_keys", ignoreCase = true) }).isEmpty()
        assertThat(DefaultTargetMigration4To5.DEFER_FOREIGN_KEYS_ON)
            .isEqualTo("PRAGMA defer_foreign_keys = ON")
    }

    @Test
    fun everyCreateIsIdempotent() {
        val creates = statements.filter { it.trimStart().startsWith("CREATE TABLE") }
        assertThat(creates).hasSize(1)
        for (sql in creates) assertThat(sql).contains("IF NOT EXISTS")
    }

    /** ★ 只有一条建表语句 —— 多一条就说明有人夹带私货（比如灌种子行）。 */
    @Test
    fun containsExactlyOneStatement() {
        assertThat(statements).hasSize(1)
        assertThat(statements.single()).contains("default_dialog_target")
    }

    /**
     * ★★ `model_target` 必须**可空且无默认值**。
     *
     * 若写成 `NOT NULL DEFAULT 'LOCAL'`，迁移当天就把「用户选过端侧」写成事实，
     * 「从未设置过」再也问不出来 —— 正是本轮要保住的那条不变量。
     */
    @Test
    fun modelTargetIsNullableAndHasNoDefault() {
        val ddl = statements.single()
        val col = Regex("`model_target`\\s+TEXT([^,]*)", RegexOption.IGNORE_CASE)
            .find(ddl)?.groupValues?.get(1)?.trim()
            ?: error("DDL 里没有 model_target 列")
        assertThat(col.uppercase()).doesNotContain("NOT NULL")
        assertThat(col.uppercase()).doesNotContain("DEFAULT")
    }

    @Test
    fun migrationIsRegistered() {
        assertThat(AkeDatabase.VERSION).isEqualTo(6)
        assertThat(AkeMigrations.ALL.map { "${it.startVersion}->${it.endVersion}" })
            .containsAtLeast("1->2", "2->3", "3->4", "4->5").inOrder()
        assertThat(AkeMigrations.ALL.toList()).contains(DefaultTargetMigration4To5)
    }

    /**
     * ★★ 样板迁移**不得**与真实迁移同号。
     *
     * 若某天有人把 `SAMPLE_MIGRATION_*` 加进 `ALL`，同一个 (4,5) 会有两条
     * `Migration`，Room 取哪条取决于数组顺序 —— 真机上表现为"迁移跑了但表没建"。
     */
    @Test
    fun noSampleMigrationSharesAVersionWithARealOne() {
        val real = AkeMigrations.ALL.map { it.startVersion to it.endVersion }.toSet()
        for (s in listOf(AkeMigrations.SAMPLE_MIGRATION_6_7)) {
            assertThat(real).doesNotContain(s.startVersion to s.endVersion)
        }
        assertThat(AkeMigrations.ALL.toList()).doesNotContain(AkeMigrations.SAMPLE_MIGRATION_6_7)
    }
}
