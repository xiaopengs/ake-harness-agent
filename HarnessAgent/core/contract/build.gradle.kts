// =============================================================================
// :core:contract —— L2 领域层：PocketDom / UiElement / StepAction / TaskRecord ...
//
// 这是领域的**单一事实来源**：它只依赖 :core:common。
// 一切实体形状（Task / Step / Observation / ToolSpec ...）都必须定义在此，
// 而不是让各业务模块各存一份副本 —— 同一批类型的双份定义会让它们永远对不上。
// =============================================================================
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    api(project(":core:common"))

    testImplementation(libs.bundles.testUnit)
}

// 见 :core:common 的说明：Kotlin 与 Java 的 target 必须一致。
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
