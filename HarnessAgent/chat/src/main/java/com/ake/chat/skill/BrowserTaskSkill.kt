package com.ake.chat.skill

import com.ake.core.common.Clock
import com.ake.core.common.TaskId
import com.ake.core.common.Url
import com.ake.core.contract.RiskClass
import com.ake.core.contract.TaskSpec
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.RunOptions
import com.ake.core.ports.SkillCall
import com.ake.core.ports.SkillConfirmPolicy
import com.ake.core.ports.SkillContext
import com.ake.core.ports.SkillCostClass
import com.ake.core.ports.SkillEvent
import com.ake.core.ports.SkillOutcome
import com.ake.core.ports.SkillSpec
import com.ake.core.ports.TaskEvent
import com.ake.core.ports.TaskRunnerPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * # `builtin.browser_task`（§2.6 / §5.2）
 *
 * 一件很朴素的事：**把「对话里的一句自然语言」变成一次既有 `AgentLoop` 的任务**，
 * 再把任务事件投影回聊天事件。`AgentLoop` 一行不改。
 *
 * ## 五条不可协商的复用纪律（§2.6），逐条落到本类
 *
 * ① `AgentLoop` 零改动 —— 这里只用 `TaskRunnerPort.run(task, opts)`。
 * ② **`TaskSpec` 由本类构造，不是把模型原文透传**：goal 取的是已通过 schema 校验的
 *    `instruction`；`maxSteps` 取信封里的额度；`allowHosts` **留空**交给既有 `DomainPolicy` 兜底。
 *    让「模型给的字符串直接当 goal」会让 §8 的所有护栏在第一步失效。
 * ③ `PolicyPort` 仍是唯一强制执行点 —— 因此本类**刻意不注入 `PolicyPort`**
 *    （文档 §2.6 的构造器里挂了它却一行未用；挂着一个不用的护栏依赖，
 *    迟早会有人在技能层「顺手再判一次风险」，那就是第二份作者单位裁定）。
 * ④ 任务的 `answer` 不被当成助手回复：它被包成 `SkillOutcome.summaryRedacted`
 *    回到 `ChatLoop` 的 `<skill_result>` 段（不可信），再由一次 Answer Turn 复述。
 * ⑤ 进度必须可见：`Observed` / `Planned` / `Guarded` 都投影成 `Progress`，
 *    但**刻意不投影每一步的原始输出** —— UI 会被刷爆（§2.6 「看不见本身会制造 bug 报告」）。
 */
