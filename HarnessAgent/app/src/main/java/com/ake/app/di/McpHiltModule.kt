package com.ake.app.di

import com.ake.agent.tools.ToolSource
import com.ake.core.ports.ToolPermissionStorePort
import com.ake.mcp.McpToolAdapter
import com.ake.mcp.McpToolFacade
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import java.time.Duration
import javax.inject.Singleton

/**
 * MCP 的 Hilt 装配（唯一能同时看见 L3 `:mcp` 与 L4 `:agent` 的地方）。
 *
 * 代码原文来自 `mcp/MCP_BRIDGE.md`（霍承钧）。我只补了 MCP_BRIDGE.md 里漏掉的两个导入：
 *  - `dagger.multibindings.IntoSet`（`@IntoSet` 不加这个导入编不过）；
 *  - `java.time.Duration`（原文写的全限定名，等价）。
 *
 * ★ 装配后若 MCP 一台 server 都没配，`discover()` 返回空列表，
 *   `Set<ToolSource>` 里仍只有本地工具，主链路照常可跑 —— 架构 §7.3「MCP 故障不阻塞主交付」的落点。
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class McpHiltModule {

    @Binds
    @Singleton
    public abstract fun bindMcpFacade(impl: McpToolAdapter): McpToolFacade

    /**
     * ★ 唯一的桥接：`:mcp` 的 facade → `:agent` 的 ToolSource。
     *   纯转发，无逻辑。
     */
    @Binds
    @IntoSet
    @Singleton
    public abstract fun bindMcpToolSource(impl: McpToolSourceAdapter): ToolSource

    public companion object {
        /**
         * MCP 专用 HttpClient。
         *  - 必须 install(SSE)：Streamable HTTP 的回推通道依赖它。
         *    注意 Ktor 3.x 里 `io.ktor.client.plugins.sse.SSE` **就定义在 ktor-client-core 里**，
         *    不需要 `ktor-client-plugins-sse`（那个坐标在任何版本都不存在，已从 catalog 删除）。
         *  - 刻意不与 App 主网络栈共用实例：MCP 只连 localhost，不需要拦截器/证书固定。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideMcpHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(SSE)
            engine { config { connectTimeout(Duration.ofSeconds(5)) } }
        }

        @Provides
        @Singleton
        @JvmStatic
        public fun provideMcpAdapter(
            http: HttpClient,
            permissions: ToolPermissionStorePort,
        ): McpToolAdapter = McpToolAdapter(httpClient = http, permissions = permissions)
    }
}
