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
 * 尚未落地的模块（`:mcp` / `:data` / `:ui` 的 Port 实现）在它们各自的
 * Hilt 模块里绑定；本文件不占位、不引用不存在的类，以免编译期就绑死。
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
