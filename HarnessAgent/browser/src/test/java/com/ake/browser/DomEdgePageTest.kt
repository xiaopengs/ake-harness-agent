package com.ake.browser

import com.ake.core.contract.DomWarning
import com.ake.core.contract.PageState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P1 · 边界页面的判据
//
// 要求：「空页面 / 只有 script 的页面 / iframe 各自有判据，不能一个空列表糊过去」。
// 这一份就是把三者的**可区分性**钉死：它们都会走向「候选为空」这条路，
// 上层要是只判 `candidates.isEmpty()`，三件事就混成一件了。
// 三层判据：
//   · 空页面      → rawNodeCount 小 / outline 空，但 parse 成功
//   · script-only → rawNodeCount 有数、outline 有骨架、candidates 为空
//   · iframe      → warnings 里必须有 IFRAME_CROSS_ORIGIN（否则模型会以为页面上没有那块内容）
//   · 注入失败    → 回调空串 ⇒ parse 抛 ⇒ PocketDom.errored()（这条最危险：ERROR 页面
//                  在调用方眼里就是「这一页没有元素」）
// ===========================================================================

class DomEdgePageTest {

    // ── 空页面

    @Test
    fun trulyEmptyPageKeepsRawStatsAsTheOnlyEvidence() {
        val parsed = DomJsonParser.parse(
            dom(
                url = "https://shop.example.com/blank",
                title = "",
                outline = "",
                maxY = 0,
                stats = mapOf("rawNodeCount" to 1, "prunedNodeCount" to 1, "serializedChars" to 120),
            ),
            topK = 48,
        )

        assertThat(parsed.candidates).isEmpty()
        assertThat(parsed.records).isNull()
        assertThat(parsed.pageState).isEqualTo(PageState.READY) // 空≠错
        assertThat(parsed.stats.rawNodeCount).isEqualTo(1)
        assertThat(parsed.stats.compressionRatio).isEqualTo(1.0f)

        // ★ 判据：空页面的 digest 只由 outline 决定，而 outline 恒为空 ⇒
        //   它和「上一页同样抽到了一个空结构」同指纹。上层得同时看 rawNodeCount 与 digest。
        assertThat(parsed.digest.value).isNotEmpty()
        val same = DomJsonParser.parse(
            dom(
                url = "https://shop.example.com/blank",
                title = "",
                outline = "",
                maxY = 0,
                stats = mapOf("rawNodeCount" to 1, "prunedNodeCount" to 1, "serializedChars" to 120),
            ),
            topK = 48,
        )
        assertThat(same.digest).isEqualTo(parsed.digest)
    }

    @Test
    fun missingCandidatesKeyIsEquivalentToEmptyArray() {
        val noKey = DomJsonParser.parse(dom(candidate(), includeCandidatesKey = false), topK = 8)
        val emptyKey = DomJsonParser.parse(dom(), topK = 8)
        assertThat(noKey.candidates).isEmpty()
        assertThat(emptyKey.candidates).isEmpty()
        assertThat(noKey.pageState).isEqualTo(PageState.READY)
    }

    // ── 只有 script 的页面

    @Test
    fun scriptOnlyPageReportsNodesButNoCandidates() {
        // JS 侧 KICKED_TAGS 把 SCRIPT/STYLE/TEMPLATE 全砍了 ⇒ 有 raw 计数、有 outline 骨架、零候选。
        // 判据就是这三者同时成立：**它和上层的 observe 超时（=> ERROR）必须区分得开**。
        val parsed = DomJsonParser.parse(
            dom(
                url = "https://shop.example.com/skeleton",
                outline = "body\n  div#root\n  script.boot",
                stats = mapOf("rawNodeCount" to 37, "prunedNodeCount" to 6, "serializedChars" to 240),
            ),
            topK = 48,
        )
        assertThat(parsed.candidates).isEmpty()
        assertThat(parsed.stats.rawNodeCount).isEqualTo(37)
        assertThat(parsed.outline).contains("div#root")
        assertThat(parsed.pageState).isEqualTo(PageState.READY)
    }

