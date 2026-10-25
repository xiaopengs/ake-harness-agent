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
    // ★ 2026-10：聊天编排（L5）。`:app` 是唯一能同时看见 `:chat`（实现）与
    //   `core:ports/Chat.kt`（契约）的地方 ⇒ `ChatRunnerPort` / `SkillRegistryPort`
    //   的 Hilt 绑定只能写在这里（见 `di/ChatPortsModule.kt`）。
    //   `:ui` 仍然只能看见契约（架构守卫 ② 的 `com.ake.chat.` 黑名单），
    //   它注入的是 `Optional<ChatRunnerPort>`，本条边不影响那条纪律。
    implementation(project(":chat"))
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
