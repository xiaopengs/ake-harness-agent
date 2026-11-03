package com.ake.ui.feature.modelmgt

import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState
import com.ake.core.modelapi.ModelErrorCodes
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.ModelLifecyclePort
import com.ake.core.ports.ModelAssetStorePort
import com.ake.core.ports.UnloadReason
import com.ake.ui.runtime.AgentSessionCoordinator
import com.ake.ui.spi.PrivacyPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 模型页的完整状态。 */
public data class ModelUi(
    val asset: ModelAssetRecord,
    val runtime: ModelRuntimeState = ModelRuntimeState.Unloaded,
    /** 下载速度（字节/秒）。由 UI 采样计算，后端目前不给速度。 */
    val bytesPerSecond: Long = 0L,
    /** 预计剩余秒数。为 null 表示速度还没测出来。 */
    val etaSeconds: Long? = null,
    val availableBytes: Long = 0L,
    /** 预检结论：空间是否够（`ModelDownloader` 要求 size × 1.15）。 */
    val enoughSpace: Boolean = true,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val busy: Boolean = false,
    /**
     * 已发起、但还没收到第一条进度。
     *
     * 从「点按钮」到「第一条 Running 事件」之间隔着 DNS、建连、创建 `.part` 文件，
     * 在弱网下能有十几秒。这段时间如果界面一动不动，用户唯一的判断是**它坏了**。
     * 所以按钮要立刻变成「正在准备…」，而不是等后端先动。
     */
    val starting: Boolean = false,
    /** 发起后超过 [PROGRESS_WATCHDOG_MS] 仍无任何进度：地址不可达 / 清单未配置 / 网络被拦。 */
    val progressStale: Boolean = false,
)

/**
 * 模型管理页的 ViewModel。
 *
 * ## 2.6 GB 决定了这个页面的形态
 *
 * 一次下载在移动网络上的成功率极低，所以它必须能**中断 10 次后仍然完成**
 * （`ModelDownloader` 用 HTTP Range 续传 + 原子 rename 做到）。
 * 对应到 UI 上就是：任何一个状态下都要能看见「已经下了多少、还要多久、失败原因是什么、能不能重试」——
 * 一个只显示「下载中 43%」的页面在断网一次之后就失去意义了。
 *
 * ## 速度 / 剩余时间由 UI 算，不由后端给
 *
 * `ModelAssetRecord` 只有 `bytesDownloaded` 这个累计值，没有速度。
 * 这里按 1 秒采样差分算速度 —— **采样点放在 UI 是刻意的**：
 * 后端给速度就会引入「用谁的时钟」「窗口多长」这类与业务无关的争论，
 * 而差分是唯一不会说谎的算法。
 */
