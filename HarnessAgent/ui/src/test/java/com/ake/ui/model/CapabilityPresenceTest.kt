package com.ake.ui.model

import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 「这个 Agent 有没有扩展能力」的三态（P3）。
 *
 * ## 两种回答不够，事实有三种
 *
 * | 事实 | 界面该说什么 |
 * |---|---|
 * | 一项都没授予 | 「它只会聊天」—— 这是关键信息，否则用户默认自己买了个会操作网页的东西 |
 * | 有授予 | 什么都不用说 |
 * | **读不到授权表** | **什么都不该说** |
 *
 * 第三种最容易被写错成 `false`：那会对着一个**其实配了六个能力**的 Agent 说
 * 「你还没配能力」。用一条错误的提示去修一个"信息缺失"的缺陷，
 * 用户会去重配一遍，然后发现配了也没用 —— 信任消耗得比沉默快得多。
 *
 * ## `UNKNOWN` 不在这里产生
 *
 * [capabilityPresenceOf] 只有两种输出：[CapabilityPresence.NONE] 与 [CapabilityPresence.GRANTED]。
 * `UNKNOWN` 是**读失败**的语义，由调用方（`ChatViewModel` / 界面）持有 ——
 * 见 `ChatViewModelCapabilityPresenceTest`。把两种职责混进一个函数里，
 * 就等于允许某个地方把"读到一张空表"当成"读不到"。
 */
class CapabilityPresenceTest {

    @Test
    fun anEmptyGrantTableMeansReallyNone() {
        assertThat(capabilityPresenceOf(emptyMap())).isEqualTo(CapabilityPresence.NONE)
    }

    @Test
    fun grantedRowsThatAreFalseStillMeanNone() {
        val ref = CapabilityRef(CapabilityKind.BUILTIN, "builtin.browser_task")
        val plugin = CapabilityRef(CapabilityKind.PLUGIN, "plugin.x")

        // 契约允许实现把 granted=false 的行也带出来，所以这里判「有没有一项为 true」，
        // 而不是判 isNotEmpty() —— 两种实现都对。
        assertThat(capabilityPresenceOf(mapOf(ref to false, plugin to false)))
            .isEqualTo(CapabilityPresence.NONE)
    }

    @Test
    fun oneTrueRowIsEnough() {
        val ref = CapabilityRef(CapabilityKind.BUILTIN, "builtin.browser_task")
        val plugin = CapabilityRef(CapabilityKind.PLUGIN, "plugin.x")

        assertThat(capabilityPresenceOf(mapOf(ref to false, plugin to true)))
            .isEqualTo(CapabilityPresence.GRANTED)
        assertThat(capabilityPresenceOf(mapOf(ref to true)))
            .isEqualTo(CapabilityPresence.GRANTED)
    }

    /** 三种取值缺一不可 —— 少一个，某个调用点就只能把两件不同的事折叠起来。 */
    @Test
    fun thereAreExactlyThreeStates() {
        assertWithMessage(
            "三态缺一：把「读不到」折叠进 NONE 就会导致误报「没配能力」"
        ).that(CapabilityPresence.entries.map { it.name })
            .containsExactly("UNKNOWN", "NONE", "GRANTED")
    }
}
