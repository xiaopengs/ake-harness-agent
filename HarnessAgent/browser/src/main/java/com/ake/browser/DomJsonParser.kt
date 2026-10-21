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

    public fun parse(raw: String, topK: Int = 48): PocketDom {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { return PocketDom.errored() }

        val stats = root.optJSONObject("stats")
        val pruned = stats?.optInt("prunedNodeCount", 0) ?: 0
        val rawNodes = stats?.optInt("rawNodeCount", 0) ?: 0

        // ★ 顺序：先评分 → 按分排倒序 → 再截断。
        //   先截断再排序会变成「取 DOM 顺序前 N 个然后重排」，把真正重要的元素挡在外面。
        val candidates = parseCandidates(root.optJSONArray("candidates"))
            .score()
            .sortedByDescending { it.score }
            .take(topK)
            .enumerate()

        val host = runCatching { java.net.URI(root.optString("url", "")).host.orEmpty() }
            .getOrDefault("")
            .removePrefix("www.")

        val signals = parseStrings(root.optJSONArray("signals"))
            .mapNotNull { runCatching { DomSignal.valueOf(it) }.getOrNull() }
            .toSet()
        val warnings = parseStrings(root.optJSONArray("warnings"))
            .mapNotNull { runCatching { DomWarning.valueOf(it) }.getOrNull() }
            .toSet()

        return PocketDom(
            url = root.optString("url", ""),
            host = host,
            title = root.optString("title", "").take(96),
            pageState = detectPageState(root, warnings),
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
                serializedChars = stats?.optInt("serializedChars", 0) ?: 0,
            ),
        )
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
            val rect = if (rectObj != null && rectObj.optInt("w", 0) > 0) {
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

    public fun stableKeyOf(tag: String, selector: String, text: String, role: String?): String {
        val input = "$tag|$role|$selector|${text.take(24)}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun stableKeyOf(c: RawCandidate): String =
        stableKeyOf(c.tag, c.selector, c.text, c.role)

    private fun sizeReasonableness(rect: Rect?): Float = when {
        rect == null -> 0f
        rect.w < 24 || rect.h < 24 -> 0f
        rect.w > 4_000 || rect.h > 4_000 -> 0.3f
        else -> 1.0f
    }

    private val CONTEXT_KEYWORDS = listOf("form", "search", "nav", "pagination", "dialog", "list", "filter", "sort")
    private fun contextBonus(c: RawCandidate): Float =
        if (CONTEXT_KEYWORDS.any { c.selector.contains(it, ignoreCase = true) }) 1.0f else 0f

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
    private fun detectPageState(root: JSONObject, warnings: Set<DomWarning>): PageState {
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
