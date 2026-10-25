package com.ake.llm

import com.ake.core.modelapi.ConversationTurn
import com.ake.core.modelapi.TurnRole
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 复水历史 → 引擎侧 `initialMessages` 的**语义**（`docs/05-chat-agents-design.md` §2.3 / §3.5）。
 *
 * 之前这里是 `initialMessages = emptyList()`：字段已经由 `:chat` 填好，
 * `:llm` 却把它丢掉 —— 每一次切回历史会话，模型都是**静默失忆**（不报错，只是答得像个陌生人）。
 *
 * ## 为什么断言的是 `plan()` 而不是 `toLiteRt()`
 *
 * `litertlm-android:0.18.0` 的 class file 是 **Java 21（major 65）**，本仓 JVM 单测跑在
 * Java 20（major 64）上 ⇒ 任何加载 `Message` / `Contents` 的测试都会在类加载期
 * `UnsupportedClassVersionError`，与逻辑对错无关。所以真正的语义（保留哪些、什么角色、
 * 什么顺序）被抽进 `plan()` 在 JVM 上钉死；`toLiteRt()` 只剩两行 `Message.user/model`
 * 的翻译。**这个测试不能证明那两行没写反，它只在代码 review 里可审计** —— 汇报里如实标注。
 *
 * 三条被守住的约束：
 *  1. **顺序 = 语义**。不重排；（"刚才那个函数的第二个参数" 靠的就是前一轮逐字在场）
 *  2. **角色一对一**：`USER → Role.USER`、`MODEL → Role.MODEL`。映射反了不会报错，
 *     只会让模型以为自己的话是用户说的 —— 最隐蔽的一类坏。
 *  3. **空串丢掉**：空 turn 只占 KV 不承载信息。
 */
@RunWith(JUnit4::class)
class RehydrationMessagesTest {

    @Test
    fun `角色与顺序逐条保留`() {
        val planned = RehydrationMessages.plan(
            listOf(
                ConversationTurn(TurnRole.USER, "第一句"),
                ConversationTurn(TurnRole.MODEL, "第二句"),
                ConversationTurn(TurnRole.USER, "第三句"),
            ),
        )

        assertThat(planned.map { it.role })
            .containsExactly(TurnRole.USER, TurnRole.MODEL, TurnRole.USER).inOrder()
        assertThat(planned.map { it.text })
            .containsExactly("第一句", "第二句", "第三句").inOrder()
    }

    @Test
    fun `空历史映射成空列表`() {
        assertThat(RehydrationMessages.plan(emptyList())).isEmpty()
    }

    @Test
    fun `空文本的轮次被丢弃，其余顺序不变`() {
        val planned = RehydrationMessages.plan(
            listOf(
                ConversationTurn(TurnRole.USER, "有内容"),
                ConversationTurn(TurnRole.MODEL, ""),
                ConversationTurn(TurnRole.USER, "还是有内容"),
            ),
        )

        assertThat(planned).hasSize(2)
        assertThat(planned.map { it.text }).containsExactly("有内容", "还是有内容").inOrder()
    }

    @Test
    fun `相邻同角色的轮次不被合并`() {
        // 复水形态（压缩边界 NOTE + 压缩行）本身就可能是 USER/USER 相邻。
        // 合并是 prompt 侧的决定，:llm 静默合并等于改写用户的对话历史。
        val planned = RehydrationMessages.plan(
            listOf(
                ConversationTurn(TurnRole.USER, "A"),
                ConversationTurn(TurnRole.USER, "B"),
            ),
        )

        assertThat(planned).hasSize(2)
        assertThat(planned.map { it.text }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun `内容完全相同的两条轮次也保留两份`() {
        // 用户重复问同一句话是真实场景；去重会让模型以为只问过一次
        val planned = RehydrationMessages.plan(
            listOf(
                ConversationTurn(TurnRole.USER, "在吗"),
                ConversationTurn(TurnRole.MODEL, "在"),
                ConversationTurn(TurnRole.USER, "在吗"),
            ),
        )

        assertThat(planned).hasSize(3)
    }
}
