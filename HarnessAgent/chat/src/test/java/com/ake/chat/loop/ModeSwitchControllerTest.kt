package com.ake.chat.loop

import com.ake.chat.ctx.ModeKind
import com.ake.chat.ctx.ModeProfileRegistry
import com.ake.core.ports.LoopMode
import com.ake.core.ports.MemoryTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 模式切换与 epoch 记账（§2.7 第 3 条）。
 *
 * 「隐藏的成本会在压测时以『怎么突然慢了 2 秒』的形式回到脸上」——
 * 所以每次切换都必须产出一条可观测的 transition，且 epoch 只增不减。
 */
class ModeSwitchControllerTest {

    private val registry = ModeProfileRegistry()

    private fun controller(kind: ModeKind = ModeKind.CHAT, epoch: Int = 0) =
        ModeSwitchController(MemoryTier.MID, registry, kind, epoch)

    @Test
    fun switchingToTheSameModeProducesNoTransition() {
        val c = controller()
        assertThat(c.to(ModeKind.CHAT, prefillTokens = 999)).isNull()
        assertThat(c.epoch).isEqualTo(0)
    }

    @Test
    fun switchingBumpsEpochAndReportsCost() {
        val c = controller()
        val t = c.to(ModeKind.TASK_IN_CHAT, prefillTokens = 1_200)!!

        assertThat(t.from).isEqualTo(LoopMode.REPLY)
        assertThat(t.to).isEqualTo(LoopMode.TASK)
        assertThat(t.epoch).isEqualTo(1)
        assertThat(t.prefillTokens).isEqualTo(1_200)
        assertThat(c.kind).isEqualTo(ModeKind.TASK_IN_CHAT)
        assertThat(c.epoch).isEqualTo(1)
    }

    @Test
    fun epochIsMonotonicAcrossBackAndForthSwitches() {
        val c = controller()
        c.to(ModeKind.TASK_IN_CHAT, 100)
        val back = c.to(ModeKind.CHAT, 50)!!
        val again = c.to(ModeKind.TASK_IN_CHAT, 10)!!

        assertThat(listOf(back.epoch, again.epoch)).containsExactly(2, 3).inOrder()
        assertThat(c.epoch).isEqualTo(3)
    }

    @Test
    fun epochIsRestoredFromTheDatabaseNotResetToZero() {
        // 冷启动复水：epoch 必须从 chat_session.mode_epoch 带回来
        val c = controller(epoch = 7)
        assertThat(c.epoch).isEqualTo(7)
        assertThat(c.to(ModeKind.TASK_IN_CHAT, 0)!!.epoch).isEqualTo(8)
    }

    @Test
    fun profileFollowsTheCurrentModeAndTier() {
        val c = controller()
        assertThat(c.profile().caps).isEqualTo(registry.profile(ModeKind.CHAT, MemoryTier.MID).caps)
        c.to(ModeKind.TASK_IN_CHAT, 0)
        assertThat(c.profile().caps).isEqualTo(registry.profile(ModeKind.TASK_IN_CHAT, MemoryTier.MID).caps)
    }

    @Test
    fun negativeCostIsClamped() {
        val c = controller()
        assertThat(c.to(ModeKind.TASK_IN_CHAT, prefillTokens = -5)!!.prefillTokens).isEqualTo(0)
    }
}
