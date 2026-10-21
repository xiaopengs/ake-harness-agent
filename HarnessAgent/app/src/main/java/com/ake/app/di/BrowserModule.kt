package com.ake.app.di

import android.content.Context
import com.ake.browser.BrowserRuntime
import com.ake.core.ports.BrowserExecutorPort
import com.ake.core.ports.BrowserViewHandle
import com.ake.core.ports.DomPerceptionPort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 浏览器侧的装配 —— **这是 `provideAgentLoop` 依赖图的最后一个断点**。
 *
 * `AgentModule.provideAgentLoop` 需要 `DomPerceptionPort` 与 `BrowserExecutorPort`，
 * 而全工程此前没有任何地方绑定这两个 Port ⇒ Hilt 图断在 `AgentLoop` 上，App 起不来。
 *
 * ## 为什么不直接 `@Binds` 两个 Port 实现类
 *
 * `WebViewDomPerception` / `WebViewExecutor` 都需要一个 WebView 实例，而
 * **两个 Port 必须共用同一个 WebView、同一个 JS 桥**（`addJavascriptInterface`
 * 按名字覆盖，各建一个桥会互相顶掉）。所以中间必须有 [BrowserRuntime] 这一层
 * 持有单例，再由它派生出两个 Port。
 *
 * ## WebView 的生命周期与主线程
 *
 * - 用 `@ApplicationContext` 构造 ⇒ WebView **活过 Activity 重建**（旋转/后台回收
 *   之后任务断点还在它身上，这是硬需求）。
 * - `BrowserRuntime.create()` 内部把构造切到主线程（Hilt 的 `@Provides` 跑在请求方
 *   线程，完全可能是 `Dispatchers.IO`），后台线程构造 WebView 是运行时崩溃。
 * - 单例而非每次新建：WebView 是最贵的对象之一，重建会把 Cookie / localStorage
 *   一起丢掉，等于把用户的登录态清空。
 */
@Module
@InstallIn(SingletonComponent::class)
public object BrowserModule {

    @Provides
    @Singleton
    public fun provideBrowserRuntime(
        @ApplicationContext context: Context,
    ): BrowserRuntime = BrowserRuntime.create(context)

    /** 感知：WebView 抽取 → PocketDom / RecordTable */
    @Provides
    @Singleton
    public fun provideDomPerceptionPort(runtime: BrowserRuntime): DomPerceptionPort =
        runtime.perception

    /** 执行：点击 / 输入 / 滚动 / 导航 → ExecutionResult */
    @Provides
    @Singleton
    public fun provideBrowserExecutorPort(runtime: BrowserRuntime): BrowserExecutorPort =
        runtime.executor

    /**
     * 交给 UI 的浏览器句柄（`core:ports` 的最小契约）。
     *
     * MVP 只留口：UI 侧要做预览页时注入它，取 `nativeView()` cast 成 `WebView`
     * 塞进 `AndroidView` 即可，不需要 `:ui` 认识 `:browser`。
     */
    @Provides
    @Singleton
    public fun provideBrowserViewHandle(runtime: BrowserRuntime): BrowserViewHandle =
        runtime
}
