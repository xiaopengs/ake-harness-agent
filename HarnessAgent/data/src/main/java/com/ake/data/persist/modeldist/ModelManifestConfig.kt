package com.ake.data.persist.modeldist

/**
 * manifest 拉取 + 验签的全部配置项。
 *
 * ## 这是一份「编译期常量」而不是运行时设置，是刻意的
 *
 * manifest 的 URL 与公钥决定**端侧信任什么**。放进可写设置里意味着
 * 一条 adb / 一个被攻破的备份就能把整条信任链换掉，而 manifest 恰好是「决定下哪个
 * 2.6 GB 可执行内容」的那份文件 —— 它必须是全链路里**最不可变**的东西。
 * 所以：
 *  - `manifestUrl` / `pinnedKeys` 由代码钉死，改它们＝发版；
 *  - 服务端能改的只有 manifest **内容**（谁签的还得是我们）。
 *
 * 真正需要运行时可配的只有一个东西：**是否启用**（灰度 / 出事时的总闸）。
 * 那也是编译期开关，不走用户设置。
 */
public data class ModelManifestConfig(
    /**
     * manifest 的 HTTPS 地址。空串 = **未配置** ⇒ [ModelManifestSource] 直接返回
     * `MANIFEST_NOT_CONFIGURED`，**不会**尝试联网。
     */
    val manifestUrl: String,
    /**
     * 钉死的公钥：`keyId -> X.509 SubjectPublicKeyInfo 的 DER（标准 base64）`。
     *
     * 多把钥匙是为了**轮换**：先同时钉新旧两把，服务端切到新 key 下发，
     * 再在下个版本摘掉旧 key。只钉一把的话，换钥匙必须所有端都升完级才能切。
     */
    val pinnedKeys: Map<String, String>,
    /** 内置兜底 manifest 原文（**必须**是用 `pinnedKeys` 里某把钥匙签过的）。`null` = 没有兜底。 */
    val bundledManifest: String? = null,
    /** 两次成功刷新之间的最小间隔。它只是**节流**，不是「过期」—— 见 `ModelManifestSource`。 */
    val refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MS,
    /** 传输层响应体上限，防御性上限（见 `ManifestTransports.http`）。 */
    val maxManifestBytes: Int = ManifestTransports.DEFAULT_MAX_BYTES,
) {

    /** 已配置 = 有 URL 且至少钉了一把钥匙。缺任一条都算没配 ⇒ fail-closed。 */
    public val isConfigured: Boolean get() = manifestUrl.isNotBlank() && pinnedKeys.isNotEmpty()

    public fun trustStore(): ManifestTrustStore {
        if (pinnedKeys.isEmpty()) return ManifestTrust.TRUST_NOTHING
        return ManifestTrust.pinned(
            *pinnedKeys.map { (keyId, spki) -> keyId to ManifestTrust.rsaSha256Verifier(spki) }.toTypedArray(),
        )
    }

    public companion object {
        public const val DEFAULT_REFRESH_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L

        /**
         * 未配置。
         *
         * ★ 这不是「占位待填」，而是**当前的真实状态**：运维还没产出签名 manifest，
         *   所以默认链路是关闭的。`ModelManifestSource` 会返回 `MANIFEST_NOT_CONFIGURED`，
         *   调用方（`:llm`）沿用 APK 内烧录的目录项 —— 也就是今天的行为，
         *   不会因为「接上了分发链路」就把一个尚不存在的服务端变成硬依赖。
         */
        public val UNCONFIGURED: ModelManifestConfig = ModelManifestConfig(
            manifestUrl = "",
            pinnedKeys = emptyMap(),
            bundledManifest = null,
        )
    }
}
