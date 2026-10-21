package com.ake.data.persist.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ake.core.common.Clock
import com.ake.core.common.FieldCipher
import com.ake.core.ports.CheckpointStorePort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.MemoryStorePort
import com.ake.core.ports.ModelAssetStorePort
import com.ake.core.ports.ObservationStorePort
import com.ake.core.ports.PolicyDecisionSink
import com.ake.core.ports.TaskStorePort
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.core.ports.TraceStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.AkeMigrations
import com.ake.data.persist.db.AkePragmaConfig
import com.ake.data.persist.db.AkePragmaDriver
import com.ake.data.persist.payload.PayloadStore
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.store.CheckpointStoreImpl
import com.ake.data.persist.store.MemoryStoreImpl
import com.ake.data.persist.store.ModelAssetStoreImpl
import com.ake.data.persist.store.ObservationRow
import com.ake.data.persist.store.ObservationStoreImpl
import com.ake.data.persist.store.PolicyDecisionSinkImpl
import com.ake.data.persist.store.StepTraceCodec
import com.ake.data.persist.store.TaskStoreImpl
import com.ake.data.persist.store.ToolPermissionStoreImpl
import com.ake.data.persist.store.TraceStoreImpl
import com.ake.data.persist.write.PersistenceGovernor
import com.ake.data.persist.write.PersistenceMetrics
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

        @Provides
        @Singleton
        @JvmStatic
        public fun provideTaskStore(
            db: AkeDatabase,
            writer: TraceBufferWriter,
            gate: PersistenceRedactionGate,
            clock: Clock,
        ): TaskStoreImpl = TaskStoreImpl(db, writer, gate, clock)

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
            writer: TraceBufferWriter,
            gate: PersistenceRedactionGate,
        ): CheckpointStoreImpl = CheckpointStoreImpl(db, writer, gate)

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
            writer: TraceBufferWriter,
        ): PolicyDecisionSinkImpl = PolicyDecisionSinkImpl(db, writer)

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

        /**
         * 「一键清除全部痕迹」。
         *
         * ★ 走 Hilt 而不是让调用方自己 `new`：`writer` 必须是**同一个** `TraceBufferWriter`
         * 单例（② 停在途写入停错了队列就白停了），`Context` 必须是 `@ApplicationContext`。
         * 之前 `:ui` 的 `AndroidPrivacyPort` 自己按 Context+Keystore 重写了一份，
         * 与本类重复 —— 那份应改为注入本类（`@Inject` 后 `wipe(deleteModelFiles)` 一行）。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideWipeEverything(
            @ApplicationContext context: Context,
            db: AkeDatabase,
            writer: TraceBufferWriter,
            payloads: PayloadStore,
            memory: MemoryStoreImpl,
        ): WipeEverythingUseCase = WipeEverythingUseCase(
            context = context,
            db = db,
            writer = writer,
            payloads = payloads,
            memory = memory,
        )
    }
}
