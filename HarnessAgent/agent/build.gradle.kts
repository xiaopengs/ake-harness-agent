plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ake.agent"
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
    // ── L1/L2：唯一允许的上游
    // ── L1/L2：唯一允许的上游
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))
    implementation(project(":core:model-api"))

    // ── L3：依赖方向 L4 → L3（架构 §1.2 / 数据库 §3.1）。
    //    只为实现 `:data` 声明的 `StepTraceCodec` 接口 —— 该接口**不含任何 Room 类型**，
    //    因此不违反「L4 不得直接依赖 Room 具体类型」这条硬约束。
    implementation(project(":data"))

    implementation(libs.coroutines.android)
    implementation(libs.javax.inject)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.bundles.testUnit)
}
