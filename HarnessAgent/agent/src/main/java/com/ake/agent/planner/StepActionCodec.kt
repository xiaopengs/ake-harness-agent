package com.ake.agent.planner

import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolId

/**
 * `StepAction` ↔ 紧凑 JSON 的编解码。
 *
 * ## 为什么需要它
 *
 * `core:ports/TraceStorePort.load(taskId): List<StepRecord>` 要求能**还原出 `StepAction`**，
 * 而 `StepRecord.action` 是非空密封接口。断点恢复要用这段历史做上下文摘要（§3.5 最低实现），
 * 所以 `:data` 的 `step` 表里必须存一份可还原的动作原文（我为此在 `StepEntity` 增补了 `action_json` 列）。
 *
 * ## 为什么编解码逻辑在 `:agent` 而不是 `:data`
 *
 * 动作的 JSON 形状是 **Action DSL 的一部分**，归 `planner` 所有（见 `SystemCharter.ACTION_DSL`）。
 * 让 `:data`（L3）去认识 DSL 等于把 L4 的知识下沉，将来改 DSL 要同时改存储层。
 *
 * 分层落点：`:data` 通过**两个函数**拿到编解码能力（`encodeAction` / `decodeAction`），
 * 由 `:app` 在装配时把本对象的方法引用传进去。`:data` 只依赖 `:core:contract`，不认识本文件。
 *
 * ## 解码必须宽容
 *
 * 库里的 JSON 是我们自己写的，理论上严格可解析 —— 但 **schema v1 之前的行可能形态不同**，
 * 而且宽容解码的代价只有几行代码，失败的代价是恢复时整段历史丢掉。
 */
object StepActionCodec {

    fun encode(action: StepAction): String = buildString {
        append("{\"act\":\"").append(action.tool).append('"')
        when (action) {
            is StepAction.Click -> { append(",\"id\":").append(action.id) }
            is StepAction.Type -> {
                append(",\"id\":").append(action.id)
                append(",\"text\":").append(json(action.text))
                if (action.submit) append(",\"submit\":true")
            }

            is StepAction.Select -> {
                append(",\"id\":").append(action.id)
                append(",\"value\":").append(json(action.value))
            }

            is StepAction.Scroll -> {
                append(",\"dir\":").append(json(action.dir))
                append(",\"px\":").append(action.px)
            }

            is StepAction.Navigate -> append(",\"url\":").append(json(action.url))
            is StepAction.Extract -> {
                append(",\"selectorHint\":").append(json(action.selectorHint))
                append(",\"fields\":[").append(action.fields.joinToString(",") { json(it) }).append(']')
                append(",\"limit\":").append(action.limit)
            }

            is StepAction.CallTool -> {
                append(",\"toolId\":").append(json(action.toolId.value))
                append(",\"args\":{").append(
                    action.args.entries.joinToString(",") { (k, v) -> "${json(k)}:${json(v)}" },
                ).append('}')
            }

            is StepAction.AskHuman -> append(",\"question\":").append(json(action.question))
            is StepAction.Finish -> {
                append(",\"answer\":").append(json(action.answer))
                append(",\"evidenceRefs\":[").append(action.evidenceRefs.joinToString(",") { json(it) }).append(']')
            }
        }
        append('}')
    }

    /**
     * 宽容解码。
     * @return null 表示无法还原 —— 调用方应退化为「这一行的历史不可用」而不是让整个任务恢复失败。
     */
    fun decode(json: String): StepAction? {
        if (json.isBlank()) return null
        val obj = JsonTolerant.parseObject(json)?.asObject() ?: return null
        // 复用主解析器：它已有全部字段校验，行为不会与执行时分叉
        return when (val r = PARSER.parseFromFields(obj)) {
            is StepActionParser.ParseResult.Ok -> r.action
            is StepActionParser.ParseResult.Fail -> null
        }
    }

    private val PARSER: StepActionParser = StepActionParser()

    private fun json(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 供 `:data` 在装配时使用的函数引用（保持与 lambda 形状一致）。 */
    fun encodeRef(): (StepAction) -> String = ::encode
    fun decodeRef(): (String) -> StepAction? = ::decode

    internal fun toolIdOf(v: String): ToolId = ToolId(v)
}
