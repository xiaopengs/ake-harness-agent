package com.ake.data.persist.write

import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.TaskCheckpointEntity
import com.ake.data.persist.db.entity.TaskEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.TraceEventEntity

/**
 * # 写入可靠性分级（2026-10 P1 缺陷修复）
 *
 * ## ★ 缺陷原文
 *
 * 原先所有写入共用一个 `TraceBufferWriter`（三级有界 Channel + `DROP_OLDEST`），
 * 而 `Op.TaskUpsert` / `Op.TaskFinish` / `Op.TaskSuspend` **也走它**。后果是两种不可接受的数据事故：
 *
 *  1. **任务凭空消失**：`TaskUpsert`（建行）被丢、`TaskFinish` 正常落盘 ⇒
 *     `UPDATE task SET ... WHERE id = :id` 打到一条不存在的行 ⇒ 影响 0 行且不报错。
 *     任务既不出现在历史里，也无法被任何清理策略回收。
 *  2. **永久 RUNNING**：`TaskFinish` 被丢 ⇒ 行停在 `RUNNING`、`finished_at` 永远为 `NULL` ⇒
 *     保留策略（`pickForDigest` 以 `finished_at IS NOT NULL` 为入口）与恢复逻辑都看不到它。
 *
 * 补充一个验收已指出的事实：**给事件流加 `observeRecent()`（Flow 版）修不了这个问题** ——
 * Room 的 Flow 只在**事务提交后**重发；写入被丢弃时事务根本没发生（没有的心跳也不会跳）。
 * 所以这是个**写入可靠性**问题，不是读取时序问题。
 *
 * ## 分级标准：不是「重不重要」，而是「量有没有上界」
 *
 * 单纯按重要程度分级会得出「全都不可丢」的结论，那就等于取消了背压。
 * 真正的判据是**单个任务生命期内该 Op 的数量有没有结构性上界**：
 *
 * | 泳道 | Op | 数量级 | 判据 |
 * |---|---|---|---|
 * | [StateOp] | TaskUpsert / BudgetAdvance / TaskFinish / TaskSuspend / Checkpoint / PolicyDecision | **O(步数)**（≤ 数十/任务） | 有上界 ⇒ 有界队列装得下 ⇒ 可以让它永不丢 |
 * | [TraceOp] | Step / ToolInvocation / Observation / TraceEvent / SyntheticTraceDropped | **O(步数 × 每步对象数)**（观测/工具调用可达数百，且载荷是 KB 级外置文件） | 无上界 ⇒ 必须与「IO 可能和 2.58 GB 模型争抢」的现实共存 ⇒ 允许丢，但必须自证 |
 *
 * ## ★ 为什么是**两个互斥子类型**而不是一个 `Reliability` 枚举字段
 *
 * 字段式分级要靠运行时 `if` 或 review 自觉来兜 —— 而被修复的这个缺陷证明软约束守不住：
 * `QueueCaps.P0` 明明写着「不可丢、深度恒为 0 才是健康的」，队列照样 `DROP_OLDEST`。
 * 所以这里让它在**类型层面进不去**：`TraceBufferWriter.append` 只接受 [TraceOp]，
 * `TaskStateWriter.submit` 只接受 [StateOp]，写错就是编译错误。
 */
public sealed interface Op {

    /**
     * 每个 Op 都属于一个任务。
     *
     * 上提到基类是为了三件具体的事：① 丢弃台账能按任务归因；② 状态泳道不必懂子类型
     * 就能做「同任务保序」；③ 日志/观测可以按任务过滤，不需要逐个子类去写 when。
     */
    public val taskId: String
}

/**
 * **可丢弃**泳道（轨迹类）。
 *
 * 丢一两条是可接受的背压出口：每一行都只用于诊断/回放，且**有 `DIGEST` 级压缩 +
 * [Op.SyntheticTraceDropped] 自证兜底**。它让出的是延迟（`append()` 非挂起、不拖主循环），不是正确性。
 */
public sealed interface TraceOp : Op {

