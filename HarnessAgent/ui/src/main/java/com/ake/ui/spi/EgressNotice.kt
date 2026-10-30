package com.ake.ui.spi

import com.ake.core.ports.CloudFailure
import com.ake.core.ports.Locality
import kotlinx.coroutines.flow.Flow

// ===========================================================================
// ★★ 这是两个「今天缺、明天必须有人补」的接缝（2026-11 · 苏映棠）
//
// 起因：`core:ports` 的 `ChatMessageView` 与 `ChatEvent` **都没有字段**
// 承载「这条回答到底是端侧还是云端产出的」以及「这一轮是否发生过回落」。
// `FallbackPolicy`（`:inference`）已经把 PRE 回落裁决写死了，
// 但那个事实**没有任何路径到达 UI** —— 于是 §4.2「必须告知用户」缺了承载者。
//
// 本文件把它定义成 `:ui` 自己的 Port：
//  · 好处是 `:ui` 不越过白名单去碰 `:inference` / `:chat` / `:cloudai`；
//  · 代价是它由谁实现，取决于契约层什么时候把这个能力补全。
//
// ★ 两个 TODO 的收口方向（需霍承钧定夺，不由 `:ui` 自己发明形状）：
//   最好是给 `core:ports` 的 `ChatMessageView` 加一个产出来源字段（per-message，
//   per-session 都不够 —— 回落是**每轮**的事），然后本文件这两个 Port 直接删掉。
// ===========================================================================

/**
 * 本轮**实际**的出境结果。
 *
 * ★ UI 只读它，不产生它：一旦 UI 能自己造出一个「回落了」的结论，
 *   它就成了另一种形式的静默降级（自己骗自己也算骗）。
 *
 * @param host ★ 归一化 host。完整 URL 永不进这个对象（§6.3 ③）。
 * @param producedBy 这一轮最终由谁产出 —— 徽标据此翻成本机还是云端。
 */
public data class TurnEgressNotice(
    val turnId: String,
    val failure: CloudFailure,
    val host: String,
    val producedBy: Locality,
)

/**
 * 回落 / 出境事实的下发。
 *
 * TODO(霍承钧): 目前**全工程没有实现**（由 `@BindsOptionalOf` 兜住，注入为空）。
 * 缺它时「必须告知用户」这一句没人说出口 ⇒ 静默降级的窗口还开着。
 * 建议的做法不是在 UI 里造，而是：
 *  ① `core:ports` 的 `ChatEvent` 新增一个承载 `TurnEgressNotice` 的事件，或
 *  ② `ChatMessageView` 带上 per-message 的 `Locality` + `CloudFailure`；
 * 然后由 `:ui` 直接消费，本接口废弃。
 */
public interface TurnEgressNoticeSource {
    public fun notices(sessionId: String): Flow<List<TurnEgressNotice>>
}
