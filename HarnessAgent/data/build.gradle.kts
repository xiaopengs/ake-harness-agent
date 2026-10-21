plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * ## 为什么这里没有 protobuf 插件了（2026-10 清理）
 *
 * 此前本文件留着一段被注释掉的 `alias(libs.plugins.protobuf)` 与 `protobuf {}`
 * 配置块，同时挂着 `androidx-datastore` + `protobuf-kotlin-lite` 两条依赖，
 * 而 `src/main/proto/` 是空目录。当时全模块对 DataStore / Proto 的**真实引用为 0**
 * （逐行 grep 代码确认，命中的全是注释和 `WipeEverythingUseCase` 里那句
 * `File(filesDir, "datastore").deleteRecursively()`）。
 *
 * 裁定：**移除**。理由是——
 *  1. "恢复插件 + 补 proto 定义"这条路在当前状态下只会产出更糟的死代码：
 *     没有 Store 实现的 proto 定义＝编译能过、运行时没人消费的第二套事实来源。
 *     而 Store 实现属于 `:data` 源码，不在本次改动范围内。
 *  2. DataStore Proto 在架构里**确实有位置**（数据库 F7：settings / domain_policy /
 *     mcp_servers 三个独立实例），但它 "尚未开工"，不是 "已被废弃"。
 *     所以 catalog 里两条坐标**保留并标注为预留**（版本是校验过的，白删了下次还要重新核），
 *     `:data` 侧的依赖边和空目录则删干净 —— 到这里为止账实相符。
 *  3. 真正触发它回归的信号是：某个 Port 需要一个 `:data` 里的 Store 实现时
 *     （本轮新加的 `SettingsStorePort` / `McpServerStorePort` 就是候选），
 *     由 `:data` 的实现方一次性把插件 + proto + 实现三件套补齐。
 *     成本是一次 `--offline` 失效条件下的三方改动，比长期挂着不敢删要健康。
 */

android {
    namespace = "com.ake.data"
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
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))

    // ── Room 2.8.5 + BundledSQLiteDriver（数据库 F5/F6 拍板）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite.bundled)

    // （DataStore / Proto 依赖已于 2026-10 移除，理由见本文件头部注释。）

    implementation(libs.androidx.core.ktx)
    // 清理作业（FULL→DIGEST→删除）与空闲维护走 WorkManager：约束 idle + 非低存储
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.bundles.testUnit)
}
