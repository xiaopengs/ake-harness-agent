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

// ★ `dom-extract.js` 必须是 `:browser` 单测的**声明式输入**。
//
// 背景：单测从源码树**机械抽取** JS 侧的 `ATTR_WHITELIST`（见 `DomFixture.attrWhitelistFromJsAsset`）
// —— 名单不再手抄进 Kotlin，最坏候选体积因此永远是真值。但抽取发生在**测试运行期**，
//   Gradle 并不把这个 asset 当成 `testDebugUnitTest` 的输入：改了 JS 再跑测试，
//   任务仍是 `UP-TO-DATE`，「机械抽取」在 CI 上等于没跑。
//   声明成输入后，JS 一改 ⇒ 测试强制重跑 ⇒ 预算那条断言才会真的响。
// ★ `HostTest` 的畸形向量表是 `:browser` 一致性测试的**第二个声明式输入**。
//
//   `HostNormalisationTest` 从 `core/common` 的 HostsTest.kt **机械抽取**那批向量并逐条对拍。
//   抽取发生在测试运行期，Gradle 看不见这层关系 ⇒ 向量表加了一条，任务仍 UP-TO-DATE，
//   对拍就不会真的重跑。声明成输入后，授权表一变 ⇒ 测试强制重跑。
//   （跨模块读源码只是为本测试提供输入，不构成依赖边 —— 依赖方向由依赖声明决定。）
tasks.withType<Test>().configureEach {
    inputs.file(file("src/main/assets/js/dom-extract.js"))
        .withPropertyName("domExtractJsAsset")
        .normalizeLineEndings()

    inputs.file(rootProject.file("core/common/src/test/java/com/ake/core/common/HostsTest.kt"))
        .withPropertyName("sharedHostVectors")
        .normalizeLineEndings()
}
