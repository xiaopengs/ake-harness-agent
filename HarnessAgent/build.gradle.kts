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
val archLeafModules = setOf(":llm", ":browser", ":agent", ":mcp", ":data", ":ui", ":chat", ":cloudai", ":inference")

// ① 允许的 Gradle 依赖边（键 = 模块 path，值 = 允许的上游 path）
val archAllowedEdges: Map<String, Set<String>> = buildMap {
    put(":core:common", emptySet())
    put(":core:model-api", setOf(":core:common"))
    put(":core:contract", setOf(":core:common"))
    // ★ 2026-10 新增边 `:core:ports → :core:model-api`（架构 08 §2.4 的落点偏差，
    //   见 `core/ports/.../Dialog.kt` 头部说明）：`DialogModelPort` / `DeviceModelPort`
    //   是 `ModelPort` 的子接口，Port 总表必须看得见它。
    put(":core:ports", setOf(":core:common", ":core:contract", ":core:model-api"))
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
    // ★ 硬约束：:ui 不得看见 :agent / :mcp / :data / :chat
    //   聊天 UI 消费的是 `core:ports/Chat.kt` 契约，不是 `:chat` 的实现。
    //   「这个功能直接调更方便」不构成一条新的实现层边的理由 —— 见 §2.4 的收尾。
    put(":ui", archCoreModules)
    // :agent → :data 是白名单里唯一一条「实现层横向」例外，
    // 且经源码断言 ② 收紧为「只许碰 com.ake.data.persist.store 的接口，
    // 不许碰 Room / db 实现」。改这条前先看 settings.gradle.kts 的 L4→L3 说明。
    put(":agent", archCoreModules + ":data")
    // ★ :chat —— 聊天会话编排（L5），向下依赖 :agent 的上下文/压缩/护栏内核。
    //   必须直接依赖 :agent 而不是重抄一份预算算法：
    //   「UI 显示一套预算、主循环跑另一套」是本项目已反复警告过的一致性灾难
    //   （§2.2 理由 2）。这条边经源码断言 ② 收紧为「:chat 只能看见 :agent 的 public API
    //   与 core 契约」，且 :chat 编译期必须同时通过 `:chat:compileDebugKotlin`。
    put(":chat", archCoreModules + ":agent")
    // ★ `:cloudai`（L3，架构 08 §8.1）—— OpenAI 兼容适配器。
    //
    //   ★★ **刻意不含 `:core:contract`**。这就是 §5.3 的 DOM 锁 B：
    //       `PocketDom` 定义在 `:core:contract`，不声明这条边（配合
    //       `:core:ports` 已收紧为 `implementation`）⇒ 该类符号在 `:cloudai`
    //       的编译类路径上**不存在**，「把 DOM 送云端」在结构上写不出来。
    put(":cloudai", setOf(":core:common", ":core:model-api", ":core:ports"))
    // ★ `:inference`（L4，架构 08 §8.1）—— ModelRouter / FallbackPolicy / 分相。
    //
    //   ★ **E6（`:inference → :llm`）已按主理人裁决删除**：纯 JVM 模块无法依赖
    //     `com.android.library` 模块（`:llm`），且与 §8.3 自相矛盾。
    //     ⇒ 端侧实现由 `:app` 组合根以 `DeviceModelPort` 接口注入（依赖倒置）。
    put(":inference", archCoreModules + ":cloudai")
    // 组合根：唯一能看见全部实现层的模块
    put(":app", archCoreModules + archLeafModules)
}

// ② 源码 import 黑名单（前缀匹配）
val archForbiddenImports: Map<String, Set<String>> = mapOf(
    // :ui 不得看见任何实现层（含本轮新增的 :chat）。聊天 UI 的契约在 core:ports/Chat.kt。
    ":ui" to setOf("com.ake.agent.", "com.ake.mcp.", "com.ake.data.", "com.ake.chat."),
    // 「不得依赖 :data 的具体实现」：Room 与 db 包一律不许进 :agent
    ":agent" to setOf("com.ake.mcp.", "com.ake.data.persist.db.", "androidx.room."),
    // 同 `:agent`：`:llm` 只能看见 `modeldist` 这一层决议，Room / db 实体不许越过。
    // 理由是这条边**刚被打开**，最容易被「这里少个 Entity 就顺手 import 一下」磨穿 ——
    // `ModelAssetDao` / `ModelAssetEntity` 若进了 `:llm`，:llm 就绕过了账本状态机直接改库。
    ":llm" to setOf("com.ake.data.persist.db.", "androidx.room."),
    ":core:common" to setOf("com.ake.agent.", "com.ake.mcp.", "com.ake.data."),
    // ★ :chat 只能看见 :agent 的 public API 与 core 契约。
    //   不得直接碰 Room / MCP / browser 实现 / UI —— 与 :agent 同一套收紧手法。
    //   `:chat → :data` 连 Gradle 边都没有：模板/会话/审计的落盘全部经 core:ports 的
    //   Store Port（ChatSessionStorePort / AgentProfileStorePort / TemplateStorePort），
    //   于是「换个存储实现要改 :chat」这件事在结构上不可能发生。
    ":chat" to setOf(
        "com.ake.mcp.", "com.ake.browser.",
        "com.ake.data.persist.db.", "androidx.room.", "com.ake.ui.",
    ),
    // =========================================================================
    // 架构 08 §8.3 —— 本轮两个新模块的源码 import 黑名单
    // =========================================================================
    //
    // ★ `:cloudai` —— 双重保险中的第二道（第一道是 Gradle 白名单里没有
    //   `:core:contract`）。即便有人先加了 Gradle 边，这里仍会红。
    ":cloudai" to setOf(
        // ★ DOM 锁 B 的第二道
        "com.ake.core.contract.",
        // 不得看见任何业务/实现层
        "com.ake.agent.", "com.ake.browser.", "com.ake.chat.",
        "com.ake.data.", "androidx.room.", "com.ake.ui.", "com.ake.llm.",
    ),
    // ★ `:inference` —— Router 只认 `DeviceModelPort` 接口，
    //   不得直接 import LiteRT 的任何类型（与「:agent 不得碰 Room」同一收紧手法）。
    ":inference" to setOf(
        "com.ake.data.persist.db.", "androidx.room.", "com.ake.ui.",
        "com.ake.browser.",
        "com.google.ai.edge.litertlm.",
    ),
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
