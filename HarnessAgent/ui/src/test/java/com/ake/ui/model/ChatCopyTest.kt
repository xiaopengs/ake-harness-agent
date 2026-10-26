package com.ake.ui.model

import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.MessageStatus
import com.ake.ui.R
import com.ake.ui.testsupport.UiStrings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * # `ChatCopy.kt` 的终态文案守卫（P1）
 *
 * ## 这一组测试为什么排在 `:ui` 所有补测的第一位
 *
 * 第 3 轮修的那批"给用户假信息"的缺陷，**没有一条**是 `ChatCopy.kt` 的逻辑写错 ——
 * `when` 分支一直是对的。坏的是**归因**：两组不同的事实被送进了同一句话里。
 *
 * | 曾经 | 界面说什么 | 事实 | 用户随之做什么 |
 * |---|---|---|---|
 * | `STORE_FAILED` | 模型推理失败 | 存储写不进去 | 换模型 / 清缓存，全部无效后认为 App 坏了 |
 * | `REQUEST_STORE_FAILED` | 回答生成好了，但没存下来 | 一个 token 都没生成 | 反复滚动找一段不存在的回答 |
 * | `IN_FLIGHT` | 安全策略拦下了这一步 | 上一轮还在占用唯一那个推理焦点 | 去设置里翻一个不存在的开关 |
 *
 * 这些都不是拼写错误，所以**谁都可能在整理 `when` 的时候悄悄改回来**：
 * 九个终态、九份文案，抽一个公共函数、顺手复用一下 `else`、把 `_title` 合并成一条，
 * 表就坏了一半，而编译一声不响。这里的每一条用例都对着**上表的一行**。
 *
 * ## 三条判据
 *
 * 1. **互异**：九个终态必须落到九份不同的文案（标题与下一步各自互异）。共享即错；
 * 2. **可恢复性**：`retryable` 按各终态自己的语义取值，不跟着"看着像失败"走；
 * 3. **音量**：只有 `IN_FLIGHT` 允许是 `NOTICE`，其余一律 `ALERT`；
 *    以及最强的一条 —— **`IN_FLIGHT` 的文案里不许出现失败语义与安全语义的字**。
 *
 * ## 关于「为什么不能只比资源 id」
 *
 * `chatTerminalCopyOf` 返回的是 `R.string.xxx` 的**整型 id**。只比 id 能抓住第 1 条，
 * 但抓不住第 3 条：id 互异只证明两张表没有共用资源，不证明资源里的字是对的。
 * 所以禁词那几条经过 [UiStrings] 把 XML 里的字面文本读出来再断 ——
 * 改 `strings.xml` 也会红，那才是真正会被改坏的地方。
 */
class ChatCopyTest {

    // ══════════════════════════════════════════════ ① 互异性

    @Test
    fun resourceReaderReadsTheRealCopyNotAnEmptyMap() {
        // ★ 这条是后面所有"看字面文本"的测试的前提，不是充数。
        //   UiStrings 读不到东西时会抛错而不是返回空串；这条断言再钉一层：
        //   它读到的确实是那句真的（「安全策略」确实是 POLICY_DENIED 的标题）。
        //   少了这一步，后面所有"看字面文本"的断言都会变成永真的废话。
        assertThat(UiStrings.entries.size).isAtLeast(100)
        assertThat(UiStrings.text(R.string.chat_term_policy_denied_title)).contains("安全策略")
        assertThat(UiStrings.entries).isNotEmpty()
    }

    @Test
    fun everyReasonMapsToItsOwnTitleAndNextStep() {
        val copies = ChatTerminalReason.entries.associateWith { chatTerminalCopyOf(it) }

        copies.forEach { (reason, copy) ->
            assertWithMessage("$reason 没有映射到真实资源")
                .that(copy.titleRes).isNotEqualTo(0)
            assertWithMessage("$reason 缺「下一步」").that(copy.nextRes).isNotEqualTo(0)
        }

        val sharedTitles = copies.entries.groupBy({ it.value.titleRes }, { it.key })
            .filterValues { it.size > 1 }
        val sharedNexts = copies.entries.groupBy({ it.value.nextRes }, { it.key })
            .filterValues { it.size > 1 }

        assertWithMessage(
            "有两个终态共用同一句标题 —— 这正是「给用户假信息」的源头。" +
                "每个终态的事实不同，共用的那句必然对其中一半是假的。"
        ).that(sharedTitles).isEmpty()
        assertWithMessage("有两个终态共用同一句「下一步」").that(sharedNexts).isEmpty()
    }

