package com.ake.app.di

import com.ake.core.common.AppError
import com.ake.core.contract.ModelAssetRecord
import com.ake.data.persist.modeldist.ModelAssetLedger
import com.ake.data.persist.modeldist.ModelLedgerHandle
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.ake.data.persist.modeldist.VerifyOutcome
import com.ake.llm.ModelLedgerGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 模型分发链路的装配（`:llm` 的 `ModelLedgerGateway` ← `:data` 的 `ModelAssetLedger`）。
 *
 * ## 为什么这个适配器要落在 `:app` 而不是 `:llm`
 *
 * `ModelAssetLedger` 的构造参数是 `ModelAssetDao`（Room DAO）。适配它就必须
 * `import com.ake.data.persist.db.*` —— 而根 `build.gradle.kts` 的守卫② 把
 * `com.ake.data.persist.db.` / `androidx.room.` 对 `:llm` 列了黑名单。
 * 这是**刻意的**：`:llm` 一旦能直接碰 DAO，就能绕开账本状态机改库，
 * 「Rename 先于 HTTP」这类顺序约束会失去唯一执行者。
 *
 * 组合根是唯一能同时看见两侧的地方（`:app` 无 import 黑名单），所以适配器放这里，
 * 与 `CoreBindingsModule.bindStepTraceCodec` 是同一个理由。
 *
 * ## ⚠ 本文件**现在一行 `persist.db` 都不碰**（见下）
 *
 * ### ★ 账本装配已搬回 `:data`（`PersistenceModule.provideModelAssetLedger`）
 *
 * 本模块此前临时提供 `ModelAssetDao`（`db.modelAssetDao()`），为此 `:app` 还多引了一行
 * `room-runtime`。账本本来就是 `:data` 的类、Room 本来就在 `:data`，那条 `@Provides`
 * 与那行依赖都已删除 —— **`:app` 现在一行 Room 都不碰**，本文件只做「接口 ↔ 账本」的转发。
 */
@Module
@InstallIn(SingletonComponent::class)
public object ModelDistributionModule {

    /**
     * 只做绑定：**不碰 DAO**。`ModelAssetLedger` 由 `:data` 的 `PersistenceModule` 装配，
     * 它自己知道去 `AkeDatabase` 取 DAO —— 这里再取一次就是同一份 Room 依赖的第二次泄漏。
     */
    @Provides
    @Singleton
    public fun provideModelLedgerGateway(ledger: ModelAssetLedger): ModelLedgerGateway =
        RoomModelLedgerGateway(ledger)
}

/**
 * 纯转发。**任何业务规则都不许写在这里** —— 规则归 `ModelAssetLedger`，
 * 这里只要多写一个 `if`，`:llm` 的单测与真机行为就会分叉。
 */
internal class RoomModelLedgerGateway(
    private val ledger: ModelAssetLedger,
) : ModelLedgerGateway {

    override suspend fun beginOrResume(entry: ModelManifestEntry): ModelLedgerHandle =
        ledger.beginOrResume(entry)

    override suspend fun progress(assetId: String, bytes: Long): Unit =
        ledger.progress(assetId, bytes)

    override suspend fun markVerifying(assetId: String): Unit = ledger.markVerifying(assetId)

    override suspend fun finish(
        assetId: String,
        entry: ModelManifestEntry,
        actualBytes: Long?,
        actualSha256: String?,
    ): VerifyOutcome = ledger.finish(assetId, entry, actualBytes, actualSha256)

    override suspend fun fail(assetId: String, error: AppError): Unit = ledger.fail(assetId, error)

    override suspend fun installed(): List<ModelAssetRecord> = ledger.installed()

    override suspend fun ownedFileNames(modelId: String): List<String> = ledger.ownedFileNames(modelId)
}
