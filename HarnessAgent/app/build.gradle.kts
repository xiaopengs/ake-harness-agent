import java.util.Base64
import java.util.Properties

// =============================================================================
// :app —— 组合根。Hilt 装配、前台服务、下载服务、入口。
//
// 【Phase 3 / 韩渡川】本文件新增的部分只有构建与发布相关配置：
//   签名（debug/release 分离，凭据走环境变量）· 混淆（R8）· ABI 分包 · 体积优化 · Lint 门禁
//   业务源码一行未动。
//
// ★ 本项目最硬的一条约束：端侧模型（Gemma 4 E2B，约 2.6GB）**不随包分发**，
//   由 App 内下载服务首次启动时拉取。因此：
//     - 这里不得出现任何把权重打进 assets/jniLibs 的配置
//     - 体积红线由 CI 的「模型红线」步骤守着（见 .github/workflows/ci.yml）
// =============================================================================
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ─────────────────────────────────────────────────────────────────────────────
// 版本：优先 -P 命令行 > 环境变量 > gradle.properties 里的 ake.* 默认值
// （gradle.properties 已定义 ake.versionCode / ake.versionName）
// ─────────────────────────────────────────────────────────────────────────────
val appVersionCode: Int =
    (project.findProperty("ake.versionCode") as String?)?.toInt()
        ?: System.getenv("AKE_VERSION_CODE")?.toIntOrNull()
        ?: 1
val appVersionName: String =
    (project.findProperty("ake.versionName") as String?)
        ?: System.getenv("AKE_VERSION_NAME")
        ?: "0.1.0-dev"

// ─────────────────────────────────────────────────────────────────────────────
// release 签名凭据
//
// ★★ 四个 Secret 的名字是**对外契约** —— CI Secret 与 docs/03-build-and-deploy.md
//    必须用同一组名字，改一处要改三处：
//
//      ANDROID_KEYSTORE_BASE64     keystore 文件本体（标准 base64，单行，无换行）
//      ANDROID_KEYSTORE_PASSWORD   keystore 密码
//      ANDROID_KEY_ALIAS           key alias
//      ANDROID_KEY_PASSWORD        key 密码
//
//    为什么 keystore 走 **base64 而不是文件路径**：CI runner 的临时目录每次都变，
//    而 Secret 只能存字符串。用 base64 意味着「Secret 即文件」，不需要再挂 volume
//    或写一步 `download-artifact`。代价只是构建时解码一次（几 KB，可忽略）。
//
// 取值顺序：**环境变量 > local.properties**。
//   - 环境变量：CI 用（GitHub Actions `secrets.*` → `env:`）
//   - local.properties：本地开发用（已在 .gitignore，见仓库根）。
//     额外支持 `ANDROID_KEYSTORE_FILE=<路径>` —— 本地有现成 keystore 文件时不
//     必每次 base64 一遍。**这一项只在 local.properties 里认**，不作为 CI Secret。
//
// 仓库内**不得**出现任何密钥、密码或 keystore 文件。.gitignore 是最后一道兜底，
// 真正的安全边界是「凭据只在构建时从外部注入」—— 文件在盘上也进不了 APK。
//
// ★★ 降级策略：fail-safe，不是 fail-fast
//   PR 构建与「刚 clone 下来」的本地构建都**没有**这几个 Secret，而这两种场景
//   占绝大多数。让 `assembleRelease` 在这种时候崩掉是错的：
//     - 崩掉 ⇒ 没人能在本地验 release 的 R8 / Lint / 资源裁剪，问题全堆到发版那天；
//     - 出 unsigned ⇒ 装不上，但 release 工作流末尾的 `apksigner verify` 会直接红，
//       不会静默发出一个「看起来是 release 其实是 debug 签名」的包。
//   所以：缺凭据 ⇒ 出未签名包 + 一条推到日志顶部的醒目 warning。
// ─────────────────────────────────────────────────────────────────────────────
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

/** 环境变量优先，其次 local.properties；空串与纯空白一律视为「没配」。 */
fun credential(envName: String): String? =
    System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
        ?: localProps.getProperty(envName)?.trim()?.takeIf { it.isNotEmpty() }

