package com.ake.agent.tools

import com.ake.core.common.AkeLog
import com.ake.core.contract.ErrorCode
import com.ake.core.contract.RiskClass
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolOrigin
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
import com.ake.core.ports.MemoryStorePort
import com.ake.core.contract.MemoryCard

/**
 * MVP 必需的内置本地工具（架构 §7.1 `LocalToolAdapter` 一侧）。
 *
 * ## 为什么是「内置」而不是走 MCP
 *
 * 这三条是**主链路的一部分**：没有 `report_finish`，模型就只能靠 `{"act":"finish"}`
 * 这一条通道上报结果，而那条通道还要过溯源校验；没有 `export_records`，
 * 「整理前三条商品名/价格/店铺」这个头号用例就没有结构化出口。
 * **把它们放在 MCP 后面等于让主交付依赖一个实验特性** —— 架构 §7.3 已经明确
 * 「即使 MCP 侧出问题也不阻塞主交付」。
 *
 * ## 为什么参数是 `Map<String, String>`（`ToolCall.External` 的既定形状）
 *
 * 端侧模型的输出是扁平单动作（planner/Action 注释：decode 只有 52 tok/s，
 * schema 越深字段越多，格式错误率越高）。所以这里**刻意不引入嵌套参数**，
 * 复杂结构走「单个 JSON 字符串字段」这一种形态，由工具自己解析。
 *
 * ## 宿主回调
 *
 * 工具本身没有能力改变任务状态 —— 那属于 Loop。所以三个动作都回调 [LocalToolHost]，
 * 由 `AgentLoop` 决定「上报完成」到底意味着什么（要不要过溯源校验、要不要落库）。
 * 这层间接不是过度设计：**唯一知道 finish 语义的地方必须是 Loop**。
 */
class LocalToolSource(
    private val host: LocalToolHost,
    private val memory: MemoryStorePort? = null,
) : ToolSource {

    override val origin: ToolOrigin = ToolOrigin.LOCAL

    override suspend fun discover(): List<ToolSpec> = SPECS

    override suspend fun invoke(toolId: ToolId, args: Map<String, String>): ToolResult = when (toolId) {
        ID_REPORT_FINISH -> {
            val answer = args["answer"]
                ?: return ToolResult.Error(ErrorCode.BAD_ARGS, "missing 'answer'")
            val refs = args["refs"].orEmpty().split('|').filter { it.isNotBlank() }
            host.reportFinish(answer, refs)
        }

        ID_EXPORT_RECORDS -> {
            val payload = args["rows"]
                ?: return ToolResult.Error(ErrorCode.BAD_ARGS, "missing 'rows'")
            val format = args["format"]?.lowercase()?.takeIf { it in setOf("md", "csv", "json") } ?: "md"
            host.exportRecords(format, payload)
        }

        ID_REMEMBER -> {
            val store = memory
                ?: return ToolResult.Error(ErrorCode.UNSUPPORTED, "memory store unavailable")
            val text = args["text"] ?: return ToolResult.Error(ErrorCode.BAD_ARGS, "missing 'text'")
            val host0 = args["host"].orEmpty()
            val kind = args["kind"]?.uppercase() ?: "DOMAIN_NOTE"
            val card = MemoryCard(
                id = com.ake.core.common.Ulid.next(),
                host = host0,
                kind = kind,
                text = text,
                createdAtMillis = System.currentTimeMillis(),
            )
            return try {
                store.put(card)
                ToolResult.Text("已记住：$kind @${host0.ifBlank { "(global)" }}")
            } catch (t: Throwable) {
                AkeLog.w(TAG, "remember failed: ${t.message}")
                ToolResult.Error(ErrorCode.IO, t.message?.take(60))
            }
        }

        else -> ToolResult.Error(ErrorCode.NOT_FOUND, toolId.value)
    }

    override suspend fun setEnabled(toolId: ToolId, enabled: Boolean): Boolean =
        toolId in SPECS_BY_ID && enabled // 内置工具不允许被禁用：禁掉它们等于让主链路残缺

    companion object {
        const val TAG: String = "LocalTools"

        val ID_REPORT_FINISH: ToolId = ToolId("local.report_finish")
        val ID_EXPORT_RECORDS: ToolId = ToolId("local.export_records")
        val ID_REMEMBER: ToolId = ToolId("local.remember")

        private val SCHEMA_ANSWER = """{"type":"object","required":["answer"],"properties":{"answer":{"type":"string","description":"最终答案，只能由本轮页面观察中真实出现的内容拼出"},"refs":{"type":"string","description":"证据引用，用 | 分隔，如 rec[1].name|[8]"}}}"""

        private val SCHEMA_ROWS = """{"type":"object","required":["rows"],"properties":{"rows":{"type":"string","description":"结构化行，JSON 数组字符串，每行是字段名到值的对象"},"format":{"type":"string","enum":["md","csv","json"],"description":"导出格式，默认 md"}}}"""

        private val SCHEMA_REMEMBER = """{"type":"object","required":["text"],"properties":{"text":{"type":"string","description":"要记住的内容（站点结构笔记、用户偏好等）"},"host":{"type":"string","description":"限定生效域名，留空表示全局"},"kind":{"type":"string","enum":["PREFERENCE","FACT","SKILL_HINT","DOMAIN_NOTE"]}}}"""

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                id = ID_REPORT_FINISH,
                name = "report_finish",
                description = "上报任务最终结果。answer 必须只由本轮页面观察里真实出现过的内容拼出，禁止编造。",
                jsonSchema = SCHEMA_ANSWER,
                origin = ToolOrigin.LOCAL,
                riskClass = RiskClass.READ_ONLY,
            ),
            ToolSpec(
                id = ID_EXPORT_RECORDS,
                name = "export_records",
                description = "把已抽取到的结构化数据行导出为用户可读的表格（md/csv/json）。取数类任务收尾前调用它。",
                jsonSchema = SCHEMA_ROWS,
                origin = ToolOrigin.LOCAL,
                riskClass = RiskClass.READ_ONLY,
            ),
            ToolSpec(
                id = ID_REMEMBER,
                name = "remember",
                description = "把一条长期有用的信息记到记忆卡（站点结构笔记、用户偏好）。不要把敏感信息写进来。",
                jsonSchema = SCHEMA_REMEMBER,
                origin = ToolOrigin.LOCAL,
                riskClass = RiskClass.READ_ONLY,
            ),
        )

        val SPECS_BY_ID: Set<ToolId> = SPECS.mapTo(mutableSetOf()) { it.id }
    }
}

/**
 * 内置工具的宿主能力。**由 `AgentLoop` 实现** —— 只有它知道「任务完成」的完整语义。
 */
interface LocalToolHost {
    suspend fun reportFinish(answer: String, refs: List<String>): ToolResult

    suspend fun exportRecords(format: String, payloadJson: String): ToolResult

    /** 当前页面 host，供 `remember` 默认作用域。 */
    fun currentHost(): String = ""
}
