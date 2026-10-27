package com.ake.browser

import android.webkit.JavascriptInterface
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Modifier

// ===========================================================================
// P3 · JS Bridge 的**改名防线**（静态 + 反射）
//
// `@JavascriptInterface` 的方法是被 WebView 按**方法名字符串**反射调用的。
// R8 不知道这事：一旦把 `deliver` 改了名，dom-extract.js 的 `AkeBridge.deliver(...)`
// 就调不到 —— 不崩溃、不报错，只是「DOM 永远抽不回来」。
// 这是本项目明确踩过的坑，也是「符号层 100% 静态验过、回调零覆盖」留下的那块空白。
//
// 真机验证 R8 mapping 成本太高（跑一次 release 构建），所以退一步做两级静态校验：
//   ① 反射列出当前**真实存在**的 @JavascriptInterface 方法清单
//   ② 拿这份清单去 `app/proguard-rules.pro` 里逐条比对：有没有规则真把它们保住
// 谁改了方法名 / 删了 keep 规则，这里会红，而不是等到真机上「Agent 看不见页面」。
// ===========================================================================

class JavascriptInterfaceContractTest {

    private fun interfaceMethods() = DomExtractBridge::class.java.methods
        .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
        .filter { it.declaringClass == DomExtractBridge::class.java }
        .map { it.name to it }
        .toMap()

    // ───────────────────────────────────────────── ① 反射清单

    @Test
    fun inventoryMatchesExactlyWhatJsCalls() {
        val rendered = interfaceMethods().values.map { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
        }.toSet()
        assertThat(rendered).containsExactly("deliver(long,String)", "deliverError(long,String)")
    }

    @Test
    fun everyCallbackIsAPublicInstanceMethod() {
        for ((name, m) in interfaceMethods()) {
            assertWithMessage("$name 必须 public").that(Modifier.isPublic(m.modifiers)).isTrue()
            assertWithMessage("$name 必须是实例方法").that(Modifier.isStatic(m.modifiers)).isFalse()
            assertWithMessage("$name 必须 void").that(m.returnType.simpleName).isEqualTo("void")
            assertWithMessage("$name 入参必须是 (long, String)")
                .that(m.parameterTypes.map { it.simpleName })
                .containsExactly("long", "String").inOrder()
        }
    }

    @Test
    fun noJavascriptInterfaceMethodHidesInAnotherBrowserClass() {
        // 新增 @JavascriptInterface 方法时，本文件要跟着长 —— 防止「加了一个方法、
        // 忘了加 keep」这种只有真机上才看得见的 bug。
        val others = browserMainSources()
            .map { it.readText().stripLineComments() }
            .filter { it.contains("@JavascriptInterface") && !it.contains("class DomExtractBridge") }
        assertThat(others).isEmpty()
        assertThat(interfaceMethods()).hasSize(2)
    }

    // ───────────────────────────────────────────── ② proguard 覆盖

    @Test
    fun keepRulesCoverTheBridgeClassAndAllItsMembers() {
        val directives = keepDirectives()
        val target = "com.ake.browser.DomExtractBridge"

        val keepClass = directives.filter { it.matchesClass(target) && it.keepsAllMembers() }
        assertWithMessage("必须有一条规则整体保住 $target（类 + 全部成员）")
            .that(keepClass)
            .isNotEmpty()

        for (name in interfaceMethods().keys) {
            val covered = directives.any { it.matchesClass(target) && it.keepsMember(name) } ||
                directives.any { it.matchesClass("*") && it.keepsMember(name) }
            assertWithMessage("-keep 缺少对 ${target}#$name 的覆盖").that(covered).isTrue()
        }
    }

    @Test
    fun javascriptInterfaceAnnotatedMembersAreKeptGlobally() {
        // 这条是兜底：哪怕将来类名改了，所有 @JavascriptInterface 方法也该被保住
        val directives = keepDirectives()
        assertThat(directives.any { it.body.contains("@android.webkit.JavascriptInterface") }).isTrue()
    }

    @Test
    fun runtimeAnnotationsSurviveWithoutDependingOnSuspiciousAttributeNames() {
        // `-keepattributes` 只认 ProGuard/R8 的属性名。`JavascriptInterface` 不是其中之一 ——
        // 那条是空转的。真正保住 RUNTIME 注解（Kotlin 侧 reflection 与 WebView 注入都要用）的
        // 是 `*Annotation*`（它展开为 RuntimeVisibleAnnotations 等）。这里断言：
        // **把可疑属性名剔掉之后，保护依然成立** —— 于是谁删了 `*Annotation*` 这里立刻红。
        val tokens = keepAttributeTokens()
        val recognized = tokens.filter { it.contains("*") || it in RECOGNIZED_ATTRIBUTES }.toSet()
        assertThat(recognized).contains(ANNOTATION_WILDCARD)
    }

