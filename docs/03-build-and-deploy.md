# 03 · 构建与部署

> 负责人：韩渡川（DevOps / 部署运维） · Phase 3
> 上游输入：`docs/01-architecture.md`（顾图南）、`docs/02-database.md`（宋砚秋）
> 工程根：`HarnessAgent/` · 包名 `com.ake.harnessagent` · 11 个 Gradle 模块

本文档覆盖：本地构建、CI 流水线、签名与发布、端侧模型分发策略、版本与分支策略、故障排查、上线检查清单。

**阅读前须知**：本项目开发环境**无 Android SDK**，本文档中所有版本号均经官方文档核实，但**构建行为未在本机实跑**。凡未经实跑验证的结论，均以 **[待验证]** 标记，集中在 §10。

---

## 1. 本地构建

### 1.1 前置要求

| 组件 | 版本 | 依据 |
|---|---|---|
| JDK | **17**（Temurin 推荐） | AGP 9.4 最低 & 默认均为 JDK 17（官方 release notes） |
| Gradle | **9.6.0**（用 wrapper，勿用系统 gradle） | AGP 9.4 要求 Gradle 最低 9.6.0；`gradle-wrapper.properties` 已锁 9.6.0 |
| Android SDK Platform | `android-36` | 全模块 `compileSdk = 36`；AGP 9.4 最高支持 API 37 |
| SDK Build Tools | **36.0.0** | AGP 9.4 要求最低 36.0.0 |
| NDK | **不需要** | 工程无 `CMakeLists.txt` / `externalNativeBuild`；LiteRT-LM 以 AAR 形式自带预编译 `.so` |
| Android Studio | 支持 AGP 9.4 的版本 | 用于 IDE 同步；命令行构建不依赖 IDE |

设置 SDK 路径（二选一，推荐环境变量，**不要** 把 `local.properties` 提交上去）：

```bash
export ANDROID_HOME=$HOME/Android/Sdk
# 或 echo "sdk.dir=$HOME/Android/Sdk" > HarnessAgent/local.properties   # 该文件已被 .gitignore 排除
```

### 1.2 常用命令

```bash
cd HarnessAgent

# ① 最快闸门：内核层单测（纯 JVM，毫秒级，:core:common 零第三方依赖）
./gradlew :core:common:test

# ② 纯 Kotlin 模块编译（:mcp 用 ktor KMP，无 Android 绑定）
./gradlew :mcp:compileKotlin

# ③ Room KSP 校验（:data 是最容易静默失败的模块）
./gradlew :data:compileDebugKotlin

# ④ 全模块编译
./gradlew :app:compileDebugKotlin      # 会顺带编译其依赖的 10 个模块

# ⑤ Lint
./gradlew :app:lintDebug

# ⑥ 出包
./gradlew :app:assembleDebug           # universal APK，默认不开 ABI 分包
./gradlew :app:assembleRelease -Pake.abiSplit=true \
                               -Pake.versionCode=2 -Pake.versionName=0.1.0
./gradlew :app:bundleRelease           # 上架用 AAB
```

> **首建耗时**：首次会拉 AGP 9.4.1 + Kotlin 2.3.21 + KSP + Compose BOM + LiteRT-LM，约 10–20 分钟，之后走 Gradle 缓存。

### 1.3 无凭据时的行为（刻意设计）

不带任何签名环境变量时：

- `assembleDebug` → 使用 AGP 自动生成的 `~/.android/debug.keystore`，正常出包
- `assembleRelease` → 正常出包，但**未签名**（`signingConfigs.release` 不会挂载）

这个设计是刻意的：让 PR/CI 在没有 Secret 的环境下也能验证「release 变体能编出来」，而**签名的正确性由 CI 里独立的 `apksigner verify` 步骤把关**，不会静默发出 debug 签名的包。

---

## 2. 版本对应关系（已核实）

### 2.1 核实来源与结论

| 项 | 值 | 来源 |
|---|---|---|
| AGP 9.4.0 最低/默认 Gradle | **9.6.0 / 9.6.0** | `developer.android.com/build/releases/agp-9-4-0-release-notes` |
| AGP 9.4.0 最低/默认 JDK | **17 / 17** | 同上 |
| AGP 9.4.0 SDK Build Tools | 36.0.0 | 同上 |
| AGP 9.4.0 最高支持 API | 37 | 同上 |
| AGP 9.0 KGP 运行时依赖 | KGP **2.2.10** | `agp-9-0-0-release-notes` §「对 Kotlin Gradle 插件的运行时依赖项」 |
| AGP 9.0 KSP 联动 | 低于 `2.2.10-2.0.2` 会被自动抬升 | 同上 |
| KSP 版本策略 | **自 2.3.0 起不再绑定 Kotlin 编译器版本**；AGP 9 支持需 ≥ 2.3.1 | `kotlin/kotlin-agent-skills` AGP9 迁移版本矩阵 |
| 内置 Kotlin | AGP 9.0+ 默认开启，`org.jetbrains.kotlin.android` **必须移除** | `developer.android.com/build/migrate-to-built-in-kotlin` |

### 2.2 本项目的落点（对照检查）

| 项目配置 | 值 | 结论 |
|---|---|---|
| `agp` | 9.4.1 | ✅ 在 9.4 线内 |
| Gradle wrapper | 9.6.0 | ✅ **恰好等于** AGP 9.4 的最低要求，不要降到 9.5.x |
| `compileSdk` / `targetSdk` | 36 / 36 | ✅ < AGP 9.4 上限 37 |
| `minSdk` | 28 | ✅ |
| `kotlin` (KGP) | 2.3.21 | ✅ > AGP 要求的 2.2.10 |
| `ksp` | 2.3.12 | ✅ ≥ 2.3.1（AGP 9 兼容下限） |
| `composeCompiler` | `version.ref = kotlin` ⇒ 2.3.21 | ✅ 与 KGP 同版本（Compose 插件版本自 KGP 2.0 起必须与 KGP 一致） |
| CI JDK | 17 (Temurin) | ✅ |

**结论：版本栈自洽，CI 不会因版本红线直接红。**

### 2.3 需要动态核实的唯一变量

AGP 9 的内置 Kotlin 会**把 KGP 版本抬升到它自己内置的版本**（AGP 9.0 是 2.2.10，AGP 9.4 的内置版本官方未在 release notes 中列出）。若 AGP 9.4 内置 KGP **高于** 2.3.21，则全工程实际使用的 KGP 会是那个更高版本，此时 `composeCompiler 2.3.21` 与 `ksp 2.3.12` 存在不匹配风险。

上机后第一条命令就是它：

```bash
cd HarnessAgent
./gradlew buildEnvironment --console=plain | grep -Ei 'kotlin-gradle-plugin|symbol-processing'
```

- 若解析出 `2.3.21` → 现状即正确，无需处理。
- 若解析出更高版本（如 2.4.x）→ 在根 `build.gradle.kts` 顶部加显式钉版本（官方为「升级到更高 KGP 版本」提供的机制）：

```kotlin
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}
```

并把 `libs.versions.toml` 里的 `kotlin` / `ksp` 同步到解析出的版本（Compose 插件跟随 `kotlin`）。

---

## 3. CI 流水线

### 3.1 文件结构

```
.github/
├── actions/setup-build-env/action.yml   # 复合动作：JDK + SDK + Gradle 缓存
├── workflows/ci.yml                     # push main / PR → 5 个校验作业
└── workflows/release.yml                # tag v* / 手动 → 签名出包
```

