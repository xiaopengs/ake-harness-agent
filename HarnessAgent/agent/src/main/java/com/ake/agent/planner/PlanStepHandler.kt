package com.ake.agent.planner

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.TaskId
import com.ake.core.contract.PlanStep
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.DocumentSource

/**
 * 单个计划步骤的执行器。
 *
 * ## 为什么按 kind 路由，而不是让协调器自己 `when(kind)`
 *
 * `PlanCoordinator` 只该负责**顺序、中止、事件**这三件事。每一种 kind 背后是一套
 * 完全不同的能力（PDF 解析要独占 PDFBox、摘要要独占推理、导出要过工具注册表），
 * 把它们塞进协调器会让那个类同时依赖文档、模型、工具三个 Port ——
 * 而它现在只依赖模型 + 计划存储，测试时给两个替身就能跑。
 *
 * ## 为什么返回 `Outcome` 而不是抛
 *
 * 步骤失败是**正常分支**：扫描件抽不出文本、用户中途撤销了文件授权、导出时磁盘满。
 * 这些都要变成 [com.ake.core.ports.PlanRunEvent.StepFailed] 展示给用户，
 * 而不是让整个执行 Flow 崩掉 —— 崩掉之后用户只看到"任务失败"，不知道失败在第几步。
 */
public interface PlanStepHandler {

    public suspend fun execute(step: PlanStep, ctx: PlanStepContext): Outcome<PlanStepOutput>
}

/**
 * 步骤执行上下文。
 *
 * @property upstream 上一步的产出。线性计划的核心假设：**每一步的输入固定为上一步的输出**。
 *                    第一步为空串。
 * @property document 用户选中的文件（已落盘）。非文档任务为 `null`。
 * @property abort 与协调器同一个中止信号 —— 一步内部的长循环（逐页解析、逐块摘要）
 *                必须自己查它，不能只靠步骤之间检查。
 */
public data class PlanStepContext(
    public val taskId: TaskId,
    public val goal: String,
    public val upstream: String,
    public val document: DocumentSource?,
    public val abort: AbortSignal,
)

/**
 * 一步的产出。
 *
 * @property text 交给下一步的正文。**会进下一次 prompt**，所以实现方要自己控制长度
 *                （把 60MB 全文原样往下传，下一步的 prefill 会直接撑爆 KV cache）。
 * @property artifactPath 落到磁盘的产物（导出文件），没有则为 `null`。
 */
public data class PlanStepOutput(
    public val text: String,
    public val artifactPath: String? = null,
)

/**
 * 按 [PlanStep.kind] 分发的处理器。
 *
 * 缺处理器时**明确失败**（`UNSUPPORTED`）而不是"跳过这一步"：
 * 跳过会让用户看到"计划里有 5 步、实际跑了 3 步、结果只覆盖了一半内容"，
 * 而这种缺失在执行完之前是不可见的。
 */
public class KindRoutedPlanStepHandler(
    private val byKind: Map<String, PlanStepHandler>,
) : PlanStepHandler {

    override suspend fun execute(step: PlanStep, ctx: PlanStepContext): Outcome<PlanStepOutput> {
        val handler = byKind[step.kind]
            ?: return Outcome.Err(
                AppError(
                    code = AppError.CODE_UNSUPPORTED,
                    reasonKey = "err_plan_step_no_handler",
                    severity = com.ake.core.common.Severity.MEDIUM,
                ),
            )
        return handler.execute(step, ctx)
    }

    /** 用于启动自检：哪些 kind 当前真的能跑。 */
    public fun supportedKinds(): Set<String> = byKind.keys.toSet()
}
