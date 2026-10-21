package com.ake.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.agent.context.ContextBudget
import com.ake.agent.loop.LoopBudget
import com.ake.core.ports.DeviceHealthPort
import com.ake.ui.spi.PrivacyPort
import com.ake.ui.spi.StorageUsage
import com.ake.ui.spi.UiLocalSettings
import com.ake.ui.spi.WipeReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页状态。 */
public data class SettingsUi(
    /** 只读：本机的硬预算表（来自 `LoopBudget.forTier` / `ContextBudget.forTier`）。 */
    val budget: BudgetFacts = BudgetFacts(),
    val usage: StorageUsage? = null,
    val wiping: Boolean = false,
    val wipeReport: WipeReport? = null,
    val hostDraft: String = "",
    val confirmWipe: Boolean = false,
    val deleteModelFiles: Boolean = false,
    val message: String? = null,
)

/** 预算事实。全部只读 —— 在没有 `BudgetOverridePort` 之前，UI 不假装有得调。 */
public data class BudgetFacts(
    val memoryTier: String = "MID",
    val maxSteps: Int = 0,
    val maxWallClockMs: Long = 0L,
    val perStepMs: Long = 0L,
    val decodeMs: Long = 0L,
    val actionMs: Long = 0L,
    val maxPrefillTokens: Long = 0L,
    val maxDecodeTokens: Long = 0L,
    val workingWindow: Int = 0,
    val outputReserve: Int = 0,
    val topK: Int = 0,
)

/**
 * 设置页的 ViewModel。
 *
 * ## 「一键清除全部痕迹」是本 App 的隐私核心卖点
 *
 * 我们在用户的**登录态**下访问网页，并且把过程完整录了下来。
 * 所以清除范围必须包括 WebView 的 `app_webview/`（Cookie / LocalStorage / IndexedDB）——
 * 只删自己的数据库而留着它，等于什么都没清。这一点在 `AndroidPrivacyPort` 里是第 ⑥ 步。
 *
 * ## 只读参数为什么不藏起来
 *
 * 上下文预算档位、单步超时这些目前**改不了**（它们由设备内存档位决定，写在 `LoopBudget` 里）。
 * 这里照样显示出来并标注「只读」：让用户看见真实约束，比让他在设置里调一个不生效的滑块要好 ——
 * 后者会造成「我调了但没用」这种最难排查的不一致。
 */
@HiltViewModel
public class SettingsViewModel @Inject constructor(
    private val privacy: PrivacyPort,
    private val settings: UiLocalSettings,
    private val health: DeviceHealthPort,
) : ViewModel() {

    public val local: StateFlow<UiLocalSettings.Values> =
        settings.values.stateIn(viewModelScope, SharingStarted.Eagerly, UiLocalSettings.Values())

    private val _ui = MutableStateFlow(SettingsUi())
    public val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { settings.refresh() }
        refreshBudget()
        refreshUsage()
    }

    private fun refreshBudget() {
        val tier = health.memoryTier()
        val b = LoopBudget.forTier(tier)
        val c = ContextBudget.forTier(tier)
        _ui.update {
            it.copy(
                budget = BudgetFacts(
                    memoryTier = tier.name,
                    maxSteps = b.maxSteps,
                    maxWallClockMs = b.maxWallClockMs,
                    perStepMs = b.perStepMs,
                    decodeMs = b.decodeMs,
                    actionMs = b.actionMs,
                    maxPrefillTokens = b.maxPrefillTokensTotal,
                    maxDecodeTokens = b.maxDecodeTokensTotal,
                    workingWindow = c.workingWindow,
                    outputReserve = c.outputReserve,
                    topK = b.currentTopK,
                ),
            )
        }
    }

    public fun refreshUsage() {
        viewModelScope.launch { _ui.update { it.copy(usage = privacy.usage()) } }
    }

    // ── 域名白名单（真生效：下发到 `TaskSpec.allowHosts`） ──

    public fun onHostDraftChange(v: String): Unit = _ui.update { it.copy(hostDraft = v) }

    public fun addAllowHost() {
        val raw = _ui.value.hostDraft.trim()
        if (raw.isBlank()) return
        val host = normalizeHost(raw)
        val cur = local.value.allowHosts
        if (host.isBlank() || host in cur) {
            _ui.update { it.copy(hostDraft = "", message = if (host.isBlank()) "域名不合法" else "已经在名单里了") }
            return
        }
        viewModelScope.launch {
            settings.update(local.value.copy(allowHosts = cur + host))
            _ui.update { it.copy(hostDraft = "", message = "已加入白名单：$host（下次任务生效）") }
        }
    }

    public fun removeAllowHost(host: String) {
        viewModelScope.launch {
            settings.update(local.value.copy(allowHosts = local.value.allowHosts - host))
        }
    }

    /** 与 `core/common/Url.host` 同口径：小写、去 `www.`、去端口。 */
    public fun normalizeHost(raw: String): String =
        raw.substringAfter("://")
            .substringBefore('/').substringBefore('?')
            .substringAfter('@').substringBefore(':')
            .lowercase().removePrefix("www.")
            .trim()

    // ── 最大步数（真生效：下发到 `TaskSpec.maxSteps`） ──

    public fun setMaxSteps(value: Int?) {
        viewModelScope.launch {
            val cap = _ui.value.budget.maxSteps
            val v = value?.coerceIn(1, cap)
            settings.update(local.value.copy(maxSteps = v))
        }
    }

    public fun setShowDomPayload(on: Boolean) {
        viewModelScope.launch { settings.update(local.value.copy(showDomPayload = on)) }
    }

    // ── 一键清除 ──

    public fun requestWipe(): Unit = _ui.update { it.copy(confirmWipe = true) }

    public fun cancelWipe(): Unit = _ui.update { it.copy(confirmWipe = false, wipeReport = null) }

    public fun setDeleteModelFiles(on: Boolean): Unit = _ui.update { it.copy(deleteModelFiles = on) }

    public fun doWipe() {
        viewModelScope.launch {
            _ui.update { it.copy(wiping = true, message = null) }
            val report = privacy.wipe(deleteModelFiles = _ui.value.deleteModelFiles)
            _ui.update {
                it.copy(
                    wiping = false,
                    confirmWipe = false,
                    wipeReport = report,
                    message = if (report.failures.isEmpty()) {
                        "已清除全部痕迹（含 WebView 的 Cookie 与本地存储）。" +
                            if (report.modelFilesDeleted) "模型文件也已删除。" else "模型文件已保留。"
                    } else {
                        "清除完成，但有 ${report.failures.size} 项失败：${report.failures.joinToString("；")}"
                    },
                )
            }
            refreshUsage()
        }
    }

    public fun disconnectTools() {
        viewModelScope.launch {
            privacy.disconnectTools()
            _ui.update { it.copy(message = "已断开全部外部工具连接") }
        }
    }

    public fun dismissMessage(): Unit = _ui.update { it.copy(message = null) }
}