### 3.2 触发

| 工作流 | 触发 |
|---|---|
| `ci.yml` | push 到 `main`、PR 到 `main`、`workflow_dispatch` |
| `release.yml` | push tag `v*`、手动 dispatch（填 versionName/versionCode） |

`concurrency` 按 `${{ github.ref }}` 分组 + `cancel-in-progress: true`：PR 连续推送时只跑最新一次，不堵 runner。

### 3.3 五个作业

| # | 作业 | 命令 | 校验什么 | 为什么单独拆出来 |
|---|---|---|---|---|
| 1 | **JVM 层快速校验** | `:core:common:test` `:mcp:compileKotlin` | Outcome / ULID / Redactor 等内核原语；MCP 层编译 | `:core:common` 是 L1，零 Android、零第三方依赖，毫秒级。它应该是流水线里**最先红的那个**——内核错了，后面全无意义 |
| 2 | **编译校验** | 11 个模块 `compileDebugKotlin` / `compileKotlin` | 全量 Kotlin 源码编译 | 刻意不用 `assemble`：编译错误要在编译阶段就暴露，别等到资源合并后 |
| 3 | **KSP 校验** | `:data:compileDebugKotlin` `:app:compileDebugKotlin` + **断言产物文件存在** | Room 注解处理器产出 `AkeDatabase_Impl` 与 ≥8 个 `*_Dao_Impl`；Hilt 产出 `*_HiltComponents*` | KSP 的失败模式和编译不同：它经常**静默不产出**——编译照样过，跑起来建库才炸。所以必须断言文件，而不是只看 exit code |
| 4 | **Lint** | `:app:lintDebug` `:ui:lintDebug` | Android Lint 门禁 + 报告归档 | `:app` 是组合根，只有在它上面才能看见合并后的完整 manifest 与资源；`:ui` 单独跑是因为 Compose 有一批只在这一层的检查项 |
| 5 | **构建 debug APK** | `:app:assembleDebug -Pake.abiSplit=false` + 体积红线 + 上传产物 | 能出可安装 APK；**APK 内不含模型权重**；体积 < 150MB | 这是本项目最有价值的一道闸门，见 §3.5 |

依赖关系：`assemble-debug` `needs: [compile, ksp-verify]`（编译不过就别浪费 20 分钟打包）；1/2/3/4 并行。

### 3.4 缓存策略（控制 CI 时长）

| 缓存 | 路径 | key |
|---|---|---|
| Android SDK | `.android-sdk`（**工作区内**） | `android-sdk-${{runner.os}}-p36-bt36.0.0-ndkfalse` |
| Gradle 依赖 + 构建缓存 | `~/.gradle/caches`、`~/.gradle/wrapper` | `gradle-${{runner.os}}-${{hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties', 'gradle.properties')}}`，带 `gradle-${{runner.os}}-` 作为 restore-key |

两个刻意的设计：

1. **SDK 装在工作区内，而不是用 runner 预装的 `/usr/local/lib/android/sdk`** —— 预装目录每次都从零装、不可缓存；装进工作区后整目录可被 `actions/cache` 命中，第二次起直接跳过 `sdkmanager`（用 runner 自带的 `sdkmanager` 加 `--sdk_root` 装到自定义位置）。
2. **用户级 `gradle.properties` 覆盖守护进程堆到 4GB** —— 项目 `gradle.properties` 写的是 `org.gradle.jvmargs=-Xmx3072m`，Android 全量构建（尤其 release + R8）容易打满。而 `org.gradle.jvmargs` **不能靠命令行 `-D` 覆盖**（JVM 已启动），只能靠优先级更高的 `$HOME/.gradle/gradle.properties`。

### 3.5 模型红线（本项目特有）

CI 环境**没有端侧模型，也绝不允许构建依赖模型**。两道断言：

1. **源码侧**（作业 1）：仓库内（排除 `build/`）不得出现 `*.litertlm` / `*.task` / `*.bin` / `*.gguf`。
2. **产物侧**（作业 5、release）：`unzip -l` APK/AAB，不得出现权重文件；且单包体积 ≤ 150MB。

> 2.6GB 的 Gemma 4 E2B 一旦被误打进 `assets/`，体积必然远超 150MB。这条红线比任何人工 review 都可靠。

### 3.6 当前风险提示

`:browser` / `:agent` 的历史编译阻塞（`DomExtractBridge` 重复 companion、`NonceSource` unresolved、`WebViewFactory` CookieManager 类型、`PageDigest` 缺失）在 commit `5f3b3cb` / `285fb6a` 中已修复（已逐条 grep 确认）。**README §8 的「待修复」清单已过期**，建议同步更新。

CI 是**严格门禁**（Lint `abortOnError = true`）。若首次运行因存量 Lint 问题变红，正确做法是生成基线再逐步清理，而不是把门禁放宽：

```bash
./gradlew :app:updateLintBaseline
# 然后放开 app/build.gradle.kts 里被注释掉的 baseline 行
```

---

## 4. 签名与发布

> **本轮实测结果（含 keystore 生成、四个 Secret、v1–v4 全开、apksigner 真实输出）见 §12.1。**

### 4.1 设计原则

- **release 凭据只走环境变量 / GitHub Secret，仓库内一个字节的密钥都没有**
- `.gitignore` 已排除 `*.jks` `*.keystore` `*.p12` `*.pem` `local.properties` `SECRET.md` `google-services.json`（已确认 git 未追踪任何此类文件）
- debug / release 密钥分离：debug 用 AGP 自动生成的 `~/.android/debug.keystore`
- 混淆映射表 `mapping.txt` 必须留档（否则线上崩溃栈全是 `a.a.b()`）

### 4.2 环境变量清单

| 变量 | 用途 | 是否机密 |
|---|---|---|
| `AKE_KEYSTORE_PATH` | keystore 文件绝对路径 | 否（路径） |
| `AKE_KEYSTORE_PASSWORD` | keystore 密码 | **是** |
| `AKE_KEY_ALIAS` | 密钥别名 | 否 |
| `AKE_KEY_PASSWORD` | 密钥密码 | **是** |
| `AKE_VERSION_CODE` | 覆盖 versionCode | 否 |
| `AKE_VERSION_NAME` | 覆盖 versionName | 否 |

四个签名变量**缺任意一个**即视为「不可签名」，不会挂 release 签名配置。

### 4.3 GitHub Secret 配置清单

在仓库 Settings → Secrets and variables → Actions 中配置（**此处只有名称，不含任何真实值**）：

| Secret 名 | 内容 | 说明 |
|---|---|---|
| `AKE_KEYSTORE_BASE64` | 上传密钥的 **base64 编码** | GitHub Secret 只能存文本，二进制 keystore 必须先 `base64 -w0 upload.jks`；工作流运行时解码到 `$RUNNER_TEMP`，作业结束随 runner 销毁 |
| `AKE_KEYSTORE_PASSWORD` | keystore 密码 | |
| `AKE_KEY_ALIAS` | 密钥别名 | |
| `AKE_KEY_PASSWORD` | 密钥密码 | |
| `AKE_PLAY_SERVICE_ACCOUNT_JSON` | Play Console 服务账号 JSON | **暂未启用**，`release.yml` 中上传步骤已注释 |

生成上传密钥（**只在本机执行一次，产物绝不入库**）：

