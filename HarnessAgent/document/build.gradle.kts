// =============================================================================
// :document —— L3：PDF → 页文本 → 定长块（交付方案 §3.1）
//
// ## 为什么它是一个独立模块
//
//  全仓**唯一**允许持有 PDFBox 的地方。理由不是"职责单一"这种空话：
//  ① 外部 SDK 只能出现在 L3 且必须 Port 封装（架构 §2.3 铁律）；
//  ② 它必须与 `:data` 完全隔离 —— `:document → :data` 一旦成立，PDFBox 的
//     解析生命周期就会与 Room 事务绑定，`HeavyResourceLease` 想做
//     "解析与推理永不重叠"就没有可切的边界；
//  ③ 它不能依赖 `:llm`（L3 横向），所以 token 估算在这里有一份**口径相同但代码独立**
//     的副本 —— 见 `TokenCount.kt` 头部，这是已知重复，不是疏忽。
//
// ## 为什么用 com.tom-roush:pdfbox-android 而不是 org.apache.pdfbox
//
//  `org.apache.pdfbox` 依赖 java.awt（Graphics2D / Color / Font），Android 上没有这些类；
//  `pdfbox-android` 是同一份代码的 Android 移植（去 awt 依赖、用 android.graphics 替代渲染）。
//  文本抽取路径（PDFTextStripper）不触碰渲染，所以纯文本场景不需要初始化字体资源。
// =============================================================================

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.ake.document"
    compileSdk = 36
    defaultConfig { minSdk = 28 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures { buildConfig = false }

    // PDFBox 的部分类会引用 android.graphics（字体/渲染路径），即使我们只走文本抽取，
    // 类加载阶段仍可能触碰。Robolectric 提供这些类的空实现，使单测不需要真机。
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))

    implementation(libs.pdfbox.android)
    implementation(libs.coroutines.android)

    testImplementation(libs.bundles.testUnit)
    // ★ 与 :ui 同一版本口径（该模块注释里记录了为什么锁 4.17 而不是 latest）
    testImplementation("org.robolectric:robolectric:4.17")
}
