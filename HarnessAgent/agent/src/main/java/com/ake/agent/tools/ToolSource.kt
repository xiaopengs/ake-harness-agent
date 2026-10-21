package com.ake.agent.tools

import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec

/**
 * 工具来源的 SPI（架构 §7.1）。
 *
 * ## 为什么这个接口必须存在
 *
 * `ToolRegistryPort` 在 `core:ports`，它的实现要在 `agent`（L4）里做聚合；
 * 而 **MCP SDK 只能出现在 L3（`:mcp`）**。L3 不能反向依赖 L4，所以 `:mcp` 无法直接实现本接口。
 *
 * 解法是**在组合根（`:app`）里做一次 6 行的桥接**（见 `mcp/McpBridge.md`）：
 * `:mcp` 暴露一个签名完全同构的 `McpToolFacade`（不含任何 SDK 类型），
 * `:app` 把它包成 `ToolSource` 塞进 `Set<ToolSource>`。
 * 多出来的这一层不是形式主义 —— 它是「`agent` 里 grep 不到任何 MCP/Room 符号」这条硬约束的代价，
 * 而这条约束换来的是换掉整个 MCP 实现时 L4 一行不用改。
 *
 * ## 签名刻意与 `McpToolFacade` 完全同构
 *
 * 同构 ⇒ 桥接类是纯转发 ⇒ 桥接里不可能藏 bug。这是我们能接受「多一层」的唯一形态。
 */
interface ToolSource {

    val origin: ToolOrigin

    suspend fun discover(): List<ToolSpec>

    /**
     * @return 工具执行结果。**不得抛异常** —— 调用方在 `withTimeout` 里跑它，
     *         异常会把超时语义搅浑（分不清是超时还是工具炸了）。
     */
    suspend fun invoke(toolId: ToolId, args: Map<String, String>): ToolResult

    /** 逐工具启停（§7.2 / §8.5）。返回 false 表示这个来源不支持运行时启停。 */
    suspend fun setEnabled(toolId: ToolId, enabled: Boolean): Boolean = false
}
