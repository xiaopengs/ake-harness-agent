package com.ake.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.Locality
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.ui.model.ModelBadge
import com.ake.ui.spi.TurnEgressNotice
import com.ake.ui.theme.AkeTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MessageBubble] 上的模型徽标与回落告知 —— 语义树层面的断言。
 *
 * ## 为什么必须有一条界面测试（而不是只测 ViewModel 的状态）
 *
 * 「徽标算出来了」与「徽标出现在树上」之间还隔着一层：
 * 一个被 `if` 悄悄吞掉的 composable，在 ViewModel 的断言里是完全绿的。
 * 红线要求的是**用户看得见**，那就得在语义树上数一次。
 *
 * ## ★ 本文件最重要的一条是 [aWebTaskTurnNeverShowsTheCloudBadge]
 *
 * 浏览器 / 网页任务的会话，界面上不许出现云端徽标。
 * 它断言的是**语义树里没有这个节点**（`assertDoesNotExist`），
 * 而不是「这个节点不可见」—— 后者会被 `alpha=0` 之类的手段蒙过去。
 *
 * ## ⚠【执行宿主】Robolectric，不是真机
 *
 * 与同目录其它用例一样跑在 JVM 上，证明的是**语义树与文案**，
 * 不是真实像素。边界见 `src/androidTest/README.md` §1。
 */
@RunWith(AndroidJUnit4::class)
class ModelBadgeUiTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val HOST: String = "gw.example.com"
        const val MODEL: String = "deepseek-chat"

        fun assistant(): ChatMessageView = ChatMessageView(
            id = "m1",
            sessionId = "s1",
            seq = 1,
            turnId = "t1",
            role = MessageRole.ASSISTANT,
            kind = MessageKind.TEXT,
            status = MessageStatus.COMPLETE,
            textRedacted = "这是回答。",
            createdAt = 0L,
        )
    }

    // ══════════════════════════════════════════════════════ ① 徽标

    @Test
    fun aDeviceTurnShowsTheDeviceBadge() {
        rule.setContent {
            AkeTheme {
                Column {
                    MessageBubble(message = assistant(), agentName = "阿客", badge = ModelBadge.Device("设备内推理"))
                }
            }
        }
        rule.onNodeWithText("本机端侧").assertIsDisplayed()
    }

    @Test
    fun aRemoteTurnShowsModelAndNormalisedHost() {
        rule.setContent {
            AkeTheme {
                Column {
                    MessageBubble(
                        message = assistant(),
                        agentName = "阿客",
                        badge = ModelBadge.Remote(MODEL, HOST),
                    )
                }
            }
        }
        // 「云端」两个字不够 —— 必须带上模型名与归一化 host，否则用户不知道发给了谁。
        rule.onNodeWithText("云端 · $MODEL · $HOST").assertIsDisplayed()
    }

    /** ★ 网页任务那一轮：云端徽标在语义树上**不存在**。 */
    @Test
    fun aWebTaskTurnNeverShowsTheCloudBadge() {
        rule.setContent {
            AkeTheme {
                Column {
                    // 锁 C 的产物：这一轮被钳回本机 ⇒ 徽标是 Device。
                    MessageBubble(message = assistant(), agentName = "阿客", badge = ModelBadge.Device("设备内推理"))
                }
            }
        }

        rule.onNodeWithText("本机端侧").assertIsDisplayed()
        // ★ 用的是「不存在」而不是「不可见」：`alpha=0` 也能让 isNotDisplayed 通过。
        val hidden = runCatching {
            rule.onNodeWithText("云端 · $MODEL · $HOST").assertDoesNotExist()
        }
        assertThat(hidden.isSuccess).isTrue()
    }

    @Test
    fun anUnknownBadgeStillSaysSomething() {
        rule.setContent {
            AkeTheme {
                Column {
                    MessageBubble(message = assistant(), agentName = "阿客", badge = ModelBadge.Unregistered)
                }
            }
        }
        // 「不知道」必须占一个可见的位子 —— 留白会被读成「本机」。
        rule.onNodeWithText("未登记后端").assertIsDisplayed()
    }

    // ══════════════════════════════════════════════════════ ② 回落告知

    /** ★ PRE 回落：界面上必须出现那句话，而且它说的是「没出境」。 */
    @Test
    fun aPreFallbackNoticeIsRenderedUnderTheBubble() {
        rule.setContent {
            AkeTheme {
                Column {
                    MessageBubble(
                        message = assistant(),
                        agentName = "阿客",
                        badge = ModelBadge.Device("设备内推理"),
                        egressNotice = TurnEgressNotice(
                            turnId = "t1",
                            failure = CloudFailure.ConnectFailed,
                            host = HOST,
                            producedBy = Locality.DEVICE,
                        ),
                    )
                }
            }
        }

        rule.onNodeWithText("连不上你填的那个端点").assertIsDisplayed()
        // ★ 那句话里必须含「已改由本机模型回答」—— 没有它，就是静默降级。
        val texts = rule.onNodeWithText("连不上你填的那个端点").fetchSemanticsNode()
        assertThat(texts).isNotNull()
        rule.onNodeWithText(
            "重试一次仍然失败，于是正文没有发出去 —— 这一条已改由本机模型回答。请检查地址与网络。"
        ).assertIsDisplayed()
    }

    /** ★ POST 失败：必须说出「已经发给 <归一化 host>」。 */
    @Test
    fun aPostFailureNoticeSaysItWasAlreadySent() {
        rule.setContent {
            AkeTheme {
                Column {
                    MessageBubble(
                        message = assistant(),
                        agentName = "阿客",
                        badge = ModelBadge.Remote(MODEL, HOST),
                        egressNotice = TurnEgressNotice(
                            turnId = "t1",
                            failure = CloudFailure.ServerError,
                            host = HOST,
                            producedBy = Locality.REMOTE,
                        ),
                    )
                }
            }
        }

        rule.onNodeWithText("服务端返回了错误（5xx）").assertIsDisplayed()
        rule.onNodeWithText("已经发给 $HOST —— 这是它那边出的问题，重试一次等于让这段话再出境一次。")
            .assertIsDisplayed()
    }

    /** 没有回落时，不许凭空长出告知 —— 谎报回落与静默降级一样坏。 */
    @Test
    fun noNoticeRendersNoNoticeText() {
        rule.setContent {
            AkeTheme {
                Column {
                    MessageBubble(
                        message = assistant(),
                        agentName = "阿客",
                        badge = ModelBadge.Device("设备内推理"),
                        egressNotice = null,
                    )
                }
            }
        }

        val missing = runCatching {
            rule.onNodeWithText("连不上你填的那个端点").assertDoesNotExist()
        }
        assertThat(missing.isSuccess).isTrue()
    }

    /** 用户消息不该有徽标 —— 那句话不是模型产出的。 */
    @Test
    fun aUserMessageHasNoModelBadge() {
        val user = assistant().copy(role = MessageRole.USER)
        rule.setContent {
            AkeTheme {
                Column {
                    MessageBubble(message = user, agentName = "阿客", badge = ModelBadge.Device("设备内推理"))
                }
            }
        }

        val missing = runCatching { rule.onNodeWithText("本机端侧").assertDoesNotExist() }
        assertThat(missing.isSuccess).isTrue()
    }
}
