package com.ake.agent.context

import com.ake.core.common.Outcome
import com.ake.core.contract.DomStats
import com.ake.core.contract.PageDigest
import com.ake.core.contract.PageState
import com.ake.core.contract.PocketDom
import com.ake.core.contract.ScrollInfo
import com.ake.core.contract.StepAction
import com.ake.core.contract.UiElement
import com.ake.core.ports.ActionContext
import com.ake.core.ports.ContentSource
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.Sanitized
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P1 · 上下文预算的**基数**（★ 这是「进程会不会被 native abort 打死」的上游）
//
// ## 起因
//
// `SegmentCaps.topKFor` 里写死「约 **58** token/候选」，且**没有任何测试**。
// 它被反推出来的痕迹很明显：`2816 / 48 ≈ 58.7` —— 是为了在 STANDARD 上凑出文档里的
// 「Top-K 48」这个值，而不是由候选的真实体积推出来的。
//
// 于是 `48 × 58 = 2,784`，刚好压在 STANDARD 的 `observation = 2,816` 之下 ——
// **账面上永远装得下**，这才是它能错这么久的唯一原因。
//
// ## 真实基数
//
// 唯一真会进 prompt 的路径是 `ContextAssembler.buildObservation → PocketDomSerializer.render`
// （`InjectionIsolator.isolateCandidates` 今天没有生产调用方，见下面的机械断言）。
// `PocketDomSerializer.renderCandidate` 渲染 **4 个**属性字段
// （`type` / `placeholder` / `value` / `aria-label`），每个上限 `MAX_TEXT = 48`：
//
//   `  [48] textarea[<48>] "<48>" ph="<48>" value="<48>" aria-label="<48>"     ::type,submit\n`
//   = **308 字符** = **110 token**（按 `:llm/TokenEstimator` 口径）
//
// 本文件把这个数**重新量一遍**并钉住 —— 改渲染格式 / 改 `MAX_TEXT` /
// 改 `:llm` 的 token 口径，这里立刻红。
// ===========================================================================

class ContextBudgetTest {

    // ──────────────────────────────────────────── ① 基数：最坏候选的体积

    @Test
    fun worstCaseCandidateLineIsPinnedInCharacters() {
        val perLine = measureSerializerLine { "x".repeat(it) }
        assertWithMessage(
            "最坏候选行字符数变了。`SegmentCaps.WORST_CASE_TOKENS_PER_CANDIDATE` 必须按新值重算 —— " +
                "它就是按这个字符数反推出来的；字符数变了而常数不变 ⇒ 预算又开始骗人",
        ).that(perLine.length).isEqualTo(SegmentCaps.WORST_CASE_CHARS_PER_CANDIDATE)
    }

    @Test
    fun worstCaseTokensPerCandidateIsPinnedAndMatchesTheConstant() {
        val measured = Tokens.estimate(measureSerializerLine { "x".repeat(it) })

        assertWithMessage(
            "最坏候选 token 数变了（按 :llm/TokenEstimator 口径实测）。" +
                "`SegmentCaps.WORST_CASE_TOKENS_PER_CANDIDATE` 必须改成这个新值",
        ).that(measured).isEqualTo(SegmentCaps.WORST_CASE_TOKENS_PER_CANDIDATE)

        // ★ 顺手钉住「58 到底错在哪」：它反推只有约 160 字符/候选，
        //   即「只有文本、没有属性」的一条候选 —— 而真实行有 4 个属性字段（192 字符）。
        assertWithMessage("旧的 58 token/候选 只有真实值的约 53% —— 它被证伪了")
            .that(measured).isGreaterThan(58)
    }

    @Test
    fun cjkContentCostsMoreThanLatinAndIsPinned() {
        // `TokenEstimator` 对中日韩字符按 **1 token/字** 计 —— 同一个 308 字符的行，
        // 全 CJK 时贵 2.7 倍（240 个 CJK 字符按 1 token/字 计）。
        // 这是**已知残留**：任何有限的 topK 都挡不住（1 条就可能 294 token），
        // 那部分由 `ContextAssembler.buildObservation` 的 OBSERVATION `tailCut` 收口。
        val latin = Tokens.estimate(measureSerializerLine { "x".repeat(it) })
        val cjk = Tokens.estimate(measureSerializerLine { "汉".repeat(it) })
        assertWithMessage("全 CJK 的最坏候选").that(cjk).isEqualTo(CJK_WORST_CASE_TOKENS_PER_CANDIDATE)
        assertWithMessage("CJK 必须比拉丁贵 —— 否则估算器没把 1 token/字 算进去")
            .that(cjk).isGreaterThan(latin)
    }

