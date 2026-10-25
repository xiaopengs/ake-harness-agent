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
/**
 * 这条结论该用**多大声**说。
 *
 * ## 为什么归因分对了还不够
 *
 * 文案决定用户"读完之后做什么"，颜色决定用户"读之前有多慌"。同一句话放在红条里，
 * 用户的第一反应是「我搞坏了什么」，然后开始找那个能修好它的开关 —— 哪怕正文写着
 * 「等一会儿」。`IN_FLIGHT` 正是这种情形：**没有任何东西失败，只是还没轮到**。
 *
 * ## 为什么不给 `else`
 *
 * 与 `when` 穷尽性同源：新增终态必须由人回答「它要不要喊」。默认值 `ALERT`
 * 是刻意选的 —— 拿不准就喊出来，比把真失败静音便宜。
 */
public enum class ChatTerminalTone {
    /** 有东西确实失败了：红条、半粗。 */
    ALERT,

    /** 没出事，只是现状：中性底、常规字重（见 `NoticeBar`）。 */
    NOTICE,
}

public data class ChatTerminalCopy(
    /** 一句话结论。 */
    @param:StringRes val titleRes: Int,
    /** 用户下一步能做什么。**必须给**，不给建议的失败态等于把问题推回给用户。 */
    @param:StringRes val nextRes: Int,
    /** 是否值得让用户点「重试」。 */
    val retryable: Boolean,
    /** 这条结论该用多大声说；默认按"有问题"处理，只有真的没出事才降成 [ChatTerminalTone.NOTICE]。 */
    val tone: ChatTerminalTone = ChatTerminalTone.ALERT,
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

    /**
     * ★ **上一轮还没收尾，所以这一轮根本没开始**（`enterFlight` 拒绝 / `FOCUS_BUSY`）。
     *
     * ## 与 `POLICY_DENIED` 的分界线（这两档曾经是同一句话）
     *
     * 拆分前，这一路上报的是 `POLICY_DENIED`，界面于是显示「安全策略拦下了这一步 ·
     * 去工具页逐条授权」。而这条路径上**没有 `PolicyPort.authorize`、没有出云裁决、
     * 没有授权表查询** —— 一次裁决都没发生。「策略」二字是凭空长出来的归因，
     * 用户照着它去翻设置，找一个不存在的开关，找不到就只能认为 App 坏了。
     *
     * | 事实 | 用户该做什么 | 归哪档 |
     * |---|---|---|
     * | 上一次裁决真的拒了（出云 / 未授权 / `FORBIDDEN`） | 换个说法，或去逐条授权 | `POLICY_DENIED` |
     * | 上一轮还在跑，这一轮排队都没排上 | **等它结束，把这句话再发一次** | 本档 |
     *
     * 所以这里的文案**一个"拦"字都不许有**：被拦下意味着有个东西在阻止你，
     * 而事实是轮子只有一个、正在被占用。
     *
     * ## 为什么 `retryable = true`
     *
     * 与文案同一件事：这句话本身没问题，模型没读过它，也没有任何东西在替用户续跑。
     * 「重试」重发的就是这句原话（`:ui` 的 `retry()` 复用 `lastUserText` 与 `turnId`），
     * 而且**只在前一轮收尾之后才可能成功** —— 所以正文必须把"等"说在最前面，
     * 否则用户会在同一秒连点，收不到任何反馈。
     *
     * ## 为什么 `tone = NOTICE`
     *
     * 见 [ChatTerminalTone]：红色会把"排队"读成"出事"。
     */
    ChatTerminalReason.IN_FLIGHT -> ChatTerminalCopy(
        titleRes = R.string.chat_term_in_flight_title,
        nextRes = R.string.chat_term_in_flight_next,
        retryable = true,
        tone = ChatTerminalTone.NOTICE,
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
