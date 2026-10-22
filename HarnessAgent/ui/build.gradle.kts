plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ake.ui"
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

    buildFeatures {
        compose = true
        buildConfig = false
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))
    implementation(project(":core:model-api"))

    // ─────────────────────────────────────────────────────────────────────────
    // 【2026-10 · 邝随行】`:agent` 与 `:mcp` 两条 Gradle 依赖边**已摘除**。
    //
    // 前置：苏映棠确认 `ui/` 对 `:agent`、`:mcp` 的**源码引用已归零**。
    // 于是依赖边继续留着就是纯噪声 —— 它会让 Gradle 在 `:agent`/`:mcp` 改动时
    // 无谓重编 `:ui`，也会让后来人误以为这两条边还承载着什么。
    //
    // 摘除后 `:ui` 已真编译通过（见 docs 构建记录），证明引用确实归零、无隐藏引用。
    // ⚠ 若将来有人要加回这两条边，请先在 PR 里说明**新增的源码引用是什么** ——
    //   「编译不过就加回去」是把架构防线当成障碍绕开。
    // ─────────────────────────────────────────────────────────────────────────
    // implementation(project(":agent"))
    // implementation(project(":mcp"))

    // ★ 持久化：**只为注入一个类型** —— `WipeEverythingUseCase`。
    //   「一键清除痕迹」的执行属于 `:data`（关库 / 停在途写入 / 删外置轨迹目录），
    //   UI 之前自己按 Context+Keystore 重写了一份，结果两边范围会静默分叉。
    //   现在 `AndroidPrivacyPort` 只做门面：断 MCP、卸模型、把结果翻成人话，删除全委托给它。
    //   注意它的签名（`invoke(Boolean) -> Result`）不含任何 Room 类型，
    //   所以 Room 不会顺着这条依赖漏进 `:ui` 的源码 —— 这与「直接 import Room」是两回事。
    //
    // ────────────────────────────────────────────────────────────────────────
    // ⚠⚠ 【待办 · 苏映棠点名 · 邝随行登记】这条边**暂时保留**（岑执衡 2026-10 裁决），
    //   等 `:data` 落地 `WipePort` 真实现后再安排摘除。
    //
    //   ★ 同时必须删掉 `ui/.../UiRuntimeModule` 里的两条**过渡期自绑** `@Binds`：
    //       · `McpServerStorePort` ← `McpServerStore`（UI 侧 org.json 兜底实现）
    //       · `SettingsStorePort`  ← `UiLocalSettings`
    //     这两条是 UI 侧的临时实现。`:data` 一旦落地同名 Port 的真实现并绑定，
    //     它们就会变成 **duplicate binding**，Hilt 编译期直接红
    //     （`[Dagger/DuplicateBindings] ... is bound multiple times`）。
    //     删这两行属于 `:ui` 源码改动 ⇒ 由苏映棠或拿到授权者执行，此处只登记。
    // ────────────────────────────────────────────────────────────────────────
    implementation(project(":data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    debugImplementation(libs.compose.ui.tooling)
}
