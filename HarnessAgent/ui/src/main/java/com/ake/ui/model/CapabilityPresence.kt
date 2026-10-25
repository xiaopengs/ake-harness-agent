package com.ake.ui.model

import com.ake.core.ports.CapabilityRef

/**
 * 「这个 Agent 有没有扩展能力」的三态。
 *
 * ## 为什么是三个值，不是 `Boolean`
 *
 * `Boolean` 只有两种回答，而这里的事实有三种：
 *
 * | 事实 | 界面该说什么 |
 * |---|---|
 * | 一项都没授予 | 「它只会聊天」—— 这是关键信息，用户以为 App 坏了 |
 * | 有授予 | 什么都不用说 |
 * | **读不到授权表** | **什么都不该说** |
 *
 * 第三种最容易被写错：`CapabilityGrantPort` 在 Hilt 图里是 `@BindsOptionalOf`（见
 * `ChatWiringModule`），拿不到实现时 `Optional.empty()`。若把「拿不到」折叠成
 * `false`，界面就会对着一个**其实配了六个能力**的 Agent 说「你还没配能力」——
 * 用一个错误的提示去修一个信息缺失的缺陷，用户会去重新配一遍，然后发现配了也没用。
 *
 * 所以 [UNKNOWN] 必须独立存在：它让调用点**在类型上**无法把「不知道」当成「没有」。
 */
public enum class CapabilityPresence {
    /** 授权表读不到（Port 未接线 / 读失败）。界面保持沉默。 */
    UNKNOWN,

    /** 授权表里一行有效的授权都没有 ⇒ 真的零能力。 */
    NONE,

    /** 至少有一项被授予。 */
    GRANTED,
}

/**
 * `CapabilityGrantPort.grantsOf()` 的产出 → 三态。
 *
 * 判 `values.any { it }` 而不是 `isNotEmpty()`：契约允许实现把 `granted = false`
 * 的行也带出来（`CapabilityGrantStoreImpl` 目前不写 0 值行，但那是实现细节，
 * 不是契约承诺）。用「有没有一项为 true」判定，两种实现都对。
 */
public fun capabilityPresenceOf(grants: Map<CapabilityRef, Boolean>): CapabilityPresence =
    if (grants.values.any { it }) CapabilityPresence.GRANTED else CapabilityPresence.NONE
