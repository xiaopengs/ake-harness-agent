package com.ake.chat.skill

import com.ake.agent.planner.JsonTolerant
import com.ake.core.common.Ulid
import com.ake.core.ports.SkillCall
import com.ake.core.ports.SkillSpec

/**
 * # 路由裁决的解析与校验（§2.6 纪律 2 / §3.1）
 *
 * Router 是一次**独立的约束解码 turn**（约 40 token），产出两种形态之一：
 *
 * ```json
 * {"mode":"reply"}
 * {"mode":"skill","skill":"browser_task","instruction":"按销量排序，取前三条","startUrl":"https://…"}
 * ```
 *
 * 为什么用 `mode` 而不是继承 Action DSL 的 `act`：`act` 在 `:agent` 里已经是
 * 「浏览器单步动作」的词汇（click/type/extract/finish…）。同一个字段名承载两种
 * 语义，模型会在「聊天回合」里输出 `{"act":"click","id":12}` 而我们无从判断它是笔误还是注入。
 *
 * ## 解析器必须宽容，但**校验器必须严格**
 *
 * 宽容的是**格式**（复用 `:agent` 的 `JsonTolerant`：模型输出的是「长得像 JSON 的东西」）。
 * 严格的是**参数**：这是一次 `TaskSpec` 的原料，而 §2.6 纪律 2 说得很清楚 ——
 * 「模型给的字符串直接当 goal 会让 §8 的所有护栏在第一步失效」。
 *
 * 于是这里做四件事，一件都不能省：
 *  ① 技能名必须命中 `enabledFor(agentId)` 的清单（不在清单里 = 模型在编造能力）；
 *  ② `instruction` 必须非空、且长度受限（超长正文是挤爆上下文的老手法）；
 *  ③ `startUrl` 必须是 http(s)（`javascript:` / `file:` / `data:` 一律丢）；
 *  ④ `callId` 由我们生成，**不取模型给的任何 id**。
 */
public class SkillRouter(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    public fun decide(rawModelOutput: String, catalog: List<SkillSpec>): TurnDecision {
        val obj = JsonTolerant.extractFirstObject(normalizeStructuralNewlines(rawModelOutput))
            ?: return TurnDecision.Reply(reason = "router 输出不是 JSON，按普通回答处理")

        val mode = obj.fields["mode"]?.asString()?.trim()?.lowercase()
            ?: return TurnDecision.Reply(reason = "缺少 mode 字段")

        if (mode != "skill") return TurnDecision.Reply(reason = "mode=$mode")

        val name = obj.fields["skill"]?.asString()?.trim()
            ?: return TurnDecision.Rejected("SKILL_NAME_MISSING")

        val spec = catalog.firstOrNull { it.name == name || it.ref.id == name }
            ?: return TurnDecision.Rejected("SKILL_NOT_ENABLED:$name")

        val instruction = obj.fields["instruction"]?.asString()?.trim().orEmpty()
        if (instruction.isBlank()) return TurnDecision.Rejected("INSTRUCTION_MISSING")
        if (instruction.length > MAX_INSTRUCTION_CHARS) return TurnDecision.Rejected("INSTRUCTION_TOO_LONG")

        val args = LinkedHashMap<String, String>()
        args["instruction"] = instruction
        safeStartUrl(obj.fields["startUrl"]?.asString())?.let { args["startUrl"] = it }

        return TurnDecision.Skill(
            call = SkillCall(
                callId = "call-" + Ulid.next(nowMillis()),
                ref = spec.ref,
                args = args,
            ),
            spec = spec,
        )
    }

    /**
     * 把**结构性换行**（字符串之外的那些）压成空格。
     *
     * ★ 这不是洁癖，是一个实测抓到的真问题：`JsonTolerant.normalize` 里那条
     * `BARE_NEWLINE_IN_STRING` 启发式（`([^\\])\n(?!\s*[}\]])`）会把**成员之间**的换行
     * 也替换成字面的 `\n`，于是「漂亮的缩进 JSON」直接解析失败 —— 而约束解码之外的路径
     * （比如拒答后模型改用自由文本、或换了机型 schema 不被支持而降级）很可能吐出缩进格式。
     *
     * 这里只做一件事：**字符串外的换行 → 空格**。字符串内部一个字节都不动，
     * 因为那是内容（聊天文本里的换行是合法的）。
     */
    internal fun normalizeStructuralNewlines(raw: String): String {
        if (!raw.contains('\n') && !raw.contains('\r')) return raw
        val out = StringBuilder(raw.length)
        var inStr = false
        var escaped = false
        for (c in raw) {
            if (inStr) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> { inStr = true; out.append(c) }
                '\n', '\r' -> out.append(' ')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * 起始 URL 的白名单。
     *
     * `javascript:` 会绕过 `DomainPolicy` 直接在页面上下文里执行；`file:` / `content:` 能读到
     * App 私有目录；`data:` 能构造一段自带脚本的文档。三者都不该由**模型输出**决定。
     * 空 / 缺失 ⇒ 交给任务的默认入口（由 `:browser` 侧决定），不在这里补。
     */
    private fun safeStartUrl(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        val lower = v.lowercase()
        val ok = lower.startsWith("http://") || lower.startsWith("https://")
        return if (ok) v.take(MAX_URL_CHARS) else null
    }

    /** 路由结果。刻意三态：`Rejected` 不能悄悄变成 `Reply` —— 否则「模型想越权」这件事没有任何痕迹。 */
    public sealed interface TurnDecision {
        public data class Reply(val reason: String) : TurnDecision

        public data class Skill(val call: SkillCall, val spec: SkillSpec) : TurnDecision

        /** 模型要求调一个不存在 / 未授权 / 参数不合法的技能。调用方应记录并退回 Reply。 */
        public data class Rejected(val code: String) : TurnDecision
    }

    public companion object {
        /**
         * 约束解码用的 schema。**字段越少越好**：端侧 decode 52 tok/s，
         * 每多一个可选字段，模型就多一次「我该不该填」的犹豫（也就是多几个 token）。
         */
        public val ROUTER_JSON_SCHEMA: String = """
            {"type":"object","properties":{
              "mode":{"type":"string","enum":["reply","skill"]},
              "skill":{"type":"string"},
              "instruction":{"type":"string"},
              "startUrl":{"type":"string"}},
             "required":["mode"]}
        """.trimIndent()

        public const val MAX_INSTRUCTION_CHARS: Int = 600
        public const val MAX_URL_CHARS: Int = 512
    }
}
