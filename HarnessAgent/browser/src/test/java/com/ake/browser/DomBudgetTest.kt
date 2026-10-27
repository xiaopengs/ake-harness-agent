package com.ake.browser

import com.ake.core.contract.DomSignal
import com.ake.core.contract.PageState
import com.ake.core.contract.UiElement
import com.google.common.truth.Truth.assertThat
import org.junit.Test

// ===========================================================================
// P1 · 压缩率与 token 预算
//
// 架构 §5.2 给的目标是「一个 5,000–30,000 节点的真实电商页面 ⇒ ≤ 12 KB 文本 ≈ 2,800 token」，
// 并写明这不是审美：**每省 1,000 token，GPU 省 0.26 秒/步，CPU 省 1.8 秒/步**。
// 端侧 prefill 是这个产品唯一的物理瓶颈。
//
// 这一份要回答两件事：
//   ① 压缩率是怎么算的、它的口径边界在哪里（估错了会让上下文预算整盘失效）
//   ② topK 到底有没有把 12 KB 守住 —— 结论：没有，它只限了「条数」，没限「每条多大」
// ===========================================================================

class DomBudgetTest {

    // ── ① stats 口径

    @Test
    fun statsArePassedThroughVerbatimAndRatioIsComputedHere() {
        // ⚠ 注意口径：`compressionRatio` 是 **Kotlin 侧现算** 的 pruned/raw，
        //   而 `serializedChars` 是 **JS 侧** `JSON.stringify(result).length` 的原值。
        //   两者不是同一经济体：ratio 描述「砍掉了多少节点」，serializedChars 描述
        //   「过 JNI 之前那一坨字符串有多大」。拿 ratio 去估 token 是错的。
        val parsed = DomJsonParser.parse(
            dom(candidate(), stats = mapOf("rawNodeCount" to 20_000, "prunedNodeCount" to 500, "serializedChars" to 11_902)),
            topK = 8,
        )
        assertThat(parsed.stats.rawNodeCount).isEqualTo(20_000)
        assertThat(parsed.stats.prunedNodeCount).isEqualTo(500)
        assertThat(parsed.stats.compressionRatio).isWithin(1e-6f).of(500f / 20_000f)
        assertThat(parsed.stats.serializedChars).isEqualTo(11_902)
    }

    @Test
    fun compressionRatioIsHigherWhenLessPruned() {
        fun ratio(pruned: Int) = DomJsonParser.parse(
            dom(stats = mapOf("rawNodeCount" to 100, "prunedNodeCount" to pruned, "serializedChars" to 0)),
            topK = 8,
        ).stats.compressionRatio

        assertThat(ratio(90)).isGreaterThan(ratio(10))
    }

    @Test
    fun zeroRawNodesDoesNotDivideByZero() {
        val parsed = DomJsonParser.parse(
            dom(stats = mapOf("rawNodeCount" to 0, "prunedNodeCount" to 0, "serializedChars" to 0)),
            topK = 8,
        )
        assertThat(parsed.stats.compressionRatio).isEqualTo(0f)
        assertThat(parsed.stats.rawNodeCount).isEqualTo(0)
    }

    // ── ② topK 守不住 12 KB

    @Test
    fun topKDoesNotBoundSerializedSize_budgetIsNotEnforcedInBrowser() {
        // 构造最坏情况：48 条候选，每条文本顶满 48 字、每个白名单属性顶满 48 字。
        // §5.2 的 12 KB 目标是写在文档里的，但 :browser 里没有任何一处按字符预算截断 ——
        //   `take(topK)` 限的是**条数**，`take(UiElement.MAX_TEXT_LEN)` 只限了 **text** 一个字段。
        val attrs = ATTR_WHITELIST_ASSOCIATED_SIZE_TEST.associateWith { "a".repeat(48) }
        val parsed = DomJsonParser.parse(
            dom(
                *Array(TOP_K) { candidate(text = "t".repeat(UiElement.MAX_TEXT_LEN), attrs = attrs) },
            ),
            topK = TOP_K,
        )

        assertThat(parsed.candidates).hasSize(TOP_K)
        val payloadChars = parsed.candidates.sumOf { el ->
            el.text.length + el.attrs.values.sumOf { it.length }
        }
        val expected = TOP_K * (UiElement.MAX_TEXT_LEN + attrs.size * 48)
        assertThat(payloadChars).isEqualTo(expected)

        // ★ 结论要直说：48 条满配候选 = 34,560 字符，已经是 §5.2 目标的近 3 倍。
        //   上下文预算是在 :agent 的 ContextAssembler 侧收口的，:browser 这一层不兜这个数。
        assertThat(payloadChars).isGreaterThan(DOCUMENTED_BUDGET_CHARS)
    }