```bash
keytool -genkeypair -v \
  -keystore ake-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias ake-upload
base64 -w0 ake-release.jks > ake-release.jks.b64   # 内容贴进 Secret
# ⚠ 生成后立刻确认 .gitignore 已覆盖 *.jks *.keystore；不要 commit
```

### 4.4 发布流程

1. 确认 `main` 上 CI 全绿
2. 更新 `gradle.properties` 的 `ake.versionCode`（单调递增）+ `ake.versionName`
3. 打标签并推送：
   ```bash
   git tag -a v0.1.0 -m "release 0.1.0"
   git push origin v0.1.0
   ```
4. `release.yml` 自动运行：还原 keystore → Lint 门禁 → `assembleRelease`（R8 + 资源裁剪 + ABI 分包）→ `bundleRelease` → 体积红线 → **apksigner 校验（确认不是 debug key）** → 上传 artifact → 建 GitHub Release
5. 从 artifact 取 AAB 上传 Google Play 内部轨道做人工验收

### 4.5 回滚

- **Play 上回滚**：内部/封闭轨道保留历史 AAB，直接回退到上一版本 AAB
- **未上架的侧载回滚**：`release-artifacts` 保留 90 天
- **关键**：`versionCode` 单调递增是硬约束，回滚**绝不能**复用已用过的 versionCode；需要发修复版就 +1

---

## 5. 端侧模型分发策略

> **本轮实测结果（客户端期望的 manifest 格式、规范化字节、RSA-256 验签、fail-closed 验证）见 §12.2。**

> 本节是本项目与常规 Android App 最大的差异点：**2.6GB 的 Gemma 4 E2B 不随 APK 分发**。
> `.gitignore` 已排除 `*.task` `*.litertlm` `*.tflite` `*.bin` `*.gguf`。

### 5.1 核心结论

> **主用「应用内下载 + manifest 驱动」；Play Asset Delivery 列为备选；手动导入作为无网/合规兜底。**

### 5.2 模型托管与版本管理（与 App 版本解耦）

**解耦的关键是一个服务端 manifest，App 不硬编码任何模型 URL。**

```
GET https://<model-cdn>/ake/gemma4-e2b/manifest.json
```

```json
{
  "schema": 1,
  "manifestVersion": "2026.10.21-1",
  "models": [
    {
      "id": "gemma4-e2b-it",
      "modelVersion": "gemma4-e2b-it-q8-2026.10",
      "url": "https://<model-cdn>/ake/gemma4-e2b/gemma4-e2b-it-q8.task",
      "sizeBytes": 2791728742,
      "sha256": "<64 hex>",
      "chunkSha256": ["<每 64MB 一片的 hash>"],
      "compatible": {
        "minAppVersionCode": 1,
        "maxAppVersionCode": null,
        "minSdk": 28,
        "abi": ["arm64-v8a"]
      },
      "rollout": { "percent": 100, "allowlist": [] }
    }
  ]
}
```

解耦机制：

- **manifest 自身带版本**（`manifestVersion`），服务端保留历史版本，可单独回滚
- **双向兼容声明**：`compatible.minAppVersionCode` / `maxAppVersionCode` 让「模型→App」也能声明边界，不只是「App→模型」
- **App 侧只做三件事**：拉 manifest → 按 `compatible` 判断能不能用 → 下载校验
- 换模型不用发版，改 manifest 即可；发新版 App 也不会强迫用户重下模型

托管选型建议：**自建对象存储（S3/OSS/COS）+ CDN**，manifest 与权重分离。GitHub Release 可作首发渠道（免运维），但大文件下载速度与限速不友好，且不利于灰度。

### 5.3 校验、断点续传与存储预检

**校验（三层，缺一不可）**

1. **分块 SHA-256**：每 64MB 一片，边下边验。2.6GB 下完才发现坏了，用户已经等了 20 分钟——分片校验能把失败提前到 1 分钟内
2. **整体 SHA-256**：落盘前最后一道，与 manifest 的 `sha256` 比对
3. **魔数/头校验**：`.task` 文件头固定，1 秒内排除「下到 HTML 错误页」这类低级事故

**断点续传**

- HTTP `Range` 请求 + 服务端必须支持 `Accept-Ranges: bytes`
- 落 `.part` 临时文件 + 独立 `.progress` 记录已确认的分片偏移；**重启后从最后一个「已校验通过」的分片继续**，而不是从文件长度继续（避免半片污染）
- 前台服务 `ModelDownloadService`（manifest 已声明 `foregroundServiceType="specialUse"`）保证可见、可暂停、可取消——2.6GB 的下载必须让用户能随时叫停
- 网络约束：`setRequiredNetworkType(UNMETERED)` 提示 + 用户显式同意后才走计量网络

**存储预检（下载前必做）**

- 需要空闲空间 ≈ `sizeBytes × 1.2`（临时文件 + 最终文件 + 校验缓冲）
- 用 `StorageManager.getAllocatableBytes()` 预检，不足时**在下载前**就提示，而不是下到 90% 报 `ENOSPC`
- 目标目录：App 私有外部存储 `getExternalFilesDir()`；`minSdk 28` 无需任何存储权限
- **不要**设 `android:allowBackup="true"`（当前已是 `false`）：2.6GB 权重一旦进备份，用户云空间会爆

### 5.4 分发渠道取舍

| 渠道 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **应用内下载**（自实现） | 完全自控：可换源、可灰度、可 A/B、可按设备能力选量化档；离线侧载也能用；不绑定任何应用商店 | 自建 CDN 流量成本；断点续传/校验/预检要自己写（约等于本项目已在做的 `ModelDownloadService`） | ✅ **主用** |
| **Play Asset Delivery (PAD)** | Google 托管与分发；自带断点续传与完整性校验；按设备配置分发 | ① 单 asset pack 上限约 **512MB**，2.6GB 需拆成 ≥6 个 pack，管理复杂；② 强绑定 Play，与手动导入/内网分发冲突；③ asset pack 更新节奏不完全自主，灰度粒度粗；④ 无法在非 Play 渠道复用 | ⚠️ **备选**：仅当确定只走 Play、且接受多 pack 拆分时采用 |
| **手动导入**（用户自己放文件 / 内网分发） | 无网环境可用；合规友好；零流量成本 | 用户门槛高；校验必须做得更严格（来源不可信） | ✅ **兜底保留**：设置页提供「导入本地模型」，走同一套 hash 校验 |

**为何最终不把 PAD 作为主用**：本项目 MVP 明确「不做云端降级」、强调「完全离线、行为可控」，且存在非 Play 分发场景。PAD 的 512MB/pack 上限还会把一次下载拆成 6 次，反而让「断点续传 + 分块校验」的复杂度转移到了 pack 编排上——而这恰恰是我们最难观测、最难回滚的部分。自控的下载服务虽然要多写代码，但**可观测、可灰度、可回滚**，符合「没有回滚就不上线」的原则。

### 5.5 灰度与回滚

**灰度**

- 按 `rollout.percent` + `hash(deviceId + modelVersion)` 做稳定分桶（同一设备每次结果一致，不会来回横跳）
- 新模型先 5% → 观察 24h → 50% → 100%
- 观察指标：下载成功率、下载耗时 P50/P95、校验失败率、首次推理成功率、首 token 延迟、OOM 率

**模型与 App 不兼容时的处理（核心场景）**

