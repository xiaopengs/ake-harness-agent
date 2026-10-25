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
 * `ChatTerminalReason` 是封闭枚举，`when` 无 `else` 分支 ⇒ 同事新增第 9 个终态时
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

    /**
     * ★ **模型答完了，但没存下来**（`core:ports` 的 `ChatTerminalReason.STORE_FAILED`）。
     *
     * ## 为什么这一档绝不能复用 `chat_term_model_failed_*`
     *
     * 那两句文案把用户引向「换模型 / 清缓存 / 重下权重」，而这一档的真实成因是
     * 磁盘满、迁移失败、事务被打回 —— 用户按「模型坏了」去处理，做完全部动作
     * 之后重试仍然失败，然后合理地认为这个 App 坏了。**归因错位的文案比没有文案更糟**：
     * 它消耗掉用户对「重试」这个动作的信任。
     *
     * 所以这里的三句话各有分工：标题说「回答是好的」（保住用户已经看到的内容的价值），
     * 正文把责任指到存储（腾空间才是对症的），并且明说重试会重新生成一遍
     * （本枚举不携带文本，模型产出只活在那一轮的内存里 —— 见 `Chat.kt` 该值的注释）。
     *
     * ## 为什么 `retryable = true`
     *
     * 存储抖动是**瞬时**失败里最典型的一类，把整轮重发一次是有效的；
     * 而 `clientRequestId` 保证 USER 消息不会重复落盘。
     */
    ChatTerminalReason.STORE_FAILED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_store_failed_title,
        nextRes = R.string.chat_term_store_failed_next,
        retryable = true,
    )

    /**
     * ★ **这句话没能写进存储，所以本轮根本没有开始回答**（一个 token 都没生成）。
     *
     * ## 为什么不复用 `STORE_FAILED` 那两句（这是本次拆分存在的**全部理由**）
     *
     * 两档的共同点只有「存储坏了」，时间线完全不同，而用户读文案时只会代入自己
     * 看到的那一幕：
     *
     * | 终态 | 屏幕上有什么 | 用户读完会去做什么 |
     * |---|---|---|
     * | `STORE_FAILED` | **一段完整的回答**（已经流出来了） | 「答都答了，别浪费」→ 重试把它存下来 |
     * | `REQUEST_STORE_FAILED` | 一句自己发出去的话，**后面什么都没有** | 同上 —— 然后去找一个不存在的回答 |
     *
     * 上一版 ①②（`append(USER)` / `beginAssistant`）的失败也报 `STORE_FAILED`，
     * 于是「模型一个字都没答」被说成「回答生成好了，但没存下来」。这不是措辞瑕疵：
     * 用户会反复滚动、反复重发同一个问题、甚至去折腾模型设置，只因为界面承诺了一段
     * 从未存在过的回答。**归因越界比多一个枚举贵得多**（见 `core:ports/Chat.kt` 该值的注释）。
     *
     * 所以这一档的三句话各有分工：标题把「没答」说在前面（先止损，别让人找），
     * 正文把责任指到存储（对症），并**主动堵掉「补回来」这个念头**
     * —— 本枚举不携带文本，也没有任何缓存持有这一轮的产物。
     *
     * ## 为什么 `retryable = true`
     *
     * 可重试的是「**把这句话再发一次**」，不是「补写一次」。`:ui` 的 `retry()`
     * 正是这么做的：复用 `lastUserText` 与 `turnId`（§9-U4），不新开一轮；
     * 而 `clientRequestId` 每次新建 —— 万一上一轮 USER 行其实已经落盘
     * （`beginAssistant` 失败那一路），幂等键会挡住重复消息，不会出现两条一样的话。
     */
    ChatTerminalReason.REQUEST_STORE_FAILED -> ChatTerminalCopy(
        titleRes = R.string.chat_term_request_store_failed_title,
        nextRes = R.string.chat_term_request_store_failed_next,
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