    // ──────────────────────────────────────────── ② topK 与 observation cap 的关系

    @Test
    fun topKFitsInsideTheObservationSegmentAtWorstCase() {
        // ★ 这是「预算」二字的字面含义：满配档放进 prompt 的候选，
        //   在最坏体积下**也必须**装得进 OBSERVATION 段。
        for (caps in listOf(SegmentCaps.NARROW, SegmentCaps.STANDARD, SegmentCaps.WIDE)) {
            val k = caps.topKFor(0)
            val cost = k * SegmentCaps.WORST_CASE_TOKENS_PER_CANDIDATE
            assertWithMessage("observation=${caps.observation} ⇒ topK=$k ⇒ 最坏 $cost token")
                .that(cost).isAtMost(caps.observation)
        }
    }

    @Test
    fun fortyEightCandidatesAtTheTrueCostBlowsNarrowAndStandard() {
        // ★ 邝随行的问题：48 条候选 × 真值 = 会不会击穿？
        //   会 —— NARROW 3.4×、STANDARD 1.88×。WIDE（6,656）反而装得下。
        val cost = 48 * SegmentCaps.WORST_CASE_TOKENS_PER_CANDIDATE
        assertThat(cost).isEqualTo(5_280)
        assertThat(cost).isGreaterThan(SegmentCaps.NARROW.observation)
        assertThat(cost).isGreaterThan(SegmentCaps.STANDARD.observation)
        assertThat(cost).isAtMost(SegmentCaps.WIDE.observation)

        // ★ 为什么五年没人发现：按旧的 58 算，48 条 = 2,784，**刚好**压在 2,816 之下。
        assertWithMessage("旧基数 58 让 48 条在账面上刚好装得下 —— 这正是它能错这么久的原因")
            .that(48 * 58).isAtMost(SegmentCaps.STANDARD.observation)
    }

    @Test
    fun theFullAttributePayloadFigureIsEvenWorseThanTheRenderedLine() {
        // ★ 邝随行在 `:browser` 算出的真值：18 项白名单全命中 × 48 字 + 48 字文本
        //   = 912 字符/候选 ⇒ 48 条 = **43,776 字符**。
        //   那是 `UiElement` 的**载荷**体积（浏览器侧口径），比真进 prompt 的渲染行还大 ——
        //   因为 `InjectionIsolator.isolateCandidates` 会把这 18 项全部渲染成 `k=v`。
        //   （白名单从 JS asset **机械抽取**，不在 Kotlin 里手抄第二份，理由同 :browser。）
        val whitelist = attrWhitelistFromJsAsset()
        val perCandidate = UiElement.MAX_TEXT_LEN + whitelist.size * UiElement.MAX_TEXT_LEN
        assertWithMessage("白名单 ${whitelist.size} 项 ⇒ 单候选载荷 $perCandidate 字符")
            .that(perCandidate).isEqualTo(912)
        assertWithMessage("48 条 × 912 字符").that(48 * perCandidate).isEqualTo(43_776)

        val asTokens = Tokens.estimate("x".repeat(43_776))
        for (caps in listOf(SegmentCaps.NARROW, SegmentCaps.STANDARD, SegmentCaps.WIDE)) {
            assertWithMessage("43,776 字符 ≈ $asTokens token，连 WIDE 也装不下")
                .that(asTokens).isGreaterThan(caps.observation)
        }
    }

    // ──────────────────────────────────────────── ③ 公理：基数只能来自「真进 prompt 的那条路径」

    @Test
    fun isolateCandidatesHasNoProductionCaller() {
        // ★ 机械断言，理由同 `WORST_CASE_TOKENS_PER_CANDIDATE` 的诚实边界：
        //   `InjectionIsolator.isolateCandidates` 渲染**全部**白名单属性（最坏 406 token/候选），
        //   一旦它被接进 prompt，110 这个基数当场作废。
        //   盯住「它没有生产调用方」，比在注释里写一句「今天没人调它」可靠。
        val callers = repoMainKotlinSources()
            .filter { it.readText().stripKotlinComments().contains("isolateCandidates") }
            .map { it.relativeTo(repoRoot()).invariantSeparatorsPath }
            .toSortedSet()

        assertWithMessage(
            "`isolateCandidates` 出现了生产调用方 ⇒ 每候选最坏变成 " +
                "${Tokens.estimate(measureIsolatorLine())} token，" +
                "`SegmentCaps.WORST_CASE_TOKENS_PER_CANDIDATE` 必须同步改，否则预算又开始骗人",
        ).that(callers).containsExactly("agent/src/main/java/com/ake/agent/context/InjectionIsolator.kt")
    }

