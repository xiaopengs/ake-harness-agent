package com.ake.data.persist.write

import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.TaskCheckpointEntity
import com.ake.data.persist.db.entity.TaskEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.TraceEventEntity

/**
 * 写入队列里的操作单元（数据库 §6.3）。
 *
 * ## 为什么是「操作」而不是「Entity 的联合」
 *
 * `observation` 这一条要**同时**带一个 DB 行和一个外置文件载荷 ——
 * 单个 Entity 装不下这个组合，而「行与文件谁先落」的顺序是正确性的一部分
 * （§4.5 纪律 1：先写文件再写行，绝不能出现「有行无文件」）。
 * 把它做成一个原子 Op，顺序就不会被写散到调用点去。
 */
public sealed interface Op {

    val priority: Priority

    /** 任务创建 / 状态跃迁 —— 不可丢。 */
    data class TaskUpsert(val row: TaskEntity) : Op {
        override val priority: Priority = Priority.P0
    }

    /** 预算累计推进 —— 不可丢（丢了等于暂停白送一张预算卡）。 */
    data class BudgetAdvance(
        val taskId: String,
        val stepsDone: Int,
        val prefillTotal: Long,
        val decodeTotal: Long,
        val elapsedTotal: Long,
        val lastActivityAt: Long,
    ) : Op {
        override val priority: Priority = Priority.P0
    }

    data class TaskFinish(
        val taskId: String,
        val status: String,
        val reason: String?,
        val finishedAt: Long,
        val answerEnc: ByteArray?,
        val answerPreview: String?,
    ) : Op {
        override val priority: Priority = Priority.P0
    }

    /**
     * ★ 挂起（PAUSED / WAITING_HUMAN）—— 不可丢，但**不是** [TaskFinish]。
     *
     * 与 [TaskFinish] 的唯一差别是落盘时不动 `finished_at`、并把 `terminal_reason` 置空。
     * 合成一个 Op 靠 `status` 分支写入会诱使后来者「顺手」把两者合并，
     * 而合并的代价是暂停中的任务被清理作业当成已结束的捞走 —— 不值得省这一个 Op。
     */
    data class TaskSuspend(
        val taskId: String,
        val status: String,
        val at: Long,
    ) : Op {
        override val priority: Priority = Priority.P0
    }

    /** 断点 —— 不可丢（恢复的正确性依赖它）。 */
    data class Checkpoint(val row: TaskCheckpointEntity) : Op {
        override val priority: Priority = Priority.P0
    }

    /** 护栏审计 —— 不可丢（用户问「它为什么点了这个」时这是唯一答案）。 */
    data class PolicyDecision(val row: PolicyDecisionEntity) : Op {
        override val priority: Priority = Priority.P0
    }

    data class Step(val row: StepEntity) : Op {
        override val priority: Priority = Priority.P1
    }

    data class ToolInvocation(val row: ToolInvocationEntity) : Op {
        override val priority: Priority = Priority.P1
    }

    data class Observation(val row: ObservationEntity, val payload: String?) : Op {
        override val priority: Priority = Priority.P1
    }

    data class TraceEvent(val row: TraceEventEntity, override val priority: Priority = Priority.P2) : Op

    /**
     * 丢弃自证（§6.3 取舍 4）。
     *
     * **丢弃必须被记录**：否则回放 UI 少了几步，工程师会花半天去找
     * 「数据库写错了还是 Agent 没跑」—— 而真相只是队列满了一次。
     */
    data class SyntheticTraceDropped(
        val taskId: String,
        val droppedOf: Priority,
        val createdAt: Long,
        val id: String,
        val seq: Long,
    ) : Op {
        override val priority: Priority = Priority.P1
    }
}

/** 三级优先级（数据库 §6.3 取舍 2）。 */
enum class Priority { P0, P1, P2 }

/** 队列容量。**有界**是硬要求：`UNLIMITED` 等于允许队列无限膨胀，是 OOM 的一条免费通道。 */
object QueueCaps {
    /** 不可丢：task 状态跃迁 / 断点 / 最终答案 / DENIED。深度恒为 0 才是健康的。 */
    const val P0: Int = 64

    /** 常规：step / tool / observation。 */
    const val P1: Int = 512

    /** 可丢：DEBUG 事件。 */
    const val P2: Int = 1024

    const val MAX_BATCH: Int = 64

    /** 批量事务之间的合并窗口。 */
    const val COALESCE_MS: Long = 200L
}
