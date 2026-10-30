package com.ake.ui.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.ake.core.common.ManualClock
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.MessageStatus
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.model.ModelBadgeSource
import com.ake.ui.testsupport.FakeChatSessionStore
import com.ake.ui.testsupport.FakeCloudCredentials
import com.ake.ui.testsupport.FakeDialogTargetStore
import com.ake.ui.testsupport.FakeProfiles
import com.ake.ui.testsupport.FixedRiskPort
import com.ake.ui.testsupport.NoopPermissions
import com.ake.ui.testsupport.ScriptedChatRunner
import com.ake.ui.testsupport.TEST_NOW
import com.ake.ui.testsupport.agentProfile
import com.ake.ui.testsupport.chatMessage
import com.ake.ui.testsupport.chatSummary
import com.ake.ui.testsupport.runUiTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/**
 * `ChatViewModel` 的状态归约（P2）。
 *
 * ## 为什么值得为这几个 Case 写测试
 *
 * `ChatUi` 是**三层叠加**的，优先级是 `stuck`（界面接管）> `liveText`（正在生成）>
 * `messages`（落盘事实）。这三层各自**单独退场**，退场时机写错，用户看到的不是"慢一点"，
 * 而是**屏幕上持续为假**：
 *
 *  - delta 被覆盖而不是累加 ⇒ 每个新到的 token 把前面已经说出口的回答吃掉；
 *  - `STORE_FAILED` 不登记 `stuck` ⇒ 那条助手气泡永远停在「正在生成」，用户盯着一个
 *    早就答完了的骗局；
 *  - 库里真的收敛后 `stuck` 不撤 ⇒ UI 永远抢着替数据库说话；
 *  - `beginTurn` 的闸失效 ⇒ 一次连点发成两轮，后端返回 `POLICY_DENIED`，
 *    界面显示"安全策略拦下了这一步" —— **用户点了两下，被告知被策略拒了**。
 *
 * 最后一条是这一组测试里最贵的一条：它断的是"UI 自己造出来的状态"，
 * 而不是某个文案写得对不对。
 *
 * ## 不涉及什么
 *
 * Compose、绘制、`LazyColumn` 都不在这里 —— 屏幕上有几个像素不属于单测能守住的范围。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    // ══════════════════════════════════════════════ 流式累加

    @Test
    fun deltasAccumulateInsteadOfOverwriting() = runUiTest {
        val h = harness()
        h.vm.onInputChange("给我讲个故事")
        h.vm.send()
        runCurrent()

        h.runner.emit(
            h.runner.delta("m1", "从前"),
            h.runner.delta("m1", "有座山，"),
            h.runner.delta("m1", "山里有座庙。"),
        )
        advanceUntilIdle()

        val ui = h.vm.ui.value
        assertThat(ui.liveText).isEqualTo("从前有座山，山里有座庙。")
        assertThat(ui.liveMessageId).isEqualTo("m1")
        assertThat(ui.streaming).isTrue()
    }

    // ══════════════════════════════════════════════ stuck 占位

    /**
     * 写路径坏了，库里那一行永远停在 `STREAMING`。
     * 界面必须在 `Terminal` 到达的那一刻把答案**冻住**并用失败现场覆盖那条气泡。
     */
    @Test
    fun storeFailedFreezesTheAlreadyStreamedTextIntoStuck() = runUiTest {
        val h = harness()
        h.vm.onInputChange("你好")
        h.vm.send()
        runCurrent()

        h.runner.emit(h.runner.delta("m1", "你好，"), h.runner.delta("m1", "有什么可以帮你"))
        advanceUntilIdle()
        h.runner.emit(h.runner.terminal("m1", ChatTerminalReason.STORE_FAILED))
        advanceUntilIdle()

        val ui = h.vm.ui.value
        assertThat(ui.streaming).isFalse()
        assertThat(ui.terminal).isEqualTo(ChatTerminalReason.STORE_FAILED)
        assertThat(ui.failure).isNotNull()
        assertThat(ui.failure!!.retryable).isTrue()

        val stuck = ui.stuck["m1"]
        assertWithMessage("STORE_FAILED 必须登记进 stuck：库写不进去了，只有 UI 能替那一刻说话")
            .that(stuck).isNotNull()
        assertWithMessage("文本必须在登记时冻住 —— 下一轮 beginTurn 会清空 liveText")
            .that(stuck!!.text).isEqualTo("你好，有什么可以帮你")
        assertThat(stuck.failure.reason).isEqualTo(ChatTerminalReason.STORE_FAILED)
    }

    /**
     * 只有 `STORE_FAILED` 有权接管。
     *
     * 其余终态都是**推理侧**的结论（`SKILL_FAILED` 时正文甚至可能是完整的），
     * 强行覆盖会把一段好的回答画成红色失败气泡 —— 拿"修一个永远转圈"换"说一句谎"。
     */
    @Test
    fun onlyStoreFailedTakesOverThePlaceholder() = runUiTest {
        val others = ChatTerminalReason.entries - ChatTerminalReason.STORE_FAILED

        others.forEach { reason ->
            val h = harness()
            h.vm.onInputChange("你好")
            h.vm.send()
            runCurrent()
            h.runner.emit(h.runner.delta("m1", "半截回答"), h.runner.terminal("m1", reason))
            advanceUntilIdle()

            val ui = h.vm.ui.value
            assertWithMessage("$reason 不该登记 stuck（只有写库失败才有「行已经锁死」这一回事）")
                .that(ui.stuck).isEmpty()
            assertThat(ui.terminal).isEqualTo(reason)
            assertThat(ui.streaming).isFalse()
        }
    }

    @Test
    fun stuckSurvivesTheNextTurn() = runUiTest {
        val h = harness()
        h.vm.onInputChange("你好")
        h.vm.send()
        runCurrent()
        h.runner.emit(h.runner.delta("m1", "第一段回答"), h.runner.terminal("m1", ChatTerminalReason.STORE_FAILED))
        h.runner.endStream()
        advanceUntilIdle()

        h.vm.onInputChange("第二句")
        h.vm.send()
        runCurrent()

        val ui = h.vm.ui.value
        assertWithMessage("那条消息属于上一轮，新一轮不该让它复活，也不该让它消失")
            .that(ui.stuck).containsKey("m1")
        assertWithMessage("新一轮从干净的 liveText 开始").that(ui.liveText).isEmpty()
    }

    /** 库里那条消息真的收敛了 ⇒ 话语权还给数据库（`stuck` 也不会随会话时长无限长）。 */
    @Test
    fun stuckIsReleasedWhenTheRowReallyConverges() = runUiTest {
        val h = harness()
        h.vm.onInputChange("你好")
        h.vm.send()
        runCurrent()
        h.runner.emit(h.runner.delta("m1", "回答内容"), h.runner.terminal("m1", ChatTerminalReason.STORE_FAILED))
        advanceUntilIdle()
        assertThat(h.vm.ui.value.stuck).containsKey("m1")

        // 还停在 STREAMING ⇒ 覆盖层必须继续撑着，不能趁早退出
        h.messages.value = listOf(chatMessage("m1", status = MessageStatus.STREAMING))
        advanceUntilIdle()
        assertWithMessage("库里那行还在转圈，此时撤退等于把 UI 的话又咽回去")
            .that(h.vm.ui.value.stuck).containsKey("m1")

        // 冷启 sweep 把它收敛成 FAILED ⇒ 界面退场
        h.messages.value = listOf(chatMessage("m1", status = MessageStatus.FAILED))
        advanceUntilIdle()
        assertThat(h.vm.ui.value.stuck).isEmpty()
    }

    // ══════════════════════════════════════════════ 防抖闸

    /**
     * 一轮还在跑的时候再按发送 —— 这次点击必须被闸门丢掉。
     *
     * 丢不掉的后果不是"多一句回答"：`:chat` 会按并发轮次拒掉后一次，返回
     * `POLICY_DENIED`，界面于是显示"安全策略拦下了这一步"。
     * **用户只是在生成过程中多问了一句，被告知被策略拒了** —— 那是 UI 自己造出来的状态。
     *
     * 所以这里断的是 `runner.requests` 的**数量**，不是某个状态字段：
     * 状态可以被别的代码顺手写对，发起了几轮这个事实不会。
     */
    @Test
    fun sendingWhileATurnIsRunningNeverStartsASecondOne() = runUiTest {
        val h = harness()
        h.vm.onInputChange("第一句")
        h.vm.send()
        runCurrent()
        assertThat(h.vm.ui.value.streaming).isTrue()

        // 用户在生成过程中又打了一句并按了发送 —— 这是闸真正要拦的那一下。
        // （同一帧连点两下的那种情况会被 input 清空顶掉，够不得到这一行。）
        h.vm.onInputChange("中途插一句")
        h.vm.send()
        runCurrent()

        assertThat(h.runner.requests).hasSize(1)
        assertThat(h.runner.requests.single().userText).isEqualTo("第一句")
    }

    @Test
    fun aRefusedSendDoesNotTouchTheStreamingState() = runUiTest {
        val h = harness()
        h.vm.onInputChange("第一句")
        h.vm.send()
        runCurrent()
        h.runner.emit(h.runner.delta("m1", "半截回答"))
        advanceUntilIdle()

        val before = h.vm.ui.value
        h.vm.onInputChange("中途插一句")
        h.vm.send()
        advanceUntilIdle()

        val after = h.vm.ui.value
        assertWithMessage("被闸挡下的那一下不许把正在生成的文本清掉")
            .that(after.liveText).isEqualTo(before.liveText)
        assertThat(after.liveMessageId).isEqualTo(before.liveMessageId)
        assertThat(after.streaming).isTrue()
    }

    @Test
    fun theGateIsReleasedAfterTheTurnEnds() = runUiTest {
        val h = harness()
        h.vm.onInputChange("第一句")
        h.vm.send()
        runCurrent()
        assertThat(h.vm.ui.value.streaming).isTrue()

        h.runner.endStream()
        advanceUntilIdle()
        assertWithMessage("流结束后必须 endTurn()，否则发送按钮永久卡住")
            .that(h.vm.ui.value.streaming).isFalse()

        h.vm.onInputChange("第二句")
        h.vm.send()
        runCurrent()

        assertThat(h.runner.requests).hasSize(2)
        assertThat(h.runner.requests.last().userText).isEqualTo("第二句")
    }

    @Test
    fun emptyInputNeverStartsATurn() = runUiTest {
        val h = harness()
        h.vm.onInputChange("   ")
        h.vm.send()

        assertThat(h.runner.requests).isEmpty()
        assertThat(h.vm.ui.value.streaming).isFalse()
    }

    /**
     * ★ `stop()` 之后立刻再发一句。
     *
     * 被取消的那个协程的 `finally` 是**稍后**才跑的，它会晚于新一轮的开头，
     * 于是有机会把新轮刚设上的状态抹回去 —— 表现是"生成中发送按钮亮了，点一下又发一轮"。
     * `turnToken` 那层世代判据就是为了掐掉这个窗口；删掉它会让这条红。
     */
    @Test
    fun aStaleTurnMustNotWipeTheStateOfTheTurnThatFollows() = runUiTest {
        val h = harness()
        h.vm.onInputChange("第一句")
        h.vm.send()
        runCurrent()
        assertThat(h.vm.ui.value.streaming).isTrue()

        h.vm.stop()
        h.vm.onInputChange("第二句")
        h.vm.send()
        // 让上一轮那个还悬着的 finally 跑起来
        runCurrent()

        assertWithMessage(
            "上一轮的 finally 抹掉了新一轮的 streaming —— turnToken 那层判据被摘了？"
        ).that(h.vm.ui.value.streaming).isTrue()

        h.runner.emit(h.runner.delta("m2", "第二句的答案"))
        h.runner.endStream()
        advanceUntilIdle()

        assertThat(h.vm.ui.value.liveText).isEqualTo("第二句的答案")
        assertThat(h.vm.ui.value.streaming).isFalse()
    }

    // ══════════════════════════════════════════════ 错误呈现

    /** 用户点「停止」不是错误：屏幕上不许出现 `JobCancellationException` 这类英文。 */
    @Test
    fun stoppingIsNotAnErrorAndNeverPrintsAnExceptionClassName() = runUiTest {
        val h = harness()
        h.vm.onInputChange("算了吧")
        h.vm.send()
        runCurrent()

        h.vm.stop()
        advanceUntilIdle()

        assertThat(h.vm.ui.value.streaming).isFalse()
        assertWithMessage("点了停止不该留下一句看不懂的东西").that(h.vm.ui.value.errorText).isNull()
    }

    @Test
    fun realFailuresAreShownWithTheirOwnMessageNotAClassName() = runUiTest {
        val h = harness()
        h.vm.onInputChange("你好")
        h.vm.send()
        runCurrent()

        h.runner.failWith(IllegalStateException("storage engine exploded"))
        advanceUntilIdle()

        assertThat(h.vm.ui.value.errorText).isEqualTo("storage engine exploded")
        assertThat(h.vm.ui.value.streaming).isFalse()
    }

    @Test
    fun sessionCreateFailureIsSurfacedAndReleasesTheGate() = runUiTest {
        val h = harness(sessionId = null)
        h.vm.onInputChange("你好")
        h.vm.send()
        advanceUntilIdle()

        assertThat(h.vm.ui.value.errorText).isEqualTo("err_chat_no_agent")
        assertThat(h.vm.ui.value.streaming).isFalse()
    }

    // ══════════════════════════════════════════════ 重试

    /**
     * 契约里写明"重试的时候 turnId 应当复用"，而 `clientRequestId` 每次新建 ——
     * 前者是 turn 的身份，后者是幂等键。两者混用会让重试在库里变成一轮新的 turn，
     * 界面上出现两段半截回答。
     */
    @Test
    fun retryReusesTheTurnIdButNeverTheIdempotencyKey() = runUiTest {
        val h = harness()
        h.vm.onInputChange("帮我查一下")
        h.vm.send()
        runCurrent()
        h.runner.emit(h.runner.terminal("m1", ChatTerminalReason.MODEL_FAILED))
        h.runner.endStream()
        advanceUntilIdle()

        h.vm.retry()
        runCurrent()

        assertThat(h.runner.requests).hasSize(2)
        val first = h.runner.requests[0]
        val second = h.runner.requests[1]
        assertThat(second.turnId).isEqualTo(first.turnId)
        assertThat(second.clientRequestId).isNotEqualTo(first.clientRequestId)
        assertThat(second.userText).isEqualTo(first.userText)
    }

    // ══════════════════════════════════════════════ 脚手架

    private fun harness(
        sessionId: String? = "session-1",
        grants: Optional<CapabilityGrantPort> = Optional.empty(),
    ): Harness = Harness(sessionId, grants)

    private class Harness(
        sessionId: String?,
        grants: Optional<CapabilityGrantPort>,
    ) {
        val messages: MutableStateFlow<List<ChatMessageView>> = MutableStateFlow(emptyList())
        val runner = ScriptedChatRunner()
        val store = FakeChatSessionStore(
            messages = messages,
            summaries = listOf(chatSummary()),
        )
        val vm = ChatViewModel(
            sessions = store,
            profiles = FakeProfiles(profile = agentProfile()),
            runner = Optional.of(runner),
            grants = grants,
            gate = UiHumanGate(NoopPermissions, FixedRiskPort()),
            clock = ManualClock(TEST_NOW),
            savedStateHandle = SavedStateHandle(
                mapOf(ChatViewModel.ARG_SESSION_ID to sessionId),
            ),
            badgeSource = ModelBadgeSource(
                maybeRouter = Optional.empty(),
                targets = FakeDialogTargetStore(),
                creds = FakeCloudCredentials(),
            ),
            notices = Optional.empty(),
        )
    }
}
