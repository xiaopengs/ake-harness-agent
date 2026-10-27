package com.ake.ui.feature.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ake.core.ports.ChatTerminalReason
import com.ake.ui.R
import com.ake.ui.model.chatTerminalCopyOf
import com.ake.ui.theme.AkeTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TerminalBanner] 的界面测试 —— 重点是「`IN_FLIGHT` 到底红没红」。
 *
 * ## 为什么非得读到像素，JVM 那半边测不出来
 *
 * `ChatCopyTest` 已经保证了 `chatTerminalCopyOf(IN_FLIGHT).tone == NOTICE`。
 * 但那是**数据**：它不知道 `TerminalBanner` 里 `when (copy.tone)` 的分支有没有接对，
 * 更不知道接对了之后画出来的是什么颜色。可见的结果只有「屏幕上是不是暖色」一种，
 * 所以这里直接截图读像素。
 *
 * ## 这条断言为什么配了一个反向对照用例
 *
 * 「没有暖色像素」这种断言有个坏毛病：**写错的时候它也通过**。
 * 比如取错了节点、截图截到了空白区域，`maxWarmness` 同样是 0，测试照样绿。
 *
 * 所以下面同时有一个 "POLICY_DENIED 确实是很暖的" 的对照：走的是同一套截图和
 * 同一套算法，只是换了个 reason。对照变红 = 量具坏了/屏幕画错了;
 * 对照绿而 IN_FLIGHT 红 = 真的有人把它改成红色了。
 * 两边一起看才值钱。
 *
 * ## 阈值为什么定得这么宽
 *
 * 告警橙 `RiskSensitive` 是 `#EF6C00`，`R-B` 差 239；中性灰蓝的 `surfaceVariant` /
 * `onSurfaceVariant` 差值在个位数。取 24 / 80 这两个门是把两者远远隔开，
 * 留足余量给抗锯齿、字体渲染差异、以及竖屏横屏的取样偏差。
 * 想收紧可以，但别为了「精确」把它做成一换字体就红的脆皮阈值。
 *
 * ## 为什么显式钉住 `darkTheme = false`
 *
 * 不钉的话它会跟设备的系统主题走 —— 同一份测试在夜里变红，是最难查的那类 flaky。
 */
@RunWith(AndroidJUnit4::class)
class TerminalBannerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun str(res: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(res, *args)

    /** 横幅实际渲染出来的整句：`title + " · " + next`。从 production 映射取，不在测试里抄文案。 */
    private fun bannerText(reason: ChatTerminalReason): String {
        val copy = chatTerminalCopyOf(reason)
        return str(copy.titleRes) + " · " + str(copy.nextRes)
    }

    /**
     * 截图里「最暖」的那一个像素有多暖：`max(R - B)`。
     *
     * 暖色（橙/红）这个值很大，中性灰蓝接近 0 甚至为负。
     */
    private fun ImageBitmap.maxWarmness(): Int {
        val bitmap = asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var max = 0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val blue = pixel and 0xFF
            max = maxOf(max, red - blue)
        }
        return max
    }

    private fun setBanner(reason: ChatTerminalReason, onRetry: () -> Unit = {}) {
        rule.setContent {
            AkeTheme(darkTheme = false) {
                TerminalBanner(reason = reason, onRetry = onRetry)
            }
        }
    }

    @Test
    // ⚠ 用例名里只能用下划线，**不能用空格**（哪怕用反引号包起来也不行）。
    // Kotlin 编译得过，但 D8 会把 V1 样式的方法名原样写进 dex，而 dex 规定
    // SimpleName 在 v040 之前不允许空格 —— 报错原文见 dexBuilderDebugAndroidTest：
    //   Space characters in SimpleName 'IN_FLIGHT 用中性色而不是告警暖色' are not allowed
    // 所以这里是 `IN_FLIGHT_用中性色` 而不是更好读的 `IN_FLIGHT 用中性色`。
    fun IN_FLIGHT_用中性色而不是告警暖色() {
        setBanner(ChatTerminalReason.IN_FLIGHT)

        val warmness = rule.onNodeWithText(bannerText(ChatTerminalReason.IN_FLIGHT), substring = true)
            .assertIsDisplayed()
            .captureToImage()
            .maxWarmness()

        assertThat(warmness).isLessThan(24)
    }

    @Test
    fun 对照_告警类终态确实画出暖色() {
        setBanner(ChatTerminalReason.POLICY_DENIED)

        val warmness = rule.onNodeWithText(bannerText(ChatTerminalReason.POLICY_DENIED), substring = true)
            .assertIsDisplayed()
            .captureToImage()
            .maxWarmness()

        assertThat(warmness).isGreaterThan(80)
    }

    @Test
    fun IN_FLIGHT_的重试按钮真的画出来了并且能点() {
        var retried = 0
        setBanner(ChatTerminalReason.IN_FLIGHT) { retried++ }

        rule.onNodeWithText(str(R.string.chat_turn_retry))
            .assertIsDisplayed()
            .performClick()

        assertThat(retried).isEqualTo(1)
    }

    @Test
    fun COMPLETED_不画任何横幅() {
        setBanner(ChatTerminalReason.COMPLETED)

        rule.onNodeWithText(str(R.string.chat_term_completed_title), substring = true)
            .assertDoesNotExist()
    }
}
