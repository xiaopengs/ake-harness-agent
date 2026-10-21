package com.ake.core.contract

import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.common.Url

/**
 * 任务 Spec 与「怎么算做完」的可执行谓词（架构 §3.5 Layer 2）。
 *
 * ★ **`SuccessCriteria` 必须是代码判定，不是自然语言描述。**
 * 模型说「做完了」只是三票里的一票；另外两票来自这里的可执行谓词与终止保护。
 * 「取 N 条数据」这类结构性要求用代码写死，让模型只填参数 —— 端侧的正解。
 */
public data class SuccessCriteria(
    val minRecords: Int = 0,
    val requiredFields: Set<String> = emptySet(),
    val requireDomSignal: Set<DomSignal> = emptySet(),
    val requireNoErrorText: Boolean = false,
) {
    /** 写进 prompt 的自然语言描述，同时供 UI 显示。 */
    public fun describe(): String = buildList {
        if (minRecords > 0) add("min_records=$minRecords")
        if (requiredFields.isNotEmpty()) add("fields=[${requiredFields.sorted().joinToString(",")}]")
        if (requireDomSignal.isNotEmpty()) add("signals=[${requireDomSignal.joinToString(",") { it.name }}]")
        if (requireNoErrorText) add("no_error_text=true")
    }.joinToString(" ")

    public fun isTrivial(): Boolean = describe().isEmpty()
}

public data class TaskSpec(
    val id: TaskId,
    val goal: String,
    val successCriteria: SuccessCriteria = SuccessCriteria(),
    val startUrl: Url? = null,
    /** 任务期间允许访问的裸域名集合。空集表示「不限」（由 PolicyPort 兜底）。 */
    val allowHosts: Set<String> = emptySet(),
    val maxSteps: Int = 25,
    val createdAtMillis: Long = 0L,
)

/**
 * Task 生命周期取值。**status 只承载 8 种主状态**（主理人裁决 2026-10）。
 *
 * ## ★ 裁决：终止原因不进 status（`(status, terminal_reason)` 二元组）
 *
 * 数据库 §2.4 原本把终止原因编码进 status（`ABORTED_BUDGET` / `ABORTED_TIMEOUT` / … 共 10 个
 * `ABORTED_*` 变体）。**已否决**，理由：
 *  - `TERMINAL` 是个封闭集合，而 `ABORTED_*` 会随终止保护项增加无限膨胀；
 *  - 把原因塞进 status 会让「查所有失败任务」从 `status = 'FAILED'` 退化成 `status LIKE 'ABORTED_%'
 *    OR status LIKE 'FAILED_%'` 这类前缀匹配 —— 前缀匹配既走不上索引，也一定会漏掉新变体。
 *
 * 于是：**status ∈ 这 8 个值；具体原因一律放 `terminal_reason` 列**（见 [TaskRecord.terminalReason]）。
 * 「查所有失败任务」= `status IN ('FAILED','IMPOSSIBLE','ABORTED')`，仍是等值查询。
 *
 * ## ★ 裁决：暂停是独立语义，不是「软终止」
 *
 * [SUSPENDED] 里的两个状态是**可恢复的非终态**。UI 必须能一眼区分
 * 「暂停待恢复」与「已结束」，所以这里给出 [TaskPhase] 与 [isSuspended]，
 * 而不是让调用方拿 `status == "ABORTED"` 去猜。
 */
public object TaskStatus {
    public const val CREATED: String = "CREATED"
    public const val RUNNING: String = "RUNNING"
    public const val PAUSED: String = "PAUSED"
    public const val WAITING_HUMAN: String = "WAITING_HUMAN"
    public const val SUCCEEDED: String = "SUCCEEDED"
    public const val FAILED: String = "FAILED"
    public const val IMPOSSIBLE: String = "IMPOSSIBLE"
    public const val ABORTED: String = "ABORTED"

    /** 全部 8 个取值。供单测与 lint 卡住「有人加了新 status 却没更新映射」。 */
    public val ALL: Set<String> = setOf(CREATED, RUNNING, PAUSED, WAITING_HUMAN, SUCCEEDED, FAILED, IMPOSSIBLE, ABORTED)

    /** 终态：任务已结束，不可恢复。 */
    public val TERMINAL: Set<String> = setOf(SUCCEEDED, FAILED, IMPOSSIBLE, ABORTED)

    /** 挂起态：**已暂停但可恢复**，与终态互斥。 */
    public val SUSPENDED: Set<String> = setOf(PAUSED, WAITING_HUMAN)

    /** 活跃态：还在烧预算。 */
    public val ACTIVE: Set<String> = setOf(CREATED, RUNNING)

    public fun isTerminal(s: String): Boolean = s in TERMINAL

    /** 已暂停（可恢复）。UI 用它决定「显示继续按钮」还是「显示已结束」。 */
    public fun isSuspended(s: String): Boolean = s in SUSPENDED

    /** 活跃。 */
    public fun isActive(s: String): Boolean = s in ACTIVE

