pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 所有模块的仓库在这里统一声明；禁止单个模块再写 repositories{}（可复现 + 防仓库漂移）
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HarnessAgent"

// ── 模块总表（职责与分层见 01-architecture.md §2.1）
//
// 【2026-10 校正 · 邝随行】本表按**每个模块 build.gradle.kts 里实际 apply 的插件**重排。
// 校正前它把 `:agent` 列在「纯 Kotlin / 零 Android 依赖」那一组，而事实是：
//   · `agent/build.gradle.kts` apply 的是 `alias(libs.plugins.androidLibrary)`；
//   · 它还写着 `implementation(project(":data"))`。
// 两点都与旧注释相反。新增模块时请同步更新本表，**以实际插件为准**。
//
// ── JVM 组：`kotlinJvm`，纯 Kotlin / 零 Android 依赖，可在 JVM 上做毫秒级单测
//   :core:common      Outcome / AppError / Id 原语 / ULID / Clock / 日志 / 脱敏原语
//   :core:model-api   ModelPort / GenerationRequest / SessionSpec ...
//   :core:contract    PocketDom / UiElement / StepAction / TaskRecord ...（领域单一事实来源）
//   :core:ports       全部 Port 接口总表
//   :agent            ❌ 见校正说明 —— 它是 `androidLibrary`，不属于本组
//   :mcp              MCP 客户端（霍承钧）。ktor 是 KMP 产物、无 Android 绑定，
//                     所以它虽然处在「实现层」仍是 JVM 模块
//
// ── Android 组：`com.android.library`（`:app` 是 `com.android.application`）
//   :agent            ReAct 主循环 + planner + context（霍承钧）
//                     ★ 它依赖 `:data` —— 依赖方向 L4 → L3（见 agent/build.gradle.kts 原文）
//   :llm              LiteRT-LM 接入 → ModelPort
//   :browser          WebView 执行器 + DOM 感知
//   :data             Room / 轨迹落盘（霍承钧）
//                     ★ 2026-10 移除了 DataStore + protobuf 死依赖（见该模块头部注释）
//   :chat             聊天会话编排（L5 会话编排层，霍承钧）
//                     ★ 向下依赖 `:agent`（L5 → L4）：复用 ContextAssembler / SegmentCaps /
//                       ContextBudget / InjectionIsolator / DeterministicCompactor /
//                       ContentSanitizer / NonceSource —— **不在本模块重抄一份预算算法**
//                       （docs/05-chat-agents-design.md §2.2 理由 2 / §2.4 的守卫 diff）。
//                     ★ `:ui` 不得看见它：聊天 UI 只消费 `core:ports/Chat.kt` 的契约。
//   :ui               Compose UI（苏映棠）
//   :app              组合根 + Hilt 装配
//
// ★ 关于层号：除上面 `:core:*` 自述的 L1/L2 之外，其余编号不由本文档断言 ——
//   **真正被编译与 CI 守住的是依赖边，不是层号**。层号只在 `agent/build.gradle.kts`
//   的「L4 → L3」这类地方被引用，作为读代码时的路标。
//   （遗留小疑问：`:core:ports` 自述 L1，但它依赖 `:core:contract`（自述 L2），
//    二者不可能同层。不影响编译，留给后续文档统一时一并处理。）
//
// 【模块粒度说明 · 需仲裁】`:core` 按架构 §2.1 原文拆成 4 个子项目，
// 而不是合成一个 `:core`。原因：`agent/build.gradle.kts`（霍承钧已提交）
// 里写的是 `project(":core:common")` / `(":core:contract")` / `(":core:ports")` /
// `(":core:model-api")`，与架构文档一致。若强行合成单模块，他的构建文件会对不上。
// 注意 `:core` 自身没有 build.gradle.kts —— 它只是这 4 个子项目的容器。
include(":core:common")
include(":core:model-api")
include(":core:contract")
include(":core:ports")
include(":agent")
include(":mcp")
include(":llm")
include(":browser")
include(":data")
include(":chat")
include(":ui")
include(":app")

include(":cloudai")
include(":inference")
