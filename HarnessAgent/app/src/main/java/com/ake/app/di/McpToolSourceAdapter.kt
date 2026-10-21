package com.ake.app.di

import com.ake.agent.tools.ToolSource
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
import com.ake.mcp.McpToolFacade
import javax.inject.Inject

/**
 * `:mcp`（L3）与 `:agent`（L4）之间的六行桥。
 * 纯转发 —— 这里加任何逻辑都说明放错地方了。
 *
 * 代码原文来自 `mcp/MCP_BRIDGE.md`（霍承钧），未作改动，仅补齐注释与 `@JvmStatic` 之外的导入。
 */
public class McpToolSourceAdapter @Inject constructor(
    private val facade: McpToolFacade,
) : ToolSource {

    override val origin: ToolOrigin = ToolOrigin.MCP

    override suspend fun discover(): List<ToolSpec> = facade.discover()

    override suspend fun invoke(toolId: ToolId, args: Map<String, String>): ToolResult =
        facade.invoke(toolId, args)

    override suspend fun setEnabled(toolId: ToolId, enabled: Boolean): Boolean {
        facade.setEnabled(toolId, enabled)
        return true
    }
}
