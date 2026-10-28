package com.ake.ui.feature.chat

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ake.ui.R
import com.ake.ui.theme.AkeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SessionRow] 的界面测试 —— 一行里「名称 / 预览 / 未读」三件套到底露没露出来。
 *
 * ## 为什么这行字值得一份 instrumentation 测试
 *
 * 列表页存在的全部意义就是**让用户不点进去也能认出要找的那条**：
 * 名称回答"和谁"，预览回答"聊到哪"，未读回答"有没有落下什么"。
 * 少任何一样，用户都得点进去才能确认 —— 那这一下点击就没省掉。
 *
 * 而这三样在此之前没有任何测试守着：`SessionRow` 是 `ChatListScreen` 的私有函数，
 * JUnit 用例够不到。把它改成 `public` 是这张背书单的代价（见该函数上的注释）。
 *
 * ## 为什么不断言点击
 *
 * 点一次行会触发 `onClick` 这件事，要稳定地测就得给 `Row` 加 `Modifier.testTag`
 * 或者依赖语义向上合并 —— 前者是往生产代码里塞测试钩子，后者依赖我此刻验证不了的
 * 合并行为。CI 上还没有模拟器（`.github/workflows` 里根本没有 emulator job），
 * 我写下的每条断言都是**只能编译、没有跑过**的，所以宁可少写一条我不敢担保的。
 *
 * ## 为什么不断言时间文案
 *
 * `RelativeTimeText` 的输出会随 `nowMillis` 与系统语言变化，且它自己有单测。
 * 这里只保证它渲染不炸 —— 顺带也是对 "[SessionRow] 在没有 `nowMillis` 时会崩" 的回归。
 *
 * ## ⚠【2026-11】执行宿主：Robolectric，不是真机
 *
 * 首跑即绿（4/4），未作任何修改。但它们跑在 Robolectric 下 ——
 * 证明的是 Compose **语义树与文案**，**不是真实渲染像素**。
 * 完整边界（能证明什么 / 证明不了什么）见 `src/androidTest/README.md` §1。
 */
@RunWith(AndroidJUnit4::class)
class SessionRowUiTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val AGENT = "客服 Agent"
        const val PREVIEW = "您的退款申请已提交"
        const val STAMP = 1_700_000_000_000L
    }

    private fun item(
        agentName: String = AGENT,
        preview: String = PREVIEW,
        unreadCount: Int = 0,
    ): ChatListItem = ChatListItem(
        sessionId = "s1",
        agentId = "a1",
        agentName = agentName,
        title = "会话标题",
        preview = preview,
        lastMessageAt = STAMP,
        unreadCount = unreadCount,
    )

    private fun setRow(item: ChatListItem, nowMillis: Long = STAMP) {
        rule.setContent {
            AkeTheme(darkTheme = false) {
                SessionRow(item = item, nowMillis = nowMillis, onClick = {})
            }
        }
    }

    private fun str(res: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(res, *args)

    @Test
    fun 会话项同时显示名称与预览() {
        setRow(item())

        rule.onNodeWithText(AGENT).assertIsDisplayed()
        rule.onNodeWithText(PREVIEW).assertIsDisplayed()
    }

    @Test
    fun 有未读时显示未读条数() {
        setRow(item(unreadCount = 3))

        rule.onNodeWithText(str(R.string.chat_list_unread, 3)).assertIsDisplayed()
    }

    @Test
    fun 没有未读时不显示未读角标() {
        setRow(item(unreadCount = 0))

        rule.onNodeWithText(str(R.string.chat_list_unread, 1)).assertDoesNotExist()
    }

    @Test
    fun 缺数据时退回占位文案而不是留白() {
        setRow(item(agentName = "", preview = ""))

        rule.onNodeWithText(str(R.string.chat_list_untitled_agent)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.chat_list_no_message)).assertIsDisplayed()
    }
}
