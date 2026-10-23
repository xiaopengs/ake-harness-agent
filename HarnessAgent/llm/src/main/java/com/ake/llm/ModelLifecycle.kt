package com.ake.llm

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.contract.ModelAssetRecord
import com.ake.data.persist.modeldist.ModelEntryState
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.ModelLifecyclePort
import com.ake.core.ports.UnloadReason
import com.ake.core.modelapi.ModelErrorCodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `ModelLifecyclePort` 实现 —— 模型加载/卸载生命周期 + 下载编排（架构 §6.4）。
 *
 * ### 为什么必须有卸载，而且是主动的
 *
 * 一个常驻 2.58 GB 模型的进程在中端机上会持续挤压 LMK。**被杀掉的往往不是 App 而是 WebView**
 * —— 而我们恰恰依赖 WebView 活着去执行动作。释放模型是保护它的唯一方式。
 * 代价是重新 `initialize()` 的秒级冷启，用 `cacheDir` 缓解第二次及以后的加载（官方明示有效）。
 *
 * ### 卸载触发源（§6.4）
 *  - 退后台 60s
 *  - 任务结束空闲 5min
 *  - `onTrimMemory(TRIM_MEMORY_BACKGROUND)` / `onLowMemory()`
 *  - `ThermalState.SEVERE`
 *  - 用户手动（腾讯/清理 UI 入口）
 *
 * ### MVP 单模型
 *
 * §6.5 的总账结论是单模型（双模型为省 9% 延迟要多付 551 MB RSS，内存是头号约束）。
 * 但 `ModelPort`/`SessionSpec` 天然支持多实例，加第二个模型不需要动 L4/L5。
 */
@Singleton
// ★ 同 `LiteRtModelPort`：`:app` 要 `@Binds` 它到 `ModelLifecyclePort`，故必须 public。
public class LiteRtModelLifecycle @Inject constructor(
    private val modelPort: LiteRtModelPort,
    private val downloader: ModelDownloader,
    private val deviceHealth: DeviceHealthPort,
    private val catalog: ModelCatalog,
    /**
     * manifest 分发链路。**每条下载都必须经过它**，因为它负责三件 `ModelDownloader`
     * 自己不管的事：归档旧模型（硬规则 1）、把进度落库（跨重启续传）、按账本裁决重试/放弃。
     */
    private val sync: ModelDistributionSync,
) : ModelLifecyclePort {

    private val _assetState = MutableStateFlow<ModelAssetRecord>(DEFAULT_RECORD)
    override fun assetState(): Flow<ModelAssetRecord> = _assetState.asStateFlow()

    override suspend fun ensureReady(modelId: String): Outcome<ModelAssetRecord> {
        val asset = catalog.find(modelId)
            ?: return Outcome.Err(AppError(ModelErrorCodes.LOAD_FAILED, "err_model_unknown_id"))

        val local = when (val r = ensureLocalFile(asset)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return Outcome.Err(r.error)
        }

        _assetState.value = asset.copy(state = "VERIFYING")

        val loadOutcome = modelPort.load(
            ModelLoadConfig(
                modelId = modelId,
                path = local.absolutePath,
                cacheDir = null,
                backend = BackendPolicy.preferForRam(deviceHealth.socProfile().totalRamMb),
                maxNumTokens = asset.contextWindow,
                allowFallback = true,
            ),
        )
        return when (loadOutcome) {
            is Outcome.Ok -> {
                val ready = asset.copy(
                    localPath = local.absolutePath,
                    bytesDownloaded = local.length(),
                    state = "READY",
                )
                _assetState.value = ready
                Outcome.Ok(ready)
            }

            is Outcome.Err -> {
                _assetState.value = asset.copy(state = "CORRUPTED")
                Outcome.Err(loadOutcome.error)
            }
        }
    }

    override suspend fun unload(reason: UnloadReason) {
        modelPort.unload()
        val cur = _assetState.value
        if (cur.state == "READY") _assetState.value = cur.copy(state = "PAUSED")
    }

    override suspend fun deleteModelFile(modelId: String): Outcome<Unit> {
        modelPort.unload()
        return downloader.delete(modelId).also {
            _assetState.value = DEFAULT_RECORD
        }
    }

    /** 低内存回调直连：同步释放，不等 coroutine 调度。 */
    suspend fun onTrimMemoryBackground() {
        if (modelPort.currentBackend != null) {
            com.ake.core.common.AkeLog.w(TAG, "trim memory: unloading model to protect WebView")
            unload(UnloadReason.LOW_MEMORY)
        }
    }

    /**
     * 拿到可以交给引擎的模型文件。
     *
     * ## 两条路径
     *
     *  1. **遗留文件**：本轮之前装的模型文件名是 `<modelId>.litertlm`，`model_asset` 表里
     *     **没有**对应行（账本是这一轮才接进来的）。直接走账本会被判成「没装过」
     *     ⇒ 重新下载 2.6 GB。所以先按内容认一次（大小 + 摘要），命中就直接加载。
     *  2. **账本路径**：其余一切情况交给 `ModelDistributionSync` —— 它负责归档旧模型、
     *     落库进度、按账本裁决重试或放弃。
     *
     * ⚠ 遗留路径认的是**内容**而不是文件名：将来真值 sha256 填进 `ModelCatalog` 后，
     *   老用户不会因为这个改动被强制重下一次。
     */
    private suspend fun ensureLocalFile(asset: ModelAssetRecord): Outcome<File> {
        val legacy = downloader.localFileOf(asset.id)
        if (legacy.isFile &&
            legacy.length() == asset.expectedBytes &&
            downloader.sha256Of(legacy).equals(asset.sha256, ignoreCase = true)
        ) {
            com.ake.core.common.AkeLog.i(TAG, "命中遗留安装文件，跳过账本：${legacy.name}")
            return Outcome.Ok(legacy)
        }

        return sync.ensure(catalog.manifestEntryOf(asset)) { p ->
            _assetState.value = downloaded(asset, p)
        }
    }

    private fun downloaded(base: ModelAssetRecord, p: ModelDownloader.Progress): ModelAssetRecord = when (p) {
        is ModelDownloader.Progress.Running ->
            base.copy(bytesDownloaded = p.downloadedBytes, state = "DOWNLOADING")

        is ModelDownloader.Progress.Verifying ->
            base.copy(bytesDownloaded = p.downloadedBytes, state = "VERIFYING")

        ModelDownloader.Progress.Done ->
            base.copy(bytesDownloaded = base.expectedBytes, state = "VERIFYING")
    }

    private companion object {
        const val TAG = "ModelLifecycle"

        /**
         * 占位记录。`assetState()` 是 Flow，必须立刻有值 —— 否则 UI 在拿到第一个真实状态之前
         * 只能自己编一个假状态，而那正是 UI 层偶发错乱的常见来源。
         */
        val DEFAULT_RECORD = ModelAssetRecord(
            id = "unset",
            variant = "unset",
            remoteUrl = "",
            localPath = null,
            expectedBytes = 0L,
            bytesDownloaded = 0L,
            sha256 = "",
            state = "NOT_PRESENT",
            contextWindow = 0,
            minRamGb = 0,
            updatedAt = 0L,
        )
    }
}

