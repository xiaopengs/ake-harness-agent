package com.ake.ui.runtime

import com.ake.core.ports.HumanGate
import com.ake.core.ports.McpConnectionPort
import com.ake.ui.spi.AndroidPrivacyPort
import com.ake.ui.spi.PrivacyPort
import com.ake.ui.spi.ToolShutdownPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:ui` 自己的装配。
 *
 * ## 为什么 `HumanGate` 由 `:ui` 提供绑定
 *
 * `AgentModule.provideAgentLoop` 需要 `HumanGate`，而全工程此前**没有任何地方绑定它**
 * （`app/`、`data/` 都没有）—— 也就是说 Hilt 图目前是断的。
 * 「谁是人」这个问题的答案就在 UI，所以绑定放在这里，而不是让某个后端模块去猜。
 *
 * 其余两条是 `:ui` 自己定义的门面（`PrivacyPort` / `ToolShutdownPort`），
 * 等 `core:ports` 有正式定义后，改这里的 `@Binds` 目标即可，调用方不动。
 *
 * ## ★ 2026-10 交接完成：`McpServerStorePort` / `SettingsStorePort` 已从这里摘除
 *
 * 此前 `:ui` 为了跑通，自绑过两个过渡实现（`filesDir/mcp/servers.json` 与
 * `filesDir/settings/ui_local.json`，都是 `org.json` 手写）。`:data` 已给出落 Room 的
 * 真实现（`McpServerStoreImpl` / `SettingsStoreImpl`）并在 `PersistenceBindingsModule`
 * 里绑定 —— 同一 Port 有两个绑定会 `[Dagger/DuplicateBindings]`，所以这两条连同
 * 那两个过渡实现文件一起删除。
 *
 * 调用方（`McpViewModel` / `SettingsViewModel` / `TaskViewModel` / `HomeViewModel`）
 * 注入的一直是 `core:ports` 的 Port，不是具体类，所以这次是**纯删**，一行调用方都没动。
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class UiRuntimeModule {

    @Binds
    @Singleton
    public abstract fun bindHumanGate(impl: UiHumanGate): HumanGate

    @Binds
    @Singleton
    public abstract fun bindPrivacyPort(impl: AndroidPrivacyPort): PrivacyPort

    @Binds
    @Singleton
    public abstract fun bindToolShutdown(impl: McpToolShutdown): ToolShutdownPort
}

/**
 * 断开全部外部工具连接。
 *
 * 它存在的唯一理由是把工具生态挡在 `PrivacyPort` 之外：
 * 隐私那条路径不该认识「MCP」这个词 —— 换掉整个工具生态时它一行都不用改。
 *
 * ★ 注入的是 `core:ports` 的 [McpConnectionPort] 契约，不是 `:mcp` 的 facade 类型。
 * 这一点是 `:ui → :mcp` 那条 Gradle 边能摘掉的前提：注入具体类的话，
 * 边在源码里就还在，摘了 Gradle 只会变成编译失败。
 */
@Singleton
public class McpToolShutdown @Inject constructor(
    private val connections: McpConnectionPort,
) : ToolShutdownPort {
    override suspend fun shutdown() {
        runCatching { connections.shutdown() }
    }
}
