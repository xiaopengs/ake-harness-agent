package com.ake.ui.feature.modelmgt

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Outcome
import com.ake.core.ports.LocalModelCandidate
import com.ake.core.ports.LocalModelReceipt
import com.ake.core.ports.ModelAvailability
import com.ake.core.ports.ModelImportPort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 整个导入流程停在哪一步。 */
public enum class ImportStep {
    /** 还没开始：一句解释 + 「选择 manifest.json」。 */
    IDLE,

    /** manifest 已验签通过，列出条目让用户挑。 */
    CHOOSE_MODEL,

    /** 正在拷 2.6 GB + 校验摘要。 */
    COPYING,

    /** 成了，显示回执。 */
    DONE,
}

/** 候选条目 + 「是不是早就在设备上了」。列表要用它决定要不要挂个标记。 */
public data class CandidateRow(
    val candidate: LocalModelCandidate,
    val alreadyOnDevice: Boolean,
)

/** 本地导入的完整状态。所有显示要用的东西都在里面，Composable 不补算。 */
public data class ModelImportUi(
    val step: ImportStep = ImportStep.IDLE,
    val manifestVersion: Long? = null,
    val rows: List<CandidateRow> = emptyList(),
    val selected: LocalModelCandidate? = null,

    /** 已从源读出的字节数。**真实值**，由包裹在流上的计数器给出。 */
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    /** 拷贝起点的 `elapsedRealtime`，用来判断「卡在 0 字节多久了」。 */
    val startedAt: Long = 0L,

    val receipt: LocalModelReceipt? = null,
    val errorCode: String? = null,
)

/**
 * 「从本地导入」的状态机。
 *
 * ## 为什么是两步（而不是让用户一次选两个文件）
 *
 * 校验基准在 manifest 里：没有它，客户端不知道该期待多大、摘要该是多少。顺序只能是
 * **manifest 先 → 文件后**。反过来的话就得先把几十亿字节读完，再回头问 manifest ——
 * 那等于先收下一份没有依据的二进制。
 *
 * ## ★★ 进度：有字节就给字节，没有就承认不知道
 *
 * [ModelImportPort.importModelFile] 的回调给的是**真实已读字节数**（流上的计数），
 * 所以能显示百分比 + 「已拷 x / y」。但有两种情况下暂时没有字节可数：
 *  - 刚起步那一瞬间；
 *  - 盘上已经有一份同名文件时，那一趟是在**顺序重读它算摘要**，压根没有源流。
 *
 * 这两种都显示不确定进度条并如实说明在做什么，**绝不按时间插值出一个百分比**。
 * 理由：2.6 GB 的拷贝在一台降频的手机上，后半程能比前半程慢一半；任何按时间外推的
 * 数字都是假的，而给一个正在等 20 分钟的用户一个假数字，比直接说「还在跑」糟得多。
 *
 * ## 速度 / 剩余时间由 UI 采样算
 *
 * 与下载那张卡片同法：1 秒差分。后端只知道累计值，差分是唯一不会说谎的算法。
 */
