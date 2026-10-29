// =============================================================================
// :core:ports —— L1：全部 Port 接口总表
//
// 规则（架构 §1.3 R4）：**Port 只能定义接口 + data class / sealed interface，不承载行为。**
// Port 是契约，不是实现仓库 —— 一旦有人在 Port 里写实现，两个实现方就会开始 diverge，
// 而这个 project divergent 只有在真机上跑 20 分钟才看得出来。
// =============================================================================
plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    api(project(":core:common"))

    /**
     * ★★ 2026-10 收紧（架构 08 §5.3）：`:core:contract` 由 `api` 改为 `implementation`。
     *
     * ## 为什么必须改
     *
     * `PocketDom` 定义在 `:core:contract`。架构 08 §5.3 的 DOM 锁 B 声称：
     * 「`:cloudai` 的依赖白名单刻意不含 `:core:contract` ⇒ `PocketDom` 在其类路径上
     *  根本不存在 ⇒ 这是结构级的锁，不是纪律」。
     *
     * **这个结论在 `api` 下不成立。** `api` 会把依赖**透传到下游的编译期类路径**，
     * 于是 `:cloudai` 只要声明了 `implementation(project(":core:ports"))`，
     * 就能 `import com.ake.core.contract.PocketDom` 并**正常编译** ——
     * Gradle 边上确实没有 `:core:contract`，但符号照样可见。
     * 实测：改之前 `:cloudai` 里写 `val x: PocketDom? = null` ⇒ `BUILD SUCCESSFUL`；
     *       改之后 ⇒ `e: Unresolved reference 'PocketDom'`。
     *
     * ⇒ 这条边改成 `implementation` 之后，锁 B 才真的成为「结构上不可能」。
     *
     * ## 影响面（已核实为零）
     *
     * 用到 `com.ake.core.contract.*` 的模块**全部**（`:llm` `:browser` `:data` `:chat`
     * `:ui` `:app` `:agent`）都已经**自己显式声明**了 `project(":core:contract")`
     * 这条 Gradle 边 ⇒ 收紧后无人受损（`checkModuleDependencies` 与
     * `:core:ports` 主/测编译均绿）。
     */
    implementation(project(":core:contract"))

    /**
     * ★ 新增边（架构 08 §2.4 的偏差，见 `Dialog.kt` 头部）：
     * `DialogModelPort` / `DeviceModelPort` 是 `ModelPort` 的子接口，Port 总表必须看得见它。
     */
    api(project(":core:model-api"))

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
