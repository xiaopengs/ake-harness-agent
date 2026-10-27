package com.ake.core.ports

import com.ake.core.common.Outcome

// =============================================================================
// 本地模型人工导入（离线侧载）的 Port。
//
// ## 它解决的场景：**首次启动 + 无网络 ⇒ 用户永远拿不到模型**
//
// 2.6 GB 的模型本体不随 APK 走，所以「内置一份兜底 manifest」救不了首次启动 ——
// 那份 manifest 里的 URL 照样下不来。真出路是让用户自己把两样东西送进来：
// 模型文件 + 与之配套的 manifest。本 Port 是这条链路在 UI 侧的**唯一入口**。
//
// ## ★★ 为什么用户得送两样，而不是只送模型文件
//
// 校验的基准（期望 sizeBytes / sha256）来自 manifest。没有 manifest 就没有
// 「可对账的期望值」，客户端无从判断用户放进来的那几十亿字节是不是它自称的那个东西
// —— 而没有网络时也确实没有一个第三方可以问。manifest 上有签名、公钥钉在包里，
// 于是它是这条离线链路上唯一的把关者。
//
// ## ★★ 为什么这一层要用「UI 侧投影」而不是 `:data` 的领域类型
//
// `:data` 的 `ModelManifestEntry` 带着 `url` / `min-max_app_version_code` /
// `capabilities` 这些**只对下载与兼容判定有意义**的字段。把它们直接抬进 Port，
// 等于把分发策略钉成契约 —— 将来 `:data` 改这张表，`Port` 就得跟着改，
// 而 Port 的价值恰恰在于不动。所以这里只暴露 UI 真正要显示的字段，
// 至于哪份 `ModelManifestEntry` 与之对应，**由实现侧自己查**（见 `:app` 的适配器）。
//
// ## 为什么入参是 `String`（URI 的字面形式）而不是 `android.net.Uri`
//
// `:core:ports` 是纯 JVM 模块（`kotlinJvm`，无 Android SDK），而 Port 的另一条规则是
// 「携带的就是 domain 对象」。URI 的字面形式在系统选择器回传时就已经是这个样子，
// 原样传下来即可，不需要 Port 为了它引入一个 Android 类型。
//
// ## ★ 失败一律走 `Outcome.Err`，`code` 必须是 `:data` 那组 `MODEL_IMPORT_*`
//
// UI 要靠 `code` 挑文案，`cause` 只在进程内留着。文案本身存放在 `:ui` 的
// `strings.xml`（架构 R5：能力层不许知道怎么显示），本文件不产出任何**给用户看**的字。
// =============================================================================

/**
 * 条目在 manifest 里的可见性标签。**UI 用它决定「能不能让用户挑」。**
 *
 * 这里刻意不叫 `ModelEntryState`：那个枚举属于 `:data` 的分发状态机，
 * 而在 Port 这一层我们关心的只有「用户该不该被允许把它导进来」。
 */
public enum class ModelAvailability {
    /** 可作为新装目标。 */
    ACTIVE,

    /** 还能用，但官方已经不再推荐；已装的不受影响。 */
    DEPRECATED,

    /**
     * 已下线。**客户端不得加载它**（典型原因：发现输出有害）。
     *
     * ⚠ 「不得加载」不等于「该删掉」—— 是否停用某个已装模型属于 `:data` 那份 manifest
     * 的决策，UI 不持有它，所以这里只表达一件事：**不许再导入**。
     */
    REVOKED,
}

/** manifest 里一条可以离线导入的模型。** Ports 只允许放 data class，不放行为。 */
public data class LocalModelCandidate(
    val modelId: String,
    val variant: String,
    /** 同一 `(model_id, variant)` 内单调递增。回滚就是把它指回一个更小的值。 */
    val revision: Long,
    /** 人类可读版本（如 `2026.10.1`），只用于展示。 */
    val version: String,
    /** 期望字节数。拷贝结束后会拿它对账，凡是不符的一律不落到正式文件名上。 */
    val sizeBytes: Long,
    val contextWindow: Int,
    val minRamGb: Int,
    val availability: ModelAvailability,
)

/** 一份侧载 manifest 的 UI 侧视图。 */
public data class LocalManifest(
    /** manifest 自身的版本，单调递增。比现有缓存还旧 ⇒ 实现侧会拒（replay 防护）。 */
    val manifestVersion: Long,
    val candidates: List<LocalModelCandidate>,
)

