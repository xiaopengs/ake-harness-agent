// =============================================================================
// :core:ports —— L1：全部 Port 接口总表
//
// 规则（架构 §1.3 R4）：**Port 只能定义接口 + data class / sealed interface，不承载行为。**
// Port 是契约，不是实现仓库 —— 一旦有人在 Port 里写实现，两个实现方就会开始 diverge，
// 而这个 project divergent 只有在真机上跑 20 分钟才看得出来。
// =============================================================================
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    api(project(":core:common"))
    // Port 的入参/返回类型是 contract 的领域对象。
    // ⚠ 反过来的方向（contract 依赖 ports）是错的，不要加。
    api(project(":core:contract"))
    api(libs.coroutines.core)

    testImplementation(libs.bundles.testUnit)
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
