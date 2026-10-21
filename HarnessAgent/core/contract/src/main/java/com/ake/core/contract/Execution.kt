package com.ake.core.contract

import com.ake.core.common.Url

/**
 * 执行器产出的值对象。
 *
 * **`PageHandle` 为什么放在 `:core:contract` 而不是 `:browser`**：
 * 架构 §2.3 明确说 —— 两个 L3 能力必须共享「页面句柄」时，正确解法是把句柄**提升到 L2 领域模型**，
 * `browser.open()` 产出它、`perception` 消费它，两边都只依赖 L2，横向依赖（违反 R2）自然消失。
 */

public data class PageHandle(
    val id: String,
    val url: Url,
    val openedAtMillis: Long,
)

/**
 * 一次动作执行后的观察。
 *
 * `before` / `after` 两个 digest 是给上层做「这一步到底有没有改变页面」的判定依据 ——
 * 循环 detection 依赖它，**不能靠「返回值说成功」来判断**（click 一个 div 永远返回成功）。
 */
public data class ExecutionResult(
    val observationId: String?,
    val before: PageDigest?,
    val after: PageDigest?,
    val url: Url?,
    val pageState: PageState,
    val change: PageChangeKind,
    val addedCandidates: Int,
    val note: String? = null,
)

public sealed interface PageEvent {
    public data class NavStarted(val url: Url) : PageEvent

    public data class NavFinished(val url: Url) : PageEvent

    public data object DomMutated : PageEvent

    /** SPA 路由变化：`onPageFinished` 不触发，只能靠 URL 变化 + MutationObserver 感知（§5.8）。 */
    public data class UrlChanged(val url: Url) : PageEvent

    public data class Dialog(val kind: String, val message: String?) : PageEvent

    public data class ConsoleError(val message: String) : PageEvent

    /** 页面进入稳定（加载完成 + JS 执行完 + 动态内容静默）。 */
    public data object Settled : PageEvent

    /** 防爬 / 风控：识别到就该转人工，明确不绕过（宪章第 6 条）。 */
    public data class Blocked(val reason: String) : PageEvent
}