val releaseKeystoreBase64: String? = credential("ANDROID_KEYSTORE_BASE64")
val releaseKeystorePassword: String? = credential("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = credential("ANDROID_KEY_ALIAS")
val releaseKeyPassword: String? = credential("ANDROID_KEY_PASSWORD")

/** 仅 local.properties 认的便利项：直接指向盘上的 keystore 文件。 */
val releaseKeystoreFileOverride: String? =
    localProps.getProperty("ANDROID_KEYSTORE_FILE")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 把凭据落成磁盘上真实的文件。
 *  base64 ⇒ 解码到 `build/keystore/`（该目录随 clean 一起消失，且本就在 .gitignore 里）；
 *  文件覆盖 ⇒ 原样使用。**路径缺失 / 文件不存在一律当「没配」**，不抛异常。
 */
val releaseKeystoreFile: File? = run {
    val b64 = releaseKeystoreBase64
    when {
        b64 != null -> {
            val decoded = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            if (decoded == null) {
                logger.warn("ANDROID_KEYSTORE_BASE64 不是合法 base64 ⇒ 本次 release 不签名")
                null
            } else {
                val out = File(
                    layout.buildDirectory.get().asFile,
                    "keystore/release-from-env.jks",
                )
                out.parentFile.mkdirs()
                out.writeBytes(decoded)
                out
            }
        }
        releaseKeystoreFileOverride != null -> {
            val f = File(releaseKeystoreFileOverride!!)
            if (f.isFile) f else {
                logger.warn("ANDROID_KEYSTORE_FILE 指向的文件不存在：${f.absolutePath}")
                null
            }
        }
        else -> null
    }
}

/** 缺了哪几项 —— 用于把 warning 说清楚，而不是笼统一句「签名未配置」。 */
val releaseCredentialGaps: List<String> = buildList {
    if (releaseKeystoreFile == null) {
        add("ANDROID_KEYSTORE_BASE64（或 local.properties 的 ANDROID_KEYSTORE_FILE）")
    }
    if (releaseKeystorePassword == null) add("ANDROID_KEYSTORE_PASSWORD")
    if (releaseKeyAlias == null) add("ANDROID_KEY_ALIAS")
    if (releaseKeyPassword == null) add("ANDROID_KEY_PASSWORD")
}

val canSignRelease: Boolean = releaseCredentialGaps.isEmpty()

if (!canSignRelease) {
    logger.warn(
        """
        |
        |${"=".repeat(78)}
        |  ⚠  RELEASE 不会签名 —— 本次产物是 **app-release-unsigned.apk**，
        |     `adb install` 会直接失败（INSTALL_FAILED_NO_MATCHING_SIGNATURES / 无签名块）。
        |
        |  缺少的凭据：${releaseCredentialGaps.joinToString("、")}
        |
        |  这是**预期内的降级**，不是构建错误：没有 Secret 时 `assembleRelease`
        |  仍应成功出包（否则本地与 PR 构建都没法验 R8 / Lint）。
        |  要产出可安装的 release 包，见 docs/03-build-and-deploy.md §签名：
        |     export ANDROID_KEYSTORE_BASE64=$(base64 -w0 <release.jks>)
        |     export ANDROID_KEYSTORE_PASSWORD=... ANDROID_KEY_ALIAS=... ANDROID_KEY_PASSWORD=...
        |${"=".repeat(78)}
        """.trimMargin(),
    )
}

// ABI 分包开关：默认关（出 universal APK，便于本地与 CI 定位产物），
// 发布时显式传 -Pake.abiSplit=true。见 docs/03-build-and-deploy.md §3.4
val abiSplitEnabled: Boolean =
    (project.findProperty("ake.abiSplit") as String?)?.toBoolean() ?: false

android {
    namespace = "com.ake.harnessagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ake.harnessagent"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // 端侧 Agent 需要可读的崩溃栈跑真机排障；当前未写仪器化测试，
        // 这里先声明 runner，等 :app 补 androidTest 时直接可用。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ─────────────────────────────────────────────────────────────────────
        // ★ 只保留 64 位 ABI —— 这是**门禁**，不是优化 ★
        //
        // 起因（2026-10 首次出包实测）：默认关闭 abiSplit 时出的是 universal 包，
        // AGP 会把依赖里的**全部** ABI 原样打进 APK。实测产物 native-code 声明了
        // 4 个 ABI，而 `liblitertlm_jni.so` 只有 arm64-v8a / x86_64 两个版本：
        //
        //   lib/arm64-v8a/liblitertlm_jni.so  21 MB   ✓
        //   lib/x86_64/liblitertlm_jni.so     25 MB   ✓
        //   lib/armeabi-v7a/  → 只有 graphics.path / sqliteJni，无 litertlm  ✗
        //   lib/x86/          → 同上                                          ✗
        //
        // 后果比「装不上」更糟：armeabi-v7a / x86 机器**能装上**（因为清单里声明了
        // 该 ABI），启动后在 Engine.initialize() 处才炸 UnsatisfiedLinkError。
        // 用户视角是「下载 96MB、装好、点开就闪退」，且日志里看不出是 ABI 问题。
        //
        // 与其让它在运行时崩，不如让 PackageManager 直接判不兼容
        // （INSTALL_FAILED_NO_MATCHING_ABIS）—— 反正 2.6 GB 权重在 32 位进程
        // 的地址空间里本来就放不下，这条路径**不可能**跑通。
        // 下面的 splits 只在 -Pake.abiSplit=true 时生效，管不到 universal 包，
        // 所以必须在这里用 abiFilters 兜住。
        // ─────────────────────────────────────────────────────────────────────
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    // ── 一致性：与其余 10 个模块完全对齐（Java 17 / Kotlin jvmTarget 17）──
    // 不一致会直接报 "Inconsistent JVM-target compatibility"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9：kotlinOptions 已移除，改由内置 Kotlin 的 compilerOptions 指定
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 签名：debug / release 分离
    // ─────────────────────────────────────────────────────────────────────────
    signingConfigs {
        // debug 沿用 AGP 自动生成的 ~/.android/debug.keystore（不入版本库）
        create("release") {
            if (canSignRelease && releaseKeystoreFile != null) {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword

                // ── 签名方案矩阵（AGP 9.4.1）
                //   v1（JAR 签名）：minSdk 28 及以下的旧设备只认它；不带 v1 的包在
                //     Android 6 及更早（本工程 minSdk 28 ⇒ 严格说用不到）装不上。
                //     留着的真实理由是**第三方渠道与侧载工具的兼容性**：
                //     部分分发平台与「APK 加固/二次打包」流水线只解析 v1。
                //   v2（APK 签名方案 v2，Android 7.0+）：**必须开**。不开 v2 的包在
                //     Android 7+ 上会被 PackageManager 直接拒（v1 只校验 ZIP 条目）。
                //   v3（Android 9+，minSdk 28 ✓）：**必须开**。它的价值是**密钥轮换** ——
                //     带 v3 的包可以在不重新打签名块的前提下证明「新密钥经旧密钥授权」，
                //     将来换签名密钥时老用户能无感升级。这是本项目迟早要做的事
                //     （keystore validity 10000 天，但**密钥泄露**没有到期日）。
                //   v4（Android 11+，fs-verity）：给 `adb install --incremental` 与
                //     Play 的增量下发用，**必须依赖 v2 或 v3**。它对安装成功与否没有影响，
                //     但能让 2.6 GB 模型的 APK 侧载与调试迭代快一个量级。
                //     实测：AGP 9.4.1 支持该开关；若将来 AGP 升级移除它，构建会在
                //     配置期直接报 unresolved reference，不会静默退化。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 端侧 Agent 排障高度依赖日志、DOM dump 与完整调用栈 ⇒ debug 一律不裁剪
            isMinifyEnabled = false
        }
        release {
            // R8 代码压缩 + 资源裁剪。规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // ★ 凭据缺失时不挂签名 ⇒ 产出 `app-release-unsigned.apk`，
            //   由 release 工作流末尾的 apksigner 步骤拦下（见 docs/03-build-and-deploy.md）
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ABI 分包与体积优化
    //
    // 取舍：端侧 App 的用户要额外下载 2.6GB 模型，安装包的每一 MB 都在
    // 和用户抢首次体验的耐心。minSdk 28 的设备里 32 位已基本退出主流，
    // 而 Gemma 4 E2B 在 32 位进程上也装不下 2.6GB 权重 ⇒ **只保留 64 位**。
    //   - arm64-v8a：真机主力
    //   - x86_64   ：模拟器/开发机（LiteRT-LM 若未提供该变体，产物只是更小，不会失败）
    //   - 不放 armeabi-v7a：装不下模型，留着只会让用户下了一个跑不起来的包
    //   - isUniversalApk = true：保留一个通用包给侧载与手动分发渠道
    // ─────────────────────────────────────────────────────────────────────────
    splits {
        abi {
            isEnable = abiSplitEnabled
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // 依赖带进来的元数据，跟运行时无关
        resources.excludes += "META-INF/*.version"
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.excludes += "META-INF/INDEX.LIST"
        // .so 以「未压缩 + 页对齐」方式存进 APK（extractNativeLibs=false）：
        // 安装后不再解压一份副本 ⇒ 省设备存储；代价是下载体积略增，
        // 但 Google Play 对 AAB 会重新压缩，实际分发体积不受影响。
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        // CI 上 Lint 不过就红。若存量问题一时清不完，先跑
        // ./gradlew :app:updateLintBaseline 生成基线，再把下面一行打开：
        // baseline = file("lint-baseline.xml")
        abortOnError = true
        warningsAsErrors = false
        checkTestSources = false
        // 端侧离线 App 未做多语言，翻译类噪声关掉
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // ★ 2026-10（关千锤 P1-2）：`ColdStartWiringTest` 从"扫源码字符串"改成
            //   "真的把 `AkeApp.onCreate()` 跑一遍"。后者必然要碰 `Application` 的父类方法
            //   与 `android.util.Log`（`AkeLog.install(AndroidLogSink)`），而单元测试用的是
            //   桩 android.jar —— 默认实现是"抛 RuntimeException("Stub!")"。
            //   打开这一项后，桩方法返回类型默认值（void → no-op），于是
            //   `Application.onCreate()` / `Log.i()` 可以在 JVM 上被调用而不炸。
            //
            // ⚠ 代价要写清楚：它让**所有** :app 单测里的 Android 调用都变成静默 no-op
            //   （比如误以为 `Context.getFilesDir()` 返回了目录，实际是 null）。
            //   `:app` 的单测只该验证"组合根有没有接线"，不该验证 Android 行为 ——
            //   真要验证后者就得上 Robolectric/仪器化，那是另一件事。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:contract"))
    implementation(project(":core:ports"))
    implementation(project(":core:model-api"))
    implementation(project(":llm"))
    implementation(project(":browser"))
    implementation(project(":agent"))
    implementation(project(":mcp"))
    implementation(project(":data"))
    // ★ 2026-10：聊天编排（L5）。`:app` 是唯一能同时看见 `:chat`（实现）与
    //   `core:ports/Chat.kt`（契约）的地方 ⇒ `ChatRunnerPort` / `SkillRegistryPort`
    //   的 Hilt 绑定只能写在这里（见 `di/ChatPortsModule.kt`）。
    //   `:ui` 仍然只能看见契约（架构守卫 ② 的 `com.ake.chat.` 黑名单），
    //   它注入的是 `Optional<ChatRunnerPort>`，本条边不影响那条纪律。
    implementation(project(":chat"))
    implementation(project(":ui"))
    // ★ 2026-10：端云推理装配（docs/08 §8.2 / docs/10 §6.3）。
    //   `:app` 是**唯一**能同时看见 `:llm`（端侧引擎）与 `:cloudai`（云端适配器）
    //   的地方 ⇒ E6 删除之后，`ModelRouter` 的装配**只能**落在这里（依赖倒置）。
    //   `:inference` 被传递进来，是为了让 `ChatServices` 能拿到 `DialogRoutingPort`。
    implementation(project(":inference"))
    // ★ `:cloudai` 也需显式声明：`:inference` 用 `implementation` 引入它，
    //   不会传递。组合根要 `new OpenAiCompatibleDialogPort(...)` 就必须看得见它。
    implementation(project(":cloudai"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // ── MCP 装配（霍承钧 MCP_BRIDGE.md §4）
    // :app 是唯一能同时看见 :mcp(L3) 与 :agent(L4) 的组合根，桥接类放这里；
    // `project(":mcp")` 已在上面的模块列表里声明，这里只补 ktor engine。
    // McpHiltModule 显式用 OkHttp engine（Android 上默认 engine 有连接复用问题）。
    implementation(libs.ktor.client.okhttp)

    // ★ `room-runtime` 曾于上一轮删除：它原本只为 `ModelDistributionModule` 里那行临时的
    //   `provideModelAssetDao(db)` 服务。账本装配已搬进 `:data` 的 `PersistenceModule`
    //   （`provideModelAssetLedger`），`:app` 只注入 `ModelAssetLedger` 本身，不碰 Room。
    //
    // ★★ 2026-10 重新加回（关千锤 P0-2 的直接后果），理由与上一轮不同、也不冲突：
    //   `TemplateBootstrap.bootstrap()` 的入参是三张表的 **Room DAO**
    //   （`TemplateDao` / `AgentProfileDao` / `CapabilityGrantDao`），而
    //   `:data` 的 `PersistenceModule` 只 `@Provides AkeDatabase`，**没有**把这三个 DAO
    //   单独暴露成 Hilt 绑定。`:app` 要拿到 DAO，只能先拿到 `AkeDatabase`；
    //   而要**引用** `AkeDatabase`（它继承 `androidx.room.RoomDatabase`），
    //   编译期就必须能看见 `RoomDatabase` 这个父类型 —— 缺它时 Kotlin 报的是
    //   「Cannot access 'androidx.room.RoomDatabase' which is a supertype of 'AkeDatabase'」。
    //
    //   ⇒ 这条依赖不是「又想在 :app 里写 SQL」，而是「组合根必须能拿到 :data 门户对象的类型」。
    //     **纪律不变**：`:app` 依然不写任何 DAO 查询，只把三个 DAO 原样交给
    //     `:data` 自己的 `TemplateBootstrap`（它是模板/授权的唯一写入口）。
    //     真要彻底去掉这一行，得由 `:data` 把 `TemplateBootstrap` 包成一个
    //     `@Inject` 门面（那属于宋砚秋的模块，本轮不动）。
    implementation(libs.androidx.room.runtime)

    testImplementation(libs.bundles.testUnit)
}
