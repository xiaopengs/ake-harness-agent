package com.ake.core.contract

/**
 * DOM → 模型输入的表示（架构 §5.2，是本方案唯一的技术护城河）。
 *
 * 目标：一个 5,000–30,000 节点的真实电商页面 ⇒ ≤ 12 KB 文本 ≈ 2,800 token。
 * 这个数字不是审美：prefill 是端侧单步的主要成本项（§3.1），
 * **每省 1,000 token，GPU 省 0.26 秒/步，CPU 省 1.8 秒/步。**
 */

/** 页面整体状态。`BLOCKED` / `CAPTCHA` 意味着任务不可继续（且我们明确不绕过风控）。 */
public enum class PageState { LOADING, READY, ERROR, BLOCKED, CAPTCHA, LOGIN_WALL }

public enum class DomSignal {
    MODAL_OPEN,
    PAGINATION,
    RESULTS_COUNT,
    HAS_FORM,
    HAS_FILTER_BAR,
    HAS_SEARCH_BOX,
    LOGIN_REQUIRED,
    INFINITE_SCROLL,
}

/** 失效标注：写进 prompt 的 WARN 行。宁可让模型知道「读不到」，也不要让它以为「页面上没有」。 */
public enum class DomWarning {
    SHADOW_DOM_OMITTED,
    IFRAME_CROSS_ORIGIN,
    CANVAS_REGION,
    LIST_TRUNCATED,
    VIRTUALIZED_LIST,
    LAZY_CONTENT,
    SUSPICIOUS_TEXT_REDACTED,
}

public enum class ElementState { VISIBLE, OFFSCREEN, DISABLED, CHECKED, FOCUSED, REQUIRED }

public data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

/** 让模型知道自己不在页首 —— 否则它会反复点击已经在视野里没有的元素。 */
public data class ScrollInfo(val y: Int, val maxY: Int)

/** 结构指纹，用于无进展检测（§3.5）与「页面到底变了没有」。 */
@JvmInline
public value class PageDigest(public val value: String)

/**
 * 一个可交互元素的最终形态。
 *
 * ★ `id` 是模型唯一可用的引用方式（`[id]`）—— 从机制上杜绝模型自己编 CSS selector 或注入 JS。
 * ★ `stableKey` 是跨快照稳定性标识（§5.6）：SPA 部分重绘后 `id` 会漂移，`stableKey` 不会。
 *   这二者并存，才让「点销量排序 → 页面局部重绘 → 编号全乱」这个头号失败原因得以解决。
 */
public data class UiElement(
    val id: Int,
    val tag: String,
    val role: String?,
    val text: String,
    /** 白名单：aria-label / placeholder / value / href / type / name / alt / title */
    val attrs: Map<String, String>,
    val states: Set<ElementState>,
    val rect: Rect?,
    val depth: Int,
    val score: Float,
    val stableKey: String,
    /** id > 稳定语义 class > nth-path */
    val selector: String,
    val xpath: String,
) {
    public companion object {
        public const val MAX_TEXT_LEN: Int = 48
    }
}

/** 结构化数据行快车道。 `"div.product-card ×60"` 给数量认知，这里给干净数据。 */
public data class RecordTable(
    val schemaHint: String,
    val columns: List<String>,
    val rows: List<Map<String, String>>,
    val totalEstimate: Int,
    val truncated: Boolean,
)

public data class DomStats(
    val rawNodeCount: Int,
    val prunedNodeCount: Int,
    val compressionRatio: Float,
    val serializedChars: Int,
)

/** 交付给模型的最终产物。序列化后 `<page_content>` 段。 */
public data class PocketDom(
    val url: String,
    val host: String,
    val title: String,
    val pageState: PageState,
    val scroll: ScrollInfo,
    /** 紧凑树骨架（不含叶子文本） */
    val outline: String,
    /** 编号后的可交互元素（Top-K） */
    val candidates: List<UiElement>,
    val records: RecordTable?,
    val signals: Set<DomSignal>,
    val warnings: Set<DomWarning>,
    val digest: PageDigest,
    val stats: DomStats,
) {
    public companion object {
        /** 病态 DOM / 感知超时时的兜底（§5.8 硬熔断）。 */
        public fun errored(url: String = "", host: String = ""): PocketDom = PocketDom(
            url = url,
            host = host,
            title = "",
            pageState = PageState.ERROR,
            scroll = ScrollInfo(0, 0),
            outline = "",
            candidates = emptyList(),
            records = null,
            signals = emptySet(),
            warnings = emptySet(),
            digest = PageDigest(""),
            stats = DomStats(0, 0, 0f, 0),
        )
    }
}

// ──────────────────────────────────────────────────────────────
// 位掩码：DB 里 signal_flags / warning_flags 是 INTEGER（数据库 §3 observation）
// 这个小转换保证「枚举顺序一旦调整」不会被悄悄写坏 —— 显式位序 + 集中定义。
// ──────────────────────────────────────────────────────────────

/**
 * ★ 为什么这两个函数名字不能都叫 `toFlags()`：
 * `Set<DomSignal>` 与 `Set<DomWarning>` 擦除后都是 `Set`，同为 `(Set)I` 的 JVM 签名会 clash。
 * 这是 JVM 类型擦除的直接后果，不是 Kotlin 的可选 correctness check。
 */
public fun Set<DomSignal>.toSignalFlags(): Int = fold(0) { acc, s -> acc or (1 shl s.ordinal) }

public fun Set<DomWarning>.toWarningFlags(): Int = fold(0) { acc, w -> acc or (1 shl w.ordinal) }

public fun decodeSignalFlags(flags: Int): Set<DomSignal> =
    DomSignal.entries.filterTo(mutableSetOf()) { flags and (1 shl it.ordinal) != 0 }

public fun decodeWarningFlags(flags: Int): Set<DomWarning> =
    DomWarning.entries.filterTo(mutableSetOf()) { flags and (1 shl it.ordinal) != 0 }
