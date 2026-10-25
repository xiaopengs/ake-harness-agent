plugins {
    alias(libs.plugins.androidLibrary)
}

/**
 * # `:chat` —— 聊天会话编排层（L5）
 *
 * 设计依据：`docs/05-chat-agents-design.md` §2.2 / §2.3 / §2.5 / §3 / §5 / §6。
 *
 * ## 依赖边只有一条向下的实现层依赖
 *
 * ```
 * :chat → :core:*(L1/L2)  +  :agent(L4)
 * ```
 *
 * 为什么必须依赖 `:agent` 而不能自带一份上下文预算：见 §2.2 理由 2 ——
 * 重抄一份 `SegmentCaps` / 降级阶梯，就会出现「设置页显示一套预算、聊天跑的是另一套」。
 * 这条边由根 `build.gradle.kts` 的 `checkModuleDependencies` 守死，并被源码断言收紧为
 * 「只许 import `:agent` 的 public API + core 契约」。
 *
 * ## 刻意没有的东西
 *
 * - **没有 Hilt 插件 / 没有 KSP**：本模块只有实现类，没有任何注解处理器要跑。
 *   实现类用 `javax.inject.Inject` / `@Singleton` 标注构造器即可 ——
 *   Hilt 在 `:app` 侧读的是**已编译的 class 文件**，不要求提供方自己 apply Hilt 插件。
 *   少一个 KSP 步骤 = 少一次全量重编，`--no-daemon` 下这个差别是十几秒。
 * - **没有 `:data` 的 Gradle 边**：会话 / 人格 / 模板全部经 `core:ports` 的 Store Port。
 * - **没有 Room / `:mcp` / `:browser`**：源码断言 ② 会把它们变成构建失败。
 */
android {
    namespace = "com.ake.chat"
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
}

dependencies {
    // ── L1/L2：契约与领域对象
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))
    implementation(project(":core:model-api"))

    // ── L4：上下文组装 / 预算 / 压缩 / 闸 1 闸 2 / TokenStream 接缝（L5 → L4 向下依赖）
    implementation(project(":agent"))

    implementation(libs.coroutines.android)
    implementation(libs.javax.inject)

    testImplementation(libs.bundles.testUnit)
}
