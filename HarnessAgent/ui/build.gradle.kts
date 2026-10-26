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

    // ─────────────────────────────────────────────────────────────────────────
    // 【本轮 · 苏映棠】`:ui` 的第一批测试依赖（此前 `:ui` 是 0 测试）。
    //
    // 选缺点：**只用版本目录里既有的别名，不新增坐标、不改版本号**。
    //  · `testUnit` = junit + truth + turbine —— 全仓每个模块的测试都用它，保持一致；
    //  · `coroutines.test` —— `viewModelScope` 跑在 `Dispatchers.Main` 上，纯 JVM 单测里
    //    没有 Android 的 Main 实现，`Dispatchers.setMain(TestDispatcher)` 必须由它提供。
    //    这条别的模块用不上（它们的 ViewModel 不多或已被 Robolectric 覆盖），所以没进 bundle。
    //
    // ⚠ 刻意**不引** Robolectric / `compose-ui-test-junit4`：那会新增 catalog 条目
    //   （邝随行的地盘），而且本项目没有 androidTest 基础设施。这里的测试一律绕开 Compose
    //   渲染，只测**行为**：文案映射、状态归约、选择器闸门 —— 像素与布局不在 C 位。
    // ─────────────────────────────────────────────────────────────────────────
    testImplementation(libs.bundles.testUnit)
    testImplementation(libs.coroutines.test)
}

// ─────────────────────────────────────────────────────────────────────────────
// 【本轮 · 苏映棠】把 `strings.xml` 声明成 `:ui` 测试任务的**输入文件**。
//
// 为什么非加不可：`ChatCopyTest` 的禁词断言直接读 `src/main/res/values/strings.xml`
// （见 `testsupport/UiStrings.kt`）。而 Gradle 的单元测试任务只知道自己 classpath
// 上的东西 —— 它没有理由知道某条用例会去读一份源码里的 XML。实测后果是：
//
//     改了 strings.xml → ./gradlew :ui:testDebugUnitTest → FROM-CACHE  → BUILD SUCCESSFUL
//
// 也就是说，「把「失败 / 安全」写进 IN_FLIGHT 文案」这个改动，在增量构建下**一次都不会红**。
// 这不是理论风险：上面那一行是实测出来的。
//
// 加了这条声明后，改任何一个字都会让测试任务重新执行。
// ⚠ 若日后有人把 `UiStrings` 换成别的读法，这里要跟着改 —— 否则又回到绿着通过。
// ─────────────────────────────────────────────────────────────────────────────
tasks.withType<Test>().configureEach {
    inputs.file(file("src/main/res/values/strings.xml"))
        .withPropertyName("akeUiStringsSource")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
