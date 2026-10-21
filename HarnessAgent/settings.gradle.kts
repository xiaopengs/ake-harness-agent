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
// L1/L2 纯 Kotlin（零 Android 依赖，JVM 毫秒级单测）：
//   :core:common      Outcome / AppError / Id 原语 / ULID / Clock / 日志 / 脱敏原语
//   :core:model-api   ModelPort / GenerationRequest / SessionSpec ...
//   :core:contract    PocketDom / UiElement / StepAction / TaskRecord ...
//   :core:ports       全部 Port 接口总表
//   :agent            ReAct 主循环 + planner + context（霍承钧）
//   :mcp              MCP 客户端（霍承钧）
// L3/L6 Android：
//   :llm              LiteRT-LM 接入 → ModelPort
//   :browser          WebView 执行器 + DOM 感知
//   :data             Room / DataStore / 轨迹落盘（霍承钧）
//   :ui               Compose UI（苏映棠）
//   :app              组合根 + Hilt 装配
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
include(":ui")
include(":app")
