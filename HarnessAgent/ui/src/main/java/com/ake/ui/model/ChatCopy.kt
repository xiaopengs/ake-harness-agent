package com.ake.ui.model

import androidx.annotation.StringRes
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.MessageStatus
import com.ake.ui.R

/**
 * `ChatTerminalReason` → 人话。
 *
 * ## 为什么另立一张表，而不复用 `TerminalCopy`（任务那条十二终态表）
 *
 * 两套终态枚举是**刻意分开**的（`core/ports/Chat.kt` 头部已裁定）：任务的十二终态里
 * 有 `FABRICATED_ANSWER` / `OSCILLATION` 这类"跑完了但结果不可信"的判定，
 * 而一次聊天轮次没有"结果可信度"这个概念 —— 它要么答完了，要么被拦了，要么模型没加载。
 * 硬套一张表会让聊天页出现"结果不可信"这种对闲聊毫无意义的话。
 *
 * ## 为什么用 `when` 穷尽而不查表
 *
 * `ChatTerminalReason` 是封闭枚举，`when` 无 `else` 分支 ⇒ 同事新增第 8 个终态时
 * **本文件编译失败**，而不是静默落到"未知原因"。聊天页最不能忍的就是
 * "出错了"三个字 —— 用户既不知道发生了什么，也不知道下一步做什么。
 */
public data class ChatTerminalCopy(
    /** 一句话结论。 */
    @param:StringRes val titleRes: Int,
    /** 用户下一步能做什么。**必须给**，不给建议的失败态等于把问题推回给用户。 */
    @param:StringRes val nextRes: Int,
    /** 是否值得让用户点「重试」。 */
    val retryable: Boolean,
)

public fun chatTerminalCopyOf(reason: ChatTerminalReason): ChatTerminalCopy = when (reason) {
    ChatTerminalReason.COMPLETED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_completed_title,
        nextRes = R.string.chat_term_completed_next,
        retryable = false,
    )

    ChatTerminalReason.USER_ABORT -> ChatTerminalCopy(
        titleRes = R.string.chat_term_user_abort_title,
        nextRes = R.string.chat_term_user_abort_next,
        retryable = false,
    )

    ChatTerminalReason.MODEL_FAILED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_model_failed_title,
        nextRes = R.string.chat_term_model_failed_next,
        retryable = true,
    )

    ChatTerminalReason.BUDGET_EXHAUSTED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_budget_exhausted_title,
        nextRes = R.string.chat_term_budget_exhausted_next,
        // 预算耗尽是"这一轮太长"，同样的话再问一遍只会再撞一次墙 ⇒ 不给重试。
        retryable = false,
    )

    ChatTerminalReason.POLICY_DENIED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_policy_denied_title,
        nextRes = R.string.chat_term_policy_denied_next,
        retryable = false,
    )

    ChatTerminalReason.SKILL_FAILED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_skill_failed_title,
        nextRes = R.string.chat_term_skill_failed_next,
        retryable = true,
    )

    ChatTerminalReason.MODEL_NOT_LOADED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_model_not_loaded_title,
        nextRes = R.string.chat_term_model_not_loaded_next,
        retryable = true,
    )
}

/**
 * 消息状态徽标文案。
 *
 * `COMPLETE` 返回 `null`：一条正常说完的消息不需要在气泡上贴一个「已完成」——
 * 那是给人看的噪音，而不是信息。
 */
@StringRes
public fun messageStatusLabelRes(status: MessageStatus): Int? = when (status) {
    MessageStatus.STREAMING -> R.string.chat_message_streaming
    MessageStatus.ABORTED -> R.string.chat_message_aborted
    MessageStatus.FAILED -> R.string.chat_message_failed
    MessageStatus.COMPLETE -> null
}
