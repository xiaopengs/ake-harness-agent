package com.ake.agent.loop

import com.ake.core.common.AkeLog
import com.ake.core.contract.PageState
import com.ake.core.contract.PocketDom
import com.ake.core.contract.StepAction
import com.ake.core.contract.SuccessCriteria
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.ToolResult
import com.ake.core.ports.BatteryState
import com.ake.core.ports.ThermalState
import javax.inject.Inject

/**
 * 终态判定器（架构 §3.5）。**三重判定，模型的意见只占一票。**
 *
 * 三个 Layer 的分工：
 *  - **Layer 0** [checkTermination]：预算 / 循环 / 健康 / 用户中止。**每步开头先跑**，
 *    先查熔断再动，别在无药可救时还付一次推理的钱。
 *  - **Layer 1** 模型显式 `finish`：见 [AnswerGrounding]，answer 必须能溯源。
 *  - **Layer 2** [judge]：结构性谓词（criteria）。模型说「做完了」必须同时过这一关。
 *
 * 全部是**纯代码、不调模型**（§3.3 七环表：只有第 3 环调一次模型）。
 * 这不是保守，是可调试性的前提 —— 一个 10 步失败的任务，要能精确指出是第 4 步 DOM 裁剪丢了元素，
 * 还是第 6 步护栏误拒。**端侧 Agent 项目死于无法定位失败原因的比例，远高于死于单点能力不足。**
 */
interface Verifier {

    /** @return 非 null 即应立即终止（或挂起，见 [SuspendReason]）。 */
    fun checkTermination(
        loop: LoopState,
        aborted: Boolean,
        thermal: ThermalState,
        battery: BatteryState,
    ): Termination?

    /** 每步执行完的推进判定。 */
    fun judge(
        task: TaskSpec,
        loop: LoopState,
        last: ToolResult,
        dom: PocketDom?,
        action: StepAction,
    ): Judge
}

sealed interface Termination {
    /** 十二终态之一。 */
    data class Terminal(val reason: TerminalReason) : Termination

    /** 可恢复挂起：热节流 / 低电量。落 PAUSED，不等于任务结束。 */
    data class Suspend(val reason: SuspendReason) : Termination
}

sealed interface Judge {
    data class Continue(val hint: String? = null) : Judge
    data class Done(val answer: String, val evidenceRefs: List<String>) : Judge
    data class Impossible(val reason: TerminalReason, val detail: String) : Judge
}

/**
 * 默认实现。刻意做成无状态 —— 所有判据来自 [LoopState]，
 * 于是「同一份 LoopState 一定得到同一个 Judge」这条性质可以在 JVM 上毫秒级回归。
 */
