package com.ake.data.persist.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ake.core.common.Clock
import com.ake.core.common.FieldCipher
import androidx.room.withTransaction
import com.ake.data.BuildConfig
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.DialogTargetStore
import com.ake.core.ports.McpServerStorePort
import com.ake.core.ports.MemoryStorePort
import com.ake.core.ports.ModelAssetStorePort
import com.ake.core.ports.ObservationStorePort
import com.ake.core.ports.PolicyDecisionSink
import com.ake.core.ports.SettingsStorePort
import com.ake.core.ports.TaskStorePort
import com.ake.core.ports.TemplateStorePort
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.core.ports.TraceStorePort
import com.ake.core.ports.WipePort
import com.ake.data.persist.agentasset.AgentAssetStore
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.DbTransaction
import com.ake.data.persist.modeldist.ArchiveRetentionSweep
import com.ake.data.persist.db.AkeMigrations
import com.ake.data.persist.db.AkePragmaConfig
import com.ake.data.persist.db.AkePragmaDriver
import com.ake.data.persist.modeldist.ManifestCache
import com.ake.data.persist.modeldist.ManifestTransport
import com.ake.data.persist.modeldist.ManifestTransports
import com.ake.data.persist.modeldist.ManifestTrustStore
import com.ake.data.persist.modeldist.ModelAssetLedger
import com.ake.data.persist.modeldist.ModelFileImporter
import com.ake.data.persist.modeldist.ModelManifestConfig
import com.ake.data.persist.modeldist.ModelManifestConfigFactory
import com.ake.data.persist.modeldist.ModelManifestImporter
import com.ake.data.persist.modeldist.ModelManifestParser
import com.ake.data.persist.modeldist.ModelManifestSource
import com.ake.data.persist.payload.PayloadStore
import com.ake.data.persist.recovery.EgressAuditReconciler
import com.ake.data.persist.recovery.TaskReconciler
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.store.AgentProfileStoreImpl
import com.ake.data.persist.store.CloudCredentialStoreImpl
import com.ake.data.persist.store.DialogTargetStoreImpl
import com.ake.data.persist.store.EgressPermitSinkImpl
import com.ake.data.persist.store.CapabilityGrantStoreImpl
import com.ake.data.persist.store.CheckpointStoreImpl
import com.ake.data.persist.store.ChatSessionStoreImpl
import com.ake.data.persist.store.McpServerStoreImpl
import com.ake.data.persist.store.MemoryStoreImpl
import com.ake.data.persist.store.ModelAssetStoreImpl
import com.ake.data.persist.store.ObservationRow
import com.ake.data.persist.store.ObservationStoreImpl
import com.ake.data.persist.store.PolicyDecisionSinkImpl
import com.ake.data.persist.store.RouteDecisionSink
import com.ake.data.persist.store.SettingsStoreImpl
import com.ake.data.persist.store.StepTraceCodec
import com.ake.data.persist.store.TaskStoreImpl
import com.ake.data.persist.store.TemplateStoreImpl
import com.ake.data.persist.store.ToolPermissionStoreImpl
import com.ake.data.persist.store.TraceStoreImpl
import com.ake.data.persist.write.ChatMessageWriter
import com.ake.data.persist.write.PersistenceGovernor
import com.ake.data.persist.write.PersistenceMetrics
import com.ake.data.persist.write.TaskLivenessRegistry
import com.ake.data.persist.write.TaskStateWriter
import com.ake.data.persist.write.TraceBufferWriter
import com.ake.data.persist.wipe.WipeEverythingUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/** 单线程写执行器（§6.2：串行化所有写事务）。 */
const val DISPATCHER_DB_WRITER: String = "dbWriter"

/**
 * ★★ 模型文件目录。**必须与 `:app` 的 `CoreModule.provideModelsDir` 指向同一个位置。**
 *
 * `:llm` 的 `ModelDownloader.modelsDir` 由 `:app` 装配（`File(ctx.filesDir, "models")`），
 * `:data` 这一份供归档清理扫描与本地模型导入使用。**两处是两次独立的判断，
 * 架构守卫（Gradle 边白名单 + import 黑名单）管不到它们的一致性** ——
 * 唯一能守的是下面这条注释与 `:data` 侧的测试。
 *
 * ⚠ 一旦两边不一致：`.part` 会被删在错误的位置、导入的模型会落在 `:llm` 找不到的地方，
 *   两者都表现为「功能看起来跑了，磁盘/模型却没变化」——最难归因的那类失败。
 *
 * TODO(ownership)：更彻底的修法是把这个路径抬到 `:core:common` 的一个常量里，
 *   由 `:app` 与 `:data` 共同引用（牵涉改 `:core`，本轮边界只允许改 `:data`）。
 */
