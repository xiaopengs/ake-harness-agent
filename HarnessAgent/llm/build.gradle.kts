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

    // ★ 模型分发（霍承钧 · `persist/modeldist`）：manifest 决议 + `model_asset` 账本状态机。
    //   `:llm` 是文件系统的所有者，账本只描述意图（`ModelFileOp`），改名/丢 part 由本模块执行。
    //   依赖方向 `:llm → :data` 已进根 build.gradle.kts 的 `archAllowedEdges` 白名单。
    implementation(project(":data"))

    // ★ LiteRT-LM —— 唯一的推理 SDK，锁版本 0.18.0（README 里的 latest.release 禁止上生产）
    implementation(libs.litertlm)

    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.bundles.testUnit)
}
