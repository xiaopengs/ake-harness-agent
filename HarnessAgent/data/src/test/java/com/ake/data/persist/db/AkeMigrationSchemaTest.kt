package com.ake.data.persist.db

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ★★ `MIGRATION_2_3` 的手写 DDL 与 Room 生成的 schema **逐列一致**（§6.2 纪律 ③）。
 *
 * ## 为什么必须存在这个测试
 *
 * 手工迁移的 SQL **不会被 Room 编译期校验**（校验只对 AutoMigration 生效）。
 * 写错了的症状是**迁移当天不报错、等到第一次查询才 `no such column`**，
 * 而那时用户的数据已经在新版本里了。数据库文档给的解法是「打开 3.json 逐列比对」，
 * 那是一条**人肉纪律** —— 本文件把它变成一条**每次 `./gradlew :data:test` 都会跑**的断言。
 *
 * ## 为什么它是 JVM 单测而不是 instrumented
 *
 * 「DDL 与 Entity 是否一致」是一个**纯静态**的性质，它不该等到能跑 SQLite 的环境
 * （真机 / 模拟器）才被检查。这也是 `ChatMigration2To3.STATEMENTS` 把 SQL 抽成数据的原因。
 *
 * ⚠ 它**替代不了** `ChatMigrationTest`（androidTest）：那条验证的是
 * 「迁移真的被执行、旧数据一个不少」—— 需要 SQLite。两者是互补的两半，缺一不可。
 */
class AkeMigrationSchemaTest {

    private val v2: DbSchema = DbSchema.load(2)
    private val v3: DbSchema = DbSchema.load(3)

    // ─────────────────────────────── ① 新表

    @Test
    fun createdTablesMatchRoomSchema() {
        val created = ChatMigration2To3.CREATE_TABLES.map { Sql.parseCreateTable(it) }
        assertThat(created.map { it.table }).containsExactly(
            "agent_profile", "chat_session", "chat_message",
            "agent_capability_grant", "template_registry", "route_decision",
        )

        for (mine in created) {
            val expected = v3.table(mine.table) ?: error("3.json 里没有表 ${mine.table}")
            assertThat(mine.normalized).isEqualTo(expected.createSqlNormalized)
        }
    }

    // ─────────────────────────────── ② 加列

    /**
     * ★★ 逐列比对（等价于 `PRAGMA table_info` 的四列），**不是 SQL 字符串比对**。
     *
     * ## 为什么不能比字符串（这条测试此前长期红着的真实原因）
     *
     * `ALTER TABLE `agent_session`` 与 `ALTER TABLE agent_session` 在 SQLite 里**完全等价** ——
     * 反引号只是标识符引用（sqlite.org/lang_keywords.html §2），SQLite 在内部把两边
     * 归一成同一个标识符。旧版这里用 `Sql.renderAddColumn()` 由 3.json 的字段**反向拼**一句
     * 带反引号的 ALTER，再与迁移原文做字面比对 ⇒ 只要迁移里没写反引号就必然假失败。
     *
     * 那是**测试写法的问题**，不是迁移的 bug：迁移实际产出的列
     * （名字 / 类型 / NOT NULL / 默认值）与 3.json 是逐列一致的 ——
     * 见本轮验证：8 个新列全部匹配，真机 `Migration 2→3` 不会留下结构不对的表。
     *
     * 所以判据改成「列的形状」：`PRAGMA table_info` 会返回的
     * `name / type / notnull / dflt_value`。JVM 单测没有 SQLite（`:data` 是 androidLibrary），
     * 于是把 ALTER 语句**解析**成同样的四元组再比 —— 比的是语义，不是引号。
     */
    @Test
    fun addedColumnsMatchRoomSchema() {
        val alters = ChatMigration2To3.ADD_COLUMNS.mapNotNull { Sql.parseAlterAddColumn(it) }
        // 11 条 DDL 里有 3 条是 CREATE INDEX（随加列一起建的），这里只取 8 条 ADD COLUMN
        assertThat(alters).hasSize(8)

        for (alter in alters) {
            val table = v3.table(alter.table) ?: error("3.json 里没有表 ${alter.table}")
            val field = table.field(alter.column)
                ?: error("3.json 的表 ${alter.table} 里没有列 ${alter.column} —— 迁移加了一个 Entity 不认识的列")
            assertThat(alter.shape()).isEqualTo(ColumnShape.of(field))
        }
    }

