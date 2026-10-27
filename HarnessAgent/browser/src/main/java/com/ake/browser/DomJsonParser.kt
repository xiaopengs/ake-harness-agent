package com.ake.browser

import com.ake.core.common.Ulid
import com.ake.core.contract.DomSignal
import com.ake.core.contract.DomStats
import com.ake.core.contract.DomWarning
import com.ake.core.contract.ElementState
import com.ake.core.contract.PageDigest
import com.ake.core.contract.PageState
import com.ake.core.contract.PocketDom
import com.ake.core.contract.RecordTable
import com.ake.core.contract.Rect
import com.ake.core.contract.ScrollInfo
import com.ake.core.contract.UiElement
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * JS 产出的紧凑 JSON → `PocketDom`（架构 §5.4–§5.6 的 ②③④⑤ 段：裁剪/折叠/去重/评分/编号）。
 *
 * 本类是 `:browser` 能在 JVM 上做 fixture 测试的关键：输入是一个 JSON 字符串，输出是纯 Kotlin 数据类，
 * 中间没有任何 Android API —— 拿一批真实页面的抽取结果存成 fixture，就能毫秒级回归。
 */
public object DomJsonParser {

    // ── stableKey 的哈希常量（§5.6 跨快照指纹）
    //
    // ★★ 这三个数是 **Kotlin 与 JS 的唯一数据源**：`BridgeHelpers.SCRIPT` 把它们注入成
    //   页面全局变量 `AKE_FNV_PRIME / AKE_FNV_OFFSET_A / AKE_FNV_OFFSET_B`，
    //   `dom-extract.js` 的 `__stableKeyOf` 只从全局变量取值。
    //   于是「两端常量对不上」这类漂移在结构上不可能发生 —— 而不是靠注释担保。
    //
    // 为什么是 FNV-1a（而不是继续用 SHA-256）：见 `dom-extract.js` 里 `__stableKeyOf`
    // 上方的长注释。一句话：JS 侧拿不到同步的 SHA-256，而 JVM 单测里没有 JS 引擎，
    // 两端一致只能靠「常量同源 + 结构逐字可核对」，那就选一个 10 行能写完的算法。
    public const val FNV_PRIME: Long = 16_777_619L
    public const val FNV_OFFSET_A: Long = 2_166_136_261L
    public const val FNV_OFFSET_B: Long = 2_654_435_769L

    /** stableKey 的长度（16 hex = 两段 32 位）。长度本身是契约的一部分：历史上 JS 侧
     *  用 `h.toString(16)` 不补零 ⇒ 长度 1–8，与 Kotlin 的恒 16 位值域不相交。 */
    public const val STABLE_KEY_LEN: Int = 16

    /** `stats` 段是 `__extract` 的必备字段；缺它说明这不是一次完整抽取的产物。 */
    private const val STATS_KEY = "stats"

    public fun parse(raw: String, topK: Int = 48): PocketDom {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { return PocketDom.errored() }

        val url = root.optString("url", "")
        val host = hostOf(url)

        // ★ 信封校验：`{"partial":true}` 这种合法 JSON 与「页面真的是空的」在字段层面
        //   完全同形（都是 READY + 零候选）。唯一的区分点是「有没有 stats 段」——
        //   `__extract` 恒产出它，缺了就说明 payload 被截断 / 不是本版本的产物。
        //   这里必须显式判成 ERROR，否则 UI 无法区分「正在加载」与「什么都不在」。
        val stats = root.optJSONObject(STATS_KEY)
            ?: return PocketDom.errored(url = url, host = host)

        val pruned = stats.optInt("prunedNodeCount", 0)
        val rawNodes = stats.optInt("rawNodeCount", 0)

        // ★ 顺序：先评分 → 按分排倒序 → 再截断。
        //   先截断再排序会变成「取 DOM 顺序前 N 个然后重排」，把真正重要的元素挡在外面。
        //
        // ★ `coerceAtLeast(0)`：`Takeable.take(-1)` 直接抛 IllegalArgumentException，
        //   而 `candidateTopK` 是从 `ObserveOptions` 一路传下来的配置项 ——
        //   一个负数配置不该让整个 observe 挂掉，优雅退化为空列表即可。
        val candidates = parseCandidates(root.optJSONArray("candidates"))
            .score()
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(0))
            .enumerate()

        val signals = parseStrings(root.optJSONArray("signals"))
            .mapNotNull { runCatching { DomSignal.valueOf(it) }.getOrNull() }
            .toSet()
        val warnings = parseStrings(root.optJSONArray("warnings"))
            .mapNotNull { runCatching { DomWarning.valueOf(it) }.getOrNull() }
            .toSet()