    // ── iframe

    @Test
    fun crossOriginIframeSurvivesAsWarningNotAsPageState() {
        // ⚠ 发现：iframe 跨域这条**唯一**的判据是 DomWarning.IFRAME_CROSS_ORIGIN。
        //   pageState 依旧是 READY（见 DomJsonParser.detectPageState 的入参 warnings 被忽略），
        //   也就是说「页面上有一块我们读不到的区域」这件事**不会**改变任何状态字段。
        val parsed = DomJsonParser.parse(dom(candidate(), warnings = listOf("IFRAME_CROSS_ORIGIN")), topK = 8)

        assertThat(parsed.warnings).containsExactly(DomWarning.IFRAME_CROSS_ORIGIN)
        assertThat(parsed.pageState).isEqualTo(PageState.READY)
        assertThat(parsed.candidates).hasSize(1) // 主文档照常可用
    }

    @Test
    fun unknownWarningStringsAreSilentlyDropped() {
        // ⚠ 发现：JS 比 App 新时（热修下发 dom-extract.js），它加的新 warning 名恒被丢掉。
        //   这不是「少一行日志」，而是「模型读不到它读不到的事实」—— 模型会以为那块内容压根不存在。
        val parsed = DomJsonParser.parse(
            dom(candidate(), warnings = listOf("SHADOW_DOM_OMITTED", "STRIPPED_BY_NEW_POLICY")), topK = 8,
        )
        assertThat(parsed.warnings).containsExactly(DomWarning.SHADOW_DOM_OMITTED)
        assertThat(parsed.warnings).doesNotContain(DomWarning.CANVAS_REGION)
    }

    @Test
    fun everyKnownWarningRoundTrips() {
        val all = DomWarning.entries.map { it.name }
        val parsed = DomJsonParser.parse(dom(candidate(), warnings = all), topK = 8)
        assertThat(parsed.warnings).containsExactlyElementsIn(DomWarning.entries.toSet())
    }

    // ── 畸形 / 注入失败

    @Test
    fun malformedPayloadFallsBackToErroredWithoutThrowing() {
        for (bad in listOf("", "   ", "{oops", "[]", "null")) {
            val parsed = DomJsonParser.parse(bad, topK = 8)
            assertWithMessage("bad=$bad").that(parsed.pageState).isEqualTo(PageState.ERROR)
            assertWithMessage("bad=$bad").that(parsed.candidates).isEmpty()
        }
    }

    @Test
    fun aPayloadWithoutTheStatsEnvelopeIsAnErrorNotAnEmptyPage() {
        // ⚠（原失败）修复前 `{"partial":true}` 是**合法** JSON ⇒ 解析成功
        //   ⇒ READY + 零候选 + 零 signal ⇒ 「页面还没加载完就被截了」和
        //   「页面真的什么都没有」在 PocketDom 层面完全同形，UI 无法区分。
        //
        //   唯一的区分点是 **`stats` 段**：`__extract` 恒产出它，缺了就说明这不是
        //   一次完整抽取的产物。现在显式判成 ERROR —— 空页面仍然走 READY（见下一条）。
        val parsed = DomJsonParser.parse("""{"partial":true}""", topK = 8)
        assertThat(parsed.pageState).isEqualTo(PageState.ERROR)
        assertThat(parsed.candidates).isEmpty()
        assertThat(parsed.stats.rawNodeCount).isEqualTo(0)
    }

    @Test
    fun aTrulyEmptyPageStillReportsReadyBecauseItHasAStatsEnvelope() {
        // ★ 与上面那条互补：真空页面有 stats（rawNodeCount=1）⇒ 仍然是 READY。
        //   两条一起才构成「能区分」的判据。
        val empty = DomJsonParser.parse(
            dom(url = "https://shop.example.com/blank", title = "", outline = "", maxY = 0),
            topK = 48,
        )
        assertThat(empty.pageState).isEqualTo(PageState.READY)
        assertThat(empty.candidates).isEmpty()

        val truncated = DomJsonParser.parse("""{"candidates":[]}""", topK = 48)
        assertThat(truncated.pageState).isEqualTo(PageState.ERROR)
        assertThat(empty.pageState).isNotEqualTo(truncated.pageState)
    }