| 场景 | 处理 |
|---|---|
| 新模型要求更新的 App（`minAppVersionCode` > 当前） | 不下载，UI 提示「请升级 App 后使用新模型」；**保留已装旧模型继续可用** |
| 新 App 不再支持旧模型 | 旧模型文件**保留不删**，UI 提示需重新下载新模型；用户在此期间不能跑任务，但数据不丢 |
| 模型下载中途失败/校验不通过 | 清 `.part`，保留旧模型，**自动回退到上一可用模型版本** |
| 新模型上线后线上指标劣化 | **只改 manifest**（把 `modelVersion` 指回上一版），不发 App 版——这是 manifest 驱动最大的价值 |
| App 回滚 | manifest 自身保留历史版本，同步回滚 manifest 到与回滚后 App 兼容的那一版 |

**三条硬规则**

1. **绝不因模型更新删除用户已装的可用模型** —— 删了就断了回退路径
2. **模型版本与 App 版本的兼容矩阵必须双向声明**，而不是只写在 App 里
3. **任何模型变更都必须能只改配置完成回滚**，不需要发版

---

## 6. 版本与分支策略

### 6.1 分支

| 分支 | 用途 | 保护 |
|---|---|---|
| `main` | 主干，始终可出包 | ✅ 需 PR + CI 全绿，禁止直接 push |
| `feat/*` `fix/*` `chore/*` | 功能/修复/杂项 | PR 到 `main`，`concurrency` 自动取消过期运行 |
| `release/*`（可选） | 发布候选，冻结功能只修缺陷 | 从 `main` 切，测完打 tag |

### 6.2 版本号

- 语义化版本 `MAJOR.MINOR.PATCH`；`0.x` 阶段 `MINOR` 表示破坏性变更
- `versionCode` **单调递增，永不复用**（回滚也 +1）
- 打 tag 即发布：`v<versionName>`，例如 `v0.1.0`

### 6.3 提交规范

沿用现有风格（git log 已是这个格式）：

```
feat(ui): ...      fix(agent,data): ...      build: ...      docs: ...      chore: ...
```

跨成员信息一律经主理人中转，**不得直接改别人负责的模块源码**。

---

## 7. 混淆（R8）要点

规则文件：`HarnessAgent/app/proguard-rules.pro`

| 保留对象 | 原因 | 不保留会怎样 |
|---|---|---|
| **kotlinx.serialization** | `:mcp` 用它编解码 MCP 的 JSON-RPC；序列化器是编译期生成的 `$serializer` / `Companion.serializer()` | R8 当死代码删掉，运行时才炸 |
| **Room** | `_Impl` 类靠 `Class.forName("...AkeDatabase_Impl")` 反射实例化；`DAO_Impl` 按字段名拼 SQL | 启动即崩 / SQL 列名错乱 |
| **LiteRT-LM JNI** | AAR 内 `.so` 通过 JNI 名 `Java_包名_类名_方法名` 找实现 | `UnsatisfiedLinkError`，**运行时**才崩，CI 抓不到 |
| **MCP SDK + Ktor** | MCP 走 JSON-RPC over SSE；Ktor 用 ServiceLoader 装 OkHttp engine | 工具调用静默失败 |
| **`@JavascriptInterface`（`DomExtractBridge`）** | JS 侧按**方法名字符串**回调 | 页面注入成功但永远收不到 DOM —— 症状是「Agent 一直看不见页面」，极难归因 |
| **Hilt** | 生成代码按 `@Inject` / 类名绑定；ViewModel 由 `hilt-navigation-compose` 反射创建 | DI 图断裂 |
| **Compose** | `@Composable` 函数被重组调度器按名调用（runtime 自带规则已覆盖大部分） | 重组异常 |

> `Room` / `Compose` / `Hilt` / `lifecycle` 都自带 consumer 规则，AGP 自动合并；`proguard-rules.pro` 只补它们覆盖不到的部分（自家代码 + LiteRT-LM + MCP SDK）。

---

## 8. 故障排查

| 症状 | 原因 | 解法 |
|---|---|---|
| `The 'org.jetbrains.kotlin.android' plugin is no longer required...` | AGP 9 内置 Kotlin，不能再 apply 该插件 | 从模块与 catalog 里删掉（根 `build.gradle.kts` 已处理） |
| `Cannot add extension with name 'kotlin'` | 同上，插件重复注册 | 同上 |
| `SDK location not found` | 未设 `ANDROID_HOME` 且无 `local.properties` | `export ANDROID_HOME=...`；CI 由复合动作注入 |
| `Inconsistent JVM-target compatibility` | Java `compileOptions` 与 Kotlin `jvmTarget` 不一致 | 全模块统一 17（已统一） |
| Gradle 报版本不满足 | wrapper 低于 9.6.0 | 保持 9.6.0，**不要**降 |
| Room 编译过但运行崩 `Cannot find implementation for AkeDatabase` | KSP 静默未产出 | `./gradlew :data:compileDebugKotlin` 后检查 `data/build/generated/ksp/debug/kotlin`；CI 作业 3 会断言 |
| release 包跑起来 `UnsatisfiedLinkError` | LiteRT-LM JNI 被混淆 | 检查 `proguard-rules.pro` §3 是否生效；用 `mapping.txt` 反查 |
| 「模型加载成功但永远不吐字」 | 缺 `uses-native-library`（OpenCL）→ GPU backend 静默失败 | manifest 已声明 `libOpenCL.so` / `libvndksupport.so`；配合 `:llm` 预热探针降级到 CPU |
| `assembleRelease` 出包但未签名 | 四个签名环境变量未齐 | 补齐 Secret；CI 的 `apksigner verify` 会拦下 |
| OOM（Java 堆） | manifest 设了 `largeHeap="false"`，而 WebView + DOM + KV cache 同时驻留 | **[待验证]** 真机压测；必要时改 `true` 或下调上下文分档 |
| CI 首次很慢（10–20 分钟） | Gradle/SDK 缓存未命中 | 第二次起命中缓存，正常 |
| Lint 阻断发布 | 存量问题 | `./gradlew :app:updateLintBaseline` 生成基线，再逐步清理；**不要**放宽门禁 |

---

## 9. 上线检查清单（输入项，供验收环节使用）

### A. 构建与 CI

- [ ] `./gradlew buildEnvironment` 解析出的 KGP/KSP 版本与 catalog 一致（见 §2.3）
- [ ] CI 五个作业全绿，且作业 3 断言出 ≥8 个 `DAO_Impl`
- [ ] 模型红线两道断言均通过（无权重文件、体积 ≤ 150MB）
- [ ] Gradle/SDK 缓存命中，二次运行总时长可接受

### B. 签名与产物

- [ ] 四个签名 Secret 齐备
- [ ] `apksigner verify` 通过且**不是** `androiddebugkey`
- [ ] `mapping.txt` 已随产物归档
- [ ] `versionCode` 单调递增、未复用

### C. 端侧模型

- [ ] manifest 可访问，`compatible` 矩阵与当前 App 匹配
- [ ] 下载 ≥2.6GB 成功；断网重连可续传
- [ ] SHA-256 分块 + 整体验证通过
- [ ] 存储不足时**下载前**即提示，无 `ENOSPC` 崩溃
- [ ] 校验失败时不删旧模型、自动回退上一可用版本
- [ ] 只改 manifest 即可完成模型回滚（无需发版）

### D. 配置一致性

