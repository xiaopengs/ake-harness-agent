package com.ake.ui.runtime

import com.ake.core.ports.HumanGate
import com.ake.mcp.McpToolFacade
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
 * 断开全部 MCP 连接。
 *
 * 它存在的唯一理由是把 `:mcp` 挡在 `PrivacyPort` 之外：
 * 隐私那条路径不该认识「MCP」这个词 —— 换掉整个工具生态时它一行都不用改。
 */
@Singleton
public class McpToolShutdown @Inject constructor(
    private val facade: McpToolFacade,
) : ToolShutdownPort {
    override suspend fun shutdown() {
        runCatching { facade.shutdown() }
    }
}
