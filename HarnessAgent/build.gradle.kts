// 根目录只做「插件版本协定」，不放任何业务代码、不放任何依赖实现。
// 每个 apply false 的插件都在这里锁版本，子模块 alias(libs.plugins.xxx) 即可。
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    // ⚠ 已移除 `kotlinAndroid`（2026-10 实测：AGP 9.0+ 内置 Kotlin 支持，
    //   再 apply 'org.jetbrains.kotlin.android' 会直接抛
    //   "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"。
    //   ⇒ 所有 Android 模块一律不要再引它；Kotlin 版本由根 catalog 的 kotlin 版本钉住。）
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.room) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// =============================================================================
// 架构守卫（2026-10 / 邝随行）
//
// 起因：跨层依赖整改把 `:ui → :agent/:mcp/:data` 三条 Gradle 边**人工**摘掉了，
//       但没有任何机器校验。下次谁「编译不过就加回去」不会有人拦 ⇒ 架构腐化。
//
// 选型：不用 dependency-analysis 类插件，也不解析 `./gradlew :ui:dependencies`。
//   · 插件：要联网、要引一个新 classpath，升级 AGP/Gradle 时容易连带炸；
//   · dependencies 输出：必须先配置并解析整张依赖图（Android 模块尤其慢），
//     而且输出是给人看的文本，格式随 Gradle 版本漂移 ⇒ 误报风险高。
// 最终做法：**两条纯文本的确定性断言**，只认字面量，不认注释，秒级出结果：
//   ① Gradle 边：每个模块 build.gradle.kts 里 project(":x") ⊆ 白名单
//   ② 源码边：每个模块 src/**/*.kt 的 import 不得命中黑名单包前缀
// 误报控制：① 先剥注释（ui/build.gradle.kts 里留着三条注释掉的旧边，必须不误伤）；
//          ② 只查 import 行，不查全限定名引用，避免注释/字符串命中。
// =============================================================================

val archCoreModules = setOf(
    ":core:common",
    ":core:contract",
    ":core:ports",
    ":core:model-api",
)
val archLeafModules = setOf(":llm", ":browser", ":agent", ":mcp", ":data", ":ui")

// ① 允许的 Gradle 依赖边（键 = 模块 path，值 = 允许的上游 path）
val archAllowedEdges: Map<String, Set<String>> = buildMap {
    put(":core:common", emptySet())
    put(":core:model-api", setOf(":core:common"))
    put(":core:contract", setOf(":core:common"))
    put(":core:ports", setOf(":core:common", ":core:contract"))
    // 实现层只认 core 契约 —— 彼此之间不得横向依赖
    //
    // ★ `:llm → :data`（2026-10 · P1 模型分发接线）：`:llm` 是模型文件的所有者，
    //   `:data` 的 `modeldist` 只产出**决议与文件动作意图**（`ModelResolution` /
    //   `ModelFileOp`），真正的 rename / 丢 part 由 `:llm` 执行 ⇒ 这条边方向是对的。
    //   它由源码断言 ② 收紧为「只许碰 `com.ake.data.persist.modeldist`，
    //   不许碰 Room / `persist.db`」—— 与 `:agent → :data` 同一套收紧手法。
    put(":llm", archCoreModules + ":data")
    put(":browser", archCoreModules)
    put(":mcp", archCoreModules)
    put(":data", archCoreModules)
    // ★ 硬约束：:ui 不得看见 :agent / :mcp / :data
    put(":ui", archCoreModules)
    // :agent → :data 是白名单里唯一一条「实现层横向」例外，
    // 且经源码断言 ② 收紧为「只许碰 com.ake.data.persist.store 的接口，
    // 不许碰 Room / db 实现」。改这条前先看 settings.gradle.kts 的 L4→L3 说明。
    put(":agent", archCoreModules + ":data")
    // 组合根：唯一能看见全部实现层的模块
    put(":app", archCoreModules + archLeafModules)
}

