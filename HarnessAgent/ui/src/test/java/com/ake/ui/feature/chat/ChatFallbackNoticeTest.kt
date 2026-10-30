package com.ake.ui.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.ake.core.common.ManualClock
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.Locality
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.ModelCapabilities
import com.ake.ui.model.ModelBadge
import com.ake.ui.model.ModelBadgeSource
import com.ake.ui.model.cloudFailureCopyOf
import com.ake.ui.model.formatCloudCopy
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.spi.TurnEgressNotice
import com.ake.ui.spi.TurnEgressNoticeSource
import com.ake.ui.testsupport.FakeChatSessionStore
import com.ake.ui.testsupport.FakeCloudCredentials
import com.ake.ui.testsupport.FakeDialogPort
import com.ake.ui.testsupport.FakeDialogTargetStore
import com.ake.ui.testsupport.FakeGrants
import com.ake.ui.testsupport.FakeModelRouter
import com.ake.ui.testsupport.FakeProfiles
import com.ake.ui.testsupport.FakeTurnEgressNoticeSource
import com.ake.ui.testsupport.FixedRiskPort
import com.ake.ui.testsupport.NoopPermissions
import com.ake.ui.testsupport.ScriptedChatRunner
import com.ake.ui.testsupport.TEST_NOW
import com.ake.ui.testsupport.UiStrings
import com.ake.ui.testsupport.chatMessage
import com.ake.ui.testsupport.chatSummary
import com.ake.ui.testsupport.runUiTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

// ===========================================================================
// ★★★ PRE 回落必须可见 —— §4.2「禁止静默回落」在界面上的最后一道闸
//
// 这一组必须能**杀死**一个具体的实现：静默降级。所以全部走**fake 注入**，
// 不读 ViewModel 的任何内部标志：
//
//   · 回落这件事由 [FakeTurnEgressNoticeSource] 吐事实 ——
//     它代表 runtime 侧（`:inference` / `:chat`）将来要接进来的那条线；
//   · 断言落在 VM 对外暴露的 `ui.value` 上：那一轮必须取出一份 [TurnEgressNotice]，
//     且它映射出来的文案必须能回答「我的数据出去没」。
//
// 谁把 `notices` 悄悄丢掉、或者压根没订阅，这组就是红的 ——
// 静默降级在这里拦不住，以后也没人能替用户发现。
// ===========================================================================
@OptIn(ExperimentalCoroutinesApi::class)
class ChatFallbackNoticeTest {

    private companion object {
        const val SESSION: String = "session-1"
        const val TURN: String = "turn-7"
        const val HOST: String = "gw.example.com"

        fun assistant(turnId: String = TURN): ChatMessageView = chatMessage(
            id = "msg-1",
            sessionId = SESSION,
            turnId = turnId,
            role = MessageRole.ASSISTANT,
            kind = MessageKind.TEXT,
            text = "这是回答正文。",
        )

        fun vmWith(notices: Optional<FakeTurnEgressNoticeSource>): ChatViewModel = ChatViewModel(
            sessions = FakeChatSessionStore(
                messages = MutableStateFlow(listOf(assistant())),
                summaries = listOf(chatSummary(SESSION)),
            ),
            profiles = FakeProfiles(),
            runner = Optional.of(ScriptedChatRunner(SESSION)),
            grants = Optional.of(FakeGrants(emptyFlow())),
            gate = UiHumanGate(NoopPermissions, FixedRiskPort()),
            clock = ManualClock(TEST_NOW),
            savedStateHandle = SavedStateHandle(mapOf(ChatViewModel.ARG_SESSION_ID to SESSION)),
            badgeSource = ModelBadgeSource(
                maybeRouter = Optional.of(
                    FakeModelRouter(port = FakeDialogPort(ModelCapabilities.REMOTE_DEFAULT))
                ),
                targets = FakeDialogTargetStore(),
                creds = FakeCloudCredentials(),
            ),
            notices = notices.map { it as TurnEgressNoticeSource },
        )
    }

    // ══════════════════════════════════════════════════════ ① PRE 回落要有话

