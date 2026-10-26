package com.ake.ui.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.SkillRegistryPort
import com.ake.core.ports.TemplateStorePort
import com.ake.ui.testsupport.FakeProfiles
import com.ake.ui.testsupport.FakeTemplates
import com.ake.ui.testsupport.runUiTest
import com.ake.ui.testsupport.template
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

/**
 * 扩展能力多选的**第二道闸**（P3）。
 *
 * ## 为什么测 ViewModel 里的 `onToggleCapability`，而不是测勾选框置灰
 *
 * 界面置灰只是**视觉**约束：一次长按、一次无障碍事件、或者将来加个"全选"，
 * 全都绕得过去。真正具备约束力的是 `CreateAgentViewModel` 里那一行判据 ——
 * 所以满额这件事必须在数据层再挡一次。
 *
 * ## 为什么这里坚持"满额就拒绝第 7 个"，不静默截断
 *
 * 6 不是审美上限：`SYSTEM` 段给 tools 的配额只有 1,024 token。
 * 用户勾了第 7 个、点了保存、回来发现少一个，会以为是自己记错了或者 App 有 bug ——
 * "满"这件事必须看得见。下面这几条守的就是"拒绝 vs 静默丢最后一个"的分界。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateAgentCapabilityLimitTest {

    private val limit = CreateAgentViewModel.DEFAULT_MAX_SKILLS

    @Test
    fun youCanSelectExactlyUpToTheLimit() {
        val h = harness()

        val refs = refs(limit)
        refs.forEach { h.vm.onToggleCapability(it, true) }

        assertThat(h.vm.ui.value.selected).containsExactlyElementsIn(refs)
        assertThat(h.vm.ui.value.selected).hasSize(limit)
    }

    @Test
    fun theOnePastTheLimitIsRefusedNotSilentlyDropped() {
        val h = harness()
        val full = refs(limit)
        full.forEach { h.vm.onToggleCapability(it, true) }

        val seventh = CapabilityRef.plugin("builtin.seventh")
        h.vm.onToggleCapability(seventh, true)

        assertWithMessage("第 7 个必须被拒绝 —— 静默丢会让用户以为自己勾上了")
            .that(h.vm.ui.value.selected).doesNotContain(seventh)
        assertWithMessage("拒绝时已有的选择一个都不许动").that(h.vm.ui.value.selected)
            .containsExactlyElementsIn(full)
    }

    /**
     * 取消永远不受上限约束 —— 否则"选满 6 个"就成了一个进去就出不来的房间。
     *
     * （"重复勾同一个会不会占名额"没有单独立一条：`selected` 是 Set，重复添加天然去重，
     *   任何实现都不可能把它写错 —— 给这种必然成立的命题写用例只是给通过率凑数。）
     */
    @Test
    fun deselectingIsAlwaysAllowedEvenAtTheLimit() {
        val h = harness()
        val full = refs(limit)
        full.forEach { h.vm.onToggleCapability(it, true) }

        val freed = CapabilityRef.plugin("builtin.fresh")
        h.vm.onToggleCapability(full.first(), false)
        h.vm.onToggleCapability(freed, true)

        assertThat(h.vm.ui.value.selected).doesNotContain(full.first())
        assertThat(h.vm.ui.value.selected).contains(freed)
        assertThat(h.vm.ui.value.selected).hasSize(limit)
    }

    @Test
    fun togglingOffRemovesItCompletely() {
        val h = harness()
        val ref = CapabilityRef.builtin("builtin.browser_task")
        h.vm.onToggleCapability(ref, true)
        h.vm.onToggleCapability(ref, true)

        assertThat(h.vm.ui.value.selected).containsExactly(ref)

        h.vm.onToggleCapability(ref, false)
        assertThat(h.vm.ui.value.selected).isEmpty()
    }

    /** 同一 id、不同来源是两个能力 —— 去重必须按 `ref`（含 kind），不能只按 id。 */
    @Test
    fun sameIdButDifferentKindsAreDifferentCapabilities() {
        val h = harness()
        val builtin = CapabilityRef(CapabilityKind.BUILTIN, "task")
        val plugin = CapabilityRef(CapabilityKind.PLUGIN, "task")

        h.vm.onToggleCapability(builtin, true)
        h.vm.onToggleCapability(plugin, true)

        assertThat(h.vm.ui.value.selected).hasSize(2)
    }

    /** 模板建议超过 6 条时按上限截 —— 且是按 limit 截，不写死 6。 */
    @Test
    fun templateSuggestionsAreTruncatedToTheLimit() = runUiTest {
        val suggested = refs(limit + 2)
        val h = harness(templateId = "tpl-1", template = template("tpl-1", suggested))
        advanceUntilIdle()

        assertWithMessage("超出上限的部分被截掉，且按模板给的原顺序取前 limit 个")
            .that(h.vm.ui.value.selected).hasSize(limit)
        assertThat(h.vm.ui.value.selected).containsExactlyElementsIn(suggested.take(limit))
    }

    // ══════════════════════════════════════════════ 脚手架

    private fun refs(n: Int): List<CapabilityRef> =
        (1..n).map { CapabilityRef.plugin("plugin.$it") }

    private fun harness(
        templateId: String? = null,
        template: com.ake.core.ports.AgentTemplate? = null,
    ): Harness = Harness(templateId, template)

    private class Harness(
        templateId: String?,
        tpl: com.ake.core.ports.AgentTemplate?,
    ) {
        val profiles: AgentProfileStorePort = FakeProfiles()
        val templates: TemplateStorePort = FakeTemplates(template = tpl)
        val vm = CreateAgentViewModel(
            profiles = profiles,
            templates = templates,
            // 注册表没接线 —— 上限判定与它没有关系，能力清单也可以只来自模板建议
            registry = Optional.empty<SkillRegistryPort>(),
            savedStateHandle = SavedStateHandle(
                mapOf(CreateAgentViewModel.ARG_TEMPLATE_ID to templateId),
            ),
        )
    }
}
