package com.ake.data.persist.modeldist

/**
 * 模型文件名布局。`:data` 只产出**名字**，真实 `File` 由 `:llm` 拼在它的 `modelsDir` 下。
 *
 * 为什么命名规则要落在 `:data` 而不是 `:llm`：回滚要能在**不下载**的前提下
 * 认出「旧版本的文件还在不在」，这要求归档名是**可从 sha256 推导**的确定性名字 ——
 * 一旦名字里掺了随机数或时间戳，回滚就退化成「重新下载 2.6 GB」。
 *
 * ## 与 `ModelDownloader` 的既有约定（**不要改**，邝随行的代码已经这么写）
 *  - 最终文件：`$id.litertlm`（`ModelDownloader.localFileOf`）
 *  - 下载中：`$id.litertlm.part`
 */
public object ModelFileLayout {

    public fun finalNameOf(assetId: String): String = "$assetId.litertlm"

    public fun partNameOf(assetId: String): String = "$assetId.litertlm.part"

    /**
     * 归档名：`<modelId>_<variant>_<sha256 前 16 位>.litertlm`。
     *
     * 用 sha256 而不是 revision 命名，是因为 revision 是服务端可以随时改的数字，
     * 而 sha256 是内容本身的身份 —— 服务端把 revision 改来改去，
     * 客户端仍能靠内容认出「这份我盘上有」。
     */
    public fun archiveNameOf(modelId: String, variant: String, sha256: String): String =
        "${modelId}_${variant}_${sha256.take(ARCHIVE_SHA_PREFIX_LEN)}.litertlm"

    /** 归档行使用的 variant：`<variant>@<sha256 前 12 位>`，避开 `(model_id, variant)` 唯一索引。 */
    public fun archiveVariantOf(variant: String, sha256: String): String =
        "$variant@${sha256.take(ARCHIVE_VARIANT_SHA_LEN)}"

    public const val ARCHIVE_SHA_PREFIX_LEN: Int = 16
    public const val ARCHIVE_VARIANT_SHA_LEN: Int = 12
}