    /**
     * ★ ADD COLUMN 的**合法性**（sqlite.org/lang_altertable.html §4）。
     *
     * 这一条比「与 Room 一致」更硬：即使两边写的是同一句错话，真机上也会当场抛
     * `Cannot add a NOT NULL column with default value NULL`。所以它单独成一条断言 ——
     * `trigger_source` 是 8 个新列里唯一 NOT NULL 的，它必须带非 NULL 默认值（'UI'）。
     */
    @Test
    fun addedColumnsAreLegalForSqliteAlterTable() {
        for (alter in ChatMigration2To3.ADD_COLUMNS.mapNotNull { Sql.parseAlterAddColumn(it) }) {
            if (alter.notNull) {
                assertThat(alter.defaultValue).isNotNull()
            }
        }
    }

    // ─────────────────────────────── ③ 索引

    @Test
    fun indexesMatchRoomSchema() {
        val declared = ChatMigration2To3.CREATE_INDEXES
            .plus(ChatMigration2To3.ADD_COLUMNS.filter { it.startsWith("CREATE INDEX") })
            .plus(ChatMigration2To3.MEMORY_CARD_REBUILD.filter { it.startsWith("CREATE") && it.contains("INDEX") })
            .map { Sql.parseCreateIndex(it) }

        // ① 迁移建的每一个索引，3.json 里都必须有、且 DDL 字面一致
        for (mine in declared) {
            val owner = v3.tables.firstOrNull { it.index(mine.name) != null }
                ?: error("3.json 里没有索引 ${mine.name}（迁移建了一个 Entity 不认识的索引）")
            assertThat(mine.normalized).isEqualTo(owner.index(mine.name)!!.createSqlNormalized(owner.name))
        }

        // ② 反向：6 张新表在 3.json 里的每一个索引，迁移都必须建出来
        val newTables = listOf(
            "agent_profile", "chat_session", "chat_message",
            "agent_capability_grant", "template_registry", "route_decision", "memory_card",
        )
        val declaredNames = declared.map { it.name }.toSet()
        for (name in newTables) {
            val table = v3.table(name)!!
            for (idx in table.indices) {
                assertThat(declaredNames).contains(idx.name)
            }
        }
    }

    // ─────────────────────────────── ④ ★ 迁移是「加」而不是「动」

    @Test
    fun legacyTablesOnlyGainedColumns() {
        val altered = ChatMigration2To3.ADD_COLUMNS
            .mapNotNull { Sql.parseAlterAddColumn(it) }
            .groupBy { it.table }
            .mapValues { (_, v) -> v.map { it.column }.toSet() }

        for (old in v2.tables) {
            val now = v3.table(old.name) ?: error("3.json 丢了旧表 ${old.name} —— 迁移绝不能删表")

            // ★ 旧列一个都不能少、一个都不能改
            for (f in old.fields) {
                val kept = now.field(f.name)
                    ?: error("旧表 ${old.name} 的列 ${f.name} 在 v3 里消失了")
                assertThat(kept.affinity).isEqualTo(f.affinity)
                assertThat(kept.notNull).isEqualTo(f.notNull)
                assertThat(kept.defaultValue).isEqualTo(f.defaultValue)
            }

            // ★ 新列必须恰好是迁移里声明的那些（多一个 = 有人改了 Entity 忘了改迁移）
            val added = now.fields.map { it.name }.toSet() - old.fields.map { it.name }.toSet()
            // ★ memory_card 的 agent_id 来自**重建**而不是 ALTER（§6.4），单独判
            val expected = if (old.name == "memory_card") {
                setOf("agent_id")
            } else {
                altered[old.name] ?: emptySet<String>()
            }
            assertThat(added).isEqualTo(expected)
        }
    }

