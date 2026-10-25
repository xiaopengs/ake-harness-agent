package com.ake.chat.skill

import com.ake.core.common.AkeLog
import com.ake.core.contract.RiskClass
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.SkillCall
import com.ake.core.ports.SkillConfirmPolicy
import com.ake.core.ports.SkillContext
import com.ake.core.ports.SkillEvent
import com.ake.core.ports.SkillRegistryPort
import com.ake.core.ports.SkillSpec
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * # 技能注册表（§5.1 / §5.4）
 *
 * ## 唯一的授权判据（S3：授权与文本彻底解耦）
 *
 * ```
 * skillEnabled(agentId, ref) =
 *       global.enabled(ref)                 // ① 通用设置页的全局开关
 *   AND agent_capability_grant[agentId][ref] // ② Agent 级开关（默认 false）
 *   AND ref ∉ FORBIDDEN                      // ③ 护栏硬禁止（不可撤销）
 *   AND count(...) ≤ maxEnabledSkills        // ④ 默认 6，硬顶 12
 * ```
 *
 * **必须是交集，不能让 Agent 级「开通」全局未开的工具**：
 * 若 Agent 级能加上东西，一个投毒模板只要在 `capabilityRequests` 里写上 `mcp.local.fs.write`，
 * 用户一路点「下一步」就把文件系统写权限交出去了 —— 而全程没看到过一个「是否允许写文件」的开关。
 * 那样攻击会伪装成「安装一个角色」。
 *
 * ## 为什么上限是 6 而不是既有硬顶 12
 *
 * 聊天的 `SYSTEM` cap 只有 1,024 token，还要装宪章 + persona + disclaimer。
 * 12 是**任务场景**的上限，不是聊天的。超限沿用 `CompositeToolRegistry.enabledLocked()` 的
 * 既有方式：静默截断 + WARN（截断顺序按名字排序，**保证可复现** —— 随机截断会让
 * 「为什么今天浏览器技能不见了」变成玄学）。
 *
 * ## `invoke` 会**再查一次**授权
 *
 * 这是二阶防御（V4 的验收点）：`enabledFor` 决定的是「注入 prompt 的清单」，
 * 而 `invoke` 决定的是「真的执行」。如果只查前者，那么任何能构造 `SkillCall` 的路径
 * （未来的 AppFunctions / 本地 MCP / 甚至一次 prompt 注入让模型拼出未授权的 ref）
 * 都能绕过授权。**第一道是给人看的，第二道才是给攻击者看的。**
 */
