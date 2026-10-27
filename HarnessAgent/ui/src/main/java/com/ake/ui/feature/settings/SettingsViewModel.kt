package com.ake.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Hosts
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.LocalSettings
import com.ake.core.ports.LoopBudgetPort
import com.ake.core.ports.SettingsStorePort
import com.ake.ui.spi.PrivacyPort
import com.ake.ui.spi.StorageUsage
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
    private val settings: SettingsStorePort,
    private val health: DeviceHealthPort,
    /**
     * 预算只读查询。★ 注入 `core:ports` 的 [LoopBudgetPort]，不再直接读 `:agent` 的
     * `LoopBudget` / `ContextBudget` —— 那两个是本文件此前唯一的 `:agent` 引用，
     * 也是 `ui/build.gradle.kts` 里那条 `:agent` 依赖边在源码里的落点。
     */
    private val budgets: LoopBudgetPort,
) : ViewModel() {

    public val local: StateFlow<LocalSettings> =
        settings.values().stateIn(viewModelScope, SharingStarted.Eagerly, LocalSettings())

    private val _ui = MutableStateFlow(SettingsUi())
    public val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init {
        // ★ 没有 `settings.refresh()` 了 —— 装载由 `SettingsStorePort.values()` 的
        //   `onStart` 自己保证，「调用方必须记得先 refresh」这类隐式契约已移除。
        refreshBudget()
        refreshUsage()
    }

    private fun refreshBudget() {
        val tier = health.memoryTier()
        val b = budgets.forTier(tier)
        _ui.update {
            it.copy(
                budget = BudgetFacts(
                    memoryTier = tier.name,
                    maxSteps = b.maxSteps,
                    maxWallClockMs = b.maxWallClockMs,
                    perStepMs = b.perStepMs,
                    decodeMs = b.decodeMs,
                    actionMs = b.actionMs,
                    maxPrefillTokens = b.maxPrefillTokens,
                    maxDecodeTokens = b.maxDecodeTokens,
                    workingWindow = b.workingWindowTokens,
                    outputReserve = b.outputReserveTokens,
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
            _ui.update {
                it.copy(
                    hostDraft = "",
                    message = if (host.isBlank()) INVALID_HOST_MESSAGE else "已经在名单里了",
                )
            }
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

    /**
     * 录入侧的 host 归一化 —— **转发**给 [Hosts.normalise]，不在这里再实现一份。
     *
     * ## 为什么必须是转发而不是"同口径的等价实现"
     *
     * 白名单里写进去的那个字符串，就是 `DomainPolicy` 匹配时用的那个字符串
     * （`host == it || host.endsWith(".$it")`，精确等值）。于是录入侧与判定侧只要差一步 ——
     * 少小写、少去尾点 —— `PASTEBIN.com` / `pastebin.com.` 就能从黑名单硬拒里绕出去。
     * 全仓曾经有 5 份不等价的实现，病根正是"各自实现 + 等价断言"：那等于要求 5 个文件
     * 永远同步，而它们已经证明了同步不了。所以这里只做转发。
     *
     * ## 那为什么裸域名还要兜一层
     *
     * [Hosts.normalise] 只认「字母开头 + `://`」，裸域名一律判成"没有 host" ⇒ 空串。
     * 这在**判定侧**是对的（保守分支），但设置页输入框的 placeholder 写的就是
     * `example.com`，照搬就会把用户最正常的输入拒掉。所以这里对"确实只填了一个域名"
     * 的输入**补上缺失的协议**，再走同一条管道 —— 补的是协议，不是重算一遍 host。
     *
     * ★ 兜底只认裸域名：`example.com/list` 的首段是 path、`about:blank` 是 opaque，
     *   它们都没有 host，补协议等于凭空造出一个 host —— 那正是会让黑名单失效的那类 bug。
     *
     * @return 归一化后的裸域名；判不出 host 时返回空串（调用方据此提示用户，不崩、不静默接受）。
     */
    public fun normalizeHost(raw: String): String {
        val s = raw.trim()
        val hierarchical = Hosts.normalise(s)
        if (hierarchical.isNotEmpty()) return hierarchical
        if (!BARE_HOST.matches(s)) return ""
        return Hosts.normalise("$ASSUMED_SCHEME$s")
    }

    private companion object {

        /** 补在裸域名前面的协议。只用于喂给 [Hosts.normalise]，不参与任何判定。 */
        const val ASSUMED_SCHEME: String = "https://"

        /**
         * 「用户只填了一个域名」的形状：不含 path / query / fragment / userinfo，
         * 端口只能是纯数字。IPv6 必须带方括号 —— 裸写 `::1` 不是 URL 的 host 段。
         *
         * ★ 这是**录入形状的判别**，不是归一化：小写 / `www.` / 尾点 / 端口 / userinfo
         *   全交给 [Hosts.normalise]，这里一个字符都不重算。
         */
        val BARE_HOST: Regex = Regex("""^(?:\[[0-9A-Fa-f:.]+\]|[^\s/?#@:]+)(?::\d+)?$""")

        /** 提示文案带例子：用户被拒时最需要知道的是"该写成什么样"，不是"你错了"。 */
        const val INVALID_HOST_MESSAGE: String =
            "域名不合法：请填 example.com，或带协议写成 https://example.com"
    }

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