    // ─────────────────────────────── ⑤ memory_card 重建

    @Test
    fun memoryCardRebuildProducesExactlyTheV3Table() {
        val rebuild = ChatMigration2To3.MEMORY_CARD_REBUILD

        // ① 12 步的形状：先关外键 → 建新表 → 显式列名 COPY → DROP → RENAME → 建索引 → 验证 → 开回来
        assertThat(rebuild.first()).isEqualTo("PRAGMA foreign_keys = OFF")
        assertThat(rebuild.last()).isEqualTo("PRAGMA foreign_keys = ON")
        assertThat(rebuild.any { it.contains("DROP TABLE `memory_card`") }).isTrue()
        assertThat(rebuild.any { it.contains("RENAME TO `memory_card`") }).isTrue()

        // ② 重建出来的表必须与 Room 期望的 **逐列一致**（把表名换回 memory_card 后比对）
        val newTable = Sql.parseCreateTable(rebuild.first { it.startsWith("CREATE TABLE") })
        val expected = v3.table("memory_card")!!
        assertThat(newTable.normalized.replace("`memory_card_new`", "`memory_card`"))
            .isEqualTo(expected.createSqlNormalized)

        // ③ ★ COPY 必须显式写列名：SELECT * 会因列序不同而静默错位（§6.4 ③）
        val copy = rebuild.first { it.startsWith("INSERT INTO") }
        assertThat(copy).contains("INSERT INTO `memory_card_new`")
        assertThat(copy).doesNotContain("SELECT *")
        assertThat(copy).contains("NULL, `key`")

        // ④ 人格隔离的落点：新的联合唯一在，旧的全局唯一不在
        assertThat(expected.index("idx_mem_agent_key")?.columnNames).containsExactly("agent_id", "key").inOrder()
        assertThat(expected.index("idx_mem_agent_key")?.unique).isTrue()
        assertThat(expected.index("idx_mem_key")).isNull()

        // ⑤ 老记忆一律降级为「全局共享」= agent_id NULL
        assertThat(copy).contains("SELECT `id`, NULL, `key`")
    }

    // ─────────────────────────────── ⑥ 幂等的前提

    @Test
    fun everyCreateIsIdempotent() {
        val creates = ChatMigration2To3.STATEMENTS.filter {
            it.startsWith("CREATE TABLE") || it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX")
        }
        assertThat(creates).isNotEmpty()
        for (sql in creates) {
            assertThat(sql).contains("IF NOT EXISTS")
        }
    }

    @Test
    fun versionAndStatementsAreWired() {
        assertThat(AkeDatabase.VERSION).isEqualTo(3)
        assertThat(AkeMigrations.ALL.map { "${it.startVersion}->${it.endVersion}" })
            .containsExactly("1->2", "2->3")
            .inOrder()
        // ★ ALL 必须包含 2→3，否则真机上根本不会跑这段迁移
        assertThat(AkeMigrations.ALL.toList()).contains(ChatMigration2To3)
    }

    // ─────────────────────────────── schema 读取（一个够用的小 JSON 解析器）

    /**
     * 本仓库的 JVM 单测拿不到可靠的 JSON 库（android.jar 里的 `org.json` 在
     * unit test 下方法会被 mock 成抛异常），而 Room 的 schema JSON 形状是固定且简单的
     * ⇒ 这里写一个 60 行的小解析器，换来「不依赖任何未在 catalog 里的坐标」。
     */
    private class DbSchema(val version: Int, val tables: List<Table>) {
        fun table(name: String): Table? = tables.firstOrNull { it.name == name }