    /** ★ 只是「先救谁」，**不代表不可丢**。不可丢的唯一表达方式，是成为 [StateOp]。 */
    public val priority: Priority

    data class Step(val row: StepEntity) : TraceOp {
        override val taskId: String get() = row.taskId
        override val priority: Priority = Priority.P1
    }

    data class ToolInvocation(val row: ToolInvocationEntity) : TraceOp {
        override val taskId: String get() = row.taskId
        override val priority: Priority = Priority.P1
    }

    /** ★ 带上外置载荷本体 ——「先写文件再写行」这条纪律必须由 Op 自己保证顺序。 */
    data class Observation(val row: ObservationEntity, val payload: String?) : TraceOp {
        override val taskId: String get() = row.taskId
        override val priority: Priority = Priority.P1
    }

    data class TraceEvent(
        val row: TraceEventEntity,
        override val priority: Priority = Priority.P2,
    ) : TraceOp {
        override val taskId: String get() = row.taskId
    }

    /**
     * 丢弃自证。
     *
     * ★ **由 `TraceBufferWriter` 在排水时生成**，而不是由 `append()` 生成。
     * 旧实现在 `append()` 里 `target.trySend(自证)` —— 队列满的时候（也就是最需要它的
     * 时候）这条自证自己也会因为队列满被丢掉。改成排水时按台账**聚合**生成后，
     * 它就在事务里，绝不可能被丢，也不会引发自证风暴。
     *
     * @param droppedCount 自上一次自证以来累计被丢弃的同优先级行数。
     */
    data class SyntheticTraceDropped(
        override val taskId: String,
        val droppedOf: Priority,
        val droppedCount: Int,
        val createdAt: Long,
        val id: String,
        val seq: Long,
    ) : TraceOp {
        override val priority: Priority = Priority.P1
    }
}

/**
 * **不可丢弃**泳道（任务状态类 + 审计类）。
 *
 * 进了这一类的 Op 不可能被静默抛弃：`TaskStateWriter` 的通道有界但**不丢**，
 * 满时是**调用方等待**而不是数据牺牲（三级阶梯的完整论证见该类）。
 *
 * ## 保序 —— 「任务凭空消失」的结构性解药
 *
 * 状态泳道是**单通道、单消费者**：`create()` 的 [TaskUpsert] 一定先于同任务的 [TaskFinish]
 * 落进同一个事务 ⇒ finish 的 `UPDATE` 不可能打到一条不存在的行。
 * 注意这与 SQL 无关 —— 换成 `UPSERT` 也救不了「先后顺序」这类正确性，只有保序能做到。
 */
public sealed interface StateOp : Op {

    /** 任务创建 / 状态跃迁 —— 丢了就是任务凭空消失。 */
    data class TaskUpsert(val row: TaskEntity) : StateOp {
        override val taskId: String get() = row.id
    }

    /** 预算累计推进 —— 丢了等于每次暂停白送一张预算卡。 */
    data class BudgetAdvance(
        override val taskId: String,
        val stepsDone: Int,
        val prefillTotal: Long,
        val decodeTotal: Long,
        val elapsedTotal: Long,
        val lastActivityAt: Long,
    ) : StateOp

    data class TaskFinish(
        override val taskId: String,
        val status: String,
        val reason: String?,
        val finishedAt: Long,
        val answerEnc: ByteArray?,
        val answerPreview: String?,
    ) : StateOp

    /**
     * ★ 挂起（PAUSED / WAITING_HUMAN）—— 不可丢，但**不是** [TaskFinish]。
     *
     * 与 [TaskFinish] 的唯一差别是落盘时不动 `finished_at`、并把 `terminal_reason` 置空。
     * 合成一个 Op 靠 `status` 分支写入会诱使后来者「顺手」把两者合并，
     * 而合并的代价是暂停中的任务被清理作业当成已结束的捞走 —— 不值得省这一个 Op。
     */
    data class TaskSuspend(
        override val taskId: String,
        val status: String,
        val at: Long,
    ) : StateOp

