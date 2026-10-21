package com.ake.agent.loop

import com.ake.core.common.Severity
import com.ake.core.contract.ErrorCode
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolResult

/**
 * 失败分类与重试策略（架构 §8.6 + §3.5「定位伪失败」）。
 *
 * ## 分类的目的不是写日志好看，是决定「下一步让模型干什么」
 *
 * 端侧模型最典型的行为缺陷是**原地重试同一个错误**。原因不是它笨，是它拿到的反馈是
 * 「操作失败」这种没有信息量的句子。所以本文件的每条话术都强制带三样东西：
 *  ① **哪一条约束没满足**（小模型不会自己定位）
 *  ② **可替代的正确做法**（否则它会重试同一个动作）
 *  ③ **当前可用信息**（如候选编号范围、元素支持的动作列表）
 *
 * ## 重试策略为什么是「换路」而不是「重来」
 *
 * 网页不是幂等环境：同一个 click 点两次可能已经触发了别的状态变化。
 * 所以 `RETRY_SAME` 只保留给**超时**这一类「可能根本没发生」的失败；
 * 其余一律 `CHANGE_APPROACH` 或直接 `GIVE_UP`。
 */
enum class RecoveryAction {
    /** 原样重试一次（仅超时类）。 */
    RETRY_SAME,

    /** 换一条路：换元素 / 换工具 / 降级到 extract。 */
    CHANGE_APPROACH,

    /** 强制降级到结构化抽取（§3.5 定位伪失败的既定处方）。 */
    FORCE_DEGRADE,

    /** 转人工。 */
    ASK_HUMAN,

    /** 不再尝试，交给终止保护。 */
    GIVE_UP,
}

/** 失败类别。它是「给模型的反馈话术」与「重试预算」共同的 key。 */
enum class FailureKind(val recovery: RecoveryAction, val severity: Severity) {
    /** 元素失效 / 编号漂移（§5.6 / R3 —— DOM 类 Agent 的头号失败原因）。 */
    STALE(RecoveryAction.CHANGE_APPROACH, Severity.LOW),

    /** 目标不存在：模型引用了 [99] 这类幻觉编号（T4）。 */
    NOT_FOUND(RecoveryAction.CHANGE_APPROACH, Severity.LOW),

    /** 超时：动作可能根本没发生，是唯一允许原样重试的一类。 */
    TIMEOUT(RecoveryAction.RETRY_SAME, Severity.LOW),

    /** 被护栏拒绝（闸 4）。不能重试 —— 重试只会再被拒一次并多写一条审计。 */
    DENIED(RecoveryAction.ASK_HUMAN, Severity.HIGH),

    /** 用户中止 / 协程取消。 */
    INTERRUPTED(RecoveryAction.GIVE_UP, Severity.LOW),

    /** 页面处于不可操作状态（BLOCKED / CAPTCHA / 未 settle）。 */
    BLOCKED(RecoveryAction.ASK_HUMAN, Severity.MEDIUM),

    /** 参数不合法（闸 5）。 */
    BAD_ARGS(RecoveryAction.CHANGE_APPROACH, Severity.LOW),

    /** 工具被禁用（§7.2 逐工具授权）。 */
    DISABLED(RecoveryAction.CHANGE_APPROACH, Severity.MEDIUM),

    /** 设备 IO 失败。 */
    IO(RecoveryAction.RETRY_SAME, Severity.MEDIUM),

    /** 未归类。默认换路，不给原样重试 —— 未知错误原样重试是在赌运气。 */
    UNKNOWN(RecoveryAction.CHANGE_APPROACH, Severity.MEDIUM),
    ;

    companion object {
        fun of(code: ErrorCode): FailureKind = when (code) {
            ErrorCode.STALE -> STALE
            ErrorCode.NOT_FOUND -> NOT_FOUND
            ErrorCode.TIMEOUT -> TIMEOUT
            ErrorCode.DENIED -> DENIED
            ErrorCode.INTERRUPTED -> INTERRUPTED
            ErrorCode.BLOCKED -> BLOCKED
            ErrorCode.BAD_ARGS -> BAD_ARGS
            ErrorCode.DISABLED -> DISABLED
            ErrorCode.IO -> IO
            ErrorCode.UNSUPPORTED, ErrorCode.UNKNOWN -> UNKNOWN
        }

        fun of(result: ToolResult): FailureKind = when (result) {
            is ToolResult.Error -> of(result.code)
            else -> UNKNOWN
        }
    }
}

