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
// release 签名凭据：**只**从环境变量读取，仓库内不得出现任何密钥或路径。
// 四个变量齐备才算「可签名」；缺任意一个就不挂签名配置 ——
// 这样 PR/CI 在无 Secret 的环境下仍能出包（未签名），而 release 工作流里
// 有 apksigner 校验步骤兜底，签不上会直接红，不会静默发出 debug 签名的包。
// ─────────────────────────────────────────────────────────────────────────────
val releaseKeystorePath = System.getenv("AKE_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("AKE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("AKE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("AKE_KEY_PASSWORD")
val canSignRelease: Boolean = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

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
            if (canSignRelease) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            // ★ 凭据缺失时不挂签名 ⇒ 产出未签名包，由 CI 的 apksigner 步骤拦下
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
    implementation(project(":ui"))

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

    // ── 仅 `ModelDistributionModule` 需要：它要 `AkeDatabase.modelAssetDao()`，
    //    而 `AkeDatabase` 的超类型是 `androidx.room.RoomDatabase`，`:data` 对 Room 用的是
    //    `implementation` ⇒ 不在我们的编译类路径上，拿 DAO 就得自己声明一次 runtime。
    //
    //    ⚠ 这是一处**已知的临时妥协**：正解是把 `provideModelLedgerGateway` 挪进
    //    `:data` 的 `PersistenceModule`（那里 Room 本就在 classpath 上），
    //    但它位于 `data/` 的 Kotlin 源码，不在本人的边界内 ⇒ 留给霍承钧搬，
    //    届时本行与 `ModelDistributionModule` 一起删除。
    //    只引 runtime（不引 room-compiler / ktx）：`:app` 不写任何 Room 代码。
    implementation(libs.androidx.room.runtime)

    testImplementation(libs.bundles.testUnit)
}
