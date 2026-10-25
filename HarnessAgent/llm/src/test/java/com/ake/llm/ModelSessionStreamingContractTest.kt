package com.ake.llm

import com.ake.core.common.Outcome
import com.ake.core.common.SessionId
import com.ake.core.common.StepId
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.InferenceStats
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.ThinkingBudget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `ModelSession.generateStream` 的**默认实现**合同（`:core:model-api`）。
 *
 * ## 为什么专门测「默认实现」
 *
 * 这个默认实现不是临时兜底，它承担两个结构性职责：
 *
 *  1. **契约升级不砸别人的 CI**。`:agent` / `:chat` 的 `FakeModelSession` 实现了这个接口，
 *     加纯抽象方法会让那些**不在本模块**的文件当场编译失败。默认实现把它们的行为
 *     （一次性吐全文）保留为一种**合法但较慢**的形态。
 *  2. **假流式的唯一合法位置**。它一次只投一条 delta，绝不把全文拆成假 token 分批发 ——
 *     「屏幕上在动」与「模型在跑」脱钩之后，排查「卡住了还是还在想」就没有信号了。
 *
 * 真流式由 `:llm` 的 `LiteRtSession` 覆盖这个默认实现（那条路径需要 native，
 * JVM 单测里跑不了，见汇报里的「未在真机验证」一节）。
 */
@RunWith(JUnit4::class)
class ModelSessionStreamingContractTest {

    private fun req(maxOutputTokens: Int = 64) = GenerationRequest(
        stepId = StepId.new(),
        userTurn = "你好",
        outputShape = OutputShape.FreeText,
        maxOutputTokens = maxOutputTokens,
        thinking = ThinkingBudget.Off,
        deadlineMs = 30_000,
    )

    private class FakeSession(private val text: String) : ModelSession {
        override val sessionId: SessionId = SessionId("fake#1")

        override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> = Outcome.Ok(
            GenerationResult(
                rawText = text,
                toolCalls = emptyList(),
                finishReason = FinishReason.STOP,
                stats = InferenceStats(0, 0, 0, 0.0),
            ),
        )

        override suspend fun currentCacheTokens(): Int = 0

        override fun close() {}
    }

    @Test
    fun `默认实现投一条 delta，而不是把全文拆成假 token`() = runBlocking {
        val deltas = ArrayList<String>()

        val result = FakeSession("你好，我是小客。").generateStream(req()) { deltas += it }

        assertThat(deltas).containsExactly("你好，我是小客。")
        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        assertThat((result as Outcome.Ok).value.rawText).isEqualTo("你好，我是小客。")
    }

    @Test
    fun `空原文不投 delta`() = runBlocking {
        val deltas = ArrayList<String>()

        FakeSession("").generateStream(req()) { deltas += it }

        assertThat(deltas).isEmpty()
    }

    @Test
    fun `默认实现把错误原样返回，不等价于成功`() = runBlocking {
        val failing = object : ModelSession {
            override val sessionId: SessionId = SessionId("fake#2")
            override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> =
                Outcome.Err(com.ake.core.common.AppError("MODEL_GENERATE_FAILED", "err_model_generate_failed"))

            override suspend fun currentCacheTokens(): Int = 0
            override fun close() {}
        }
        val deltas = ArrayList<String>()

        val result = failing.generateStream(req()) { deltas += it }

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat(deltas).isEmpty()
    }
}
