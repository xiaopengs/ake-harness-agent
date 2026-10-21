# agent / mcp / data 三模块对 `core/` 的期望接口（给邝随行对齐）

> 作者：霍承钧（L4+L3 owner）。以下类型我**全部按架构文档 §2.2 / §5.2 / 数据库文档 §3.1·§12.3 的原文**使用。
> 我的三个模块**不自带这些类型的副本**——共享契约只能有一份，放在 `core/`，否则会出现同一 FQCN 双份类。
> 因此 `core/` 必须先于 `agent/ mcp/ data/` 落地（对应 W1）。

## 0. Outcome（core:common）

```kotlin
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>
}
inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R>
inline fun <T, R> Outcome<T>.bind(f: (T) -> Outcome<R>): Outcome<R>   // 我用 .bind() 展开
inline fun <T> Outcome<T>.getOrElse(f: (AppError) -> T): T
fun <T> Outcome<T>.getOrNull(): T?
```
`AppError(code: String, reasonKey: String, cause: Throwable? = null, severity: Severity = Severity.LOW)`

## 1. ModelPort 家族（core:model-api）—— 严格按架构 §2.2 原文

```kotlin
data class GenerationRequest(
    val stepId: StepId, val userTurn: String, val outputShape: OutputShape,
    val maxOutputTokens: Int, val thinking: ThinkingBudget, val deadlineMs: Long,
)
sealed interface OutputShape { data object AgentStep; data object FreeText; data class JsonSchema(val schema: String) }
sealed interface ThinkingBudget { data object Off; data class Capped(val tokens: Int) }
interface ModelPort {
    suspend fun load(config: ModelLoadConfig): Outcome<Unit>
    suspend fun unload(): Outcome<Unit>
    val runtimeState: Flow<ModelRuntimeState>
    suspend fun openSession(spec: SessionSpec): Outcome<ModelSession>
    fun estimateTokens(text: String): Int          // 必须本地估算，禁止跑模型
}
interface ModelSession : AutoCloseable {
    val sessionId: SessionId
    suspend fun generate(req: GenerationRequest): Outcome<GenerationResult>
    suspend fun currentCacheTokens(): Int
}
data class GenerationResult(
    val rawText: String, val toolCalls: List<RawToolCall>,
    val finishReason: FinishReason, val stats: InferenceStats,
)
data class InferenceStats(
    val prefillTokens: Int, val decodeTokens: Int, val ttftMs: Int, val decodeTps: Double,
)
```
`RawToolCall(name: String, argumentsJson: String, callId: String?)`

## 2. Port 家族（core:ports）—— 严格按架构 §2.2 原文

`DomPerceptionPort` / `ObserveOptions` / `ExtractHint` / `BrowserExecutorPort` / `ToolRegistryPort` /
`PolicyPort` / `PolicyVerdict` / `ActionContext` / `ContentSource` / `Sanitized` /
`DeviceHealthPort` / `MemoryTier` / `ThermalState` / `BatteryState` / `SocProfile` /
`TraceStorePort` / `MemoryStorePort` / `HumanGate` / `Resolved` / `ModelLifecyclePort`

**其中四条签名架构文档给了名字但没给形态，我按如下使用（请照此实现）：**

```kotlin
// §8.4 唯一闸口
interface PolicyPort {
    suspend fun authorize(action: StepAction, ctx: ActionContext): Outcome<PolicyVerdict>
    fun sanitize(text: String, source: ContentSource): Sanitized
}
enum class ContentSource(val maxLen: Int) { PAGE(2048), TOOL_RETURN(4096), USER(1024), MODEL(4096) }
data class Sanitized(val text: String, val redacted: Boolean, val flagged: Boolean = false, val tripwires: Int = 0)
data class ActionContext(val currentUrl: Url?, val task: TaskSpec, val currentDom: PocketDom?, val stepIndex: Int)

// 人机交互点（§3.2 humanGate.resolve）
interface HumanGate {
    suspend fun resolve(verdict: PolicyVerdict, action: StepAction): Resolved
}
sealed interface Resolved {
    data object Proceed : Resolved
    data class Reject(val reason: String) : Resolved
    data class Amend(val amended: StepAction) : Resolved
    data object Abort : Resolved
}
// 用户中止必须随时可用：再补一个「不问 verdict、直接查有没有中止信号」的入口
interface AbortSignal { fun isAborted(): Boolean }
```

