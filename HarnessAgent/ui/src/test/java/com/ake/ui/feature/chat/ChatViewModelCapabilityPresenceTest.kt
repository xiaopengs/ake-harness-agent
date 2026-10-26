package com.ake.ui.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.ake.core.common.ManualClock
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.ui.model.CapabilityPresence
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.testsupport.FakeChatSessionStore
import com.ake.ui.testsupport.FakeGrants
import com.ake.ui.testsupport.FakeProfiles
import com.ake.ui.testsupport.FixedRiskPort
import com.ake.ui.testsupport.NoopPermissions
import com.ake.ui.testsupport.ScriptedChatRunner
import com.ake.ui.testsupport.TEST_NOW
import com.ake.ui.testsupport.agentProfile
import com.ake.ui.testsupport.chatSummary
import com.ake.ui.testsupport.runUiTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

/**
 * `ChatViewModel` 的能力三态归约（P3 的下半截）。
 *
 * 上面那张 `CapabilityPresenceTest` 测的是"给定一张表，答案是什么"；
 * 这张测的是更前面的一环：**表读不到的时候，界面对不对地说"不知道"**。
 *
 * ## 为什么这一层更值得测
 *
 * 三态里最容易坏的就是 `UNKNOWN`，而它坏的方式是**退化**：
 * 有人在 `Optional.empty()` 分支里顺手返回 `NONE`、或者在 Flow 抛错时忘了 catch，
 * 于是这条提示会在「明明配了能力却什么都不说」和「明明没配却谎称有」之间摇摆。
 * 两种都是错的，而且都编译得过。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelCapabilityPresenceTest {

    @Test
    fun noGrantPortMeansUnknownAndThePageStaysQuiet() = runUiTest {
        val vm = vm(grants = Optional.empty())
        advanceUntilIdle()

        assertWithMessage(
            "拿不到 CapabilityGrantPort 时必须停在 UNKNOWN —— 保持沉默，" +
                "不能拿「没读到」冒充「没有」"
        ).that(vm.ui.value.capabilityPresence).isEqualTo(CapabilityPresence.UNKNOWN)
    }

    @Test
    fun anEmptyGrantTableIsNoneAndIsAllowedToSaySo() = runUiTest {
        val vm = vm(grants = Optional.of(FakeGrants(flowOf(emptyMap()))))
        advanceUntilIdle()

        assertThat(vm.ui.value.capabilityPresence).isEqualTo(CapabilityPresence.NONE)
    }

    @Test
    fun anyGrantedCapabilityIsPresence() = runUiTest {
        val ref = CapabilityRef(CapabilityKind.BUILTIN, "builtin.browser_task")
        val vm = vm(grants = Optional.of(FakeGrants(flowOf(mapOf(ref to true)))))
        advanceUntilIdle()

        assertThat(vm.ui.value.capabilityPresence).isEqualTo(CapabilityPresence.GRANTED)
    }

    /**
     * ★ 已经说过 GRANTED，然后订阅失败 ⇒ 必须**退回到 UNKNOWN**，不能赖在旧值上。
     *
     * 赖着旧值的诱惑很大（"至少不是空，看起来不像 bug"），但那等于把一分钟前的快照
     * 当成现在的事实继续展示 —— 用户撤回授权之后还被告知一切正常。
     */
    @Test
    fun aLaterReadFailureFallsBackToUnknownInsteadOfKeepingTheStaleVerdict() = runUiTest {
        val ref = CapabilityRef(CapabilityKind.PLUGIN, "plugin.x")
        val failing = flow<Map<CapabilityRef, Boolean>> {
            emit(mapOf(ref to true))
            throw IllegalStateException("grant table blew up")
        }
        val vm = vm(grants = Optional.of(FakeGrants(failing)))
        advanceUntilIdle()

        assertThat(vm.ui.value.capabilityPresence).isEqualTo(CapabilityPresence.UNKNOWN)
    }

    /** 授权表是跟 facts 走的：撤回授权后"有 / 没有"必须跟着变，而不是创建那一次的快照。 */
    @Test
    fun presenceFollowsTheGrantTableBothWays() = runUiTest {
        val ref = CapabilityRef(CapabilityKind.MCP_TOOL, "mcp.fs.read")
        val table = kotlinx.coroutines.flow.MutableStateFlow(mapOf(ref to true))
        val vm = vm(grants = Optional.of(FakeGrants(table)))
        advanceUntilIdle()
        assertThat(vm.ui.value.capabilityPresence).isEqualTo(CapabilityPresence.GRANTED)

        table.value = mapOf(ref to false)
        advanceUntilIdle()
        assertThat(vm.ui.value.capabilityPresence).isEqualTo(CapabilityPresence.NONE)
    }

    private fun vm(grants: Optional<CapabilityGrantPort>): ChatViewModel = ChatViewModel(
        sessions = FakeChatSessionStore(summaries = listOf(chatSummary())),
        profiles = FakeProfiles(profile = agentProfile()),
        runner = Optional.of(ScriptedChatRunner()),
        grants = grants,
        gate = UiHumanGate(NoopPermissions, FixedRiskPort()),
        clock = ManualClock(TEST_NOW),
        savedStateHandle = SavedStateHandle(
            mapOf(ChatViewModel.ARG_SESSION_ID to "session-1"),
        ),
    )
}
