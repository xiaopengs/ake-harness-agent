plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ake.llm"
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

    buildFeatures { buildConfig = false }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model-api"))
    implementation(project(":core:ports"))
    implementation(project(":core:contract"))

    // ★ LiteRT-LM —— 唯一的推理 SDK，锁版本 0.18.0（README 里的 latest.release 禁止上生产）
    implementation(libs.litertlm)

    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.bundles.testUnit)
}
