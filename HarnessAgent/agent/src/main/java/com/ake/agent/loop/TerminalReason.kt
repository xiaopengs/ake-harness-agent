package com.ake.agent.loop

import com.ake.core.contract.TaskStatus

/**
 * # 十二终态状态机（架构 §3.5）
 *
 * 「绝不能让模型单独宣告『做完了』」—— 终态由**三重判定**产生，模型的意见只占一票：
 *
 *  - **Layer 2 结构性谓词**（主力）：`SuccessCriteria` 是代码判定，模型说完成也要过这一关。
 *  - **Layer 1 模型显式 finish**：`{"act":"finish"}`，且 answer 必须能在本轮 observation 里溯源。
 *  - **Layer 0 终止保护**（熔断）：预算/循环/健康，每步开头先跑，不等模型开口。
 *
 * ## 流转图
 *
 * ```
 *                          ┌──────────┐
 *                          │ CREATED  │
 *                          └────┬─────┘
 *                               │ start()
 *                          ┌────▼─────┐   user stop / battery<8% / guard abort
 *                          │ RUNNING  │──────────────┬───────────────────────┐
 *                          └────┬─────┘              │                       │
 *        thermal>=SEVERE ┌──────┘  └──────┐ confirm  │                       │
 *        battery<15%    │                 │ required │                       │
 *              ┌────────▼──────┐   ┌──────▼────────┐ │                       │
 *   ┌─────────►│     PAUSED    │   │ WAITING_HUMAN │ │                       │
 *   │          │ (resumable)   │   │  (resumable)  │ │                       │
 *   │          └────────┬──────┘   └──────┬────────┘ │                       │
 *   │  resume()         │ saveAndStop     │ resolve()│                       │
 *   │                   │                 │          │                       │
 *   │  ┌────────────────┼─────────────────┘          │                       │
 *   └──┤                │                            │                       │
 *      │      ┌─────────▼─────────┐                  │                       │
 *      │      │  十二终态 · 入口   │                  │                       │
 *      │      └─────────┬─────────┘                  │                       │
 *      │                │                            │                       │
 *      │   ┌────────────┼────────────┬───────────────┤                       │
 *      │   │            │            │               │                       │
 *  ┌───▼───┴──┐  ┌──────▼─────┐  ┌───▼────────┐  ┌───▼────────┐              │
 *  │SUCCEEDED │  │ IMPOSSIBLE │  │  ABORTED   │  │   FAILED   │              │
 *  │ ①COMPLETED│ │②INFEASIBLE │  │④USER_ABORT │  │⑤BUDGET_    │              │
 *  └───────────┘ │③FABRICATED │  │⑫BATTERY_   │  │ EXHAUSTED  │              │
 *                │  _ANSWER   │  │  STOP      │  │⑥TIMEOUT    │              │
 *                └────────────┘  └────────────┘  │⑦LOOP_      │              │
 *                                                 │ DETECTED   │              │
 *                                                 │⑧NO_PROGRESS│             │
 *                                                 │⑨OSCILLATION│             │
 *                                                 │⑩LOCATE_    │             │
 *                                                 │  FAILED    │             │
 *                                                 │⑪REPAIR_    │             │
 *                                                 │  FAILED    │             │
 *                                                 └────────────┘             │
 *                                                                            │
 *      （④ 也可由 WAITING_HUMAN 的 Resolved.Abort 直接进入 ────────────────────┘
 * ```
 *
 * ## 十二终态的迁移条件
 *
 * | # | 终态 | Layer | 判定条件 | 落库 status |
 * |---|---|---|---|---|
 * | ① | COMPLETED | L1+L2 | 模型 `finish` **且** criteria 满足 **且** answer 可溯源 | SUCCEEDED |
 * | ② | INFEASIBLE | L2 | 模型 `finish(impossible)` / 页面 BLOCKED·CAPTCHA·LOGIN_WALL | IMPOSSIBLE |
 * | ③ | FABRICATED_ANSWER | L2 | `finish` 但 answer 在当轮 observation 里溯源命中率低于阈值 | IMPOSSIBLE |
 * | ④ | USER_ABORT | L0 | `AbortSignal.isAborted()` 或 `Resolved.Abort` | ABORTED |
 * | ⑤ | BUDGET_EXHAUSTED | L0 | `steps > maxSteps` 或 prefill/decode 累计超上限 | FAILED |
 * | ⑥ | TIMEOUT | L0 | `elapsed > maxWallClockMs` | FAILED |
 * | ⑦ | LOOP_DETECTED | L0 | 同一 `actionKey` **连续 3 次**（立即熔断，不给第四次机会） | FAILED |
 * | ⑧ | NO_PROGRESS | L0 | 连续 **4 步** pageDigest 未变 且 无 record 增量 且 URL 未变 | FAILED |
 * | ⑨ | OSCILLATION | L0 | URL 历史出现长度 ≥ 3 的环（A→B→A） | FAILED |
 * | ⑩ | LOCATE_FAILED | L0 | 同一元素定位失败 2 次后强制降级，再失败则熔断 | FAILED |
 * | ⑪ | REPAIR_FAILED | L0 | 连续 2 次输出无法解析 | FAILED |
 * | ⑫ | BATTERY_STOP | L0 | 电量 < 8% 且未充电 → saveAndStop（落盘进度） | ABORTED |
 *
 * ## ⚠ 与数据库 §2.4 的口径差异（需主理人裁决 —— 我没有改 `core/`）
 *
 * 数据库 §2.4 把终止原因**编码进 status**（`ABORTED_BUDGET` / `ABORTED_TIMEOUT` / …，共 10 个 ABORTED_* 变体），
 * 而 `core:contract/Task.kt` 的 `TaskStatus` 只有 8 个取值（`SUCCEEDED/FAILED/IMPOSSIBLE/ABORTED` + 4 个中间态），
 * 并把原因放在独立的 `terminal_reason` 列。
 *
 * **我按 `(status, terminal_reason)` 这一对实现** —— 因为 `TaskStatus.TERMINAL` 是个封闭集合，
 * 而 ABORTED_* 变体会随保护项增加无限膨胀；把原因塞进 status 会让「查询所有失败任务」退化成前缀匹配。
 * 若宋砚秋坚持 §2.4 的扁平写法，改的是 `data/` 里 `TaskEntity.status` 的写入值（一行映射函数），
 * 本文件与 `core/` 都不用动。
 *
 * ## 「暂停」与「终止」的区别（§3.5 原文强调）
 *
 * 热节流与低电量是**可恢复**的（[SuspendReason]），走 `PAUSED` 而非终态。
 * 一个跑了 90 秒的任务因掉电从头重来是不可接受的 —— 恢复时预算累计必须从 checkpoint 带回来。
 */
