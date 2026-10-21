# `:mcp` → `:agent` 的桥接（给邝随行，需你在 `:app` 加两个文件）

## 为什么需要桥接

分层约束（架构 R2）：

- `:agent` 是 **L4**，`ToolSource`（工具来源 SPI，纯 Kotlin 接口）定义在 `com.ake.agent.tools`。
- `:mcp` 是 **L3**，持有 MCP Kotlin SDK。
- **L3 不能反向依赖 L4** ⇒ `:mcp` 无法实现 `ToolSource`。

所以由组合根 `:app`（唯一能同时看见两者的地方）做一次纯转发。
`McpToolFacade` 与 `ToolSource` 的方法签名**刻意做成同构**，桥接类因此不可能藏 bug。

## 需要新增的文件

### 1. `app/src/main/java/com/ake/app/di/McpHiltModule.kt`

```kotlin
package com.ake.app.di

import com.ake.agent.tools.ToolSource
import com.ake.mcp.McpToolAdapter
import com.ake.mcp.McpToolFacade
import com.ake.core.ports.ToolPermissionStorePort
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import javax.inject.Singleton

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
         *    不需要 `ktor-client-plugins-sse`（那个坐标在任何版本都不存在）。
         *  - 刻意不与 App 主网络栈共用实例：MCP 只连 localhost，不需要拦截器/证书固定。
         */
        @Provides
        @Singleton
        @JvmStatic
        public fun provideMcpHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(SSE)
            engine { config { connectTimeout(java.time.Duration.ofSeconds(5)) } }
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
```

> `OkHttp` engine 的 `engine { config { ... } }` 需要 `io.ktor:ktor-client-okhttp`。
> 若你不想在 `:app` 引 ktor，可以改成 `HttpClient { install(SSE) }`（走默认 engine），
> 但 Android 上建议显式 OkHttp —— 默认 engine 在部分机型上有连接复用问题。

### 2. `app/src/main/java/com/ake/app/di/McpToolSourceAdapter.kt`

```kotlin
package com.ake.app.di

import com.ake.agent.tools.ToolSource
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
import com.ake.mcp.McpToolFacade
import javax.inject.Inject

/**
 * `:mcp`（L3）与 `:agent`（L4）之间的六行桥。
 * 纯转发 —— 这里加任何逻辑都说明放错地方了。
 */
public class McpToolSourceAdapter @Inject constructor(
    private val facade: McpToolFacade,
) : ToolSource {

    override val origin: ToolOrigin = ToolOrigin.MCP

    override suspend fun discover(): List<ToolSpec> = facade.discover()

    override suspend fun invoke(toolId: ToolId, args: Map<String, String>): ToolResult =
        facade.invoke(toolId, args)

    override suspend fun setEnabled(toolId: ToolId, enabled: Boolean): Boolean {
        facade.setEnabled(toolId, enabled)
        return true
    }
}
```

### 3. `settings.gradle.kts` —— 无需改动

`include(":mcp")` 已存在。

### 4. `app/build.gradle.kts` —— 需加两条

```kotlin
implementation(project(":mcp"))            // 组合根才能同时看到 L3 与 L4
implementation(libs.ktorClientOkhttp)      // 若用上面的 OkHttp engine
```

## 装配后的行为

- `Set<ToolSource>` 里会有两个元素：`McpToolSourceAdapter` 与 `:agent` 自带的 `LocalToolSource`。
- 若 MCP 一台 server 都没配，`discover()` 返回空列表 → 注册表里只有 3 个内置本地工具，**主链路照常可跑**。这正是架构 §7.3「即使 MCP 侧出问题也不阻塞主交付」的落点。

## 依赖坐标更正（重要 —— 会直接导致构建失败）

根 `gradle/libs.versions.toml` 里这一条**必须删除**：

```toml
ktor-client-plugins-sse = { group = "io.ktor", name = "ktor-client-plugins-sse", version.ref = "ktor" }
```

实测（2026-10-20，Maven Central 全版本目录）：`io.ktor:ktor-client-plugins-sse` **在任何版本都不存在**（3.0.0 / 3.1.0 / 3.2.0 / 3.3.0 / 3.4.0 / 3.5.0 / 3.5.1 全部 404）。
Ktor 3.x 的 SSE 客户端插件 `io.ktor.client.plugins.sse.SSE` 已经收进 `ktor-client-core` 内部，不再单独发布。

我已同步修正 `mcp/build.gradle.kts`。请你在根 catalog 里把这条别名删掉，避免别的模块误引。

其余坐标我已核实存在：`io.modelcontextprotocol:kotlin-sdk-client:0.15.0`（Maven Central 有 0.15.0，jvm 变体由 Gradle 自动选 `kotlin-sdk-client-jvm`）、`io.ktor:ktor-client-{core,okhttp}:3.5.1`。
