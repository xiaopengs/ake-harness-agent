package com.ake.agent.di

import com.ake.agent.context.ContextAssembler
import com.ake.agent.context.InjectionIsolator
import com.ake.agent.context.PocketDomSerializer
import com.ake.agent.guard.ContentSanitizer
import com.ake.agent.guard.DomainPolicy
import com.ake.agent.guard.PolicyEngine
import com.ake.agent.loop.AgentLoop
import com.ake.agent.loop.DefaultVerifier
import com.ake.agent.loop.TokenStream
import com.ake.agent.loop.Verifier
import com.ake.agent.planner.DefaultSystemCharter
import com.ake.agent.planner.StepActionParser
import com.ake.agent.planner.StepPromptBuilder
import com.ake.agent.planner.SystemCharterText
import com.ake.agent.tools.CompositeToolRegistry
import com.ake.agent.tools.LocalToolHost
import com.ake.agent.tools.LocalToolSource
import com.ake.agent.tools.ResultCollector
import com.ake.agent.tools.ToolSource
import com.ake.core.common.Clock
import com.ake.core.contract.RiskClass
import com.ake.core.contract.ToolId
import com.ake.core.modelapi.ModelPort
import com.ake.core.ports.BrowserExecutorPort
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.DomPerceptionPort
import com.ake.core.ports.HumanGate
import com.ake.core.ports.MemoryStorePort
import com.ake.core.ports.ObservationStorePort
import com.ake.core.ports.PolicyDecisionSink
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.TaskStorePort
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.core.ports.ToolRegistryPort
import com.ake.core.ports.TraceStorePort
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `:agent` 的装配（架构 §2.1：业务规则归各自模块，组合根只做绑定）。
 *
 * ## `ToolSource` 的装配方式（分层约束的直接后果）
 *
 * `:mcp`（L3）不能反向依赖 `:agent`（L4），所以 MCP 侧的实现要由 `:app` 用
 * 一个 6 行桥接类塞进 `Set<ToolSource>`（见 `mcp/MCP_BRIDGE.md`）。
 * **本模块不引用任何 MCP 符号** —— 这条约束保证换掉整个 MCP 实现时 L4 一行不用改。
 *
 * `Set<ToolSource>` 为空时 Hilt 会给空集，此时注册表里只有内置本地工具，主链路照样能跑。
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class AgentBindingsModule {

    @Binds
    @Singleton
    public abstract fun bindSystemCharter(impl: DefaultSystemCharter): SystemCharterText

    @Binds
    @Singleton
    public abstract fun bindVerifier(impl: DefaultVerifier): Verifier

    @Binds
    @Singleton
    public abstract fun bindPolicyPort(impl: PolicyEngine): PolicyPort

    @Binds
    @Singleton
    public abstract fun bindToolRegistry(impl: CompositeToolRegistry): ToolRegistryPort

    @Binds
    @Singleton
    public abstract fun bindLocalToolHost(impl: ResultCollector): LocalToolHost

    public companion object {

        @Provides
        @Singleton
        @JvmStatic
        public fun provideParser(): StepActionParser = StepActionParser()

        @Provides
        @Singleton
        @JvmStatic
        public fun provideSanitizer(): ContentSanitizer = ContentSanitizer()

        @Provides
        @Singleton
        @JvmStatic
        public fun provideDomainPolicy(): DomainPolicy = DomainPolicy.DEFAULT

        /**
         * nonce 每进程随机：固定 nonce 会让网页内容有机会伪造闭合标记提前跳出数据区
         * （`InjectionIsolator` 注释里的攻击手法是真实存在的，不是理论风险）。
         *
         * 引用形态是**顶层** `NonceSource`，不是 `InjectionIsolator.NonceSource` ——
         * 后者在 Kotlin 里解析不到（该类型原本嵌在 `companion object` 里，
         * 外部只能写成 `InjectionIsolator.Companion.NonceSource`）。已提到顶层根治。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideIsolator(policy: PolicyPort): InjectionIsolator =
            InjectionIsolator(sanitizer = policy, nonce = NonceSource.random())

        @Provides
        @Singleton
        @JvmStatic
        public fun provideDomSerializer(): PocketDomSerializer = PocketDomSerializer()

        /**
         * `estimate` 必须是**本地估算** —— 整个上下文预算机制依赖于此。
         * 如果这一步要问模型才能知道一段文本多贵，预算反馈回路的时间常数就会超过单步成本，
         * 整个「压缩 ⇄ 预算」的体系就塌了。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideContextAssembler(
            model: ModelPort,
            isolator: InjectionIsolator,
            serializer: PocketDomSerializer,
            charter: SystemCharterText,
        ): ContextAssembler = ContextAssembler(
            estimate = model::estimateTokens,
            isolator = isolator,
            domSerializer = serializer,
            charter = charter,
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideStepPromptBuilder(assembler: ContextAssembler): StepPromptBuilder =
            StepPromptBuilder(assembler)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideResultCollector(): ResultCollector = ResultCollector()

        @Provides
        @Singleton
        @JvmStatic
        public fun provideLocalToolSource(
            host: LocalToolHost,
            memory: MemoryStorePort,
        ): LocalToolSource = LocalToolSource(host = host, memory = memory)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideCompositeToolRegistry(
            sources: Set<@JvmSuppressWildcards ToolSource>,
            local: LocalToolSource,
        ): CompositeToolRegistry = CompositeToolRegistry(
            sources = sources + local,
            maxEnabledTools = CompositeToolRegistry.DEFAULT_MAX_ENABLED,
        )

        /**
         * 护栏。`toolRisk` 刻意**延迟解析**：护栏要知道某个 MCP 工具声明的风险等级，
         * 而工具目录是运行时才发现的东西，不能在构造期固定。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun providePolicyEngine(
            clock: Clock,
            sink: PolicyDecisionSink,
            permissions: ToolPermissionStorePort,
            registry: CompositeToolRegistry,
            domainPolicy: DomainPolicy,
            sanitizer: ContentSanitizer,
        ): PolicyEngine = PolicyEngine(
            domainPolicy = domainPolicy,
            clock = clock,
            sink = sink,
            toolPermissions = permissions,
            toolRisk = { id: ToolId -> registry.declaredRisk(id) ?: RiskClass.PAGE_MUTATING },
            sanitizer = sanitizer,
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideAgentLoop(
            model: ModelPort,
            perception: DomPerceptionPort,
            browser: BrowserExecutorPort,
            tools: CompositeToolRegistry,
            guard: PolicyPort,
            verifier: Verifier,
            promptBuilder: StepPromptBuilder,
            parser: StepActionParser,
            trace: TraceStorePort,
            observations: ObservationStorePort,
            checkpoints: CheckpointStorePort,
            tasks: TaskStorePort,
            health: DeviceHealthPort,
            humanGate: HumanGate,
            clock: Clock,
            collector: ResultCollector,
        ): AgentLoop = AgentLoop(
            model = model,
            perception = perception,
            browser = browser,
            tools = tools,
            guard = guard,
            verifier = verifier,
            promptBuilder = promptBuilder,
            parser = parser,
            trace = trace,
            observationStore = observations,
            checkpointStore = checkpoints,
            taskStore = tasks,
            health = health,
            humanGate = humanGate,
            clock = clock,
            collector = collector,
        )
    }
}