    @Test
    fun attachAndDetachGoThroughTheSharedInterfaceName() {
        val src = browserSource("java/com/ake/browser/DomExtractBridge.kt").stripLineComments()
        assertThat(src).contains("webView.addJavascriptInterface(bridge, bridge.interfaceName)")
        assertThat(src).contains("webView.removeJavascriptInterface(bridge.interfaceName)")
        // 加固调用点：不允许出现写死字符串的第二处 addJavascriptInterface
        assertThat(src.countOccurrences("addJavascriptInterface(")).isEqualTo(1)
    }

    // ───────────────────────────────────────────── parser

    private fun proguardRulesText(): String {
        val f = java.io.File(repoRoot(), "app/proguard-rules.pro")
        check(f.isFile) { "找不到 ${f.path}：keep 规则被挪走的话，这条静态校验要跟着改" }
        return f.readText()
    }

    private fun keepAttributeTokens(): List<String> = proguardRulesText()
        .lines()
        .filter { it.trim().startsWith("#").not() }
        .filter { it.trim().startsWith("-keepattributes") }
        .flatMap { it.removePrefix("-keepattributes").trim().split(Regex("""[,\s]+""")) }
        .filter { it.isNotBlank() }

    /** 把 `-keepXXX <classSpec> { body }` 逐条拆出来（兼容单行与多行写法）。 */
    private fun keepDirectives(): List<KeepDirective> {
        val out = ArrayList<KeepDirective>()
        var header: String? = null
        val body = StringBuilder()
        for (raw in proguardRulesText().lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (header == null) {
                if (!line.startsWith("-keep")) continue
                val brace = line.indexOf('{')
                if (brace < 0) { header = line; body.setLength(0); continue }
                val h = line.substring(0, brace).trim()
                val rest = line.substring(brace + 1)
                if (rest.trimEnd().endsWith("}")) {
                    out += KeepDirective(h, rest.trim().removeSuffix("}").trim())
                } else {
                    header = h; body.setLength(0); body.append(rest)
                }
            } else {
                if (line == "}") {
                    out += KeepDirective(header!!, body.toString().trim())
                    header = null
                } else {
                    body.append(' ').append(line.removeSuffix("}"))
                    if (line.endsWith("}")) {
                        out += KeepDirective(header!!, body.toString().trim().removeSuffix("}").trim())
                        header = null
                    }
                }
            }
        }
        return out
    }

    private class KeepDirective(private val header: String, val body: String) {

        /** 头部形如 `-keep class A` / `-keepclassmembers class *` / `-keepclasseswithmembers class A$*` */
        fun matchesClass(fqcn: String): Boolean {
            val spec = header.substringAfter("class ", "").trim()
            if (spec.isEmpty()) return false
            if (spec == "*") return true
            val pattern = Regex(spec.replace(".", "\\.").replace("$", "\\$").replace("*", ".*"))
            return pattern.matches(fqcn)
        }

        fun keepsAllMembers(): Boolean {
            val b = body.trim().removeSuffix(";").trim()
            return b == "*" || b == "*;" || body.contains("<methods>")
        }

        fun keepsMember(name: String): Boolean =
            keepsAllMembers() || body.contains("<methods>") || body.contains(name)
    }

    private fun String.countOccurrences(token: String): Int =
        windowed(token.length, 1) { sub -> if (sub == token) 1 else 0 }.sum()

    private companion object {
        const val ANNOTATION_WILDCARD = "*Annotation*"

        /** ProGuard/R8 认可的属性名；出现表外名字即视为可疑（可能是照着注解名写的无效项）。 */
        val RECOGNIZED_ATTRIBUTES: Set<String> = setOf(
            "SourceFile", "SourceDir", "InnerClasses", "EnclosingMethod", "Deprecated", "Signature",
            "Exceptions", "LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable",
            "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations",
            "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations",
            "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations",
            "AnnotationDefault", "MethodParameters", "BootstrapMethods",
            "NestHost", "NestMembers", "PermittedSubclasses", "Record", "Code",
        )
    }
}