    @Test
    fun storeFailureIsNotReportedAsAModelFailure() {
        val model = chatTerminalCopyOf(ChatTerminalReason.MODEL_FAILED)
        val store = chatTerminalCopyOf(ChatTerminalReason.STORE_FAILED)

        assertThat(store.titleRes).isNotEqualTo(model.titleRes)
        assertThat(store.nextRes).isNotEqualTo(model.nextRes)
        // 存不下去这件事跟模型无关：归因必须落在存储上 —— 不许把人支使到模型页去。
        assertThat(UiStrings.text(store.nextRes)).contains("存储")
        assertThat(UiStrings.text(store.nextRes)).doesNotContain("模型页")
        assertThat(UiStrings.text(model.nextRes)).contains("模型页")
    }

    @Test
    fun requestStoreFailureIsNotReportedAsAFinishedButUnsavedAnswer() {
        val store = chatTerminalCopyOf(ChatTerminalReason.STORE_FAILED)
        val request = chatTerminalCopyOf(ChatTerminalReason.REQUEST_STORE_FAILED)

        assertThat(request.titleRes).isNotEqualTo(store.titleRes)
        assertThat(request.nextRes).isNotEqualTo(store.nextRes)
        // STORE_FAILED 的那句承诺"回答已经有了"，REQUEST_STORE_FAILED 必须把这句话收回去 ——
        // 一个 token 都没生成，用户去找那段回答是找不到的。
        assertThat(UiStrings.text(request.titleRes)).doesNotContain("没存下来")
    }

    @Test
    fun queueingIsNotReportedAsAPolicyDenial() {
        val policy = chatTerminalCopyOf(ChatTerminalReason.POLICY_DENIED)
        val inFlight = chatTerminalCopyOf(ChatTerminalReason.IN_FLIGHT)

        assertThat(inFlight.titleRes).isNotEqualTo(policy.titleRes)
        assertThat(inFlight.nextRes).isNotEqualTo(policy.nextRes)
        // 反向 sanity：`POLICY_DENIED` 确实说的是策略 —— 证明这两句各自站在自己那一侧。
        assertThat(UiStrings.text(policy.titleRes)).contains("安全策略")
    }

    // ══════════════════════════════════════════════ ② retryable

    /**
     * `retryable` 的含义是「把同一句话再发一次会不会有不同的结果」，
     * 而不是「这次是不是失败了」。
     *
     * - `BUDGET_EXHAUSTED` / `POLICY_DENIED` 是 false：同一句话再问一遍只会撞同一面墙
     *   （预算还是那么多、策略还是那条），给按钮等于让用户对着红灯踩油门；
     * - 两个存储终态是 true：磁盘满 / 迁移未完成是**瞬时**故障，重来一次是有意义的；
     * - `IN_FLIGHT` 是 true：这句话模型一个字都没读过，重发就是它唯一的出路。
     */
    @Test
    fun retryableFollowsWhetherRepeatingTheTurnCanChangeAnything() {
        val expected = mapOf(
            ChatTerminalReason.COMPLETED to false,
            ChatTerminalReason.USER_ABORT to false,
            ChatTerminalReason.MODEL_FAILED to true,
            ChatTerminalReason.BUDGET_EXHAUSTED to false,
            ChatTerminalReason.POLICY_DENIED to false,
            ChatTerminalReason.SKILL_FAILED to true,
            ChatTerminalReason.MODEL_NOT_LOADED to true,
            ChatTerminalReason.STORE_FAILED to true,
            ChatTerminalReason.REQUEST_STORE_FAILED to true,
            ChatTerminalReason.IN_FLIGHT to true,
        )

        // 先保证这段代码自己没少写一格：新增第 10 个终态时这里必须显式给出取值，
        // 不许默认落在某个分支上。
        assertThat(expected.keys).containsExactlyElementsIn(ChatTerminalReason.entries)

        ChatTerminalReason.entries.forEach { reason ->
            assertWithMessage("$reason.retryable")
                .that(chatTerminalCopyOf(reason).retryable)
                .isEqualTo(expected.getValue(reason))
        }
    }

    // ══════════════════════════════════════════════ ③ 音量（tone）

    /** 见 [ChatTerminalTone]：默认值是 `ALERT`，只有"真的没出事"才降下来。 */
    @Test
    fun onlyInFlightSpeaksNeutral() {
        val neutral = ChatTerminalReason.entries.filter { chatTerminalCopyOf(it).tone == ChatTerminalTone.NOTICE }

        assertThat(neutral).containsExactly(ChatTerminalReason.IN_FLIGHT)
    }