    @Test
    fun attrCountIsUnboundedSoAttrsDominateThePayload() {
        // attrs 的值被砍到 48，**但条目数没有上限**：白名单 14 项全命中时，
        // 单个候选的属性体积是文本体积的 14 倍。
        val el = DomJsonParser.parse(
            dom(candidate(text = "短文本", attrs = ATTR_WHITELIST_ASSOCIATED_SIZE_TEST.associateWith { "v".repeat(48) })),
            topK = 2,
        ).candidates.single()

        assertThat(el.attrs).hasSize(ATTR_WHITELIST_ASSOCIATED_SIZE_TEST.size)
        assertThat(el.attrs.values.sumOf { it.length }).isGreaterThan(el.text.length)
    }

    @Test
    fun recordsProvideTheCompactLaneForListPages() {
        // 「数量认知 + 干净数据」是给列表页的逃生通道：60 行 × 2 列 × ≤40 字 ≈ 4.8 KB，
        // 比把 60 个卡片的全套候选展开（同 topK 口径下远超 40 KB）小一个量级。
        val rows = List(60) { i -> mapOf("title" to "跑鞋 $i", "price" to "¥${399 + i}") }
        val parsed = DomJsonParser.parse(
            dom(records = records(columns = listOf("title", "price"), rows = rows, totalEstimate = 60)),
            topK = 48,
        )
        val chars = parsed.records!!.rows.sumOf { r -> r.values.sumOf { it.length } }
        assertThat(chars).isAtMost(DOCUMENTED_BUDGET_CHARS)
        assertThat(parsed.records!!.rows).hasSize(60)
    }

    // ── ③ digest / 编号的经济性

    @Test
    fun digestIsStableAndShort() {
        val a = DomJsonParser.parse(dom(candidate(), candidate(selector = "div.pdp>a.b")), topK = 8)
        val b = DomJsonParser.parse(dom(candidate(), candidate(selector = "div.pdp>a.b")), topK = 8)
        assertThat(a.digest).isEqualTo(b.digest)
        assertThat(a.digest.value).hasLength(20) // SHA-256 前 10 字节 ⇒ 20 个 hex
    }

    @Test
    fun digestChangesWhenCandidateSetOrOutlineChanges() {
        val base = DomJsonParser.parse(dom(candidate()), topK = 8).digest.value
        val plusOne = DomJsonParser.parse(dom(candidate(), candidate(selector = "div.pdp>a.share")), topK = 8).digest.value
        val otherOutline = DomJsonParser.parse(dom(candidate(), outline = "div.other"), topK = 8).digest.value
        assertThat(plusOne).isNotEqualTo(base)
        assertThat(otherOutline).isNotEqualTo(base)
    }

    @Test
    fun signalsAreNotDroppedWhenKnown() {
        val parsed = DomJsonParser.parse(
            dom(signals = listOf("HAS_FORM", "PAGINATION", "MODAL_OPEN", "RESULTS_COUNT_UNKNOWN")), topK = 8,
        )
        // 未知 signal 与未知 warning 一样被静默丢掉
        assertThat(parsed.signals).containsExactly(DomSignal.HAS_FORM, DomSignal.PAGINATION, DomSignal.MODAL_OPEN)
    }

    @Test
    fun scrollAndHostCarryIntoPocketDom() {
        val parsed = DomJsonParser.parse(dom(scrollY = 1200, maxY = 9800), topK = 8)
        assertThat(parsed.scroll.y).isEqualTo(1200)
        assertThat(parsed.scroll.maxY).isEqualTo(9800)
        assertThat(parsed.host).isEqualTo("shop.example.com")
    }

    // ── ④ host / pageState 的经济性（顺路锁住两个会悄悄丢信息的派生字段）

