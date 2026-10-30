package com.ake.ui.feature.settings

import com.ake.core.ports.PlainSecret

// ===========================================================================
// ★★ 设置页里「可能含秘密」的输入值的唯一容器
//
// 它与 `:core:ports` 的 `PlainSecret` 同一套顾虑，但要解决的问题多一层：
// `PlainSecret` 保证的是「明文只有一个读取口」；这里要保证的是
// **「它根本不在 ViewModel 的状态里」**。
//
// ## 为什么不能直接写成 `SettingsUi.baseUrl` / `SettingsUi.apiKey`
//
// `SettingsUi` 是 data class。三条理由逐个都是硬的：
//
//  ① data class 自动生成 `toString()` —— VM 被 log、被装进 Bundle、
//     被某个调试面板 dump 一下，原文就裸着出去了；
//  ② `StateFlow<SettingsUi>` 是**可观测**的：任何 `collect` 都能看见它，
//     一次「打印整个 ui 状态」的事故就等于一次泄漏；
//  ③ 进程还原要求状态可序列化 —— 序列化出去的内容会躺在磁盘上的 Bundle 里，
//     绕过 `CloudCredentialPort` 的加密存储（§6.6）。
//
// ## ★ BaseURL 为什么也在这儿 —— 这是本条目最不直观的一条
//
// 自建网关常把 key 放在 query 里（`?key=xxx`，§6.3 ③）。
// 于是 `baseUrl` 这根字符串**本身就是秘密**，和 Key 用同一个盒子装。
// 界面能看到的只有它的派生结果：`EndpointConfig.hostOf()`
// （= `Hosts.normalise`，全仓唯一实现）。
//
// ## 这条边界到哪儿为止（诚实评估）
//
// 本类保证的是「**不经解释地打印 / 序列化 / 转储会得到掩码**」。
// 它防不住 root / Frida、进程内存 dump、IME 词库 —— 与 `PlainSecret` 同一档。
// ===========================================================================

public final class SecretDraft private constructor(
    private var raw: String,
) {

    /**
     * Compose 的 `TextField` 需要原文才能画。
     *
     * ★ 屏幕上出不出明文由调用方决定（Key 用 `visualTransformation` 掩码；
     *   BaseURL 本来就允许看 —— 但**允许看不等于允许 dump**）。
     */
    public val value: String get() = raw

    /** 是否已经填了东西 —— 用于禁用按钮，不暴露长度之外的任何信息。 */
    public val isBlank: Boolean get() = raw.isBlank()

    /** ★ 唯一能把它变成 [PlainSecret] 的地方。用完最好再来一次 [clear]。 */
    public fun freeze(): PlainSecret = PlainSecret.of(raw)

    /**
     * 彻底丢弃。★ 保存 / 自检成功后调用：原文没必要在内存里多活一秒。
     *
     * 注意 JVM 的 `String` 不可变，这里做的是「解绑」而不是「擦除字节」——
     * 与 `PlainSecret` 同一档承诺，不冒充 `ByteArray.fill(0)` 那档。
     */
    public fun clear() {
        raw = ""
    }

    public fun replace(newValue: String) {
        raw = newValue
    }

    /**
     * ★ `toString` 恒为掩码，长度都不给：长度本身就是一条侧信道
     * （「32 还是 64 字符」足以在候选集里砍掉一半）。
     */
    public override fun toString(): String = MASKED

    public companion object {
        public const val MASKED: String = "***"

        public fun empty(): SecretDraft = SecretDraft("")
    }
}
