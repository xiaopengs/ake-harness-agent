package com.ake.app.di

import com.ake.chat.ports.ChatServices
import com.ake.chat.skill.ChatSkillRegistry
import com.ake.core.common.Clock
import com.ake.core.common.RedactionGate
import com.ake.core.contract.ToolId
import com.ake.core.modelapi.ModelPort
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.ChatRunnerPort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.HumanGate
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.SkillRegistryPort
import com.ake.core.ports.TaskRunnerPort
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.core.ports.TraceStorePort
import com.ake.chat.egress.ChatEgress
import com.ake.data.persist.store.RouteDecisionSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * # 聊天编排的**唯一** Hilt 装配点（`ChatRunnerPort` / `SkillRegistryPort`）
 *
 * ## 这个模块补的是什么缺口
 *
 * `:ui` 的 `ChatWiringModule` 用 `@BindsOptionalOf` 声明了三个 Port 可能不存在，
 * 于是 `ChatViewModel` 注入 `Optional<ChatRunnerPort>`，缺席时降级为「只读历史 + 发送禁用」。
 * 那是**正确的兜底**，不是长期状态：`@BindsOptionalOf` 只声明「可能有」，
 * 图里没有实现时给 `Optional.empty()`，有实现时同一个 `Optional` 自动变成 present，
 * **`:ui` 一行都不用改** —— 本模块就是把「有实现」这一半补上。
 *
 * `@BindsOptionalOf` 与真实绑定**可以共存**：前者绑定的是 `Optional<T>`，
 * 后者绑定的是 `T`，是两个不同的 key，不构成 `[Dagger/DuplicateBindings]`。
 *
 * ## 为什么三个 `@Provides` 都从同一个 `ChatServices` 上取
 *
 * [ChatServices] 是 `:chat` 的手写工厂（理由见其类注释）。它内部创建了三样
 * **必须进程内唯一**的东西：
 *
 * | 对象 | 若被创建两份的后果 |
 * |---|---|
 * | `InferenceFocusArbiter` | 两个轮次都能拿到推理槽 ⇒ 同时往同一个 native Conversation 灌 prompt ⇒ **串字**（F3） |
 * | `ChatSkillRegistry.confirmedOnce` | "首次确认"记忆分裂 ⇒ 每个新会话都要再确认一次 |
 * | `ChatEgress` 的审计通道 | 出云审计分散到两个实例（本轮恒 LOCAL，但链路要求单一） |
 *
 * 所以这里**只** `@Provides ChatServices`，另两个 Port 是它的投影（`services.loop` /
 * `services.skills`）。给 `ChatLoop` / `ChatSkillRegistry` 各写一个 `@Provides`
 * 会真的造出两个实例 —— 因为 `:chat` 没有 `@Inject constructor`，Dagger 无法复用。
 *
 * ## `CapabilityGrantPort` 不在这里
 *
 * 它的实现属于 `:data`（`CapabilityGrantStoreImpl`），由
 * `PersistenceBindingsModule.bindCapabilityGrants` 绑定。组合根不重复绑。
 */
@Module
@InstallIn(SingletonComponent::class)
public object ChatPortsModule {