- [ ] 11 个模块 `compileSdk` / `minSdk` / Java 17 / Kotlin jvmTarget 17 全部一致
- [ ] 11 个模块 namespace 唯一（AGP 9 的 `android.uniquePackageNames` 强制要求）
- [ ] 仓库内无任何 `.jks` / `*.keystore` / `local.properties` / 模型权重

---

## 10. 已核实 vs 待真机验证

### ✅ 已核实（有官方来源）

1. AGP 9.4 → Gradle 9.6.0 / JDK 17 / Build Tools 36.0.0 / 最高 API 37
2. AGP 9.0+ 内置 Kotlin 默认开启，`org.jetbrains.kotlin.android` 必须移除（本项目已移除）
3. AGP 9.0 对 KGP 2.2.10 有运行时依赖；KSP 低于 `2.2.10-2.0.2` 会被自动抬升
4. KSP 自 2.3.0 起版本号不再绑定 Kotlin 编译器；AGP 9 需 KSP ≥ 2.3.1（本项目 2.3.12 ✅）
5. Compose 编译器插件版本必须与 KGP 一致（本项目两者均 2.3.21 ✅）
6. `gradle-wrapper.properties` = 9.6.0，与 AGP 9.4 最低要求**精确匹配**
7. 全 11 模块 `compileSdk 36` / `minSdk 28` / Java 17 / Kotlin jvmTarget 17 一致
8. 全模块 namespace 唯一（`com.ake.harnessagent` / `agent` / `llm` / `browser` / `data` / `ui` + 4 个无 namespace 的 JVM 模块）
9. 工程无 CMake / externalNativeBuild ⇒ **不需要 NDK**
10. `.gitignore` 覆盖全部密钥与权重后缀；git 未追踪任何密钥文件
11. `:browser` / `:agent` 的历史编译阻塞（`DomExtractBridge` 重复 companion、`NonceSource`、`PageDigest`、`CookieManager`）**已修复**（已 grep 确认），README §8 清单已过期
12. `:core:common` 是唯一有单测的模块（Outcome / ULID / Redactor / UlidPerf）

### ⚠️ 待真机 / 上机验证（沙箱无 Android SDK，未实跑）

| # | 项 | 验证方法 | 若不通过的兜底 |
|---|---|---|---|
| V1 | AGP 9.4 实际解析的 KGP 版本 | `./gradlew buildEnvironment \| grep -Ei 'kotlin-gradle-plugin\|symbol-processing'` | 按 §2.3 在根 build.gradle.kts 加 `buildscript` classpath 钉版本 |
| V2 | `android { kotlin { compilerOptions { } } }` 嵌套形式 | `./gradlew :app:compileDebugKotlin` | Google 官方文档示例为**顶层** `kotlin { compilerOptions { jvmTarget.set(JVM_17) } }`；若嵌套形式报错则改为顶层 |
| V3 | `signingConfig = signingConfigs.getByName("release")` 赋值形式 | 带齐四个环境变量跑 `assembleRelease` | 改用 `androidComponents { onVariants(selector().withBuildType("release")) { it.signingConfig?.setConfig(...) } }` |
| V4 | `packaging { jniLibs { useLegacyPackaging = false } }` | `assembleRelease` | 该属性在 AGP 8 为 `packagingOptions.jniLibs.useLegacyPackaging`；若解析失败改用 `.set(false)` |
| V5 | ABI 分包实际产出 | `./gradlew :app:assembleRelease -Pake.abiSplit=true` 后看 `app/build/outputs/apk/release/` | LiteRT-LM 可能只提供 `arm64-v8a`；若某 ABI 产物异常小，从 `include(...)` 中移除 |
| V6 | R8 全量混淆后能否正常运行 | release 包真机跑通一次完整任务 | 逐个二分 `proguard-rules.pro` 的 `-keep` |
| V7 | `lint { disable += setOf("MissingTranslation","ExtraTranslation") }` | `./gradlew :app:lintDebug` | Lint 对未知 issue id 会报错，若报 unknown id 则删掉该行 |
| V8 | `largeHeap="false"` 下 2.6GB 模型 + WebView 的内存表现 | 真机压测（低端机优先） | 必要时改 `true` 或下调上下文分档（架构 §4.2） |
| V9 | CI 首次运行全绿 | 推一个 PR 观察 | 若 Lint 存量问题阻断，用 `updateLintBaseline` 生成基线 |

### 📌 顺带发现的配置问题（不影响当前构建，建议后续处理）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| P1 | `settings.gradle.kts` 注释把 `:agent` 归为「纯 Kotlin（零 Android 依赖）」，但它实际是 `androidLibrary` | `settings.gradle.kts:48-51` | 改注释，避免误导后续分层判定 |
| P2 | `protobuf` 插件被 `TEMP-DISABLED-FOR-VERIFY` 注释掉，但仍留着 `datastore` + `protobuf-kotlin-lite` 依赖，且 `data/src/main/proto/` 是空目录 | `data/build.gradle.kts` | 要么恢复插件并补 `.proto`，要么清掉死依赖 |
| P3 | `:mcp` 依赖 `kotlinx-serialization-json` 但未 apply `serialization` 插件 | `mcp/build.gradle.kts` | 当前源码未用 `@Serializable`，暂不报错；一旦有人加 `@Serializable` 就会编译失败，建议提前补 `alias(libs.plugins.serialization)` |
| P4 | `gradle.properties` 的 `kotlin.jsr305=strict` | `gradle.properties:11` | KGP 的 jsr305 严格模式通常用编译器参数 `-Xjsr305=strict` 配置；需确认该 property 是否被 KGP 识别（**待验证**），否则等于没生效 |
| P5 | `README.md` §8「待修复」清单已过期 | `README.md:151-158` | 按 §3.6 同步更新 |
| P6 | `:app` 声明了 `testInstrumentationRunner` 但无 androidTest 依赖与用例 | `app/build.gradle.kts` | 无害；补 androidTest 时再引依赖 |

---

## 11. 我改动的文件清单

| 文件 | 动作 |
|---|---|
| `.github/actions/setup-build-env/action.yml` | 新增 · 复合动作（JDK + SDK + Gradle 缓存） |
| `.github/workflows/ci.yml` | 新增 · 五个校验作业 |
| `.github/workflows/release.yml` | 新增 · 签名出包 |
| `HarnessAgent/app/proguard-rules.pro` | 新增 · R8 规则 |
| `HarnessAgent/app/build.gradle.kts` | 修改 · 签名 / 混淆 / ABI 分包 / 体积 / Lint（**业务源码未动**） |
| `HarnessAgent/gradle.properties` | 追加 · `ake.abiSplit` 开关 |
| `docs/03-build-and-deploy.md` | 新增 · 本文档 |

**未改动**：`core/` `llm/` `browser/` `agent/` `mcp/` `data/` `ui/` 的任何 Kotlin 源码。

---

> 维护：韩渡川（DevOps）
> 范围：release 签名（可安装性）· 模型分发 manifest（可用性）
>
> ★ 本文件里凡是标注「**以代码为准**」的部分，都是从 `data/.../modeldist/` 的
>   **客户端实现里读出来的**，不是设计出来的。改格式前先改客户端，否则产出的
>   manifest 客户端解析不了 —— 那等于没做。

---

## 12. 本轮实测补记：release 签名与模型 manifest

### 12.0. 三十秒速查