@HiltViewModel
public class ModelImportViewModel @Inject constructor(
    private val importer: ModelImportPort,
) : ViewModel() {

    private val _ui = MutableStateFlow(ModelImportUi())
    public val ui: StateFlow<ModelImportUi> = _ui.asStateFlow()

    private var samplingJob: Job? = null
    private var lastBytes = 0L
    private var lastSampleAt = 0L

    /** 第一步：用户选了 manifest 文件。 */
    public fun onManifestPicked(sourceUri: String?) {
        if (sourceUri == null) return   // 用户直接退出了选择器：保持现状，不弹错

        viewModelScope.launch {
            _ui.update { ModelImportUi(manifestVersion = it.manifestVersion) }

            when (val r = importer.importManifest(sourceUri)) {
                is Outcome.Err -> _ui.update { it.copy(errorCode = r.error.code) }

                is Outcome.Ok -> {
                    val rows = r.value.candidates.map { c ->
                        CandidateRow(c, importer.alreadyImported(c))
                    }
                    _ui.update {
                        it.copy(
                            step = ImportStep.CHOOSE_MODEL,
                            manifestVersion = r.value.manifestVersion,
                            rows = rows,
                            // 默认勾选第一个还能装的：REVOKED 的不许挑，见下面那条裁决。
                            selected = rows.firstOrNull { it.candidate.availability == ModelAvailability.ACTIVE }
                                ?.candidate,
                            errorCode = null,
                        )
                    }
                }
            }
        }
    }

    public fun select(row: CandidateRow) {
        if (row.candidate.availability == ModelAvailability.REVOKED) return
        _ui.update { it.copy(selected = row.candidate) }
    }

    /** 第二步：用户选了模型文件。这一步要走完 2.6 GB。 */
    public fun onModelFilePicked(sourceUri: String?) {
        val candidate = _ui.value.selected ?: return
        if (sourceUri == null) return
        if (candidate.availability == ModelAvailability.REVOKED) return

        viewModelScope.launch {
            _ui.update {
                it.copy(
                    step = ImportStep.COPYING,
                    copiedBytes = 0L,
                    totalBytes = candidate.sizeBytes,
                    bytesPerSecond = 0L,
                    etaSeconds = null,
                    startedAt = SystemClock.elapsedRealtime(),
                    errorCode = null,
                    receipt = null,
                )
            }
            startSampling()

            // ★ 这个回调来自 Dispatchers.IO；StateFlow.update 是线程安全的，直接写即可。
            when (val r = importer.importModelFile(candidate, sourceUri) { bytes ->
                _ui.update { it.copy(copiedBytes = bytes) }
            }) {
                is Outcome.Err -> {
                    stopSampling()
                    _ui.update { it.copy(step = ImportStep.CHOOSE_MODEL, errorCode = r.error.code) }
                }

                is Outcome.Ok -> {
                    stopSampling()
                    _ui.update { it.copy(step = ImportStep.DONE, receipt = r.value) }
                }
            }
        }
    }

    /** 回到第一步：换个 manifest 重来。 */
    public fun reset() {
        stopSampling()
        _ui.update { ModelImportUi() }
    }

    public companion object {
        /**
         * 「盘上可能已经有了一份、正在重算摘要」这段没有字节可数的静默期的容忍上限。
         * 超过这个毫秒数还出不来字节，就换成不确定进度条并说明在做什么。
         */
        public const val SILENT_MS: Long = 2_000L
    }

    // ──────────────────────────────────────────────── 采样（速度 / 剩余时间）

    private fun startSampling() {
        samplingJob?.cancel()
        lastBytes = 0L
        lastSampleAt = SystemClock.elapsedRealtime()
        samplingJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val cur = _ui.value
                val now = SystemClock.elapsedRealtime()
                val dtMs = (now - lastSampleAt).coerceAtLeast(1L)
                val delta = cur.copiedBytes - lastBytes
                val speed = (delta * 1000L / dtMs).coerceAtLeast(0L)
                val remain = (cur.totalBytes - cur.copiedBytes).coerceAtLeast(0L)
                _ui.update {
                    it.copy(
                        bytesPerSecond = speed,
                        etaSeconds = if (speed > 0L) remain / speed else null,
                    )
                }
                lastBytes = cur.copiedBytes
                lastSampleAt = now
            }
        }
    }

    private fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 本轮的两个产品裁决（写在这里而不是挨着某一行，是因为它们各自跨了好几个分支，
// 只看一处分支漏掉另一处就会不一致）：
//
// ── 裁决一：REVOKED 的条目不许挑 ──────────────────────────────────────────────
//
// `REVOKED` 的语义是「已下线，不再允许加载」（典型原因：发现输出有害）。
// 让用户花两个小时拷进去一个引擎根本不会加载的模型，是这个界面能犯的最贵的一个错 ——
// 而且它会以一种最难归因的样子失败：导入明明成功了，模型页却说「未就绪」。
// 所以它在列表上就是灰的，选不动。
//
// ⚠ 这不等于「该把它删掉」。删不该删什么属于 `:data` 那份 manifest 的决策，UI 不持有它。
//
// ── 裁决二：MANIFEST_DOWNGRADE 拒绝，而不是「允许但警告」 ──────────────────────
//
// 后端（`ModelManifestImporter`）已经是拒绝的，所以这里没有「放不放行」的开关可拨；
// 即便有，也应当拒绝。三条理由：
//
//  1. **它是重放攻击面。** 签名合法不等于内容新鲜：攻击者只要肯留着一份旧的、
//     签名合法的 manifest，就能把 ACTIVE 指针推回一个已经被 REVOKED 的模型。
//     「允许用户确认后继续」在这里等于把这道门交给正在被引导的用户去判断。
//  2. **`manifest_version` 的单调性是既定的。** 一旦允许它变小，
//     这个数字就再也不能用来判断「我手上这份是不是比服务端旧」了 ——
//     而它恰恰就是为了这一件事存在的。
//  3. **没有正当用途需要它。** 想装旧版模型根本不需要旧 manifest：服务端在 manifest 里
//     同时挂着新旧 revision，回滚是服务端的事。用户手里的旧 manifest 不提供任何
//     新一份里没有的信息。
//
// ⇒ 唯一要做的是把话说明白：告诉他**去拿最新的那一份**，而不是「换个版本试试」。
// ═════════════════════════════════════════════════════════════════════════════