enum class TerminalReason(
    /** 落库 `task.status`。取值来自 `core:contract/TaskStatus`，不自创。 */
    val status: String,
    /** 落库 `task.terminal_reason`，同时也是 UI 文案的 i18n key。 */
    val reasonKey: String,
    /** 是否属于「正常完成」。用于 UI 选颜色与统计口径。 */
    val isSuccess: Boolean,
    /** 是否保留断点供用户手动重试（与「自动恢复」无关，仅决定要不要清 checkpoint）。 */
    val keepCheckpoint: Boolean,
) {

    // ── Layer 1 + Layer 2：三层判定一致才算完成 ──────────────────────
    COMPLETED(TaskStatus.SUCCEEDED, "terminal_completed", isSuccess = true, keepCheckpoint = false),

    // ── Layer 2：结构性判定 ────────────────────────────────────────
    INFEASIBLE(TaskStatus.IMPOSSIBLE, "terminal_infeasible", isSuccess = false, keepCheckpoint = false),
    FABRICATED_ANSWER(TaskStatus.IMPOSSIBLE, "terminal_fabricated_answer", isSuccess = false, keepCheckpoint = true),

    // ── Layer 0：终止保护 ──────────────────────────────────────────
    USER_ABORT(TaskStatus.ABORTED, "terminal_user_abort", isSuccess = false, keepCheckpoint = true),
    BATTERY_STOP(TaskStatus.ABORTED, "terminal_battery_stop", isSuccess = false, keepCheckpoint = true),

    BUDGET_EXHAUSTED(TaskStatus.FAILED, "terminal_budget_exhausted", isSuccess = false, keepCheckpoint = false),
    TIMEOUT(TaskStatus.FAILED, "terminal_timeout", isSuccess = false, keepCheckpoint = true),
    LOOP_DETECTED(TaskStatus.FAILED, "terminal_loop_detected", isSuccess = false, keepCheckpoint = false),
    NO_PROGRESS(TaskStatus.FAILED, "terminal_no_progress", isSuccess = false, keepCheckpoint = false),
    OSCILLATION(TaskStatus.FAILED, "terminal_oscillation", isSuccess = false, keepCheckpoint = false),
    LOCATE_FAILED(TaskStatus.FAILED, "terminal_locate_failed", isSuccess = false, keepCheckpoint = true),
    REPAIR_FAILED(TaskStatus.FAILED, "terminal_repair_failed", isSuccess = false, keepCheckpoint = true),
    ;

    companion object {

        /** 全部取值 = 12。供单测与 lint 卡住「有人新增了终态却忘了更新流转表」。 */
        val ALL: List<TerminalReason> = entries

        fun parseOrNull(value: String?): TerminalReason? =
            if (value == null) null else entries.firstOrNull { it.name == value }
    }
}

