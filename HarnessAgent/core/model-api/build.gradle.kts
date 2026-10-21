// =============================================================================
// :core:model-api —— L1：ModelPort / GenerationRequest / SessionSpec ...
//
// 只依赖 :core:common。**刻意不依赖 :core:contract** ——
// model-api 对 StepRecord / TaskRecord 这类业务实体一无所知，它只认识
// stepId / sessionId 这类跨层原语。这也保证「换推理引擎」时，改动面把它钉在一个模块里。
// =============================================================================
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    api(project(":core:common"))
    // Flow 是 Port 契约的一部分（ModelPort.runtimeState）。这是本模块唯一的第三方依赖。
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
