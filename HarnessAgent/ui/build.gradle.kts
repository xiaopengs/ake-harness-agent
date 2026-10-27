plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ake.ui"
    compileSdk = 36
    defaultConfig {
        minSdk = 28

        // ─────────────────────────────────────────────────────────────────────
        // instrumentation 跑 JUnit4 用例需要 runner，默认 [`android.test.
        // InstrumentationTestRunner`] 是 JUnit3 时代的产物，不理解 Compose 测试规则。
        //
        // ★ 但要注意：`androidx.test:runner` **不是我自己声明的**，它是传递依赖，链路实测如下
        //   （`./gradlew :ui:dependencyInsight --dependency androidx.test:runner
        //      --configuration debugAndroidTestRuntimeClasspath`）：
        //
        //     androidx.test:runner:1.5.0
        //     \--- androidx.test.espresso:espresso-core:3.5.0
        //          \--- androidx.compose.ui:ui-test-android:1.11.4
        //
        //   同一条链也带进来了 `androidx.test.ext:junit:1.1.5`（测试里的
        //   `@RunWith(AndroidJUnit4::class)` 就靠它），`junit:junit:4.13.2` 则由
        //   `libs.bundles.testUnit` 直接提供。
        //
        //   这条路是 Compose ui-test 自己的设计（它天生依赖 espresso），所以短期稳定；
        //   ⚠ 但它毕竟是"别人顺带捎过来的"。等邝随行补了 catalog 别名后应当显式声明
        //   `androidTestImplementation(libs.androidx.test.runner)` 和
        //   `...ext.junit`，别让自己站在传递链的第 3 层上。
        // ─────────────────────────────────────────────────────────────────────
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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
    // 【补记 · 2026-10 苏映棠】上面提到的"刻意不引 compose-ui-test-junit4、本项目没有
    //   androidTest 基础设施"**已经作废**：本轮的 `src/androidTest/` 把它引了进来，
    //   理由见下面那段注释。这段保留是因为它解释了为什么 JVM 侧只测行为不测像素 ——
    //   那个取舍本身仍然是成立的，只是不再"别无选择"。
    // ─────────────────────────────────────────────────────────────────────────
    testImplementation(libs.bundles.testUnit)
    testImplementation(libs.coroutines.test)

    // ─────────────────────────────────────────────────────────────────────────
    // 【本轮 · 苏映棠】`:ui` 的 Compose 界面测试（instrumentation）依赖。
    //
    // ## 为什么这里出现的是裸坐标而不是 `libs.*` 别名
    //
    // 版本目录（`gradle/libs.versions.toml`）里**没有任何** Compose / AndroidX 测试坐标 ——
    // 它的测试区只有 junit / truth / turbine 三条。也就是说不存在"现成别名"可用。
    // 两条出路权衡后选了"不改 catalog"：
    //   · 改 catalog：等于在邝随行并行编辑的同一份文件里动手，冲突风险由别人承担；
    //   · **不改 catalog**：版本由 `libs.compose.bom`（已有别名）统一供给 —— 下面两行
    //    坐标**一个版本号都不写**，正是 `compose-ui` / `compose-material3` 现有的玩法
    //    （它们在 catalog 里也没有 version 字段）。
    //
    // 代价：`libs.versions.toml` 头部"模块只允许出现别名"的纪律被局部违反。
    // ⚠ 待办（需邝随行）：把这两条提升为 catalog 别名 `compose-ui-test-junit4` /
    //   `compose-ui-test-manifest`（同样不带 version），届时这里的裸坐标应被替换掉。
    //
    // ## 为什么 `ui-test-manifest` 落在 debugImplementation 而不是 androidTest
    //
    // 它往 **被测 APK（debug）** 的 manifest 里塞 `ComponentActivity` 与空 theme；
    // 放进 androidTest 的话 activity 在被测端找不到，启动即 ClassNotFound。
    //
    // ## 刻意不引的
    //   · `uiAutomator`：本次全部场景都在应用内的 Compose 树里，用不到跨进程/系统 UI 查询。
    //     而且它**不在 compose BOM 管理范围内**，引它就必须写一个裸版本号 —— 不值得。
    //   · AndroidX Test Orchestrator：同理（`androidx.test:orchestrator` 需版本号），
    //     且它只影响"怎么跑"，不影响本次要保证的"能不能编译"。真机上跑之前再补。
    //   · Hilt 测试相关：本次刻意不渲染带 `hiltViewModel()` 的整屏（见 tests 注释），
    //     所以不需要 hilt-android-testing。也没那个别名。
    // ─────────────────────────────────────────────────────────────────────────
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
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
