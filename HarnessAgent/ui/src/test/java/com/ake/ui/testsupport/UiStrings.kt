package com.ake.ui.testsupport

import com.ake.ui.R
import java.io.File

/**
 * 纯 JVM 单测里读真人能看见的那句中文。
 *
 * ## 为什么必须有这个 class
 *
 * `ChatCopy.kt` 交出来的是 `R.string.xxx` 的**整型 id**，不是文本。
 * 于是「两个终态共用同一句文案」这类 bug 光比 id 就能抓出来（见 `ChatCopyTest` 的互异断言），
 * 但 `IN_FLIGHT` 那条铁律 —— **文案里不许出现"失败 / 出错 / 被拒 / 拦截 / 安全 / 权限"** ——
 * 只看 id 是**看不见**的：id 只告诉我两张表指向不同的资源，不告诉我资源里写了什么字。
 * 上一版"排队"被写成"安全策略拦下了这一步"正是栽在这里 —— 改的是 `strings.xml`，
 * Kotlin 代码一行没动，`ChatCopy.kt` 的 `when` 穷尽性一声没响。
 *
 * ## 为什么不用 `Resources.getSystem()` / Robolectric
 *
 * 前者要 Android 运行时，后者要往版本目录里加新坐标（邝随行的地盘），而且本项目
 * 没有 `androidTest` 基础设施。直接读 XML 是唯一既不引新依赖、又读得到真实文本的做法：
 * 这份 `strings.xml` 就是打进 APK 的那一份，改它测试就会红。
 *
 * ## 为什么要做自检
 *
 * 一个"读不到就返回空串"的工具类，会让所有依赖它的断言变成永真的废话 ——
 * 那正是 `:app` 里 `ColdStartWiringTest` 上一版被关千锤一枪打死的原因
 * （只读源码文本，把实现注释掉还全绿）。所以这里：
 *  - 找不到文件 ⇒ 抛错，不返回空 map；
 *  - 解析出的条目少于 [EXPECTED_MIN_ENTRIES] ⇒ 抛错；
 *  - `R.string` 的某个 id 反查不到名字 ⇒ 抛错，而不是返回 ""。
 */
public object UiStrings {

    /**
     * `resId`（`R.string.xxx`）→ 字面文本。
     *
     * 链路是「反射取字段名 → 按名到 `strings.xml` 里取值」，
     * 所以 `ChatCopy.kt` 那个 `@StringRes Int` 最终落在哪一行资源上，测试是一路跟得住的。
     */
    public fun text(resId: Int): String {
        val name = nameOf(resId)
        return entries[name] ?: error("strings.xml 里没有名为 '$name' 的条目")
    }

    /** 资源名（如 `chat_term_in_flight_title`）→ 字面文本。 */
    public fun named(name: String): String =
        entries[name] ?: error("strings.xml 里没有名为 '$name' 的条目")

    /** 全部 `<string>` 条目。 */
    public val entries: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val map = parse(xml())
        check(map.size >= EXPECTED_MIN_ENTRIES) {
            "strings.xml 只解析出 ${map.size} 条，少于应有的下限 $EXPECTED_MIN_ENTRIES 条 —— " +
                "解析器坏了就让它红，不要静默通过"
        }
        map
    }

    // ─────────────────────────────────────────────────────── 内部

    private val idToName: Map<Int, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val fields = R.string::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
        val map = HashMap<Int, String>(fields.size)
        fields.forEach { f ->
            val id = runCatching {
                f.isAccessible = true
                f.getInt(null)
            }.getOrElse { return@forEach }
            map[id] = f.name
        }
        check(map.size >= EXPECTED_MIN_ENTRIES) {
            "R.string 反射只拿到 ${map.size} 个字段 —— 单测 classpath 里的 R 类不完整，" +
                "这条必须当红灯修，不要把下界调小去绕"
        }
        map
    }

    private fun nameOf(resId: Int): String =
        idToName[resId] ?: error("id=$resId 不在 R.string 里 —— 传的不是本模块的 string 资源")

    private fun xml(): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        // Gradle Test 的工作目录按模块走；改过 workingDir 时宁可多找两级，也不要一上来就红在半路。
        val candidates = listOf(
            File(cwd, "src/main/res/values/strings.xml"),
            File(File(cwd, "ui"), "src/main/res/values/strings.xml"),
            File(cwd.parentFile, "src/main/res/values/strings.xml"),
        )
        val hit = candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到 ui/src/main/res/values/strings.xml。搜过：\n  " +
                    candidates.joinToString("\n  ") { it.absolutePath }
            )
        return hit.readText(Charsets.UTF_8)
    }

    /**
     * 极简 `<string>` 抽取。只认一种形状 —— 本模块 `strings.xml` 现在没有
     * `<plurals>` / `<string-array>`，出现了再说，不为"通用"提前复杂化。
     *
     * 转义必须还原，否则 `don\'t` 会带着反斜杠进来，让"禁止词"检查出现假阳性。
     */
    private fun parse(xml: String): Map<String, String> {
        val re = Regex("""<string\s+name="([^"]+)"(?:[^>]*)>([\s\S]*?)</string>""")
        val out = LinkedHashMap<String, String>()
        re.findAll(xml).forEach { m -> out[m.groupValues[1]] = unescape(m.groupValues[2]) }
        return out
    }

    private fun unescape(raw: String): String = raw
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /**
     * 条目数的下界。**不要**把它调成跟实际值相等：它是一条"解析没坏事"的粗判据，
     * 加新文案不该让测试红。
     */
    private const val EXPECTED_MIN_ENTRIES: Int = 100
}
