// =============================================================================
// :core:common —— L1 内核：Outcome / AppError / Id 原语 / ULID / Clock / 日志门面 / 脱敏原语
//
// 依赖：**无**（连 :core 其它子模块也不许依赖）。
// 这是架构 §1.3 R6 的落地：「core:common 不引入任何第三方库」，
// 因为一旦它被污染，所有引擎（`litertlm` 带 gson + kotlin-reflect + coroutines-android）
// 的整棵依赖树都会顺着它漫本项目。
// =============================================================================
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    // 刻意留空。只有 :core:common 是零依赖模块；其余三个兄弟模块依赖它。
    testImplementation(libs.bundles.testUnit)
}

// jvmTarget 17 ⇔ javac 17 必须一致，否则 Gradle 9 会直接报
// "Inconsistent JVM Target Compatibility Between Java and Kotlin Tasks"。
// 17 是给下游 Android 模块的最低可用字节码等级。
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
