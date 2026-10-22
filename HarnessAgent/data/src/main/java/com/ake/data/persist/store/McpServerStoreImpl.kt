package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.McpServerStorePort
import com.ake.core.ports.TransportKind
import com.ake.data.persist.db.AkeDatabase
import androidx.room.withTransaction
import com.ake.data.persist.db.entity.McpServerEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `McpServerStorePort` 的真实现（落 Room，`mcp_server` 表）。
 *
 * ## 它替代的是 `:ui` 那份 `org.json` 过渡实现
 *
 * 那份写在 `filesDir/mcp/servers.json`。换掉的收益不是「更规范」，是三件具体的事：
 *
 *  1. **清除范围回到单一判据**：见 `db/entity/ConfigEntities.kt` 的类注释第 1 条。
 *     配置落进 Room 之后，`WipeEverythingUseCase` 的 ②停在途写入 → ③删库
 *     这条已验证过的路径自动覆盖它，不再需要 ⑤ 那段 `deleteRecursively()` 去猜文件在哪。
 *  2. **`remove` 是行级 DELETE**：过渡实现是「读全量 → 过滤 → 写全量」，
 *     两个并发请求会互相覆盖。Room 的 DELETE 带主键，天然幂等。
 *  3. **清单可以被 `observe`**：UI 不再需要手工 `refresh()`，
 *     与 `TaskStorePort.observeRecent` 同一条理由（`core/ports/Persistence.kt` 已论证）。
 *
 * ## 为什么要容错装载
 *
 * `McpServerConfig` 的 `init` 里 `require` 了 id 非空与 url 必须以 `http(s)` 开头。
 * 库里的行来自上一次写入，**而那一次写入可能来自一个有 bug 的版本**。
 * 若装载时让 `require` 抛出去，后果是「一条脏数据让整个工具页打不开」——
 * 用户连删掉它的机会都没有。所以这里逐行 `runCatching` 并跳过坏行，
 * 同时留一条 WARN：静默丢弃与崩溃之间的正确解是「丢弃并留下证据」。
 */
@Singleton
public class McpServerStoreImpl @Inject constructor(
    private val db: AkeDatabase,
    private val clock: Clock,
) : McpServerStorePort {

    override suspend fun load(): List<McpServerConfig> =
        runCatching { db.configDao().mcpServers() }
            .onFailure { AkeLog.w(TAG, "load failed: ${it.message}") }
            .getOrDefault(emptyList())
            .mapNotNull { row -> toDomain(row) }

    /**
     * 全量覆盖。
     *
     * ★ **先删后插，且在同一个事务里**：`save()` 的语义是「这份清单就是全部」，
     *   用 `REPLACE` 逐条 upsert 会留下「上次有、这次没提」的孤儿行 ——
     *   症状是「用户在设置页删掉了 server，它还在」。
     */
    override suspend fun save(list: List<McpServerConfig>) {
        val now = clock.nowMillis()
        val rows = list.mapIndexed { i, c -> toEntity(c, now + i) }
        runCatching {
            db.withTransaction {
                val dao = db.configDao()
                dao.clearMcpServers()
                if (rows.isNotEmpty()) dao.upsertMcpServers(rows)
            }
        }.onFailure { AkeLog.w(TAG, "save failed: ${it.message}") }
    }

    override suspend fun upsert(config: McpServerConfig) {
        runCatching { db.configDao().upsertMcpServers(listOf(toEntity(config, clock.nowMillis()))) }
            .onFailure { AkeLog.w(TAG, "upsert failed: ${it.message}") }
    }

    override suspend fun remove(id: String) {
        runCatching { db.configDao().deleteMcpServer(id) }
            .onFailure { AkeLog.w(TAG, "remove failed: ${it.message}") }
    }

    override suspend fun clearAll() {
        runCatching { db.configDao().clearMcpServers() }
            .onFailure { AkeLog.w(TAG, "clearAll failed: ${it.message}") }
    }

    // ──────────────────────────────────────── 映射

    /**
     * 行 → 领域对象。
     *
     * @return `null` 表示该行不合法（见类注释「为什么要容错装载」），调用方跳过它。
     */
    internal fun toDomain(row: McpServerEntity): McpServerConfig? = runCatching {
        McpServerConfig(
            id = row.id,
            label = row.label,
            url = row.url,
            headers = FlatJson.decodeMap(row.headersJson),
            enabled = row.enabled != 0,
            transport = runCatching { TransportKind.valueOf(row.transport) }
                .getOrDefault(TransportKind.STREAMABLE_HTTP),
            callTimeoutMs = row.callTimeoutMs,
            connectTimeoutMs = row.connectTimeoutMs,
            maxReconnectAttempts = row.maxReconnectAttempts,
        )
    }.onFailure { AkeLog.w(TAG, "skip invalid mcp_server row id=${row.id}: ${it.message}") }
        .getOrNull()

    /**
     * 领域对象 → 行。
     *
     * @param updatedAt 显式传入而不是在内部取 `clock`：批量写入时它要单调递增，
     *                  否则所有行的 `updated_at` 相同 ⇒ `ORDER BY updated_at` 退化成不确定顺序
     *                  ⇒ UI 列表在每次装载后跳来跳去。
     */
    internal fun toEntity(c: McpServerConfig, updatedAt: Long): McpServerEntity = McpServerEntity(
        id = c.id,
        label = c.label,
        url = c.url,
        headersJson = FlatJson.encodeMap(c.headers),
        enabled = if (c.enabled) 1 else 0,
        transport = c.transport.name,
        callTimeoutMs = c.callTimeoutMs,
        connectTimeoutMs = c.connectTimeoutMs,
        maxReconnectAttempts = c.maxReconnectAttempts,
        updatedAt = updatedAt,
    )

    public companion object {
        public const val TAG: String = "McpServerStore"
    }
}
