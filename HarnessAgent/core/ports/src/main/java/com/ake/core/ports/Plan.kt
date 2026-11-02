package com.ake.core.ports

import com.ake.core.common.TaskId
import com.ake.core.contract.PlanRevision
import com.ake.core.contract.PlanStep
import kotlinx.coroutines.flow.Flow

/**
 * 计划版本存储（V1.0 M2）。
 *
 * ## 为什么"确认"是一次 CAS 而不是一个 boolean 字段
 *
 * 计划确认页可能同时存在于两个地方（任务详情、通知跳转），用户在两处都点了"开始"。
 * 如果确认只是 `UPDATE status='CONFIRMED'`，第二次点击会返回成功 —— 于是执行器会收到
 * 两次"开始执行"，而端侧只有一个推理焦点。CAS（只有 DRAFT 能变 CONFIRMED）
 * 让第二次点击**明确失败**，上层可以据此什么都不做。
 *
 * ## 为什么没有 `update(plan)` 这种通用写
 *
 * 见 `PlanDao` 的注释：允许原地改步骤会让"用户确认的是哪一版"这个前提失效。
 * 要改就重新生成一版（`saveDraft` 自动 +1）。
 */
public interface PlanStorePort {

    /**
     * 追加一版草稿，并把同一任务的旧草稿置为 `SUPERSEDED`。
     *
     * @return 落库后的版本（`revision` 已由实现分配）。
     */
    public suspend fun saveDraft(taskId: TaskId, goal: String, steps: List<PlanStep>): PlanRevision

    /**
     * 确认某一版。
     *
     * @return true = 这次点击真正把它从草稿变成了已确认；
     *         false = 已经不是草稿（已被确认 / 已被取代 / 不存在）—— 调用方**不该**报错。
     */
    public suspend fun confirm(taskId: TaskId, revision: Int): Boolean

    public suspend fun latest(taskId: TaskId): PlanRevision?

    public fun observeLatest(taskId: TaskId): Flow<PlanRevision?>
}

/**
 * 计划阶段的对外入口：**生成草稿 → 用户确认 → 逐步执行**。
 *
 * ## 为什么生成是 `suspend`、执行是 `Flow`
 *
 * 生成只发生一次，UI 需要的只有"成功 / 被拒 / 失败"三种结局 —— 中间过程是模型在吐字，
 * 吐到一半的 JSON 对用户没有意义（真正要看的是**结构化后的计划**）。
 * 执行则相反：5 步可能跑 90 秒，用户必须看到"现在在第 3 步、前两步已经出了什么"，
 * 否则和卡死没有区别。
 *
 * ## 为什么 `confirm` 不在这里直接接 `execute`
 *
 * 确认与执行之间隔着一次用户点击，中间可能隔几分钟（用户去接了杯水）。
 * 合成一个方法就意味着**必须把确认和执行绑在同一次调用里**，
 * 而恢复执行（进程被杀后重进）需要"已确认但还没跑"这个状态能被单独表达。
 */
public interface PlanCoordinatorPort {

    /**
     * 生成一版草稿并落库。
     *
     * 内部最多尝试 **2 次**（第一次失败后回灌一条"怎么改"再试一次）。
     * 不做第三次：2B 模型连错两次通常说明这个目标它拆不出来，
     * 再试只是让用户多等 20 秒。
     *
     * @param fileName 只用于给模型一个"有没有文件"的线索，**不会**被当作参数写进计划。
     */
    public suspend fun draft(
        taskId: TaskId,
        goal: String,
        fileName: String? = null,
    ): PlanDraftResult

    /** 同 [PlanStorePort.confirm]。 */
    public suspend fun confirm(taskId: TaskId, revision: Int): Boolean

    /**
     * 执行某一版**已确认**的计划。
     *
     * 冷 Flow；最后一条事件一定是终止语（[PlanRunEvent.Completed] / [PlanRunEvent.Failed] /
     * [PlanRunEvent.Aborted]），中途的 [PlanRunEvent.StepFailed] 之后还会有一条终止语。
     *
     * @param revision 调用方**必须**带上自己确认的那一版。传错版本直接失败而不是"跑最新的" ——
     *                 用户确认的是第 2 版，跑的却是第 3 版，这是审计上无法解释的。
     * @param document 用户选中的文件（已落盘）。计划里的 `read_file` / `pdf_extract`
     *                 靠它定位数据源；纯文本任务传 `null`。
     */
    public fun execute(
        taskId: TaskId,
        revision: Int,
        document: DocumentSource? = null,
        abort: AbortSignal = NoAbortSignal,
    ): Flow<PlanRunEvent>
}

/** [PlanCoordinatorPort.draft] 的结局。 */
public sealed interface PlanDraftResult {

    /**
     * @property dropped 模型写了但被丢弃的非法/重复步骤数。>0 说明模型没守 schema，
     *                   但计划本身可用 —— 不该因此让用户重来。
     * @property truncated 超过 5 步被截掉的条数。
     */
    public data class Drafted(
        public val revision: PlanRevision,
        public val dropped: Int = 0,
        public val truncated: Int = 0,
    ) : PlanDraftResult

    /** 两次都解析不出合法计划。`hint` 是给模型的下一条指令，不是给用户的文案。 */
    public data class Rejected(
        public val reason: String,
        public val hint: String,
    ) : PlanDraftResult

    /** 模型层失败（未加载 / 超时 / OOM）。与 [Rejected] 的区别是**用户重试可能有用**。 */
    public data class Failed(public val error: com.ake.core.common.AppError) : PlanDraftResult
}

/** 计划执行的事件。 */
public sealed interface PlanRunEvent {

    public data class Started(public val revision: Int, public val stepCount: Int) : PlanRunEvent

    public data class StepStarted(
        public val index: Int,
        public val kind: String,
        public val target: String,
    ) : PlanRunEvent

    /** @property summary 该步产出的**开头一小段**（给列表行显示），不是全文。 */
    public data class StepFinished(
        public val index: Int,
        public val summary: String,
        public val elapsedMs: Long,
    ) : PlanRunEvent

    /** 非终止：后面还会跟一条 [Failed]。 */
    public data class StepFailed(
        public val index: Int,
        public val error: com.ake.core.common.AppError,
    ) : PlanRunEvent

    public data class Completed(
        public val revision: Int,
        public val result: String,
        public val elapsedMs: Long,
    ) : PlanRunEvent

    public data class Failed(public val error: com.ake.core.common.AppError) : PlanRunEvent

    /** @property atIndex 被中止时**正要做**的那一步（0 表示还没开始）。 */
    public data class Aborted(public val atIndex: Int) : PlanRunEvent
}
