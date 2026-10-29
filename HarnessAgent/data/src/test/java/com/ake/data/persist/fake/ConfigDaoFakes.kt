package com.ake.data.persist.fake

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.dao.CheckpointDao
import com.ake.data.persist.db.dao.ConfigDao
import com.ake.data.persist.db.dao.MemoryDao
import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.dao.PolicyDao
import com.ake.data.persist.db.dao.SessionDao
import com.ake.data.persist.db.dao.TaskDao
import com.ake.data.persist.db.dao.ToolRegistryDao
import com.ake.data.persist.db.dao.TraceDao
import com.ake.data.persist.db.entity.AppSettingsEntity
import com.ake.data.persist.db.entity.McpServerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * `ConfigDao` 的**内存实现**（不是 mock）。
 *
 * ## 为什么把行级语义在内存里重实现一遍
 *
 * 本轮要守住的正是 §8.5 那条裁决：**按条撤销 = 行级 UPDATE/DELETE，不是整体重写文件**。
 * 这条性质住在 SQL 里，而 JVM 单测跑不了 Room（`:data` 是 androidLibrary，
 * 真库要 Context）。于是这里把两条关键守卫在内存里重新表达：
 *
 *  - [FakeConfigDao.upsertMcpServers] 走**主键覆盖**：只动传入的那几行，兄弟行**原样保留**
 *    —— 这正是 `REPLACE INTO` 与「读全量 → 过滤 → 写全量」的分水岭；
 *  - [FakeConfigDao.deleteMcpServer] 只删命中的那一行，删不到返回 0，且**幂等**。
 *
 * 它验证的不是 SQL 字符串本身（那是真机/instrumented 的事），
 * 而是**调用方与守卫之间的契约**：Store 传进来的到底是「一行」还是「一整份」。
 *
 * ## 关于 `journal`
 *
 * 记录所有写动作的总序，用来断言 `save()` 是「先删后插」而不是「先插后删」
 * —— 后者在进程被杀时会留下孤儿行。
 */
internal class FakeConfigDao : ConfigDao {

    /** `mcp_server` 表。key = 主键 id。 */
    val rows: LinkedHashMap<String, McpServerEntity> = LinkedHashMap()

    /** `app_settings` 单行表。 */
    var settingsRow: AppSettingsEntity? = null

    /** 写动作总序（去掉「读」）。 */
    val journal: MutableList<String> = ArrayList()

    /** 置 true 后所有写操作抛异常，用来验证 Store 的容错分支。 */
    var writesThrow: Boolean = false

    /** `withTransaction` 进入次数（由 [FakeAkeDatabase] 递增）。 */
    private val settingsFlow = MutableStateFlow(0)

    private fun begin(op: String) {
        if (writesThrow) error("simulated DB write failure: $op")
    }

    private fun emit() {
        settingsFlow.value = settingsFlow.value + 1
    }

    override suspend fun mcpServers(): List<McpServerEntity> {
        // 读也走同一开关：本轮用它模拟「一次 DB 故障」，验证 Store 不会把崩溃传给用户
        begin("mcpServers")
        return rows.values.sortedWith(compareBy({ it.updatedAt }, { it.id }))
    }

    override fun observeMcpServers(): Flow<List<McpServerEntity>> =
        settingsFlow.map { mcpServers() }

    override suspend fun mcpServer(id: String): McpServerEntity? = rows[id]

    override suspend fun upsertMcpServers(rows: List<McpServerEntity>) {
        begin("upsertMcpServers")
        // ★ REPLACE 语义：只覆盖出现在这份列表里的主键，其余行一动不动
        rows.forEach { r -> this.rows[r.id] = r }
        journal += "upsert:${rows.map { it.id }}"
        emit()
    }

    override suspend fun deleteMcpServer(id: String) {
        begin("deleteMcpServer")
        journal += if (rows.remove(id) != null) "delete:$id" else "delete-miss:$id"
        emit()
    }

    override suspend fun clearMcpServers() {
        begin("clearMcpServers")
        rows.clear()
        journal += "clear"
        emit()
    }

    override suspend fun settings(rowId: Int): AppSettingsEntity? =
        settingsRow?.takeIf { it.id == rowId }

    override fun observeSettings(rowId: Int): Flow<AppSettingsEntity?> =
        settingsFlow.map { settings(rowId) }

    override suspend fun upsertSettings(row: AppSettingsEntity) {
        begin("upsertSettings")
        settingsRow = row
        journal += "settings-upsert:${row.id}"
        emit()
    }

    override suspend fun clearSettings() {
        begin("clearSettings")
        settingsRow = null
        journal += "settings-clear"
        emit()
    }
}

/**
 * `AkeDatabase` 的测试替身：只提供 [configDao]，其余 DAO 一律抛。
 *
 * ★ `withTransaction` 的三件套被覆盖成空操作 ——
 *   真实的 `RoomDatabase.withTransaction` 会去摸 `SupportSQLiteOpenHelper`，
 *   而 JVM 单测里那是个 Android stub，一碰就抛。
 *   覆盖之后事务的**顺序语义**仍在（同一个协程串行执行），
 *   而 `journal` 仍然记录了真实的调用次序，够用来断言「先删后插」。
 */
internal class FakeAkeDatabase(private val configDao: ConfigDao) : AkeDatabase() {

    var transactionDepth: Int = 0
        private set

