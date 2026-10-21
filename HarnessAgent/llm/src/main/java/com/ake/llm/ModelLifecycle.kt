package com.ake.llm

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.contract.ModelAssetRecord
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
internal class LiteRtModelLifecycle @Inject constructor(
    private val modelPort: LiteRtModelPort,
    private val downloader: ModelDownloader,
    private val deviceHealth: DeviceHealthPort,
    private val catalog: ModelCatalog,
) : ModelLifecyclePort {

    private val _assetState = MutableStateFlow<ModelAssetRecord>(DEFAULT_RECORD)
    override fun assetState(): Flow<ModelAssetRecord> = _assetState.asStateFlow()

    override suspend fun ensureReady(modelId: String): Outcome<ModelAssetRecord> {
        val asset = catalog.find(modelId)
            ?: return Outcome.Err(AppError(ModelErrorCodes.LOAD_FAILED, "err_model_unknown_id"))

        val local = downloader.localFileOf(modelId)
        if (!local.exists()) {
            val result = downloader.download(
                ModelDownloader.Request(
                    id = modelId,
                    remoteUrl = asset.remoteUrl,
                    expectedBytes = asset.expectedBytes,
                    sha256 = asset.sha256,
                ),
                onProgress = { p ->
                    _assetState.value = downloaded(asset, p)
                },
            )
            if (result is com.ake.core.common.Outcome.Err) {
                return com.ake.core.common.Outcome.Err(result.error)
            }
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