// ② 源码 import 黑名单（前缀匹配）
val archForbiddenImports: Map<String, Set<String>> = mapOf(
    ":ui" to setOf("com.ake.agent.", "com.ake.mcp.", "com.ake.data."),
    // 「不得依赖 :data 的具体实现」：Room 与 db 包一律不许进 :agent
    ":agent" to setOf("com.ake.mcp.", "com.ake.data.persist.db.", "androidx.room."),
    // 同 `:agent`：`:llm` 只能看见 `modeldist` 这一层决议，Room / db 实体不许越过。
    // 理由是这条边**刚被打开**，最容易被「这里少个 Entity 就顺手 import 一下」磨穿 ——
    // `ModelAssetDao` / `ModelAssetEntity` 若进了 `:llm`，:llm 就绕过了账本状态机直接改库。
    ":llm" to setOf("com.ake.data.persist.db.", "androidx.room."),
    ":core:common" to setOf("com.ake.agent.", "com.ake.mcp.", "com.ake.data."),
)

// 剥掉 /* */ 与 // 注释（保留 URL 里的 :// 不被误判为行注释）
fun String.stripKtsComments(): String =
    replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .joinToString("\n") { it.replace(Regex("(?<!:)//.*$"), "") }

fun File.moduleBuildFile(): File? =
    listOf("build.gradle.kts", "build.gradle").firstNotNullOfOrNull { resolve(it).takeIf { f -> f.isFile } }

tasks.register("checkModuleDependencies") {
    group = "verification"
    description = "断言模块依赖边不越层（Gradle 边白名单 + 源码 import 黑名单）"

    val rootDir = rootProject.rootDir
    val allowedEdges = archAllowedEdges
    val forbiddenImports = archForbiddenImports

    doLast {
        val violations = mutableListOf<String>()

        // ① Gradle 边
        val projectDepRegex = Regex("""project\(\s*"([^"]+)"\s*\)""")
        allowedEdges.keys.sorted().forEach { module ->
            val dir = File(rootDir, module.removePrefix(":").replace(':', '/'))
            val buildFile = dir.moduleBuildFile()
            if (buildFile == null) {
                violations += "$module: 找不到 build.gradle(.kts)，白名单与磁盘不一致"
                return@forEach
            }
            val declared = projectDepRegex.findAll(buildFile.readText().stripKtsComments())
                .map { it.groupValues[1] }
                .toSet()
            val allowed = allowedEdges.getValue(module)
            (declared - allowed).sorted().forEach { dep ->
                violations += "$module → $dep【白名单未列】允许的上游只有 ${allowed.sorted()}"
            }
        }

        // ② 源码 import 边
        val importRegex = Regex("""^\s*import\s+([\w.]+)""")
        forbiddenImports.keys.sorted().forEach { module ->
            val srcDir = File(File(rootDir, module.removePrefix(":").replace(':', '/')), "src")
            if (!srcDir.isDirectory) return@forEach
            val banned = forbiddenImports.getValue(module)
            srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.useLines { lines ->
                        lines.forEach { line ->
                            val fq = importRegex.find(line)?.groupValues[1] ?: return@forEach
                            banned.firstOrNull { fq.startsWith(it) }?.let { hit ->
                                violations += "$module → $hit【源码越层】" +
                                    "  ${file.relativeTo(rootDir)}: import $fq"
                            }
                        }
                    }
                }
        }

        if (violations.isEmpty()) {
            logger.lifecycle("checkModuleDependencies: OK — ${allowedEdges.size} 个模块的依赖边均在白名单内")
            return@doLast
        }
        logger.error("checkModuleDependencies: 发现 ${violations.size} 处架构越层\n" +
            violations.joinToString("\n") { "  ✗ $it" })
        throw GradleException(
            "架构守卫失败：${violations.size} 处越层依赖。若确属必要，请先改 build.gradle.kts 的 " +
                "archAllowedEdges / archForbiddenImports 并在 PR 里说明理由，不要绕过本检查。"
        )
    }
}
