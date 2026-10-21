plugins {
    alias(libs.plugins.kotlinJvm)
    // ★ kotlinx-serialization 编译器插件（`libs.kotlinx.serialization.json` 的配套一半）。
    //   只有 runtime 依赖、没有这个插件时，代码里一旦出现 `@Serializable` 就会报
    //   "kotlinx.serialization compiler plugin is not applied to the module" ——
    //   而 `:mcp` 的 `McpSchemaMapper` / ToolSpec 编解码迟早要用它，届时报错的会是
    //   「刚写下 @Serializable 的那个人」，一处 unrelated 的位置。现在一次性补齐。
    //   注意 version.ref 已在 root catalog 的 `serialization` alias 里锁住（= kotlin 版本）。
    alias(libs.plugins.serialization)
}

/**
 * `:mcp` —— MCP 客户端（霍承钧）。
 *
 * 纯 Kotlin/协程：MCP Kotlin SDK 走 ktor，ktor 是 Kotlin Multiplatform，
 * 没有 Android 绑定 ⇒ 这一层可以在 JVM 上对本地 mock server 做集成测试，不必上真机。
 *
 * 对外只暴露 `ToolSpec` 形状的发现与调用（架构 §7.1），
 * **模型永远看不到 MCP 的存在**。
 *
 * ## 坐标更正说明（2026-10 实测，:mcp:compileKotlin 通过）
 *  - `io.ktor:ktor-client-plugins-sse` **在任何版本都不存在**（Ktor 2.x 亦无）。
 *    Ktor 3.x 的 SSE 客户端插件 `io.ktor.client.plugins.sse.SSE` 已经**收进 `ktor-client-core`**，
 *    不需要额外坐标。保留它会让 Gradle 在依赖解析阶段直接失败。
 *  - SDK 是 KMP 发布物，Gradle 会自动选 `kotlin-sdk-client-jvm` 变体，无需手工指定。
 */
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))

    implementation(libs.mcp.sdk.client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.testUnit)
}