    /**
     * 从 `:chat` 的显式工厂取出唯一一套编排组件。
     *
     * 构造链上每一个参数都是 `core:ports` 的契约或各模块自己绑好的实现：
     *
     * ```
     * ModelPort            ← :llm      LiteRtModelPort
     * PolicyPort           ← :agent    PolicyEngine（闸 4 的唯一实现）
     * TaskRunnerPort       ← :agent    AgentLoop（browser_task 派生任务走它，AgentLoop 零改动）
     * TraceStorePort       ← :data     TraceStoreImpl
     * ChatSessionStorePort ┐
     * AgentProfileStorePort├← :data    本轮 v3 的四个 Store
     * CapabilityGrantPort  ┘
     * DeviceHealthPort     ← :app      AndroidDeviceHealth（内存分档决定 ChatBudget）
     * HumanGate            ← :ui       UiHumanGate（"人"在 UI 侧，确认必须回到气泡）
     * Clock / RedactionGate← :app      CoreModule
     * ```
     *
     * 任何一个缺席，Dagger 都会在**编译期**报 `[Dagger/MissingBinding]` ——
     * 这是刻意的失败方向：图不完整就不出包，而不是运行时静默降级成"发送没反应"。
     */
    @Provides
    @Singleton
    public fun provideChatServices(
        model: ModelPort,
        health: DeviceHealthPort,
        policy: PolicyPort,
        sessions: ChatSessionStorePort,
        profiles: AgentProfileStorePort,
        grants: CapabilityGrantPort,
        taskRunner: TaskRunnerPort,
        humanGate: HumanGate,
        trace: TraceStorePort,
        clock: Clock,
        redactionGate: RedactionGate,
        /**
         * 全局工具开关（两级授权的第一级）。
         *
         * `ToolPermissionStorePort` 查的是 `tool_registry.enabled` —— 用户在设置页
         * 打开/关闭某个 MCP 工具就是改这一列。**不接它**的后果不是报错，而是
         * 「全局开关永远读不到」：内置技能之外的一切能力恒为关。
         */
        toolPermissions: ToolPermissionStorePort,
        /**
         * 出云审计落点（T12）。
         *
         * `RouteDecisionSink.record(ctx, decision, chatMessageId, payloadBytes, verdict)`
         * 与 `ChatEgress.AuditSink` 的签名逐字对齐，所以适配是一行。
         * 这里**必须**传真实的 sink：传 `AuditSink.NONE` 等于
         * 「路由审计表永远是空的」，而空表无法证明链路通（V5 用例直接失败）。
         */
        routeSink: RouteDecisionSink,
    ): ChatServices = ChatServices(
        model = model,
        health = health,
        policy = policy,
        sessions = sessions,
        profiles = profiles,
        grants = grants,
        taskRunner = taskRunner,
        humanGate = humanGate,
        trace = trace,
        clock = clock,
        audit = ChatEgress.AuditSink { ctx, decision, messageId, bytes, verdict ->
            routeSink.record(ctx, decision, messageId, bytes, verdict)
        },
        // ★ 真流式：`:llm` 的 `ModelSession.generateStream` 已落地（回调经 UNLIMITED Channel
        //   隔离到调用方协程，解码线程不直接碰 collector），这里把接缝接上。
        //   两个签名逐字对齐（`suspend (ModelSession, GenerationRequest, suspend (String)->Unit)`），
        //   所以适配体就是一次转发 —— 没有任何"拆全文假装流式"的空间，这正是 `TokenStream`
        //   被设计成 `fun interface` 而不是回调参数的原因（见该类 KDoc）。
        //   若上游回退到默认 `generateStream` 实现，行为会自动降级为整段交付，UI 无需改。
        tokenStream = com.ake.agent.loop.TokenStream { session, req, onDelta ->
            session.generateStream(req, onDelta)
        },
        // ★ 用 `:data` 落盘同一份脱敏闸（`:app` 的 CoreModule 已绑定，底层 FieldCipher 一致），
        //   而不是 `SCRUB_ONLY` 默认值：技能产出的网页原文要按与消息落盘同一套规则裁。
        redactionGate = redactionGate,
        globalGate = ChatSkillRegistry.GlobalSkillGate { ref ->
            when (ref.kind) {
                // 内置技能是本 APK 编译进来的函数：既没有第三方声明，也没有"被发现"这件事。
                CapabilityKind.BUILTIN -> true
                // MCP 工具：必须 `tool_registry.enabled = 1` 才算开（默认关）。
                // 这是对抗「本机 MCP 未授权调用」的唯一姿态。
                CapabilityKind.MCP_TOOL -> toolPermissions.isEnabled(ToolId(ref.id))
                // 插件本轮只有枚举占位（§5.5），恒关。
                CapabilityKind.PLUGIN -> false
            }
        },
    )

    /** 一轮对话的唯一入口。`:ui` 的 `Optional<ChatRunnerPort>` 从此变成 present。 */
    @Provides
    @Singleton
    public fun provideChatRunnerPort(services: ChatServices): ChatRunnerPort = services.loop

    /** 能力清单 / 授权收敛的唯一入口。`CreateAgentViewModel` 的能力选择器靠它。 */
    @Provides
    @Singleton
    public fun provideSkillRegistryPort(services: ChatServices): SkillRegistryPort = services.skills
}