    @Test
    fun erroredPayloadLosesUrlAndHost() {
        // ⚠ 发现：`errored()` 是不带参数调用的 —— 哪怕是「已知 URL 上解析失败」，
        //   落下来的 PocketDom 里 url/host 都是空串，轨迹回溯时这一帧没有页面信息。
        val parsed = DomJsonParser.parse("{oops", topK = 8)
        assertThat(parsed.url).isEmpty()
        assertThat(parsed.host).isEmpty()
        assertThat(parsed.digest.value).isEmpty()
    }

    @Test
    fun emptyDeliverFromJsIsIndistinguishableFromBrokenPage() {
        // ★ 端到端串起来看：注入失败 ⇒ JS 给回一个空串 ⇒ bridge 把它当成正常 payload
        //   ⇒ parse("") 抛 ⇒ errored()。整条链路上没有任何一处是异常，
        //   最终效果与「页面真的是空的」共用同一个出口。
        val bridgePayload = ""
        val parsed = DomJsonParser.parse(bridgePayload, topK = 8)
        assertThat(parsed.pageState).isEqualTo(PageState.ERROR)
    }

    // ── records

    @Test
    fun recordsRoundTripWithColumnOrder() {
        val parsed = DomJsonParser.parse(
            dom(
                candidate(),
                records = records(
                    columns = listOf("title", "price"),
                    rows = listOf(
                        mapOf("title" to "跑鞋 A", "price" to "¥399"),
                        mapOf("title" to "跑鞋 B", "price" to "¥499"),
                    ),
                    totalEstimate = 60,
                    truncated = true,
                ),
            ),
            topK = 8,
        )
        assertThat(parsed.records!!.columns).containsExactly("title", "price").inOrder()
        assertThat(parsed.records!!.rows).hasSize(2)
        assertThat(parsed.records!!.rows[0]["title"]).isEqualTo("跑鞋 A")
        assertThat(parsed.records!!.totalEstimate).isEqualTo(60)
        assertThat(parsed.records!!.truncated).isTrue()
    }

    @Test
    fun recordsWithMissingCellsBecomeEmptyStrings() {
        val parsed = DomJsonParser.parse(
            dom(records = records(columns = listOf("title", "price"), rows = listOf(mapOf("title" to "只有标题")))),
            topK = 8,
        )
        // 缺列补空串 ⇒ 「这一格没抽到」和「这里是空的」没法区分
        assertThat(parsed.records!!.rows.single()["price"]).isEmpty()
    }

    @Test
    fun recordsAreNullWhenColumnsOrRowsMissing() {
        assertThat(DomJsonParser.parse(dom(), topK = 8).records).isNull()
        assertThat(
            DomJsonParser.parse(
                dom(records = mapOf("columns" to emptyList<String>(), "rows" to emptyList<Map<String, String>>())),
                topK = 8,
            ).records,
        ).isNull()
        assertThat(DomJsonParser.parse(dom(records = mapOf("columns" to listOf("a"))), topK = 8).records).isNull()
    }

    @Test
    fun recordCellsAndTotalsHaveDocumentedCaps() {
        val parsed = DomJsonParser.parse(
            dom(
                records = records(
                    columns = listOf("c"),
                    rows = listOf(mapOf("c" to "x".repeat(200))),
                    totalEstimate = -1,
                ),
            ),
            topK = 8,
        )
        assertThat(parsed.records!!.rows.single()["c"]).hasLength(40)
        assertThat(parsed.records!!.totalEstimate).isEqualTo(1) // 缺失时退化成行数
    }
}
