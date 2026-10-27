package com.ake.browser

import com.ake.core.contract.ElementState
import com.ake.core.contract.UiElement
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P1 · DOM 抽取与压缩（咽喉项）
//
// 这一份守的是「模型到底看见了什么」。失败模式全是静默的：
// 排序错了 ⇒ 目标元素根本不在 Top-K 里；去重错了 ⇒ 同一个元素拿两个编号；
// 可见性丢了 ⇒ 模型去点一个看不见的东西。没有一条会让 CI 变红，
// 只有用户觉得「这 App 有点怪」。
//
// ★ golden 权重的说明：`expectedScore()` 把 §5.5 的权重表抄了一遍。
//   它是**故意重复**的 —— 权重一改，这里立刻红，逼着改的人重新过一遍下面的
//   「排序 / 可见性 / 去重」断言（这正是 DomJsonParser.score 注释里说的「用数据调，不用直觉调」）。
// ===========================================================================

class DomExtractionTest {

    // ─────────────────────────────────────────────────────── ⑤ 编号与 ④ 评分的顺序

    @Test
    fun idsAreAssignedAfterTopKTruncation_notDomOrder() {
        val json = dom(
            candidate(text = "弱候选", weight = 0.05, selector = "div.a>li.x"),
            candidate(text = "强候选", weight = 1.0, selector = "div.b>input.search"),
            candidate(text = "中候选", weight = 0.4, selector = "div.c>a.y"),
        )
        val els = DomJsonParser.parse(json, topK = 2).candidates

        // 先评分排序、再截断、最后编号 ⇒ DOM 顺序第 1 个的元素被挤出去后，编号必须连续
        assertThat(els.map { it.tag to it.text }).containsExactly(
            "button" to "强候选",
            "button" to "中候选",
        ).inOrder()
        assertThat(els.map { it.id }).containsExactly(1, 2).inOrder()
        assertThat(els.first().text).isEqualTo("强候选")
    }

    @Test
    fun topKGateKeepsEveryElementWhenUnderLimit() {
        val json = dom(candidate(), candidate(selector = "div.pdp>button.b"))
        assertThat(DomJsonParser.parse(json, topK = 48).candidates).hasSize(2)
        assertThat(DomJsonParser.parse(json, topK = 0).candidates).isEmpty()
    }

    @Test
    fun negativeTopKIsClampedToEmpty_notAThrownException() {
        // ⚠（原失败）修复前 `Takeable.take(-1)` 直接抛 IllegalArgumentException。
        //   选 **clamp 而不是 reject**：`candidateTopK` 是从 `ObserveOptions` 一路传下来的
        //   **配置项**，不是调用方的逻辑错误；一个负数配置让整个 observe 挂掉，
        //   症状是「这一页什么都抽不到且没有解释」，比「抽到 0 个候选」更难归因。
        //   clamp 到 0 保留了「这一轮确实被限成空了」这一可诊断事实。
        val json = dom(candidate())
        val parsed = DomJsonParser.parse(json, topK = -1)
        assertThat(parsed.candidates).isEmpty()
        assertThat(parsed.pageState).isEqualTo(com.ake.core.contract.PageState.READY)
        // 正数语义不变
        assertThat(DomJsonParser.parse(json, topK = 1).candidates).hasSize(1)
        assertThat(DomJsonParser.parse(json, topK = 0).candidates).isEmpty()
    }

    @Test
    fun equalScoresKeepDomOrder_sortIsStable() {
        val twin = candidate(text = "同一个", weight = 0.9, selector = "div.pdp>button.twins")
        val first = twin + ("attrs" to mapOf("class" to "first"))
        val second = twin + ("attrs" to mapOf("class" to "second"))
        val els = DomJsonParser.parse(dom(first, second), topK = 4).candidates

        assertThat(els[0].score).isEqualTo(els[1].score)
        // 同分时排序必须稳定，否则同一页两次观察会给出两套编号 ⇒ 编号漂移
        assertThat(els[0].attrs["class"]).isEqualTo("first")
        assertThat(els[1].attrs["class"]).isEqualTo("second")
    }

