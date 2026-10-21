package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `ToolPermissionStorePort` 实现 —— **逐工具启停与 Always-Allow**（§8.5 / 数据库 F7）。
 *
 * ## 为什么落 Room 而不是 DataStore
 *
 * 「所有 MCP 工具的 Always Allow 授权必须能在设置里**按工具粒度撤销**」。
 * Proto DataStore 是**整体重写文件**：200 个工具里撤销 1 个要重写整个文件，
 * 低端机上有可感知延迟，且重写过程中崩溃会让整个授权表归零。
 * Room 的单列 UPDATE 是毫秒级、行级原子、天然可审计。
 *
 * ## 读写分离
 *
 * 写走 DAO 直接调用（低频：用户点开关时才发生，不在主循环里）；
 * 读也走 DAO（`catalog()` 是 Flow，给设置页用）。
 * **这一层的写不需要经过 `TraceBufferWriter`** —— 授权不是轨迹数据，
 * 丢了它用户必须立刻知道（所以同步写 + 失败抛）。
 */
public class ToolPermissionStoreImpl(
    private val db: AkeDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ToolPermissionStorePort {

    override suspend fun isEnabled(toolId: ToolId): Boolean =
        runCatching { db.toolRegistryDao().enabledOf(toolId.value) }
            .onFailure { AkeLog.w(TAG, "isEnabled failed: ${it.message}") }
            .getOrNull()?.let { it != 0 } ?: false

    override suspend fun isAlwaysAllow(toolId: ToolId): Boolean =
        runCatching { db.toolRegistryDao().alwaysAllowOf(toolId.value) }
            .onFailure { AkeLog.w(TAG, "isAlwaysAllow failed: ${it.message}") }
            .getOrNull()?.let { it != 0 } ?: false

    override suspend fun grantAlwaysAllow(toolId: ToolId) {
        db.toolRegistryDao().setAlwaysAllow(toolId.value, 1, clock())
    }

    override suspend fun revokeAlwaysAllow(toolId: ToolId) {
        db.toolRegistryDao().setAlwaysAllow(toolId.value, 0, clock())
    }

    override suspend fun setEnabled(toolId: ToolId, enabled: Boolean) {
        db.toolRegistryDao().setEnabled(toolId.value, if (enabled) 1 else 0)
    }

    /**
     * discover 后的全量登记。
     *
     * ★ **保留用户的启停状态**：用 REPLACE 会复活被用户禁用的工具 ——
     * 那等于「每次重新发现就重置一次授权」，是安全问题不是体验问题。
     * 所以先读一次现有状态，合并后再写。
     */
    override suspend fun upsertDiscovered(tools: List<ToolSpec>) {
        if (tools.isEmpty()) return
        val now = clock()
        val dao = db.toolRegistryDao()
        val merged = ArrayList<com.ake.data.persist.db.entity.ToolRegistryEntity>(tools.size)
        for (spec in tools) {
            val existing = runCatching { dao.byId(spec.id.value) }.getOrNull()
            val row = EntityMappers.toolSpecToEntity(spec, now)
            merged += if (existing == null) {
                row
            } else {
                row.copy(
                    enabled = existing.enabled,
                    alwaysAllow = existing.alwaysAllow,
                    grantedAt = existing.grantedAt,
                    riskClassOverride = existing.riskClassOverride,
                    discoveredAt = existing.discoveredAt,
                )
            }
        }
        runCatching { dao.upsertAll(merged) }
            .onFailure { AkeLog.w(TAG, "upsertDiscovered failed: ${it.message}") }
    }

    override fun catalog(): Flow<List<ToolSpec>> =
        db.toolRegistryDao().catalog().map { list -> list.map(EntityMappers::toolSpecToDomain) }

    /** §7.2 硬限制：同时启用 ≤ 12 个。给设置页做「再开就要关掉一个」的提示。 */
    suspend fun enabledCount(): Int =
        runCatching { db.toolRegistryDao().enabledCount() }.getOrDefault(0)

    /** discover 后消失的工具打标（UI 提示「该工具已不可达」）。 */
    suspend fun markMissing(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching { db.toolRegistryDao().markMissing(ids, clock()) }
    }

    companion object {
        const val TAG: String = "ToolPermission"
    }
}
