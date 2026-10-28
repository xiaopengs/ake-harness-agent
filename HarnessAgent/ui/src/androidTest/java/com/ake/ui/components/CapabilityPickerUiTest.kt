package com.ake.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ake.core.ports.CapabilityRef
import com.ake.ui.R
import com.ake.ui.theme.AkeTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CapabilityPicker] 的界面测试 —— 盯的是「勾满上限后到底灰没灰」。
 *
 * ## 为什么必须有这一份，光有 `CreateAgentCapabilityLimitTest` 不够
 *
 * 那份 JVM 用例验的是 `selected.size >= limit` 这个**判定**返回 true；
 * 但它证明不了 `checkable` 真的传进了 `Checkbox(enabled = ...)`。中间还可能断在：
 * `enabled` 参数名写错、`CapabilityRow` 忘了转发、`Checkbox` 外面糊了一层
 * `Modifier.clickable` 把 true 又点亮了。这些都是**编译得过、看着也对**的断法。
 *
 * 「静默截断」是本项目反复警告的反模式：用户勾了第 7 个、并不知道它没生效。
 * 这里断言两件事同时成立 —— 未勾的行真的不能点、且界面上真的说了"先取消一个"。
 *
 * ## 复选框节点的定位方式，以及为什么不用 `testTag`
 *
 * 这里按**遍历顺序取第 i 个**：`CapabilityPicker` 用一个 `Column` 自上而下渲染、
 * 每行恰好一个 `Checkbox`，所以"第 i 个勾框"就是 `options[i]`。
 * 这样做的好处是它不受语义是否向上合并的影响（无论 `Checkbox` 保有自己的语义节点、
 * 还是被合并进外层的 `Row`，数量与顺序都不变）。
 *
 * 代价是对 `CapabilityRow` 的结构敏感：谁要是给它外面又套了一层带 `Checkbox` 的容器，
 * 这里就会错位。真到了那一天，正确的做法是给 `CapabilityRow` 加 `Modifier.testTag`，
 * 而不是继续猜顺序 —— 我没现在就加，是因为不想为了写测试往生产代码里塞钩子。
 *
 * ## 【2026-11 修订】首次真跑（Robolectric）暴露的两个问题，都改在这里，没有动断言
 *
 * 这两条用例此前**只在 androidTest 里编译过，从没执行过**。放到 Robolectric 下第一次
 * 真跑，两条都在 `checkboxes()[4]` 上红了：
 *
 *      AssertionError: The component with (Role = 'Checkbox')[4] is not displayed!
 *
 * 查下来是**测试写法的问题，不是 `CapabilityPicker` 的 bug**，原因有两层：
 *
 *   1. Robolectric 的默认视口是 **320dp × 470dp**（实测 `onRoot().getBoundsInRoot()`），
 *      比任何真机都小。8 行 `CapabilityRow` 铺不完，第 5 行往下全部落在视口外 ——
 *      Compose 的 `assertIsDisplayed()` 对视口外的节点就是 false，这是它的语义。
 *   2. 但**这不是 Robolectric 才有的坑**：真机（411×891dp）同样铺不完 8 行。
 *      生产侧 `CreateAgentScreen` 是把它包在 `Modifier.verticalScroll(rememberScrollState())`
 *      里的（`CreateAgentScreen.kt:66-67`），用户靠滚动够到后面的行 —— 而这份测试
 *      当初直接裸渲染 `CapabilityPicker`，**少了生产侧那个滚动宿主**。
 *
 * 所以修法是「把测试环境补成生产的样子」：外面套一层同样的 `verticalScroll`，
 * 断言前对每一行 `performScrollTo()`。断言本身一个字没改 ——
 * `assertIsDisplayed()` / `assertIsEnabled()` / `assertIsNotEnabled()` 全都还在，
 * 只是现在每一行都真的被滚进视口再验，而不是靠"恰好放得下"蒙过去。
 *
 * ⚠ 执行宿主是 Robolectric，不是真机：这套断言证明的是 Compose **语义树上的
 *   enabled 状态与文案**，**不是真实渲染像素**。完整边界见
 *   `src/androidTest/README.md` §1。
 */
@RunWith(AndroidJUnit4::class)
class CapabilityPickerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val options: List<CapabilityOption> = (1..8).map { i ->
        CapabilityOption(
            ref = CapabilityRef.builtin("builtin.cap_$i"),
            name = "能力 $i",
            description = "第 $i 号能力的说明",
            riskClass = null,
            costClass = null,
            confirmPolicy = null,
        )
    }

    private fun setPicker(
        selectedCount: Int,
        onToggle: (CapabilityRef, Boolean) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            AkeTheme(darkTheme = false) {
                // 与 CreateAgentScreen 保持一致：外面是 verticalScroll 宿主。
                // 缺了它，视口外的行既滚不到也断言不到 —— 见类注释里的修订说明。
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    CapabilityPicker(
                        options = options,
                        selected = options.take(selectedCount).map { it.ref }.toSet(),
                        limit = 6,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }

    private fun checkboxes() = rule.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
    )

    /**
     * 滚到第 [i] 行再断言。
     *
     * `performScrollTo()` 是必要的：8 行在任何视口下都铺不完（见类注释）。
     * 它对 `onAllNodes` 取到的那一条 SemanticsNodeInteraction 生效，
     * 所以这里取的是"第 i 个 Checkbox"而不是某段文案 —— 与 `checkboxes()` 的定位口径一致。
     */
    private fun assertRowEnabled(i: Int, enabled: Boolean) {
        val node = checkboxes()[i].performScrollTo().assertIsDisplayed()
        if (enabled) node.assertIsEnabled() else node.assertIsNotEnabled()
    }

    private fun str(res: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(res, *args)

    @Test
    fun 未满额时全部可勾且不显示上限文案() {
        setPicker(selectedCount = 5)

        val nodes = checkboxes().fetchSemanticsNodes()
        assertThat(nodes).hasSize(options.size)

        nodes.indices.forEach { i ->
            assertRowEnabled(i, enabled = true)
        }
        rule.onNodeWithText(str(R.string.cap_limit_reached, 6)).assertDoesNotExist()
    }

    @Test
    fun 勾满六个后未勾的置灰且给出上限文案() {
        setPicker(selectedCount = 6)

        // 已选中的前 6 行仍然可点 —— 取消选项不该被上限卡住
        repeat(6) { i ->
            assertRowEnabled(i, enabled = true)
        }

        // 未选中的第 7、8 行置灰：这是「没有静默截断」的直接证据
        assertRowEnabled(6, enabled = false)
        assertRowEnabled(7, enabled = false)

        // 而且必须说清楚为什么点不了，不能只灰着不解释。
        // ⚠ 必须先 performScrollTo()：上面对第 7、8 行的断言已经把列表滚到底了，
        //    顶部那句上限文案此刻在视口外，直接 assertIsDisplayed() 会红
        //    （这是首次真跑时踩到的第二个点，与类注释里那两个同源）。
        rule.onNodeWithText(str(R.string.cap_limit_reached, 6))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun 未置灰的行点击会回调出取消动作() {
        val toggled = mutableListOf<Pair<CapabilityRef, Boolean>>()
        setPicker(selectedCount = 6) { ref, checked -> toggled += ref to checked }

        checkboxes()[0].assertIsDisplayed().performClick()

        assertThat(toggled).containsExactly(options[0].ref to false)
    }
}
