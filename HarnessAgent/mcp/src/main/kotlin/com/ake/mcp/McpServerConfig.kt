package com.ake.mcp

/**
 * ★★ 迁移垫片：`com.ake.mcp.{McpServerConfig, McpServerState, TransportKind}`
 *        → `com.ake.core.ports.*`
 *
 * 这三个纯数据形状的第二份定义已从本模块删除，新坐标在 `:core:ports`
 * （见 `core/ports/McpServers.kt`）。本文件现在是且仅是三行转发。
 *
 * ## 为什么是 alias 而不是删除
 *
 * `:ui` 有 5 处 `import com.ake.mcp.{McpServerConfig, McpServerState, TransportKind}`
 * （`McpViewModel` / `McpScreen` / `ui/spi/McpServerStore`），`:app` 另有
 * `McpToolFacade` / `McpToolAdapter` 两处。它们都不在本次改动范围内。
 * 与 `core/ports/McpServers.kt` 顶部的三步约定一致：**先加契约 → 再改实现 → 最后 UI 换 import 摘 Gradle 边**。
 *
 * `typealias` 不是第二份定义，UI 与 `:mcp` 用的是同一个类。
 *
 * ## ⚠ Kotlin 限制（与 `:agent` 侧同一条，真编译实测）
 *
 * typealias 不代理嵌套 classifier，也不保证代理伴生对象成员。
 * 因此本模块内部文件（`McpConnectionManager` / `McpToolAdapter`）一律
 * `import com.ake.core.ports.{McpServerConfig, McpServerState, TransportKind}` ——
 * 显式导入优先于同包 alias，于是 `McpServerState.Phase.READY` 与
 * `McpServerConfig.DEFAULT_LOOPBACK_ONLY` 都能正常解析。
 *
 * ## TODO(migration · step 3)：`:ui` 改完 import 后删除本文件
 *
 *  1. `:ui` 把上述 5 处 import 改成 `import com.ake.core.ports.*`；
 *  2. 摘掉 `ui/build.gradle.kts` 里的 `implementation(project(":mcp"))`；
 *  3. 回来删除本文件。
 */
public typealias McpServerConfig = com.ake.core.ports.McpServerConfig

public typealias McpServerState = com.ake.core.ports.McpServerState

public typealias TransportKind = com.ake.core.ports.TransportKind