public class BrowserTaskSkill(
    private val taskRunner: TaskRunnerPort,
    private val clock: Clock,
) : SkillHandler {

    override val spec: SkillSpec = SkillSpec(
        ref = CapabilityRef.builtin(SKILL_ID),
        name = SKILL_ID,
        description = "打开网页并按指令完成浏览操作（检索、排序、翻页、抽取结构化数据、填写表单）。" +
            "参数是自然语言任务描述与可选起始 URL。会打开可见的任务进度，耗时数十秒。" +
            "不得用于支付、下单、以及任何涉及登录凭证的操作。",
        jsonSchema = """{"type":"object","properties":{
            "instruction":{"type":"string","description":"要完成的网页任务，一句话，含目标与产出形式"},
            "startUrl":{"type":"string","description":"可选。已知起始 URL"}},
            "required":["instruction"]}""",
        riskClass = RiskClass.PAGE_MUTATING,
        costClass = SkillCostClass.LONG_RUNNING,
        confirmPolicy = SkillConfirmPolicy.FIRST_USE_PER_AGENT,
        maxWallClockMs = 240_000L,
        enabled = true,
    )

    override suspend fun invoke(call: SkillCall, ctx: SkillContext): Flow<SkillEvent> = flow {
        val instruction = call.args["instruction"]?.trim().orEmpty()
        if (instruction.isEmpty()) {
            // 参数不合法的失败**不是异常**：Router 已经校验过一次，走到这里说明解析层有洞。
            emit(SkillEvent.Failed(CODE_BAD_ARGS, null))
            return@flow
        }

        // ★ 纪律 2：TaskSpec 由这里构造。id 从 SkillContext 派生，**不取模型给的任何 id**
        //   （模型能在 args 里塞一个它想要的 taskId，那就等于它能寻址别人的任务）。
        val task = TaskSpec(
            id = TaskId(ctx.derivedTaskId()),
            goal = instruction,
            startUrl = call.args["startUrl"]?.takeIf { it.isNotBlank() }?.let(::Url),
            maxSteps = ctx.budgetEnvelope.maxSteps,
            // ★ 留空 ⇒ 交给既有 DomainPolicy。技能不该自己决定能访问哪些域名：
            //   那是护栏的职责，而护栏只有一份。
            allowHosts = emptySet(),
            createdAtMillis = clock.nowMillis(),
        )

        emit(SkillEvent.Started(ctx.callId, ctx.derivedTaskId()))

        var terminalEmitted = false
        taskRunner.run(task, RunOptions(abort = ctx.abort)).collect { ev ->
            when (ev) {
                is TaskEvent.Started -> Unit // 已经在上面发过 Started，避免两条
                is TaskEvent.Observed -> emit(
                    SkillEvent.Progress(
                        stepSeq = ev.seq, kind = KIND_OBSERVED,
                        detailRedacted = ev.titleRedacted, maxSteps = ctx.budgetEnvelope.maxSteps,
                    ),
                )

                is TaskEvent.Planned -> emit(
                    SkillEvent.Progress(
                        stepSeq = ev.seq, kind = KIND_PLANNED,
                        detailRedacted = "${ev.action.tool}(${ev.action.compactArgs()})",
                        maxSteps = ctx.budgetEnvelope.maxSteps,
                    ),
                )

                is TaskEvent.Executed -> emit(
                    SkillEvent.Progress(
                        stepSeq = ev.seq, kind = KIND_EXECUTED,
                        detailRedacted = ev.result.hintBrief(),
                        maxSteps = ctx.budgetEnvelope.maxSteps,
                    ),
                )

                is TaskEvent.Guarded -> emit(
                    SkillEvent.Progress(
                        stepSeq = ev.seq, kind = KIND_GUARDED,
                        detailRedacted = ev.reasonKey ?: ev.decision,
                        maxSteps = ctx.budgetEnvelope.maxSteps,
                    ),
                )

                is TaskEvent.ConfirmRequested -> emit(SkillEvent.NeedsConfirm(ev.reasonKey, ev.args))

                is TaskEvent.GuardTriggered -> emit(
                    SkillEvent.Progress(
                        stepSeq = ev.seq, kind = KIND_GUARD, detailRedacted = "${ev.kind}: ${ev.detail}",
                        maxSteps = ctx.budgetEnvelope.maxSteps,
                    ),
                )

                is TaskEvent.PerceptionFault -> emit(
                    SkillEvent.Progress(
                        stepSeq = ev.seq, kind = KIND_PERCEPTION_FAULT, detailRedacted = ev.code,
                        maxSteps = ctx.budgetEnvelope.maxSteps,
                    ),
                )

                // ★ 挂起**不是**成功也不是失败的任务结果：任务还没跑完，聊天里不能有一个
                //   「已完成」的结论。这里发 Failed 是因为技能的 Flow 必须终结，
                //   而 UI 需要的「任务卡片还在等条件/等确认」由 ChatEvent 层另外表达。
                is TaskEvent.Paused -> {
                    terminalEmitted = true
                    emit(SkillEvent.Failed(CODE_TASK_SUSPENDED, ev.reason.reasonKey))
                }

                is TaskEvent.Suspended -> {
                    terminalEmitted = true
                    emit(SkillEvent.Failed(CODE_TASK_SUSPENDED, ev.suspension.reasonKey))
                }

                is TaskEvent.Terminal -> {
                    terminalEmitted = true
                    emit(outcomeOf(ev, ctx))
                }

                // TokenDelta / StatusChanged / CheckpointSaved / RepairIssued —— 刻意不投影（§2.6 ⑤）
                else -> Unit
            }
        }

        if (!terminalEmitted) {
            // 任务流没有终结语是 AgentLoop 的契约违约，但聊天侧不能因此永远转圈。
            emit(SkillEvent.Failed(CODE_TASK_NO_TERMINAL, null))
        }
    }

    private fun outcomeOf(ev: TaskEvent.Terminal, ctx: SkillContext): SkillEvent {
        if (!ev.isSuccess) {
            return SkillEvent.Failed(CODE_TASK_FAILED + ":" + ev.reason.name, ev.detail)
        }
        val outcome = ev.outcome
        val raw = outcome?.answer?.trim().orEmpty()
        val summary = if (raw.isEmpty()) NO_ANSWER_NOTE else raw
        // 任务产出可能含网页原文 ⇒ 过脱敏闸。次数要一路上报（redaction_count 进消息表）。
        val scrubbed = ctx.redactionGate.scrub(summary).let { it.text.take(SUMMARY_MAX_CHARS) }
        val redactions = ctx.redactionGate.scrub(summary).redactionCount
        return SkillEvent.Succeeded(
            SkillOutcome(
                summaryRedacted = scrubbed,
                derivedTaskId = outcome?.taskId?.value ?: ctx.derivedTaskId(),
                stepsDone = outcome?.stepsDone ?: 0,
                prefillTokensTotal = outcome?.prefillTokensTotal ?: 0L,
                decodeTokensTotal = outcome?.decodeTokensTotal ?: 0L,
                redactionCount = redactions,
                // ★ 技能自报的 escalation **不作数**：真正的判定在 ChatLoop 把这段文本
                //   塞进 <skill_result> 时由 InjectionIsolator 给出（它是唯一看过全文的那一层）。
                escalation = null,
            ),
        )
    }

    public companion object {
        public const val SKILL_ID: String = "browser_task"

        /** 目标 ≤ ~256 token（§2.6）。超了就在落段时被 tailCut，这里先自截一刀更好定位。 */
        public const val SUMMARY_MAX_CHARS: Int = 600

        public const val NO_ANSWER_NOTE: String = "(任务已完成，但没有产出文字结论)"

        public const val CODE_BAD_ARGS: String = "BAD_ARGS"
        public const val CODE_TASK_FAILED: String = "TASK_FAILED"
        public const val CODE_TASK_SUSPENDED: String = "TASK_SUSPENDED"
        public const val CODE_TASK_NO_TERMINAL: String = "TASK_NO_TERMINAL"

        public const val KIND_OBSERVED: String = "observed"
        public const val KIND_PLANNED: String = "planned"
        public const val KIND_EXECUTED: String = "executed"
        public const val KIND_GUARDED: String = "guarded"
        public const val KIND_GUARD: String = "guard"
        public const val KIND_PERCEPTION_FAULT: String = "perception_fault"
    }
}
