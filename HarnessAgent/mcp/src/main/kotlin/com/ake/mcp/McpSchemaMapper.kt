package com.ake.mcp

import com.ake.core.contract.RiskClass
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MCP SDK 类型 ↔ `:core:contract` 类型的**唯一转换点**。
 *
 * **整个 `:mcp` 模块里，只有本文件允许出现 `io.modelcontextprotocol.*` 的 import。**
 * 这条纪律可以用一行 grep 卡在 CI 里：
 *
 * ```
 * rg -l "io\.modelcontextprotocol" mcp/src --glob '!McpSchemaMapper.kt' --glob '!McpConnectionManager.kt'
 * ```
 *
 * 为什么值得这么严格：SDK 是 `0.x`，四个月内出过重命名级破坏变更（参考 AppFunctions alpha10→13）。
 * 把 SDK 收敛到两个文件，将来升级时改动面是可枚举的。
 *
 * ## 关于「模型可理解的 tool 定义」
 *
 * `ToolSpec.jsonSchema` 会原样写进 SYSTEM 段（`planner/SystemCharter` 的 TOOL_SECTION）。
 * 所以这里**不做 schema 简化**：MCP 的 `ToolSchema` 已经是 `{type, properties, required}` 这种
 * 扁平形态，比 OpenAPI 更适合小模型 —— 多一次转换只会丢信息。
 */
internal object McpSchemaMapper {

    /** `encodeDefaults = true` 是必须的：不写 `type:"object"` 会让某些 server 的校验拒绝入参。 */
    private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }

    /** 工具 id 命名空间。`mcp.<serverId>.<name>` 让「这条授权属于哪台 server」在 id 上就可读。 */
    fun toolIdOf(serverId: String, toolName: String): ToolId =
        ToolId("mcp.$serverId.${toolName.trim().lowercase()}")

    fun toToolSpec(serverId: String, tool: Tool): ToolSpec = ToolSpec(
        id = toolIdOf(serverId, tool.name),
        name = tool.name,
        // 描述直接决定调用质量。server 没给描述时兜一句，避免模型拿到一个空壳工具。
        description = tool.description?.take(MAX_DESCRIPTION)
            ?: "MCP tool '${tool.name}' from server '$serverId' (no description provided).",
        jsonSchema = encodeSchema(tool.inputSchema),
        origin = ToolOrigin.MCP,
        riskClass = riskOf(tool),
        enabled = true,
    )

    /**
     * 风险分级（架构 §7.1：「由注册方声明 + `PolicyPort` 可覆写」）。
     *
     * MCP 的 `ToolAnnotations` 是**提示**不是保证 —— 恶意 server 完全可以声明自己是 readOnly。
     * 所以这里的原则是**就高不就低**：
     *  - 没声明 ⇒ 按 `EXTERNAL_SIDE_EFFECT`（最保守），由 `PolicyPort` 决定是否降到确认档；
     *  - 声明 `destructiveHint=true` ⇒ `SENSITIVE`；
     *  - 声明 `readOnlyHint=true` 且没声明 destructive ⇒ `READ_ONLY`。
     *
     * 宁可多问一次用户，也不要因为相信 server 的自述而放行一个破坏性操作。
     */
    fun riskOf(tool: Tool): RiskClass {
        val a = tool.annotations
        return when {
            a?.destructiveHint == true -> RiskClass.SENSITIVE
            a?.readOnlyHint == true -> RiskClass.READ_ONLY
            a == null -> RiskClass.EXTERNAL_SIDE_EFFECT
            else -> RiskClass.EXTERNAL_SIDE_EFFECT
        }
    }

    fun encodeSchema(schema: ToolSchema): String =
        runCatching { JSON.encodeToString(schema) }
            .getOrElse { FALLBACK_SCHEMA }

    /**
     * `CallToolResult` → `ToolResult`。
     *
     * ## 为什么要裁剪
     *
     * 工具返回可能很大（一个 `fs.read` 就是几百 KB）。它最终会进 TOOL_RETURN 段
     * （STANDARD 档只有 768 token）。**不在这里裁剪，就会在 ContextAssembler 里被尾部截断** ——
     * 而尾部截断会丢掉最有价值的部分（结果通常是「前面一堆噪音，最后才是答案」）。
     * 所以这里给的是**头部保留 + 尾部保留**的双段裁剪，并显式标注被切掉多少。
     */
    fun toToolResult(res: CallToolResult, maxChars: Int = MAX_RESULT_CHARS): ToolResult {
        val isError = res.isError == true
        val text = extractText(res.content)

        if (isError) {
            return ToolResult.Error(
                com.ake.core.contract.ErrorCode.UNKNOWN,
                text.take(ERROR_MESSAGE_CHARS).ifBlank { "mcp tool reported error" },
            )
        }
        if (text.isBlank()) return ToolResult.Noop

        return ToolResult.Text(clip(text, maxChars))
    }

    /** 只取文本内容。图片/音频/资源链接在端侧 MVP 没有消费方，显式说明而不是静默丢弃。 */
    fun extractText(blocks: List<ContentBlock>): String {
        val sb = StringBuilder()
        var skipped = 0
        for (b in blocks) {
            when (b) {
                is TextContent -> {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(b.text)
                }

                else -> skipped++
            }
        }
        if (skipped > 0) sb.append("\n[已省略 $skipped 个非文本块：本版本只消费文本]")
        return sb.toString()
    }

    /**
     * 双段裁剪：保留头部与尾部，中间用显式省略标记。
     *
     * 为什么不是简单「从尾部裁」：工具返回的**错误原因与结论常在末尾**，
     * 而**标识性信息（文件路径、查询条件）在开头**。两端都要。
     */
    internal fun clip(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = maxChars * HEAD_RATIO / 100
        val tail = maxChars - head - OMISSION_TAG.length
        if (tail <= 0) return text.substring(0, maxChars)
        return text.substring(0, head) +
            OMISSION_TAG.replace("{n}", (text.length - head - tail).toString()) +
            text.substring(text.length - tail)
    }

    private const val MAX_DESCRIPTION: Int = 400
    private const val MAX_RESULT_CHARS: Int = 6_000
    private const val ERROR_MESSAGE_CHARS: Int = 240
    private const val HEAD_RATIO: Int = 60
    private const val OMISSION_TAG: String = "\n…[中间省略 {n} 字符]…\n"

    /** schema 序列化失败时的兜底：宁可给一个空对象，也不要把 null 写进 SYSTEM 段。 */
    private const val FALLBACK_SCHEMA: String = """{"type":"object","properties":{}}"""
}