    // ───────────────────────────────────────────── 评分表 golden lock

    @Test
    fun goldenScoreMatchesDocumentedWeightTable() {
        val els = DomJsonParser.parse(dom(candidate()), topK = 4).candidates
        assertThat(els).hasSize(1)
        val expected = expectedScore(
            visibility = 1.0f, weight = 0.9f, textfulness = 1.0f, size = 1.0f,
            context = 0f, aria = 1.0f, depth = 5,
        )
        assertThat(els.single().score).isWithin(1e-5f).of(expected)
    }

    @Test
    fun offscreenElementIsHeavilyPenalised() {
        val on = DomJsonParser.parse(dom(candidate(offscreen = false)), topK = 2).candidates.single()
        val off = DomJsonParser.parse(dom(candidate(offscreen = true)), topK = 2).candidates.single()
        // 0.30 * (1.0 - 0.05) 的差值
        assertThat(on.score - off.score).isWithin(1e-5f).of(0.285f)
        assertThat(off.states).contains(ElementState.OFFSCREEN)
    }

    @Test
    fun singleCharacterTextLosesTextfulness() {
        val one = DomJsonParser.parse(dom(candidate(text = "×")), topK = 2).candidates.single()
        val two = DomJsonParser.parse(dom(candidate(text = "×2")), topK = 2).candidates.single()
        // 「加入购物车」这类按钮常有单字符变体（×/+/…），长度 <2 一律判 0.2 ⇒ 掉 0.12
        assertThat(two.score - one.score).isWithin(1e-5f).of(0.15f * 0.8f)
    }

    @Test
    fun blankTextLosesTextfulness() {
        val blank = DomJsonParser.parse(dom(candidate(text = "   ")), topK = 2).candidates.single()
        val real = DomJsonParser.parse(dom(candidate(text = "OK")), topK = 2).candidates.single()
        assertThat(real.score - blank.score).isWithin(1e-5f).of(0.15f * 0.8f)
    }

    @Test
    fun zeroWidthRectIsKeptBecausePositionIsWhatTheModelNeeds() {
        // ⚠（原失败）修复前 `rect` 的判据只看 `w > 0` ⇒ 一个 h=44、w=0 的竖条
        //   把**整条 rect** 丢掉 ⇒ `UiElement.rect = null` ⇒ 模型既拿不到位置，
        //   也看不到这个可点目标（overlay / label 包裹 / 分隔线按钮里这是常见形状）。
        //   JS 侧 `isVisible` 已经排掉了 w 与 h **同时**为 0 的元素，Kotlin 不该再砍一刀。
        val zero = DomJsonParser.parse(dom(candidate(rect = mapOf("x" to 0, "y" to 0, "w" to 0, "h" to 44))), topK = 2)
            .candidates.single()

        assertThat(zero.rect).isNotNull()
        assertThat(zero.rect!!.h).isEqualTo(44)
        assertThat(zero.rect!!.w).isEqualTo(0)

        // ★ 补回的是**位置信息**，不是分数：sizeReasonableness 仍按 w<24 给 0 分，
        //   所以排序不受这条改动影响（Top-K 的相对顺序不变）。
        val wide = DomJsonParser.parse(dom(candidate(rect = USUAL_RECT)), topK = 2).candidates.single()
        assertThat(wide.score - zero.score).isWithin(1e-5f).of(0.10f)
    }

    @Test
    fun aRectThatIsZeroInBothAxesIsStillDropped() {
        // 只有「宽高同时为 0」才丢 —— 与 JS 侧 isVisible 的口径保持一致
        val none = DomJsonParser.parse(dom(candidate(rect = mapOf("x" to 0, "y" to 0, "w" to 0, "h" to 0))), topK = 2)
            .candidates.single()
        assertThat(none.rect).isNull()
    }

