package com.ake.agent.tools

import com.ake.core.contract.ErrorCode
import com.ake.core.contract.ToolResult

/**
 * [LocalToolHost] 的默认实现 —— 内置工具与主循环之间的**唯一交接点**。
 *
 * ## 为什么是「收集器」而不是让工具直接改 LoopState
 *
 * `local.report_finish` / `local.export_records` 是在 `ToolSource.invoke` 里被调用的，
 * 那一刻 Loop 正处在「执行中」的中间态。让工具直接写 LoopState 等于把内部状态暴露给工具层，
 * 之后任何「谁改了 stepSeq」的问题都会变成全局搜索。
 *
 * 收集器把这件事收敛成一句话：**工具只能往这里放结果，Loop 决定这个结果意味着什么。**
 * 于是「模型用内置工具上报了结果 ⇒ 等同于 finish」这条规则有唯一一处实现。
 *
 * ## 幂等
 *
 * `take*()` 取走即清空：同一步里模型重复调用 `report_finish` 不会让 Loop 收到两份答案。
 */
class ResultCollector : LocalToolHost {

    @Volatile
    private var answer: Pair<String, List<String>>? = null

    @Volatile
    private var export: String? = null

    @Volatile
    private var host: String = ""

    override suspend fun reportFinish(answer: String, refs: List<String>): ToolResult {
        if (answer.isBlank()) return ToolResult.Error(ErrorCode.BAD_ARGS, "answer must not be blank")
        this.answer = answer to refs
        return ToolResult.Text("已收到结果（${answer.length} 字 / ${refs.size} 条证据）。请调用 finish 结束任务。")
    }

    override suspend fun exportRecords(format: String, payloadJson: String): ToolResult {
        if (payloadJson.isBlank()) return ToolResult.Error(ErrorCode.BAD_ARGS, "rows must not be blank")
        val text = renderTable(format, payloadJson)
        this.export = text
        return ToolResult.Text("已导出 ${format.uppercase()} 表格（${text.length} 字符）。")
    }

    override fun currentHost(): String = host

    fun setHost(h: String) {
        host = h
    }

    fun takeAnswer(): Pair<String, List<String>>? = answer.also { answer = null }

    fun takeExport(): String? = export

    companion object {

        /**
         * 表格渲染。刻意**不用 JSON 库解析**：
         *  - 端侧模型给出的 `rows` 很可能不是严格 JSON（缺引号、尾逗号），严格解析会让导出频繁失败；
         *  - 导出是给人看的，格式退化（原样输出）比报错好得多。
         *
         * 所以这里做的是「尽力而为的规整」：能按行拆就拆，拆不动就原样附上。
         */
        internal fun renderTable(format: String, payload: String): String {
            val rows = payload.split(';', '\n')
                .map { it.trim().trim('[', ']', '{', '}') }
                .filter { it.isNotBlank() }
            if (rows.isEmpty()) return payload.take(8_192)

            return when (format) {
                "csv" -> rows.joinToString("\n") { row ->
                    row.split(',').joinToString(",") { cell -> csvCell(cell) }
                }

                "json" -> "[" + rows.joinToString(",") { "{$it}" } + "]"

                else -> { // md
                    val cells = rows.map { row ->
                        row.split(',').map { it.substringAfter(':').trim().trim('"', '\'', ' ') }
                    }
                    val width = cells.maxOfOrNull { it.size } ?: 0
                    if (width == 0) return payload.take(8_192)
                    val sb = StringBuilder()
                    sb.append("| ").append((0 until width).joinToString(" | ") { "c$it" }).append(" |\n")
                    sb.append("| ").append((0 until width).joinToString(" | ") { "---" }).append(" |\n")
                    for (r in cells) {
                        sb.append("| ").append((0 until width).joinToString(" | ") { r.getOrNull(it).orEmpty() })
                            .append(" |\n")
                    }
                    sb.toString()
                }
            }.take(8_192)
        }

        private fun csvCell(raw: String): String {
            val v = raw.substringAfter(':').trim().trim('"', '\'')
            return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v
        }
    }
}
