package com.ake.data.persist.modeldist

import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState

/** 本地已存在的一份模型文件（只说明「有个大小对得上的文件」，**未**校内容）。 */
public data class LocalModelFile(
    /** 文件名（相对模型目录）。真实 File 句柄归 `:llm` 所有，`:data` 只记名字。 */
    val name: String,
    val sizeBytes: Long,
)

/**
 * 本地文件盘点。**由 `:llm` 实现**（`ModelDownloader` 是文件系统的所有者，
 * `:data` 不碰 File）。
 *
 * 为什么 `:data` 需要它：回滚要能回答「旧版本的文件还在不在」。
 * 而「在不在」只有文件系统知道 —— Room 里那行记录并不保证磁盘上的字节还在
 * （用户清过缓存、SD 卡换过、别的清理工具动过手）。
 */
public fun interface ModelFileInventory {
    /** 没有符合的文件返回 `null`。字节数不符也返回 `null`（半截文件不算「有」）。 */
    public fun probe(entry: ModelManifestEntry): LocalModelFile?
}

/**
 * 一次「该用什么模型」的裁决。
 *
 * ★★ **这个类型里没有任何 DELETE 分支，是刻意的** ——
 * 硬规则「绝不因模型更新删除用户已装的可用模型」如果只写在注释里，迟早会被某个
 * 「顺手清理一下」的 PR 破坏掉。这里把它做进类型：调用方**拿不到**一个删除动作，
 * 想删只能走 `:llm` 的 `ModelLifecyclePort.deleteModelFile()`（用户显式触发）。
 */
public sealed interface ModelResolution {

    /** 已装的正是目标版本：什么都不用做。 */
    public data class Current(
        val entry: ModelManifestEntry,
        val asset: ModelAssetRecord,
    ) : ModelResolution

    /**
     * 目标版本的文件**本地已经有了**（下载过 / 被归档过）⇒ 校验后直接切，零下载。
     *
     * @param isRollback 这是一次回滚（见 [Download]）
     */
    public data class UseLocal(
        val entry: ModelManifestEntry,
        val file: LocalModelFile,
        val isRollback: Boolean,
        /** 切换前在用的资产。**必须保留**，新版本校验失败时它是唯一退路。 */
        val previous: ModelAssetRecord?,
    ) : ModelResolution

    /**
     * 需要下载。
     *
     * @param isRollback 服务端把 ACTIVE 指针退回了一个比已装版本**更小**的 revision。
     *   注意：这**不意味着**要重新下载 —— 若旧文件还在盘上，走的是 [UseLocal]；
     *   只有「旧文件也没了」才落到这个分支。
     * @param keep 必须保留的已装资产（下载期间它仍在服务）。
     */
    public data class Download(
        val entry: ModelManifestEntry,
        val isRollback: Boolean,
        val keep: ModelAssetRecord?,
    ) : ModelResolution

    /** 没有任何兼容条目，但本地有可用模型 ⇒ 继续用它，只是不再更新。 */
    public data class KeepInstalled(
        val asset: ModelAssetRecord,
        val reasonKey: String,
    ) : ModelResolution

    /**
     * 在用模型已被服务端 REVOKED（例如发现输出有害）⇒ **停止加载**。
     * 但**文件不删**：2.6 GB 的重下代价由用户承担不合适，
     * 且 REVOKED 有可能是一次误操作，删了就回不来（见硬规则 1）。
     */
    public data class InstalledRevoked(
        val asset: ModelAssetRecord,
    ) : ModelResolution

    /** 既没有兼容条目，本地也没有可用模型 ⇒ 只能提示用户。 */
    public data class Blocked(
        val reasonKey: String,
    ) : ModelResolution
}

/**
 * 目标版本选择 + 回滚判定。
 *
 * ## 回滚语义（选 manifest 方案的核心理由，必须真正可用）
 *
 * 一次回滚在服务端长这样：**只改配置**
 *  1. 把坏条目 `state` 置为 `REVOKED`（或 `DEPRECATED`）；
 *  2. 把旧条目置回 `ACTIVE`；
 *  3. `manifest_version` **+1**（不回退，原因见 `ModelManifest.manifestVersion` 的注释）。
 *
 * 客户端的判定链条：
 *  - 目标 = `ACTIVE ∧ 兼容` 里 **revision 最大**的条目 ⇒ 自动指回旧条目；
 *  - `isRollback` = 在用资产的 revision **大于**目标 revision ⇒ 识别出「这是一次回退」；
 *  - 文件身份靠 **sha256**（不是 revision 数字）：旧条目的 sha256 与当初装的一致，
 *    于是 [ModelFileInventory.probe] 能在盘上认出它 ⇒ [UseLocal] ⇒ **秒切，零下载**。
 *
 * ★ 用 sha256 而不是「把 revision 存一列」来认人，是为了**不动数据库 schema**：
 * 回滚能力如果依赖一次 Room 迁移（改表 + 版本号 + 迁移脚本），
 * 那它就至少要一个 App 版本才能真正生效 —— 那恰好否定了「不发版就能回滚」这个前提。
 */
public object ModelResolver {

    public fun resolve(
        manifest: ModelManifest,
        installed: List<ModelAssetRecord>,
        appVersionCode: Int,
        inventory: ModelFileInventory,
    ): ModelResolution {
        val ready = installed.filter { it.state == ModelAssetState.READY && !it.localPath.isNullOrBlank() }
        val active = ready.maxByOrNull { it.updatedAt }

        // ① 安全优先：在用的那份被服务端撤下了 ⇒ 停止加载（但不删文件）
        if (active != null) {
            val activeEntry = entryOf(manifest, active.sha256)
            if (activeEntry != null && activeEntry.state == ModelEntryState.REVOKED) {
                return ModelResolution.InstalledRevoked(active)
            }
        }

        // ② 目标 = ACTIVE ∧ 兼容 ∧ revision 最大
        val target = manifest.models
            .filter { ModelCompatibility.isUsable(ModelCompatibility.evaluate(it, appVersionCode)) }
            .maxByOrNull { it.revision }
            ?: return if (active != null) {
                ModelResolution.KeepInstalled(active, "err_model_no_compatible_but_installed")
            } else {
                ModelResolution.Blocked("err_model_no_compatible")
            }

        // ③ 已装的正是目标 ⇒ 无事发生
        if (active != null && active.sha256.equals(target.sha256, ignoreCase = true)) {
            return ModelResolution.Current(target, active)
        }

        // ④ 回滚判定：在用的 revision 比目标 revision 还大
        val activeRevision = active?.let { entryOf(manifest, it.sha256)?.revision }
        val isRollback = activeRevision != null && activeRevision > target.revision

        val local = inventory.probe(target)
        return if (local != null) {
            ModelResolution.UseLocal(target, local, isRollback, active)
        } else {
            ModelResolution.Download(target, isRollback, active)
        }
    }

    /** 按 sha256 在 manifest 里认人。找不到 = 本地这份是个孤儿版本（不在服务端目录里了）。 */
    public fun entryOf(manifest: ModelManifest, sha256: String): ModelManifestEntry? =
        manifest.models.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }

    public fun revisionOf(manifest: ModelManifest, sha256: String): Long? = entryOf(manifest, sha256)?.revision
}
