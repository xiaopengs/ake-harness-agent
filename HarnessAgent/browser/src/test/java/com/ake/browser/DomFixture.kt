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

internal fun browserTestSources(): List<java.io.File> =
    java.io.File(repoRoot(), "browser/src/test/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

/**
 * 全仓 `src/main` 的 Kotlin 源码（跳过 `build/` 与 `.git/`）。
 *
 * 给「跨模块清单」类断言用：host 归一化在 `:browser` 之外还有几份实现，
 * `:browser` 的测试**编译期看不见它们**（依赖边只允许 core），只能靠扫源码盯住清单。
 */
internal fun repoMainKotlinSources(): List<java.io.File> = repoRoot().walkTopDown()
    .onEnter { it.name != "build" && it.name != ".git" }
    .filter {
        it.isFile && it.extension == "kt" &&
            it.invariantSeparatorsPath.contains("/src/main/")
    }
    .toList()

/**
 * 从 `assets/js/dom-extract.js` **机械抽取** `ATTR_WHITELIST`。
 *
 * ★ 为什么必须是抽取而不是「测试里再抄一份」：抄的那份一旦和 JS 脱钩，
 *   最坏候选体积就被系统性低估 —— 而上下文预算是按这个体积估的。
 *   （历史上这里抄的是 14 项，JS 里是 18 项 ⇒ 少算 9,216 字符。）
 */
internal fun attrWhitelistFromJsAsset(): List<String> {
    val src = java.io.File(browserMainDir(), "assets/js/dom-extract.js").readText()
    val marker = "var ATTR_WHITELIST = ["
    val start = src.indexOf(marker)
    check(start >= 0) {
        "`dom-extract.js` 里找不到 `$marker` —— 机械抽取器要跟着改，" +
            "绝不允许退化成「测试里再手抄一份名单」"
    }
    val end = src.indexOf(']', startIndex = start)
    check(end > start) { "`dom-extract.js` 的 `ATTR_WHITELIST` 数组没有闭合" }
    val names = Regex("""'([^']+)'""").findAll(src.substring(start, end))
        .map { it.groupValues[1] }
        .toList()
    check(names.isNotEmpty()) { "`ATTR_WHITELIST` 抽出来是空的 —— 抽取器坏了，别默默用空名单算预算" }
    return names
}

/** 按相对路径读 `:browser` 的主源码（缺件时给出明确失败原因，不要静默给空串）。 */
internal fun browserSource(relativeToMain: String): String {
    val f = java.io.File(browserMainDir(), relativeToMain)
    check(f.isFile) { "找不到源码 $relativeToMain —— :browser 主源码结构变了，静态审计需要同步" }
    return f.readText()
}

/** 按相对路径读全仓任意一个源码文件（`src/main` / `src/test` 都行）。 */
internal fun repoSource(relativeToRoot: String): String {
    val f = java.io.File(repoRoot(), relativeToRoot)
    check(f.isFile) { "找不到源码 $relativeToRoot —— 仓库结构变了，机械抽取器需要同步" }
    return f.readText()
}

/**
 * 从 `core:common` 的 `HostsTest.HOST_VECTORS` **机械抽取**那批畸形输入向量。
 *
 * ★ 为什么不出题 attack list 而是在测试里手抄一份：手抄的那份一旦和权威表脱钩，
 *   两边就会各自维护各自的口径 —— 「少覆盖一条绕过路径」在报表上看不出来，
 *   因为抄的那份自己也全绿。抽取出错必须**响**（下面的 `check`），不许退化成空表。
 *
 * ★ `core:common` 的测试源码对 `:browser` **编译期不可见**（模块间互看不见对方的
 *   test source set），所以只能按文件读 —— 这与 `attrWhitelistFromJsAsset` 同一手法。
 */
internal fun sharedHostVectors(): List<Pair<String, String>> {
    val src = repoSource("core/common/src/test/java/com/ake/core/common/HostsTest.kt")

    // ★ 锚必须是**声明**（`val HOST_VECTORS … = listOf(`），不能用第一次出现的名字 ——
    //   那只是某个用例里的引用，后面紧跟的 `listOf(` 是另一坨无关的 URL 列表
    //   （第一次这里就抽成了 0 条，被 size 检查拦住才没变成「少覆盖也不响」）。
    val start = src.indexOf("val HOST_VECTORS")
    check(start >= 0) {
        "`core:common/HostsTest.kt` 里找不到 `val HOST_VECTORS` —— 机械抽取器要跟着改"
    }

    val open = src.indexOf("listOf(", startIndex = start)
    check(open > start) { "`HOST_VECTORS` 的声明后面找不到 `listOf(` —— 抽取器坏了" }

    // 取到 `listOf(` 的闭合括号为止（其后还有别的代码），注释行在这里就被留着，
    // 下面的非锚定正则自然不会命中它们。
    val body = StringBuilder()
    for (line in src.substring(open).lineSequence()) {
        if (line.trim().startsWith(")")) break
        body.appendLine(line)
    }

    // ★ 非锚定：不依赖行首/行尾锚（它们在 JVM 正则 + Kotlin 原始字符串里的写法
    //   容易踩 `$`，上一次就是这么静默抽成 0 条的），也不需要剥注释。
    val pair = Regex(""""((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"\s*,?""")
    val out = pair.findAll(body).map { m -> m.groupValues[1] to m.groupValues[2] }.toList()
    check(out.size >= 29) {
        "从 `HostsTest.HOST_VECTORS` 只抽到 ${out.size} 条向量（历史记录里是 30 条）—— " +
            "抽取器坏了，绝不允许默默拿一张更小的表去跑一致性断言"
    }
    return out
}

/** 剥行注释，保留 `https://` 里的 `//`（与根 build.gradle.kts 的同名工具同一手法）。 */
internal fun String.stripLineComments(): String =
    lines().joinToString("\n") { it.replace(Regex("(?<!:)//.*$"), "") }
