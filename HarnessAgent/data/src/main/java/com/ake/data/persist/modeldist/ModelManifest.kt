package com.ake.data.persist.modeldist

/**
 * 模型分发 manifest 的**领域模型**（架构 §6.4「应用内下载 + manifest 驱动」的客户端侧）。
 *
 * ## 为什么选 manifest 驱动（而不是把 URL 烧进 APK / 走 Play Asset Delivery）
 *
 *  1. **回滚只改配置**：模型是 2.6 GB 的二进制，出问题时「改一行 CDN 上的 JSON」
 *     与「发一个新 APK」的代价差两个数量级，而发版还要等审核。
 *  2. **服务端保留历史**：manifest 里同时挂着新旧 revision，回滚 = 把 ACTIVE 指针退回旧条目，
 *     不需要重新上传文件（旧文件本来就在 CDN 上）。
 *  3. **兼容矩阵随模型走**：`min/max_app_version_code` 与模型条目同源，
 *     不会出现「App 已经升级到不兼容的壳，但模型 URL 还是老配置」的错配。
 *
 * ## 三条硬规则在本模型里的落点
 *
 *  | 规则                                   | 落点                                                          |
 *  |----------------------------------------|---------------------------------------------------------------|
 *  | 绝不因模型更新删除用户已装的可用模型     | 模型只增不删；`ModelResolution` **没有任何 DELETE 分支**（见 `ModelResolver`） |
 *  | 兼容矩阵双向声明                        | [ModelManifestEntry.minAppVersionCode] / [maxAppVersionCode] 两端都必填/可选 |
 *  | 任何模型变更必须能只改配置回滚          | `state` + `revision` + sha256 三重身份，回滚见 `ModelResolver`  |
 */
public data class ModelManifest(
    val schemaVersion: Int,
    /**
     * manifest 自身的版本。**只用于判断「我手上的缓存是否比服务端旧」，不参与回滚判定。**
     *
     * ★ 关键语义：它**永远单调递增**，即使内容是一次回滚也照样 +1。
     * 理由：CDN 与各级缓存对「版本号变小」的处理是未定义的（很多边缘节点会继续吐旧副本），
     * 一旦允许 manifest_version 回退，回滚就可能在部分用户那里永远不生效。
     * 真正的回滚判定看的是**条目的 revision 与 ACTIVE 指针**，不是这个数字。
     */
    val manifestVersion: Long,
    val generatedAt: Long,
    val models: List<ModelManifestEntry>,
    val signature: ManifestSignature?,
)

/** 单条模型条目。业务身份是 `(model_id, variant)`，版本身份是 `revision` + `sha256`。 */
public data class ModelManifestEntry(
    val modelId: String,
    val variant: String,
    /** 同一 `(model_id, variant)` 内单调递增。**回滚就是把它指回一个更小的值。** */
    val revision: Long,
    /** 人类可读版本（`2026.10.1`），只用于 UI 展示，不参与任何比较。 */
    val version: String,
    val url: String,
    val sizeBytes: Long,
    /** 小写 64 位 hex。它同时是「这份模型内容」的**唯一身份** —— 回滚靠它认人。 */
    val sha256: String,
    /**
     * 兼容矩阵下界（含）。当前 App `versionCode >= 它` 才可用。
     * 典型用途：新版模型用了旧壳没有的算子/分词器 ⇒ 老 App 不许下载。
     */
    val minAppVersionCode: Int,
    /**
     * 兼容矩阵上界（含）。`null` = 不封顶。
     * 典型用途：模型里有个已知问题只影响新壳的某条加载路径 ⇒ 先把上界钉死，
     * 等下个模型版本修好再放开，而**不必发版**。
     */
    val maxAppVersionCode: Int?,
    val contextWindow: Int,
    val minRamGb: Int,
    val capabilities: List<String>,
    val state: ModelEntryState,
)

/**
 * 条目状态。**这是「只改配置回滚」的开关**：
 * 服务端把坏条目置为 [REVOKED]、把旧条目置回 [ACTIVE]，客户端下一次拉 manifest 即完成回滚。
 */
public enum class ModelEntryState {
    /** 可下载 / 可被选为目标版本 */
    ACTIVE,

    /** 仍可用（已装的不受影响），但不再作为新装目标 —— 平滑退场用 */
    DEPRECATED,

    /** 已下线：不再允许加载（例如发现输出有害）。**客户端仍不得删除文件**，见 `ModelResolver`。 */
    REVOKED,
}