    @Test
    fun oversizedElementGetsPartialSizeCredit() {
        val huge = DomJsonParser.parse(
            dom(candidate(rect = mapOf("x" to 0, "y" to 0, "w" to 4200, "h" to 900))), topK = 2,
        ).candidates.single()
        val normal = DomJsonParser.parse(dom(candidate(rect = USUAL_RECT)), topK = 2).candidates.single()
        assertThat(normal.score - huge.score).isWithin(1e-5f).of(0.10f * 0.7f)
    }

    @Test
    fun depthPenaltyIsCappedAtTwenty() {
        fun at(depth: Int) = DomJsonParser.parse(dom(candidate(depth = depth)), topK = 2).candidates.single().score
        assertThat(at(3) - at(4)).isWithin(1e-5f).of(0.02f)
        assertThat(at(20)).isWithin(1e-5f).of(at(45)) // 超过 20 层不再惩罚
    }

    @Test
    fun contextBonusMatchesWholeSelectorSegments_notSubstrings() {
        // ⚠（原失败）修复前对 selector 做的是 **子串** 包含（ignoreCase），
        //   `informal` / `platform` 都含 `form` ⇒ 与真正的 `<form>` 同分 (+0.10)。
        //   评分表里 context 只占 0.10 一档，误伤会**直接改变 Top-K 排序**。
        fun scoreOf(selector: String) =
            DomJsonParser.parse(dom(candidate(selector = selector)), topK = 2).candidates.single().score

        val neutral = scoreOf("div.page>button.a")
        val realForm = scoreOf("form.checkout>button.a")

        assertThat(realForm - neutral).isWithin(1e-5f).of(0.10f)
        // 误伤必须消失
        assertThat(scoreOf("div.informal-banner>button.a")).isEqualTo(neutral)
        assertThat(scoreOf("div.platform-row>button.a")).isEqualTo(neutral)
        assertThat(scoreOf("div.former-price>button.a")).isEqualTo(neutral)

        // 但真正的关键词一个都不能少（按片段等值命中）
        for (seg in listOf("form", "search", "nav", "pagination", "dialog", "list", "filter", "sort")) {
            assertWithMessage("片段 $seg 应命中 contextBonus")
                .that(scoreOf("div.x>button.$seg")).isEqualTo(realForm)
        }
    }

    @Test
    fun contextBonusIsCaseInsensitiveBecauseSelectorsAre() {
        fun scoreOf(selector: String) =
            DomJsonParser.parse(dom(candidate(selector = selector)), topK = 2).candidates.single().score
        assertThat(scoreOf("FORM.Checkout>button.A")).isEqualTo(scoreOf("form.checkout>button.a"))
    }

    @Test
    fun ariaLabelOrRoleGrantsBonus() {
        val plain = DomJsonParser.parse(dom(candidate(role = null)), topK = 2).candidates.single()
        val aria = DomJsonParser.parse(
            dom(candidate(role = null, attrs = mapOf("aria-label" to "搜索"))), topK = 2,
        ).candidates.single()
        assertThat(aria.score - plain.score).isWithin(1e-5f).of(0.07f)
    }

    // ───────────────────────────────────────────── 字段裁剪

    @Test
    fun textIsTruncatedToMaxTextLen() {
        val long = "x".repeat(200)
        val el = DomJsonParser.parse(dom(candidate(text = long)), topK = 2).candidates.single()
        assertThat(el.text).hasLength(UiElement.MAX_TEXT_LEN)
    }

    @Test
    fun titleIsTruncatedTo96Chars() {
        val parsed = DomJsonParser.parse(dom(candidate(), title = "T".repeat(300)), topK = 2)
        assertThat(parsed.title).hasLength(96)
    }

    @Test
    fun attrValuesAreTruncatedAndEmptyOnesDropped() {
        val el = DomJsonParser.parse(
            dom(candidate(attrs = mapOf("id" to "i".repeat(100), "alt" to "", "class" to "c"))), topK = 2,
        ).candidates.single()
        assertThat(el.attrs).hasSize(2)
        assertThat(el.attrs["id"]).hasLength(48)
        assertThat(el.attrs).doesNotContainKey("alt") // 空值被静态丢掉
    }