/**
 * 给模型的反馈话术生成器。
 *
 * 所有输出都会被塞进 RECENT_TRACE 段（256–1024 token），**必须短**（≤ ~60 字符/条）。
 * 超过这个长度，连续三四个失败就会把预算吃光 —— 那时上下文降级会先于模型把错误修好。
 */
object RecoveryAdvice {

    /** 每类允许的连续同类型失败次数。超过就升级为 [RecoveryAction.GIVE_UP]。 */
    private val BUDGET: Map<FailureKind, Int> = mapOf(
        FailureKind.TIMEOUT to 2,
        FailureKind.IO to 2,
        FailureKind.STALE to 3,
        FailureKind.NOT_FOUND to 3,
        FailureKind.BAD_ARGS to 2,
        FailureKind.DENIED to 0,      // 被拒就是被拒，不重试
        FailureKind.BLOCKED to 1,
        FailureKind.INTERRUPTED to 0,
        FailureKind.DISABLED to 1,
        FailureKind.UNKNOWN to 2,
    )

    fun maxAttempts(kind: FailureKind): Int = BUDGET[kind] ?: 1

    /**
     * @param attempt 该类失败已发生的次数（0-based）
     * @param validIds 本轮候选编号范围，来自当轮 PocketDom
     */
    fun toModel(
        kind: FailureKind,
        detail: String?,
        attempt: Int,
        validIds: IntRange?,
        supported: List<String>,
    ): String {
        val ids = validIds?.let { "可用编号 ${it.first}..${it.last}。" } ?: "本轮未取到候选列表，请先 scroll 后重新观察。"
        val alt = if (supported.isNotEmpty()) "该元素支持：${supported.joinToString("/")}。" else ""
        val tail = if (attempt >= 1) " 已失败 ${attempt + 1} 次，请换一种做法。" else ""
        val head = when (kind) {
            FailureKind.STALE ->
                "目标元素已失效（页面重绘导致编号漂移）。请基于本轮 <candidates> 重新选择，不要用上一步的编号。$ids"
            FailureKind.NOT_FOUND ->
                "你引用的元素不存在：$detail。$ids 若目标不在列表里，先 scroll 再观察。"
            FailureKind.TIMEOUT ->
                "上一步执行超时，动作可能未生效。请先重新观察页面确认当前状态，再决定是重试还是换动作。"
            FailureKind.DENIED ->
                "该操作被安全策略拒绝，重试也不会通过。请改用其它动作完成任务，或用 ask 向用户说明需要人工操作。"
            FailureKind.BLOCKED ->
                "页面当前不可操作（可能是未加载完成、有弹窗、或遇到人机校验）。不得尝试绕过。请等待或调用 ask。"
            FailureKind.BAD_ARGS ->
                "参数不合法：$detail。请按 ACTION SCHEMA 补齐字段后重新输出。"
            FailureKind.DISABLED ->
                "该工具当前处于禁用状态。请改用列表中其它已启用工具。"
            FailureKind.IO ->
                "设备 IO 失败（可能是存储或网络问题）。请重试一次；持续失败则用 finish 说明当前进度。"
            FailureKind.INTERRUPTED ->
                "操作已被中止。"
            FailureKind.UNKNOWN ->
                "上一步失败：$detail。请换一种做法，不要重复同一个动作。"
        }
        return head + alt + tail
    }

    /**
     * §3.5「定位伪失败」的既定处方：同一元素失败 2 次 → 强制降级到 `extract`。
     *
     * 为什么是 extract 而不是「再点一次」：定位失败说明我们对该元素的**引用方式**不可靠，
     * 而 extract 走的是「按结构模式批量取」的路子，不依赖单个元素编号，天然更稳。
     */
    fun degradeHint(action: StepAction): String = when (action) {
        is StepAction.Click, is StepAction.Type, is StepAction.Select ->
            "此元素已连续定位失败。请改用 extract（按选择器模式批量抽取）拿数据，或用 ask 转人工，不要再点它。"
        else -> "连续定位失败，请换一种方式达成目标。"
    }
}
