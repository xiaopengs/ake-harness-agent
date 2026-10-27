package com.ake.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.ui.R
import com.ake.ui.model.ChatTurnFailure
import com.ake.ui.theme.AkeTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MessageBubble] 的界面测试 —— 气泡的左右归属，以及失败脚注里那三样东西。
 *
 * ## P2：左右归属为什么值得测
 *
 * 它是最基本的东西，回归风险也最低，但**一旦坏了极其显眼**：用户看到自己的话靠左、
 * 助手的话靠右，会立刻怀疑这个 App 是不是把对话搞混了。
 * 更实际的风险是 `MessageBubble` 里那两处 `return` —— `isUser` 走 `Arrangement.End`
 * 早退，其余走另一条路。任何人把其中一个早退挪了位置，左右立刻翻转，
 * 而 JVM 单测没有任何东西会红。
 *
 * 这里断言的是**相对位置**（用户的左边界在助手右边），而不是某个具体像素值 ——
 * 后者会让测试在换了屏宽、换了字体缩放之后无故变红。
 *
 * ## P1：失败脚注为什么必须"三样齐全"
 *
 * 只写「出错了」等于把排查成本全推给用户；只写原因不给按钮，用户只能自己重打一遍话。
 * 所以这里把 title / next / 重试按钮当成**一个整体**来断言，缺一样就红。
 *
 * 另外单独验了 `failure == null` 那条分支（冷启动读历史时拿不到终态详情）：
 * 那时仍要说人话，只是不给按钮 —— 一行都不一样都会掉 fidelity。
 */
@RunWith(AndroidJUnit4::class)
class MessageBubbleUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun message(
        role: MessageRole,
        text: String,
        status: MessageStatus = MessageStatus.COMPLETE,
    ): ChatMessageView = ChatMessageView(
        id = "m-${text.hashCode()}",
        sessionId = "s1",
        seq = 1,
        turnId = "t1",
        role = role,
        kind = MessageKind.TEXT,
        status = status,
        textRedacted = text,
        createdAt = 0L,
    )

    private fun str(res: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(res, *args)

    @Test
    fun 用户消息靠右而助手消息靠左() {
        rule.setContent {
            AkeTheme(darkTheme = false) {
                Column(Modifier.fillMaxWidth()) {
                    MessageBubble(message(MessageRole.USER, "我要退款"), agentName = "客服")
                    MessageBubble(message(MessageRole.ASSISTANT, "好的，我来查"), agentName = "客服")
                }
            }
        }

        val userBox = rule.onNodeWithText("我要退款").assertIsDisplayed().getBoundsInRoot()
        val assistantBox = rule.onNodeWithText("好的，我来查").assertIsDisplayed().getBoundsInRoot()

        assertThat(userBox.left).isGreaterThan(assistantBox.left)
        assertThat(userBox.right).isGreaterThan(assistantBox.right)
    }

    @Test
    fun 失败终态同时给出原因下一步和重试按钮() {
        var retried = 0
        val failure = requireNotNull(ChatTurnFailure.of("t1", ChatTerminalReason.MODEL_FAILED))

        rule.setContent {
            AkeTheme(darkTheme = false) {
                MessageBubble(
                    message = message(MessageRole.ASSISTANT, "半句话就断了"),
                    agentName = "客服",
                    statusOverride = MessageStatus.FAILED,
                    failure = failure,
                    onRetry = { retried++ },
                )
            }
        }

        rule.onNodeWithText(str(R.string.chat_term_model_failed_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.chat_term_model_failed_next)).assertIsDisplayed()

        rule.onNodeWithText(str(R.string.chat_turn_retry))
            .assertIsDisplayed()
            .performClick()

        assertThat(retried).isEqualTo(1)
    }

    @Test
    fun 拿不到失败明细时仍然说人话但不给重试按钮() {
        rule.setContent {
            AkeTheme(darkTheme = false) {
                MessageBubble(
                    message = message(MessageRole.ASSISTANT, "半句话就断了"),
                    agentName = "客服",
                    statusOverride = MessageStatus.FAILED,
                    failure = null,
                    onRetry = {},
                )
            }
        }

        rule.onNodeWithText(str(R.string.chat_term_model_failed_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.chat_term_model_failed_next)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.chat_turn_retry)).assertDoesNotExist()
    }

    @Test
    fun 非失败消息不出现任何失败文案() {
        rule.setContent {
            AkeTheme(darkTheme = false) {
                MessageBubble(
                    message = message(MessageRole.ASSISTANT, "说完了"),
                    agentName = "客服",
                    statusOverride = MessageStatus.COMPLETE,
                    failure = null,
                )
            }
        }

        rule.onNodeWithText("说完了").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.chat_term_model_failed_title)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.chat_turn_retry)).assertDoesNotExist()
    }
}
