package com.ake.agent.context

import com.ake.core.contract.DomSignal
import com.ake.core.contract.DomWarning
import com.ake.core.contract.PocketDom
import com.ake.core.contract.UiElement

/**
 * `PocketDom` → prompt 文本的序列化器（架构 §5.7）。
 *
 * 格式取舍（每一条都对应一个真实代价）：
 *  - **极低句法噪音**：没有 `<div class="..." style="...">` 这种 token 黑洞。相较 full DOM 压缩 95%+。
 *  - **行式、可预测**：每行字数可控 ⇒ 预算控制才可能精确。
 *  - **「`div.product-card ×60`」把「数量认知」和「细节」一次给出两者**。
 *  - **候选编号 `[n]` 是唯一动作寻址方式**：从机制上杜绝模型自己编 selector 或注入 JS。
 *
 * @param urlSanitizer URL 消毒：抹掉 query 里的凭据型参数（token/password/...），
 *                     但保留 host + path —— 模型需要知道自己在哪，域名策略也需要 host。
 */
class PocketDomSerializer(
    private val urlSanitizer: (String) -> String = { UrlSanitizer.sanitize(it) },
) {

    /**
     * @param topK      本轮实际放进 prompt 的候选数（由 LoopBudget.currentTopK 决定）
     * @param recordRows 本轮展示的 record 行数（默认 3，模型要全量必须走 extractRecords）
     */
    fun render(dom: PocketDom, topK: Int, recordRows: Int = 3): String = buildString {
        append("URL: ").append(urlSanitizer(dom.url)).append("    ")
        append("TITLE: ").append(dom.title.take(MAX_TITLE)).append('\n')
        append("STATE: ").append(dom.pageState.name)
        append("   SCROLL: ").append(dom.scroll.y).append('/').append(dom.scroll.maxY).append('\n')
        if (dom.signals.isNotEmpty()) append("SIGNALS: ").append(renderSignals(dom.signals)).append('\n')
        if (dom.warnings.isNotEmpty()) append("WARN: ").append(dom.warnings.joinToString(", ") { it.name }).append('\n')
        append('\n')

        append("OUTLINE:\n")
        append(dom.outline.trimEnd()).append('\n')
        append('\n')

        val shown = dom.candidates.take(topK)
        append("CANDIDATES (top ").append(shown.size).append(" of ").append(dom.candidates.size)
            .append(" interactable):\n")
        shown.forEach { e -> append("  ").append(renderCandidate(e)).append('\n') }
        if (dom.candidates.size > shown.size) {
            append("  ... 其余 ").append(dom.candidates.size - shown.size)
                .append(" 个候选未展示；若目标不在其中，先 scroll_to 再重新观察。\n")
        }
        append('\n')

        val table = dom.records
        if (table != null && table.rows.isNotEmpty()) {
            val rows = table.rows.take(recordRows)
            append("RECORDS (from ").append(table.schemaHint).append(", ").append(table.totalEstimate)
                .append(" total, showing ").append(rows.size).append('/').append(table.rows.size)
                .append(" -- use extract_records for full):\n")
            append("  | # | ").append(table.columns.joinToString(" | ")).append(" |\n")
            rows.forEachIndexed { i, row ->
                append("  | ").append(i + 1).append(" | ")
                append(table.columns.joinToString(" | ") { col -> row[col].orEmpty().take(MAX_CELL) })
                append(" |\n")
            }
        }
    }

    private fun renderCandidate(e: UiElement): String = buildString {
        append('[').append(e.id).append("] ")
        append(e.tag)
        e.attrs["type"]?.let { append('[').append(it).append(']') }
        append(' ')
        if (e.text.isNotBlank()) append('"').append(e.text.take(MAX_TEXT)).append('"')
        e.attrs["placeholder"]?.let { append(" ph=\"").append(it.take(MAX_TEXT)).append('"') }
        e.attrs["value"]?.let { append(" value=\"").append(it.take(MAX_TEXT)).append('"') }
        e.attrs["aria-label"]?.let { append(" aria-label=\"").append(it.take(MAX_TEXT)).append('"') }
        append("     ::").append(ActionSupport.of(e))
    }

    private fun renderSignals(signals: Set<DomSignal>): String =
        signals.joinToString(", ") { s ->
            when (s) {
                DomSignal.RESULTS_COUNT -> "RESULTS_COUNT(${'$'}n)" // 占位：真实计数由 perception 写入时展开
                else -> s.name
            }
        }

    /** L3 骨架化：只保留结构骨架，丢掉候选与数据行（OBSERVATION 降级到 L3 时用） */
    fun skeleton(dom: PocketDom): String = buildString {
        append("URL: ").append(urlSanitizer(dom.url)).append("  STATE: ").append(dom.pageState.name).append('\n')
        append("OUTLINE:\n").append(dom.outline.trimEnd()).append('\n')
        append("(候选与数据行已省略：上下文预算不足)")
    }

    private companion object {
        const val MAX_TEXT = 48        // §12.2 附录 MAX_TEXT_LEN
        const val MAX_TITLE = 64
        const val MAX_CELL = 40
    }
}

/**
 * URL 消毒（T3 数据外泄 + T6 凭据落盘的第一道）。
 * 抹掉 query / fragment 里的凭据型参数值，保留 host 与 path。
 */
object UrlSanitizer {
    private val SENSITIVE_KEYS = setOf(
        "token", "access_token", "refresh_token", "id_token", "auth", "authorization",
        "password", "passwd", "pwd", "secret", "session", "sessionid", "sid",
        "cookie", "cvv", "cvc", "card", "cardno", "idcard", "sig", "signature", "key",
    )

    fun sanitize(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val base = url.substring(0, qIdx)
        val query = url.substring(qIdx + 1)
        val kept = query.split('&').map { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@map pair
            val k = pair.substring(0, eq)
            if (k.lowercase() in SENSITIVE_KEYS) "$k=<redacted>" else pair
        }
        return base + "?" + kept.joinToString("&")
    }

    /** 取裸域名小写化（对齐数据库 §7.4 host 归一化规则） */
    fun hostOf(url: String): String {
        val s = url.substringAfter("://", url)
        val host = s.substringBefore('/').substringBefore(':').substringAfter('@')
        return host.lowercase().removePrefix("www.")
    }
}