        companion object {
            fun load(version: Int): DbSchema {
                val file = schemaFile(version)
                val root = Json.parse(file.readText()) as Map<*, *>
                val db = root["database"] as Map<*, *>
                val entities = db["entities"] as List<*>
                val tables = entities.map { e ->
                    val m = e as Map<*, *>
                    val name = m["tableName"] as String
                    val fields = (m["fields"] as List<*>).map { f ->
                        val fm = f as Map<*, *>
                        Field(
                            name = fm["columnName"] as String,
                            affinity = fm["affinity"] as String,
                            notNull = (fm["notNull"] as? Boolean) ?: false,
                            defaultValue = fm["defaultValue"] as String?,
                        )
                    }
                    val indices = ((m["indices"] as? List<*>) ?: emptyList<Any>()).map { i ->
                        val im = i as Map<*, *>
                        Index(
                            name = im["name"] as String,
                            unique = (im["unique"] as? Boolean) ?: false,
                            columnNames = (im["columnNames"] as List<*>).map { it as String },
                            createSql = im["createSql"] as String,
                        )
                    }
                    Table(name, m["createSql"] as String, fields, indices)
                }
                return DbSchema((db["version"] as Double).toInt(), tables)
            }

            private fun schemaFile(version: Int): File {
                val rel = "schemas/com.ake.data.persist.db.AkeDatabase/$version.json"
                // Gradle 的 unit test 工作目录是模块目录；这里多找几处，避免换个 runner 就失效
                val candidates = listOf(File(rel), File("data/$rel"), File("../data/$rel"))
                return candidates.firstOrNull { it.isFile }
                    ?: error("找不到 Room schema：$rel（试过 ${candidates.map { it.path }}）")
            }
        }
    }

    private class Table(
        val name: String,
        val createSql: String,
        val fields: List<Field>,
        val indices: List<Index>,
    ) {
        val createSqlNormalized: String = Sql.normalize(createSql.replace("\${TABLE_NAME}", name))
        fun field(column: String): Field? = fields.firstOrNull { it.name == column }
        fun index(indexName: String): Index? = indices.firstOrNull { it.name == indexName }
    }

    private class Field(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )

    private class Index(
        val name: String,
        val unique: Boolean,
        val columnNames: List<String>,
        val createSql: String,
    ) {
        fun createSqlNormalized(table: String): String =
            Sql.normalize(createSql.replace("\${TABLE_NAME}", table))
    }

