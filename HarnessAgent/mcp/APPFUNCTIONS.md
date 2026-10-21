# AppFunctions（Android MCP）对外暴露 —— 正确 API 形态 + 稳定性风险评估

> 归属：霍承钧。MVP 结论：**列为 Phase 2 stretch goal，架构上留门、现在不装修**（架构 §7.5）。
> 本文件给出「一旦决定装修」时的正确形态与代价，**不落地任何编译单元**。

## 0. 核实结论（2026-10-21，官方 release notes + reference）

| 项 | 结论 |
|---|---|
| 坐标 | `androidx.appfunctions:appfunctions:1.0.0-alpha13` ✅ 存在（Google Maven 实测 200）<br>`androidx.appfunctions:appfunctions-compiler:1.0.0-alpha13` ✅ 存在<br>`appfunctions-testing:1.0.0-alpha13` ✅ 存在 |
| 注解名 | **`@AppFunctionDeclaration`**（alpha13 由 `@AppFunction` 改名）。签名为 `AppFunctionDeclaration(isEnabled: Boolean = true, isDescribedByKDoc: Boolean = false)` |
| 服务入口 | `@AppFunctionServiceEntryPoint(serviceName = "…", appFunctionXmlFileName = "…")`，加在 **abstract** 的 `AppFunctionService` 子类上；KSP 生成具体 Service + XML 到 assets |
| `AppFunctionContext` | **已在 alpha12 移除**。任何还在用它（含官方 Overview 页示例）的代码都过不了编译 |
| `AppFunctionConfiguration` | **已在 alpha12 移除** |
| `ObserveAppFunctionsEvent` | alpha13 改名 `AppFunctionsChangeEvent` |
| 新增（alpha13） | `@AppFunctionAccessLevel` —— 访问限制 |
| 线程 | **默认在主线程执行**。必须声明 `suspend` 并自己 `withContext` 切走 |
| 取消 | 系统/调用方取消 ⇒ 协程被取消。CPU 密集循环要 `ensureActive()` 协作 |
| 异常 | 抛 `AppFunctionException`；参数非法抛 `AppFunctionInvalidArgumentException` |
| 运行环境 | 要求 **Android 16（API 36）**；调用方需 `android.permission.EXECUTE_APP_FUNCTIONS` |

### ⚠ 官方文档落后于 release notes（架构 §7.5 的警告属实）

两条独立证据：

1. `developer.android.com/reference/androidx/appfunctions/AppFunctionDeclaration` 的**示例代码里仍然写着 `context: AppFunctionContext`** —— 而这个类型在 alpha12 已被移除。
2. `AppFunctionServiceEntryPoint` 的 reference 示例**仍然用 `@AppFunction`** —— 而 alpha13 已改名。

⇒ **实现时以 release notes 与 `androidx.appfunctions` reference 的"Summary"章节为准，不要照抄页面里的示例片段。**

## 1. MVP 最小可用形态（参考骨架，未落地）

```kotlin
// :tools:appfunctions （独立 Gradle 模块，主 APK 仅在 debug 变体引入）
@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "AkeAgentAppFunctionService",
    appFunctionXmlFileName = "ake_agent_app_function_service",
)
abstract class BaseAkeAgentAppFunctionService : AppFunctionService() {

    @Inject internal lateinit var taskRepo: TaskRepositoryPort   // 只依赖 L2 抽象

    /**
     * 用自然语言描述一个浏览器任务并执行，返回结构化结果。
     *
     * @param params 任务描述与可选的起始 URL。
     * @return 执行结果摘要。
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun runBrowserTask(params: RunBrowserTaskParams): TaskResult =
        withContext(Dispatchers.Default) {          // ★ 默认主线程，必须切
            ensureActive()                           // ★ 协作式取消
            // … 驱动 AgentLoop，收集 TaskEvent.Terminal …
        }
}

@AppFunctionSerializable(isDescribedByKDoc = true)
data class RunBrowserTaskParams(val instruction: String, val startUrl: String?)
```

Manifest 需要声明 KSP 生成的 Service 与 `foregroundServiceType`（本 App 已有前台服务，类型要对齐）。

## 2. 稳定性风险评估

| 维度 | 评估 | 依据 |
|---|---|---|
| 技术可行性 | 🟢 中高 | 注解形态明确、有 KSP 代码生成、有 `appfunctions-testing` |
| **API 稳定性** | 🔴 **差** | 4 个月内 3 次破坏变更：alpha10 引入 `@AppFunctionServiceEntryPoint` 并预告移除 `AppFunctionConfiguration`；alpha11 用 `AppFunctionState` 取代 `AppFunctionMetadata#isEnabled`（元数据与状态彻底分离）；**alpha12 移除 `AppFunctionContext`/`AppFunctionConfiguration`**；**alpha13 把 `@AppFunction` 改名**。`0.x alpha` 意味着任何一次升级都可能要重写模块 |
| 文档可靠性 | 🟠 中 | 官方 reference 示例代码**目前仍是错的**（见上）。参考成本高于正常 Jetpack 库 |
| 实际价值（MVP 期） | 🟠 **低** | 能调用它的系统 Agent 生态（Gemini）自 2026-05 起处于**可信测试者私有预览**，普通用户此刻用不到。实现完也没有调用方 |
| 维护成本 | 🔴 高 | 每次升级要重写并重测；且它跑在独立 Service 进程路径上，出问题不好定位 |
| 合规性 | 🟢 优 | 这是接入系统 Agent 生态的**正道**，比 AccessibilityService 干净得多 |

## 3. 建议（与主理人/架构 §7.5 一致）

1. **MVP 不承诺任何与系统 Agent 联动的功能。**
2. 若要做，作为**完全隔离的 Gradle 模块**，主 APK 用 `debugImplementation` 或 dynamic-feature 引入；用 build variant flag 控制，`appfunctions.enabled=false` 时该模块不参与编译。
3. 模块内**只依赖 L2 的 `TaskRepository` 抽象**，不得反向依赖 L4/L5。
4. **版本锁 `1.0.0-alpha13`**，升级须单独评估并把重写成本计入排期。
5. 线程与取消两条（默认主线程、协程取消）必须写进该模块的 code review checklist —— 这是最容易在 alpha 迁移中被漏掉、且后果（ANR）最严重的两条。
