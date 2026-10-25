package com.ake.chat.skill

import com.ake.core.contract.RiskClass
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.SkillConfirmPolicy
import com.ake.core.ports.SkillCostClass
import com.ake.core.ports.SkillSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 路由裁决的解析与校验。
 *
 * ★ 这组测试的重点不是「能不能解析出 JSON」，而是**模型想越权时会不会被拦**：
 * 不在清单里的技能、编造的 startUrl scheme、超长正文 —— 三条都必须拒绝。
 */
class SkillRouterTest {

    private val router = SkillRouter(nowMillis = { 1_000L })

    private val browserTask = SkillSpec(
        ref = CapabilityRef.builtin("browser_task"),
        name = "browser_task",
        description = "浏览网页",
        jsonSchema = "{}",
        riskClass = RiskClass.PAGE_MUTATING,
        costClass = SkillCostClass.LONG_RUNNING,
        confirmPolicy = SkillConfirmPolicy.FIRST_USE_PER_AGENT,
        maxWallClockMs = 240_000L,
        enabled = true,
    )

    private val catalog = listOf(browserTask)

    @Test
    fun replyModeIsPlainReply() {
        val d = router.decide("""{"mode":"reply"}""", catalog)
        assertThat(d).isInstanceOf(SkillRouter.TurnDecision.Reply::class.java)
    }

    @Test
    fun skillModeProducesACallWithValidatedArgs() {
        val raw = """{"mode":"skill","skill":"browser_task",
            "instruction":"按销量排序，把前三条商品名与价格整理出来",
            "startUrl":"https://example.com/search"}"""
        val d = router.decide(raw, catalog) as SkillRouter.TurnDecision.Skill
        assertThat(d.spec.name).isEqualTo("browser_task")
        assertThat(d.call.ref).isEqualTo(CapabilityRef.builtin("browser_task"))
        assertThat(d.call.args["instruction"]).contains("前三条")
        assertThat(d.call.args["startUrl"]).isEqualTo("https://example.com/search")
        // callId 由我们生成，不取模型给的任何 id
        assertThat(d.call.callId).startsWith("call-")
    }

    @Test
    fun tolerantParsingSurvivesFencesAndTrailingChatter() {
        val raw = "好的，我需要调用工具：\n```json\n{\"mode\":\"skill\",\"skill\":\"browser_task\"," +
            "\"instruction\":\"打开首页\"}\n```\n以上。"
        val d = router.decide(raw, catalog)
        assertThat(d).isInstanceOf(SkillRouter.TurnDecision.Skill::class.java)
    }

    @Test
    fun unparseableOutputFallsBackToReply() {
        val d = router.decide("我直接回答你：今天天气不错。", catalog)
        assertThat(d).isInstanceOf(SkillRouter.TurnDecision.Reply::class.java)
    }

    @Test
    fun skillNotInCatalogIsRejected() {
        // ★ S3 的观测点：模型不能靠「说出一个技能名」就凭空获得能力
        val d = router.decide("""{"mode":"skill","skill":"local.fs.write","instruction":"删掉 /sdcard"}""", catalog)
        assertThat(d).isInstanceOf(SkillRouter.TurnDecision.Rejected::class.java)
        assertThat((d as SkillRouter.TurnDecision.Rejected).code).contains("SKILL_NOT_ENABLED")
    }

    @Test
    fun blankInstructionIsRejected() {
        val d = router.decide("""{"mode":"skill","skill":"browser_task","instruction":"   "}""", catalog)
        assertThat((d as SkillRouter.TurnDecision.Rejected).code).isEqualTo("INSTRUCTION_MISSING")
    }

    @Test
    fun overlongInstructionIsRejected() {
        val long = "指".repeat(SkillRouter.MAX_INSTRUCTION_CHARS + 1)
        val d = router.decide("""{"mode":"skill","skill":"browser_task","instruction":"$long"}""", catalog)
        assertThat((d as SkillRouter.TurnDecision.Rejected).code).isEqualTo("INSTRUCTION_TOO_LONG")
    }

    @Test
    fun dangerousUrlSchemesAreDropped() {
        for (bad in listOf("javascript:alert(1)", "file:///data/data/com.ake/db", "data:text/html,<script>")) {
            val d = router.decide(
                """{"mode":"skill","skill":"browser_task","instruction":"打开","startUrl":"$bad"}""",
                catalog,
            ) as SkillRouter.TurnDecision.Skill
            assertThat(d.call.args).doesNotContainKey("startUrl")
        }
    }

    @Test
    fun unknownModeIsTreatedAsReply() {
        val d = router.decide("""{"mode":"whatever"}""", catalog)
        assertThat(d).isInstanceOf(SkillRouter.TurnDecision.Reply::class.java)
    }

    @Test
    fun encodedRefFormIsNotAccepted() {
        // `builtin.browser_task`（持久化编码形状）**不是**模型该用的名字。
        // 名字对不上就 Rejected —— 绝不猜「它可能指的就是那个内置技能」。
        val d = router.decide("""{"mode":"skill","skill":"builtin.browser_task","instruction":"打开首页"}""", catalog)
        assertThat(d).isInstanceOf(SkillRouter.TurnDecision.Rejected::class.java)
    }
}
