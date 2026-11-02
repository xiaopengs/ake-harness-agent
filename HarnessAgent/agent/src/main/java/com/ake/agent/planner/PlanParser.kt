package com.ake.agent.planner

import com.ake.core.contract.PlanLimits
import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind

/**
 * 计划解析器：把模型的自由文本变成一个**可执行、可确认**的线性计划。
 *
 * ## 为什么解析期就做"闭合"
 *
 * 计划会被展示给用户、被存进 `plan_revision`、被执行器逐步消费。这三个消费方对
 * "计划长什么样"的假设必须一致。如果解析器把模型瞎写的 `"do":"analyze"` 原样放过去，
 * 展示层会显示一个不存在的步骤，执行层找不到分支只能跳过 —— 用户看到的现象是
 * **"计划里有这一步，但它跳过了"**，这比计划少一步更难解释。
 * 所以闭集在这里强制：不认识的 kind 直接丢弃，并在结果里报告丢了几条。
 *
 * ## 三条硬规则
 *
 * 1. **不认识的 kind 丢弃**，不做同义词映射（"read"→`read_file` 这种映射一旦开了头，
 *    就得维护一张永远不全的同义词表，而模型每天都能造出新词）；
 * 2. **连续重复的 kind 合并**（模型经常写 `read_file` 两次），保留第一条；
 * 3. **最后一步必须是产出型**（[PlanStepKind.SUMMARIZE] / [PlanStepKind.TODOS] /
 *    [PlanStepKind.EXPORT]），否则整份计划无效 —— 一个读完就结束的计划，
 *    用户等 90 秒什么都拿不到。
 */
public object PlanParser {

    public sealed interface Result {
        /**
         * @property dropped 因不认识 / 重复而被丢掉的步骤数。>0 时执行仍可继续，
         *                   但计划生成侧应记一笔（说明模型没按 schema 走）。
         * @property truncated 原始步数超过 [PlanLimits.MAX_STEPS] 时，被截掉的条数。
         */
        public data class Ok(
            val steps: List<PlanStep>,
            val dropped: Int = 0,
            val truncated: Int = 0,
        ) : Result

        /** @property hint 回灌给模型的一句话，必须是"怎么改"而不是"你错了"。 */
        public data class Invalid(val reason: String, val hint: String) : Result
    }

    /** 模型可能用的字段名。**只做同层同义**（都表示"做什么"），不做语义映射。 */
    private val KIND_KEYS = listOf("do", "kind", "act", "step")
    private val TARGET_KEYS = listOf("target", "what", "on", "input", "of")

    private val OUTPUT_KINDS = setOf(PlanStepKind.SUMMARIZE, PlanStepKind.TODOS, PlanStepKind.EXPORT)

    /**
     * @param text 模型的原始输出（可能带 ```json 围栏、前后解释、多个对象）。
     */
    public fun parse(text: String): Result {
        val root = JsonTolerant.extractFirstObject(text)
            ?: return Result.Invalid(
                reason = "没有找到 JSON 对象",
                hint = "只输出一个 JSON 对象，形如 {\"steps\":[{\"do\":\"read_file\",\"target\":\"这份 PDF\"}]}，不要写解释。",
            )

        val raw = root.fields["steps"]?.asArray()
            ?: root.fields["plan"]?.asArray()
            ?: root.fields["todo"]?.asArray()
            ?: return Result.Invalid(
                reason = "缺少 steps 数组",
                hint = "顶层必须是 {\"steps\":[…] }，steps 里放 3–5 个步骤对象。",
            )

        var dropped = 0
        val kept = ArrayList<PlanStep>(raw.size)

        for (item in raw) {
            val obj = item.asObject()
            if (obj == null) {
                dropped++
                continue
            }
            val kind = KIND_KEYS.firstNotNullOfOrNull { k -> obj[k]?.asString()?.trim()?.lowercase() }
            if (kind == null || kind !in PlanStepKind.ALL) {
                dropped++
                continue
            }
            // 连续重复合并：模型在同一步里常写两次 read_file
            if (kept.isNotEmpty() && kept.last().kind == kind) {
                dropped++
                continue
            }
            val target = TARGET_KEYS.firstNotNullOfOrNull { k ->
                obj[k]?.asString()?.trim()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            kept += PlanStep(index = kept.size, kind = kind, target = target.take(80))
        }

        if (kept.size < PlanLimits.MIN_STEPS) {
            return Result.Invalid(
                reason = "计划里没有可执行的步骤",
                hint = "从 ${PlanStepKind.PROMPT_ENUM} 里选 3–5 个，按先后顺序排好，最后一步要是 summarize 或 report_todos。",
            )
        }

        val truncated = (kept.size - PlanLimits.MAX_STEPS).coerceAtLeast(0)
        val capped = if (truncated > 0) kept.take(PlanLimits.MAX_STEPS) else kept

        // ★ 截断之后才检查收尾：截掉的正好是产出步骤时，这份计划不该被执行
        if (capped.last().kind !in OUTPUT_KINDS) {
            return Result.Invalid(
                reason = "最后一步不是产出步骤",
                hint = "计划的最后一步必须是 summarize、report_todos 或 export_records，否则用户拿不到结果。",
            )
        }

        return Result.Ok(
            steps = capped.mapIndexed { i, s -> s.copy(index = i) },
            dropped = dropped,
            truncated = truncated,
        )
    }

    /**
     * 用户手工编辑后的规范化。
     *
     * 用户在确认页删掉一步之后，`index` 会断号；这里统一重排，并保证最后一步是产出型。
     * **与 [parse] 共用输出规则**，避免"模型生成的计划合法、用户改过的计划非法"这种分叉。
     */
    public fun normalize(steps: List<PlanStep>): Result {
        if (steps.isEmpty()) {
            return Result.Invalid("计划是空的", "至少保留一步，否则没有可执行的内容。")
        }
        val deduped = ArrayList<PlanStep>(steps.size)
        for (s in steps) {
            if (s.kind !in PlanStepKind.ALL) continue
            if (deduped.isNotEmpty() && deduped.last().kind == s.kind) continue
            deduped += s
        }
        val capped = deduped.take(PlanLimits.MAX_STEPS)
        if (capped.isEmpty()) {
            return Result.Invalid("计划里没有可执行的步骤", "至少保留一步。")
        }
        if (capped.last().kind !in OUTPUT_KINDS) {
            return Result.Invalid(
                reason = "最后一步不是产出步骤",
                hint = "把 summarize 或 report_todos 放回最后一步，否则跑完什么都没有。",
            )
        }
        return Result.Ok(capped.mapIndexed { i, s -> s.copy(index = i) })
    }
}