const val DIR_MODELS: String = "modelsDir"

/** ★ v3：聊天外置目录 `filesDir/chat/` 的 PayloadStore 限定符（与轨迹那一份区分）。 */
const val DIR_CHAT: String = "chatPayloads"

/**
 * `:data` 的装配（数据库 §3.10）。
 *
 * ## 三条刻意的取舍
 *
 *  1. **不用 `setAutoCloseTimeout()`。** Room 2.8.5 有一条相关修复说明：
 *     库关闭后再触发挂起查询会抛 ISE。Agent 任务一跑几分钟、UI 可能长时间不碰 DB，
 *     自动关闭会带来「随机关库 + 随机报错」这种最难复现的问题。
 *     在一个已经有 R1 内存风险的项目里，不为了省几 MB 去换一个随机崩溃源。
 *  2. **不开库不放在 `Application.onCreate()`。** Room 的迁移在首次打开数据库时同步执行，
 *     开库必须走后台线程（迁移耗时属已知 ANR 来源）。Hilt 的 `@Singleton` 懒加载即可。
 *  3. **不允许 `allowMainThreadQueries()`。**
 *
 * ## `StepTraceCodec` 需要 `:app` 绑定
 *
 * 它的实现 `com.ake.agent.planner.AgentStepTraceCodec` 在 `:agent`（L4）。
 * `:data`（L3）不能反向依赖它，所以由 `:app` 提供一行 `@Binds`。
 * **如果忘了绑，构建会失败而不是静默用一个有损默认值** —— 这是刻意选的失败方向。
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class PersistenceBindingsModule {

    @Binds
    @Singleton
    public abstract fun bindTaskStore(impl: TaskStoreImpl): TaskStorePort

    @Binds
    @Singleton
    public abstract fun bindTraceStore(impl: TraceStoreImpl): TraceStorePort

    @Binds
    @Singleton
    public abstract fun bindCheckpointStore(impl: CheckpointStoreImpl): CheckpointStorePort

    @Binds
    @Singleton
    public abstract fun bindObservationStore(impl: ObservationStoreImpl): ObservationStorePort

    @Binds
    @Singleton
    public abstract fun bindPolicySink(impl: PolicyDecisionSinkImpl): PolicyDecisionSink

    @Binds
    @Singleton
    public abstract fun bindToolPermissions(impl: ToolPermissionStoreImpl): ToolPermissionStorePort

    @Binds
    @Singleton
    public abstract fun bindModelAssetStore(impl: ModelAssetStoreImpl): ModelAssetStorePort

    @Binds
    @Singleton
    public abstract fun bindMemoryStore(impl: MemoryStoreImpl): MemoryStorePort

    // ────────────────────────────────────────────────────────────────────────
    // 2026-10：接手 `:ui` 那两个过渡实现的 Port
    //
    // ⚠⚠ **交接给苏映棠**：下面三条绑定一旦生效，`:ui` 的
    //    `UiRuntimeModule.bindMcpServerStore` 与 `bindLocalSettings`
    //    两条 `@Binds` 必须删除，否则同一个 Port 有两个绑定，
    //    Hilt 编译期直接红（`[Dagger/DuplicateBindings]`）。
    //    `bindWipePort` 那条不冲突（`:ui` 侧从未绑过 `WipePort`）——
    //    它要等 `AndroidPrivacyPort` 改成注入 `WipePort` 之后才算完成交接。
    // ────────────────────────────────────────────────────────────────────────

    /**
     * MCP 服务器清单（落 Room `mcp_server` 表）。
     *
     * 替代 `:ui` 的 `McpServerStore`（`filesDir/mcp/servers.json`，org.json 手写）。
     * 落 Room 的理由见 `db/entity/ConfigEntities.kt` 的类注释 —— 最硬的一条是
     * **清除范围只有一处判据**。
     */
    @Binds
    @Singleton
    public abstract fun bindMcpServerStore(impl: McpServerStoreImpl): McpServerStorePort

    /** 本地设置（落 Room `app_settings` 单行表）。替代 `:ui` 的 `UiLocalSettings`。 */
    @Binds
    @Singleton
    public abstract fun bindSettingsStore(impl: SettingsStoreImpl): SettingsStorePort

    // ────────────────────────────────────────────────────────────────────────
    // ★ v4：云端凭证与出境许可（docs/09 §4 / §6）
    //
    // ★★ 装配落 `:data` 而不是 `:app`：`CloudCredentialStoreImpl` 同时需要 DAO 与
    //    `FieldCipher`，两者都在 `:data` ⇒ 放这里**零新增依赖边**，且 `:app` 零改动
    //    （MVP 复用既有 Keystore 别名 `ake.trace.dek.v1`，docs/09 §4.5）。
    //
    //    ⚠ 若 `:app` 侧将来也绑了这两个 Port，Hilt 会直接报 `[Dagger/DuplicateBindings]`
    //      ⇒ 那种情况下删掉 `:app` 那一份，不要删这里的（`WipeEverythingUseCase`
    //      的清除链路依赖本模块能看见 DAO）。
    // ────────────────────────────────────────────────────────────────────────

    /** ★ 凭证生命周期 Port（密文落 `cloud_credential` 表，A5 裁决）。 */
    @Binds
    @Singleton
    public abstract fun bindCloudCredentialStore(
        impl: CloudCredentialStoreImpl,
    ): CloudCredentialPort

    /** ★ 出境许可落点（先落 PENDING 再发字节，fail-closed）。 */
    @Binds
    @Singleton
    public abstract fun bindEgressPermitSink(impl: EgressPermitSinkImpl): EgressPermitSink

    /**
     * ★ 会话级模型目标（落 `chat_session.model_target` 列，**持久化**）。
     *
     * ⚠ 不要换成 `:inference` 的 `InMemoryDialogTargetStore`：那个是**单测替身**，
     *   重启即丢 ⇒ 用户选了云端模型下次冷启动变回端侧（静默降级）。
     */
    @Binds
    @Singleton
    public abstract fun bindDialogTargetStore(impl: DialogTargetStoreImpl): DialogTargetStore

    /**
     * 「一键清除全部痕迹」的执行契约。
     *
     * ★ `:ui` 的 `AndroidPrivacyPort` 注入的就是本契约（[WipePort]），真实现
     *   `WipeEverythingUseCase` 只留在 `:data` 内部被绑上来 —— 它那个
     *   `invoke()` 兼容垫片因此已无调用方，于 2026-10 删除。
     */
    @Binds
    @Singleton
    public abstract fun bindWipePort(impl: WipeEverythingUseCase): WipePort

    // ── ★ v3：聊天化 Agent 的三个 Port

    @Binds
    @Singleton
    public abstract fun bindChatSessionStore(impl: ChatSessionStoreImpl): ChatSessionStorePort

    @Binds
    @Singleton
    public abstract fun bindAgentProfileStore(impl: AgentProfileStoreImpl): AgentProfileStorePort

    @Binds
    @Singleton
    public abstract fun bindTemplateStore(impl: TemplateStoreImpl): TemplateStorePort

    /**
     * ★ v3：Agent 级能力授权（两级授权里的第二级）。
     *
     * 这是 `:core:ports/Chat.kt` 里最后一个没有实现的 Port —— 补上它之后，
     * `ChatSkillRegistry` 才第一次有真实的授权源，而不是靠 `:ui` 的 `@BindsOptionalOf` 兜底。
     *
     * ⚠ 它**只**写 `agent_capability_grant`，不碰 `tool_registry.enabled`。
     *   两级授权取交集，Agent 级只能做减法 —— 见 `CapabilityGrantStoreImpl` 类注释。
     */
    @Binds
    @Singleton
    public abstract fun bindCapabilityGrants(impl: CapabilityGrantStoreImpl): CapabilityGrantPort

    public companion object {

        /**
         * 单线程写执行器。
         *
         * Room 保证同一时刻只用它里的一个线程执行事务，其余排队（数据库事实 12）。
         * **专用 + 单线程 ⇒ 不共享 ⇒ 不会触发「共享 executor 必须无界」的死锁场景。**
         */
        @Provides
        @Singleton
        @Named(DISPATCHER_DB_WRITER)
        @JvmStatic
        public fun provideWriterExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ake-db-writer").apply {
                // 落盘不得与模型推理抢 CPU/IO
                priority = android.os.Process.THREAD_PRIORITY_BACKGROUND
            }
        }

        @Provides
        @Singleton
        @Named("dbReader")
        @JvmStatic
        public fun provideQueryExecutor(): Executor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ake-db-reader").apply {
                priority = android.os.Process.THREAD_PRIORITY_BACKGROUND
            }
        }

        /** writer 协程作用域：SupervisorJob + 单线程 dispatcher（§6.3）。 */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideWriteScope(
            @Named(DISPATCHER_DB_WRITER) writer: Executor,
        ): CoroutineScope = CoroutineScope(SupervisorJob() + writer.asCoroutineDispatcher())

        @Provides
        @Singleton
        @JvmStatic
        public fun provideDatabase(
            @ApplicationContext context: Context,
            health: DeviceHealthPort,
            @Named(DISPATCHER_DB_WRITER) writer: Executor,
            @Named("dbReader") reader: Executor,
        ): AkeDatabase = Room.databaseBuilder(context, AkeDatabase::class.java, AkeDatabase.DB_FILE_NAME)
            // ★ 捆_driver：minSdk 28 的系统 SQLite 低至 3.22，缺 UPSERT/窗口函数/RETURNING（F6）
            .setDriver(AkePragmaDriver(BundledSQLiteDriver(), AkePragmaConfig.forTier(health.memoryTier())))
            // ⚠ 必须显式声明。Room 2.x 默认 AUTOMATIC，行为随 API 等级漂移；此处要确定化。
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setTransactionExecutor(writer)
            .setQueryExecutor(reader)   // 与 setQueryCoroutineContext 互斥，只能设其一
            .addMigrations(*AkeMigrations.ALL)
            .build()

        @Provides
        @Singleton
        @JvmStatic
        public fun providePayloadStore(@ApplicationContext context: Context): PayloadStore =
            PayloadStore(File(context.filesDir, "trace"))

        /**
         * ★ v3：聊天外置目录 `filesDir/chat/`。
         *
         * 与 [providePayloadStore] **同一个类、不同根**（§7.2）：
         * 消息 payload 与 checkpoint envelope 共用这一个根 ⇒ 一次 `purgeAll()`
         * 全覆盖，新增一类落盘物时清除清单不需要跟着改。
         */
        @Provides
        @Singleton
        @Named(DIR_CHAT)
        @JvmStatic
        public fun provideChatPayloadStore(@ApplicationContext context: Context): PayloadStore =
            PayloadStore(File(context.filesDir, "chat"))

        /** ★ v3：人格资产目录 `filesDir/agents/`。只暴露 `purgeAll()`，不引入新的清除机制。 */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideAgentAssetStore(@ApplicationContext context: Context): AgentAssetStore =
            AgentAssetStore(File(context.filesDir, "agents"))

        /**
         * 模型文件目录（见 [DIR_MODELS] 那条「必须与 `:app` 一致」的警告）。
         */
        @Provides
        @Singleton
        @Named(DIR_MODELS)
        @JvmStatic
        public fun provideModelsDir(@ApplicationContext context: Context): File =
            File(context.filesDir, "models").apply { mkdirs() }

        @Provides
        @Singleton
        @JvmStatic
        public fun provideGovernor(): PersistenceGovernor = PersistenceGovernor()

        @Provides
        @Singleton
        @JvmStatic
        public fun provideMetrics(): PersistenceMetrics = PersistenceMetrics()

        @Provides
        @Singleton
        @JvmStatic
        public fun provideLiveness(): TaskLivenessRegistry = TaskLivenessRegistry()

        /**
         * 孤儿对账器。
         *
         * ★ 它是 `TaskStateWriter` 的构造参数之一 ⇒ ** writer 单例一装配就自触发一轮 sweep **
         * （见 `TaskStateWriter.init`）。这是本轮的关键接线：冷启动后第一次触库之前，
         * 遗留的 RUNNING 行就已经被收敛了，UI 永远不会显示一个「正在跑」的死任务。
         *
         * ★ 它**不是** WorkManager 周期任务：那样的周期会被系统按 idle 条件推迟，
         * 而我们需要的是「每次进程起来都先扫一眼」的确定性。
         * 周期性复查可以后续由 `:app` 用 WorkManager 接 `sweep()` 补上（低频，非必需）。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideReconciler(
            db: AkeDatabase,
            liveness: TaskLivenessRegistry,
            clock: Clock,
            metrics: PersistenceMetrics,
        ): TaskReconciler = TaskReconciler(
            taskDao = db.taskDao(),
            checkpointDao = db.checkpointDao(),
            liveness = liveness,
            clock = clock,
            metrics = metrics,
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideRedactionGate(cipher: FieldCipher): PersistenceRedactionGate =
            PersistenceRedactionGate(cipher)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideWriter(
            db: AkeDatabase,
            writeScope: CoroutineScope,
            payloads: PayloadStore,
            governor: PersistenceGovernor,
            metrics: PersistenceMetrics,
        ): TraceBufferWriter = TraceBufferWriter(
            db = db,
            writeScope = writeScope,
            payloads = payloads,
            governor = governor,
            metrics = metrics,
        )

        /**
         * 任务状态类的可靠写入通道。
         *
         * ★ 与 [provideWriter] 用的是**同一个 `writeScope`**（同一个单线程 dispatcher），
         * 这样 §6.2「单写者」这条纪律不会被打破：两条泳道的消费者在同一个线程上
         * 协作式地串行执行，Room 的事务不会互相撞锁。
         * 轨迹那边的 200 ms 合并延迟与这里是互相独立的（各有独立的排水协程）。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideStateWriter(
            db: AkeDatabase,
            writeScope: CoroutineScope,
            governor: PersistenceGovernor,
            metrics: PersistenceMetrics,
            reconciler: TaskReconciler,
        ): TaskStateWriter = TaskStateWriter(
            db = db,
            writeScope = writeScope,
            governor = governor,
            metrics = metrics,
            reconciler = { reconciler.sweep() },
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideTaskStore(
            db: AkeDatabase,
            state: TaskStateWriter,
            gate: PersistenceRedactionGate,
            clock: Clock,
            liveness: TaskLivenessRegistry,
            reconciler: TaskReconciler,
        ): TaskStoreImpl = TaskStoreImpl(
            db = db,
            state = state,
            gate = gate,
            clock = clock,
            liveness = liveness,
            reconciler = reconciler,
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideTraceStore(
            db: AkeDatabase,
            writer: TraceBufferWriter,
            codec: StepTraceCodec,
        ): TraceStoreImpl = TraceStoreImpl(db, writer, codec)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideCheckpointStore(
            db: AkeDatabase,
            state: TaskStateWriter,
            gate: PersistenceRedactionGate,
        ): CheckpointStoreImpl = CheckpointStoreImpl(db, state, gate)

        /** 行反查投影：store 层不认识 Room Entity（§3.1 铁律 1 的延伸）。 */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideObservationStore(
            writer: TraceBufferWriter,
            gate: PersistenceRedactionGate,
            payloads: PayloadStore,
            db: AkeDatabase,
        ): ObservationStoreImpl = ObservationStoreImpl(
            writer = writer,
            gate = gate,
            payloads = payloads,
            findRow = { id -> db.traceDao().observation(id)?.let { ObservationRow(it.taskId, it.payloadFile) } },
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun providePolicySink(
            db: AkeDatabase,
            state: TaskStateWriter,
        ): PolicyDecisionSinkImpl = PolicyDecisionSinkImpl(db, state)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideToolPermissions(db: AkeDatabase): ToolPermissionStoreImpl =
            ToolPermissionStoreImpl(db)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideModelAssetStore(db: AkeDatabase): ModelAssetStoreImpl =
            ModelAssetStoreImpl(db)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideMemoryStore(
            db: AkeDatabase,
            gate: PersistenceRedactionGate,
        ): MemoryStoreImpl = MemoryStoreImpl(db, gate)

        // ──────────────────────────────────────────────────────────────────────
        // 模型分发（manifest 驱动）
        //
        // ★★ **账本装配搬回 `:data`**（交接项 B1）：它此前临时落在 `:app` 的
        //    `ModelDistributionModule`，因为 `AkeDatabase.modelAssetDao()` 需要 Room，
        //    `:app` 为此还多引了一行 `room-runtime`。
        //    账本本来就是 `:data` 的类、Room 本来就在 `:data`，装配它不需要任何
        //    `:app` 才知道的信息 ⇒ 搬回来之后 `:app` 那行 `room-runtime` 可删。
        //
        //    ⚠ 仍然留在 `:app` 的是 `ModelLedgerGateway` 的适配器本身：
        //    那个接口属于 `:llm`，`:data` 不能反向依赖，组合根是唯一能同时看见两侧的地方。
        //    但适配器现在只需要注入本类产出的 `ModelAssetLedger`，不再碰 DAO。
        // ──────────────────────────────────────────────────────────────────────

        /**
         * 归档保留清理的执行者（`retentionPlan` 的生产调用方，见 [ArchiveRetentionSweep]）。
         *
         * ★ 它能删的东西被刻意收得很窄（只有「陈旧 + 无人读写 + 零残留价值」的 `.part`），
         *   README 级的理由全在那个文件的类注释里；这里是唯一的装配点。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideArchiveRetentionSweep(
            db: AkeDatabase,
            @Named(DIR_MODELS) modelsDir: File,
            clock: Clock,
        ): ArchiveRetentionSweep = ArchiveRetentionSweep(
            dao = db.modelAssetDao(),
            modelsDir = modelsDir,
            clock = clock,
        )

        /**
         * 账本。
         *
         * ★★ 归档保留扫描的自触发点在这里：把 [ArchiveRetentionSweep] 做成它的构造参数，
         *   由 `ModelAssetLedger.init` 在 `writeScope` 上拉起一轮。
         *   这与 `TaskStateWriter` 把 `TaskReconciler` 做成构造参数并在自己的 `init` 里自触发
         *   是**同一个形状**（`ColdStartCoordinator` 也是这一形）——
         *   好处是「清不清理」不依赖任何人记得调用它，而 `:app` 不需要为此改一行代码
         *   （它本来就会装配本账本）。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideModelAssetLedger(
            db: AkeDatabase,
            clock: Clock,
            writeScope: CoroutineScope,
            sweep: ArchiveRetentionSweep,
        ): ModelAssetLedger = ModelAssetLedger(
            dao = db.modelAssetDao(),
            clock = clock,
            writeScope = writeScope,
            retentionSweep = { sweep.runOnce() },
        )

        /**
         * manifest 配置。**整条分发链路的开关与信任锚都在这一个对象里。**
         *
         * ## 两个值来自两个不同地方 —— 这不是随意的，见各自的类注释
         *
         *  | 值             | 来源                                            | 改它需要做什么 |
         *  |----------------|-------------------------------------------------|----------------|
         *  | `manifestUrl`  | 构建期注入（`data/build.gradle.kts` → `BuildConfig.MODEL_MANIFEST_URL`） | 改构建环境的一行（换 CDN / 分环境） |
         *  | `pinnedKeys`   | 源码钉死（`ModelManifestPins.ALL`）              | 改代码 + 发版（换签名钥匙） |
         *
         * 判据是「谁有权决定端侧信任什么」：CDN 地址是**部署目标**，可以让部署者改；
         * 公钥是**信任锚本身**，只能随包走（`ModelManifestPins` 类注释写了两条理由）。
         *
         * ## ★★ 注入缺失 / 不可用时的行为：落到 [ModelManifestConfig.UNCONFIGURED]，不崩
         *
         * [ModelManifestConfigFactory] 会对注入值做三道校验（空 / 非 https / 公钥串不可用），
         * 任何一道不过都返回同一个未配置对象 ⇒ [ModelManifestSource] 报
         * `MANIFEST_NOT_CONFIGURED` ⇒ `:llm` 的 `ensureCurrent` 原样上抛 ⇒
         * **连一个 HTTP 请求都不会发出**（`ModelManifestConfigTest` 与 `:llm` 的
         * `ManifestFailClosedTest` 都断言了「传输从未被调用」）。
         *
         * 这条 fail-closed 语义是刻意保留的：它同时也是默认构建（没人注入 URL 时）
         * 的行为 —— **没有可信 manifest 就不下载**，而不是「退去寻找下一个来源」，
         * 也不会因为一次配错就让 App 起不来。
         *
         * ## ⚠ 私钥在哪
         *
         * 不在这个仓库里，也不在构建环境里：签名钥匙的另一半只存在于运维的密钥管理
         * 服务。这里只有**公钥**（公开信息）。构建时需要注入的只有 manifest 地址。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideModelManifestConfig(): ModelManifestConfig =
            ModelManifestConfigFactory.build(manifestUrl = BuildConfig.MODEL_MANIFEST_URL)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideManifestTransport(config: ModelManifestConfig): ManifestTransport =
            ManifestTransports.http(maxBytes = config.maxManifestBytes)

        /** 公钥钉扎：配置里没钉钥匙 ⇒ `TRUST_NOTHING`（任何 manifest 都验不过）。 */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideManifestTrustStore(config: ModelManifestConfig): ManifestTrustStore =
            config.trustStore()

        @Provides
        @Singleton
        @JvmStatic
        public fun provideModelManifestParser(trust: ManifestTrustStore): ModelManifestParser =
            ModelManifestParser(trust)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideManifestCache(@ApplicationContext context: Context): ManifestCache =
            ManifestCache(File(context.filesDir, "modeldist"))

        // ──────────────────────────────────────────────────────────────────
        // 本地人工导入（`ModelFileImporter` / `ModelManifestImporter`）
        //
        // ★★ 交接给苏映棠的接口就是这两个类本身（见各自类注释），本模块只负责把它们
        //    装配成单例并绑到正确的目录上。UI 侧拿到的入口：
        //
        //      @Inject lateinit var manifestImporter: ModelManifestImporter
        //      @Inject lateinit var modelImporter: ModelFileImporter
        //
        //    两者都只依赖 `@ApplicationContext` 的私有存储 ⇒ **不需要任何存储权限**，
        //    也没有引入新的 Gradle 边（`:core:model-api` 仍不在 `:data` 的依赖里）。
        // ──────────────────────────────────────────────────────────────────

        @Provides
        @Singleton
        @JvmStatic
        public fun provideModelFileImporter(@Named(DIR_MODELS) modelsDir: File): ModelFileImporter =
            ModelFileImporter(modelsDir = modelsDir)

        @Provides
        @Singleton
        @JvmStatic
        public fun provideModelManifestImporter(
            parser: ModelManifestParser,
            cache: ManifestCache,
        ): ModelManifestImporter = ModelManifestImporter(parser = parser, cache = cache)

        /**
         * manifest 的唯一来源。**整条分发链路（含 `:llm` 的 `resolve()`）就挂在这个单例上**。
         *
         * ★ 它是「通电」那一格的实体：有了它，`ModelManifest` 才有了生产者，
         *   `ModelResolver.resolve()` 才有了入参 —— 上一轮那条链路被 R8 当成死代码
         *   shrink 掉，根因正是没有这个生产者。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideModelManifestSource(
            config: ModelManifestConfig,
            transport: ManifestTransport,
            parser: ModelManifestParser,
            cache: ManifestCache,
            clock: Clock,
        ): ModelManifestSource = ModelManifestSource(
            config = config,
            transport = transport,
            parser = parser,
            cache = cache,
            clock = clock,
        )

        /**
         * 「一键清除全部痕迹」。
         *
         * ★ 走 Hilt 而不是让调用方自己 `new`：`writer` 必须是**同一个** `TraceBufferWriter`
         * 单例（② 停在途写入停错了队列就白停了），`Context` 必须是 `@ApplicationContext`。
         * 之前 `:ui` 的 `AndroidPrivacyPort` 自己按 Context+Keystore 重写了一份，
         * 与本类重复 —— 那份应改为注入本类（`@Inject` 后 `wipe(deleteModelFiles)` 一行）。
         */
        // ──────────────────────────────────────────────────────────────────────
        // ★ v3：聊天化 Agent（docs/06 §3.9 / §7.2）
        //
        // ChatMessageWriter 由 `@Provides` 提供**单例**（必须同一个单例，
        // 否则「停在途写入」会停错泳道 —— 与 TraceBufferWriter 那条警告同一条）。
        // 三个 Store 走 `@Binds`，与既有 bindTaskStore 同形。
        // ──────────────────────────────────────────────────────────────────────

        @Provides
        @Singleton
        @JvmStatic
        public fun provideChatMessageWriter(
            db: AkeDatabase,
            gate: PersistenceRedactionGate,
            metrics: PersistenceMetrics,
            @Named(DISPATCHER_DB_WRITER) writer: Executor,
        ): ChatMessageWriter = ChatMessageWriter(
            dao = db.chatMessageDao(),
            gate = gate,
            metrics = metrics,
            writerContext = writer.asCoroutineDispatcher(),
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideChatSessionStore(
            db: AkeDatabase,
            writer: ChatMessageWriter,
            gate: PersistenceRedactionGate,
            clock: Clock,
            @Named(DIR_CHAT) chatPayloads: PayloadStore,
        ): ChatSessionStoreImpl = ChatSessionStoreImpl(
            sessions = db.chatSessionDao(),
            messages = db.chatMessageDao(),
            tasks = db.taskDao(),
            agentSessions = db.sessionDao(),
            profiles = db.agentProfileDao(),
            writer = writer,
            gate = gate,
            clock = clock,
            transaction = { block -> db.withTransaction { block() } },
            payloads = chatPayloads,
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideAgentProfileStore(
            db: AkeDatabase,
            gate: PersistenceRedactionGate,
            clock: Clock,
        ): AgentProfileStoreImpl = AgentProfileStoreImpl(
            profiles = db.agentProfileDao(),
            grants = db.capabilityGrantDao(),
            sessions = db.chatSessionDao(),
            messages = db.chatMessageDao(),
            memory = db.memoryDao(),
            gate = gate,
            clock = clock,
            transaction = { block -> db.withTransaction { block() } },
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideTemplateStore(
            db: AkeDatabase,
            gate: PersistenceRedactionGate,
            profiles: AgentProfileStoreImpl,
            clock: Clock,
        ): TemplateStoreImpl = TemplateStoreImpl(
            dao = db.templateDao(),
            gate = gate,
            profiles = profiles,
            grants = db.capabilityGrantDao(),
            clock = clock,
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideRouteDecisionSink(db: AkeDatabase, clock: Clock): RouteDecisionSink =
            RouteDecisionSink(dao = db.routeDecisionDao(), clock = clock)

        /** ★ v4：云端凭证（A5：密文落 Room；MVP 复用既有 Keystore 别名）。 */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideCloudCredentialStore(
            db: AkeDatabase,
            cipher: FieldCipher,
            clock: Clock,
        ): CloudCredentialStoreImpl = CloudCredentialStoreImpl(
            dao = db.cloudCredentialDao(),
            cipher = cipher,
            clock = clock,
        )

        /** ★ v4：审计行的冷启收敛器。 */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideEgressAuditReconciler(
            db: AkeDatabase,
            clock: Clock,
            metrics: PersistenceMetrics,
        ): EgressAuditReconciler = EgressAuditReconciler(
            dao = db.routeDecisionDao(),
            clock = clock,
            metrics = metrics,
        )

        /**
         * ★ v4：会话级模型目标的落点（`chat_session.model_target`）。
         *
         * ★★ 必须**持久化**，不能用 `:inference` 的 `InMemoryDialogTargetStore`：
         *    内存实现重启即丢 ⇒ 用户选了云端模型，下次冷启动静默变回端侧。
         *    那是「用户以为在用 A，实际在用 B」，与本轮红线同一类错误。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideDialogTargetStore(
            db: AkeDatabase,
            clock: Clock,
        ): DialogTargetStoreImpl = DialogTargetStoreImpl(
            sessions = db.chatSessionDao(),
            clock = clock,
        )

        /** ★ v4：出境许可落点。 */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideEgressPermitSink(
            db: AkeDatabase,
            reconciler: EgressAuditReconciler,
            clock: Clock,
        ): EgressPermitSinkImpl = EgressPermitSinkImpl(
            tx = DbTransaction.of(db),
            routeDao = db.routeDecisionDao(),
            sessions = db.chatSessionDao(),
            credentials = db.cloudCredentialDao(),
            reconciler = reconciler,
            clock = clock,
        )

        /**
         * Agent 级能力授权。
         *
         * 只依赖 DAO + Clock，不需要 `PersistenceRedactionGate`：本表**没有任何一列能装正文**
         * （`ref_id` 是能力标识、`kind` 是枚举），因此不存在需要过脱敏闸的字段 ——
         * 与 `agent_profile.persona_charter_enc` 那种必须加密的列不是一类东西。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideCapabilityGrantStore(
            db: AkeDatabase,
            clock: Clock,
        ): CapabilityGrantStoreImpl = CapabilityGrantStoreImpl(
            grants = db.capabilityGrantDao(),
            clock = clock,
        )

        @Provides
        @Singleton
        @JvmStatic
        public fun provideWipeEverything(
            @ApplicationContext context: Context,
            db: AkeDatabase,
            writer: TraceBufferWriter,
            state: TaskStateWriter,
            payloads: PayloadStore,
            memory: MemoryStoreImpl,
            mcpServers: McpServerStorePort,
            settings: SettingsStorePort,
            chatWriter: ChatMessageWriter,
            @Named(DIR_CHAT) chatPayloads: PayloadStore,
            agentAssets: AgentAssetStore,
            credentials: CloudCredentialStoreImpl,
        ): WipeEverythingUseCase = WipeEverythingUseCase(
            context = context,
            db = db,
            writer = writer,
            state = state,
            payloads = payloads,
            memory = memory,
            mcpServers = mcpServers,
            settings = settings,
            chatWriter = chatWriter,
            chatPayloads = chatPayloads,
            agentAssets = agentAssets,
            credentials = credentials,
        )
    }
}