    /** status → 三相位。UI 的唯一判定入口，禁止在 UI 里直接比字符串。 */
    public fun phaseOf(s: String): TaskPhase = when {
        isTerminal(s) -> TaskPhase.TERMINAL
        isSuspended(s) -> TaskPhase.SUSPENDED
        else -> TaskPhase.ACTIVE
    }
}

/**
 * 任务的三相位（UI 渲染的唯一判定依据）。
 *
 * 为什么要有它：`PAUSED` 与 `ABORTED` 在 UI 上是两种完全不同的呈现 ——
 * 前者「已暂停，点击继续」，后者「已结束」。靠 `status` 字符串比较迟早会写错分支，
 * 所以把相位做成枚举，让 UI 只能 `when(phase)` 穷尽。
 */
public enum class TaskPhase {
    /** 进行中（CREATED / RUNNING）。 */
    ACTIVE,

    /** ★ 已暂停但**可恢复**（PAUSED / WAITING_HUMAN）。不是终态。 */
    SUSPENDED,

    /** 已终止，不可恢复（SUCCEEDED / FAILED / IMPOSSIBLE / ABORTED）。 */
    TERMINAL,
}

/**
 * ★ 「已暂停（可恢复）」这一事实的领域载体（主理人裁决 2026-10）。
 *
 * 它与终止**在同一层被区分开**：`TaskSuspension` 只可能携带 [TaskStatus.SUSPENDED] 里的 status，
 * 构造时即 `require` 校验，所以「拿一个暂停当终止渲染」在构造点就崩，不会静默流到 UI。
 *
 * 用途：
 *  - `:agent` 主循环挂起时**发这个而不是发一条伪终止事件**；
 *  - `:data` 落盘时写 `status = suspension.status`、`terminal_reason` 保持 `null`
 *    （暂停没有终止原因，这一点很重要 —— 有值就意味着它其实是终态）；
 *  - `:ui` 冷启动后按 `TaskPhase.SUSPENDED` 恢复「继续」按钮。
 */
public data class TaskSuspension(
    val taskId: TaskId,
    /** 只能是 [TaskStatus.PAUSED] 或 [TaskStatus.WAITING_HUMAN]。 */
    val status: String,
    /** i18n key（如 `suspend_thermal`），供 UI 显示「因温度过高暂停」。 */
    val reasonKey: String,
    /** 暂停发生在第几步 —— 恢复时的起点。 */
    val stepSeq: Int,
    /** 是否存在可恢复的断点。false 表示「暂停了但断点没存住」，UI 应禁用继续按钮。 */
    val checkpointSaved: Boolean = true,
) {
    init {
        require(status in TaskStatus.SUSPENDED) {
            "TaskSuspension.status 必须是挂起态（${TaskStatus.SUSPENDED}），实际='$status'。" +
                "终态请用 terminal_reason 表达，不要用 TaskSuspension。"
        }
    }

    /** [TaskStatus.WAITING_HUMAN] 需要 UI 立刻响应，其余只需等条件恢复。 */
    val needsUserAction: Boolean get() = status == TaskStatus.WAITING_HUMAN

    public companion object {
        public fun of(
            taskId: TaskId,
            status: String,
            reasonKey: String,
            stepSeq: Int,
            checkpointSaved: Boolean = true,
        ): TaskSuspension = TaskSuspension(taskId, status, reasonKey, stepSeq, checkpointSaved)
    }
}

/**
 * 一步的完整记录。它是 trace 的核心，也是 `DeterministicCompactor` 的输入
 * （压缩行数 ≤ 15–25 token/步，50 步历史只要 ~1,000 token）。
 */
public data class StepRecord(
    val id: StepId,
    val taskId: TaskId,
    /** 任务内序号，与 ULID 时间序一致 */
    val index: Int,
    val createdAtMillis: Long,
    val action: StepAction,
    val result: ToolResult,
    val status: String = StepStatus.OK,
    val modelRawRedacted: String? = null,
    val prefillTokens: Int = 0,
    val decodeTokens: Int = 0,
    val ttftMs: Int? = null,
    val decodeTps: Double? = null,
    /** §4.3 `currentCacheTokens()` 快照 —— 用于预判压缩时机 */
    val kvCacheTokensAfter: Int? = null,
    val promptTokensEstimate: Int = 0,
    val budgetRemainingPct: Int? = null,
    val pageDigest: String? = null,
    val observationId: String? = null,
    val durationMs: Long? = null,
    val repairAttempts: Int = 0,
    val errorCode: String? = null,
)

public object StepStatus {
    public const val OK: String = "OK"
    public const val REPAIRED: String = "REPAIRED"
    public const val DENIED: String = "DENIED"
    public const val ERROR: String = "ERROR"
    public const val ABORTED: String = "ABORTED"
}

/** 页面变化类型（数据库 §3 step.page_changed）。 */
public enum class PageChangeKind { DOM_CHANGED, NO_CHANGE, URL_MOVED }

/** 跨任务沉淀的小记忆（站点结构笔记等）。MVP 只做「最近 N 条 + host 过滤」。 */
public data class MemoryCard(
    val id: String,
    val host: String,
    val kind: String,
    val text: String,
    val createdAtMillis: Long,
)
