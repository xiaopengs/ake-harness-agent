package com.ake.mcp

import com.ake.core.contract.ErrorCode
import com.ake.core.contract.RiskClass
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * `McpSchemaMapper` 的单测。
 *
 * ## 为什么它值得 20 条断言
 *
 * 它是 `:mcp` 里**唯一的 SDK ↔ 契约转换点**，而它的输出有两个去向，两个都不能出错：
 *
 *  1. `ToolSpec.jsonSchema` / `description` 会原样写进 SYSTEM 段的 TOOL_SECTION ——
 *     一份错误的 schema 不会报错，只会让模型**稳定地用错参数**，症状离现场很远；
 *  2. `ToolResult` 会进 TOOL_RETURN 段，而 STANDARD 档只有 768 token ——
 *     不在这里按预算裁剪，就会在 ContextAssembler 里被尾部截断，而**结论常在末尾**。
 *
 * 所以它测的不是「能不能转」，而是三条边界：**缺信息怎么兜、越界怎么裁、异常怎么降**。
 */
class McpSchemaMapperTest {

    // ──────────────────────────────────────────── 工具 id

    @Test
    fun `工具 id 归一化：小写 + 去空格 + mcp 前缀`() {
        // 小写是刻意的：server 侧可能给 `FS.Read`，而授权记录按 id 存，
        // 大小写漂移会让「已授权」变成「未授权」。
        assertThat(McpSchemaMapper.toolIdOf("local", "FS.Read")).isEqualTo(ToolId("mcp.local.fs.read"))
        assertThat(McpSchemaMapper.toolIdOf("local", "  spaced  ")).isEqualTo(ToolId("mcp.local.spaced"))
    }

    @Test
    fun `工具 id 带 server 命名空间：授权归属在 id 上就可读`() {
        assertThat(McpSchemaMapper.toolIdOf("fs", "read").value).startsWith("mcp.fs.")
        assertThat(McpSchemaMapper.toolIdOf("mail", "read").value).startsWith("mcp.mail.")
        assertThat(McpSchemaMapper.toolIdOf("fs", "read")).isNotEqualTo(McpSchemaMapper.toolIdOf("mail", "read"))
    }

    // ──────────────────────────────────────────── Tool → ToolSpec

    @Test
    fun `toToolSpec 基本字段映射正确`() {
        val spec = McpSchemaMapper.toToolSpec(
            "local",
            tool(name = "fs.read", description = "读取文件"),
        )
        assertThat(spec.id).isEqualTo(ToolId("mcp.local.fs.read"))
        assertThat(spec.name).isEqualTo("fs.read")
        assertThat(spec.description).isEqualTo("读取文件")
        assertThat(spec.origin).isEqualTo(ToolOrigin.MCP)
        assertThat(spec.enabled).isTrue()
        assertThat(spec.jsonSchema).contains("\"object\"")
    }

    @Test
    fun `缺 description 时兜一句而不是给模型空壳工具`() {
        val spec = McpSchemaMapper.toToolSpec("local", tool(name = "fs.stat", description = null))
        assertThat(spec.description).contains("fs.stat")
        assertThat(spec.description).contains("local")
        assertThat(spec.description).contains("no description")
        assertThat(spec.description).isNotEmpty()
    }

    @Test
    fun `description 超长时截断到 400 字符`() {
        val spec = McpSchemaMapper.toToolSpec("local", tool(name = "x", description = "描".repeat(5_000)))
        assertThat(spec.description).hasLength(400)
    }

