package com.ake.core.ports

import com.ake.core.common.Outcome
import com.ake.core.common.Url
import com.ake.core.contract.BrowserAction
import com.ake.core.contract.PageHandle
import com.ake.core.contract.ExecutionResult
import com.ake.core.contract.PageEvent
import com.ake.core.contract.PageState
import com.ake.core.contract.PocketDom
import com.ake.core.contract.RecordTable
import kotlinx.coroutines.flow.Flow

/**
 * 感知与执行两条能力线（架构 §2.2）。
 *
 * **同层禁止横向依赖（R2）**：`DomPerceptionPort` 的实现绝对不许 import `:browser`。
 * 「当前加载的网页」这件事由 L4 的 AgentLoop 配对；真要共享句柄，就把句柄提升到 L2
 * （`PageHandle` 定义在 `:core:contract`），两边都只依赖 L2 —— 横向依赖自然消失。
 */

public data class ObserveOptions(
    val candidateTopK: Int = 48,
    val includeTextBelowFold: Boolean = false,
    val maxRecordsHint: Int = 40,
    val viewportExpansionPx: Int = 800,
)

/** `extractRecords` 的入参：抽数任务的快车道，不走 full DOM。 */
public data class ExtractHint(
    val selectorHint: String,
    val fields: List<String>,
    val limit: Int = 20,
    val page: PageHandle? = null,
)

public interface DomPerceptionPort {
    public suspend fun observe(opts: ObserveOptions): Outcome<PocketDom>

    /**
     * 抽数任务的快车道：直接给结构化行，不走 full DOM。
     *
     * **为什么必须有这条**：逐个读 DOM 去凑 60 个商品的三个字段，一来慢、二来容易丢字段，
     * 而列表页的数据本来就以重复子树的形式躺在页面上（架构 §5.4）。
     */
    public suspend fun extractRecords(hint: ExtractHint): Outcome<RecordTable>
}

public interface BrowserExecutorPort {
    public suspend fun execute(action: BrowserAction): Outcome<ExecutionResult>

    public suspend fun open(url: Url): Outcome<PageHandle>

    /** 页面稳定判定。不要 sleep —— 要检测加载完成 + JS 执行完成 + 动态内容静默。 */
    public suspend fun waitForSettled(timeoutMs: Long): Outcome<PageState>

    /** NAV_START / DOM_MUTATED / DIALOG / CONSOLE_ERR */
    public fun pageEvents(): Flow<PageEvent>
}

/**
 * ★ 把浏览器宿主视图交给 UI 的最小契约（MVP 只留口，不做 UI 接入）。
 *
 * ## 为什么句柄类型是 `Any?` 而不是 `WebView`
 *
 * `:core:ports` 是 **L2 纯 Kotlin、零 Android 依赖**（架构 §2.1 / R3）。
 * 一旦这里出现 `android.webkit.WebView`，`core:ports` 就变成了 Android 模块，
 * 所有依赖它的 L1/L2 单测都得搬上 Robolectric。
 * 所以类型在这里退化为 `Any?`，**唯一的 cast 点放在 L6 的 `AndroidView` 里** ——
 * 一个 cast 换整个 core 层的可测性，这笔交易是划算的。
 *
 * ## 为什么 MVP 默认可以是「无头」
 *
 * 端侧 Agent 的主链路（观察 → 决策 → 执行）**不需要把 WebView 显示出来**，
 * 无头跑既省一次布局，也避免把正在自动操作的页面暴露给用户造成困惑。
 * 所以 [isAttachable] 显式表达「当前有没有可展示视图」：
 * UI 拿到 false 就渲染「后台执行中」而不是去 cast 一个 null。
 *
 * 后续要做浏览器预览页时，实现侧（`:browser` 的 `BrowserRuntime`）已经就位，
 * UI 只需要 `AndroidView(factory = { handle.nativeView() as WebView })`。
 */
public interface BrowserViewHandle {

    /** 当前是否有可供 UI 挂载的视图。false = 无头执行中。 */
    public val isAttachable: Boolean

    /**
     * 交给 UI 挂载的原生视图句柄（Android 上即 `android.webkit.WebView`）。
     * 无头时为 null。
     */
    public fun nativeView(): Any?

    /** UI 把视图挂上容器（主线程）。MVP 默认 no-op。 */
    public fun onHostAttached(): Unit = Unit

    /** UI 把视图摘下（主线程）。MVP 默认 no-op。 */
    public fun onHostDetached(): Unit = Unit
}

/**
 * 工具注册表：本地工具与 MCP 工具在此收敛为同一形状。
 *
 * `discover()` 是唯一的「工具从哪来」入口 —— 这使得工具数量的变化不会波及任何上层。
 */
public interface ToolRegistryPort {
    public suspend fun discover(): Outcome<List<com.ake.core.contract.ToolSpec>>

    public suspend fun invoke(call: com.ake.core.contract.ToolCall): Outcome<com.ake.core.contract.ToolResult>

    public suspend fun enable(toolId: com.ake.core.contract.ToolId, enabled: Boolean)

    public fun catalog(): Flow<List<com.ake.core.contract.ToolSpec>>
}