```bash
# 出可安装的 release 包（CI 形态：凭据走环境变量）
export ANDROID_KEYSTORE_BASE64=$(base64 -w0 < release.jks)
export ANDROID_KEYSTORE_PASSWORD=...   ANDROID_KEY_ALIAS=ake-release
export ANDROID_KEY_PASSWORD=...
./gradlew assembleRelease --no-daemon --no-parallel --no-build-cache
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
    app/build/outputs/apk/release/app-release.apk

# 出模型分发 manifest
./scripts/model_manifest.py keygen --out-dir .local --key-id model-ca-2026
./scripts/model_manifest.py manifest --file <模型文件> --manifest-version 7 \
    --url https://cdn.ake.cn/models/... --signing-key .local/manifest-signing-private.pem \
    --key-id model-ca-2026 --out dist/model-manifest.json
./scripts/model_manifest.py verify --manifest dist/model-manifest.json \
    --public-key .local/manifest-signing-public.pem
./scripts/model_manifest.py self-test      # fail-closed 的离线证明
```

---

### 12.1 release 签名

#### 12.1.1 ⚠ 仓库里这把 keystore 是什么

`.local/release.jks` 是**工程验证用的自签名 keystore**，生成命令：

```bash
keytool -genkeypair -v -keystore .local/release.jks -storetype PKCS12 \
  -alias ake-release -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "CN=Ake Harness Agent (ENG VERIFICATION ONLY), OU=Engineering, O=AkeLab, L=Shenzhen, ST=Guangdong, C=CN" \
  -storepass "$ANDROID_KEYSTORE_PASSWORD" -keypass "$ANDROID_KEY_PASSWORD"
```

- **它的 CN 里就写着 `ENG VERIFICATION ONLY`** —— 就是为了让任何人在
  `apksigner verify --print-certs` 输出里一眼看见这四个字。
- **它不得用于任何对外发布**：不是 Play 上架密钥，不是渠道包密钥，
  没有做密钥托管/备份/轮换，密码就躺在 `.local/release-credentials.env` 里。
- 正式发布密钥由发布负责人另行生成，走密钥管理服务保管，只以
  `ANDROID_KEYSTORE_BASE64` 的形式进 CI Secret。
- 换密钥时利用 **APK 签名方案 v3 的密钥轮换**（见 §1.5），老用户可无感升级。

#### 12.1.2 凭据怎么传：四个 Secret

| Secret 名 | 含义 | 形态 |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件本体 | 标准 base64，单行，**无换行** |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 | 字符串 |
| `ANDROID_KEY_ALIAS` | key alias | 字符串（本仓库 `ake-release`） |
| `ANDROID_KEY_PASSWORD` | key 密码 | 字符串 |

取值顺序（`app/build.gradle.kts`）：**环境变量 > `local.properties`**。

- **环境变量**：CI / 生产发布唯一途径。
  GitHub Actions 里 `${{ secrets.ANDROID_KEYSTORE_BASE64 }}` → `env:`。
  为什么 keystore 走 **base64 而不是文件路径**：CI runner 的临时目录每次都变，
  Secret 只能存字符串；base64 意味着「Secret 即文件」，不用挂 volume 也不用到处传 artifact。
- **`local.properties`**：本地开发。它已在 `.gitignore`（该文件上一版**根本不存在**，
  本轮补的）。额外支持 `ANDROID_KEYSTORE_FILE=<绝对路径>` ——
  本地有现成 keystore 时不必每次 base64 一遍。**这一项只在 `local.properties` 里认**，
  不作为 CI Secret。

> ⚠ **PKCS12 的坑**：`keytool` 会忽略 `-keypass`（"Different store and key passwords
> not supported for PKCS12"）。所以对本仓库这把 PKCS12 keystore，
> `ANDROID_KEY_PASSWORD` **必须等于** `ANDROID_KEYSTORE_PASSWORD`。
> 构建脚本仍然按四个独立变量读取 —— 换 JKS / PKCS#11 时两者可以不同，不必改脚本。

#### 12.1.3 没 Secret 时的降级行为（fail-safe）

**构建照常成功，产出 `app-release-unsigned.apk`，并打一条醒目 warning。**

这是刻意的：

- 崩掉 ⇒ 没人能在本地验 release 的 R8 / 资源裁剪 / Lint，问题全堆到发版那天；
- 出 unsigned ⇒ `adb install` 必失败，而 release 工作流末尾的
  `apksigner verify` 会直接红 —— **不会静默发出一个「看着是 release 其实是 debug 签名」的包**。

warning 长这样（配置期打印，推到日志顶部）：

```
==============================================================================
  ⚠  RELEASE 不会签名 —— 本次产物是 **app-release-unsigned.apk**，
     `adb install` 会直接失败（INSTALL_FAILED_NO_MATCHING_SIGNATURES / 无签名块）。

  缺少的凭据：ANDROID_KEYSTORE_BASE64（或 local.properties 的 ANDROID_KEYSTORE_FILE）、…
```

实测（本轮）：无凭据 `assembleRelease` → `BUILD SUCCESSFUL` + `app-release-unsigned.apk`。

#### 12.1.4 产物命名

| 状态 | 产物 |
|---|---|
| 已签名 | `app/build/outputs/apk/release/app-release.apk`（+ `.idsig`） |
| 未签名 | `app/build/outputs/apk/release/app-release-unsigned.apk` |

AGP 自己在 `packageRelease` 阶段按「有没有挂 signingConfig」决定后缀，
**不需要**在 `build.gradle.kts` 里手写 `archivesBaseName` 或改 `outputFileName`。

#### 12.1.5 签名方案：v1 + v2 + v3 + v4 全开

AGP 9.4.1 四个开关都可用（`SigningConfig.enableV1Signing/…/enableV4Signing`），全部 `true`。

| 方案 | 为什么开 |
|---|---|
| v1 (JAR) | 侧载渠道与「加固/二次打包」流水线只解析 v1；也是老设备唯一认的方案 |
| v2 | Android 7+ 的硬要求，不开会被 PackageManager 直接拒 |
| v3 | **密钥轮换**：minSdk 28 ✓。将来换签名密钥时老用户可无感升级 |
| v4 | fs-verity，供 `adb install --incremental`；依赖 v2/v3 |

#### 12.1.6 `apksigner verify` 的真实输出（本轮实测）

```
$ apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
Signer #1 certificate DN: CN=Ake Harness Agent (ENG VERIFICATION ONLY), OU=Engineering,
                          O=AkeLab, L=Shenzhen, ST=Guangdong, C=CN
Signer #1 certificate SHA-256 digest: 9da5a19f47fb8d36f453ad828a775fc01e8caa265e82c7850bf2eb9ac9690c30
Signer #1 certificate SHA-1 digest:   2bca65593de0bad7a7a8c7fe96b918c05c9079d7

$ apksigner verify --verbose --min-sdk-version 21 \
      --v4-signature-file app-release.apk.idsig app-release.apk
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): true
```

