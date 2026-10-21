plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ake.browser"
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

    // WebViewFactory 用 BuildConfig.DEBUG 控制 setWebContentsDebuggingEnabled
    buildFeatures { buildConfig = true }

    // dom-extract.js 走 assets（browser/src/main/assets/js/dom-extract.js）：
    // 它是本模块的「第二个编译产物」，必须能被替换而不重新打包 APK 逻辑
    // （便于通过远端下发修一个选择器 bug）。
    // 注：`src/main/assets` 本就是 AGP 默认 assets 目录，不需要再 srcDirs 声明；
    //     且 AGP 9 移除了旧 `AndroidLibrarySourceSet` 类型，写 sourceSets["main"] 会直接抛
    //     ClassCastException，故此处不声明。
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))
    implementation(project(":core:model-api"))

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.bundles.testUnit)
}
