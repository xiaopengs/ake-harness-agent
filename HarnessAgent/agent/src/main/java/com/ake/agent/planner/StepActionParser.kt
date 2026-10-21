package com.ake.agent.planner

import com.ake.core.contract.PocketDom
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolId
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.RawToolCall

/**
 * 动作解析器（架构 §3.2 第 4 环）。
 *
 * 双通道：
 *  ① **约束解码通道**：若引擎支持 `OutputShape.AgentStep`（§12.2 第 4 项复验），
 *     `GenerationResult.toolCalls` 里已有结构化调用，优先用它 —— 语法必然合法。
 *  ② **文本容错通道**：引擎不支持约束解码时（架构已给出降级预案），从 rawText 里宽容解析。
 *
 * 注意：**约束解码只保证语法合法，不保证语义落地**。元素存在性、动作可行性、
 * URL 合法性、凭据字段这些仍要过闸 4 / 闸 5（§8.6）。所以解析成功 ≠ 可以执行。
 */
class StepActionParser {

    sealed interface ParseResult {
        data class Ok(val action: StepAction) : ParseResult
        data class Fail(val hint: RepairHint) : ParseResult
    }

    fun parse(gen: GenerationResult, dom: PocketDom?, attempt: Int = 0): ParseResult {
        val ids = dom?.candidates?.map { it.id }
        val range = if (ids.isNullOrEmpty()) null else ids.min()..ids.max()

        // ── 通道 ①：约束解码产出的结构化调用
        if (gen.toolCalls.isNotEmpty()) {
            when (val r = fromToolCall(gen.toolCalls.first(), range, attempt)) {
                is ParseResult.Ok -> return r
                is ParseResult.Fail -> { /* 结构调用也可能语义不合，fall through 到文本通道 */ }
            }
        }

        // ── 通道 ②：文本容错
        val text = gen.rawText
        if (text.isBlank()) return fail(ParseFailure.EMPTY_OUTPUT, "", range, attempt)

        val obj = JsonTolerant.extractFirstObject(text)
            ?: return fail(ParseFailure.NO_JSON, "未找到完整 JSON 对象", range, attempt)

        return fromFields(obj.asObject() ?: emptyMap(), range, attempt, "")
    }

    /**
     * 公开入口：从已解析好的字段集合直接构造动作。
     *
     * 供 `StepActionCodec`（断点恢复时的历史还原）复用 —— **刻意不另写一套解析**，
     * 否则执行时与回放时对同一段 JSON 的行为会分叉，那类 bug 极难复现。
     */
    fun parseFromFields(fields: Map<String, JsonValue>): ParseResult =
        fromFields(fields, null, 0, "")

    private fun fromToolCall(call: RawToolCall, range: IntRange?, attempt: Int): ParseResult {
        val argsObj = JsonTolerant.parseObject(call.argumentsJson)?.asObject()
        return fromFields((argsObj ?: emptyMap()) + mapOf(ACT_KEY to JsonValue.Str(call.name)), range, attempt, call.name)
    }

    private fun fromFields(
        f: Map<String, JsonValue>,
        range: IntRange?,
        attempt: Int,
        forcedAct: String,
    ): ParseResult {
        val act = (f[ACT_KEY]?.asString() ?: f["action"]?.asString() ?: forcedAct)
            .trim().lowercase()
        if (act.isBlank()) return fail(ParseFailure.UNKNOWN_ACT, "(空)", range, attempt)

        return when (act) {
            "click" -> {
                val id = f["id"]?.asInt() ?: return fail(ParseFailure.MISSING_FIELD, "id", range, attempt)
                ParseResult.Ok(StepAction.Click(id))
            }
            "type" -> {
                val id = f["id"]?.asInt() ?: return fail(ParseFailure.MISSING_FIELD, "id", range, attempt)
                val text = f["text"]?.asString() ?: return fail(ParseFailure.MISSING_FIELD, "text", range, attempt)
                val submit = f["submit"]?.asBool() ?: false
                if (text.length > MAX_INPUT_LEN) {
                    return fail(ParseFailure.BAD_FIELD_TYPE, "text 超过 $MAX_INPUT_LEN 字符，请分段", range, attempt)
                }
                ParseResult.Ok(StepAction.Type(id, text, submit))
            }
            "select" -> {
                val id = f["id"]?.asInt() ?: return fail(ParseFailure.MISSING_FIELD, "id", range, attempt)
                val v = f["value"]?.asString() ?: return fail(ParseFailure.MISSING_FIELD, "value", range, attempt)
                ParseResult.Ok(StepAction.Select(id, v))
            }
            "scroll" -> {
                val dir = f["dir"]?.asString() ?: "down"
                val px = f["px"]?.asInt() ?: 800
                ParseResult.Ok(StepAction.Scroll(if (dir == "up") "up" else "down", px.coerceIn(0, 20_000)))
            }
            "goto", "navigate" -> {
                val url = f["url"]?.asString() ?: return fail(ParseFailure.MISSING_FIELD, "url", range, attempt)
                ParseResult.Ok(StepAction.Navigate(url))
            }
            "extract", "extract_records" -> {
                val hint = f["selectorHint"]?.asString() ?: f["selector"]?.asString() ?: ""
                val fields = f["fields"]?.asStringList() ?: emptyList()
                val limit = (f["limit"]?.asInt() ?: 20).coerceIn(1, MAX_RECORDS)
                ParseResult.Ok(StepAction.Extract(hint, fields, limit))
            }
            "tool", "call_tool" -> {
                val toolId = f["toolId"]?.asString() ?: f["tool"]?.asString()
                    ?: return fail(ParseFailure.MISSING_FIELD, "toolId", range, attempt)
                val args = (f["args"]?.asObject() ?: emptyMap()).mapValues { (_, v) -> v.asString() ?: "" }
                ParseResult.Ok(StepAction.CallTool(ToolId(toolId), args))
            }
            "ask", "ask_human" -> {
                val q = f["question"]?.asString() ?: return fail(ParseFailure.MISSING_FIELD, "question", range, attempt)
                ParseResult.Ok(StepAction.AskHuman(q))
            }
            "finish" -> {
                val answer = f["answer"]?.asString() ?: return fail(ParseFailure.MISSING_FIELD, "answer", range, attempt)
                val refs = f["evidenceRefs"]?.asStringList() ?: emptyList()
                ParseResult.Ok(StepAction.Finish(answer, refs))
            }
            else -> fail(ParseFailure.UNKNOWN_ACT, act, range, attempt)
        }
    }

    private fun fail(f: ParseFailure, detail: String, range: IntRange?, attempt: Int) =
        ParseResult.Fail(RepairHint.of(f, detail, range, attempt))

    private companion object {
        const val ACT_KEY = JsonTolerant.ACT_FIELD
        const val MAX_INPUT_LEN = 512     // §12.2 MAX_INPUT_LEN
        const val MAX_RECORDS = 100
    }
}
