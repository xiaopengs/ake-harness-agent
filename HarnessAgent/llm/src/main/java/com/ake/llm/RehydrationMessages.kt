package com.ake.llm

import com.ake.core.modelapi.ConversationTurn
import com.ake.core.modelapi.TurnRole
import com.google.ai.edge.litertlm.Message

/**
 * 复水历史（`SessionSpec.initialMessages`）→ LiteRT-LM 的 `initialMessages`。
 *
 * ## 这个映射为什么是 P0
 *
 * `ConversationConfig.initialMessages` 是**开会话时一次性 prefill** 的载体：历史灌在这里，
 * 引擎付一次 prefill 重建 KV cache；之后每轮只是普通增量 prefill。
 * 灌进 `userTurn` 则是另一个数量级的代价 —— 每轮都要重新 prefill 一遍全部历史
 * （32 轮原文约 40k token，在 CPU 回退机上等于 72 秒/轮，见 `:chat` 的 `ChatRehydrator`）。
 *
 * 字段已经在 `:core:model-api` 定型、`:chat` 已经在填，唯一缺的就是这一跳。
 * 之前这里传的是 `initialMessages = emptyList()`：**每一次切回历史会话，模型都是失忆的**，
 * 而且失忆得没有痕迹 —— 不报错，只是答得像个刚认识你的陌生人。
 *
 * ## 三条约束
 *
 * 1. **顺序即语义**。历史是一段有先后的话，任何重排/去重/合并都会改变指代关系
 *    （"刚才那个函数的第二个参数" 之所以能问，靠的就是前一轮逐字在场）。
 *    所以这里只做逐条映射，**不做任何归并**。
 * 2. **相邻同角色轮次不合并**。复水形态本身就可能是 `USER(压缩边界) + MODEL(...) + ...`，
 *    末尾还可能落在 USER 上。要不要合并是 prompt 侧的决定（`:chat`），
 *    `:llm` 静默合并等于偷偷改写用户的对话历史。若 chat template 不接受，
 *    `openSession` 会以 `Outcome.Err(SESSION_DEAD)` 明确失败，而不是崩在 native。
 * 3. **空文本直接丢**。空串过 chat template 只会产出一对无内容的 turn token，
 *    既占 KV 又可能让模板引擎报错。
 *
 * ★ 角色只有两个：`USER` / `MODEL`。`Role.SYSTEM` 不从这里走 ——
 * 系统提示是 `ConversationConfig.systemInstruction` 的**独立参数**，
 * 混进 initialMessages 会让它出现在对话中段而不是 preface。
 *
 * ## 为什么决策与 SDK 类型分开（[plan] + [toLiteRt]）
 *
 * `litertlm-android:0.18.0` 的 class file 是 **Java 21（major 65）**，而本仓的 JVM 单测跑在
 * Java 17/20 上 —— 任何触碰 `Message` / `Contents` 的测试都会在加载期就
 * `UnsupportedClassVersionError`，**与逻辑对错无关**。
 * 所以「哪些轮次保留、以什么角色、按什么顺序」这段**真正的语义**被抽成纯函数 [plan]，
 * 在 JVM 上逐条断言；只剩最后一跳 `Message.user/model` 是两行 SDK 调用（见 [toLiteRt]）。
 */
internal object RehydrationMessages {

    /** 一条待写入引擎的轮次（SDK 无关的中间形态，可单测）。 */
    data class PlannedTurn(val role: TurnRole, val text: String)

    /**
     * 纯决策：过滤空文本，保留顺序与角色，不合并、不去重。
     *
     * 这是复水的全部语义；[toLiteRt] 只是把它翻译成 SDK 类型。
     */
    fun plan(turns: List<ConversationTurn>): List<PlannedTurn> {
        if (turns.isEmpty()) return emptyList()
        val out = ArrayList<PlannedTurn>(turns.size)
        for (turn in turns) {
            if (turn.text.isEmpty()) continue
            out += PlannedTurn(turn.role, turn.text)
        }
        return out
    }

    fun toLiteRt(turns: List<ConversationTurn>): List<Message> =
        plan(turns).map { planned ->
            when (planned.role) {
                TurnRole.USER -> Message.user(planned.text)
                TurnRole.MODEL -> Message.model(planned.text)
            }
        }
}
