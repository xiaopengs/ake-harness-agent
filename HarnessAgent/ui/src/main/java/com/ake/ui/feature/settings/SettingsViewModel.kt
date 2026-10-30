package com.ake.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.AkeLog
import com.ake.core.common.Hosts
import com.ake.core.common.Outcome
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetStore
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.LocalSettings
import com.ake.core.ports.Locality
import com.ake.core.ports.LoopBudgetPort
import com.ake.core.ports.SettingsStorePort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.TlsMode
import com.ake.ui.spi.CloudEndpointProbe
import com.ake.ui.spi.CloudProbeResult
import com.ake.ui.spi.PrivacyPort
import com.ake.ui.spi.StorageUsage
import com.ake.ui.spi.WipeReport
import java.util.Optional
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页状态。★ 这里不存在任何能装 API Key 的字段 —— 见 [ApiKeyDraft]。 */
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
    /** 云端推理后端那一块。同样不含任何秘密。 */
    val cloud: CloudBackendUi = CloudBackendUi(),
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

// ═══════════════════════════════════════════════════════════════════════════
// 云端推理后端（架构 08 §3 / §6）
// ═══════════════════════════════════════════════════════════════════════════

/** 已存凭证的**可展示**投影。★ 没有 secret，也没有完整 URL。 */
public data class CredentialSummary(
    val ref: CredentialRef,
    val label: String,
    val modelName: String,
    /** ★ 归一化后的裸域名，来自 `EndpointConfig.hostOf()`。 */
    val host: String,
)

/**
 * ★ 连通性自检的状态机。**四个状态必须互斥且各有一句话**（不存在“看起来没问题”这一档）。
 *
 * | 状态 | 说明 |
 * |---|---|
 * | [Idle] | 没测过。图标与文案都必须让人看出“还不知道”，而不是看不出 |
 * | [Probing] | 真的在发一个请求（§6.2：自检就是一次出境） |
 * | [Available] | 可用。**必须带上 host** —— 「可用」两个字不说明连到了哪儿 |
 * | [Failed] | 失败。**只回显归一化 host 与 `CloudFailure` 的文案** |
 * | [NotWired] | 能力还没接上。明说没接上，不用「能存进去」冒充「能连上」 |
 */
public sealed interface CloudProbeState {
    public data object Idle : CloudProbeState
    public data object Probing : CloudProbeState
    public data class Available(val host: String) : CloudProbeState
    public data class Failed(val failure: CloudFailure, val host: String) : CloudProbeState
    public data object NotWired : CloudProbeState
}