/**
 * 可恢复挂起原因（**不是终态**）。落库 status = `PAUSED`，恢复后回到 `RUNNING`。
 *
 * 与 [TerminalReason] 分开是刻意的：二者在 UI 上的呈现完全不同 ——
 * 前者显示「已暂停，点击继续」，后者显示「已结束」。混在一个枚举里必然写出错的 UI 分支。
 */
enum class SuspendReason(val reasonKey: String) {
    /** `ThermalState >= SEVERE`：降温后可恢复。 */
    THERMAL("suspend_thermal"),

    /** 电量 < 15% 且未充电。 */
    BATTERY_LOW("suspend_battery_low"),

    /** HumanGate 挂起等待 UI 响应。status 是 `WAITING_HUMAN` 而不是 `PAUSED`。 */
    HUMAN_GATE("suspend_human_gate"),
    ;

    /** HumanGate 走独立 status（架构 §3.2 / 数据库 §2.4），其余走 PAUSED。 */
    val status: String
        get() = if (this == HUMAN_GATE) TaskStatus.WAITING_HUMAN else TaskStatus.PAUSED
}

/**
 * 状态迁移白名单。
 *
 * **为什么要把迁移表写成代码而不是散在 if 里**：端侧 Agent 的「任务卡在某个中间态」
 * 是高频且难复现的故障。有一张显式迁移表，就能在单测里穷尽 8×14 种组合，
 * 并在生产上对非法迁移打 ERROR 而不是静默改状态 —— 静默改状态会让 UI 永远显示「进行中」。
 */
object TaskPhaseMachine {

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

    fun canTransition(from: String, to: String): Boolean =
        TRANSITIONS[from]?.contains(to) ?: false

    fun isTerminal(status: String): Boolean = TaskStatus.isTerminal(status)

    /** 终态 → 终态一律非法（包括「成功后再失败」这种数据损坏信号）。 */
    fun isSuspended(status: String): Boolean =
        status == TaskStatus.PAUSED || status == TaskStatus.WAITING_HUMAN
}