    @Test
    fun hostIsNormalisedLowercaseAndStrippedOfWww() {
        // ⚠（原失败·**安全问题**，见 HostNormalisationTest）修复前全程没有 lowercase，
        //   `removePrefix("www.")` 又对大小写敏感 ⇒ `WWW.Example.com` 既去不掉前缀
        //   也不会被归一化 ⇒ 与同一站点的另一种写法在「按 host 精确等值」的场景下
        //   算两个不同的域 —— 而这正是域名白/黑名单的匹配方式。
        assertThat(DomJsonParser.parse(dom(url = "https://www.example.com/a"), topK = 1).host).isEqualTo("example.com")
        assertThat(DomJsonParser.parse(dom(url = "https://WWW.Example.com/a"), topK = 1).host).isEqualTo("example.com")
        assertThat(DomJsonParser.parse(dom(url = "https://SHOP.Example.COM/list"), topK = 1).host)
            .isEqualTo("shop.example.com")
        assertThat(DomJsonParser.parse(dom(url = "https://shop.example.com:8443/list"), topK = 1).host)
            .isEqualTo("shop.example.com")
    }

    @Test
    fun schemeLessOrRelativeUrlYieldsEmptyHost() {
        assertThat(DomJsonParser.parse(dom(url = "example.com/list"), topK = 1).host).isEmpty()
        assertThat(DomJsonParser.parse(dom(url = "/checkout"), topK = 1).host).isEmpty()
        assertThat(DomJsonParser.parse(dom(url = "about:blank"), topK = 1).host).isEmpty()
    }

    @Test
    fun captchaAndLoginWallHeuristicsHaveFalsePositives() {
        // 命中路径：title/url 含 captcha/验证/人机/安全校验/robot，url 含 /login、passport 等
        assertThat(
            DomJsonParser.parse(dom(url = "https://shop.example.com/p", title = "请完成安全校验"), topK = 1).pageState,
        ).isEqualTo(PageState.CAPTCHA)
        assertThat(
            DomJsonParser.parse(dom(url = "https://shop.example.com/login?next=%2Fcart"), topK = 1).pageState,
        ).isEqualTo(PageState.LOGIN_WALL)

        // ⚠ 误伤：子串匹配 + 不看 title ⇒ 正常页面被判成登录墙/验证码
        assertThat(
            DomJsonParser.parse(dom(url = "https://shop.example.com/loginless-sale"), topK = 1).pageState,
        ).isEqualTo(PageState.LOGIN_WALL)
        assertThat(
            DomJsonParser.parse(dom(url = "https://shop.example.com/passport-photo/pricing"), topK = 1).pageState,
        ).isEqualTo(PageState.LOGIN_WALL)
        assertThat(
            DomJsonParser.parse(dom(url = "https://shop.example.com/robotics-week"), topK = 1).pageState,
        ).isEqualTo(PageState.CAPTCHA) // "robot" 命中
    }

    @Test
    fun blockedIsNeverProducedByDetection() {
        // §5.8 要求遇到风控「立刻转人工」，但 detectPageState 只产出
        // CAPTCHA / LOGIN_WALL / READY —— BLOCKED 与 ERROR 在这个位置上都不会出现。
        val states = listOf(
            dom(url = "https://x.cn/a", title = "正常页面"),
            dom(url = "https://x.cn/captcha", title = "hi"),
            dom(url = "https://x.cn/login", title = "登录"),
            dom(url = "https://x.cn/a", title = "被风控拦截了"),
        ).map { DomJsonParser.parse(it, topK = 1).pageState }
        assertThat(states).doesNotContain(PageState.BLOCKED)
        assertThat(states.last()).isEqualTo(PageState.READY) // 只写进 title 的风控拦不住
    }

    private companion object {
        const val TOP_K = 48
        const val DOCUMENTED_BUDGET_CHARS = 12_000

        /** 与 dom-extract.js 的 `ATTR_WHITELIST` 逐字对齐；JS 改白名单必须同步改这里。 */
        val ATTR_WHITELIST_ASSOCIATED_SIZE_TEST: List<String> = listOf(
            "aria-label", "aria-labelledby", "aria-describedby", "role", "placeholder",
            "value", "href", "type", "name", "alt", "title", "id", "class",
            "aria-current",
        )
    }
}
