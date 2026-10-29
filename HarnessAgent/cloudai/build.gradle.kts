// =============================================================================
// :cloudai —— L3：OpenAI 兼容云端适配器（架构 08 §8.1）
//
// ## 职责边界：**只做协议，不做策略**
//
//  · URL 幂等拼接      —— CloudEndpoints
//  · SSE 行解析        —— SseLineReader / SseDecoder（★ 全仓唯一一份）
//  · 请求/响应模型     —— OpenAiChatWire
//  · HTTP 重试         —— KtorCloudTransport（重试**计数**，不是重试**决策**）
//
// ✘ **不含任何策略**：是否出云、失败后是否回落本地、重放上限 —— 全在 `:inference`。
//   理由与 `Hosts.kt` 同一条：**判定只有一份，才会只有一份正确。**
//
// ## ★★ 依赖白名单刻意不含 `:core:contract`（架构 08 §5.3 · DOM 锁 B）
//
// `PocketDom` 定义在 `:core:contract`。本模块不声明这条 Gradle 边，
// 且 `:core:ports` 已把 `:core:contract` 收紧为 `implementation` ⇒
// `com.ake.core.contract.PocketDom` 在本模块的编译类路径上**不存在**。
// 这是本次唯一一条「结构上不可能」的锁，由 `:inference` 的 `DomLockTest` 机械盯住。
//
// ## 为什么是 `kotlinJvm` 而不是 `androidLibrary`
//
// 本模块是本次**最需要被单测**的部分（协议畸形、重试、截断），而它**不需要任何 Android API**。
// `:mcp` 已是这个先例。⇒ 毫秒级 JVM 单测，不依赖真机 20 分钟（`docs/07` 的核心教训）。
// =============================================================================

plugins {
    alias(libs.plugins.kotlinJvm)
    // kotlinx-serialization 编译器插件（`libs.kotlinx.serialization.json` 的配套一半）。
    // 与 `:mcp` 同一处理：只有 runtime 依赖、没有插件时，代码里出现 `@Serializable` 会直接报错。
    alias(libs.plugins.serialization)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model-api"))
    implementation(project(":core:ports"))
    // ★ 刻意没有 project(":core:contract")

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(libs.bundles.testUnit)
    testImplementation(libs.coroutines.test)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.add("-jvm-default=enable")
    }
}

tasks.withType<Test>().configureEach { useJUnit() }