class DefaultVerifier(
    private val grounding: AnswerGrounding = AnswerGrounding(),
) : Verifier {

    /**
     * Hilt 入口（`AgentModule.bindVerifier` 绑的是本类，不是 `Verifier` 的默认实现）。
     *
     * ★ 刻意做成**次级**构造函数，而不是把 `@Inject` 直接打在主键上：
     * Dagger 只看**形参列表**，看不见 Kotlin 的默认实参 —— 打在主键上会要求图上再有一条
     * `AnswerGrounding` 绑定，而它自己的 `minRatio` 也有默认值，会连环扩散出一串
     * 只为「填一个没人想改的默认值」而存在的绑定。次级构造函数把「默认 grounding」
     * 这一事实固定在类内部，图里不引入任何新类型，也不引入回边（本类无状态，无循环依赖风险）。
     *
     * 主键保留带参形态：阈值调优 / 回归测试要注入自定义 `minRatio` 时不必改装配。
     */
    @Inject
    constructor() : this(AnswerGrounding())

    override fun checkTermination(
        loop: LoopState,
        aborted: Boolean,
        thermal: ThermalState,
        battery: BatteryState,
    ): Termination? {
        // ④ 用户中止：无条件优先（§8.4「用户按停止时，不该出现先问一圈再停的中间态」）
        if (aborted) return Termination.Terminal(TerminalReason.USER_ABORT)

        // ⑫ / 挂起：电量。低于 8% 且未充电 → 落盘停止；15% 以下未充电 → 暂停
        //
        // ★★ `levelPct >= 0` 这道守卫是 2026-10 补单测时抓到的真实缺陷。
        //    `BatteryState.unknown()` 的 `levelPct` 是 **-1**（契约里写明「未知为 -1」），
        //    而 -1 同时满足 `in 0..8` 为假、`< 15` 为真 ⇒ **「没读到电量」被当成了「快没电了」**。
        //
        //    触发路径是真实存在的：主循环在 `supervisorScope` 里用 `launchIn` 订阅
        //    `health.battery()`，而 `launchIn` 是**异步**的 ——
        //    在首个 `checkTermination` 之前它不保证已经 emit 过。
        //    于是「刚点开始、任务立刻因为低电量暂停」这个故障，取决于一次调度时序。
        //
        //    失败方向必须选对：**读不到电量时不该动**，下一次判定还有机会读到；
        //    而误挂起一次会让用户看到「刚开始就暂停了」，那是最难解释的一类故障。
        if (battery.levelPct >= 0 && !battery.isCharging) {
            if (battery.levelPct in 0..BATTERY_STOP_PCT) {
                return Termination.Terminal(TerminalReason.BATTERY_STOP)
            }
            if (battery.levelPct < BATTERY_PAUSE_PCT) {
                return Termination.Suspend(SuspendReason.BATTERY_LOW)
            }
        }

        // 挂起：热节流（可恢复，不是终态）
        if (thermal >= ThermalState.SEVERE) {
            return Termination.Suspend(SuspendReason.THERMAL)
        }

        val b = loop.budget
        val u = loop.usage

        // ⑤ 步数 / 预算上限
        if (u.stepsDone >= b.maxSteps) return Termination.Terminal(TerminalReason.BUDGET_EXHAUSTED)
        if (u.prefillTokensTotal >= b.maxPrefillTokensTotal) return Termination.Terminal(TerminalReason.BUDGET_EXHAUSTED)
        if (u.decodeTokensTotal >= b.maxDecodeTokensTotal) return Termination.Terminal(TerminalReason.BUDGET_EXHAUSTED)

        // ⑥ 墙钟
        if (loop.elapsedMs() >= b.maxWallClockMs) return Termination.Terminal(TerminalReason.TIMEOUT)

        // ⑦~⑨ 循环三件套：先查熔断再动，别付下一次推理的钱
        if (loop.repeatsIdenticalAction()) return Termination.Terminal(TerminalReason.LOOP_DETECTED)
        if (loop.hasUrlCycle()) return Termination.Terminal(TerminalReason.OSCILLATION)
        if (loop.isStalled()) return Termination.Terminal(TerminalReason.NO_PROGRESS)

        // ⑪ 解析失败熔断（连续 2 次）
        if (loop.consecutiveParseFailures >= LoopState.PARSE_FAIL_LIMIT) {
            return Termination.Terminal(TerminalReason.REPAIR_FAILED)
        }
        return null
    }

    override fun judge(
        task: TaskSpec,
        loop: LoopState,
        last: ToolResult,
        dom: PocketDom?,
        action: StepAction,
    ): Judge {
        // ── ② 页面不可继续：不绕过风控是宪章第 6 条，识别到就明确声明做不了
        val state = dom?.pageState
        if (state == PageState.BLOCKED || state == PageState.CAPTCHA || state == PageState.LOGIN_WALL) {
            return Judge.Impossible(TerminalReason.INFEASIBLE, "page_state=${state.name}")
        }

        if (action !is StepAction.Finish) return Judge.Continue()

        // ── ① / ③ Layer 1 + Layer 2：模型说完成了，两关都要过
        val criteria = task.successCriteria
        val evidence = Evidence.from(dom)
        val missing = criteria.unmetBy(evidence)

        if (missing.isNotEmpty()) {
            // 「你认为已完成，但 X 未满足」—— 这条反馈回路是修 Agent 幻觉最便宜的手段
            return Judge.Continue(hint = "你认为已完成，但下列条件尚未满足：${missing.joinToString("；")}。请继续执行。")
        }

        val gr = grounding.score(action.answer, evidence)
        if (!gr.acceptable) {
            AkeLog.w(TAG, "finish answer not grounded: ratio=${gr.hitRatio} missing=${gr.missingSamples}")
            return Judge.Impossible(
                TerminalReason.FABRICATED_ANSWER,
                "answer_grounding_ratio=${gr.hitRatio} below $GROUNDING_MIN_RATIO",
            )
        }
        return Judge.Done(action.answer, action.evidenceRefs)
    }

    private companion object {
        const val TAG = "Verifier"
        const val BATTERY_STOP_PCT: Int = 8
        const val BATTERY_PAUSE_PCT: Int = 15
        const val GROUNDING_MIN_RATIO: Double = 0.60
    }
}

/**
 * 当轮 observation 里可被引用的证据集。
 *
 * 刻意只收集**当轮** DOM（不是全历史）：`finish` 的契约是「answer 只能由本轮 observation
 * 里真实出现的内容拼出」（planner/SystemCharter 的 ACTION SCHEMA 注释）。
 * 放宽到全历史会让模型学会在第 3 步就宣布完成 —— 它记得第 1 步看到的价格。
 */
