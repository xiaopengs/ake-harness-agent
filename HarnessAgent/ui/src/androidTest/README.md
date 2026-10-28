# `:ui` 的 Compose 界面测试 —— 它们在哪跑、证明了什么、证明不了什么

> 本目录下的 4 个测试类共 **15 条用例**（`CapabilityPickerUiTest` 3 +
> `MessageBubbleUiTest` 4 + `SessionRowUiTest` 4 + `TerminalBannerUiTest` 4）。
>
> **2026-11 之前它们一条都没执行过** —— 只被 `compileDebugAndroidTestKotlin` 编译过。
> 现在它们会在 `./gradlew :ui:testDebugUnitTest` 里跑（Robolectric 宿主）。

---

## 一、这些是 **Robolectric 下的验证，不等于真设备验证**

这一条不要省略，也不要倒过来读。本项目最不能接受的就是把一种验证说成另一种。

### ✅ Robolectric 能证明

| 类别 | 例子 |
| --- | --- |
| Compose 树的**结构** | 勾框节点有 8 个、顺序与 `options` 一致 |
| **状态驱动的可见性 / 禁用态** | 满额后未勾的 `Checkbox` `enabled=false`、上限文案出现 |
| **文案内容** | 失败脚注的 title / next / 重试按钮一个不缺；缺数据时退回占位文案 |
| **相对布局关系** | 用户气泡的左边界在助手气泡右边 |
| **点击回调** | 点重试按钮，`onRetry` 真的被调用了一次 |

### ❌ Robolectric 证明不了

| 类别 | 说明 |
| --- | --- |
| **真实渲染像素** | 没有 Surface，没有 Choreographer 帧回调，没有 PixelCopy。`TerminalBannerUiTest` 里读像素的 2 条用了 `Assume` 显式跳过 —— **skip ≠ pass** |
| **真 WebView 行为** | M10（DOM 抽取、JS 注入）必须真 WebView，Robolectric 救不了，那部分不在这里 |
| **ART 上的真实时序** | 帧节奏、动画、`Choreographer`、跨进程、真实 IME 都不在模拟范围内 |
| **真实字体 / 文本测量** | Robolectric 下文本节点尺寸是退化的（实测同一段文案宽度只有 5dp），任何依赖**绝对**像素尺寸的断言都不可信 —— 这也是为什么 `MessageBubbleUiTest` 只断言**相对**位置 |
| **真实的设备配置** | Robolectric 默认视口是 **320dp × 470dp**，比任何真机都小；本目录里 `qualifiers` 也未改，所以"内容放得下放不下"在这套用例里**不能**当作真机结论 |

---

## 二、它们是怎么跑起来的（改动都在 `ui/build.gradle.kts`）

1. `sourceSets.test` 把 `src/androidTest/java` 同时登记为 **`java.srcDirs` 和
   `kotlin.srcDirs`**。后者是必须的：AGP 9 的内置 Kotlin 编译**不**从
   `java.srcDirs` 派生 Kotlin 源集 —— 只加前者时 `:ui:compileDebugUnitTestKotlin`
   产物里一个 `*UiTest.class` 都没有。
2. 测试类上的 `@RunWith(AndroidJUnit4::class)` **没改**。`androidx.test.ext:junit`
   的 `AndroidJUnit4` 在非 Android 运行时会自动把执行委派给
   `org.robolectric.RobolectricTestRunner`（`getRunnerClassName()` 就是这么写的），
   所以一份源码同时服务两个源集：
   · `:ui:testDebugUnitTest` → Robolectric（今天就在跑）
   · `connectedDebugAndroidTest` → 真机/模拟器（等设备到位）
3. Robolectric 的 SDK 钉在 `src/test/resources/robolectric.properties` 的
   `sdk=35`（**不是** 模块的 `compileSdk = 36`）。原因写在该文件里：SDK 36 要求
   Java 21，本机只有 JDK 20。
4. `testOptions.unitTests.isIncludeAndroidResources = true` —— 用例到处在用
   `Context.getString(R.string.*)`，不加这条读不到 strings.xml。

---

## 三、首次真跑暴露的问题（都记在各测试类的 KDoc 里）

| 用例 | 结果 | 定性 |
| --- | --- | --- |
| `CapabilityPickerUiTest` ×2 | `AssertionError: (Role='Checkbox')[4] is not displayed` | **测试写法问题**，非生产 bug。测试当初裸渲染 `CapabilityPicker`，少了生产侧 `CreateAgentScreen` 的 `verticalScroll` 宿主；修法是补上宿主 + `performScrollTo()`，**断言一字未改** |
| `CapabilityPickerUiTest` ×1（后续） | 上限文案 `assertIsDisplayed` 红 | 同上：滚到第 8 行后顶部文案已出视口，断言前先 `performScrollTo()` |
| `TerminalBannerUiTest` ×2 | `ComposeTimeoutException` @ `captureToImage` | **宿主能力边界**，非 bug。用 `Assume` 声明不可执行（skip） |

`MessageBubbleUiTest` 4 条与 `SessionRowUiTest` 4 条**首跑即绿**，未作任何修改。

---

## 四、两条命令

```bash
./gradlew :ui:testDebugUnitTest --no-daemon   # 90 tests, 0 failures, 2 skipped
./gradlew test --no-daemon                    # 全仓，0 failures
```

报告里那 2 个 skipped 是 `TerminalBannerUiTest` 的像素用例，出现位置固定 ——
**它们变绿（pass）的那天，才是像素真的被验到的那天。**
