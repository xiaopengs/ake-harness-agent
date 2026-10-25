package com.ake.chat.ctx

import com.ake.agent.context.ContextBudget
import com.ake.agent.context.SegmentCaps
import com.ake.agent.context.SegmentId
import com.ake.core.ports.MemoryTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 9 张 cap 表（3 档 × 3 模式）的求和断言。
 *
 * R5：手工维护必然出错，而出错的表现是**人格被悄悄挤掉** —— 最难复现的一类缺陷。
 * 所以这张清单必须由机器守。
 */
class ModeProfileRegistryTest {

    private val registry = ModeProfileRegistry()

    @Test
    fun allNineTablesSumExactlyToUsablePromptTokens() {
        val all = registry.all()
        assertThat(all).hasSize(9)
        for (p in all) {
            val usable = ContextBudget.forTier(p.tier).usablePromptTokens
            assertThat(p.caps.total).isEqualTo(usable)
        }
    }

    @Test
    fun everyTierAndModeCombinationIsCoveredExactlyOnce() {
        val keys = registry.all().map { it.kind to it.tier }
        for (kind in ModeKind.entries) {
            for (tier in MemoryTier.entries) {
                assertThat(keys.count { it == (kind to tier) }).isEqualTo(1)
            }
        }
    }

    @Test
    fun systemSegmentIsNeverInAnyDegradationOrder() {
        for (p in registry.all()) {
            assertThat(p.degradationOrder).doesNotContain(SegmentId.SYSTEM)
        }
    }

    @Test
    fun chatProfileKeepsSystemBudgetLargeEnoughForCharterAndPersona() {
        // 聊天剖面把 observation 与 task 之外的额度几乎全给了「历史 + 最近原文」，
        // 但 SYSTEM 必须仍然拿得到装宪章 + persona 的钱。
        for (tier in MemoryTier.entries) {
            val chat = registry.profile(ModeKind.CHAT, tier)
            assertThat(chat.caps.observation).isEqualTo(0)
            assertThat(chat.caps.system).isAtLeast(480)
            assertThat(chat.caps.recentTrace).isGreaterThan(chat.caps.system)
        }
    }

    @Test
    fun taskInChatEnablesBothHistorySources() {
        val p = registry.profile(ModeKind.TASK_IN_CHAT, MemoryTier.MID)
        // 双源：HISTORY_COMPACT 装任务步、RECENT_TRACE 装聊天轮
        assertThat(p.caps.historyCompact).isGreaterThan(0)
        assertThat(p.caps.recentTrace).isGreaterThan(0)
        assertThat(p.historySplit).isEqualTo(ModeProfileRegistry.HISTORY_SPLIT_CHAT)
    }

    @Test
    fun taskProfileIsTheExistingAgentProfileUnchanged() {
        // TASK 模式必须**就是** `:agent` 既有那张表 —— 任何「顺手调大一点」都是两套预算的开始。
        assertThat(registry.profile(ModeKind.TASK, MemoryTier.MID).caps).isEqualTo(SegmentCaps.STANDARD)
        assertThat(registry.profile(ModeKind.TASK, MemoryTier.LOW).caps).isEqualTo(SegmentCaps.NARROW)
        assertThat(registry.profile(ModeKind.TASK, MemoryTier.HIGH).caps).isEqualTo(SegmentCaps.WIDE)
    }

    @Test
    fun profileNameIsStableForPersistence() {
        assertThat(registry.profile(ModeKind.CHAT, MemoryTier.MID).name).isEqualTo("CHAT_MID")
        assertThat(registry.profile(ModeKind.TASK_IN_CHAT, MemoryTier.LOW).name).isEqualTo("TASK_IN_CHAT_LOW")
    }

    @Test
    fun loopModeMappingMatchesContract() {
        assertThat(ModeKind.CHAT.loopMode()).isEqualTo(com.ake.core.ports.LoopMode.REPLY)
        assertThat(ModeKind.TASK.loopMode()).isEqualTo(com.ake.core.ports.LoopMode.TASK)
        assertThat(ModeKind.TASK_IN_CHAT.loopMode()).isEqualTo(com.ake.core.ports.LoopMode.TASK)
    }
}
