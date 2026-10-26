import java.util.Properties

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * ## 为什么这里没有 protobuf 插件了（2026-10 清理）
 *
 * 此前本文件留着一段被注释掉的 `alias(libs.plugins.protobuf)` 与 `protobuf {}`
 * 配置块，同时挂着 `androidx-datastore` + `protobuf-kotlin-lite` 两条依赖，
 * 而 `src/main/proto/` 是空目录。当时全模块对 DataStore / Proto 的**真实引用为 0**
 * （逐行 grep 代码确认，命中的全是注释和 `WipeEverythingUseCase` 里那句
 * `File(filesDir, "datastore").deleteRecursively()`）。
 *
 * 裁定：**移除**。理由是——
 *  1. "恢复插件 + 补 proto 定义"这条路在当前状态下只会产出更糟的死代码：
 *     没有 Store 实现的 proto 定义＝编译能过、运行时没人消费的第二套事实来源。
 *     而 Store 实现属于 `:data` 源码，不在本次改动范围内。
 *  2. DataStore Proto 在架构里**确实有位置**（数据库 F7：settings / domain_policy /
 *     mcp_servers 三个独立实例），但它 "尚未开工"，不是 "已被废弃"。
 *     所以 catalog 里两条坐标**保留并标注为预留**（版本是校验过的，白删了下次还要重新核），
 *     `:data` 侧的依赖边和空目录则删干净 —— 到这里为止账实相符。
 *  3. 真正触发它回归的信号是：某个 Port 需要一个 `:data` 里的 Store 实现时
 *     （本轮新加的 `SettingsStorePort` / `McpServerStorePort` 就是候选），
 *     由 `:data` 的实现方一次性把插件 + proto + 实现三件套补齐。
 *     成本是一次 `--offline` 失效条件下的三方改动，比长期挂着不敢删要健康。
 */

// =============================================================================
// ★ 模型分发 manifest 的地址（构建期注入 · 宋砚秋）
//
// ★★ 为什么 URL 不能写死在 Kotlin 里：它是**部署目标**，不是代码事实。
//   换 CDN、加镜像、给 staging 指向另一套桶 ⇒ 都只该改构建环境的一行，
//   而不是去动 `modeldist/` 下那份会审过的源码。
//
// ★★ 为什么也不能反过来把它做成「用户设置」或「远程下发」：
//   它决定端侧**信任谁**。可写的配置意味着一条 adb / 一份备份就能把全网的
//   模型来源换掉（docs/03 §2.4：一次 302 都能把信任边界带到另一个域，
//   所以连重定向都不跟）。它是全链路里最不可变的东西 ⇒ **只能编译期钉进 APK**。
//
//   ⇒ 结论：**三级注入**（优先级从高到低）
//       1. `-Pake.modelManifestUrl=…`   命令行（临时指向另一套 CDN 试试）
//       2. `AKE_MODEL_MANIFEST_URL=…`   环境变量（CI Secret，与 ANDROID_KEYSTORE_* 同规格）
//       3. `local.properties` 的同名键     本地开发（本文件已在 .gitignore）
//
//   三者都没有 ⇒ **空串**，即「本次构建不接分发链路」：产物的行为与今天一样，
//   `ensureCurrent` 恒返回 MANIFEST_NOT_CONFIGURED（fail-closed，不下载也不崩），
//   并且构建时打一条醒目 warning —— 与 `app/build.gradle.kts` 里 release 签名凭据
//   缺配时的降级风格一致（出包 + 把问题喊出来，而不是让要发布那天才发现）。
//
// ⚠ 与之相反：**公钥不走这条链路**，它钉在源码的 `ModelManifestPins.kt` 里。
//   理由是「谁有权决定端侧信任什么」——见那个文件的类注释。
// =============================================================================
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun trimmedOrNull(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

/**
 * manifest 地址。空串 = 未配置 ⇒ 运行时 MANIFEST_NOT_CONFIGURED（fail-closed）。
 *
 * URL 里不会合法出现 `"` 与 `\`，转义只做「注入值意外带了它们时不把 BuildConfig 编译崩」
 * 这一层兜底 —— 真带上这种字符时，交给 `ModelManifestConfigFactory` 判非法并降级。
 */
val modelManifestUrl: String =
    trimmedOrNull(project.findProperty("ake.modelManifestUrl") as String?)
        ?: trimmedOrNull(System.getenv("AKE_MODEL_MANIFEST_URL"))
        ?: trimmedOrNull(localProps.getProperty("AKE_MODEL_MANIFEST_URL"))
        ?: ""

if (modelManifestUrl.isEmpty()) {
    logger.warn(
        """
        |
        |${"=".repeat(78)}
        |  ⚠  模型分发链路**未配置** —— 本次产物拿不到服务端 manifest，
        |     首次启动的用户只能走到 `MANIFEST_NOT_CONFIGURED`（fail-closed：不下载、不崩）。
        |
        |  注入方式（三选一，缺一不可）：
        |      ./gradlew … -Pake.modelManifestUrl=https://<cdn>/ake/manifest.json
        |      export AKE_MODEL_MANIFEST_URL=https://<cdn>/ake/manifest.json   # CI 走这条
        |      local.properties: AKE_MODEL_MANIFEST_URL=https://<cdn>/ake/manifest.json
        |
        |  注意 URL 必须是 https 的**最终地址**（客户端不跟随重定向）。
        |  验签公钥不在这里配 —— 它钉在源码 `ModelManifestPins.kt`，改它要发版（这是刻意的）。
        |${"=".repeat(78)}
        |
        """.trimMargin(),
    )
} else if (!modelManifestUrl.startsWith("https://") || modelManifestUrl.any { it.isWhitespace() }) {
    logger.warn("AKE_MODEL_MANIFEST_URL 看着不像一个合法的最终地址：$modelManifestUrl ⇒ 运行时会拒绝启用")
}

android {
    namespace = "com.ake.data"
    compileSdk = 36

    // ModelManifestConfig 要在装配期读到注入值 ⇒ 本模块必须生成 BuildConfig。
    // 全仓默认 false（根 gradle.properties 的 android.defaults.buildfeatures.buildconfig），
    // 只在真正需要的模块打开。
    buildFeatures { buildConfig = true }

    defaultConfig {
        minSdk = 28
        // 值来自上面的三级注入；URL 里合法字符不含引号与反斜杠，转义只为兜住手误。
        buildConfigField(
            "String",
            "MODEL_MANIFEST_URL",
            "\"${modelManifestUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))

    // ── Room 2.8.5 + BundledSQLiteDriver（数据库 F5/F6 拍板）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite.bundled)

    // （DataStore / Proto 依赖已于 2026-10 移除，理由见本文件头部注释。）

    implementation(libs.androidx.core.ktx)
    // 清理作业（FULL→DIGEST→删除）与空闲维护走 WorkManager：约束 idle + 非低存储
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.bundles.testUnit)
}

/**
 * ★ Room schema 导出位置（2026-10 · 霍承钧）
 *
 * `exportSchema = true` 只是「导出」，**导出到哪**默认由 Room 决定（各 AGP 版本不尽相同）。
 * 本轮把它钉死在 `data/schemas/`，因为 `AkeMigrationSchemaTest` 要读 `3.json`
 * 与 `AkeMigrations.MIGRATION_2_3` 的手写 DDL 逐列比对 ——
 * 那条断言必须有一个**确定的、签入 git 的**路径，否则「改了 Entity 忘了改迁移」
 * 这件事仍然只能靠人肉比对发现。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
