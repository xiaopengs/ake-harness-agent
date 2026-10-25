package com.ake.app.di

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import com.ake.agent.planner.AgentStepTraceCodec
import com.ake.core.common.Clock
import com.ake.core.common.FieldCipher
import com.ake.core.common.RedactionGate
import com.ake.core.common.SystemClock
import com.ake.core.modelapi.ModelPort
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.ModelLifecyclePort
import com.ake.llm.LiteRtModelLifecycle
import com.ake.llm.LiteRtModelPort
import com.ake.llm.ModelCatalog
import com.ake.llm.ModelDownloader
import com.ake.data.persist.store.StepTraceCodec
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

// ──────────────────────────────────────────────────────────────
// 限定符
// ────────────────────────────────────────────────────────────────

/** 端侧推理专用单线程 dispatcher（LiteRT-LM 非线程安全）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class InferenceDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class DefaultDispatcher

// ──────────────────────────────────────────────────────────────
// 原语
// ────────────────────────────────────────────────────────────────

/**
 * 全局装配。
 *
 * ★ **只写装配，不写别人的实现**：这里只把「接口」绑到「各模块自己提供的实现类」，
 * 不含任何业务规则。业务规则归各自的 L3/L4 模块。
 *
 * 各模块的 Port 实现在它们**自己的** Hilt 模块里绑定，本文件不重复绑、也不占位：
 *
 * ```
 *   :data  → PersistenceModule        会话 / 人格 / 模板 / 能力授权 / 轨迹 / 任务
 *   :ui    → UiRuntimeModule           HumanGate / PrivacyPort / ToolShutdownPort
 *   :ui    → ChatWiringModule          @BindsOptionalOf（声明"可能有"，不是实现）
 *   :mcp   → app/di/McpHiltModule      McpConnectionPort / ToolPermissionStorePort
 * ```
 *
 * ★ 2026-10 修正：这里原文写「尚未落地的模块（`:mcp` / `:data` / `:ui` 的 Port 实现）」。
 *   三者现在**全部**已有绑定 —— 留着一句"尚未落地"，下一个人就会去找一个不存在的缺口，
 *   或者更糟：据此以为某个 Port 是可选依赖。（关千锤 P1-5：已知错误的注释比没有注释更糟。）
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class CoreBindingsModule {

    /** 推理引擎：LiteRT-LM 封装 → ModelPort */
    @Binds
    @Singleton
    public abstract fun bindModelPort(impl: LiteRtModelPort): ModelPort

    /** 模型生命周期：下载 → 校验 → 加载 → 卸载 */
    @Binds
    @Singleton
    public abstract fun bindModelLifecycle(impl: LiteRtModelLifecycle): ModelLifecyclePort

    /**
     * 轨迹编解码：`StepTraceCodec` 是 `:data` 定义的接口（存储侧），
     * 实现 `AgentStepTraceCodec` 在 `:agent`（动作 DSL 只有 planner 认识）。
     * 组合根是唯一能同时看见两者的地方，所以这条绑定只能写在这里 ——
     * 缺了它 `:data` 的 TraceStore 拿不到 codec，落盘轨迹会整体退化为空。
     */
    @Binds
    @Singleton
    public abstract fun bindStepTraceCodec(impl: AgentStepTraceCodec): StepTraceCodec
}

@Module
@InstallIn(SingletonComponent::class)
public object ProvidesModule {

    /**
     * ★ **无注解** `Context`（与 Hilt 内置的 `@ApplicationContext Context` 是两个不同的 key）。
     *
     * ## 为什么需要它
     *
     * `LiteRtModelPort` 的构造参数是**不带任何限定符**的 `Context`（它按「Application
     * 的 Context」语义使用：`applicationInfo.nativeLibraryDir` 找 NPU 库、`cacheDir`
     * 放 LiteRT 缓存）。Hilt 只内置了 `@ApplicationContext Context`，
     * 无注解 `Context` 没有任何绑定 ⇒ `hiltJavaCompileDebug` 直接报
     * `[Dagger/MissingBinding] android.content.Context`。
     *
     * ## 语义边界（别绑错）
     *
     * - 这里绑的一定是 **Application Context**，不是 Activity Context：
     *   本模块 `InstallIn(SingletonComponent)`，单例比任何 Activity 活得久，
     *   绑 Activity Context 就是稳定的内存泄漏。
     * - 与 `@ApplicationContext` 指向**同一个对象**，只是换了个 key。
     *   不改 `LiteRtModelPort` 的签名，是因为那是 `:llm` 的地盘（边界约束）。
     *
     * ## ⚠ 已知 footgun（留给后来人）
     *
     * 有了这条绑定之后，**任何地方请求无注解 `Context` 都会拿到 Application Context**，
     * 包括那些本意想要 Activity Context 的地方 —— 而后者不会报错，只会静默拿错。
     * 需要 Activity Context 时必须显式写 `@ActivityContext`。
     * 更彻底的修法是让 `:llm` 改成 `@ApplicationContext`（那时本方法可以删除），
     * 但那属于 `:llm` 源码改动，本轮不越界。
     */
    @Provides
    @Singleton
    public fun provideUnqualifiedApplicationContext(
        @ApplicationContext context: Context,
    ): Context = context

    /** 模型文件目录：必须在 app 私有存储，用绝对路径传给 EngineConfig（官方明示） */
    @Provides
    @Singleton
    public fun provideModelsDir(@ApplicationContext ctx: Context): File =
        File(ctx.filesDir, "models").apply { mkdirs() }

    @Provides
    @Singleton
    public fun provideDownloader(modelsDir: File): ModelDownloader =
        ModelDownloader(modelsDir)

    @Provides
    @Singleton
    public fun provideClock(): Clock = SystemClock

    /**
     * 脱敏唯一闸口（数据库 §8.4 / F3(c)）。
     *
     * 把 [FieldCipher] 注入进来，于是 `:data:persist` 拿到的就是一个**没有旁路的工具**，
     * 而不是一份需要大家自觉执行的规范。
     */
    @Provides
    @Singleton
    public fun provideRedactionGate(cipher: FieldCipher): RedactionGate =
        RedactionGate(cipher)
}
