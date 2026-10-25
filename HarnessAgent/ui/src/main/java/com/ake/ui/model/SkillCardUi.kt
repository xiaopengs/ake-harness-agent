package com.ake.ui.model

import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.MessageStatus

/**
 * 一张技能卡片的可渲染状态。
 *
 * ## 为什么要和 `ChatMessageView` 分开
 *
 * 落库的 `SKILL_CALL` 消息只有一行摘要，而用户在流式过程中真正要看的是
 * **「现在跑到第几步」** —— 那批 `SkillProgress` 事件按设计**不落库**
 * （`Chat.kt` 里写明"刻意不是每一步一条，否则 UI 会被刷爆"）。
 * 所以卡片状态 = 落库摘要（基线）+ 内存里的实时进度（覆盖层），
 * 两者按 `callId` 配对（§9-U5）。
 *
 * `callId` 是配对键而不是消息 id：`SKILL_CALL` 与 `SKILL_RESULT` 是两条消息，
 * 它们共享同一个 `callId`。用消息 id 配对会让"结果卡片"永远找不到"调用卡片"的进度。
 */
public data class SkillCardUi(
    val callId: String,
    val name: String,
    val ref: CapabilityRef?,
    /** 已跑完的步数（来自 `SkillProgress.stepsDone`）。 */
    val stepsDone: Int? = null,
    /** 步数上限（来自 `BudgetEnvelope.maxSteps` 的投影）。 */
    val maxSteps: Int? = null,
    /** 当前这一步在做什么，已脱敏。 */
    val detail: String? = null,
    /** 调用参数摘要（已脱敏）。 */
    val args: String? = null,
    /** 结果摘要（已脱敏）。 */
    val result: String? = null,
    /** `NONE` / `FLAGGED` / `BLOCKED`；非 NONE 必须挂警示。 */
    val escalation: String? = null,
    val status: MessageStatus = MessageStatus.STREAMING,
    /** 派生出来的任务 id，点开可以跳任务页看完整步骤流。 */
    val taskId: String? = null,
)

/**
 * 一轮失败的现场。
 *
 * 挂在**消息**上而不是只挂在页面顶部：用户看到的是"这一条气泡没说完"，
 * 原因贴在气泡里才对应得上；只放顶部横幅的话，往回翻历史时那些失败气泡
 * 就变成了没有理由的残句。
 *
 * @property turnId 只对这一轮的消息生效（一个会话里可以有多轮失败）
 */
public data class ChatTurnFailure(
    val turnId: String?,
    val reason: ChatTerminalReason,
    val retryable: Boolean,
) {
    public companion object {
        /** 只有「没说完」才值得占用气泡的一条位置；`COMPLETED` / `USER_ABORT` 不算失败。 */
        public fun of(turnId: String?, reason: ChatTerminalReason): ChatTurnFailure? = when (reason) {
            ChatTerminalReason.COMPLETED, ChatTerminalReason.USER_ABORT -> null
            else -> ChatTurnFailure(turnId, reason, chatTerminalCopyOf(reason).retryable)
        }
    }
}
