package com.ake.chat.ports

import com.ake.agent.context.InjectionIsolator
import com.ake.agent.loop.TokenStream
import com.ake.agent.planner.DefaultSystemCharter
import com.ake.agent.planner.SystemCharterText
import com.ake.chat.ctx.ChatHistoryCollector
import com.ake.chat.ctx.ModeProfileRegistry
import com.ake.chat.egress.ChatEgress
import com.ake.chat.egress.LocalOnlyEgressGate
import com.ake.chat.egress.LocalOnlyRoutingPort
import com.ake.chat.loop.ChatLoop
import com.ake.chat.loop.ChatRehydrator
import com.ake.chat.loop.InferenceFocusArbiter
import com.ake.chat.skill.BrowserTaskSkill
import com.ake.chat.skill.ChatSkillRegistry
import com.ake.chat.skill.SkillHandler
import com.ake.core.common.Clock
import com.ake.core.common.RedactionGate
import com.ake.core.modelapi.ModelPort
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.EgressGate
import com.ake.core.ports.HumanGate
import com.ake.core.ports.InferenceFocus
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.RoutingPort
import com.ake.core.ports.TaskRunnerPort
import com.ake.core.ports.TraceStorePort

/**
 * # `:chat` 的装配（对齐 `:agent/ports/UiFacingPorts.kt` 的既有打法）
 *
 * 本模块里「Port 的实现」分两类：
 *
 * | 契约 | 实现 | 说明 |
 * |---|---|---|
 * | `ChatRunnerPort` | [ChatLoop] | 一次轮次的唯一入口 |
 * | `SkillRegistryPort` | [ChatSkillRegistry] | 注册表 + 两级授权 + 二阶防御 |
 * | `InferenceFocus` | [InferenceFocusArbiter] | **进程级单例**，F3 的正确性组件 |
 * | `RoutingPort` | [LocalOnlyRoutingPort] | MVP 恒 `LOCAL_ONLY` |
 * | `EgressGate` | [LocalOnlyEgressGate] | 唯一出口，类型断言 + 拒绝一切出网 |
 * | `ChatSessionStorePort` / `AgentProfileStorePort` / `TemplateStorePort` | **不在本模块** | 已由 `:data` 实现 |
 *
 * ## 为什么这里有一个手写装配类而不是一堆 `@Provides`
 *
 * `:chat` 刻意没有 apply Hilt 插件（见该模块 build 文件）。装配写成**一个普通的工厂**：
 *  - `:app` 侧只需要一行 `ChatServices.create(...)`，不必知道
 *    「`InjectionIsolator` 需要 `PolicyPort`」「`estimate` 来自 `ModelPort.estimateTokens`」
 *    这些内部知识；
 *  - 这些知识一旦散进 `:app` 的 Hilt Module，就会有人在那里改 `maxEnabledSkills`
 *    或者绕过 `ChatEgress` 直接拿 `RoutingPort` —— 而这两个都是**不该有第二个入口**的东西；
 *  - 单测可以直接 `ChatServices.create(fakes...)`，不需要起 Hilt。
 *
 * `@Singleton` / `@Inject` 只标在 [InferenceFocusArbiter]（见其文件）。本类是**显式工厂**：
 * 刻意**不**标 `@Inject constructor` —— 它有三个带默认值的参数（审计 / 流式 / 脱敏闸），
 * 而 Dagger 会忽略默认值、要求为每一个参数提供绑定，于是在 `:app` 里表现为
 * 「少一个 `EgressGate` 绑定就编不过」这种与真实问题无关的报错。
 * 显式构造 + 显式传参，`audit` 传什么就是什么 —— 生产环境传 `AuditSink.NONE` 这件事
 * 应该在手写代码里刺眼地出现，而不是被一个默认值藏起来。
 *
 * ## ★ Hilt 绑定的落点：`:app`（2026-10 补齐）
 *
 * 本类**不是** Hilt 的绑定本身，`:ui` 注入的 `ChatRunnerPort` / `SkillRegistryPort`
 * 由 `:app` 的 `di/ChatPortsModule.kt` 从本实例上取（`services.loop` / `services.skills`）。
 * 三个别名**必须指向同一个 `ChatServices` 单例** —— 否则
 * `InferenceFocusArbiter`（进程级唯一，F3 的正确性组件）与
 * `ChatSkillRegistry.confirmedOnce`（"首次确认"记忆）各自变成两份，
 * 表现是「第二个会话能抢占推理槽」与「每个会话都要重新确认一次」。
 */
