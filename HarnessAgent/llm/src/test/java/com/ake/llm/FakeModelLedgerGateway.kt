package com.ake.llm

import com.ake.core.common.AppError
import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState
import com.ake.data.persist.modeldist.ModelFileLayout
import com.ake.data.persist.modeldist.ModelFileOp
import com.ake.data.persist.modeldist.ModelLedgerHandle
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.ake.data.persist.modeldist.ModelVerifyGate
import com.ake.data.persist.modeldist.VerifyAction
import com.ake.data.persist.modeldist.VerifyOutcome
import com.ake.data.persist.modeldist.VerifyVerdict

/**
 * `ModelLedgerGateway` 的假实现 —— **只在单测里替代 `ModelAssetLedger`**。
 *
 * 为什么不用真的：真账本要 `ModelAssetDao`（Room）⇒ 得引 Robolectric 或内存 Room，
 * 而 `:llm` 的黑名单（根 build.gradle.kts 守卫②）本来就禁止 `androidx.room.` 进来。
 *
 * ⚠ 本类**复刻**了 `ModelAssetLedger` 的判定顺序（先同 sha 续传、再归档、再重置槽位），
 * 不是重新设计。真账本改了语义而这里没跟着改，单测会先红 —— 这是它该有的行为。
 */
internal class FakeModelLedgerGateway(
    private val maxVerifyAttempts: Int = 3,
) : ModelLedgerGateway {

    /** 执行轨迹。断言「Rename 先于 HTTP」全靠它。 */
    val log: MutableList<String> = mutableListOf()

    private val rows: MutableMap<String, Row> = linkedMapOf()
    private var seq = 0

    class Row(
        val id: String,
        val modelId: String,
        var variant: String,
        var sha256: String,
        var state: String,
        var localPath: String?,
        var tempFile: String?,
        var attempts: Int,
        var bytes: Long,
    )

    override suspend fun beginOrResume(entry: ModelManifestEntry): ModelLedgerHandle {
        log += "begin(r${entry.revision})"
        val existing = rows.values.firstOrNull { it.modelId == entry.modelId && it.variant == entry.variant }

        if (existing != null && existing.sha256.equals(entry.sha256, ignoreCase = true)) {
            val ready = existing.state == ModelAssetState.READY && !existing.localPath.isNullOrBlank()
            if (!ready) existing.state = ModelAssetState.DOWNLOADING
            return ModelLedgerHandle(existing.id, existing.bytes, ready, emptyList())
        }

        val ops = ArrayList<ModelFileOp>(2)
        if (existing != null && existing.state == ModelAssetState.READY && !existing.localPath.isNullOrBlank()) {
            val archiveName = ModelFileLayout.archiveNameOf(entry.modelId, entry.variant, existing.sha256)
            if (archiveName != existing.localPath) ops += ModelFileOp.Rename(existing.localPath!!, archiveName)

            // 归档行：保持 READY，variant 带 @sha12 避唯一索引
            val archiveId = "archive-${++seq}"
            rows[archiveId] = Row(
                id = archiveId,
                modelId = entry.modelId,
                variant = ModelFileLayout.archiveVariantOf(existing.variant, existing.sha256),
                sha256 = existing.sha256,
                state = ModelAssetState.READY,
                localPath = archiveName,
                tempFile = null,
                attempts = 0,
                bytes = existing.bytes,
            )
            existing.sha256 = entry.sha256
            existing.state = ModelAssetState.DOWNLOADING
            existing.localPath = null
            existing.tempFile = ModelFileLayout.partNameOf(existing.id)
            existing.attempts = 0
            existing.bytes = 0L
        }

        var id = existing?.id
        if (id == null) {
            id = "asset-${++seq}"
            rows[id] = Row(
                id = id,
                modelId = entry.modelId,
                variant = entry.variant,
                sha256 = entry.sha256,
                state = ModelAssetState.DOWNLOADING,
                localPath = null,
                tempFile = ModelFileLayout.partNameOf(id),
                attempts = 0,
                bytes = 0L,
            )
        }

        return ModelLedgerHandle(id, 0L, false, ops)
    }

    override suspend fun progress(assetId: String, bytes: Long) {
        rows[assetId]?.bytes = bytes
    }

    override suspend fun markVerifying(assetId: String) {
        rows[assetId]?.state = ModelAssetState.VERIFYING
    }

    override suspend fun finish(
        assetId: String,
        entry: ModelManifestEntry,
        actualBytes: Long?,
        actualSha256: String?,
    ): VerifyOutcome {
        val row = rows[assetId]
            ?: return VerifyOutcome(VerifyVerdict.FILE_MISSING, VerifyAction.GIVE_UP, 0, emptyList())

        val verdict = ModelVerifyGate.evaluate(entry, actualBytes, actualSha256)
        val attempts = row.attempts + 1
        row.attempts = attempts
        val action = ModelVerifyGate.nextAction(verdict, attempts, maxVerifyAttempts)
        val ops = ArrayList<ModelFileOp>(1)

        when (action) {
            VerifyAction.PROMOTE -> {
                row.state = ModelAssetState.READY
                row.localPath = ModelFileLayout.finalNameOf(assetId)
                row.tempFile = null
            }
            VerifyAction.DISCARD_AND_RESTART, VerifyAction.GIVE_UP -> {
                if (row.tempFile != null) ops += ModelFileOp.Discard(row.tempFile!!)
                row.state = if (action == VerifyAction.GIVE_UP) ModelAssetState.CORRUPTED else ModelAssetState.DOWNLOADING
                if (action == VerifyAction.GIVE_UP) row.tempFile = null
            }
        }
        log += "finish($verdict/$action#$attempts)"
        return VerifyOutcome(verdict, action, attempts, ops)
    }

    override suspend fun fail(assetId: String, error: AppError) {
        log += "fail(${error.code})"
        rows[assetId]?.state = ModelAssetState.PAUSED
    }

    override suspend fun installed(): List<ModelAssetRecord> =
        rows.values
            .filter { it.state == ModelAssetState.READY && !it.localPath.isNullOrBlank() }
            .map {
                ModelAssetRecord(
                    id = it.id,
                    variant = it.variant,
                    remoteUrl = "",
                    localPath = it.localPath,
                    expectedBytes = it.bytes,
                    bytesDownloaded = it.bytes,
                    sha256 = it.sha256,
                    state = it.state,
                    contextWindow = 0,
                    minRamGb = 0,
                    updatedAt = 0L,
                )
            }

    // ── 测试用辅助 ──────────────────────────────────────────────────────────

    /** 预置一条「已装好」的槽位行，模拟升级前的状态。 */
    fun seedReady(entry: ModelManifestEntry, assetId: String = "asset-seeded"): String {
        rows[assetId] = Row(
            id = assetId,
            modelId = entry.modelId,
            variant = entry.variant,
            sha256 = entry.sha256,
            state = ModelAssetState.READY,
            localPath = ModelFileLayout.finalNameOf(assetId),
            tempFile = null,
            attempts = 0,
            bytes = entry.sizeBytes,
        )
        return assetId
    }

    fun attemptsOf(assetId: String): Int = rows[assetId]?.attempts ?: -1
    fun stateOf(assetId: String): String? = rows[assetId]?.state
}
