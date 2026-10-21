plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ake.ui"
    compileSdk = 36
    defaultConfig { minSdk = 28 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9：kotlinOptions 已移除，改由内置 Kotlin 的 compilerOptions 指定
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))
    implementation(project(":core:model-api"))

    // ★ 编排层：UI 消费 `TaskEvent` / `TerminalReason` / `SuspendReason`，并驱动 `AgentLoop`。
    //   `HumanGate` 的实现也在这里（「人」就在 UI 这一侧），它同时补上了目前无人绑定的
    //   `AgentModule.provideAgentLoop` 依赖缺口。
    implementation(project(":agent"))

    // ★ 工具生态：`McpToolFacade` / `McpServerConfig` / `McpServerState` 都不含 MCP SDK 类型
    //   （`:mcp` 把 SDK 关在 `McpConnectionManager` 与 `McpSchemaMapper` 两个文件里），
    //   所以这里不违反「UI 不得依赖 MCP SDK」。
    implementation(project(":mcp"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    debugImplementation(libs.compose.ui.tooling)
}
