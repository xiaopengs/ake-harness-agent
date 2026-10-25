package com.ake.chat.skill

import com.ake.chat.fixtures.FakeGrants
import com.ake.chat.fixtures.FakeHumanGate
import com.ake.core.common.RedactionGate
import com.ake.core.contract.RiskClass
import com.ake.core.ports.BudgetEnvelope
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ContextWindowFacts
import com.ake.core.ports.SkillCall
import com.ake.core.ports.SkillConfirmPolicy
import com.ake.core.ports.SkillContext
import com.ake.core.ports.SkillCostClass
import com.ake.core.ports.SkillEvent
import com.ake.core.ports.SkillOutcome
import com.ake.core.ports.SkillSpec
import com.ake.core.ports.NoAbortSignal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 两级授权（§5.4）+ 二阶防御（V4）。
 *
 * 这一组测试守的是本项目最重要的一条结构性防御：**授权与文本解耦**。
 * 因此断言全部落在「谁能启用 / 谁能执行」，而不是「模型说了什么」。
 */
class ChatSkillRegistryTest {

    private val browser = skill("browser_task")
    private val other = skill("calc")

    private fun skill(
        id: String,
        risk: RiskClass = RiskClass.PAGE_MUTATING,
        confirm: SkillConfirmPolicy = SkillConfirmPolicy.FIRST_USE_PER_AGENT,
    ) = SkillSpec(
        ref = CapabilityRef.builtin(id),
        name = id,
        description = "$id desc",
        jsonSchema = "{}",
        riskClass = risk,
        costClass = SkillCostClass.CHEAP_SYNC,
        confirmPolicy = confirm,
        maxWallClockMs = 1_000L,
        enabled = true,
    )

    private class Handler(override val spec: SkillSpec, private val emitFailure: Boolean = false) : SkillHandler {
        override suspend fun invoke(call: SkillCall, ctx: SkillContext): Flow<SkillEvent> = flow {
            emit(SkillEvent.Started(call.callId, null))
            if (emitFailure) emit(SkillEvent.Failed("NOPE", "坏了"))
            else emit(
                SkillEvent.Succeeded(
                    SkillOutcome("好了", null, 1, 10, 5, 0, null),
                ),
            )
        }
    }

    private fun context(agentId: String = "agent-1") = SkillContext(
        agentId = agentId,
        sessionId = "s1",
        callId = "call-1",
        turnId = "t1",
        budgetEnvelope = BudgetEnvelope(
            context = ContextWindowFacts("MID", 8_192, 896, 7_296),
            modeProfile = BudgetEnvelope.MODE_PROFILE_TASK_IN_CHAT,
            maxSteps = 25,
            maxWallClockMs = 90_000,
            maxPrefillTokensTotal = 60_000,
            maxDecodeTokensTotal = 512,
        ),
        abort = NoAbortSignal,
        humanGate = FakeHumanGate(),
        redactionGate = RedactionGate.SCRUB_ONLY,
    )

    @Test
    fun grantIsRequiredForEverySkill() {
        val grants = FakeGrants(emptyMap())
        val reg = ChatSkillRegistry(listOf(Handler(browser)), grants)

        assertThat(runBlocking { reg.enabledFor("agent-1") }).isEmpty()
    }

    @Test
    fun grantedAndEnabledSkillIsOffered() {
        val grants = FakeGrants(mapOf(CapabilityRef.builtin("browser_task") to true))
        val reg = ChatSkillRegistry(listOf(Handler(browser)), grants)

        assertThat(runBlocking { reg.enabledFor("agent-1") }.map { it.name }).containsExactly("browser_task")
    }

    @Test
    fun forbiddenRiskIsNeverOfferedEvenIfGranted() {
        val forbidden = skill("pay", risk = RiskClass.FORBIDDEN)
        val grants = FakeGrants(mapOf(CapabilityRef.builtin("pay") to true))
        val reg = ChatSkillRegistry(listOf(Handler(forbidden)), grants)

        // 护栏硬禁止不可撤销：授权层不可能把它开通（§5.4 ③）
        assertThat(runBlocking { reg.enabledFor("agent-1") }).isEmpty()
    }

    @Test
    fun globalGateCanOnlySubtract() {
        // MCP 工具默认关：即使 Agent 级授权了，全局未开也不算启用
        val mcp = SkillSpec(
            ref = CapabilityRef.tool("mcp.local.fs.write"),
            name = "fs_write", description = "", jsonSchema = "{}",
            riskClass = RiskClass.SENSITIVE, costClass = SkillCostClass.CHEAP_SYNC,
            confirmPolicy = SkillConfirmPolicy.ALWAYS, maxWallClockMs = null, enabled = true,
        )
        val grants = FakeGrants(mapOf(mcp.ref to true))
        val reg = ChatSkillRegistry(listOf(Handler(mcp)), grants, ChatSkillRegistry.GlobalSkillGate.DENY_NON_BUILTIN)

        assertThat(runBlocking { reg.enabledFor("agent-1") }).isEmpty()
    }

