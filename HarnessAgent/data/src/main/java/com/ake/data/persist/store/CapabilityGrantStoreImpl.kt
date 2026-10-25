package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.data.persist.db.dao.CapabilityGrantDao
import com.ake.data.persist.db.entity.AgentCapabilityGrantEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `CapabilityGrantPort` 的实现 —— **两级授权里的第二级（Agent 级）**（§5.4 / 数据库 §3.2）。
 *
 * ## ★ 本类只能做减法，这一点是结构性的而不是纪律性的
 *
 * `SkillRegistry` 的判据是交集：
 * ```
 * skillEnabled(agentId, ref) =
 *       tool_registry.enabled(ref)            // ① 全局开关，归 ToolPermissionStorePort
 *   AND agent_capability_grant[agentId][ref]  // ② 本类
 *   AND ref ∉ FORBIDDEN
 *   AND count ≤ maxEnabledSkills
 * ```
 * 本类**没有**任何通往 ① 的写口：`setGranted(true)` 只写 `agent_capability_grant` 一行，
 * 既不改 `tool_registry.enabled`，也不创建 `tool_registry` 行。
 *
 * 因此「一个投毒模板在 `capabilityRequests` 里写上 `mcp.local.fs.write`」不会开通任何东西 ——
 * 它最多在**用户已经在通用设置页开过**的能力里做筛选。这是 §5.4 最重要的一条结构性防御：
 * **新增许可只能发生在通用设置页**（那里有风险徽标与「这是一次工具授权」的明确语义）。
 *
 * ⚠ 如果哪天有人为了「让模板装完就能用」在这里补一句 `toolRegistryDao().setEnabled(...)`，
 * §5.4 就整条失效，且失效方式是静默的（功能照常工作，只是护栏没了）。
 *
 * ## ★ 表里只存 `granted = 1` 的行 ⇒ `setGranted(false)` 是 DELETE 而不是写 0
 *
 * 「无行」与 `granted = 0` 在 §5.4 的公式里结果完全相同。存 0 值行只会带来两件事：
 * ① 表随「全局工具数 × Agent 数」膨胀；② 出现两种表达同一语义的写法 ——
 * 而「有两种写法」是所有漂移 bug 的起点。契约侧不必改动：
 * [grantsOf] 只对**存在**的 ref 返回 `true`，调用方照旧写 `map[ref] == true`。
 *
 * ## ★★ 本类的 `agentId` 参数是 `agent_profile.id`（ULID），不是对外短名
 *
 * `agent_capability_grant.agent_id` 的外键是 `REFERENCES agent_profile(id)`
 * （见 `ChatMigration2To3` 的建表语句与 `AgentCapabilityGrantEntity` 的 `ForeignKey`），
 * 而 `agent_profile` 同时还有一列**对外短名** `agent_id`（`ag-xxxxxxxxxx`，MCP / AppFunctions 用它寻址）。
 * 两个 id 空间同名不同义，是本轮最容易接错的一处：
 *  - 传短名 ⇒ 外键失败（`PRAGMA foreign_keys = ON`，见 `AkePragmaDriver`）；
 *  - 传短名且外键恰好被关掉 ⇒ 写成功，但 `ChatLoop` 用 `chat_session.agent_id`（主键）
 *    去查时永远是空 map ⇒ **技能全部静默不可用**，日志里只有一句「未授权」。
 *
 * `ChatLoop` 传进来的正是 `ChatSessionSummary.agentId`，而该列同样是主键空间 ⇒ 两边一致。
 * 写 UI 的授权开关时请传 `AgentProfileSummary.id`，不要传 `.agentId`。
 *
 * ## 写失败为什么不返回 Outcome
 *
 * `CapabilityGrantPort` 的契约签名是 `suspend fun setGranted(...)`（无返回值），
 * 与 `ToolPermissionStorePort` 一致。用户点一个开关后界面立刻回读 [grantsOf]，
 * 写没写进去是**可见的**；这里只补一条 WARN 供排障，不擅自改变契约。
 */
public class CapabilityGrantStoreImpl internal constructor(
    private val grants: CapabilityGrantDao,
    private val clock: Clock,
) : CapabilityGrantPort {

    /**
     * 授予 / 撤销。
     *
     * `granted = true` 走 `INSERT OR REPLACE`，但 **`granted_at` 保留首次授权时间**：
     * 那是「这个 Agent 什么时候被交给这项能力」的唯一证据，重开一次开关不该抹掉它。
     * 代价是一次读（该表每个 Agent 至多几十行，且这是人手点击频率，不在主循环上）。
     */
    override suspend fun setGranted(agentId: String, ref: CapabilityRef, granted: Boolean) {
        if (agentId.isBlank()) {
            AkeLog.w(TAG, "setGranted ignored: 空 agentId（ref=${ref.encode()}）")
            return
        }
        val now = clock.nowMillis()
        runCatching {
            if (granted) {
                val firstGrantedAt = grants.grantedRows(agentId)
                    .firstOrNull { it.kind == ref.kind.name && it.refId == ref.id }
                    ?.grantedAt
                    ?: now
                grants.grant(
                    AgentCapabilityGrantEntity(
                        agentId = agentId,
                        kind = ref.kind.name,
                        refId = ref.id,
                        // ★ 恒为 1：本表不存 0 值行（见类注释）。
                        granted = 1,
                        grantedAt = firstGrantedAt,
                        updatedAt = now,
                    ),
                )
            } else {
                grants.revoke(agentId, ref.kind.name, ref.id)
            }
        }.onFailure {
            AkeLog.w(TAG, "setGranted(${ref.encode()}, granted=$granted) failed agent=$agentId: ${it.message}")
        }
    }

    /**
     * 该 Agent 的授权集合（**只含被授予的 ref**）。
     *
     * `kind` 反解失败的行直接丢弃并 WARN：一个未知的 `kind` 只能来自
     * ① 未来版本写入后降级安装，或 ② 手工改库。两种情况下「当作未授权」都是安全方向
     * （少一个能力，而不是多一个）。
     */
    override fun grantsOf(agentId: String): Flow<Map<CapabilityRef, Boolean>> =
        grants.observe(agentId).map { rows ->
            val out = LinkedHashMap<CapabilityRef, Boolean>(rows.size)
            for (row in rows) {
                if (row.granted == 0) continue
                val kind = runCatching { CapabilityKind.valueOf(row.kind) }.getOrNull()
                if (kind == null) {
                    AkeLog.w(TAG, "grant 行 kind 无法识别，已忽略：agent=${row.agentId} kind=${row.kind}")
                    continue
                }
                out[CapabilityRef(kind, row.refId)] = true
            }
            out
        }

    /**
     * 已授予数量。
     *
     * 给设置页做「已选 N / 6」的提示用。**不是**限额的强制执行点 ——
     * 真正截断发生在 `ChatSkillRegistry.enabledFor()` 的读取侧（静默截断 + WARN），
     * 与 `AgentProfileStoreImpl.replaceGrants` 的注释一致：
     * Store 不该替用户决定「超了 6 个该丢哪个」。
     *
     * ⚠ 已消失的工具（孤儿 grant 行）**也占坑** —— 这就是 §9-U7 那条清理约定的动机。
     */
    public suspend fun grantedCount(agentId: String): Int =
        runCatching { grants.countOf(agentId) }
            .onFailure { AkeLog.w(TAG, "countOf failed agent=$agentId: ${it.message}") }
            .getOrDefault(0)

    public companion object {
        public const val TAG: String = "CapabilityGrantStore"
    }
}
