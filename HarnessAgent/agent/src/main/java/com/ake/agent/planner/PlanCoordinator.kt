package com.ake.agent.planner

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.StepId
import com.ake.core.common.SystemClock
import com.ake.core.common.TaskId
import com.ake.core.contract.PlanStatus
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ThinkingBudget
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.DEFAULT_MODEL_ID
import com.ake.core.ports.DocumentSource
import com.ake.core.ports.InferenceFocus
import com.ake.core.ports.PlanCoordinatorPort
import com.ake.core.ports.PlanDraftResult
import com.ake.core.ports.PlanRunEvent
import com.ake.core.ports.PlanStorePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 计划编排：**生成草稿 → 确认 → 逐步执行**。
 *
 * ## 它为什么存在（而不是把这些写进 `AgentLoop`）
 *
 * `AgentLoop` 是**单步 ReAct**：每步都要看一眼页面再决定下一步，适合"网页上做一件事"。
 * 文档任务不是这个形状 —— 用户选了一个 PDF，接下来的 5 步是**确定的**：
 * 抽正文、分块摘要、汇总、抽待办。让模型在每一步重新看一遍 DOM 既没有信息量，
 * 又要把 40 秒花在 5 次 prefill 上。
 *
 * 所以计划模式把"决定做什么"提前到一次生成、由用户确认，执行阶段只负责顺序执行。
 * 两者共用 `ModelPort`，但**不共用主循环** —— 硬塞进那个七环状态机会让它再多两个分支。
 *
 * ## 三条纪律
 *
 * 1. **生成失败只重试一次**（见 [PlanCoordinatorPort.draft]）；
 * 2. **执行只跑已确认的版本**，`revision` 对不上直接失败（审计要求）；
 * 3. **任一步失败就停**，不"跳过继续" —— 后面的步骤依赖前面的产出，
 *    跳过第 2 步去跑第 3 步，第 3 步拿到空输入，产出的是一段看起来像结果的废话。
 */
