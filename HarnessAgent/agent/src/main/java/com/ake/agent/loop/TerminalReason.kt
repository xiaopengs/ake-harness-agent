package com.ake.agent.loop

import com.ake.core.contract.TaskStatus

/**
 * ★★ `TerminalReason` 与 `SuspendReason` 的主体已在 `:core:ports`
 * （`com.ake.core.ports.TerminalReason` / `com.ake.core.ports.SuspendReason`）。
 *
 * 本文件不再持有第二份定义 —— 这里只留两行 `typealias` 转发，
 * 加上本模块自己那张 [TaskPhaseMachine] 迁移表（它不属于 Port 契约，理由见其注释）。
 *
 * ## 为什么是 typealias 而不是「直接删」
 *
 * 与 `AgentEvents.kt` 同一批、同一个打法，理由写在那里：`:ui` 有
 * `import com.ake.agent.loop.TerminalReason` / `SuspendReason` 共 4 处，
 * 直接删会让 UI 编译失败，而摘 Gradle 边是「第 3 步」的事。
 * typealias 让旧坐标与新坐标指向**同一个枚举**，UI 的穷举 `when` 照旧工作。
 *
 * ## 两个枚举的语义不变（迁移前的原文摘要，便于 review 时对照）
 *
 *  - [TerminalReason]：十二终态，**由三重判定产生，模型的意见只占一票** ——
 *    Layer 2 结构性谓词（主力）+ Layer 1 模型显式 finish + Layer 0 终止保护熔断。
 *  - [SuspendReason]：**不是终态**，热节流 / 低电量 / HumanGate 挂起，可恢复。
 *    二者分开是刻意的：「已暂停，点击继续」与「已结束」在 UI 上是两种呈现。
 *
 * ## ⚠ 保留事项：终态原因与数据库 §2.4 的口径差异（待主理人裁决 —— `:core` 不在本次边界）
 *
 * 数据库 §2.4 把终止原因**编码进 status**（`ABORTED_BUDGET` / `ABORTED_TIMEOUT` / …，共 10 个变体），
 * 而 `core:contract/TaskStatus` 只有 8 个取值，并把原因放在独立的 `terminal_reason` 列。
 * **实现按 `(status, terminal_reason)` 这一对走** —— 因为 `TaskStatus.TERMINAL` 是个封闭集合，
 * 而 ABORTED_* 变体会随保护项增加无限膨胀；把原因塞进 status 会让「查询所有失败任务」退化成前缀匹配。
 * 若改为 §2.4 的扁平写法，改的是 `data/` 里 `TaskEntity.status` 的写入值（一行映射函数）。
 */
public typealias TerminalReason = com.ake.core.ports.TerminalReason

public typealias SuspendReason = com.ake.core.ports.SuspendReason

/**
 * 状态迁移白名单。
 *
 * **为什么要把迁移表写成代码而不是散在 if 里**：端侧 Agent 的「任务卡在某个中间态」
 * 是高频且难复现的故障。有一张显式迁移表，就能在单测里穷尽 8×14 种组合，
 * 并在生产上对非法迁移打 ERROR 而不是静默改状态 —— 静默改状态会让 UI 永远显示「进行中」。
 *
 * ★ **它刻意留在 `:agent`**：这张表服务于主循环自身的状态推进，**调用方只有 `:agent` 自己**。
 * UI 不该能判定「哪些迁移合法」—— 那相当于给了 UI 一张绕过完整状态机直接改 status 的通行证。
 * 把它放进 `core:ports` 就是把「决定哪次迁移合法」的能力公开了出去。
 */
public object TaskPhaseMachine {

    /**
     * 允许的迁移。`from → Set<to>`；未列出的组合一律非法。
     */
    private val TRANSITIONS: Map<String, Set<String>> = mapOf(
        TaskStatus.CREATED to setOf(TaskStatus.RUNNING, TaskStatus.ABORTED),
        TaskStatus.RUNNING to setOf(
            TaskStatus.RUNNING,
            TaskStatus.PAUSED,
            TaskStatus.WAITING_HUMAN,
            TaskStatus.SUCCEEDED,
            TaskStatus.FAILED,
            TaskStatus.IMPOSSIBLE,
            TaskStatus.ABORTED,
        ),
        TaskStatus.PAUSED to setOf(TaskStatus.RUNNING, TaskStatus.ABORTED),
        TaskStatus.WAITING_HUMAN to setOf(TaskStatus.RUNNING, TaskStatus.ABORTED),
    )

    public fun canTransition(from: String, to: String): Boolean =
        TRANSITIONS[from]?.contains(to) ?: false

    public fun isTerminal(status: String): Boolean = TaskStatus.isTerminal(status)

    /** 终态 → 终态一律非法（包括「成功后再失败」这种数据损坏信号）。 */
    public fun isSuspended(status: String): Boolean =
        status == TaskStatus.PAUSED || status == TaskStatus.WAITING_HUMAN
}
