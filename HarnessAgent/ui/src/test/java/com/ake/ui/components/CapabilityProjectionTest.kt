package com.ake.ui.components

import com.ake.core.contract.RiskClass
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.ui.testsupport.skillSpec
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 能力选择器里**能被测的那部分**（P3）。
 *
 * 勾选框长什么样、置不置灰，属于 Compose 渲染，本项目没有 `androidTest` 基础设施，
 * 不为它引新依赖。但"这一行能不能勾"这件事的输入，是两份纯投影 ——
 * 它们的值错了，界面再怎么画也是错的。
 *
 * ## 这里守的是哪一条生产线上的错
 *
 * `SkillRegistryPort` 没有 Hilt 绑定，于是内存里可能只有一串 `CapabilityRef`
 * 而没有 `SkillSpec`。这时如果照顾用户感受顺手写一句"帮你操作浏览器"，
 * 那句话是**编的** —— 我们没有从注册表里读到过它。
 * UI 层的纪律是：读不到就说读不到（显示 id），不许补充。
 */
class CapabilityProjectionTest {

    @Test
    fun forbiddenSkillsAreNeverCheckable() {
        assertThat(skillSpec(riskClass = RiskClass.FORBIDDEN).toOption().forbidden).isTrue()
    }

    @Test
    fun everythingBelowForbiddenIsStillOffered() {
        RiskClass.entries.filter { it != RiskClass.FORBIDDEN }.forEach { risk ->
            assertWithMessage("$risk 不是硬禁止，应当仍旧出现在列表里")
                .that(skillSpec(riskClass = risk).toOption().forbidden).isFalse()
        }
    }

    @Test
    fun specIsProjectedWithItsOwnWords() {
        val spec = skillSpec(
            name = "网页任务",
            description = "打开网页并按目标取数据",
            riskClass = RiskClass.EXTERNAL_SIDE_EFFECT,
        )
        val option = spec.toOption()

        assertThat(option.ref).isEqualTo(spec.ref)
        assertThat(option.name).isEqualTo("网页任务")
        assertThat(option.description).isEqualTo("打开网页并按目标取数据")
        assertWithMessage("风险等级必须原样带出来，界面靠它决定能不能勾")
            .that(option.riskClass).isEqualTo(RiskClass.EXTERNAL_SIDE_EFFECT.name)
        assertThat(option.costClass).isEqualTo(spec.costClass)
        assertThat(option.confirmPolicy).isEqualTo(spec.confirmPolicy)
    }

    /**
     * ★ 只有引用、没有规格时的投影。
     *
     * `description` 必须是空的：我们没有读到任何描述，写出来的每一个字都是编的。
     */
    @Test
    fun aBareReferenceIsNotDressedUpWithAFabricatedDescription() {
        val ref = CapabilityRef(CapabilityKind.BUILTIN, "builtin.browser_task")
        val option = ref.toOption()

        assertThat(option.ref).isEqualTo(ref)
        assertThat(option.name).isEqualTo("builtin.browser_task")
        assertThat(option.description).isEmpty()
        assertThat(option.riskClass).isNull()
        assertThat(option.costClass).isNull()
        assertThat(option.confirmPolicy).isNull()
        assertWithMessage("风险未知 ≠ 可以勾：只有 FORBIDDEN 之外且已知等级的行才可被信任")
            .that(option.forbidden).isFalse()
    }
}
