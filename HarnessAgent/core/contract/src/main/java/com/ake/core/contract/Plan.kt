package com.ake.core.contract

import com.ake.core.common.TaskId

/**
 * 计划（V1.0 只支持 3–5 步**线性**计划）。
 *
 * ## 为什么不是自由 DAG
 *
 * 端侧 2B 模型在 6 步以上计划的成功率会快速衰减 —— 现有 `StepActionParser` / `RepairHints` /
 * `JsonTolerant` / `Verifier` 这四个类的存在本身就是"模型不可靠"的证据。线性计划把
 * **每一步的输入固定为上一步的输出**，模型只需要决定"下一步干什么"，不需要维护依赖图。
 * 等真机数据证明 5 步不够，再谈 DAG。
 *
 * ## 为什么步骤只有 `kind` 没有参数
 *
 * 计划阶段模型**看不到文件内容**，任何具体参数（页码、关键词、行号）都是编的。
 * 所以计划只表达"做什么、对什么做"，真正的参数由执行阶段在拿到观察结果后填写 ——
 * 这样也顺带把"计划里写死了一个不存在的文件名"这类失败变成了不可能。
 *
 * @property index 从 0 开始，必须连续。断点续跑按它定位。
 * @property kind 闭集，见 [PlanStepKind]。不认识的 kind 在解析期就被丢弃，不会流到执行期。
 * @property target 给执行阶段看的一句话（"这份 PDF" / "第 3 页的表格"），不是机器参数。
 */
public data class PlanStep(
    public val index: Int,
    public val kind: String,
    public val target: String,
)

/**
 * 计划步骤类型的**闭集**。
 *
 * ★ 闭集不是洁癖：这些字符串会进 Prompt，也会进 `plan_revision.steps_json`。
 * 一旦允许模型自由发挥（"analyze"、"think"、"maybe_read"），执行期就没有对应的分支，
 * 只能落到"什么都不做"——那比计划少一步更糟，因为用户看到的是"计划里有这一步、它跳过了"。
 */
public object PlanStepKind {
    /** 打开并读取用户选中的文件（或其中一段）。 */
    public const val READ_FILE: String = "read_file"

    /** 从 PDF 抽正文（分页、跳过扫描页）。 */
    public const val EXTRACT: String = "pdf_extract"

    /** 对已抽出的内容做摘要（map-reduce）。 */
    public const val SUMMARIZE: String = "summarize"

    /** 从内容里抽待办 / 关键结论，结构化输出。 */
    public const val TODOS: String = "report_todos"

    /** 把结果导出成文件。 */
    public const val EXPORT: String = "export_records"

    public val ALL: Set<String> = setOf(READ_FILE, EXTRACT, SUMMARIZE, TODOS, EXPORT)

    /** 给模型看的枚举串（写进 Prompt，与 [ALL] 同源，避免两处漂移）。 */
    public val PROMPT_ENUM: String = ALL.joinToString(" | ")
}

/** 计划版本状态。 */
public object PlanStatus {
    /** 模型刚生成，等用户确认。 */
    public const val DRAFT: String = "DRAFT"

    /** 用户已确认（或改过之后确认），可以执行。 */
    public const val CONFIRMED: String = "CONFIRMED"

    /** 用户放弃了这个计划（重新生成或取消任务）。 */
    public const val SUPERSEDED: String = "SUPERSEDED"

    public val ALL: Set<String> = setOf(DRAFT, CONFIRMED, SUPERSEDED)
}

/**
 * 一个计划版本。
 *
 * `(taskId, revision)` 是主键 —— 每次重新生成 +1，旧版本保留（`SUPERSEDED`）。
 * 保留旧版本不是为了"历史记录"，而是为了**审计能回答"用户当初确认的是哪一版"**：
 * 如果只留最新一版，用户说"我确认的不是这个"，我们拿不出证据。
 */
public data class PlanRevision(
    public val taskId: TaskId,
    public val revision: Int,
    public val goal: String,
    public val steps: List<PlanStep>,
    public val status: String = PlanStatus.DRAFT,
    public val createdAt: Long = 0L,
)

/** 计划的结构约束（解析期与落库前都要过一遍，两处同源）。 */
public object PlanLimits {
    /**
     * 上限 5 步。
     *
     * 依据：交付方案 §2 的裁决 + 端侧模型能力。**超出不是报错而是截断** ——
     * 模型给 8 步时说明它想把一件事拆细，截前 5 步仍然是一个可执行的计划；
     * 直接失败会让用户对着"计划生成失败"重试三次，体验更差。
     */
    public const val MAX_STEPS: Int = 5

    /** 下限 1 步：0 步的计划等于"我自己也不知道要干嘛"，必须让模型重来。 */
    public const val MIN_STEPS: Int = 1
}
