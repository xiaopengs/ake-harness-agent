package com.ake.browser

import org.json.JSONObject

// ===========================================================================
// DOM 抽取测试用的 fixture 构造器。
//
// ★ 为什么是「Kotlin Map → JSON 字符串」而不是一坨裸 JSON 字面量：
//   `DomJsonParser` 唯一的入口是 `parse(raw: String)`，它吃的就是
//   `dom-extract.js` 的 `__extract(opts)` 返回值。所以 fixture 必须是**字符串**，
//   这样测试才是在打真正的边界（畸形 JSON 也才测得到）。
//   但裸字面量不可读、不可参数化 —— 用 Map 构造再 `toString()` 两者兼得，
//   代价是多依赖了一次 JSON 序列化（`JsonShimSelfTest` 已经压过这条路径）。
//
// ★ 字段名与 dom-extract.js 产出保持逐字一致（url/title/outline/candidates/
//   records/signals/warnings/stats），改 JS 那边的 key 必须同步改这里。
// ===========================================================================

internal const val GOLDEN_URL = "https://shop.example.com/list?q=shoe"

internal val USUAL_RECT: Map<String, Int> = mapOf("x" to 10, "y" to 20, "w" to 120, "h" to 44)

/** 一个 `__extract` 候选元素。默认值取「电商列表页里最常见的一颗加入购物车按钮」。 */
internal fun candidate(
    tag: String = "button",
    role: String? = "button",
    text: String = "加入购物车",
    attrs: Map<String, String> = emptyMap(),
    rect: Map<String, Int>? = USUAL_RECT,
    depth: Int = 5,
    offscreen: Boolean = false,
    weight: Double = 0.9,
    selector: String = "div.pdp>button.pdp-cart",
    states: Map<String, Boolean> = mapOf("visible" to true),
): Map<String, Any?> = mapOf(
    "tag" to tag,
    "role" to role,
    "text" to text,
    "attrs" to attrs,
    "rect" to rect,
    "depth" to depth,
    "weight" to weight,
    "selector" to selector,
    "offscreen" to offscreen,
    "states" to states,
)

/** 一个 `__extract` 的完整返回值（序列化后的字符串）。 */
internal fun dom(
    vararg candidates: Map<String, Any?>,
    url: String = GOLDEN_URL,
    title: String = "跑鞋 - Shop",
    outline: String = "div.app\n  ul.list",
    scrollY: Int = 0,
    maxY: Int = 4000,
    warnings: List<String> = emptyList(),
    signals: List<String> = emptyList(),
    stats: Map<String, Int> = mapOf("rawNodeCount" to 12000, "prunedNodeCount" to 1800, "serializedChars" to 9341),
    records: Map<String, Any?>? = null,
    includeCandidatesKey: Boolean = true,
): String {
    val root = LinkedHashMap<String, Any?>()
    root["url"] = url
    root["title"] = title
    root["outline"] = outline
    root["scrollY"] = scrollY
    root["maxY"] = maxY
    root["signals"] = signals
    root["warnings"] = warnings
    root["stats"] = stats
    root["records"] = records
    if (includeCandidatesKey) root["candidates"] = candidates.toList()
    return JSONObject(root).toString()
}

/** `recordTable` 的 records 段。 */
internal fun records(
    columns: List<String> = listOf("title", "price"),
    rows: List<Map<String, String>> = listOf(mapOf("title" to "跑鞋 A", "price" to "¥399")),
    schemaHint: String = ".product-card",
    totalEstimate: Int = -1,
    truncated: Boolean = false,
): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["schemaHint"] = schemaHint
    out["columns"] = columns
    out["rows"] = rows
    if (totalEstimate >= 0) out["totalEstimate"] = totalEstimate
    out["truncated"] = truncated
    return out
}

// ────────────────────────────────────────────────────────────── 静态审计用小工具

/** 仓库根。单元测试的 cwd 是模块目录，往上找到有 settings.gradle.kts 的那层。 */
internal fun repoRoot(): java.io.File = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
    .first { java.io.File(it, "settings.gradle.kts").isFile }

internal fun browserMainDir(): java.io.File = java.io.File(repoRoot(), "browser/src/main")

internal fun browserMainSources(): List<java.io.File> =
    java.io.File(browserMainDir(), "java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

/** 按相对路径读 `:browser` 的主源码（缺件时给出明确失败原因，不要静默给空串）。 */
internal fun browserSource(relativeToMain: String): String {
    val f = java.io.File(browserMainDir(), relativeToMain)
    check(f.isFile) { "找不到源码 $relativeToMain —— :browser 主源码结构变了，静态审计需要同步" }
    return f.readText()
}

/** 剥行注释，保留 `https://` 里的 `//`（与根 build.gradle.kts 的同名工具同一手法）。 */
internal fun String.stripLineComments(): String =
    lines().joinToString("\n") { it.replace(Regex("(?<!:)//.*$"), "") }
