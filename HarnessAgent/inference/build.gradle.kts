// =============================================================================
// :inference —— L4：推理派发 / 路由 / 降级判定（架构 08 §8.1）
//
// ## 职责
//
//  · `ModelRouter` 实现（`DefaultModelRouter`）
//  · `DialogTarget` 选择与持久化（经 `DialogTargetStore` Port）
//  · `FallbackPolicy` —— **判定只有一份**（与 Hosts.kt 同一条理由）
//  · 路由分相（`EgressPhase`）
//  · `LocalDialogAdapter` —— 把端侧引擎「升格」成对话入口
//
// ## ★ 为什么 `ModelRouter` 不能放在别处（架构 08 §4.1）
//
//  · `:llm`（L3）  ✗ 它需要看见 `:cloudai` ⇒ L3 横向依赖
//  · `:cloudai`    ✗ 它需要看见 `:llm` ⇒ 同上
//  · `:chat`（L5） ✗ 要为它新开两条 L3 边，而 `:chat` 刻意**一条 L3 边都没有**
//  · `:app`（L6）  ⚠ 合法但不对：Router 有真实策略逻辑，塞进组合根 = 把业务放进装配层
//
// ## ★★ E6（`:inference → :llm`）已删除 —— 主理人裁决
//
// 原设计要这条边，理由是「Router 要装配 `LiteRtModelPort`」。行不通：
//  ① 纯 JVM 模块在 Gradle 里**无法依赖 `com.android.library` 模块**；
//  ② 与 §8.3 自相矛盾（黑名单已禁止 `:inference` import `litertlm.*`，
//     那它持有 `:llm` 的边毫无意义）。
// ⇒ 改为：`DefaultModelRouter` 只认 `DeviceModelPort` **接口**，
//   由 `:app` 组合根把 `LiteRtModelPort` 注入进来（依赖倒置）。
//   顺带保住 `:inference` 的毫秒级 JVM 单测（`:llm` 会把整个 JVM 单测拖死）。
// =============================================================================

plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:model-api"))
    implementation(project(":core:ports"))

    // ★ 只有这一条 L3 边（架构 08 §8.2 E7）
    implementation(project(":cloudai"))

    implementation(libs.coroutines.core)

    testImplementation(libs.bundles.testUnit)
    testImplementation(libs.coroutines.test)
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