        return PocketDom(
            url = url,
            host = host,
            title = root.optString("title", "").take(96),
            pageState = detectPageState(root),
            scroll = ScrollInfo(
                y = root.optInt("scrollY", 0),
                maxY = root.optInt("maxY", 0),
            ),
            outline = root.optString("outline", ""),
            candidates = candidates,
            records = parseRecords(root.optJSONObject("records")),
            signals = signals,
            warnings = warnings,
            digest = digestOf(candidates, root.optString("outline", "")),
            stats = DomStats(
                rawNodeCount = rawNodes,
                prunedNodeCount = pruned,
                compressionRatio = if (rawNodes > 0) pruned.toFloat() / rawNodes else 0f,
                serializedChars = stats.optInt("serializedChars", 0),
            ),
        )
    }

    /**
     * ★ **域名归一化 —— 这是安全代码，不是格式化代码。**
     *
     * `PocketDom.host` 会一路流到 `ObservationSummary.host`（`ObservationProjector`），
     * 而该字段的契约注释写得很死：**「小写化裸域名，明文 —— 域名策略要精确等值查询」**
     * （数据库 §7.4）。域名策略（`DomainPolicy`）的黑名单是「命中即 Denied，不提供确认选项」，
     * 白名单是 `host == it || host.endsWith(".$it")` —— 全是**精确等值**比较。
     *
     * 因此只要这里漏掉 lowercase / `www.` 归一，`PASTEBIN.com` 就既不等于也不 endsWith
     * `pastebin.com` ⇒ 硬拒变「需确认」、白名单归类错、按 host 精确查询的清理/保留策略漏行。
     * 这正是 `DomainPolicy` 自己的 KDoc 点名的「攻击者绕过白名单的入口」。
     *
     * 口径与 `core:common/Url.host`、`:agent` 的 `UrlSanitizer.hostOf` /
     * `ObservationProjector.hostOf` **逐字一致**（小写、去 `www.`、去端口、去 userinfo）。
     */
    public fun hostOf(url: String): String {
        // `URI.host` 已经去掉了 userinfo（`user@`）与端口；相对 URL / `about:blank`
        // 解析不出 host ⇒ null ⇒ 空串（与 `Url.host` 对相对 URL 的行为保持一致）。
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        return host.lowercase().removePrefix("www.")
    }

    // ────────────────────────────────────────────────────────────── candidates

    private data class RawCandidate(
        val tag: String,
        val role: String?,
        val text: String,
        val attrs: Map<String, String>,
        val rect: Rect?,
        val depth: Int,
        val offscreen: Boolean,
        val states: Set<ElementState>,
        val selector: String,
        val xpath: String,
        val weight: Float,
    )

    private fun parseCandidates(arr: JSONArray?): List<RawCandidate> {
        if (arr == null) return emptyList()
        val out = ArrayList<RawCandidate>(minOf(arr.length(), 256))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val attrs = LinkedHashMap<String, String>()
            val ao = o.optJSONObject("attrs")
            if (ao != null) {
                val it = ao.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = ao.optString(k, "")
                    if (v.isNotEmpty()) attrs[k] = v.take(48)
                }
            }
            val rectObj = o.optJSONObject("rect")
            // ★ 判据是「宽或高至少有一个非零」，不是「w > 0」。
            //   w=0 而 h=44 的竖条（某些 overlay / label 包裹 / 分隔线按钮）是合法的：
            //   JS 侧 `isVisible` 已经排掉了 w 与 h **同时**为 0 的元素。
            //   历史实现把 w=0 的整条 rect 丢掉 ⇒ rect=null ⇒ 模型既拿不到位置也看不到它。
            //   （sizeReasonableness 仍会给它 0 分，所以这条改动只补回位置信息，不改排序。）
            val rect = if (rectObj != null && (rectObj.optInt("w", 0) != 0 || rectObj.optInt("h", 0) != 0)) {
                Rect(rectObj.optInt("x"), rectObj.optInt("y"), rectObj.optInt("w"), rectObj.optInt("h"))
            } else null

            val states = LinkedHashSet<ElementState>()
            val so = o.optJSONObject("states")
            if (so != null) {
                if (so.optBoolean("visible", false)) states += ElementState.VISIBLE
                if (so.optBoolean("disabled", false)) states += ElementState.DISABLED
                if (so.optBoolean("checked", false)) states += ElementState.CHECKED
                if (so.optBoolean("focused", false)) states += ElementState.FOCUSED
                if (so.optBoolean("required", false)) states += ElementState.REQUIRED
            }
            if (o.optBoolean("offscreen", false)) states += ElementState.OFFSCREEN

            out += RawCandidate(
                tag = o.optString("tag", "div"),
                role = o.optString("role", "").ifBlank { null },
                text = o.optString("text", "").take(UiElement.MAX_TEXT_LEN),
                attrs = attrs,
                rect = rect,
                depth = o.optInt("depth", 0),
                offscreen = o.optBoolean("offscreen", false),
                states = states,
                selector = o.optString("selector", ""),
                xpath = "",
                weight = o.optDouble("weight", 0.05).toFloat(),
            )
        }
        return out
    }

    /**
     * ④ 元素评分（架构 §5.5）。决定哪些元素进 Top-K。
     *
     * 权重表是本项目**最需要「用数据调」而不是「用直觉调」的一处**：
     * 迭代循环是「跑 5 个任务 → 统计哪个元素被反复选中却失败 / 哪个目标元素总没进 Top-K → 调权重」。
     * 所以权重被写成一个显式的常量表，而不是散在表达式里的魔数。
     */
    private fun List<RawCandidate>.score(): List<UiElement> = map { c ->
        val visibility = when {
            c.offscreen -> 0.05f
            else -> 1.0f
        }
        val textfulness = if (c.text.isNotBlank() && c.text.length in 2..UiElement.MAX_TEXT_LEN) 1.0f else 0.2f
        val sizeScore = sizeReasonableness(c.rect)
        val contextBonus = contextBonus(c)
        val ariaBonus = if (c.attrs.containsKey("aria-label") || c.role != null) 1.0f else 0f
        val depthPenalty = -0.02f * c.depth.coerceAtMost(20)

        val s = (0.30f * visibility +
            0.28f * c.weight +
            0.15f * textfulness +
            0.10f * sizeScore +
            0.10f * contextBonus +
            0.07f * ariaBonus +
            depthPenalty).coerceIn(0f, 1f)

        UiElement(
            id = 0, // 编号在 enumerate 阶段统一分配
            tag = c.tag,
            role = c.role,
            text = c.text,
            attrs = c.attrs,
            states = c.states,
            rect = c.rect,
            depth = c.depth,
            score = s,
            stableKey = stableKeyOf(c),
            selector = c.selector,
            xpath = "",
        )
    }

    /**
     * ⑤ 编号分配。
     *
     * 注意这里**只负责生成本轮编号**。跨快照的稳定性不靠 id 而是靠
     * [UiElement.stableKey] + 上一轮映射（`:agent` 的 `remapWarnings`）——
     * §5.6 协议 1/2 就是在那里完成的。「编号漂移」是 DOM 类 Agent 的第一大失败原因，
     * 必须同时有「本轮编号」和「跨轮指纹」两套东西才能解决。
     *
     * `stableKey` = 结构化指纹哈希：tag + id/class 路径 + 文本前 24 字。
     * **文本必须参与但要短** —— 纯结构指纹在「同一位置的同类元素」上会退化，
     * 纯文本指纹则会因价格刷新这类微变而 miss。
     */
    private fun List<UiElement>.enumerate(): List<UiElement> = mapIndexed { index, e ->
        e.copy(id = index + 1)
    }

    /**
     * 跨快照指纹（§5.6）。**两端唯一契约** —— 与 `dom-extract.js` 的 `__stableKeyOf` 逐字对应。
     *
     * ★ `role = null` 必须插**空串**：历史上 `"$role"` 在 null 时插的是字符串 "null"，
     *   而 JS 侧 `(role || '')` 插空串 ⇒ 凡是 role 为 null 的元素（占绝大多数）
     *   两端永远不等 ⇒ 每次点击都 STALE。
     */
    public fun stableKeyOf(tag: String, selector: String, text: String, role: String?): String {
        val input = "$tag|${role.orEmpty()}|$selector|${text.take(24)}"
        return hex8(fnv1a32(input, FNV_OFFSET_A)) + hex8(fnv1a32(input, FNV_OFFSET_B))
    }

    /**
     * 32 位 FNV-1a。逐字对应 JS 的 `__fnv1a32`：`h = Math.imul(h ^ s.charCodeAt(i), PRIME)`。
     *
     * ★ 按 **UTF-16 码元**迭代（`String` 的 `Char`），与 JS 的 `charCodeAt` 同口径 ——
     *   不按 UTF-8 字节，否则中文 / emoji 两端不等。
     * ★ 全程用 Long 做乘法并取低 32 位，等价于 `Math.imul` 的 int32 截断语义。
     */
    public fun fnv1a32(s: String, offsetBasis: Long): Int {
        var h = offsetBasis and 0xFFFF_FFFFL
        for (i in s.indices) {
            h = ((h xor (s[i].code.toLong() and 0xFFFFL)) * FNV_PRIME) and 0xFFFF_FFFFL
        }
        return h.toInt()
    }

    /** 定长 8 位小写 hex —— 不补零会让长度变成契约的一部分（历史 bug）。 */
    private fun hex8(v: Int): String =
        (v.toLong() and 0xFFFF_FFFFL).toString(16).padStart(8, '0')

    private fun stableKeyOf(c: RawCandidate): String =
        stableKeyOf(c.tag, c.selector, c.text, c.role)

    private fun sizeReasonableness(rect: Rect?): Float = when {
        rect == null -> 0f
        rect.w < 24 || rect.h < 24 -> 0f
        rect.w > 4_000 || rect.h > 4_000 -> 0.3f
        else -> 1.0f
    }

    private val CONTEXT_KEYWORDS = setOf("form", "search", "nav", "pagination", "dialog", "list", "filter", "sort")

    /**
     * ★ 必须按**片段**等值匹配，不能子串匹配。
     *
     * 子串匹配时 `.informal-banner` 含 `form`、`.platform-row` 含 `form` ⇒
     * 与真正的 `<form>` 同样 +0.10。评分表只有 0.10 这一档，误伤会直接改变 Top-K 的排序，
     * 表现为「模型总去点那个其实不重要的横幅」。
     */
    private fun contextBonus(c: RawCandidate): Float {
        val segments = c.selector.lowercase().split(SEGMENT_SEPARATORS)
        return if (segments.any { it in CONTEXT_KEYWORDS }) 1.0f else 0f
    }

    /** 选择器里的片段分隔符：`div.pdp>button.buy` → [div, pdp, button, buy]。 */
    private val SEGMENT_SEPARATORS: Regex = Regex("""[^A-Za-z0-9_-]+""")

    // ────────────────────────────────────────────────────────────── records / 其它

    public fun parseRecords(o: JSONObject?): RecordTable? {
        if (o == null) return null
        val cols = parseStrings(o.optJSONArray("columns"))
        if (cols.isEmpty()) return null
        val rowsArr = o.optJSONArray("rows") ?: return null
        val rows = ArrayList<Map<String, String>>(minOf(rowsArr.length(), 128))
        for (i in 0 until rowsArr.length()) {
            val ro = rowsArr.optJSONObject(i) ?: continue
            val row = LinkedHashMap<String, String>()
            for (col in cols) row[col] = ro.optString(col, "").take(40)
            rows += row
        }
        return RecordTable(
            schemaHint = o.optString("schemaHint", ""),
            columns = cols,
            rows = rows,
            totalEstimate = o.optInt("totalEstimate", rows.size),
            truncated = o.optBoolean("truncated", false),
        )
    }

    private fun parseStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotBlank()) out += v
        }
        return out
    }

    /**
     * §5.8：遇到验证码 / 风控特征 ⇒ `CAPTCHA` / `BLOCKED`，**立刻转人工，不尝试绕过**（宪章第 6 条）。
     *
     * 这一步不是体验优化而是合规边界：绕过风控会让整个产品失去长期信任。
     */
    private fun detectPageState(root: JSONObject): PageState {
        val title = root.optString("title", "")
        val url = root.optString("url", "")
        if (CAPTCHA_HINTS.any { title.contains(it, true) || url.contains(it, true) }) return PageState.CAPTCHA
        if (LOGIN_HINTS.any { url.contains(it, true) }) return PageState.LOGIN_WALL
        return PageState.READY
    }

    /** 结构指纹：给无进展检测用。取候选的 stableKey 序列而不是全文 —— 抗动态内容扰动。 */
    private fun digestOf(candidates: List<UiElement>, outline: String): PageDigest {
        val input = buildString {
            append(outline.hashCode())
            candidates.forEach { append('|').append(it.stableKey).append(':').append(it.tag) }
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return PageDigest(bytes.take(10).joinToString("") { "%02x".format(it) })
    }

    /** 页面唯一id，用于轨迹定位。与 PocketDom 无关，仅感知侧记账。 */
    public fun newObservationId(): String = Ulid.next()

    private val CAPTCHA_HINTS = listOf("captcha", "验证", "人机", "安全校验", "robot", "recaptcha")
    private val LOGIN_HINTS = listOf("/login", "/signin", "login?", "passport", "auth/login")
}
