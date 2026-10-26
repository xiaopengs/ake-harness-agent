package com.ake.ui.model

import com.ake.core.ports.ChatTerminalReason
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * `ChatTurnFailure.of()` —— 哪个终态值得占一条气泡的位置。
 *
 * ## 为什么连它也要测
 *
 * `ChatCopy.kt` 管的是"说什么"，`ChatTurnFailure` 管的是"说不说"。
 * 两者都列了九个终态（都刻意不写 `else`），所以**任何一处漏列都只能靠人发现** ——
 * 编译器不会告诉你"这两个分支应该同步改"。
 *
 * ## 判据（按值）
 *
 * - `COMPLETED` / `USER_ABORT` ⇒ null。答完了没有需要解释的东西；
 *   而用户自己按的中止，不需要 App 从头解释一遍"发生了什么"；
 * - 剩下七个 ⇒ 非 null，且 `retryable` 与文案表一致。
 *
 * ## 为什么 `IN_FLIGHT` 必须**占**一条位置（最容易被人"优化"掉的一条）
 *
 * 排队看起来不像失败：没推理、没报错、系统也没坏。上一版把它归入"不必解释"，
 * 结果用户看到的是 —— 按了发送，什么都没发生，也不知道该等还是该重打，于是再按一次；
 * 而 `beginTurn` 的闸会把第二下丢掉，屏幕上依旧什么都没有。**沉默才是这里的失败形态**。
 */
class ChatTurnFailureTest {

    @Test
    fun successAndUserAbortNeedNoExplanation() {
        assertThat(ChatTurnFailure.of("turn-1", ChatTerminalReason.COMPLETED)).isNull()
        assertThat(ChatTurnFailure.of("turn-1", ChatTerminalReason.USER_ABORT)).isNull()
    }

    @Test
    fun queueingCountsAsAFailureTheUserMustBeToldAbout() {
        val failure = ChatTurnFailure.of("turn-7", ChatTerminalReason.IN_FLIGHT)

        assertWithMessage(
            "IN_FLIGHT 必须占一条气泡：用户看到的只是「点了没反应」，" +
                "不给解释他就只能继续点。"
        ).that(failure).isNotNull()
        assertThat(failure!!.reason).isEqualTo(ChatTerminalReason.IN_FLIGHT)
        assertThat(failure.turnId).isEqualTo("turn-7")
        assertThat(failure.retryable).isTrue()
    }

    @Test
    fun everyUnfinishedTurnGetsAFailureWithTheSameRetryabilityAsItsCopy() {
        val shouldOccupy = ChatTerminalReason.entries - setOf(
            ChatTerminalReason.COMPLETED,
            ChatTerminalReason.USER_ABORT,
        )

        // 这里的期望值直接取自文案表：这是一致性断言 —— 防止哪天有人把 `of()` 里的
        // `chatTerminalCopyOf(reason).retryable` 改成一刀切的常量。
        // 每个终态到底该不该给重试按钮，权威取值在 `ChatCopyTest.retryableFollows...` 里。
        val expectedRetryable = shouldOccupy.associateWith { chatTerminalCopyOf(it).retryable }

        shouldOccupy.forEach { reason ->
            val failure = ChatTurnFailure.of("turn-x", reason)
            assertWithMessage("$reason 应当占一条气泡").that(failure).isNotNull()
            assertWithMessage("$reason.retryable 与文案表不一致")
                .that(failure!!.retryable).isEqualTo(expectedRetryable.getValue(reason))
            assertThat(failure.reason).isEqualTo(reason)
            assertThat(failure.turnId).isEqualTo("turn-x")
        }

        assertThat(shouldOccupy).hasSize(ChatTerminalReason.entries.size - 2)
    }

    @Test
    fun turnIdIsCarriedThroughSoTheFailureLandsOnTheRightBubble() {
        // 一个会话里可以有多轮失败，`turnId` 错了脚注就会贴到隔壁一轮上。
        assertThat(ChatTurnFailure.of("turn-a", ChatTerminalReason.STORE_FAILED)!!.turnId)
            .isEqualTo("turn-a")
        assertThat(ChatTurnFailure.of("turn-b", ChatTerminalReason.REQUEST_STORE_FAILED)!!.turnId)
            .isEqualTo("turn-b")
        assertThat(ChatTurnFailure.of(null, ChatTerminalReason.MODEL_FAILED)!!.turnId).isNull()
    }
}