public class PlanCoordinator(
    private val model: ModelPort,
    private val plans: PlanStorePort,
    private val steps: PlanStepHandler,
    /**
     * 推理焦点仲裁器（进程级唯一，F3 的正确性组件）。
     *
     * ★ 生产必须由 `:app` 注入 `ChatServices.focus` —— **同一个实例**。
     *   在这里 `InferenceFocusArbiter(clock)` 另造一个，等于两把互不知情的锁：
     *   双方都以为自己拿到了引擎，串字照旧发生，而且比没有锁更难查。
     */
    private val focus: InferenceFocus = NoInferenceFocus,
    private val clock: Clock = SystemClock,
    private val modelId: String = DEFAULT_MODEL_ID,
    /** 计划最多 5 步 × 每步一句话，160 token 足够；给多了模型会开始写解释。 */
    private val draftMaxTokens: Int = 160,
    private val draftTimeoutMs: Long = 45_000,
    private val stepTimeoutMs: Long = 120_000,
) : PlanCoordinatorPort {

    override suspend fun draft(
        taskId: TaskId,
        goal: String,
        fileName: String?,
    ): PlanDraftResult {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) {
            return PlanDraftResult.Rejected(
                reason = "目标是空的",
                hint = "先问用户想对这个文件做什么，再规划。",
            )
        }

        // 拿不到焦点就立刻失败：见 FocusGate 的注释（计划永不抢聊天）。
        val leaseOut = focus.acquireForPlan(planFocusHolder(taskId.value))
        if (leaseOut is Outcome.Err) return PlanDraftResult.Failed(leaseOut.error)
        val lease = (leaseOut as Outcome.Ok).value

        val opened = model.openSession(
            SessionSpec(
                modelId = modelId,
                taskId = taskId.value,
                maxOutputTokens = draftMaxTokens,
                // ★ 温度 0：计划要可复现。同一句话两次生成出不同步数，
                //   会让"用户确认的那一版"在重进页面后无从对应。
                temperature = 0.0,
                thinking = ThinkingBudget.Off,
                // 计划走纯文本 + 解析器收敛（见 PlanPromptBuilder 的注释），
                // 不启引擎侧 JSON 约束：2B 模型在约束模式下更容易吐空对象。
                enableJsonConstraint = false,
            ),
        )

        return try {
            when (opened) {
                is Outcome.Err -> PlanDraftResult.Failed(opened.error)
                is Outcome.Ok -> {
                    val session = opened.value
                    try {
                        draftWithRetry(session, taskId, trimmed, fileName)
                    } finally {
                        runCatching { session.close() }
                            .onFailure { AkeLog.w(TAG, "plan session close failed: ${it.message}") }
                    }
                }
            }
        } finally {
            // 生成可能跑满 45 秒 ×2 次；不还租约的话，用户之后再发聊天会一直 FOCUS_BUSY。
            runCatching { lease.close() }
        }
    }

    private suspend fun draftWithRetry(
        session: ModelSession,
        taskId: TaskId,
        goal: String,
        fileName: String?,
    ): PlanDraftResult {
        var last: PlanParser.Result.Invalid? = null

        repeat(MAX_DRAFT_ATTEMPTS) { attempt ->
            val prompt = if (attempt == 0) {
                PlanPromptBuilder.build(goal, fileName)
            } else {
                // ★ 第二次用 repair：只回灌"哪里不对 + 怎么改"，不回灌上一次输出。
                PlanPromptBuilder.repair(last?.hint ?: "格式不对")
            }

            val generated = generate(session, prompt)
                ?: return PlanDraftResult.Failed(AppError.timeout("plan_draft"))
            if (generated is Outcome.Err) {
                // 模型层错误（未加载 / OOM）重试没有意义，直接上报。
                return PlanDraftResult.Failed(generated.error)
            }

            val result = (generated as Outcome.Ok).value
            if (result.finishReason == FinishReason.LENGTH) {
                // 被截断的 JSON 一定解析不出来，但原因和"模型乱写"不同：
                // 前者要它写短，后者要它守格式。混在一句提示里，模型会去改格式。
                last = PlanParser.Result.Invalid(
                    reason = "输出被截断",
                    hint = "步骤写短一点（每步不超过 8 个字），只输出 JSON。",
                )
                return@repeat
            }

            when (val parsed = PlanParser.parse(result.rawText)) {
                is PlanParser.Result.Ok -> {
                    val revision = plans.saveDraft(taskId, goal, parsed.steps)
                    AkeLog.i(
                        TAG,
                        "plan drafted task=${taskId.value} rev=${revision.revision} " +
                            "steps=${parsed.steps.size} dropped=${parsed.dropped} truncated=${parsed.truncated}",
                    )
                    return PlanDraftResult.Drafted(
                        revision = revision,
                        dropped = parsed.dropped,
                        truncated = parsed.truncated,
                    )
                }

                is PlanParser.Result.Invalid -> {
                    last = parsed
                    AkeLog.w(TAG, "plan parse failed (attempt ${attempt + 1}): ${parsed.reason}")
                }
            }
        }

        return PlanDraftResult.Rejected(
            reason = last?.reason ?: "计划格式不正确",
            hint = last?.hint ?: "只输出一个 JSON 对象。",
        )
    }

    /** 单次生成。`null` = 超时；`Err` = 模型层失败。两者必须分开，处理方式不同。 */
    private suspend fun generate(
        session: ModelSession,
        prompt: String,
    ): Outcome<GenerationResult>? = withTimeoutOrNull(draftTimeoutMs) {
        session.generate(
            GenerationRequest(
                stepId = StepId.new(),
                userTurn = prompt,
                outputShape = OutputShape.FreeText,
                maxOutputTokens = draftMaxTokens,
                thinking = ThinkingBudget.Off,
                deadlineMs = draftTimeoutMs,
            ),
        )
    }

    override suspend fun confirm(taskId: TaskId, revision: Int): Boolean =
        plans.confirm(taskId, revision)

    override fun execute(
        taskId: TaskId,
        revision: Int,
        document: DocumentSource?,
        abort: AbortSignal,
    ): Flow<PlanRunEvent> = flow {
        val plan = plans.latest(taskId)
        if (plan == null) {
            emit(fail("PLAN_NOT_FOUND", "err_plan_not_found"))
            return@flow
        }
        if (plan.revision != revision) {
            // ★ 不"跑最新的"：用户确认的是 A 版，跑的却是 B 版，事后无法解释。
            emit(fail("PLAN_REVISION_MISMATCH", "err_plan_revision_mismatch"))
            return@flow
        }
        if (plan.status != PlanStatus.CONFIRMED) {
            emit(fail("PLAN_NOT_CONFIRMED", "err_plan_not_confirmed"))
            return@flow
        }

        emit(PlanRunEvent.Started(revision, plan.steps.size))
        val startedAt = clock.nowMillis()
        var upstream = ""

        for (step in plan.steps) {
            if (abort.isAborted()) {
                emit(PlanRunEvent.Aborted(step.index))
                return@flow
            }
            emit(PlanRunEvent.StepStarted(step.index, step.kind, step.target))
            val stepStart = clock.nowMillis()

            val result = try {
                withTimeoutOrNull(stepTimeoutMs) {
                    steps.execute(
                        step,
                        PlanStepContext(
                            taskId = taskId,
                            goal = plan.goal,
                            upstream = upstream,
                            document = document,
                            abort = abort,
                        ),
                    )
                } ?: Outcome.Err(AppError.timeout("plan_step_${step.kind}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 处理器内部没兜住的异常也不能让流崩掉：用户需要知道**第几步**炸了。
                Outcome.Err(AppError.wrap(e))
            }

            when (result) {
                is Outcome.Ok -> {
                    upstream = result.value.text
                    emit(
                        PlanRunEvent.StepFinished(
                            index = step.index,
                            summary = preview(upstream),
                            elapsedMs = clock.nowMillis() - stepStart,
                        ),
                    )
                }

                is Outcome.Err -> {
                    emit(PlanRunEvent.StepFailed(step.index, result.error))
                    emit(PlanRunEvent.Failed(result.error))
                    return@flow
                }
            }
        }

        emit(
            PlanRunEvent.Completed(
                revision = revision,
                result = upstream,
                elapsedMs = clock.nowMillis() - startedAt,
            ),
        )
    }

    private fun fail(code: String, reasonKey: String) =
        PlanRunEvent.Failed(AppError(code = code, reasonKey = reasonKey))

    private fun preview(text: String): String {
        val oneLine = text.replace('\n', ' ').trim()
        return if (oneLine.length <= PREVIEW_CHARS) oneLine else oneLine.take(PREVIEW_CHARS) + "…"
    }

    private companion object {
        const val TAG: String = "PlanCoordinator"
        const val MAX_DRAFT_ATTEMPTS: Int = 2
        const val PREVIEW_CHARS: Int = 120
    }
}
