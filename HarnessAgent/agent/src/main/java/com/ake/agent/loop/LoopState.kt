package com.ake.agent.loop

import com.ake.core.common.Clock
import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.contract.LoopCheckpoint
import com.ake.core.contract.StepAction
import com.ake.core.contract.StepRecord
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.ToolResult
import com.ake.core.contract.UiElement

/**
 * 一次任务执行的**可变状态机载体**（架构 §3.2 `LoopState` + §3.5 Layer 0 的判据来源）。
 *
 * 它不是线程安全的：整个 Loop 跑在单个协程里，所有读写都在同一个线程上发生。
 * 刻意不加锁 —— 加锁会诱导调用点从 `withTimeout` 外面碰它，那才是真正的灾难
 * （超时取消时状态停在半更新态）。
 *
 * ## 断点续跑（§3.5 / 数据库 F1）
 *
 * [snapshot] 是唯一的出口，**必须带上的字段是四个预算累计** ——
 * 宋砚秋的原话是「预算累计丢失等于每次暂停白送一张预算卡」：
 * 一次热节流暂停若把 `prefillTokensTotal` 清零，用户点继续后这个任务能再烧一整份预算，
 * 而 UI 上完全看不出来。
 *
 * 刻意**不**落盘的：
 *  - `trace`：步记录本身已在 `step` 表里，恢复时从 `TraceStorePort.load()` 拉回摘要即可；
 *  - `dom` / `session`：模型 session 不可序列化，恢复时重建并从 `lastUrl` 继续（§3.5 最低实现）。
 */
