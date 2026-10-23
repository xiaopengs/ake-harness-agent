package com.ake.llm

import com.ake.core.common.AppError
import com.ake.core.contract.ModelAssetRecord
import com.ake.data.persist.modeldist.ModelLedgerHandle
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.ake.data.persist.modeldist.VerifyOutcome

/**
 * `ModelAssetLedger`（`:data`）在 `:llm` 这边的窄接口。
 *
 * ## 为什么中间要夹一层，而不是直接注入 `ModelAssetLedger`
 *
 * `ModelAssetLedger` 的构造参数是 `ModelAssetDao`（Room DAO）。直接注入它，
 * 就等于把 `:data` 的**持久化实现**拖进 `:llm`：
 *
 *  - 架构守卫② 就得放开 `com.ake.data.persist.db.` 给 `:llm`，
 *    而那条黑名单正是为了防「绕过账本状态机直接改库」才立的；
 *  - 「下载 → 校验 → 账本裁决」这条链就再也没法在 JVM 上单测了 ——
 *    DAO 要么上 Robolectric，要么上内存 Room，都比手写 30 行假账本贵一个数量级。
 *
 * 夹一层之后：`:llm` 只见 `modeldist` 的决议类型，Room 适配器由组合根 `:app` 提供，
 * 而**「Rename 必须在开 HTTP 之前」这条硬规则落点可以被纯 JVM 单测钉死**。
 * 这不是为了好看加的抽象层，是为了让那条规则有机器校验。
 *
 * ⚠ 所有方法签名必须与 `ModelAssetLedger` 保持一一对应：本接口不承载任何业务规则，
 * 规则归账本。加了新方法时同步更新 `:app` 的 `RoomModelLedgerGateway`。
 */
public interface ModelLedgerGateway {

    public suspend fun beginOrResume(entry: ModelManifestEntry): ModelLedgerHandle

    public suspend fun progress(assetId: String, bytes: Long)

    public suspend fun markVerifying(assetId: String)

    public suspend fun finish(
        assetId: String,
        entry: ModelManifestEntry,
        actualBytes: Long?,
        actualSha256: String?,
    ): VerifyOutcome

    public suspend fun fail(assetId: String, error: AppError)

    /** 回滚候选集：所有 READY 行（含归档行）。 */
    public suspend fun installed(): List<ModelAssetRecord>
}