data class Evidence(
    val candidateTexts: List<String>,
    val recordCells: List<String>,
    val signals: Set<String>,
    val recordRows: Int,
) {
    fun allText(): Sequence<String> = (candidateTexts.asSequence() + recordCells.asSequence())

    companion object {
        fun from(dom: PocketDom?): Evidence {
            if (dom == null) return EMPTY
            val cells = dom.records?.rows.orEmpty()
                .flatMap { row -> dom.records?.columns.orEmpty().mapNotNull { col -> row[col] } }
            return Evidence(
                candidateTexts = dom.candidates.map { it.text },
                recordCells = cells,
                signals = dom.signals.mapTo(mutableSetOf()) { it.name },
                recordRows = dom.records?.rows?.size ?: 0,
            )
        }

        val EMPTY = Evidence(emptyList(), emptyList(), emptySet(), 0)
    }
}

/** 扩展而不是改 `core/contract` 的 `SuccessCriteria`（不越界）。 */
private fun SuccessCriteria.unmetBy(ev: Evidence): List<String> {
    val out = ArrayList<String>(3)
    if (minRecords > 0 && ev.recordRows < minRecords) {
        out += "需要至少 $minRecords 条记录，当前只有 ${ev.recordRows} 条（请先用 extract 拿到结构化行）"
    }
    val missingFields = requiredFields.filter { f -> ev.recordCells.none { it.contains(f, ignoreCase = true) } }
    if (requiredFields.isNotEmpty() && missingFields.isNotEmpty()) {
        out += "缺少字段 ${missingFields.joinToString(",")}"
    }
    val missingSignals = requireDomSignal.map { it.name }.filter { s -> s !in ev.signals }
    if (missingSignals.isNotEmpty()) {
        out += "页面缺少信号 ${missingSignals.joinToString(",")}"
    }
    return out
}

/**
 * answer 溯源校验（架构 §3.5 Layer 1）。
 *
 * **这是端侧小型模型最典型的失败模式** —— 它非常愿意编一个看起来很像那么回事的答案。
 * 必须制度化地防。
 *
 * 做法：从 answer 里抽出「数据片段」（数字、引号串、≥4 字的连续中文），
 * 逐条到当轮证据里找；命中率低于 [MIN_RATIO] 判为编造。
 * 不做语义相似度 —— 端侧跑不起，而且语义相似度恰恰会让「看起来很像」的编造通过。
 */
class AnswerGrounding(private val minRatio: Double = 0.60) {

    /** 阈值随结果一起带出去，调用方不必回查 grounding 实例（便于单测断言）。 */
    data class Result(
        val hitRatio: Double,
        val total: Int,
        val hits: Int,
        val missingSamples: List<String>,
        val minRatio: Double = 0.60,
    ) {
        /** 无可抽取片段时视为通过：纯叙述性回答不该被判成编造。 */
        val acceptable: Boolean get() = total == 0 || hitRatio >= minRatio
    }

    fun score(answer: String, ev: Evidence): Result {
        val samples = extractDataFragments(answer)
        if (samples.isEmpty()) return Result(1.0, 0, 0, emptyList(), minRatio)

        val haystack = ev.allText().joinToString("\n")
        val missing = ArrayList<String>(2)
        var hits = 0
        for (s in samples) {
            if (haystack.contains(s, ignoreCase = true)) hits++ else if (missing.size < 3) missing += s
        }
        return Result(hits.toDouble() / samples.size, samples.size, hits, missing, minRatio)
    }

    /**
     * 抽数据片段。刻意**不抽整句**：整句里混着连接词，会在长答案上把命中率拉到 0，
     * 于是「如实回答」也会被判成编造 —— 那是比漏判更糟的失败方向（用户会被反复卡住）。
     */
    internal fun extractDataFragments(answer: String): List<String> {
        val out = LinkedHashSet<String>()
        NUMERIC.findAll(answer).forEach { out += it.value.trim() }
        QUOTED.findAll(answer).forEach { out += it.groupValues[1].trim() }
        CJK.findAll(answer).forEach { out += it.value.trim() }
        return out.filter { it.length >= MIN_FRAGMENT }.distinct().take(MAX_SAMPLES)
    }

    private companion object {
        const val MIN_FRAGMENT: Int = 2
        const val MAX_SAMPLES: Int = 12
        val NUMERIC = Regex("""\d+(?:\.\d+)?(?:元|块|件|条|个|%)?""")
        val QUOTED = Regex("""["“”「」『』]([^"“”「」『』]{2,24})["“”「」『』]""")
        val CJK = Regex("""[\u4e00-\u9fa5A-Za-z0-9]{4,16}""")
    }
}
