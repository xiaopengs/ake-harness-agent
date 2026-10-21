package com.ake.ui.runtime

import com.ake.core.ports.HumanGate
import com.ake.core.ports.McpConnectionPort
import com.ake.core.ports.McpServerStorePort
import com.ake.core.ports.SettingsStorePort
import com.ake.ui.spi.AndroidPrivacyPort
import com.ake.ui.spi.McpServerStore
import com.ake.ui.spi.PrivacyPort
import com.ake.ui.spi.ToolShutdownPort
import com.ake.ui.spi.UiLocalSettings
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

    /**
     * ★ 服务器清单的过渡实现（`McpServerStore` 用 `org.json` 手写，落在 `filesDir/mcp/`）。
     *
     * 契约在 `core:ports`，所以它现在是 `:ui` 自己绑定的 Port —— 好处是调用方只认
     * [McpServerStorePort]，`:data` 将来给出真实现时是纯替换（删掉本条 `@Binds`）。
     *
     * ⚠ 两个实现同时存在会 duplicate binding，交接时务必先删这里。
     */
    @Binds
    @Singleton
    public abstract fun bindMcpServerStore(impl: McpServerStore): McpServerStorePort

    /**
     * ★ 本地设置的过渡实现（同样是 `org.json` 手写，落在 `filesDir/settings/`）。
     *
     * ⚠ 与上面那条同理：`:data` 落地真实现后请删掉本条，否则 duplicate binding。
     */
    @Binds
    @Singleton
    public abstract fun bindLocalSettings(impl: UiLocalSettings): SettingsStorePort
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