## 3. 我需要 core:ports **新增**的 6 个持久化 Port（对齐数据库文档 §12.3）

```kotlin
interface TaskStorePort {                       // 返回 core:contract 类型，绝不返回 Room Entity
    suspend fun create(rec: TaskRecord)
    suspend fun advance(taskId: TaskId, patch: TaskBudgetPatch)
    suspend fun finish(taskId: TaskId, status: String, reason: String?, answerEnc: ByteArray?, answerPreview: String?)
    suspend fun load(taskId: TaskId): TaskRecord?
    fun observe(taskId: TaskId): Flow<TaskRecord?>
}
interface CheckpointStorePort {
    suspend fun save(cp: LoopCheckpoint)
    suspend fun load(taskId: TaskId): LoopCheckpoint?
    suspend fun clear(taskId: TaskId)
    suspend fun findResumable(): List<TaskId>
}
interface ObservationStorePort {
    fun append(summary: ObservationSummary, payload: String?): Boolean   // ★ 非挂起
    suspend fun loadPayload(observationId: String): String?
}
interface ToolPermissionStorePort {           // §8.5 逐工具授权，落 Room 不落 DataStore
    suspend fun isEnabled(toolId: ToolId): Boolean
    suspend fun isAlwaysAllow(toolId: ToolId): Boolean
    suspend fun grantAlwaysAllow(toolId: ToolId)
    suspend fun revokeAlwaysAllow(toolId: ToolId)
    suspend fun setEnabled(toolId: ToolId, enabled: Boolean)
    suspend fun upsertDiscovered(tools: List<ToolSpec>)
    fun catalog(): Flow<List<ToolSpec>>
}
interface PolicyDecisionSink {                 // 审计：所有策略决策必须落盘
    fun record(d: PolicyDecisionRecord): Boolean                        // ★ 非挂起
}
interface ModelAssetStorePort { suspend fun currentReady(): ModelAssetRecord? }
```

## 4. 我需要 core:contract **新增/确认**的记录类型

`TaskRecord` / `LoopCheckpoint` / `ObservationSummary` / `ToolInvocationSummary` /
`PolicyDecisionRecord` / `TraceEventRecord` / `ModelAssetRecord` —— 形状见 §12.3。
我在 `agent` / `mcp` / `data` 里按下列字段使用：

```kotlin
data class LoopCheckpoint(
    val taskId: TaskId,
    val updatedAt: Long,
    val statusSnapshot: String,
    val currentStepSeq: Int,
    val prefillTokensTotal: Long, val decodeTokensTotal: Long, val elapsedMsTotal: Long,
    val seenDigests: List<String>,
    val scratchpad: String?,
    val lastUrl: String?, val lastObservationId: String?, val lastKvCacheTokens: Int?,
    val compactionLastStep: Int?,
    val humanGatePending: Boolean, val humanGateJson: String?,
)
data class PolicyDecisionRecord(
    val id: String, val taskId: TaskId, val stepId: StepId?, val createdAt: Long,
    val decision: String /* ALLOW|REQUIRES_CONFIRM|DENIED */, val severity: String?,
    val reasonKey: String?, val reasonArgs: List<String>,
    val actionKind: String, val targetHost: String?, val riskClass: String?,
)
```

## 5. 需要根 `libs.versions.toml` 新增的坐标（请邝随行统一加，我改不了根文件）

```toml
mcpSdk    = "0.15.0"        # io.modelcontextprotocol:kotlin-sdk-client
ktor      = "3.5.1"         # ktor-client-okhttp / ktor-client-plugins-sse
room      = "2.8.5"
sqliteBundled = "2.7.1"
datastore = "1.2.1"
protobufKotlinLite = "4.32.1"
appfunctions = "1.0.0-alpha13"
litertlm  = "0.18.0"
```