    @Test
    fun xpathIsNeverPopulatedByTheParser() {
        // 契约里 UiElement.xpath 写着「id > 稳定语义 class > nth-path」，
        // 但 JS 不产出、Kotlin 也不推导 ⇒ 恒为空。执行侧只能依赖 selector。
        val el = DomJsonParser.parse(dom(candidate()), topK = 2).candidates.single()
        assertThat(el.xpath).isEmpty()
    }

    @Test
    fun statesAreMappedAndUnknownStateKeysIgnored() {
        val el = DomJsonParser.parse(
            dom(candidate(states = mapOf("visible" to true, "focused" to true, "nope" to true))), topK = 2,
        ).candidates.single()
        assertThat(el.states).containsAtLeast(ElementState.VISIBLE, ElementState.FOCUSED)
        assertThat(el.states).containsNoDuplicates()
    }

    @Test
    fun roleBlankBecomesNull() {
        val el = DomJsonParser.parse(dom(candidate(role = "  ")), topK = 2).candidates.single()
        assertThat(el.role).isNull()
    }

    // ───────────────────────────────────────────── 去重

    @Test
    fun sameElementTwiceIsNotDeduped_identicalFingerprint_twoIds() {
        // ⚠ 发现：Kotlin 侧**没有按 stableKey 去重**。同一个元素被多个选择器命中
        //   （或 JS 侧重复 push）时，会拿到两个不同的 id 却指向同一个 DOM 节点。
        //   站在模型视角：编号 3 和编号 7 长得一模一样，点哪个都说得通 —— 这是
        //   「 CLI-quiet 编号漂移」的另一条来源，且不靠异常暴露。
        val twin = candidate(text = "加入购物车", selector = "div.pdp>button.pdp-cart")
        val els = DomJsonParser.parse(dom(twin, twin), topK = 8).candidates

        assertThat(els).hasSize(2)
        assertThat(els[0].stableKey).isEqualTo(els[1].stableKey)
        assertThat(els[0].id).isNotEqualTo(els[1].id)
        assertThat(els.map { it.id }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun sameElementUnderDifferentSelectorsYieldsDifferentFingerprints() {
        val a = candidate(selector = "div.pdp>button.pdp-cart")
        val b = candidate(selector = "#add-cart")
        val els = DomJsonParser.parse(dom(a, b), topK = 8).candidates

        assertThat(els).hasSize(2)
        assertThat(els[0].stableKey).isNotEqualTo(els[1].stableKey)
        assertThat(els.map { it.stableKey }.toSet()).hasSize(2)
    }

    @Test
    fun nonObjectEntriesInCandidatesArrayAreSkipped() {
        // JS 侧 try/catch 单个节点失败时可能留下 null 占位
        val root = org.json.JSONObject(
            mapOf(
                "url" to GOLDEN_URL,
                // 必须带 stats 段 —— 缺了会被判成「payload 被截断」（ERROR），
                // 见 DomEdgePageTest 的信封校验
                "stats" to mapOf("rawNodeCount" to 3, "prunedNodeCount" to 3, "serializedChars" to 100),
                "candidates" to listOf(null, candidate(text = "有效"), "not-an-object"),
            ),
        ).toString()
        val els = DomJsonParser.parse(root, topK = 8).candidates
        assertThat(els.map { it.text }).containsExactly("有效")
    }

    // ───────────────────────────────────────────── helper

    /**
     * §5.5 权重表的 golden 副本（见本文件头注释）。
     * 改 `DomJsonParser.score()` 必须同步改这里，并按注释里说的用真实任务数据复评。
     */
    private fun expectedScore(
        visibility: Float,
        weight: Float,
        textfulness: Float,
        size: Float,
        context: Float,
        aria: Float,
        depth: Int,
    ): Float = (0.30f * visibility +
        0.28f * weight +
        0.15f * textfulness +
        0.10f * size +
        0.10f * context +
        0.07f * aria +
        -0.02f * depth.coerceAtMost(20)).coerceIn(0f, 1f)
}