    @Test
    fun isolateCandidatesIsBoundedPerField_likeTheSerializer() {
        // 漏项已补：每个字段按 `UiElement.MAX_TEXT_LEN` 截断（与 `PocketDomSerializer` 同口径）。
        // 不存在的上限 ⇒ 「每行字数可控」不成立 ⇒ 预算无从谈起。
        val line = measureIsolatorLine()
        assertWithMessage("全部白名单属性 + 48 字文本的隔离行（含换行）").that(line.length).isEqualTo(1_137)
        assertWithMessage("这条路径的最坏成本被钉住，供「若将来接进 prompt」时换算基数用")
            .that(Tokens.estimate(line)).isEqualTo(407)
    }

    // ──────────────────────────────────────────── ④ 降级阶梯

    @Test
    fun degradationLadderIsMonotonicAndNeverBelowTheFloor() {
        for (caps in listOf(SegmentCaps.NARROW, SegmentCaps.STANDARD, SegmentCaps.WIDE)) {
            val l0 = caps.topKFor(0)
            val l1 = caps.topKFor(1)
            val l2 = caps.topKFor(2)
            val l3 = caps.topKFor(3)
            assertWithMessage("observation=${caps.observation} ⇒ $l0 / $l1 / $l2 / $l3")
                .that(l0).isAtLeast(l1)
            assertThat(l1).isAtLeast(l2)
            assertThat(l2).isAtLeast(l3)
            assertThat(l3).isEqualTo(6)
            assertThat(l2).isEqualTo(SegmentCaps.MIN_TOP_K)
            assertThat(l0).isAtMost(SegmentCaps.MAX_TOP_K)
        }
    }

    // ──────────────────────────────────────────── 工具

    /** 最坏候选：文本与**全部**白名单属性都顶满 `MAX_TEXT_LEN`。 */
    private fun worstCaseElement(id: Int = 48, fill: (Int) -> String): UiElement {
        val n = UiElement.MAX_TEXT_LEN
        val attrs = LinkedHashMap<String, String>()
        for (name in attrWhitelistFromJsAsset()) attrs[name] = fill(n)
        return UiElement(
            id = id,
            tag = "textarea",
            role = "button",
            text = fill(n),
            attrs = attrs,
            states = emptySet(),
            rect = null,
            depth = 3,
            score = 1.0f,
            stableKey = "worst-$id",
            selector = "#worst-$id",
            xpath = "",
        )
    }

    /** `PocketDomSerializer` 渲染出的**单条候选行**（含缩进与换行）。 */
    private fun measureSerializerLine(fill: (Int) -> String): String {
        val text = PocketDomSerializer().render(
            domWith(listOf(worstCaseElement(fill = fill))), topK = 1, recordRows = 0,
        )
        val lines = text.lines().filter { it.startsWith(CANDIDATE_LINE_PREFIX) }
        assertThat(lines).hasSize(1)
        return lines.single() + "\n"
    }

    /** `InjectionIsolator.isolateCandidates` 渲染出的**单条隔离行**（含换行）。 */
    private fun measureIsolatorLine(): String {
        val iso = InjectionIsolator(sanitizer = passthroughPolicy(), nonce = NonceSource.fixed("abcdefgh"))
        val lines = iso.isolateCandidates(listOf(worstCaseElement(fill = { "x".repeat(it) })))
            .rendered.lines().filter { it.startsWith("[48] ") }
        assertThat(lines).hasSize(1)
        return lines.single() + "\n"
    }

    private fun domWith(candidates: List<UiElement>): PocketDom = PocketDom(
        url = "https://shop.example.com/list",
        host = "shop.example.com",
        title = "t",
        pageState = PageState.READY,
        scroll = ScrollInfo(0, 1),
        outline = "div.app",
        candidates = candidates,
        records = null,
        signals = emptySet(),
        warnings = emptySet(),
        digest = PageDigest("d"),
        stats = DomStats(1, 1, 1f, 1),
    )

    private fun passthroughPolicy(): PolicyPort = object : PolicyPort {
        override fun sanitize(raw: String, source: ContentSource): Sanitized =
            Sanitized(text = raw, redacted = false, flagged = false, tripwires = 0)

        override suspend fun authorize(action: StepAction, ctx: ActionContext): Outcome<PolicyVerdict> =
            Outcome.Ok(PolicyVerdict.Allow)
    }