    /**
     * ★ AuthRejected 是 PRE —— 服务端在读正文之前就拒了，一个字节都没出去。
     *
     * 允许回落到本机，但**必须**有一句话说出来。
     */
    @Test
    fun aPreFallbackProducesANoticeThatSaysItStayedOnDevice() = runUiTest {
        val source = FakeTurnEgressNoticeSource()
        val vm = vmWith(Optional.of(source))
        advanceUntilIdle()

        source.flow.value = listOf(
            TurnEgressNotice(
                turnId = TURN,
                failure = CloudFailure.AuthRejected,
                host = HOST,
                producedBy = Locality.DEVICE,
            )
        )
        advanceUntilIdle()

        val notice = vm.ui.value.noticeOf(assistant())
        assertWithMessage(
            "★ 回落发生了就必须有一条告知 —— 静默降级正是从这里溜走的。" +
                "拿不到 notice，说明 UI 根本不知道自己在回答的其实是本机。"
        ).that(notice).isNotNull()

        val copy = cloudFailureCopyOf(notice!!.failure)
        assertWithMessage("AuthRejected 是 PRE").that(copy.phase).isEqualTo(EgressPhase.PRE)

        val spoken = UiStrings.text(copy.titleRes) + UiStrings.text(copy.nextRes)
        assertWithMessage(
            "PRE 回落必须让界面说出「改由本机模型回答」+ 没出境。\n  实际文案：$spoken"
        ).that(spoken).contains("已改由本机模型回答")
        assertWithMessage("PRE 一个字节都没出去，不许说「已经发给」")
            .that(spoken).doesNotContain("已经发给")
    }

    /** ★ 同一条事实必须把徽标一起翻成本机 —— 只翻一半就是自相矛盾的界面。 */
    @Test
    fun aPreFallbackAlsoFlipsTheBadgeToDevice() = runUiTest {
        val source = FakeTurnEgressNoticeSource()
        val vm = vmWith(Optional.of(source))
        advanceUntilIdle()

        source.flow.value = listOf(
            TurnEgressNotice(
                turnId = TURN,
                failure = CloudFailure.PageContextRefused,
                host = HOST,
                producedBy = Locality.DEVICE,
            )
        )
        advanceUntilIdle()

        val badge = vm.ui.value.badgeOf(assistant())
        assertWithMessage("这一轮被钳回本机了，徽标必须跟着改口")
            .that(badge).isNotInstanceOf(ModelBadge.Remote::class.java)
        assertThat(badge).isInstanceOf(ModelBadge.Device::class.java)
    }

    // ══════════════════════════════════════════════════════ ② POST 禁止回落

    /**
     * ★ ModelNotFound 是 POST —— 请求体早就发出去了，服务端是收下之后才报的错。
     *
     * 禁止回落，且**必须**把「已经发给谁」说出口。
     * 让用户以为「没成功所以没出去」比失败本身更糟：那是一个用户无从察觉的隐私事件。
     */
    @Test
    fun aPostFailureSaysAlreadySentToTheHostAndNeverSaysFellBack() = runUiTest {
        val source = FakeTurnEgressNoticeSource()
        val vm = vmWith(Optional.of(source))
        advanceUntilIdle()

        source.flow.value = listOf(
            TurnEgressNotice(
                turnId = TURN,
                failure = CloudFailure.ModelNotFound,
                host = HOST,
                producedBy = Locality.REMOTE,
            )
        )
        advanceUntilIdle()

        val notice = vm.ui.value.noticeOf(assistant())
        assertWithMessage("POST 失败也必须有告知：它本身就是一个出境事实").that(notice).isNotNull()

        val copy = cloudFailureCopyOf(notice!!.failure)
        assertWithMessage("ModelNotFound 是 POST").that(copy.phase).isEqualTo(EgressPhase.POST)

        val next = formatCloudCopy(UiStrings.text(copy.nextRes), notice.host)
        assertWithMessage("§4.4 总原则：POST 失败必须说出「已经发给 <归一化 host>」")
            .that(next).contains("已经发给 $HOST")
        assertWithMessage("POST 禁止回落 ⇒ 不许出现「已改由本机模型回答」")
            .that(next).doesNotContain("已改由本机模型回答")
    }

    /** 没有回落发生时，界面不许凭空长出一条告知 —— 谎报回落和静默降级一样坏。 */
    @Test
    fun noNoticeMeansNoNoticeIsRendered() = runUiTest {
        val source = FakeTurnEgressNoticeSource()
        val vm = vmWith(Optional.of(source))
        advanceUntilIdle()

        assertWithMessage("没有发生过回落，就不该有一条告知")
            .that(vm.ui.value.noticeOf(assistant())).isNull()
        assertWithMessage("但徽标必须照样算出来 —— 不可见的承诺等于没有承诺")
            .that(vm.ui.value.badges).containsKey(TURN)
    }

    /**
     * ★ `noticeSource` 没接进来时（今天的接线状态）**徽标仍然要有**。
     *
     * 徽标依赖的是路由器的答复，不是回落告知。把两者捆成一根线的实现，
     * 会让「看不见的路由承诺」又变成看不见的。
     */
    @Test
    fun badgesExistEvenWhenTheNoticeSourceIsMissing() = runUiTest {
        val vm = vmWith(Optional.empty())
        advanceUntilIdle()

        assertWithMessage("noticeSource 缺失不是徽标消失的理由")
            .that(vm.ui.value.badges).isNotEmpty()
        assertThat(vm.ui.value.badges).containsKey(TURN)
    }
}