@HiltViewModel
public class ModelViewModel @Inject constructor(
    private val lifecycle: ModelLifecyclePort,
    private val assetStore: ModelAssetStorePort,
    private val modelPort: com.ake.core.modelapi.ModelPort,
    private val health: DeviceHealthPort,
    private val privacy: PrivacyPort,
    private val coordinator: AgentSessionCoordinator,
) : ViewModel() {

    private val _ui = MutableStateFlow(ModelUi(asset = DEFAULT_ASSET))
    public val ui: StateFlow<ModelUi> = _ui.asStateFlow()

    private var samplingJob: Job? = null
    private var downloadJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastBytes = 0L
    private var lastSampleAt = 0L

    init {
        viewModelScope.launch {
            // 资产状态是 Flow，且在下载过程中持续更新
            lifecycle.assetState().collect { asset ->
                _ui.update {
                    val moving = asset.state == ModelAssetState.DOWNLOADING ||
                        asset.state == ModelAssetState.VERIFYING
                    it.copy(
                        asset = asset,
                        busy = moving,
                        // 第一条进度到达 = 链路是通的，之前的"准备中/没反应"提示全部撤掉
                        starting = if (moving) false else it.starting,
                        progressStale = if (moving) false else it.progressStale,
                    )
                }
                lastBytes = asset.bytesDownloaded
                lastSampleAt = android.os.SystemClock.elapsedRealtime()
            }
        }
        viewModelScope.launch {
            modelPort.runtimeState.collect { st -> _ui.update { it.copy(runtime = st) } }
        }
        viewModelScope.launch {
            // 冷启动时把「已就绪的模型」补上：`assetState()` 的初值是占位记录
            val ready = runCatching { assetStore.currentReady() }.getOrNull()
            if (ready != null) _ui.update { it.copy(asset = ready) }
            refreshSpace()
        }
        startSampling()
    }

    /** 开始 / 继续：下载 → 校验 → 加载，全过程幂等。 */
    public fun ensureReady() {
        if (downloadJob?.isActive == true) return
        _ui.update { it.copy(starting = true, progressStale = false, errorCode = null, errorMessage = null) }
        armProgressWatchdog()
        downloadJob = viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorCode = null, errorMessage = null) }
            refreshSpace()
            when (val r = lifecycle.ensureReady(MODEL_ID)) {
                is Outcome.Ok -> _ui.update {
                    it.copy(busy = false, starting = false, progressStale = false, errorCode = null, errorMessage = null)
                }
                is Outcome.Err -> _ui.update {
                    it.copy(
                        busy = false,
                        starting = false,
                        progressStale = false,
                        errorCode = r.error.code,
                        errorMessage = errorTextOf(r.error),
                    )
                }
            }
        }
    }

    /**
     * 看门狗：发起后 [PROGRESS_WATCHDOG_MS] 还没有任何进度，就明确告诉用户"没收到进度"。
     *
     * 为什么不在失败时才提示：地址 404 / DNS 被污染 / 清单未签名这几类问题，
     * 底层可能要重试好几轮才放弃（默认 3 次 + 退避），前端在那之前全是静默的。
     * 与其让用户盯着一个不动的按钮猜，不如 10 秒就把话说明白。
     */
    private fun armProgressWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            delay(PROGRESS_WATCHDOG_MS)
            val cur = _ui.value
            if (cur.starting) {
                _ui.update { it.copy(progressStale = true) }
            }
        }
    }

    /** 暂停当前下载；半成品由底层保留，继续时使用 Range 续传。 */
    public fun pauseDownload() {
        if (_ui.value.asset.state != ModelAssetState.DOWNLOADING) return
        watchdogJob?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        _ui.update { it.copy(starting = false, progressStale = false) }
        _ui.update { current ->
            current.copy(asset = current.asset.copy(state = ModelAssetState.PAUSED), busy = false)
        }
    }

    /** 取消下载并删除已归属此模型的半成品。 */
    public fun cancelDownload(onCancelled: () -> Unit = {}) {
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch {
            lifecycle.deleteModelFile(MODEL_ID)
            _ui.update { it.copy(busy = false, errorCode = null, errorMessage = null, asset = DEFAULT_ASSET) }
            onCancelled()
        }
    }

    /** 主动卸载：保护 WebView 不被 LMK 杀掉。 */
    public fun unload() {
        viewModelScope.launch { lifecycle.unload(UnloadReason.USER_STOP) }
    }

    /**
     * 删除模型文件。2.6 GB，重下代价高 —— 所以必须二次确认，UI 侧强制走确认弹窗。
     */
    public fun deleteFile() {
        viewModelScope.launch {
            when (val r = lifecycle.deleteModelFile(MODEL_ID)) {
                is Outcome.Err -> _ui.update { it.copy(errorCode = r.error.code, errorMessage = errorTextOf(r.error)) }
                else -> Unit
            }
        }
    }

    public fun refreshSpace() {
        viewModelScope.launch {
            val usage = privacy.usage()
            val need = (_ui.value.asset.expectedBytes * SPACE_HEADROOM).toLong()
            _ui.update { it.copy(availableBytes = usage.availableBytes, enoughSpace = usage.availableBytes >= need) }
        }
    }

    private fun startSampling() {
        samplingJob?.cancel()
        samplingJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val cur = _ui.value.asset
                if (cur.state != ModelAssetState.DOWNLOADING) {
                    lastBytes = cur.bytesDownloaded
                    lastSampleAt = android.os.SystemClock.elapsedRealtime()
                    continue
                }
                val now = android.os.SystemClock.elapsedRealtime()
                val dtMs = (now - lastSampleAt).coerceAtLeast(1L)
                val speed = ((cur.bytesDownloaded - lastBytes) * 1000L / dtMs).coerceAtLeast(0L)
                val remain = (cur.expectedBytes - cur.bytesDownloaded).coerceAtLeast(0L)
                _ui.update {
                    it.copy(
                        bytesPerSecond = speed,
                        etaSeconds = if (speed > 0L) remain / speed else null,
                    )
                }
                lastBytes = cur.bytesDownloaded
                lastSampleAt = now
            }
        }
    }

    /** SoC 画像：决定能不能走 NPU / GPU。 */
    public fun socSummary(): String {
        val p = health.socProfile()
        val npu = p.npuBrand ?: "无"
        return "${p.manufacturer} ${p.socModel} · 内存 ${p.totalRamMb / 1024} GB · NPU：$npu"
    }

    public fun memoryTier(): String = health.memoryTier().name

    public fun errorTextOf(e: AppError): String {
        val expectedBytes = _ui.value.asset.expectedBytes.takeIf { it > 0L }
            ?: com.ake.ui.feature.onboarding.ModelGateViewModel.VERIFIED_MODEL_BYTES
        val size = formatBytes(expectedBytes)
        return when (e.code) {
            ModelErrorCodes.DOWNLOAD_NO_SPACE -> "存储空间不足。$size 的模型还需要 15% 临时空间，请先清理存储。"
            ModelErrorCodes.DOWNLOAD_FAILED -> "网络连接中断。检查网络后重试，会从已下载的位置继续。"
            ModelErrorCodes.CHECKSUM_MISMATCH -> "下载的文件不完整，已安全丢弃。请换一个稳定的网络重试。"
            ModelErrorCodes.LOAD_FAILED -> "模型暂时无法启动，请关闭占用内存较多的应用后重试。"
            ModelErrorCodes.OUT_OF_MEMORY -> "可用内存不足，请先关闭其他应用后重试。"
            ModelErrorCodes.OPENCL_MISSING -> "这台设备不支持首选加速方式，已切换为兼容模式。"
            else -> "暂时无法准备模型，请检查网络和存储空间后重试。"
        }
    }

    public companion object {
        public const val MODEL_ID: String = AgentSessionCoordinator.DEFAULT_MODEL_ID

        /** 与 `ModelDownloader.SPACE_HEADROOM` 同值：预检要 15% 余量。 */
        public const val SPACE_HEADROOM: Double = 1.15

        /**
         * 发起下载后多久没有进度就提示"没收到进度"。
         *
         * 10 秒 ≈ 弱网下建连 + 首包的上限。**超时的典型原因不是慢，是域名根本连不上**：
         * 内置地址指向 `huggingface.co`，在中国大陆的移动网络下通常是被拦的 ——
         * 表现为「一点字节都不进来，最后报 DOWNLOAD_FAILED」，而不是一个清楚的 404。
         */
        public const val PROGRESS_WATCHDOG_MS: Long = 10_000L

        /**
         * 清单里的 sha256 是否还是占位值。
         *
         * `ModelCatalog` 的内置条目写着 `REPLACE_WITH_SIGNED_MANIFEST_SHA256`，
         * 这意味着**下载完也一定会被校验拒绝**。这是刻意的 fail-closed，
         * 但对用户来说就是"下了 2.6 GB 然后说文件损坏"。所以必须在开始下载之前就说清楚，
         * 而不是让他白等十分钟。
         */
        public fun isUnsignedManifest(sha256: String): Boolean =
            sha256.length != 64 || !sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /** 与 `:llm` 的 `DEFAULT_RECORD` 同形：Flow 必须有初值，否则 UI 只能自己编一个假状态。 */
        public val DEFAULT_ASSET: ModelAssetRecord = ModelAssetRecord(
            id = "unset",
            variant = "unset",
            remoteUrl = "",
            localPath = null,
            expectedBytes = 0L,
            bytesDownloaded = 0L,
            sha256 = "",
            state = ModelAssetState.NOT_PRESENT,
            contextWindow = 0,
            minRamGb = 0,
            updatedAt = 0L,
        )
    }
}

/** 可用空间。`StatFs` 放在这里是无奈：可用空间属于设备能力，本该由 `DeviceHealthPort` 给。 */
public fun availableBytesOf(dir: java.io.File): Long = runCatching {
    val stat = StatFs(dir.absolutePath)
    stat.availableBlocksLong * stat.blockSizeLong
}.getOrDefault(0L)
