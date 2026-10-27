package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
import com.ake.core.common.AkeLog
import com.ake.core.common.Outcome
import com.ake.core.common.Severity

/**
 * # manifest 的离线侧载 —— 人工导入链路的「前半段」
 *
 * ## 为什么它也属于这个功能
 *
 * [ModelFileImporter] 回答不了「用户放进来的这一份，摘要应该是多少」：
 * 校验的基准（[ModelManifestEntry.sha256] / `sizeBytes`）来自 manifest，
 * 而没有网络的时候 `ModelManifestSource.load()` 什么都拿不到 ⇒ 没有一个可以对账的期望值。
 *
 * 所以用户送进来的应当**是两样东西**：模型文件 + 与之配套的那份已签名 manifest。
 * 本类负责收下后者。有了它，即使从此再也不联网，
 * `ModelManifestSource.current()` 也能从缓存里读到一份**已验签**的 manifest，
 * 于是 `ModelResolver` 才有 `entry` 可用，`ModelDirInventory.probe()` 才有东西可认 ——
 * 整条离线链路这才闭合。
 *
 * ## ★ 为什么「已签名」这三个字不能省
 *
 * 公钥钉在源码里（`ModelManifestPins`），所以**别人签的 manifest 验不过**。
 * 而不验签的后果是具体的：随便伪造一份 manifest，里面写一个攻击者指定的 sha256，
 * 客户端就会把用户送进来的任意二进制当成合法模型。
 * ⇒ 验签在这个离线入口上是唯一的把关者，所以这里刻意**复用既有的 `ModelManifestParser`**，
 *   不去写第二套「宽松一点的校验」。
 *
 * ## 为什么还要查 `manifest_version` 回退
 *
 * 签名合法 ≠ 内容新鲜。攻击者可以把一份**旧的、签名合法**的 manifest 重放过来，
 * 把 ACTIVE 指针推回一个已经被 REVOKED 的模型 —— 它与 `ModelManifestSource.refresh()`
 * 里那条 `MANIFEST_DOWNGRADE` 防护是同一件事，只是发生在另一道门上。
 */
public class ModelManifestImporter(
    private val parser: ModelManifestParser,
    /**
     * 落点就是既有的 manifest 磁盘缓存 —— 写进去之后 `ModelManifestSource.current()`
     * 就会读到它，**导入链路因此不需要 `:llm` / `:app` 改任何一行**。
     */
    private val cache: ManifestCache,
) {

    /**
     * 侧载一份 manifest。
     *
     * @param rawJson 用户提供的原文 JSON。**必须是原文**（带签名），
     *   不接收已经反序列化过的对象 —— 缓存之所以存原文而不是对象，见 `ManifestCache` 类注释。
     * @return 校验通过后的 manifest，UI 可以就此列出其中的条目让用户挑、再挑模型文件。
     */
    public fun import(rawJson: String): Outcome<ModelManifest> {
        if (rawJson.isBlank()) return Outcome.Err(invalid("blank"))

        val parsed = parser.parse(rawJson)
        if (parsed is Outcome.Err) {
            val code = parsed.error.code
            AkeLog.w(TAG, "侧载 manifest 未通过校验：$code")
            return Outcome.Err(
                AppError(ModelDistErrorCodes.IMPORT_MANIFEST_INVALID, "err_manifest_import_invalid", null, Severity.HIGH),
            )
        }
        val manifest = (parsed as Outcome.Ok).value

        val previous = currentVersion()
        if (previous != null && manifest.manifestVersion < previous) {
            AkeLog.w(TAG, "侧载 manifest 版本回退（$previous → ${manifest.manifestVersion}）⇒ 拒绝")
            return Outcome.Err(
                AppError(
                    ModelDistErrorCodes.IMPORT_MANIFEST_DOWNGRADE,
                    "err_manifest_import_downgrade",
                    null,
                    Severity.HIGH,
                ),
            )
        }

        if (!cache.write(rawJson)) {
            // 写缓存失败不影响本次使用（与 `ModelManifestSource.refresh()` 同一处置）。
            AkeLog.w(TAG, "侧载 manifest 未能写入缓存（本次仍可用，重启后会重新联网取）")
        }
        AkeLog.i(TAG, "侧载 manifest v${manifest.manifestVersion} 成功，${manifest.models.size} 个条目")
        return Outcome.Ok(manifest)
    }

    // ──────────────────────────────────────────────── 内部

    /** 当前缓存里那份的版本号；没有或坏了（验不过签）都返回 `null` ⇒ 不做回退比较。 */
    private fun currentVersion(): Long? {
        val raw = cache.read() ?: return null
        return when (val p = parser.parse(raw)) {
            is Outcome.Ok -> p.value.manifestVersion
            is Outcome.Err -> null
        }
    }

    private fun invalid(why: String): AppError = AppError(
        ModelDistErrorCodes.IMPORT_MANIFEST_INVALID,
        "err_manifest_import_invalid",
        IllegalArgumentException(why),
        Severity.MEDIUM,
    )

    public companion object {
        public const val TAG: String = "ManifestImport"
    }
}