    @Test
    fun `嵌套 schema 原样保留而不做简化`() {
        // 类注释定过调子：MCP 的 schema 已经是 {type, properties, required} 扁平形态，
        // 比 OpenAPI 更适合小模型。多一次转换只会**丢信息**，所以这里断言「原样带回来」。
        val spec = McpSchemaMapper.toToolSpec(
            "local",
            tool(
                name = "query",
                description = "查询",
                properties = JsonObject(
                    mapOf(
                        "path" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                        "opts" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(
                                    mapOf("depth" to JsonObject(mapOf("type" to JsonPrimitive("integer")))),
                                ),
                            ),
                        ),
                    ),
                ),
                required = listOf("path"),
            ),
        )
        val parsed = Json.parseToJsonElement(spec.jsonSchema).jsonObject
        assertThat(parsed["type"]?.jsonPrimitive?.content).isEqualTo("object")
        assertThat(spec.jsonSchema).contains("depth")
        assertThat(spec.jsonSchema).contains("integer")
        assertThat(spec.jsonSchema).contains("\"required\"")
        assertThat(spec.jsonSchema).contains("path")
    }

    @Test
    fun `schema 序列化失败时降级为空对象 schema 而不是 null`() {
        // ★ 这条断言锁的是**降级策略**：宁可给空对象，也不要把 null 写进 SYSTEM 段。
        //   触发手段：kotlinx.serialization 的 Json 默认拒绝编码 NaN（`useSpecialFloatingPointValues=false`），
        //   于是 `encodeToString` 会抛 —— 这正是 `runCatching` 那一支存在的理由。
        val broken = ToolSchema(
            schema = "object",
            properties = JsonObject(mapOf("weird" to JsonPrimitive(Double.NaN))),
        )
        val encoded = McpSchemaMapper.encodeSchema(broken)
        assertThat(encoded).isEqualTo("""{"type":"object","properties":{}}""")
    }

    // ──────────────────────────────────────────── 风险分级

    @Test
    fun `destructiveHint 优先于 readOnlyHint`() {
        // 恶意 server 完全可以同时声明「只读」和「无害」—— 冲突时按高的来。
        assertThat(McpSchemaMapper.riskOf(tool(name = "rm", annotations = ToolAnnotations(destructiveHint = true, readOnlyHint = true))))
            .isEqualTo(RiskClass.SENSITIVE)
    }

    @Test
    fun `readOnlyHint 且未声明 destructive → READ_ONLY`() {
        assertThat(McpSchemaMapper.riskOf(tool(name = "get", annotations = ToolAnnotations(readOnlyHint = true))))
            .isEqualTo(RiskClass.READ_ONLY)
    }

    @Test
    fun `没有 annotations → EXTERNAL_SIDE_EFFECT（就高不就低）`() {
        assertThat(McpSchemaMapper.riskOf(tool(name = "op", annotations = null)))
            .isEqualTo(RiskClass.EXTERNAL_SIDE_EFFECT)
    }

    @Test
    fun `annotations 存在但两个 hint 都没给 → 仍按最保守档`() {
        assertThat(McpSchemaMapper.riskOf(tool(name = "op2", annotations = ToolAnnotations(title = "x"))))
            .isEqualTo(RiskClass.EXTERNAL_SIDE_EFFECT)
    }

    // ──────────────────────────────────────────── CallToolResult → ToolResult

    @Test
    fun `isError 为真 → Error(UNKNOWN) 且消息截断到 240`() {
        val res = McpSchemaMapper.toToolResult(
            CallToolResult(content = listOf(TextContent(text = "E".repeat(1_000))), isError = true),
        )
        assertThat(res).isInstanceOf(ToolResult.Error::class.java)
        assertThat(res.codeOrNull()).isEqualTo(ErrorCode.UNKNOWN)
        assertThat((res as ToolResult.Error).message).hasLength(240)
    }

    @Test
    fun `isError 为真但没有文本 → 兜底文案而不是空消息`() {
        val res = McpSchemaMapper.toToolResult(CallToolResult(content = emptyList(), isError = true))
        assertThat(res).isInstanceOf(ToolResult.Error::class.java)
        assertThat((res as ToolResult.Error).message).isEqualTo("mcp tool reported error")
    }

    @Test
    fun `空内容 → Noop（循环检测要看得到它）`() {
        assertThat(McpSchemaMapper.toToolResult(CallToolResult(content = emptyList())))
            .isEqualTo(ToolResult.Noop)
    }

    @Test
    fun `空字符串内容也算 Noop`() {
        assertThat(McpSchemaMapper.toToolResult(CallToolResult(content = listOf(TextContent(text = "   ")))))
            .isEqualTo(ToolResult.Noop)
    }

    @Test
    fun `正常文本 → Text 且不做裁剪`() {
        val res = McpSchemaMapper.toToolResult(CallToolResult(content = listOf(TextContent(text = "hello"))))
        assertThat(res).isEqualTo(ToolResult.Text("hello"))
    }

    @Test
    fun `多文本块用换行拼接`() {
        val res = McpSchemaMapper.toToolResult(
            CallToolResult(content = listOf(TextContent(text = "a"), TextContent(text = "b"))),
        )
        assertThat(res).isEqualTo(ToolResult.Text("a\nb"))
    }

    @Test
    fun `非文本块被显式标注而不是静默丢弃`() {
        val text = McpSchemaMapper.extractText(
            listOf(
                TextContent(text = "正文"),
                ImageContent(data = "AAAA", mimeType = "image/png"),
            ),
        )
        assertThat(text).startsWith("正文")
        assertThat(text).contains("已省略 1 个非文本块")
    }

    @Test
    fun `只有非文本块时给出提示文本而不是 Noop`() {
        // ★ 这是一条容易被误改的分支：提示文本本身非空 ⇒ `toToolResult` 走 Text 而不是 Noop。
        //   模型因此会知道「这次调用返回了图片，但我看不到」，而不是「什么都没发生」——
        //   后者会让它原地重试同样的调用。
        val res = McpSchemaMapper.toToolResult(
            CallToolResult(content = listOf(ImageContent(data = "AAAA", mimeType = "image/png"))),
        )
        assertThat(res).isInstanceOf(ToolResult.Text::class.java)
        assertThat((res as ToolResult.Text).value).contains("已省略 1 个非文本块")
    }

    @Test
    fun `超长结果按预算裁剪且保留头尾`() {
        val head = "H".repeat(100)
        val tail = "T".repeat(100)
        val text = head + "M".repeat(20_000) + tail

        val res = McpSchemaMapper.toToolResult(CallToolResult(content = listOf(TextContent(text = text))))

        assertThat(res).isInstanceOf(ToolResult.Text::class.java)
        val clipped = (res as ToolResult.Text).value
        assertThat(clipped).startsWith(head)
        assertThat(clipped).endsWith(tail)
        assertThat(clipped).contains("中间省略")
        // 预算是**硬上限**：裁剪后绝不能超过 6_000（+几字符的数字位数容差）
        assertThat(clipped.length).isAtMost(6_000)
    }

    // ──────────────────────────────────────────── clip 的三条分支

    @Test
    fun `clip 在长度不超过预算时原样返回`() {
        assertThat(McpSchemaMapper.clip("abc", 100)).isEqualTo("abc")
        assertThat(McpSchemaMapper.clip("abc", 3)).isEqualTo("abc")
    }

    @Test
    fun `clip 保留头部与尾部，并标注省略数量`() {
        val text = "A".repeat(600) + "B".repeat(10_000) + "C".repeat(383)
        val clipped = McpSchemaMapper.clip(text, 1_000)

        // 不写死 383：省略标记的长度取决于省略数的位数，让断言从结果里反推并自洽校验
        val omitted = Regex("中间省略 (\\d+) 字符").find(clipped)!!.groupValues[1].toInt()
        val tag = "\n…[中间省略 $omitted 字符]…\n"
        val tail = 1_000 - 600 - tag.length

        assertThat(omitted).isEqualTo(text.length - 600 - tail)
        assertThat(clipped).isEqualTo("A".repeat(600) + tag + "C".repeat(tail))
        // ★ 硬上限：输出长度必须恰好等于预算，多一个字符都会挤占 TOOL_RETURN 的 768 token
        assertThat(clipped.length).isEqualTo(1_000)
    }

    @Test
    fun `clip 在省略数位数变化时仍不超预算`() {
        // 4 位与 6 位省略数：旧实现会分别超出 1 / 3 个字符
        for (size in listOf(11_000, 200_000)) {
            val clipped = McpSchemaMapper.clip("Z".repeat(size), 6_000)
            assertWithMessage("原文长度=$size 的裁剪结果超预算")
                .that(clipped.length)
                .isAtMost(6_000)
        }
    }

    @Test
    fun `clip 在预算小到放不下省略标记时退化为硬截断`() {
        // head = 10*60/100 = 6，tail = 10 - 6 - 17 = -13 ≤ 0 ⇒ 走 substring(0, maxChars)
        val clipped = McpSchemaMapper.clip("0123456789ABCDEF", 10)
        assertThat(clipped).isEqualTo("0123456789")
        assertThat(clipped).doesNotContain("省略")
    }

    private fun ToolResult.codeOrNull(): ErrorCode? = (this as? ToolResult.Error)?.code
}
