package com.ake.mcp

import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.ToolPermissionStorePort
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `:mcp` 单测共用的构造糖。
 *
 * 集中放这里的理由：SDK 的 `Tool` / `ToolSchema` 是**九参构造**且带默认参数，
 * 散在各个测试文件里会让「这条断言到底在测什么」被构造噪声盖住。
 * 这里一律只暴露**与断言有关的那几个字段**，其余走 SDK 默认值。
 */

internal fun tool(
    name: String,
    description: String? = null,
    properties: JsonObject = JsonObject(emptyMap()),
    required: List<String>? = null,
    annotations: ToolAnnotations? = null,
): Tool = Tool(
    name = name,
    inputSchema = ToolSchema(schema = "object", properties = properties, required = required),
    description = description,
    annotations = annotations,
)

/** 一个 `{ "type": "string" }` 形状的属性表，`schemaOf("path")` ⇒ `{"path":{"type":"string"}}`。 */
internal fun schemaOf(vararg names: String): JsonObject = JsonObject(
    names.associateWith {
        JsonObject(mapOf("type" to JsonPrimitive("string")))
    },
)

internal fun textBlock(text: String): TextContent = TextContent(text = text)

/**
 * `ToolPermissionStorePort` 的内存实现（测试替身）。
 *
 * 只记录**被调用了什么**，不做任何策略判断 ——
 * 授权策略属于 `PolicyPort`，这里多判断一点就会在测试里长出第二个真相来源。
 */
internal class FakePermissionStore : ToolPermissionStorePort {

    val discovered: MutableList<List<ToolSpec>> = mutableListOf()
    val setEnabledCalls: MutableList<Pair<ToolId, Boolean>> = mutableListOf()
    val grants: MutableList<ToolId> = mutableListOf()
    val revokes: MutableList<ToolId> = mutableListOf()

    private val enabled = mutableMapOf<ToolId, Boolean>()
    private val always = mutableMapOf<ToolId, Boolean>()

    override suspend fun isEnabled(toolId: ToolId): Boolean = enabled[toolId] ?: true

    override suspend fun isAlwaysAllow(toolId: ToolId): Boolean = always[toolId] ?: false

    override suspend fun grantAlwaysAllow(toolId: ToolId) {
        always[toolId] = true
        grants += toolId
    }

    override suspend fun revokeAlwaysAllow(toolId: ToolId) {
        always[toolId] = false
        revokes += toolId
    }

    override suspend fun setEnabled(toolId: ToolId, enabled: Boolean) {
        this.enabled[toolId] = enabled
        setEnabledCalls += toolId to enabled
    }

    override suspend fun upsertDiscovered(tools: List<ToolSpec>) {
        discovered += tools
    }

    override fun catalog(): Flow<List<ToolSpec>> = emptyFlow()
}
