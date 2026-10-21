package com.ake.core.contract

/**
 * 工具生态的统一形状（架构 §7.1）。
 *
 * **关键设计：模型侧看到的永远是同一份 `ToolSpec`，不关心背后是本地 Kotlin 函数还是 MCP server。**
 * 这让 PolicyPort 只需在一处把关、tracing 只需在一个点埋 —— 安全代码写在越少的地方越好，
 * 因为它是唯一不能出错的那类代码。
 */

@JvmInline
public value class ToolId(public val value: String)

public enum class ToolOrigin { LOCAL, MCP }

/**
 * 风险等级。**由注册方声明 + `PolicyPort` 可覆写**。
 * 明确要求：不要在 UI 层或 Agent 层判断风险 —— 那是护栏的职责（架构 §7.1）。
 */
public enum class RiskClass {
    READ_ONLY,
    PAGE_MUTATING,
    SENSITIVE,
    EXTERNAL_SIDE_EFFECT,
    FORBIDDEN,
}

public data class ToolSpec(
    /** "browser.click" / "mcp.local.fs.read" */
    val id: ToolId,
    val name: String,
    /** ★ 写给模型看的，直接决定调用质量 */
    val description: String,
    /** OpenAPI-style parameters schema */
    val jsonSchema: String,
    val origin: ToolOrigin,
    val riskClass: RiskClass,
    val enabled: Boolean = true,
)

/** ToolRegistry 的统一入口。本地工具与 MCP 工具在此收敛。 */
public sealed interface ToolCall {

    public data class Browser(val action: BrowserAction) : ToolCall

    public data class External(val toolId: ToolId, val args: Map<String, String>) : ToolCall
}

public enum class ErrorCode(public val shortName: String) {
    STALE("STALE"),
    NOT_FOUND("NOT_FOUND"),
    TIMEOUT("TIMEOUT"),
    DENIED("DENIED"),
    INTERRUPTED("INTERRUPTED"),
    IO("IO"),
    BLOCKED("BLOCKED"),
    BAD_ARGS("BAD_ARGS"),
    DISABLED("DISABLED"),
    UNSUPPORTED("UNSUPPORTED"),
    UNKNOWN("UNKNOWN"),
}

/**
 * 工具返回值。**刻意做成一个很窄的封闭集合**（六个子类型）：
 *
 * 它的唯一消费目标是 `DeterministicCompactor` 的压缩行 —— 压缩逻辑要对它做穷尽 `when`。
 * 子集越窄，「新增一种返回」就越会被编译器逼着去更新压缩器，
 * 而不是悄悄写进一个 `else -> ""` 的黑洞里丢失信息（架构 §4.5）。
 */
public sealed interface ToolResult {

    /** 「成功」定义得非常保守：只要不是 Error，`isOk` 就为 true。Noop 属于成功但不进展。 */
    public val isOk: Boolean get() = this !is Error

    /** 失败时给模型的提示语（≤ ~40 字符，会进 RECENT_TRACE 预算）。 */
    public fun hintBrief(): String

    public data class DomChanged(val addedCandidates: Int) : ToolResult {
        override fun hintBrief(): String = "页面已更新（+${addedCandidates} 个候选）"
    }

    public data class UrlMoved(val host: String) : ToolResult {
        override fun hintBrief(): String = "页面已跳转"
    }

    public data class RecordsGot(val count: Int) : ToolResult {
        override fun hintBrief(): String = "取到 $count 条记录"
    }

    /** 成功执行但没有产生任何可观察变化 —— 循环检测要看得到它，否则会误判为「有进展」。 */
    public data object Noop : ToolResult {
        override fun hintBrief(): String = "执行成功但页面无变化，请改用其它动作"
    }

    public data class Error(val code: ErrorCode, val message: String? = null) : ToolResult {
        override fun hintBrief(): String = message?.take(48) ?: when (code) {
            ErrorCode.STALE -> "元素已失效，编号可能变了"
            ErrorCode.NOT_FOUND -> "目标元素未找到"
            ErrorCode.TIMEOUT -> "执行超时"
            ErrorCode.DENIED -> "操作被安全策略拒绝"
            ErrorCode.INTERRUPTED -> "已被用户中止"
            ErrorCode.BLOCKED -> "页面处于不可操作状态"
            ErrorCode.BAD_ARGS -> "参数不合法"
            ErrorCode.DISABLED -> "该工具已被禁用"
            ErrorCode.UNSUPPORTED -> "该动作不被支持"
            ErrorCode.IO -> "设备 IO 失败"
            ErrorCode.UNKNOWN -> "未知错误"
        }
    }

    public data class Text(val value: String) : ToolResult {
        override fun hintBrief(): String = value.take(48)
    }
}