/** 一次导入的结果。UI 可以直接把它显示给用户。 */
public data class LocalModelReceipt(
    val fileName: String,
    val sizeBytes: Long,
    /** 实际算出的摘要（小写 hex）。能拿到这张回执，说明它与期望值一致。 */
    val sha256: String,
    val modelId: String,
    val variant: String,
    /**
     * 这次是真的拷了一份（true），还是盘上本来就有、直接跳过（false）。
     *
     * UI 用它区分「刚花了 20 分钟拷完」与「早就在了，一秒也没拷」，
     * 这两种结局共享同一个成功态，但用户看到的句子应当是两句。
     */
    val copied: Boolean,
)

/**
 * 本地模型导入的能力面。
 *
 * ## 三个方法对应「两步交互」的形状
 *
 *  [importManifest] →  UI 列出 [LocalManifest.candidates] 让用户挑  →  [importModelFile]
 *
 * 之所以拆成两步而不是「一次选两个文件」：**manifest 先到才能知道该期待多大、
 * 摘要该是多少**。反过来先挑模型文件的话，客户端只能先把几十亿字节读完算出摘要，
 * 再回头问 manifest —— 那等于在还没有任何依据的时候先收下了一份未知的二进制。
 *
 * ## ★★ [importModelFile] 的进度回调是**真实字节数**，不是估算值
 *
 * 2.6 GB 的拷贝不是一瞬间，用户需要对正在进行中这件事有信心。这里 `onProgress`
 * 报告的是**已从源流读出的累计字节数**（由实现侧在流上包一层计数器取得），
 * 配合 [LocalModelCandidate.sizeBytes] 就能算出真实的百分比。
 *
 * ⚠ `onProgress` 会在 IO 线程被调用 —— 传进去的实现必须是线程安全的。
 *
 * ## 实现方的位置
 *
 * 本 Port 的实现需要同时看见 `:data` 的两个 Importer 与 Android 的
 * `ContentResolver`，而 `:ui`（禁止 `import com.ake.data.*`）与 `:data`（无 Android
 * 文件选择器）都做不到 ⇒ **组合根 `:app` 提供适配器**，与 `StepTraceCodec` /
 * `ModelLedgerGateway` 是同一个理由。
 */
public interface ModelImportPort {

    /**
     * 侧载一份 manifest，返回其中可导入的条目。
     *
     * @param sourceUri 用户在系统文件选择器里选中的 manifest 文件。**必须是原文 JSON**
     *   （带签名），实现侧会验签。
     */
    public suspend fun importManifest(sourceUri: String): Outcome<LocalManifest>

    /**
     * 导入一份本地模型文件。
     *
     * @param sourceUri 用户选中的模型文件（`content://` 或 `file://` 皆可）。
     * @param onProgress 累计已读字节数。拷贝过程中会被高频调用（缓冲区级），实现侧不节流，
     *   由调用方决定要不要降频；**它反映的一定是真实读到的字节**，不允许插值外推。
     */
    public suspend fun importModelFile(
        candidate: LocalModelCandidate,
        sourceUri: String,
        onProgress: (copiedBytes: Long) -> Unit,
    ): Outcome<LocalModelReceipt>

    /**
     * 这份模型是不是已经在正确的落点上且字节数相符（**未重算摘要**）。
     *
     * UI 在让用户等 2.6 GB 之前先问一句，好在列表上标出「已在设备上」。
     * 它只是一次 `stat`，代价可以忽略 —— 真正的摘要校验发生在 [importModelFile] 里。
     */
    public suspend fun alreadyImported(candidate: LocalModelCandidate): Boolean
}

/**
 * 本 Port 会用到的错误码。
 *
 * ⚠ **必须与 `:data` 的 `ModelDistErrorCodes` 逐字一致**：那些 code 是 `:data` 的
 * `AppError` 直接带上来的，而 UI 是按 code 挑文案的 —— 两边一旦漂移，
 * 用户会看到"未知错误"而不是那句告诉他下一步该怎么做的中文。
 *
 * TODO(convergence)：邝随行把它们与 `ModelErrorCodes` 合并时，这里应当换成那边的常量引用，
 * 而不是再抄一份字符串。
 */
public object ModelImportErrorCodes {
    // ── 文件侧（ModelFileImporter）
    public const val NO_SPACE: String = "MODEL_IMPORT_NO_SPACE"
    public const val SIZE_MISMATCH: String = "MODEL_IMPORT_SIZE_MISMATCH"
    public const val CHECKSUM_MISMATCH: String = "MODEL_IMPORT_CHECKSUM_MISMATCH"
    public const val IO_FAILED: String = "MODEL_IMPORT_IO_FAILED"
    public const val SOURCE_MISSING: String = "MODEL_IMPORT_SOURCE_MISSING"

    // ── manifest 侧（ModelManifestImporter）
    public const val MANIFEST_INVALID: String = "MODEL_IMPORT_MANIFEST_INVALID"
    public const val MANIFEST_DOWNGRADE: String = "MODEL_IMPORT_MANIFEST_DOWNGRADE"
}