    @Test
    fun inFlightIsNoticeNotAlert() {
        assertThat(chatTerminalCopyOf(ChatTerminalReason.IN_FLIGHT).tone)
            .isEqualTo(ChatTerminalTone.NOTICE)
    }

    // ══════════════════════════════════════════════ ④ IN_FLIGHT 的禁词

    /**
     * ★ 本次补测里最重要的一条。
     *
     * `IN_FLIGHT` 的事实是：**没有任何东西失败，只是还没轮到** ——
     * 推理焦点只有一个，上一轮还占着，这一轮排队都没排上。
     * 一次裁决都没发生。
     *
     * 所以它的文案里不允许出现任何"被一个什么东西挡住"的字眼：
     * 用户读到"拦截 / 策略 / 权限"，就会去四处找一个开关来解决一个不存在的问题。
     * 这条以前真的坏过（曾经复用 `POLICY_DENIED` 那两句），而且坏在 `strings.xml` 里 ——
     * Kotlin 代码没动，穷尽性检查一声没响。
     */
    @Test
    fun inFlightCopyNeverSaysFailureErrorOrSecurity() {
        val copy = chatTerminalCopyOf(ChatTerminalReason.IN_FLIGHT)
        val spoken = UiStrings.text(copy.titleRes) + UiStrings.text(copy.nextRes)

        // ★ 报错信息里带上整句话，不是装饰：下次失败的人得看见那句到底写成什么样，
        //   而不是只知道"有个词被撞到了"。
        BANNED_IN_FLIGHT_WORDS.forEach { word ->
            assertWithMessage(
                "IN_FLIGHT 的文案里出现了「$word」 —— 排队不是失败、更不是被拦下。" +
                    "\n  实际：(title=${UiStrings.text(copy.titleRes)}) (next=${UiStrings.text(copy.nextRes)})"
            ).that(spoken).doesNotContain(word)
        }
    }

    /** 反向对照：同一个词表用在 `POLICY_DENIED` 上应该是**命中**的。 */
    @Test
    fun theBannedWordListReallyHasTeeth() {
        val policy = chatTerminalCopyOf(ChatTerminalReason.POLICY_DENIED)
        val spoken = UiStrings.text(policy.titleRes) + UiStrings.text(policy.nextRes)

        assertThat(BANNED_IN_FLIGHT_WORDS.filter { spoken.contains(it) })
            .containsAtLeast("安全", "策略")
    }

    @Test
    fun everyTerminalCopyActuallySaysSomething() {
        ChatTerminalReason.entries.forEach { reason ->
            val copy = chatTerminalCopyOf(reason)
            assertWithMessage("$reason 标题为空").that(UiStrings.text(copy.titleRes)).isNotEmpty()
            assertWithMessage("$reason 缺下一步").that(UiStrings.text(copy.nextRes)).isNotEmpty()
        }
    }

    // ══════════════════════════════════════════════ 消息状态徽标

    /** `COMPLETE` 必须是 null：一条正常说完的消息不该在气泡上贴「已完成」。 */
    @Test
    fun messageStatusLabelsAreDistinctAndSilentWhenComplete() {
        assertThat(messageStatusLabelRes(MessageStatus.COMPLETE)).isNull()

        val labels = listOf(
            MessageStatus.STREAMING to messageStatusLabelRes(MessageStatus.STREAMING),
            MessageStatus.ABORTED to messageStatusLabelRes(MessageStatus.ABORTED),
            MessageStatus.FAILED to messageStatusLabelRes(MessageStatus.FAILED),
        )
        labels.forEach { (status, res) ->
            assertWithMessage("$status 缺徽标").that(res).isNotNull()
        }
        assertThat(labels.map { it.second }.toSet()).hasSize(3)
    }

    private companion object {
        /**
         * `IN_FLIGHT` 文案的禁止词。
         *
         * 前六个是直接从既定规则搬来的（失败 / 出错 / 被拒 / 拦截 / 安全 / 权限），
         * 后面几个是它们的邻近说法 —— 只写前六个的话，把文案改成"策略禁止了你这句话"
         * 就能绕过去，而那和"安全策略拦下了这一步"是同一句谎话。
         */
        val BANNED_IN_FLIGHT_WORDS: List<String> = listOf(
            "失败", "出错", "错误", "被拒", "拒绝", "拦截", "安全", "权限", "策略", "禁止", "未授权",
        )
    }
}
