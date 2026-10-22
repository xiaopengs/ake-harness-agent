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

    // ─────────────────────────────────────────────────────────────────────────
    // 【2026-10 · 邝随行】`:agent` 与 `:mcp` 两条 Gradle 依赖边**已摘除**。
    //
    // 前置：苏映棠确认 `ui/` 对 `:agent`、`:mcp` 的**源码引用已归零**。
    // 于是依赖边继续留着就是纯噪声 —— 它会让 Gradle 在 `:agent`/`:mcp` 改动时
    // 无谓重编 `:ui`，也会让后来人误以为这两条边还承载着什么。
    //
    // 摘除后 `:ui` 已真编译通过（见 docs 构建记录），证明引用确实归零、无隐藏引用。
    // ⚠ 若将来有人要加回这两条边，请先在 PR 里说明**新增的源码引用是什么** ——
    //   「编译不过就加回去」是把架构防线当成障碍绕开。
    // ─────────────────────────────────────────────────────────────────────────
    // implementation(project(":agent"))
    // implementation(project(":mcp"))

    // ─────────────────────────────────────────────────────────────────────────
    // 【2026-10 · 邝随行】`:data` 这条边**已摘除**。至此 `:ui` 对 L3 的全部 Gradle 边
    // （`:agent` / `:mcp` / `:data`）都已清空，L6 → L3 的直连在构建图上不再存在。
    //
    // 这条边此前唯一的用途是注入 `WipeEverythingUseCase`。整改后：
    //   · `WipePort` 契约上收到 `:core:ports`，真实现 `WipeEverythingUseCase` 留在 `:data`；
    //   · `AndroidPrivacyPort` 改为注入 `WipePort`，删除动作全权委托，不再持有 `:data` 类型；
    //   · `UiRuntimeModule` 里两条过渡期自绑（`McpServerStorePort` ← `McpServerStore`、
    //     `SettingsStorePort` ← `UiLocalSettings`）已由苏映棠删除，改由 `:mcp`/`:data`
    //     的 `McpServerStoreImpl` / `SettingsStoreImpl` 提供真实现，无 duplicate binding。
    //
    // 摘除前已 grep 自查：`ui/` 全目录（含 build/）对 `com.ake.data.*` /
    // `com.ake.agent.*` / `com.ake.mcp.*` 命中数为 0；摘除后 `:ui:compileDebugKotlin`
    // 真编译通过，证明引用确实归零、无隐藏引用。
    //
    // ⚠ 若将来有人要加回任一条边，请先在 PR 里说明**新增的源码引用是什么** ——
    //   「编译不过就加回去」是把架构防线当成障碍绕开。
    // ─────────────────────────────────────────────────────────────────────────
    // implementation(project(":data"))

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
