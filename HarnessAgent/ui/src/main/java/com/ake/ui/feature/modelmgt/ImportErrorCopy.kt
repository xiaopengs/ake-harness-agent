package com.ake.ui.feature.modelmgt

import androidx.annotation.StringRes
import com.ake.core.ports.LocalModelCandidate
import com.ake.core.ports.ModelImportErrorCodes
import com.ake.ui.R

/**
 * 「导入失败」这句中文该怎么选。
 *
 * ## ★★ 这里唯一的难点是两条 mismatch，它们必须**一看就不是同一件事**
 *
 * 两者在后端都只是「验不过」，于是很容易写成一句「导入失败，请重试」。那就完了：
 *
 *  |  | `SIZE_MISMATCH` | `CHECKSUM_MISMATCH` |
 *  |---|---|---|
 *  | 字节数 | 不等于期望 | **等于**期望 |
 *  | 它到底是什么 | 一份**残的**文件 | 一份完好的、**不对的**文件 |
 *  | 病因 | 传输中断（拷了一半 / 网盘没下完） | 版本拿混了（别的型号、别的 revision） |
 *  | 用户该做的 | 完整地重传一次 | **别重传** —— 去找配套的那一版 |
 *
 * 写成一句的代价是具体的：一个拿到「摘要不符」的用户，第一反应就是再传一遍，
 * 20 分钟后他会看到一模一样的错误。所以这两句必须分开写、而且要各自说完「下一步」。
 *
 * ## 为什么字串全部放在 `strings.xml`
 *
 * 架构 R5：能力层不该知道怎么显示。这里虽然已经在 `:ui`，但同理 —— 台词归资源，
 * 代码归判断。抽出来的形状是 `@StringRes Int` + 参数列表，由 Composable 负责
 * `stringResource()`，于是**这条映射是纯函数、可以在 JVM 单测里直接断**。
 */
public data class ImportErrorView(
    @StringRes val messageRes: Int,
    /** `stringResource` 的位置化参数。`%1$s` 对应第 0 个元素。 */
    val args: List<String> = emptyList(),
)

/**
 * 错误码 → 该显示的那句台词。纯函数：只做映射，不读任何运行时状态。
 *
 * @param code `:data` 那个 `AppError.code`（常量见 [ModelImportErrorCodes]）。
 * @param selected 用户挑的那一条，用来把「期望多大」这个数字代进句子里。
 */
public fun importErrorViewOf(
    code: String,
    selected: LocalModelCandidate?,
): ImportErrorView = when (code) {

    // 犯错之前就预检过了，一个字节都没写 ⇒ 可以直接说「没动过」，让用户放心去清理。
    ModelImportErrorCodes.NO_SPACE -> ImportErrorView(
        R.string.err_model_import_no_space,
        listOf(formatBytes(selected?.sizeBytes ?: 0L)),
    )

    // 文件不完整 ⇒ 去重传。**不许说「重试」，那会被读成「接着断点继续」。
    ModelImportErrorCodes.SIZE_MISMATCH -> ImportErrorView(
        R.string.err_model_import_size_mismatch,
        listOf(formatBytes(selected?.sizeBytes ?: 0L)),
    )

    // 文件是错的 ⇒ 重传没用。**这句必须明说不要再拷一遍**。
    ModelImportErrorCodes.CHECKSUM_MISMATCH -> ImportErrorView(
        R.string.err_model_import_checksum_mismatch,
    )

    ModelImportErrorCodes.IO_FAILED -> ImportErrorView(R.string.err_model_import_io_failed)

    ModelImportErrorCodes.SOURCE_MISSING -> ImportErrorView(R.string.err_model_import_source_missing)

    ModelImportErrorCodes.MANIFEST_INVALID -> ImportErrorView(R.string.err_manifest_import_invalid)

    // 见 ModelImportViewModel 里那条「为什么拒绝降级」的裁决。
    ModelImportErrorCodes.MANIFEST_DOWNGRADE -> ImportErrorView(R.string.err_manifest_import_downgrade)

    else -> ImportErrorView(R.string.err_model_import_unknown)
}