    @Test
    fun moreThanSixSkillsAreTruncatedDeterministically() {
        val many = (1..9).map { skill("s$it") }
        val grants = FakeGrants(many.associate { it.ref to true })
        val reg = ChatSkillRegistry(many.map { Handler(it) }, grants)

        val enabled = runBlocking { reg.enabledFor("agent-1") }
        assertThat(enabled).hasSize(ChatSkillRegistry.MAX_ENABLED_SKILLS)
        // 按名字排序后截断 ⇒ 可复现（随机截断会让「技能不见了」变成玄学）
        assertThat(enabled.map { it.name }).isInOrder()
        assertThat(enabled.map { it.name }).containsExactly("s1", "s2", "s3", "s4", "s5", "s6")
    }

    @Test
    fun hardCapAboveTwelveIsRejectedAtConstruction() {
        val grants = FakeGrants()
        val e = runCatching {
            ChatSkillRegistry(listOf(Handler(browser)), grants, maxEnabledSkills = 13)
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun duplicateRefsAreRejectedAtConstruction() {
        val e = runCatching {
            ChatSkillRegistry(listOf(Handler(browser), Handler(browser)), FakeGrants())
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun invokeRechecksAuthorizationAtExecutionTime() {
        // ★ 二阶防御：即使有人绕过 UI 直接构造 SkillCall，执行层仍然拒绝
        val reg = ChatSkillRegistry(listOf(Handler(browser)), FakeGrants(emptyMap()))
        val events = runBlocking {
            reg.invoke(SkillCall("call-1", browser.ref, mapOf("instruction" to "x")), context()).toList()
        }
        assertThat(events).hasSize(1)
        assertThat((events.single() as SkillEvent.Failed).code).isEqualTo(ChatSkillRegistry.CODE_DISABLED)
    }

    @Test
    fun unknownSkillFailsInsteadOfThrowing() {
        val reg = ChatSkillRegistry(listOf(Handler(browser)), FakeGrants(mapOf(browser.ref to true)))
        val events = runBlocking {
            reg.invoke(SkillCall("call-1", CapabilityRef.builtin("ghost"), emptyMap()), context()).toList()
        }
        assertThat((events.single() as SkillEvent.Failed).code).isEqualTo(ChatSkillRegistry.CODE_UNKNOWN_SKILL)
    }

    @Test
    fun missingTerminalEventIsBackfilled() {
        // 技能实现漏了终结语时，注册表兜底 —— 否则聊天里会有一张永远转圈的任务卡
        val silent = object : SkillHandler {
            override val spec = other
            override suspend fun invoke(call: SkillCall, ctx: SkillContext): Flow<SkillEvent> =
                flow { emit(SkillEvent.Started(call.callId, null)) }
        }
        val reg = ChatSkillRegistry(listOf(silent), FakeGrants(mapOf(other.ref to true)))
        val events = runBlocking { reg.invoke(SkillCall("c", other.ref, emptyMap()), context()).toList() }

        assertThat(events.last()).isInstanceOf(SkillEvent.Failed::class.java)
        assertThat((events.last() as SkillEvent.Failed).code).isEqualTo(ChatSkillRegistry.CODE_NO_TERMINAL)
    }

    @Test
    fun firstUseConfirmationIsTrackedPerAgent() {
        val reg = ChatSkillRegistry(listOf(Handler(browser)), FakeGrants(mapOf(browser.ref to true)))
        assertThat(reg.requiresConfirm("agent-1", browser)).isTrue()
        reg.markConfirmed("agent-1", browser)
        assertThat(reg.requiresConfirm("agent-1", browser)).isFalse()
        // 另一个 Agent 仍然要确认一次（确认是「每个 Agent 首次」的语义）
        assertThat(reg.requiresConfirm("agent-2", browser)).isTrue()
    }

    @Test
    fun neverAndAlwaysPoliciesAreAbsolute() {
        val reg = ChatSkillRegistry(emptyList(), FakeGrants())
        val never = skill("n", confirm = SkillConfirmPolicy.NEVER)
        val always = skill("a", confirm = SkillConfirmPolicy.ALWAYS)
        assertThat(reg.requiresConfirm("agent-1", never)).isFalse()
        reg.markConfirmed("agent-1", always)
        assertThat(reg.requiresConfirm("agent-1", always)).isTrue()
    }

    @Test
    fun specOfFindsTheHandlerSpec() {
        val reg = ChatSkillRegistry(listOf(Handler(browser)), FakeGrants())
        assertThat(reg.specOf(browser.ref)?.name).isEqualTo("browser_task")
        assertThat(reg.specOf(CapabilityRef.builtin("ghost"))).isNull()
    }
}