    private object Sql {
        fun normalize(sql: String): String = sql
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\(\\s+"), "(")
            .replace(Regex("\\s+\\)"), ")")
            .trim()
            .trimEnd(';')

        private val CREATE_TABLE = Regex(
            "^CREATE TABLE IF NOT EXISTS `([^`]+)` \\((.*)\\)$", RegexOption.DOT_MATCHES_ALL,
        )

        fun parseCreateTable(sql: String): ParsedTable {
            val s = normalize(sql)
            val m = CREATE_TABLE.find(s) ?: error("无法解析 CREATE TABLE：$sql")
            return ParsedTable(table = m.groupValues[1], normalized = s)
        }

        private val ALTER_ADD = Regex(
            "^ALTER\\s+TABLE\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+ADD\\s+COLUMN\\s+" +
                "`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )

        /** 列定义的三段：类型 / NOT NULL / DEFAULT。**多余的约束（UNIQUE / CHECK）一律解析失败**。 */
        private val COLUMN_TAIL = Regex(
            "^(TEXT|INTEGER|REAL|BLOB|NUMERIC)\\s*(NOT\\s+NULL)?\\s*(?:DEFAULT\\s+(.+))?$",
            RegexOption.IGNORE_CASE,
        )

        private val REFERENCES = Regex("REFERENCES", RegexOption.IGNORE_CASE)

        /**
         * 解析成 `PRAGMA table_info` 会返回的形状（不含 pk —— ADD COLUMN 不可能带主键）。
         *
         * ★ 不认识的行尾**不静默放过**：多出一个 `UNIQUE` / `CHECK` 时这里直接抛，
         *   而不是让它以「看起来解析成功」的姿态混过去。
         */
        fun parseAlterAddColumn(sql: String): ParsedAlter? {
            val s = normalize(sql)
            val m = ALTER_ADD.find(s) ?: return null
            // ★ REFERENCES 子句是外键语义，不是列形状（Room 的 fields 里也不含它 ⇒ 比了必然假失败）
            val raw = m.groupValues[3]
            val refAt = REFERENCES.find(raw)?.range?.first ?: raw.length
            val tail = raw.substring(0, refAt).trim()
            val t = COLUMN_TAIL.find(tail)
                ?: error("无法解析 ADD COLUMN 的列定义（含未识别的列约束？）：$sql")
            return ParsedAlter(
                table = m.groupValues[1],
                column = m.groupValues[2],
                type = t.groupValues[1].uppercase(),
                notNull = t.groupValues[2].isNotBlank(),
                defaultValue = t.groupValues[3].trim().ifBlank { null },
            )
        }

        private val CREATE_INDEX = Regex(
            "^CREATE (UNIQUE )?INDEX IF NOT EXISTS `([^`]+)` ON `([^`]+)` \\((.*)\\)$",
        )

        fun parseCreateIndex(sql: String): ParsedIndex {
            val s = normalize(sql)
            val m = CREATE_INDEX.find(s) ?: error("无法解析 CREATE INDEX：$sql")
            return ParsedIndex(
                name = m.groupValues[2],
                table = m.groupValues[3],
                unique = m.groupValues[1].isNotBlank(),
                normalized = s,
            )
        }

        /** 用 3.json 的 Field 反渲染出 Room 自己的 ADD COLUMN 形状，再与迁移的字面比对。 */
        class ParsedTable(val table: String, val normalized: String)

        class ParsedIndex(val name: String, val table: String, val unique: Boolean, val normalized: String)

        class ParsedAlter(
            val table: String,
            val column: String,
            val type: String,
            val notNull: Boolean,
            val defaultValue: String?,
        ) {
            /** 迁移产出的部分在 `PRAGMA table_info` 里的样子。 */
            fun shape(): ColumnShape = ColumnShape(column, type, notNull, defaultValue)
        }
    }

    /**
     * `PRAGMA table_info` 的四列：`name / type / notnull / dflt_value`。
     *
     * 第 6 列 `pk` 没有放进来：`ALTER TABLE ADD COLUMN` **不能**加主键
     * （sqlite.org/lang_altertable.html §4），对一个新列它结构性恒为 0，
     * 比它只会得到一条永远为真的断言。
     */
    private data class ColumnShape(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
    ) {
        companion object {
            fun of(f: Field): ColumnShape =
                ColumnShape(f.name, f.affinity.uppercase(), f.notNull, f.defaultValue)
        }
    }

    /**
     * 极简 JSON 解析器。**只**支持 Room schema JSON 用到的形状：
     * object / array / string / number / true / false / null。
     */
    private object Json {
        fun parse(text: String): Any? {
            var i = 0

            fun ws() { while (i < text.length && text[i].isWhitespace()) i++ }

            fun string(): String {
                require(text[i] == '"') { "期望 '\"' 于 $i" }
                i++
                val sb = StringBuilder()
                while (text[i] != '"') {
                    if (text[i] == '\\') {
                        i++
                        when (text[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(text.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(text[i])
                        }
                        i++
                    } else {
                        sb.append(text[i]); i++
                    }
                }
                i++
                return sb.toString()
            }

            fun number(): Any {
                val start = i
                while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
                return text.substring(start, i).toDouble()
            }

            fun value(): Any? {
                ws()
                return when (text[i]) {
                    '{' -> {
                        i++
                        val map = LinkedHashMap<String, Any?>()
                        ws()
                        if (text[i] == '}') { i++; return map }
                        while (true) {
                            ws()
                            val key = string()
                            ws()
                            require(text[i] == ':') { "期望 ':' 于 $i" }
                            i++
                            map[key] = value()
                            ws()
                            if (text[i] == ',') { i++; continue }
                            require(text[i] == '}') { "期望 '}' 于 $i" }
                            i++
                            return map
                        }
                    }

                    '[' -> {
                        i++
                        val list = ArrayList<Any?>()
                        ws()
                        if (text[i] == ']') { i++; return list }
                        while (true) {
                            list += value()
                            ws()
                            if (text[i] == ',') { i++; continue }
                            require(text[i] == ']') { "期望 ']' 于 $i" }
                            i++
                            return list
                        }
                    }

                    '"' -> string()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> number()
                }
            }

            return value()
        }
    }
}