> ★★ **一个会把人带沟里的坑（本轮实测确认）**
>
> 不指定 `--min-sdk-version` 时，apksigner 取 APK 自己的 `minSdkVersion`（本项目 **28**），
> 于是输出变成 `v1: false / v2: false / v3: true` —— **看起来像没签 v1/v2**。
>
> 那不是没签，是 apksigner 按 minSdk 裁剪了**检查项**（API 28+ 只需 v3）。
> 证据：
>  - APK 里 `META-INF/CERT.SF` `CERT.RSA` `MANIFEST.MF` 都在（v1）；
>  - APK Signing Block 里 `0x7109871a`(v2)、`0xf05368c0`(v3)、`0x42726577`(v4) 三块都在；
>  - 强制 `--min-sdk-version 21` 后 v1/v2/v3 全 `true`。
>
> ⇒ **CI 的验收命令必须显式带 `--min-sdk-version 21`**，否则这条检查什么都验不出来
> （只验 v3 时，v1/v2 被意外关掉也不会红）。v4 还要额外传 `--v4-signature-file`。

---

### 12.2 模型分发 manifest（运维侧）

#### 12.2.1 客户端到底期望什么 —— **以代码为准**

出处：`data/src/main/java/com/ake/data/persist/modeldist/`
（`ModelManifest.kt` / `ManifestParser.kt` / `ManifestCanonicalForm.kt` /
`ManifestTrust.kt` / `StrictJson.kt` / `ManifestTransport.kt`）。

#### 根对象

```jsonc
{
  "schema_version": 1,              // 必填，必须 == 1（ManifestSchema.CURRENT）
  "manifest_version": 7,            // 必填 Long >= 1 ★永远单调递增，回滚也要 +1
  "generated_at": 1700000000000,    // 可选，默认 0
  "models": [ /* 非空数组 */ ],
  "signature": {                    // ★ 必填：缺失 ⇒ MANIFEST_SIGNATURE_MISSING
    "alg": "RSA-SHA256",            // 只认这一个（ManifestTrust.ALG_RSA_SHA256）
    "key_id": "model-ca-2026",      // 必须在 pinnedKeys 里登记过
    "value": "<标准 base64 的签名>"  // 覆盖的是“规范化字节”，不是传输字节
  }
}
```

#### `models[]` 条目

| 字段 | 类型 | 约束 |
|---|---|---|
| `model_id` | string | 非空 |
| `variant` | string | 非空 |
| `revision` | long | >= 0；同 (model_id, variant) 内单调递增，**回滚就是把它指回更小值** |
| `version` | string | 人类可读（`2026.10.5`），**只用于 UI，不参与任何比较** |
| `url` | string | **必须 `https://`**（http 会被客户端硬拒） |
| `size_bytes` | long | > 0 |
| `sha256` | string | **64 位小写 hex**；它是「这份内容」的唯一身份 |
| `min_app_version_code` | int | >= 1 |
| `max_app_version_code` | int \| null | 可缺（缺 == null）；不得小于 min |
| `context_window` | int | > 0 |
| `min_ram_gb` | int | 可选，默认 0，>= 0 |
| `capabilities` | string[] | 可选，默认 `[]` |
| `state` | enum | 可选，默认 `ACTIVE`；`ACTIVE` / `DEPRECATED` / `REVOKED` |

- 同一 `(model_id, variant)` 出现两次 ⇒ `MANIFEST_DUPLICATE_VARIANT`。
- 未知字段**允许**且会被丢弃（不进签名）⇒ 服务端加字段不会打断老客户端。
  反过来：**安全语义不能藏在新字段里**，老客户端不执行。
- JSON 必须是**严格** JSON：无尾逗号、无注释、无重复 key、无 `NaN`/`+1`/`01`
  （`StrictJson.kt`）；重复 key 直接报错。

#### 签名覆盖的「规范化字节」（`ManifestCanonicalForm`）

签名**不**覆盖传输字节，覆盖的是双方各自重新序列化的结果：

```
{"schema_version":1,"manifest_version":7,"generated_at":1700000000000,"models":[{
 "model_id":"…","variant":"…","revision":5,"version":"…","url":"…","size_bytes":…,
 "sha256":"…","min_app_version_code":1,"max_app_version_code":null,
 "context_window":8192,"min_ram_gb":6,"capabilities":["TEXT","TOOLS"],"state":"ACTIVE"}]}
```

规则：**不含** `signature` · 键顺序固定（**不是**字典序）· `models` 按
`(model_id, variant)` 升序 · 缺 `max` 与显式 `null` 等价 · `/` **不转义** · 输出 UTF-8。

> 空格 / 缩进 / 键顺序 / CDN 压缩重写 **都不影响验签** —— 这正是规范化方案的目的。
> 代价是服务端必须实现同一套规范化（≈40 行，见 `scripts/model_manifest.py`）。

#### 12.2.2 生成脚本

`scripts/model_manifest.py`（零 pip 依赖，只需 `python3` + `openssl`）：

| 子命令 | 作用 |
|---|---|
| `keygen` | 生成 RSA-2048 密钥对，打印**要钉进 APK 的公钥串** |
| `manifest` | 由模型文件算 SHA-256 + 大小 ⇒ 规范化 ⇒ 签名 ⇒ 输出 JSON |
| `verify` | 上线前验签（含 alg / 字段检查） |
| `self-test` | 离线自检：**含 fail-closed 负向用例** |

上线前会拦住的客户端硬拒绝项：非 https 的 url、非 hex 的 sha256、
`size_bytes <= 0`、`max < min`、重复 `(model_id, variant)`、未知 `state`。

#### 12.2.3 签名方案：RSA-2048 / SHA-256

- **算法**：`RSA-SHA256`（Java `SHA256withRSA`，PKCS#1 v1.5 over SHA-256）。
- **为什么不是 Ed25519**（`ManifestTrust.kt` 里已论证）：
  `Signature("Ed25519")` 在 Android 上要 **API 31** 才有，而本项目 `minSdk = 28` ——
  用了它，API 28~30 的机器在 `Signature.getInstance` 上抛
  `NoSuchAlgorithmException`，即**老机器永远装不上模型**。minSdk 抬到 31 再换是一行改动。
- **公钥怎么进 APK**：X.509 **SubjectPublicKeyInfo** 的 DER（标准 base64），
  钉在 `ModelManifestConfig.pinnedKeys` 的 `keyId -> spki` 映射里。
  格式错了（比如给裸 modulus / PKCS#1）会让 `X509EncodedKeySpec` **静默失败**，
  真机表现为「所有 manifest 都验签失败」，排查成本极高。
  取串命令：`openssl rsa -in priv.pem -pubout -outform DER | base64 -w0`
  （脚本 `keygen` 直接打印）。
- **私钥怎么保管**：运维侧的密钥管理服务（Vault / KMS / Secrets Manager）。
  **绝不进仓库** —— `.gitignore` 对 `.local/`、`*.pem`、`*.key`、`*secret*` 有四条独立兜底。
- **轮换**：`pinnedKeys` 支持多把钥匙。先同时钉新旧两把 ⇒ 服务端切到新 key 下发 ⇒
  下个版本摘掉旧 key。只钉一把的话，换钥匙必须所有端都升完级才能切。

#### 12.2.4 CDN

**manifest 侧**（客户端行为见 `ManifestTransport.kt`）：

- URL 由代码钉死（`ModelManifestConfig.manifestUrl`），**不**走用户设置、
  不走远程配置 —— 它决定端侧信任什么，必须是全链路最不可变的东西。
- **不接受重定向**（`instanceFollowRedirects = false`）：一次 302 就能把信任边界
  带到另一个域。3xx 会被当失败处理 ⇒ **运维必须把 URL 配成最终地址**。