    /** 断点 —— 不可丢（四个预算累计全在里面）。 */
    data class Checkpoint(val row: TaskCheckpointEntity) : StateOp {
        override val taskId: String get() = row.taskId
    }

    /**
     * 护栏审计 —— 不可丢（用户问「它为什么点了这个」时这是唯一答案）。
     *
     * ★ 它被划进 [StateOp] 而不是「优先级更高的 TraceOp」：一次 `DENIED` 丢了，
     * 一个安全事件就永远无法归因。它的量级是 O(工具调用)，有上界，装得进可靠泳道。
     */
    data class PolicyDecision(val row: PolicyDecisionEntity) : StateOp {
        override val taskId: String get() = row.taskId
    }
}

/**
 * 轨迹优先级（只在轨迹泳道内生效）。
 *
 * **P0 已随「不可丢」这一类 Op 迁往 `TaskStateWriter` 而退役**：
 * 继续留一个 P0 通道会让人误以为「标成 P0 就不会丢」，而本轮缺陷正是这么发生的
 * （`QueueCaps.P0` 的注释写着「不可丢」，实现却是 `DROP_OLDEST`）。
 * 现在的语义很清楚：想不丢就必须是 [StateOp]；留在 [TraceOp] 里的，连 P1 都可能在极端 IO 下被牺牲。
 */
public enum class Priority { P1, P2 }

/**
 * 轨迹队列容量。**有界**是硬要求：`UNLIMITED` 等于允许队列无限膨胀，是 OOM 的一条免费通道。
 */
public object QueueCaps {

    /** 常规轨迹：step / tool / observation。 */
    public const val P1: Int = 512

    /** 可丢：DEBUG 事件。 */
    public const val P2: Int = 1024

    public const val MAX_BATCH: Int = 64

    /** 批量事务之间的合并窗口。**只有轨迹泳道有**；状态泳道「收到即提交」。 */
    public const val COALESCE_MS: Long = 200L
}

/**
 * 状态泳道容量，以及「为什么这个数不会 OOM」的算术。
 *
 * ```
 * 单任务状态 Op 总量 ≈ 1(TaskUpsert) + 步数(≤40) + 断点(≤40) + 审计(≤步数×数个) + 1(TaskFinish)
 *                    ≈ 10² 量级          ← 由 LoopBudget.maxSteps 结构性封顶
 * 单 Op 驻留字节     ≈ 10² 量级（断点最大，含 seenDigest / scratchpad 密文）
 * ⇒ 单任务 ≤ 数十 KB；即便同时挂着 100 个任务也在 MB 级。
 * ```
 *
 * 所以 2048 的深度是留了**一个数量级以上余量**的。它一旦被填满，说明下游已经异常
 * （磁盘满 / 写线程饿死）—— 那时 `metrics.stateBacklog` 必然报警，而报警远比静默丢弃好处置。
 */
public object StateLaneCaps {

    /** 状态泳道深度。填满是**异常事件**（有 metric），不是流量事件。 */
    public const val CAPACITY: Int = 2048

    /** 单次事务的最大 Op 数。状态泳道不靠攒批降 IO，这只用来限制单事务持锁时长。 */
    public const val MAX_BATCH: Int = 64

    /**
     * 「一次性写入」等待落盘确认的宽限：只有 `create()` 与终态 `finish()` 等，
     * 每步的 `advance()` / `checkpoint()` **不等**。
     *
     * 2 秒 ≫ 一次 SQLite 事务的正常耗时（SLO 15 ms），足以覆盖一次偶发抖动，
     * 又远小于用户对「点了停止之后 UI 卡住」的容忍度。
     */
    public const val COMMIT_AWAIT_MS: Long = 2_000L

    /** 极端情况下的交接等待（`send()`）宽限。超时即抛出，由调用方 `runCatching` 兜住。 */
    public const val SUBMIT_GRACE_MS: Long = 5_000L

    /** 写入失败的指数退避：0.25s → 0.5s → 1s → 2s → 4s（封顶）。 */
    public const val RETRY_BASE_MS: Long = 250L

    public const val RETRY_MAX_TIMES: Int = 5
}
