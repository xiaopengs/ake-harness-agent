package com.ake.core.ports

import kotlinx.coroutines.flow.Flow

/**
 * # 「清除全部痕迹」与「本地设置」两个 Port
 *
 * 这两个能力此前 **UI 只能靠跨层拿**：一个是直接注入 `:data` 的
 * `com.ake.data.persist.wipe.WipeEverythingUseCase`，一个是 UI 自己
 * 用 `org.json` 手写了两份暂存（`com.ake.ui.spi.UiLocalSettings`）。
 *
 * 补成契约之后：
 *  - 删除动作的实现仍在 `:data`（它要关库、停在途写入队列、删外置轨迹目录）；
 *  - 「清完之后告诉用户什么」仍在 `:ui`（分项用量、失败项措辞）；
 *  - 两侧之间的 Contract 由本文件给出，`:ui` 不再需要 Gradle 边。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 清除
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ★ 「一键清除全部痕迹」的**执行**契约（实现在 `:data`）。
 *
 * 端侧 Agent 恰恰是最需要它的 App —— 我们在用户的登录态下访问站点，并把过程完整录了下来。
 *
 * ## 顺序不能改（这是实现的正确性要求，不只是注释）
 *
 * ```
 *  ① 删 Keystore 密钥   ← 主手段，先做（见下）
 *  ② 停在途写入         ← 否则刚删完又被写回来
 *  ③ 删 Room 库文件     ← 含 -wal / -shm
 *  ④ 删外置轨迹目录
 *  ⑤ 删 DataStore / 配置
 *  ⑥ ★ 删 WebView 痕迹  ← 本 App 最大一块不受我们控制的痕迹
 *  ⑦ 模型文件删不删由用户单独决定
 * ```
 *
 * 「⑥ WebView」不可省：只删自己的库而留着 `app_webview/`，
 * 拿 ADB 看一眼就能还原访问过哪些站点 —— 那一刻 App 的隐私承诺是假的。
 *
 * ## 为什么「先删密钥」而不是「先删文件」
 *
 * 反过来做，中途崩了就是**文件还在而密钥没了** ⇒ 数据变成永久垃圾；
 * 而**密钥先没、文件还在** ⇒ 数据已经不可读，剩下的只是等待被覆盖。
 * 先删密钥是更安全的失败方向。
 *
 * ## 调用前的前置动作不在本接口内
 *
 * 「先断 MCP 连接、先卸载模型」是 UI 侧门面的职责（`:ui` 的 `PrivacyPort`），
 * 因为 MCP 的存在对 `:data` 来说是实现细节，不该让 `:data` 反向依赖它。
 */
public interface WipePort {

    /**
     * 执行清除。
     *
     * @param deleteModelFiles 是否连模型文件一起删（约 2.6 GB，重下代价高，默认由用户决定）。
     */
    public suspend fun wipeEverything(deleteModelFiles: Boolean): WipeResult
}

/**
 * 清除结果。
 *
 * ★ `failures` 是为了补上 `:ui` 侧已经指出过的那个**诚实性缺口**：
 * 实现内部每一步都是 `runCatching { }.onFailure { log }`，失败不外抛，
 * 于是 UI 的 `WipeReport.failures` 里只可能有「整个用例抛异常」这一种，
 * 用户看不到「库没删掉但没报错」。
 *
 * 这里把它写进契约，实现方逐步汇总每一步的失败即可。给了默认值是为了让
 * 现有实现（`WipeResult(modelsDeleted)`）不用改也能满足契约。
 */
public data class WipeResult(
    val modelsDeleted: Boolean,
    val failures: List<String> = emptyList(),
) {
    public val ok: Boolean get() = failures.isEmpty()
}

// ─────────────────────────────────────────────────────────────────────────────
// 本地设置
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 设置页里**能真正生效**的那几项的持久化（实现应在 `:data`）。
 *
 * ## 哪些设置是真生效的，哪些不该出现在这张表里
 *
 * | 设置项 | 是否生效 | 落点 |
 * |---|---|---|
 * | 域名白名单 | ✅ | 下发到 `TaskSpec.allowHosts` |
 * | 最大步数 | ✅ | 下发到 `TaskSpec.maxSteps` |
 * | 显示 DOM 原文 | ✅ | 纯 UI 偏好 |
 * | 上下文预算档位 / 单步超时 / 域名黑名单 | ❌ 只读 | 由设备档位与护栏决定，**没有 Setter** |
 *
 * 「只读」那几项刻意不放进来：UI 不能靠自己存一份来假装能调，
 * 那会造成「用户在设置里调了、任务跑在另一套预算上」这种最难排查的不一致。
 *
 * ★ `:data` 的 `WipeEverythingUseCase` 里留着一条明确的 TODO：
 * 「core/ports 落地 SettingsStorePort 后，这里改成 store.clearAll()」。本接口就是它等的那个。
 */
public interface SettingsStorePort {

    /** 当前值，并随变更持续 emit（替换 UI 侧手工 `refresh()` 的时机问题）。 */
    public fun values(): Flow<LocalSettings>

    public suspend fun update(v: LocalSettings)

    /**
     * 清空全部设置。★ 必须实现，且必须与 [WipePort] 的清除范围一致 ——
     * 否则「一键清除」之后会留下一批孤儿文件，那比「有两份实现」更糟。
     */
    public suspend fun clearAll()
}

/** 与 `:ui` 现有 `UiLocalSettings.Values` 逐字段同构，迁移时是纯改名。 */
public data class LocalSettings(
    /** 任务级域名白名单。空集 = 不限（由跨域名确认兜底）。 */
    val allowHosts: List<String> = emptyList(),
    /** 最大步数。`null` = 跟随档位默认值。 */
    val maxSteps: Int? = null,
    /** 是否显示「Agent 看到的 DOM 原文」。默认开 —— 这是本 App 的可解释性卖点。 */
    val showDomPayload: Boolean = true,
)
