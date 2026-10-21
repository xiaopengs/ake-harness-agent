package com.ake.agent.planner

/**
 * 解析失败的分类与**给模型的反馈话术**（架构 §8.6 闸 5 + §3.5「连续 2 次输出无法解析」熔断）。
 *
 * 话术的设计原则（很关键，别写成「你错了」）：
 *  ① 明确说**哪一条**约束没满足 —— 小模型不会自己定位。
 *  ② 给出**可替代的正确做法**，而不只是拒绝 —— 否则它会原地重试同一个错误。
 *  ③ 附上**当前可用信息**（如候选编号范围），让下一步就能修正。
 */
enum class ParseFailure(val code: String) {
    NO_JSON("NO_JSON"),
    BAD_JSON("BAD_JSON"),
    UNKNOWN_ACT("UNKNOWN_ACT"),
    MISSING_FIELD("MISSING_FIELD"),
    BAD_FIELD_TYPE("BAD_FIELD_TYPE"),
    EMPTY_OUTPUT("EMPTY_OUTPUT"),
}

data class RepairHint(
    val failure: ParseFailure,
    val display: String,          // 给用户看的（UI / trace_event）
    val toModel: String,          // 喂回模型的观察文本（进 recentTrace 段）
) {
    companion object {
        fun of(f: ParseFailure, detail: String, validIds: IntRange?, attempt: Int): RepairHint {
            val ids = validIds?.let { "当前可用编号：${it.first}..${it.last}" } ?: "（本轮无候选列表）"
            val toModel = when (f) {
                ParseFailure.NO_JSON ->
                    "你上一步的输出里没有找到 JSON 对象。请只输出一个 JSON 对象，例如 {\"act\":\"click\",\"id\":1}。$ids"
                ParseFailure.BAD_JSON ->
                    "你上一步的 JSON 无法解析（$detail）。请输出严格合法的 JSON：双引号、无尾逗号、不加 markdown 围栏。"
                ParseFailure.UNKNOWN_ACT ->
                    "不支持的动作 \"$detail\"。可选：click / type / select / scroll / goto / extract / tool / ask / finish。"
                ParseFailure.MISSING_FIELD ->
                    "动作缺少必需字段 \"$detail\"。请补齐后重新输出一次。"
                ParseFailure.BAD_FIELD_TYPE ->
                    "字段 \"$detail\" 的类型不对（注意 id 必须是整数数字，不要写成字符串或小数）。"
                ParseFailure.EMPTY_OUTPUT ->
                    "你上一步没有输出任何动作。请输出一个 JSON 对象。$ids"
            }
            val suffix = if (attempt >= 1) "（这是第 ${attempt + 1} 次修正；再失败任务将被终止）" else ""
            return RepairHint(f, "输出不可解析：${f.code} $detail", toModel + suffix)
        }
    }
}