- 必须 `https://`、`HTTP 200`；`304` 会被当成 `MANIFEST_NOT_MODIFIED`（失败）。
- 响应体上限 **1 MiB**（安全边界，不是优化）。
- 客户端发 `Cache-Control: no-cache`；CDN 侧**不要**给 manifest 设长 TTL ——
  TTL 一拉长，服务端紧急回滚就到不了端上。
- `Content-Type: application/json`。

**模型文件侧**（客户端行为见 `ModelDownloader.kt`）：

- **没有目录结构要求**：`url` 对客户端是不透明字符串，从 manifest 里原样取。
  唯一的硬性要求是 `https://`。建议 `…/<model_id>/<variant>/r<revision>.litertlm`。
- **必须支持 HTTP Range**：客户端断点续传发 `Range: bytes=N-`，
  期望 `206 Partial Content`。返回 `200` 会被理解为「服务端不支持续传」⇒ **从头重下 2.6 GB**。
- 文件内容必须与 manifest 的 `sha256` 逐字节一致；不符 ⇒ `CHECKSUM_MISMATCH`，
  客户端丢弃 `.part` 且**不在本地重试**（由账本裁决，有重试预算）。
- 客户端下载前要求 `size * 1.15` 的可用空间。

#### 12.2.5 fail-closed 的验证

#### ① JVM 单测（**用真实生产代码**，不是重实现）

`llm/src/test/java/com/ake/llm/ManifestFailClosedTest.kt` —— 12 个用例全绿
（`./gradlew :llm:test`，本模块共 50 个用例 0 失败）。

密钥是**运行时现生成的真 RSA-2048 密钥对**；解析走 `ModelManifestParser`、
验签走 `ManifestTrust.rsaSha256Verifier`、规范化走 `ManifestCanonicalForm.render`、
入口走 `ModelDistributionSync.ensureCurrent`（`:app` 的 `ModelDownloadService` 唯一入口）。

核心两条（成对存在，缺一就是假绿）：

| 用例 | 断言 |
|---|---|
| `manifest 签名不对时 不下载任何字节` | Err(`MANIFEST_SIGNATURE_INVALID`)、**`transfer` 从未被调用**、模型目录为空、账本未被开过 |
| `manifest 签名合法时 才会真正下载` | Ok、且 `transfer` **确实被调用** |

其余：内容被篡改 / 签名值坏一个字节 / 未登记 key_id / 未知 alg / 无 signature 块
⇒ 全部拒绝；未配置 ⇒ `MANIFEST_NOT_CONFIGURED` 且不下载。

> 判据取「`transfer` 没被调用」而不是「返回了 Err」：后者在「验签其实恒真、
> 但后面某处也失败了」的情况下也会绿 —— 那是假绿。

#### ② 客户端 ↔ 运维脚本的字节级对齐

两侧（Kotlin `ManifestCanonicalForm.render` 与 Python `render_canonical`）**断言同一串
golden 字节**。它们分别写在
`ManifestFailClosedTest.GOLDEN_CANONICAL` 与 `scripts/model_manifest.py:GOLDEN_CANONICAL`。
任一侧渲染规则漂移 ⇒ 两边各红一次，**不会等到「全网用户同一时刻拉不到模型」才发现**。

#### ③ 脚本侧自检

`./scripts/model_manifest.py self-test`：规范化性质、字段校验、
「有效签名通过 / 篡改被拒 / 换钥匙被拒 / 签名坏一字节被拒」。

---

### 12.3 密钥与凭据的存放（硬约束核对）

| 材料 | 位置 | 是否入库 |
|---|---|---|
| release keystore | `.local/release.jks` | ❌（`.local/`、`*.jks` 双保险） |
| keystore 密码 | `.local/release-credentials.env` / CI Secret | ❌ |
| manifest **私钥** | `.local/manifest-signing-private.pem` / 运维 KMS | ❌（`.local/`、`*.pem`、`*.key` 三保险） |
| manifest **公钥** | 钉进 `ModelManifestConfig.pinnedKeys` | ✅ 公开信息，本就该入库 |
| `local.properties` | 仓库根 | ❌ |

> 本轮开工时仓库里**没有 `.gitignore`** —— `local.properties`、keystore、私钥全部处于
> 「可以被 `git add`」的状态。已补（分层：构建产物 / 本地环境 / 密钥材料）。
> 但 `.gitignore` 只是兜底；真正的安全边界是**凭据只在构建时从外部注入**。

---

### 12.4 已知缺口（如实列出，未自行绕过）

1. ★★ **`ModelManifestConfig` 的绑定在 `:data` 里，本轮没改**（硬约束：不动 `:core:*` / `:data`）。
   `data/.../di/PersistenceModule.kt` 的 `provideModelManifestConfig()` 目前返回
   `ModelManifestConfig.UNCONFIGURED`（空 URL、零公钥），
   即**默认链路仍是关闭的**，`ensureCurrent` 恒返回 `MANIFEST_NOT_CONFIGURED`。
   接线已全部就绪（`:app` 的 `ModelDownloadService` 已在调 `sync.ensureCurrent()`），
   **真正上线前需要宋砚秋在那一个函数里把 `manifestUrl` 与 `pinnedKeys` 填成真值**。
   Hilt 不能从 `:app` 覆盖同一个 `@Provides`（重复绑定）⇒ 我在 `:app` 侧无法代劳。
2. **`ProfileOrigin.SIGNED` 不存在**。实际枚举是
   `{ USER_DIRECT, TEMPLATE_BUILTIN, TEMPLATE_MARKET }`（`core/ports/Chat.kt`）。
   `TrustTier` 也没有 `SIGNED`（是 `USER_AUTHORED / BUILTIN / MARKET_VERIFIED /
   MARKET_QUARANTINED`）。这两个是**人格/模板的信任分级**，与模型分发 manifest
   不是同一条链路 —— 任务描述里的这组关键词对不上代码，按实际代码处理。
3. **没有内置兜底 manifest**：`bundledManifest = null`。首次启动 + 断网 = 没有 manifest
   = 拿不到模型。建议上线前用 `keygen` 的钥匙签一份兜底 manifest 内置进 APK
   （兜底同样必须验签，见 `ModelManifestSource.current()`）。
4. **`docs/` 目录原本不存在**，本文件是新建的（`01` / `02` 等编号文件缺失）。

---

### 12.5 CI 建议（release 工作流）

```yaml
env:
  ANDROID_KEYSTORE_BASE64:     ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
  ANDROID_KEYSTORE_PASSWORD:   ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
  ANDROID_KEY_ALIAS:           ${{ secrets.ANDROID_KEY_ALIAS }}
  ANDROID_KEY_PASSWORD:        ${{ secrets.ANDROID_KEY_PASSWORD }}
steps:
  - run: ./gradlew assembleRelease --no-daemon --no-parallel --no-build-cache
  # ★ --min-sdk-version 21 必带：否则 apksigner 只验 v3，v1/v2 被关掉也不会红
  - run: |
      $ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose \
        --min-sdk-version 21 \
        --v4-signature-file app/build/outputs/apk/release/app-release.apk.idsig \
        app/build/outputs/apk/release/app-release.apk
  # ★ 产物名断言：出成 app-release-unsigned.apk 说明 Secret 没配，直接红
  - run: test -f app/build/outputs/apk/release/app-release.apk
```

PR 构建（`pull_request` 事件拿不到 Secret）会走 §1.3 的降级路径出未签名包 ——
这是预期行为，PR 阶段的目的是验编译 / R8 / Lint，不是出可安装包。