public class ChatSkillRegistry(
    private val handlers: List<SkillHandler>,
    private val grants: CapabilityGrantPort,
    private val globalGate: GlobalSkillGate = GlobalSkillGate.DENY_NON_BUILTIN,
    private val maxEnabledSkills: Int = MAX_ENABLED_SKILLS,
) : SkillRegistryPort {

    /** 进程内记忆「这个 Agent 已经确认过这个技能的首次使用」。 */
    private val confirmedOnce = ConcurrentHashMap.newKeySet<String>()

    init {
        require(maxEnabledSkills in 1..HARD_MAX_SKILLS) {
            "maxEnabledSkills 必须落在 1..$HARD_MAX_SKILLS（Q2 裁决：默认 6、硬顶 12），实际 $maxEnabledSkills"
        }
        val dup = handlers.groupBy { it.spec.ref }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "技能 ref 重复注册：$dup —— 同名技能会让路由结果是随机的" }
    }

    override fun all(): Flow<List<SkillSpec>> = flowOf(handlers.map { it.spec })

    override suspend fun enabledFor(agentId: String): List<SkillSpec> {
        val granted = grants.grantsOf(agentId).first()
        val eligible = handlers.map { it.spec }
            .filter { spec ->
                spec.enabled &&
                    spec.riskClass != RiskClass.FORBIDDEN &&
                    globalGate.isEnabled(spec.ref) &&
                    granted[spec.ref] == true
            }
            .sortedBy { it.name }

        if (eligible.size <= maxEnabledSkills) return eligible
        AkeLog.w(
            TAG,
            "agent=$agentId 已启用技能 ${eligible.size} 个，超过上限 $maxEnabledSkills ⇒ 静默截断：" +
                eligible.drop(maxEnabledSkills).map { it.name },
        )
        return eligible.take(maxEnabledSkills)
    }

    override suspend fun invoke(call: SkillCall, ctx: SkillContext): Flow<SkillEvent> = flow {
        val handler = handlers.firstOrNull { it.spec.ref == call.ref }
        if (handler == null) {
            emit(SkillEvent.Failed(CODE_UNKNOWN_SKILL, null))
            return@flow
        }
        // ★ 二阶防御：执行前再查一次（见类注释）
        val allowed = enabledFor(ctx.agentId).any { it.ref == call.ref }
        if (!allowed) {
            AkeLog.w(TAG, "invoke denied: agent=${ctx.agentId} ref=${call.ref.encode()} 未授权或已全局关闭")
            emit(SkillEvent.Failed(CODE_DISABLED, null))
            return@flow
        }
        emitAll(guarded(handler, call, ctx))
    }

    private fun guarded(handler: SkillHandler, call: SkillCall, ctx: SkillContext): Flow<SkillEvent> = flow {
        var terminal = false
        handler.invoke(call, ctx).collect { ev ->
            if (ev is SkillEvent.Succeeded || ev is SkillEvent.Failed) terminal = true
            emit(ev)
        }
        // 「最后一条必须是终结语」这条纪律由注册表兜底：技能实现漏了终结语时，
        // 上层（ChatLoop）会永远等不到结果 —— 那会表现成「任务卡片一直转圈」。
        if (!terminal) emit(SkillEvent.Failed(CODE_NO_TERMINAL, null))
    }

    /** 这个技能对当前 Agent 是否要走一次人工确认（§5.2 / S4：与人设里写了什么无关）。 */
    public fun requiresConfirm(agentId: String, spec: SkillSpec): Boolean = when (spec.confirmPolicy) {
        SkillConfirmPolicy.NEVER -> false
        SkillConfirmPolicy.ALWAYS -> true
        SkillConfirmPolicy.FIRST_USE_PER_AGENT -> confirmKey(agentId, spec.ref) !in confirmedOnce
    }

    /**
     * 记下「已确认过首次使用」。
     *
     * ⚠ **MVP 只在进程内记忆**：重启后会再问一次。这是**安全的失败方向** ——
     * 多问一次只是烦，少问一次是不可撤销的授权；而一个跨进程的「已确认」集合
     * 需要一张新表或一条新 Port，本轮不做（`agent_capability_grant` 表达的是
     * 「这个 Agent 允许用」，与「首次确认已发生」是两件事，混用会把两个语义搅在一起）。
     */
    public fun markConfirmed(agentId: String, spec: SkillSpec) {
        confirmedOnce.add(confirmKey(agentId, spec.ref))
    }

    public fun specOf(ref: CapabilityRef): SkillSpec? = handlers.firstOrNull { it.spec.ref == ref }?.spec

    private fun confirmKey(agentId: String, ref: CapabilityRef): String = "$agentId|${ref.encode()}"

    /** 全局开关的读侧。`:app` 用 `ToolPermissionStorePort` 实现 MCP 那一支。 */
    public fun interface GlobalSkillGate {
        public suspend fun isEnabled(ref: CapabilityRef): Boolean

        public companion object {
            /**
             * 默认：**内置技能开、其余关**。
             *
             * 为什么内置技能不需要一条 `tool_registry` 记录就默认开：它不是「外部能力」，
             * 是我们自己编译进来的两个函数（`browser_task`），既没有第三者声明，
             * 也没有「被发现」这件事。反过来，`MCP_TOOL` / `PLUGIN` 必须查询到显式 enabled 才算开 ——
             * 默认关是唯一能对抗 T11（本机 MCP 未授权调用）的姿态。
             */
            public val DENY_NON_BUILTIN: GlobalSkillGate = GlobalSkillGate {
                it.kind == CapabilityKind.BUILTIN
            }
        }
    }

    public companion object {
        public const val TAG: String = "ChatSkillRegistry"

        /** Q2 裁决：聊天默认 6。 */
        public const val MAX_ENABLED_SKILLS: Int = 6

        /** Q2 裁决：硬顶 12（`AgentProfile.maxEnabledSkills` 的上界）。 */
        public const val HARD_MAX_SKILLS: Int = 12

        public const val CODE_UNKNOWN_SKILL: String = "UNKNOWN_SKILL"
        public const val CODE_DISABLED: String = "SKILL_DISABLED"
        public const val CODE_NO_TERMINAL: String = "SKILL_NO_TERMINAL_EVENT"
    }
}