class LoopState internal constructor(
    val task: TaskSpec,
    val budget: LoopBudget,
    val clock: Clock,
    startStepSeq: Int,
    startUsage: UsageTotals,
    startScratchpad: String,
    startSeenDigests: List<String>,
    startUrl: String?,
    startObservationId: String?,
    startKvCache: Int?,
    private val startedAtMs: Long,
) {

    constructor(task: TaskSpec, budget: LoopBudget, clock: Clock) : this(
        task = task,
        budget = budget,
        clock = clock,
        startStepSeq = 0,
        startUsage = UsageTotals(),
        startScratchpad = "",
        startSeenDigests = emptyList(),
        startUrl = task.startUrl?.value,
        startObservationId = null,
        startKvCache = null,
        startedAtMs = clock.nowMillis(),
    )

    // ──────────────────────────────────────────── 进度 / 预算累计（★ 落 checkpoint）

    /** 下一个要执行的步序号（0-based），同时也等于已完成步数。 */
    var stepSeq: Int = startStepSeq
        private set

    var usage: UsageTotals = startUsage
        private set

    var scratchpad: String = startScratchpad

    /** §3.5 NoProgress 的回溯证据：最近 8 个 pageDigest。 */
    private val seenDigests = ArrayDeque<String>(SEEN_DIGEST_CAP).also {
        it.addAll(startSeenDigests.takeLast(SEEN_DIGEST_CAP))
    }

    var lastUrl: String? = startUrl
        private set

    var lastObservationId: String? = startObservationId
        private set

    var lastKvCacheTokens: Int? = startKvCache
        private set

    // ──────────────────────────────────────────── 循环检测判据（§3.5 Layer 0）

    private val recentActionKeys = ArrayDeque<String>(ACTION_KEY_CAP)
    private val recentUrls = ArrayDeque<String>(URL_HISTORY_CAP)

    /** 连续「成功但页面没动」的步数。 */
    var stalledSteps: Int = 0
        private set

    /** 连续输出不可解析次数（§3.5：连续 2 次熔断）。 */
    var consecutiveParseFailures: Int = 0
        private set

    /** 本步内已发生的解析修正次数（写进 `step.repair_attempts`）。 */
    var repairAttempts: Int = 0
        private set

    private val locateFailures = HashMap<String, Int>()
    private val degradedKeys = HashSet<String>()

    /** 失败类别 → 已发生次数，供 [attemptsOf] 决定要不要「换路」还是「放弃」。 */
    private val failureTally = HashMap<FailureKind, Int>()

    // ──────────────────────────────────────────── 上下文窗口（供 ContextAssembler）

    private val traceWindow = ArrayList<StepRecord>(MAX_TRACE_WINDOW)
    private val recentRaw = ArrayDeque<Pair<Int, String>>(RECENT_RAW_CAP)

    /** 上一轮的 (候选编号 → stableKey)，用于 §5.6 重绘 WARN。 */
    private var previousKeys: Map<Int, String> = emptyMap()

    /** 待喂回模型的修正话术。喂一次即清空。 */
    private var repairHint: String? = null

    /** 本任务内被护栏拒绝过的 reasonKey 列表（喂给模型，避免它重试同一条死路）。 */
    private val denials = ArrayDeque<String>(MAX_DENIALS)

    var lastToolReturn: String? = null

    // ──────────────────────────────────────────── 读取

    fun elapsedMs(): Long = (clock.nowMillis() - startedAtMs) + usage.elapsedMsTotal

    fun nextStepId(): StepId = StepId.new()

    fun remainingBudgetPct(): Int = budget.remainingPct(usage)

    fun traceWindow(): List<StepRecord> = traceWindow

    fun recentRaw(): List<Pair<Int, String>> = recentRaw.toList()

    fun seenDigests(): List<String> = seenDigests.toList()

    fun pendingDenials(): List<String> = denials.toList()

    fun peekRepairHint(): String? = repairHint

    fun locateFailureCount(stableKey: String): Int = locateFailures[stableKey] ?: 0

    fun isDegraded(stableKey: String): Boolean = stableKey in degradedKeys

    fun attemptsOf(kind: FailureKind): Int = failureTally[kind] ?: 0

    fun lastActionKey(): String? = recentActionKeys.lastOrNull()

    // ──────────────────────────────────────────── 写入

    fun seedTrace(history: List<StepRecord>) {
        traceWindow.clear()
        traceWindow.addAll(history.takeLast(MAX_TRACE_WINDOW))
    }

    /** 感知成功。 */
    fun observePage(digest: String, url: String?, observationId: String?) {
        if (digest.isNotBlank() && seenDigests.lastOrNull() != digest) {
            seenDigests.addLast(digest)
            if (seenDigests.size > SEEN_DIGEST_CAP) seenDigests.removeFirst()
        }
        if (url != null && recentUrls.lastOrNull() != url) {
            recentUrls.addLast(url)
            if (recentUrls.size > URL_HISTORY_CAP) recentUrls.removeFirst()
        }
        lastUrl = url ?: lastUrl
        lastObservationId = observationId ?: lastObservationId
    }

    /**
     * §5.6 编号漂移 WARN：比对上一轮的 (id → stableKey) 与本轮候选。
     *
     * 「沿用旧编号」是 DOM 类 Agent 的第一大失败原因，必须显式告知模型 ——
     * 沉默地让它点错元素是修不好的，因为它会认为自己点的没错。
     */
    fun consumeRemapWarnings(current: List<UiElement>): List<String> {
        val prev = previousKeys
        previousKeys = current.associate { it.id to it.stableKey }
        if (prev.isEmpty()) return emptyList()
        val byKey = current.associateBy { it.stableKey }
        val out = ArrayList<String>(2)
        for ((oldId, key) in prev) {
            val now = byKey[key]
            when {
                now == null -> out += "上一步引用的 [$oldId] 已不存在（可能已被重绘或移除）。"
                now.id != oldId -> out += "页面已重绘：原 [$oldId] 现在是 [${now.id}]。请基于本轮 <candidates> 重新选择。"
            }
            if (out.size >= MAX_REMAP_WARNINGS) break
        }
        return out
    }

    fun setRepairHint(hint: String) {
        repairHint = hint
        repairAttempts += 1
    }

    fun clearRepairHint() {
        repairHint = null
    }

    fun noteDenial(reasonKey: String) {
        denials.addLast(reasonKey)
        if (denials.size > MAX_DENIALS) denials.removeFirst()
    }

    /** 感知或推理失败：算作一步 stall（失败本身也是「没进展」的一种）。 */
    fun noteStall() {
        stalledSteps += 1
        repairAttempts += 1
    }

    fun appendScratchpad(hint: String) {
        val merged = if (scratchpad.isBlank()) hint else "$scratchpad\n$hint"
        scratchpad = merged.take(MAX_SCRATCHPAD)
    }

    fun recordParseFailure() {
        consecutiveParseFailures += 1
        repairAttempts += 1
    }

    fun clearParseFailures() {
        consecutiveParseFailures = 0
        repairAttempts = 0
    }

    fun recordLocateFailure(stableKey: String) {
        locateFailures[stableKey] = (locateFailures[stableKey] ?: 0) + 1
    }

    fun markDegraded(stableKey: String) {
        degradedKeys += stableKey
    }

    fun tallyFailure(kind: FailureKind) {
        failureTally[kind] = (failureTally[kind] ?: 0) + 1
    }

    /**
     * 一步完成后的总登记。**所有预算累计都在这里发生，别在循环体里散着加** ——
     * 散着加的结果一定是「某条早退路径忘了加」，而那种 bug 表现为预算永远用不完。
     */
    fun commitStep(
        action: StepAction,
        result: ToolResult,
        prefillTokens: Int,
        decodeTokens: Int,
        kvCacheAfter: Int?,
        durationMs: Long,
        repairAttempts: Int,
        errorCode: String?,
        status: String,
        modelRawRedacted: String?,
        pageDigest: String?,
    ): StepRecord {
        val rec = StepRecord(
            id = StepId.new(),
            taskId = task.id,
            index = stepSeq,
            createdAtMillis = clock.nowMillis(),
            action = action,
            result = result,
            status = status,
            modelRawRedacted = modelRawRedacted,
            prefillTokens = prefillTokens,
            decodeTokens = decodeTokens,
            kvCacheTokensAfter = kvCacheAfter,
            pageDigest = pageDigest,
            observationId = lastObservationId,
            durationMs = durationMs,
            repairAttempts = repairAttempts,
            errorCode = errorCode,
        )

        usage = usage.copy(
            stepsDone = usage.stepsDone + 1,
            prefillTokensTotal = usage.prefillTokensTotal + prefillTokens,
            decodeTokensTotal = usage.decodeTokensTotal + decodeTokens,
            elapsedMsTotal = usage.elapsedMsTotal + durationMs,
        )
        stepSeq += 1
        lastKvCacheTokens = kvCacheAfter ?: lastKvCacheTokens

        if (traceWindow.size >= MAX_TRACE_WINDOW) traceWindow.removeAt(0)
        traceWindow += rec

        recentActionKeys.addLast(actionKeyOf(action))
        if (recentActionKeys.size > ACTION_KEY_CAP) recentActionKeys.removeFirst()

        // 无进展计数：只有「成功但页面没动」才持续累加；失败步由 noteStall 单独记
        stalledSteps = if (result.isOk && result is ToolResult.Noop) stalledSteps + 1 else 0

        recentRaw.addLast(rec.index to (modelRawRedacted ?: action.compactArgs()))
        if (recentRaw.size > RECENT_RAW_CAP) recentRaw.removeFirst()

        repairHint = null
        return rec
    }

    // ──────────────────────────────────────────── §3.5 循环检测谓词

    /** ⑦ 完全重复：同一 actionKey 连续 [times] 次。 */
    fun repeatsIdenticalAction(times: Int = IDENTICAL_REPEAT_LIMIT): Boolean {
        if (recentActionKeys.size < times) return false
        val it = recentActionKeys.toList()
        val last = it.last()
        return it.takeLast(times).all { k -> k == last }
    }

    /** ⑧ 无进展。 */
    fun isStalled(limit: Int = NO_PROGRESS_LIMIT): Boolean = stalledSteps >= limit

    /** ⑨ 振荡：URL 历史里出现长度 ≥ [minCycle] 的环（A→B→A）。 */
    fun hasUrlCycle(minCycle: Int = MIN_URL_CYCLE): Boolean {
        val u = recentUrls.toList()
        if (u.size < minCycle) return false
        val last = u.last()
        for (i in u.size - 2 downTo 0) {
            if (u[i] == last && (u.size - i) >= minCycle) return true
        }
        return false
    }

    // ──────────────────────────────────────────── 断点

    fun snapshot(statusSnapshot: String, humanGatePending: Boolean, humanGateJson: String?): LoopCheckpoint =
        LoopCheckpoint(
            taskId = task.id,
            updatedAt = clock.nowMillis(),
            statusSnapshot = statusSnapshot,
            currentStepSeq = stepSeq,
            // ★ 累计终值，不是增量
            prefillTokensTotal = usage.prefillTokensTotal,
            decodeTokensTotal = usage.decodeTokensTotal,
            elapsedMsTotal = usage.elapsedMsTotal,
            seenDigests = seenDigests(),
            scratchpad = scratchpad,
            lastUrl = lastUrl,
            lastObservationId = lastObservationId,
            lastKvCacheTokens = lastKvCacheTokens,
            compactionLastStep = null,
            humanGatePending = humanGatePending,
            humanGateJson = humanGateJson,
        )

    companion object {

        const val SEEN_DIGEST_CAP: Int = 8
        const val ACTION_KEY_CAP: Int = 8
        const val URL_HISTORY_CAP: Int = 8
        const val MAX_TRACE_WINDOW: Int = 64
        const val RECENT_RAW_CAP: Int = 3
        const val MAX_DENIALS: Int = 4
        const val MAX_REMAP_WARNINGS: Int = 3
        const val MAX_SCRATCHPAD: Int = 1_024

        /** §3.5：连续 3 次完全重复 → 立即熔断，不给第四次机会。 */
        const val IDENTICAL_REPEAT_LIMIT: Int = 3

        /** §3.5：连续 4 步无进展。 */
        const val NO_PROGRESS_LIMIT: Int = 4

        /** §3.5：URL 环长度 ≥ 3（A→B→A）。 */
        const val MIN_URL_CYCLE: Int = 3

        /** §3.5：同一元素定位失败 2 次 → 强制降级到 extract。 */
        const val LOCATE_FAIL_LIMIT: Int = 2

        /** §3.5：连续 2 次输出不可解析 → 熔断。 */
        const val PARSE_FAIL_LIMIT: Int = 2

        /** 从断点恢复。预算累计原样带回（见类注释）。 */
        fun restore(task: TaskSpec, budget: LoopBudget, clock: Clock, cp: LoopCheckpoint): LoopState =
            LoopState(
                task = task,
                budget = budget,
                clock = clock,
                startStepSeq = cp.currentStepSeq,
                startUsage = UsageTotals(
                    stepsDone = cp.currentStepSeq,
                    prefillTokensTotal = cp.prefillTokensTotal,
                    decodeTokensTotal = cp.decodeTokensTotal,
                    elapsedMsTotal = cp.elapsedMsTotal,
                ),
                startScratchpad = cp.scratchpad.orEmpty(),
                startSeenDigests = cp.seenDigests,
                startUrl = cp.lastUrl,
                startObservationId = cp.lastObservationId,
                startKvCache = cp.lastKvCacheTokens,
                startedAtMs = clock.nowMillis(),
            )

        /**
         * `actionKey` = 工具名 + 归一化参数 + 目标（§3.5 原文，也是数据库 `step.action_key` 列的口径）。
         *
         * 归一化很关键：不归一化的话 `scroll(down, 800)` 与 `scroll(down, 812)` 会被当成两个不同动作，
         * 而它们在语义上是同一个 —— 循环检测会漏掉真实存在的死循环。
         */
        fun actionKeyOf(action: StepAction): String = when (action) {
            is StepAction.Click -> "click"
            is StepAction.Type -> "type:text"
            is StepAction.Select -> "select:${action.value.lowercase()}"
            is StepAction.Scroll -> "scroll:${action.dir}"
            is StepAction.Navigate -> "goto:${action.url.substringBefore('?').lowercase()}"
            is StepAction.Extract -> "extract:${action.selectorHint.lowercase()}"
            is StepAction.CallTool -> "tool:${action.toolId.value}"
            is StepAction.AskHuman -> "ask"
            is StepAction.Finish -> "finish"
        }
    }
}

/** 任务产出。 */
data class TaskOutcome(
    val taskId: TaskId,
    val reason: TerminalReason,
    val answer: String?,
    val evidenceRefs: List<String>,
    val stepsDone: Int,
    val prefillTokensTotal: Long,
    val decodeTokensTotal: Long,
    val elapsedMsTotal: Long,
    /** 通过 `local.export_records` 导出的表格文本（md/csv/json）。 */
    val exported: String? = null,
) {
    val isSuccess: Boolean get() = reason.isSuccess
}