public data class ManifestSignature(
    /** 算法标识，例如 `RSA-SHA256`。未知算法 ⇒ 拒绝（fail-closed）。 */
    val alg: String,
    /** 密钥 id，用于在信任库里查公钥。未登记的 keyId ⇒ 拒绝。 */
    val keyId: String,
    /** 签名值（标准 base64）。覆盖的是**规范化后的 manifest 字节**（见 `ManifestCanonicalForm`）。 */
    val valueBase64: String,
)

/**
 * 模型分发链路的错误码。
 *
 * ⚠ **待收敛**：`:data` 当前不依赖 `:core:model-api`，拿不到 `ModelErrorCodes`
 * （它在 `com.ake.core.modelapi`），所以这里先自立一套。命名与形状对齐 `ModelErrorCodes`
 * （机器可读 code + i18n reasonKey），将来由邝随行把它们合并进 `ModelErrorCodes`
 * 并给 `:data` 加上 `:core:model-api` 依赖边即可，**调用方只见字符串 code，不受影响**。
 */
public object ModelDistErrorCodes {
    public const val MANIFEST_MALFORMED: String = "MANIFEST_MALFORMED"
    public const val MANIFEST_SCHEMA_UNSUPPORTED: String = "MANIFEST_SCHEMA_UNSUPPORTED"
    public const val MANIFEST_FIELD_MISSING: String = "MANIFEST_FIELD_MISSING"
    public const val MANIFEST_FIELD_INVALID: String = "MANIFEST_FIELD_INVALID"
    public const val MANIFEST_EMPTY: String = "MANIFEST_EMPTY"
    public const val MANIFEST_DUPLICATE_VARIANT: String = "MANIFEST_DUPLICATE_VARIANT"
    public const val SIGNATURE_MISSING: String = "MANIFEST_SIGNATURE_MISSING"
    public const val SIGNATURE_KEY_UNKNOWN: String = "MANIFEST_SIGNATURE_KEY_UNKNOWN"
    public const val SIGNATURE_ALG_UNSUPPORTED: String = "MANIFEST_SIGNATURE_ALG_UNSUPPORTED"
    public const val SIGNATURE_INVALID: String = "MANIFEST_SIGNATURE_INVALID"

    // ── 拉取侧（`ModelManifestSource` / `ManifestTransport`）
    public const val MANIFEST_NOT_CONFIGURED: String = "MANIFEST_NOT_CONFIGURED"
    public const val MANIFEST_UNAVAILABLE: String = "MANIFEST_UNAVAILABLE"
    public const val MANIFEST_FETCH_FAILED: String = "MANIFEST_FETCH_FAILED"
    public const val MANIFEST_NOT_MODIFIED: String = "MANIFEST_NOT_MODIFIED"
    public const val MANIFEST_TOO_LARGE: String = "MANIFEST_TOO_LARGE"
    public const val MANIFEST_DOWNGRADE: String = "MANIFEST_DOWNGRADE"

    // ── 归档清理（`ModelArchiveRetention`）
    public const val CLEANUP_NEEDS_CONFIRM: String = "MODEL_CLEANUP_NEEDS_CONFIRM"
    public const val CLEANUP_CURRENT_UNVERIFIED: String = "MODEL_CLEANUP_CURRENT_UNVERIFIED"

    public const val MODEL_INCOMPATIBLE: String = "MODEL_INCOMPATIBLE"
    public const val MODEL_UNAVAILABLE: String = "MODEL_UNAVAILABLE"

    // ── 本地人工导入（`ModelFileImporter` / `ModelManifestImporter`）
    public const val IMPORT_NO_SPACE: String = "MODEL_IMPORT_NO_SPACE"
    public const val IMPORT_SIZE_MISMATCH: String = "MODEL_IMPORT_SIZE_MISMATCH"
    public const val IMPORT_CHECKSUM_MISMATCH: String = "MODEL_IMPORT_CHECKSUM_MISMATCH"
    public const val IMPORT_IO_FAILED: String = "MODEL_IMPORT_IO_FAILED"
    public const val IMPORT_SOURCE_MISSING: String = "MODEL_IMPORT_SOURCE_MISSING"

    /** manifest 侧载被拒：`manifest_version` 比现有缓存更小（replay 防护）。 */
    public const val IMPORT_MANIFEST_DOWNGRADE: String = "MODEL_IMPORT_MANIFEST_DOWNGRADE"
    public const val IMPORT_MANIFEST_INVALID: String = "MODEL_IMPORT_MANIFEST_INVALID"
}

/** manifest schema 版本。客户端只接受这一个 —— 未知 schema 直接拒绝，不做「尽力解析」。 */
public object ManifestSchema {
    public const val CURRENT: Int = 1
}