/** 云端后端那一块的可渲染状态。★ 同样不含任何秘密。 */
public data class CloudBackendUi(
    /** 端侧 / 云端。 */
    val choice: Locality = Locality.DEVICE,
    /**
     * ★ 由 [SecretDraft] 里的原文派生出来的**归一化 host**。
     *
     * ★★ 这里刻意没有 `baseUrl` 字段：那条 URL 的 query 里可能就是 key（§6.3 ③），
     *   放进 data class 会让 `toString()` 与每一个 `collect` 都看见它。
     *   界面只能用这个 host 说话 —— 它是唯一允许出现的端点信息。
     */
    val host: String = "",
    val modelName: String = "",
    val label: String = "",
    /** ★ 只记「填没填」，不记内容、不记长度 —— 长度是侧信道。 */
    val keyFilled: Boolean = false,

    val saved: List<CredentialSummary> = emptyList(),
    val probe: CloudProbeState = CloudProbeState.Idle,
    val message: String? = null,
) {
    /** 表单是否填全了（够不够发起一次自检 / 保存）。两格 Key 都只看有没有填。 */
    public val readyToProbe: Boolean get() = host.isNotBlank() && modelName.isNotBlank() && keyFilled
}

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
    /** ★ 凭证生命周期（`§6.3`）：它不提供 Key 的任何读取结果的展示位。 */
    private val creds: CloudCredentialPort,
    /** ★ 会话级目标（§3.3）：切换模型 = 给会话重选目标，会话内不可变。 */
    private val targets: DialogTargetStore,
    /** 给「还没选过后端」的会话补一次选择用。 */
    private val sessions: ChatSessionStorePort,
    /**
     * 连通性自检。★ `Optional`：实现要真发一次请求（§6.2），
     * 今天它在契约层还不存在，注入为空时界面明说，而不是假装通过。
     */
    probe: Optional<CloudEndpointProbe>,
) : ViewModel() {

    private val probe: CloudEndpointProbe? = probe.orElse(null)

    /**
     * ★★ 可能含秘密的输入值。**刻意不在 [SettingsUi] 里**。
     *
     *  · [urlDraft] —— BaseURL。它之所以算秘密，是因为自建网关常把 key 塞在 query 里。
     *  · [apiKey]   —— Key 本身。
     *
     * 它们一旦进了 `_ui`，就会被 `StateFlow` 的每一个 `collect`、每一次调试 dump、
     * 每一次进程还原（`SavedStateHandle` 要求可序列化）看见 ——
     * 而这三件事都不是「写日志」那种能被肉眼盯住的显式动作。
     */
    public val urlDraft: SecretDraft = SecretDraft.empty()
    public val apiKey: SecretDraft = SecretDraft.empty()

    public val local: StateFlow<LocalSettings> =
        settings.values().stateIn(viewModelScope, SharingStarted.Eagerly, LocalSettings())

    private val _ui = MutableStateFlow(SettingsUi())
    public val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init {
        // ★ 没有 `settings.refresh()` 了 —— 装载由 `SettingsStorePort.values()` 的
        //   `onStart` 自己保证，「调用方必须记得先 refresh」这类隐式契约已移除。
        refreshBudget()
        refreshUsage()
        refreshCloud()
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

        // ── 云端推理后端 ─────────────────────────────────────────────────

        /** 凭证 ref 前缀。`model@host` 做尾巴：同一份「这个模型走这个网关」落到同一个 ref。 */
        const val CREDENTIAL_PREFIX: String = "cloud:"

        /**
         * ★ 日志 tag 与前缀常量。定为常量是为了让单测**按这两个名字精确捞** ——
         *   断言「这条路径确实打了日志」比断言「全进程一行日志都没有」有力得多。
         */
        const val LOG_TAG: String = "AkeCloudSettings"
        const val SAVED_LOG_PREFIX: String = "云端凭证已保存 "

        const val MESSAGE_URL_NO_HOST: String =
            "这个地址读不出域名。请写成 example.com，或带协议写成 https://example.com。"
        const val MESSAGE_MODEL_EMPTY: String = "还没填模型名。"
        const val MESSAGE_KEY_EMPTY: String = "还没填 API Key。"
        const val OK_PREFIX: String = "已加密保存："
        const val MESSAGE_SAVE_FIRST: String = "选云端之前先保存一份服务：先填好 BaseURL / 模型名 / API Key 再点保存。"
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

    // ═══════════════════════════════════════════════════════════════════════
    // 云端推理后端（架构 08 §3 / §6）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 列出已保存的服务。★ 每条只有 `label` + **归一化 host**（§6.7）——
     * 没有 Key，也没有完整 URL。
     */
    public fun refreshCloud() {
        viewModelScope.launch {
            val list = ArrayList<CredentialSummary>()
            for (ref in creds.refs().first()) {
                val outcome = creds.configOf(ref)
                if (outcome !is Outcome.Ok) continue
                list += CredentialSummary(
                    ref = ref,
                    label = outcome.value.label,
                    modelName = outcome.value.modelName,
                    host = outcome.value.hostOf(),
                )
            }
            _ui.update { it.copy(cloud = it.cloud.copy(saved = list)) }
        }
    }

    public fun chooseBackend(locality: Locality) {
        _ui.update { it.copy(cloud = it.cloud.copy(choice = locality, probe = CloudProbeState.Idle)) }
    }

    /**
     * BaseURL 的输入。★ 原文留在 [urlDraft]，进状态的只有派生出来的归一化 host。
     *
     * 改了 URL / 模型名就把上一次自检作废 —— 「可用」这个结论只对那一份配置成立。
     */
    public fun onBaseUrlChange(v: String) {
        urlDraft.replace(v)
        _ui.update { it.copy(cloud = it.cloud.copy(host = draftHost(), probe = CloudProbeState.Idle)) }
    }

    public fun onModelNameChange(v: String): Unit =
        _ui.update { it.copy(cloud = it.cloud.copy(modelName = v, probe = CloudProbeState.Idle)) }

    public fun onLabelChange(v: String): Unit =
        _ui.update { it.copy(cloud = it.cloud.copy(label = v)) }

    /**
     * ★ Key 的输入。**只把「有没有填」写进状态，内容永远留在 [apiKey] 里**。
     *
     * 这里刻意**没有** `_ui.copy(cloud = cloud.copy(key = v))` ——
     * 一旦写了 `key` 那个字段，`AkeLog.d("$ui")` 这种没人审查的一行就能把它送出去，
     * 而且 `_ui` 是要进 `StateFlow` 的：任何一个 `collect` 都能看见它。
     */
    public fun onApiKeyChange(v: String) {
        apiKey.replace(v)
        _ui.update { it.copy(cloud = it.cloud.copy(keyFilled = !apiKey.isBlank)) }
    }

    /**
     * ★ [urlDraft] 的派生视图 —— **唯一**能进状态的那一格。
     * 走 `Hosts.normalise`（全仓唯一实现），不在这里重算一遍。
     */
    private fun draftHost(): String = normalizeHost(urlDraft.value)

    /**
     * 由草稿拼出 [EndpointConfig]。★ 这是**唯一**一处把用户输入的 URL 变成 `EndpointConfig`
     * 的地方；display 用的 host 也只从这里走 `hostOf()`（→ `Hosts.normalise`，全仓唯一实现）。
     *
     * @return `null` ⇒ 这个 URL 读不出 host（相对 URL / opaque URI 等）。
     */
    private fun draftConfig(): EndpointConfig? {
        val url = urlDraft.value.trim()
        val host = draftHost()
        // ★ 判不出 host 就拒。`normalizeHost` 已经挡住了「给相对 URL 补个协议，
        //   于是凭空造出一个 host」这种事 —— 那正是会让域名策略失效的那类 bug。
        if (host.isBlank()) return null
        return EndpointConfig(
            baseUrl = url,
            modelName = _ui.value.cloud.modelName.trim(),
            tlsMode = TlsMode.REQUIRED,
            declaredContextWindow = null,
            label = _ui.value.cloud.label.trim().ifEmpty { _ui.value.cloud.modelName.trim() },
        )
    }

    /**
     * ★ 保存凭证：走 [CloudCredentialPort] 一次加密写入，随后**立刻**丢掉内存里的明文。
     *
     * ## 为什么这条路径上故意有一条 log
     *
     * 「Key 不会进日志」这条性质要能被**证伪**，先得有人在这条路上真的打日志 ——
     * 一行都没有的话，任何负向断言都是永真的：它只是在断言「没有人在 log 而已」。
     * 这里记 ref、归一化 host、模型名，**就不记** Key 与完整 URL（§6.3 ③）。
     */
    public fun saveCloudCredential() {
        viewModelScope.launch {
            val cfg = draftConfig()
            if (cfg == null) {
                say(MESSAGE_URL_NO_HOST)
                return@launch
            }
            if (cfg.modelName.isEmpty()) {
                say(MESSAGE_MODEL_EMPTY)
                return@launch
            }
            if (apiKey.isBlank) {
                say(MESSAGE_KEY_EMPTY)
                return@launch
            }

            val ref = CredentialRef(CREDENTIAL_PREFIX + cfg.modelName + "@" + cfg.hostOf())
            val secret = apiKey.freeze()
            val outcome = creds.upsert(ref, cfg, secret)
            // ★★ 明文到此为止：upsert 成功与否都不再需要它留在内存里。
            apiKey.clear()
            _ui.update { it.copy(cloud = it.cloud.copy(keyFilled = false)) }

            when (outcome) {
                is Outcome.Ok -> {
                    AkeLog.i(LOG_TAG, SAVED_LOG_PREFIX + "ref=$ref host=${cfg.hostOf()} model=${cfg.modelName}")
                    val applied = applyToTargetlessSessions(ref)
                    refreshCloud()
                    say(
                        "${OK_PREFIX}${cfg.modelName}@${cfg.hostOf()}；" +
                            "$applied 个还没选过后端的会话已按你的选择（${localityName(_ui.value.cloud.choice)}）登记。"
                    )
                }

                is Outcome.Err -> say(outcome.error.code)
            }
        }
    }

    /**
     * ★ 把当前的选择落到**还没有登记过目标**的会话上。端侧这条路径就用它 ——
     *   它不需要凭证，也不该被「先填 Key」之类的校验挡住。
     */
    public fun applyBackendChoice() {
        viewModelScope.launch {
            val locality = _ui.value.cloud.choice
            if (locality == Locality.REMOTE && _ui.value.cloud.saved.isEmpty()) {
                say(MESSAGE_SAVE_FIRST)
                return@launch
            }
            val applied = applyToTargetlessSessions()
            say(
                "$applied 个还没选过后端的会话已按你的选择（${localityName(locality)}）登记。" +
                    "已经选过的一律不动 —— 会话内不可变（§3.3）。"
            )
        }
    }

    /**
     * ★ 把「后端选择」写到**还没有登记过目标**的会话上。
     *
     * ## 为什么只写这一类会话
     *
     * §3.3：目标是会话级的，且**会话内不可变** —— 中途改目标是设计外的用法。
     * 已经选过的会话一律不动：它们那些回答的承诺是当时那个后端给的，回头改写等于篡改历史。
     * 反过来，**没登记过目标**的会话会在路由时被 `Err(NO_TARGET)` 拒掉
     * （`DefaultModelRouter` 不猜），给它们补一次选择正好是用户此刻的意图。
     *
     * TODO(霍承钧): `:core:ports` 没有「新会话默认后端」这条 API ——
     *   此后新建的会话仍然没有目标。`ensureForNewTurn` 需要一个能同时带上
     *   `DialogTarget` 的入口（或一张 `default_dialog_target` 表），UI 这边补不上。
     *
     * @return 真正被写入的会话数。
     */
    public suspend fun applyToTargetlessSessions(justSaved: CredentialRef? = null): Int {
        val locality = _ui.value.cloud.choice
        val recent = sessions.sessions(null, includeArchived = false).first()
        var applied = 0

        for (summary in recent) {
            if (targets.of(summary.id) != null) continue
            val target: DialogTarget = when (locality) {
                Locality.DEVICE -> DialogTarget.Device
                Locality.REMOTE -> {
                    val ref = justSaved ?: _ui.value.cloud.saved.firstOrNull()?.ref ?: continue
                    DialogTarget.Remote(ref)
                }
            }
            targets.select(summary.id, target)
            applied++
        }

        AkeLog.i(LOG_TAG, "后端选择已落到 $applied 个未登记的会话 locality=$locality")
        return applied
    }

    public fun deleteCloudCredential(ref: CredentialRef) {
        viewModelScope.launch {
            creds.delete(ref)
            refreshCloud()
            _ui.update { it.copy(cloud = it.cloud.copy(probe = CloudProbeState.Idle)) }
        }
    }

    /**
     * ★ 连通性自检：真的发一次请求（§6.2），四个状态各自落定，没有中间态。
     *
     * 失败时界面只能拿到 [CloudFailure] —— 它**结构上**就没有 message / cause / body / url
     * 四个字段（§6.3），于是「把服务端原文（可能带 query 里的 key）带回界面」这件事
     * 在这里做不成，不是靠作者记得不去做。
     */
    public fun testConnection() {
        viewModelScope.launch {
            val cfg = draftConfig()
            if (cfg == null) {
                say(MESSAGE_URL_NO_HOST)
                return@launch
            }
            if (apiKey.isBlank) {
                say(MESSAGE_KEY_EMPTY)
                return@launch
            }

            _ui.update { it.copy(cloud = it.cloud.copy(probe = CloudProbeState.Probing, message = null)) }
            val activeProbe = probe
            val result = if (activeProbe == null) {
                CloudProbeResult.NotWired
            } else {
                val secret = apiKey.freeze()
                try {
                    activeProbe.probe(cfg, secret)
                } finally {
                    // ★ 自检一结束就把草稿里的明文丢掉，不等下一个用户动作。
                    apiKey.clear()
                    _ui.update { it.copy(cloud = it.cloud.copy(keyFilled = false)) }
                }
            }

            val host = cfg.hostOf()
            val state: CloudProbeState = when (result) {
                is CloudProbeResult.Ok -> CloudProbeState.Available(host)
                is CloudProbeResult.Failed -> CloudProbeState.Failed(result.failure, host)
                CloudProbeResult.NotWired -> CloudProbeState.NotWired
            }
            _ui.update { it.copy(cloud = it.cloud.copy(probe = state)) }
        }
    }

    public fun dismissCloudMessage(): Unit =
        _ui.update { it.copy(cloud = it.cloud.copy(message = null)) }

    private fun say(text: String): Unit = _ui.update { it.copy(cloud = it.cloud.copy(message = text)) }
}

/** `Locality` 的列举名 `DEVICE` / `REMOTE` 是给机器看的，界面要说人话。 */
private fun localityName(locality: Locality): String = when (locality) {
    Locality.DEVICE -> "端侧"
    Locality.REMOTE -> "云端"
}
