package com.ake.agent.planner

import com.ake.core.contract.PlanLimits
import com.ake.core.contract.PlanStepKind

/**
 * 计划生成的 Prompt。
 *
 * ## 为什么不用 function calling
 *
 * 本项目的 `automaticToolCalling = false`：LiteRT-LM 侧的工具调用需要把 schema 塞进
 * 每一轮上下文，而端侧 2B 模型在"同时理解任务 + 遵守 schema + 选工具"三件事上会明显掉准。
 * 计划生成只发生一次、且结果要展示给用户确认，**用纯文本 + 闭集 + 解析器收敛**比
 * 让模型走工具调用通道更稳，也更容易在失败时给出"怎么改"的提示。
 *
 * ## 为什么示例只有一条
 *
 * few-shot 每多一条就多吃 60–100 token 的 prefill，而 decode 只有 52 tok/s。
 * 一条覆盖"读文件 → 抽取 → 摘要 → 待办"的完整例子，比三条半截例子更能定住格式。
 */
public object PlanPromptBuilder {

    public fun build(goal: String, fileName: String?): String = buildString {
        appendLine("你是任务规划器。把用户的目标拆成 ${PlanLimits.MIN_STEPS}–${PlanLimits.MAX_STEPS} 步的线性计划。")
        appendLine()
        appendLine("规则：")
        appendLine("1. 只能使用这些步骤类型：${PlanStepKind.PROMPT_ENUM}")
        appendLine("2. 每一步只做一件事，后一步用前一步的结果。")
        appendLine("3. 最后一步必须是 ${PlanStepKind.SUMMARIZE}、${PlanStepKind.TODOS} 或 ${PlanStepKind.EXPORT}。")
        appendLine("4. 不要写页码、关键词、文件名之类的具体参数——执行时才知道。")
        appendLine("5. 只输出一个 JSON 对象，不要解释、不要代码块围栏。")
        appendLine()
        appendLine("格式：{\"steps\":[{\"do\":\"步骤类型\",\"target\":\"对什么做\"}]}")
        appendLine()
        appendLine("例子：")
        appendLine("目标：把这份 PDF 的重点和待办整理出来")
        appendLine(
            "输出：{\"steps\":[" +
                "{\"do\":\"read_file\",\"target\":\"用户选中的 PDF\"}," +
                "{\"do\":\"pdf_extract\",\"target\":\"全文正文\"}," +
                "{\"do\":\"summarize\",\"target\":\"正文的重点\"}," +
                "{\"do\":\"report_todos\",\"target\":\"正文里要做的事\"}]}",
        )
        appendLine()
        appendLine("现在轮到你了。")
        append("目标：").appendLine(goal.trim())
        if (!fileName.isNullOrBlank()) {
            append("文件：").appendLine(fileName)
        }
        append("输出：")
    }

    /**
     * 修复用的回灌。
     *
     * ★ 只回灌"哪里不对 + 怎么改"，**不回灌上一次的完整输出**：
     * 把错误输出整段贴回去，2B 模型有相当概率照抄一遍（尤其是它认为自己对的时候），
     * 而上下文预算只有那么多。
     */
    public fun repair(hint: String): String = buildString {
        appendLine("上一次的输出不能用：$hint")
        append("请直接重新输出一个 JSON 对象，不要解释。")
    }
}