    private fun repoRoot(): java.io.File =
        generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }

    /** 剥掉 Kotlin 注释 —— KDoc 里提到某个函数名不算调用它。 */
    private fun String.stripKotlinComments(): String =
        replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines().joinToString("\n") { it.replace(Regex("(?<!:)//.*$"), "") }

    private fun repoMainKotlinSources(): List<java.io.File> = repoRoot().walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
        .toList()

    /**
     * 从 `browser/src/main/assets/js/dom-extract.js` **机械抽取** `ATTR_WHITELIST`。
     * ★ 不在 Kotlin 里手抄第二份 —— 手抄的那份会停在旧项数上，最坏体积被系统性低估
     *   （历史上：抄的 14 项 vs JS 的 18 项 ⇒ 少算 9,216 字符）。
     */
    private fun attrWhitelistFromJsAsset(): List<String> {
        val src = java.io.File(repoRoot(), "browser/src/main/assets/js/dom-extract.js").readText()
        val marker = "var ATTR_WHITELIST = ["
        val start = src.indexOf(marker)
        check(start >= 0) { "`dom-extract.js` 里找不到 `$marker` —— 机械抽取器要跟着改" }
        val end = src.indexOf(']', startIndex = start)
        check(end > start) { "`ATTR_WHITELIST` 数组没有闭合" }
        val names = Regex("""'([^']+)'""").findAll(src.substring(start, end)).map { it.groupValues[1] }.toList()
        check(names.isNotEmpty()) { "`ATTR_WHITELIST` 抽出来是空的" }
        return names
    }

    /**
     * 与 `:llm/TokenEstimator` **同口径**的 token 估算。
     *
     * `:agent` 编译期看不见 `:llm`（架构守卫），所以这里从 `:llm` 的源码里
     * **机械抽取**那几个标定常数，而不是再抄一份 —— 抄一份就等于让「:llm 改了口径」
     * 这件事在本模块静默失效，那正是本次要修的病。
     */
    private object Tokens {
        private val LATIN: Double = llmConst("LATIN_CHARS_PER_TOKEN")
        private val DIGIT: Double = llmConst("DIGIT_CHARS_PER_TOKEN")
        private val CONSERVATIVE: Double = llmConst("CONSERVATIVE_FACTOR")
        private val NOISE_PER_100: Double = llmConst("JSON_NOISE_PER_100")

        fun estimate(text: String): Int {
            if (text.isEmpty()) return 0
            var tokens = 0.0
            var asciiRun = 0
            var lastWasDigit = false
            fun flushAscii() {
                if (asciiRun > 0) {
                    tokens += asciiRun / if (lastWasDigit) DIGIT else LATIN
                    asciiRun = 0
                }
            }
            for (c in text) {
                when {
                    isCjk(c) -> { flushAscii(); tokens += 1.0 }
                    c.isDigit() -> { if (!lastWasDigit) flushAscii(); asciiRun++; lastWasDigit = true }
                    c.code < 128 -> { asciiRun++; lastWasDigit = false }
                    else -> { flushAscii(); tokens += 0.5 }
                }
            }
            flushAscii()
            val raw = (tokens + text.length / 100.0 * NOISE_PER_100) * CONSERVATIVE
            return raw.toInt().coerceAtLeast(if (text.isBlank()) 0 else 1)
        }

        private fun isCjk(c: Char): Boolean = c in '\u4E00'..'\u9FFF' ||
            c in '\u3400'..'\u4DBF' ||
            c in '\u3040'..'\u30FF' ||
            c in '\uAC00'..'\uD7AF'

        private fun llmConst(name: String): Double {
            val f = java.io.File(
                generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
                    .first { java.io.File(it, "settings.gradle.kts").isFile },
                "llm/src/main/java/com/ake/llm/TokenEstimator.kt",
            )
            check(f.isFile) { "找不到 :llm 的 TokenEstimator —— 预算基数失去标定来源，别默默退化" }
            val m = Regex("""private const val $name = ([\d.]+)""").find(f.readText())
            check(m != null) { "`TokenEstimator` 里找不到常数 $name —— 抽取器要跟着改" }
            return m.groupValues[1].toDouble()
        }
    }

    private companion object {
        /** 候选行的缩进前缀（`render` 里写的是两个空格）。 */
        const val CANDIDATE_LINE_PREFIX = "  ["

        /** 全 CJK 内容的最坏候选 token 数（同一个 308 字符，1 token/字）。已知残留，由 tailCut 收口。 */
        const val CJK_WORST_CASE_TOKENS_PER_CANDIDATE: Int = 294
    }
}