public class ChatServices(
    private val model: ModelPort,
    private val health: DeviceHealthPort,
    private val policy: PolicyPort,
    private val sessions: ChatSessionStorePort,
    private val profiles: AgentProfileStorePort,
    private val grants: CapabilityGrantPort,
    private val taskRunner: TaskRunnerPort,
    private val humanGate: HumanGate,
    private val trace: TraceStorePort,
    private val clock: Clock,
    /** ★ 生产必须传 `:data` 的 `RouteDecisionSink` 适配器；传 `NONE` 等于 T12 失守。 */
    private val audit: ChatEgress.AuditSink = ChatEgress.AuditSink.NONE,
    private val tokenStream: TokenStream = TokenStream.SINGLE_SHOT,
    private val redactionGate: RedactionGate = RedactionGate.SCRUB_ONLY,
    /**
     * 全局技能开关（两级授权里的**第一级**）。
     *
     * 默认 [ChatSkillRegistry.GlobalSkillGate.DENY_NON_BUILTIN] 只适用于单测：
     * 生产必须由 `:app` 传一个**查 `tool_registry.enabled`** 的实现
     * （注入 `ToolPermissionStorePort`，见 `ChatSkillRegistry.GlobalSkillGate` 的注释）。
     * 传默认值不会报错，只会让"用户在设置页打开了某个 MCP 工具，聊天里却永远用不上" ——
     * 这正是本轮把 [com.ake.core.ports.CapabilityGrantPort] 实现出来的同一个动机：
     * 授权链上任何一段用默认值兜底，最终表现都是"功能在，但点了没反应"。
     */
    private val globalGate: ChatSkillRegistry.GlobalSkillGate =
        ChatSkillRegistry.GlobalSkillGate.DENY_NON_BUILTIN,
) {

    private val estimate: (String) -> Int = { model.estimateTokens(it) }

    private val isolator: InjectionIsolator = InjectionIsolator(policy)

    private val historyCollector: ChatHistoryCollector = ChatHistoryCollector(estimate)

    public val focus: InferenceFocus = InferenceFocusArbiter(clock)

    public val routing: RoutingPort = LocalOnlyRoutingPort(clock)

    public val egressGate: EgressGate = LocalOnlyEgressGate()

    private val chatEgress: ChatEgress = ChatEgress(routing, egressGate, audit, clock)

    /**
     * 本轮注册的技能 = **只有** `builtin.browser_task`。
     *
     * ## §5.3 的 MCP 工具为什么本轮没有接进来（不是忘了，是刻意留白）
     *
     * 文档 §5.1 的图里 `McpToolSkillAdapter` 是第二根支柱，它需要三件事同时成立：
     *
     *  1. **一个 `SkillHandler` 适配器**：`ToolRegistryPort.invoke(...)` 的入参是
     *     `(ToolId, Map<String, Any?>/JsonObject, ctx)`，而 [SkillCall.args] 是
     *     `Map<String, String>` —— 我们**还没有**决定 args 的 JSON 编解码走哪条路
     *     （`SkillSpec.jsonSchema` 现在是一个未经解析的 `String`）。
     *     把任意 JSON 塞进 `Map<String, String>` 需要在 `:chat` 里引入一个 JSON 库，
     *     而 `:chat` 目前零 JSON 依赖；这个选型要和 `:agent` 的 `StepActionCodec` 对齐，
     *     不能在这里单方面定。
     *  2. **描述文本的单一来源**：`tool_registry` 的 `description` / `schema_hash` 必须与
     *     `SkillSpec` 是同一份（§5.3 表格最后一行）。这要求 `ToolPermissionStorePort`
     *     暴露 `catalog()` 给 `:chat`，而当前 `SkillRegistryPort` 的注册表是**构造期固定**的
     *     `List<SkillHandler>` —— MCP 工具是运行时 `discover` 出来的，注册表需要变成可变 /
     *     可观察的。那是一次结构性改动，不是加一个 handler。
     *  3. **确认策略的仲裁**：`always_allow = 1 ⇒ NEVER`，否则 `FIRST_USE_PER_AGENT`。
     *     聊天侧已经有 `ChatSkillRegistry.confirmedOnce` 这一层进程内记忆，
     *     与 `always_allow` 是**两套**"别再问了"的语义 —— 叠加之前必须先裁决谁说了算，
     *     否则会出现"设置页说永久允许，聊天里每轮又问一次"。
     *
     * 三条都悬着的时候硬接进来，产出的是一个**能被 Router 选中、能过授权、但执行时
     * 参数解析必然失败**的技能 —— 那比不注册更糟：用户看到技能卡片出现然后红掉。
     *
     * ⇒ 本轮只注册内置技能。**加技能请同时更新** `ChatSkillRegistry.MAX_ENABLED_SKILLS`
     * 的 6 个上限说明（§5.4：聊天 SYSTEM cap 只有 1,024 token）。
     */
    private val handlers: List<SkillHandler> = listOf(BrowserTaskSkill(taskRunner, clock))

    public val skills: ChatSkillRegistry = ChatSkillRegistry(
        handlers = handlers,
        grants = grants,
        globalGate = globalGate,
    )

    public val modeProfiles: ModeProfileRegistry = ModeProfileRegistry()

    public val charter: SystemCharterText = DefaultSystemCharter()

    public val rehydrator: ChatRehydrator = ChatRehydrator(sessions, historyCollector, estimate)

    public val loop: ChatLoop = ChatLoop(
        model = model,
        focus = focus,
        store = sessions,
        profiles = profiles,
        skills = skills,
        policy = policy,
        health = health,
        egress = chatEgress,
        humanGate = humanGate,
        traceStore = trace,
        clock = clock,
        tokenStream = tokenStream,
        registry = modeProfiles,
        charter = charter,
        redactionGate = redactionGate,
        rehydrator = rehydrator,
        historyCollector = historyCollector,
        isolator = isolator,
    )
}