    override fun configDao(): ConfigDao = configDao

    override fun beginTransaction() {
        transactionDepth++
    }

    override fun setTransactionSuccessful() = Unit

    override fun endTransaction() {
        transactionDepth--
    }

    override fun sessionDao(): SessionDao = unsupported("sessionDao")
    override fun taskDao(): TaskDao = unsupported("taskDao")
    override fun traceDao(): TraceDao = unsupported("traceDao")
    override fun policyDao(): PolicyDao = unsupported("policyDao")
    override fun checkpointDao(): CheckpointDao = unsupported("checkpointDao")
    override fun memoryDao(): MemoryDao = unsupported("memoryDao")
    override fun modelAssetDao(): ModelAssetDao = unsupported("modelAssetDao")
    override fun toolRegistryDao(): ToolRegistryDao = unsupported("toolRegistryDao")
    // ── v3 聊天 DAO：本轮这两个替身不覆盖聊天路径，一律 unsupported。
    //    ★ 刻意不返回「空实现」：静默的空值会让「覆盖到了」变成假象（见本文件 unsupported 的注释）。
    override fun agentProfileDao(): com.ake.data.persist.db.dao.AgentProfileDao =
        unsupported("agentProfileDao")
    override fun chatSessionDao(): com.ake.data.persist.db.dao.ChatSessionDao =
        unsupported("chatSessionDao")
    override fun chatMessageDao(): com.ake.data.persist.db.dao.ChatMessageDao =
        unsupported("chatMessageDao")
    override fun capabilityGrantDao(): com.ake.data.persist.db.dao.CapabilityGrantDao =
        unsupported("capabilityGrantDao")
    override fun templateDao(): com.ake.data.persist.db.dao.TemplateDao =
        unsupported("templateDao")
    override fun routeDecisionDao(): com.ake.data.persist.db.dao.RouteDecisionDao =
        unsupported("routeDecisionDao")
    // ── v4：云端凭证 DAO（A5 裁决的落点）。本轮这两个替身不覆盖凭证路径 ⇒ unsupported。
    override fun cloudCredentialDao(): com.ake.data.persist.db.dao.CloudCredentialDao =
        unsupported("cloudCredentialDao")


    override fun createOpenHelper(config: DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper =
        unsupported("createOpenHelper")

    override fun createInvalidationTracker(): InvalidationTracker = unsupported("createInvalidationTracker")

    override fun clearAllTables() = unsupported("clearAllTables")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException(
            "FakeAkeDatabase.$name 未被本轮测试覆盖。若断言需要它，请在此处补内存语义，" +
                "而不是让它静默返回一个空值 —— 空值会让「覆盖到了」变成假象。",
        )
}

/**
 * 最小 AkeDatabase 替身（供 `McpServerStoreImpl` / `SettingsStoreImpl` 直接构造用）。
 *
 * 与 [FakeAkeDatabase] 的区别：这个**不覆盖**事务三件套，
 * 用于验证「不走 withTransaction 的路径不需要事务」这一事实。
 */
internal class BareAkeDatabase(private val configDao: ConfigDao) : AkeDatabase() {

    override fun configDao(): ConfigDao = configDao

    override fun sessionDao(): SessionDao = unsupported("sessionDao")
    override fun taskDao(): TaskDao = unsupported("taskDao")
    override fun traceDao(): TraceDao = unsupported("traceDao")
    override fun policyDao(): PolicyDao = unsupported("policyDao")
    override fun checkpointDao(): CheckpointDao = unsupported("checkpointDao")
    override fun memoryDao(): MemoryDao = unsupported("memoryDao")
    override fun modelAssetDao(): ModelAssetDao = unsupported("modelAssetDao")
    override fun toolRegistryDao(): ToolRegistryDao = unsupported("toolRegistryDao")

    // ── v3 聊天 DAO：本替身不覆盖聊天路径，一律 unsupported（理由见 [FakeAkeDatabase] 的同名注释）。
    override fun agentProfileDao(): com.ake.data.persist.db.dao.AgentProfileDao =
        unsupported("agentProfileDao")
    override fun chatSessionDao(): com.ake.data.persist.db.dao.ChatSessionDao =
        unsupported("chatSessionDao")
    override fun chatMessageDao(): com.ake.data.persist.db.dao.ChatMessageDao =
        unsupported("chatMessageDao")
    override fun capabilityGrantDao(): com.ake.data.persist.db.dao.CapabilityGrantDao =
        unsupported("capabilityGrantDao")
    override fun templateDao(): com.ake.data.persist.db.dao.TemplateDao =
        unsupported("templateDao")
    override fun routeDecisionDao(): com.ake.data.persist.db.dao.RouteDecisionDao =
        unsupported("routeDecisionDao")
    // ── v4：云端凭证 DAO（A5 裁决的落点）。本轮这两个替身不覆盖凭证路径 ⇒ unsupported。
    override fun cloudCredentialDao(): com.ake.data.persist.db.dao.CloudCredentialDao =
        unsupported("cloudCredentialDao")

    override fun createOpenHelper(config: DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper =
        unsupported("createOpenHelper")

    override fun createInvalidationTracker(): InvalidationTracker = unsupported("createInvalidationTracker")

    override fun clearAllTables() = unsupported("clearAllTables")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("BareAkeDatabase.$name 未被本轮测试覆盖")
}