/**
 * 内置模型清单（架构 §6.4：**签名过的 JSON manifest，绝不打包进 APK**）。
 *
 * Gemma-4-E2B-it 约 2.58 GB —— 打进 APK 会让安装包不可安装（Play 也有体积硬上限）。
 * 这里的清单只描述「去哪下、下多大、校验值是什么」，真正的存储过程由 [ModelDownloader] 负责。
 *
 * 【上线前必办】remoteUrl 与 sha256 必须换成我方签名过的 manifest 里的真实值；
 * 当前是占位常量，若直接使用会因 sha256 不匹配而在校验阶段失败 —— 这是刻意设计的 fail-closed。
 */
@Singleton
public class ModelCatalog @Inject constructor() {

    public fun all(): List<ModelAssetRecord> = listOf(GEMMA4_E2B_IT)

    public fun find(id: String): ModelAssetRecord? = all().firstOrNull { it.id == id }

    /**
     * 内置清单条目 → manifest 条目。
     *
     * 为什么需要这一步：账本的状态机只认 `ModelManifestEntry`。内置清单是
     * **manifest 尚未落地时的兜底**（见类注释「上线前必办」），所以这里按
     * 「revision = 0 的 ACTIVE 条目、兼容矩阵全开」映射 —— 它会在真 manifest
     * 接进来之后被 `ModelResolver` 的决议取代。
     */
    public fun manifestEntryOf(record: ModelAssetRecord): ModelManifestEntry = ModelManifestEntry(
        modelId = record.id,
        variant = record.variant,
        revision = 0L,
        version = "builtin",
        url = record.remoteUrl,
        sizeBytes = record.expectedBytes,
        sha256 = record.sha256,
        minAppVersionCode = 0,
        maxAppVersionCode = null,
        contextWindow = record.contextWindow,
        minRamGb = record.minRamGb,
        capabilities = emptyList(),
        state = ModelEntryState.ACTIVE,
    )

    public companion object {
        public const val GEMMA4_E2B_ID: String = "gemma-4-E2B-it"
        public const val CONTEXT_WINDOW: Int = 32_768

        public val GEMMA4_E2B_IT: ModelAssetRecord = ModelAssetRecord(
            id = GEMMA4_E2B_ID,
            variant = "TEXT_STD",
            remoteUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/model.litertlm",
            localPath = null,
            expectedBytes = 2_770_000_000L, // ≈2.58 GB
            bytesDownloaded = 0L,
            sha256 = "REPLACE_WITH_SIGNED_MANIFEST_SHA256",
            state = "NOT_PRESENT",
            contextWindow = CONTEXT_WINDOW,
            minRamGb = 8,
            updatedAt = 0L,
        )
    }
}
