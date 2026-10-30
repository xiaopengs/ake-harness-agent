package com.ake.ui.model

import com.ake.core.common.Outcome
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetStore
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.Locality
import com.ake.core.ports.ModelRouter
import com.ake.ui.spi.TurnEgressNotice
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

// ===========================================================================
// 模型徽标 —— 「不可见的路由承诺等于没有承诺」在界面上的那一半（架构 08 §3.1 / §5）
//
// ## ★ 三条可能的来源，优先级就是它们的可靠程度排序
//
//   ① 本轮的实际产出（回落告知）——  最可靠：它是已经发生的事实；
//   ② 路由器的运行时答复（ModelRouter.dialogFor）——  本次会话此刻会被派到哪里；
//   ③ 会话登记的目标（DialogTargetStore）——  用户当初**想**选什么。
//
// ## ★★ 为什么「运行时」必须压过「当初的意图」
//
// `DialogTarget.Remote` 只说明用户勾了云端，它**不承诺**这条回答真是云端写的：
// 凭证被删了会回落、会话带上页面上下文会回落（锁 C）、网络不通会回落。
// 若徽标直接读 `DialogTarget`，界面就会在一条本机产出的回答上贴「云端」——
// 那句承诺是假的，而用户没有任何办法发现它是假的。
// 这正是 §4.2「禁止静默回落」在 UI 侧要防的同一件事，只是症状不同。
//
// ## DOM 红线在这里的边界
//
// 「浏览器任务必须走端侧」是 `:core` 四道锁的事（§5），**UI 不去判动静**：
// 本文件没有一处 `if (涉及网页) DEVICE`。运行时说 DEVICE 就画 DEVICE —— 判定只有一份。
// ===========================================================================

/**
 * 一条回答的产出来源。
 *
 * ★ 刻意带 [Unregistered] / [Unavailable] 两个「不知道」的分支：
 * 空状态与失败态**也占一个分支**，不给实现留「假装知道」的余地。
 */
public sealed interface ModelBadge {

    /** 会话还没有登记过后端。既不能说端侧，也不能说云端。 */
    public data object Unregistered : ModelBadge

    /** 本机产出。 */
    public data class Device(val label: String) : ModelBadge

    /**
     * 云端产出。
     *
     * @param host ★ 只能是 `EndpointConfig.hostOf()`（`Hosts.normalise` 的结果）。
     *             完整 URL 不进这个对象 —— 它在字段层面就不能携带秘密。
     */
    public data class Remote(val modelName: String, val host: String) : ModelBadge

    /** 登记过目标，但此刻确定不了路由结果（如路由报错 / 凭证读不出来）。 */
    public data class Unavailable(val code: String) : ModelBadge
}

/**
 * 徽标来源。
 *
 * ## 为什么 [router] 是 `Optional` 而另外两个不是
 *
 * `CloudCredentialPort` 与 `DialogTargetStore` 已由 `:data` 的 `PersistenceModule`
 * 绑定（可直接注入）。`ModelRouter` 的实现在 `:inference`，绑定由组合根装 ——
 * 沿用 `ChatWiringModule` 对 `ChatRunnerPort` 的同一套做法（`@BindsOptionalOf`）：
 * 图里没有就给 `empty()`，界面降级而不是把整个 App 的编译打断。
 *
 * ## ★★ 三级降级必须依次往下，且每一级都比下一级诚实
 *
 * 路由器不在图里时退到「登记的意图」。那一刻它仍是用户自己登记的事实，
 * 但它**不是**这条消息的产出证据 —— 见类注释第二段。
 * 这是今日接线不全时的临时态，`ModelRouter` 一旦被绑上，第 ② 级自动接管。
 *
 * ## ★ 这里一处都不 catch 异常
 *
 * 路由读不出来就必须是 [ModelBadge.Unavailable]。吞掉异常 fallback 成 DEVICE，
 * 等于把一次未知说成一次确定的「留在本机」—— 那是比报错更糟的答案。
 */
@Singleton
public class ModelBadgeSource @Inject constructor(
    maybeRouter: Optional<ModelRouter>,
    private val targets: DialogTargetStore,
    private val creds: CloudCredentialPort,
) {

    private val router: ModelRouter? = maybeRouter.orElse(null)

    /**
     * @param notice 本轮若发生过回落必须传进来；它压过一切推论。
     */
    public suspend fun badgeFor(sessionId: String, notice: TurnEgressNotice? = null): ModelBadge {
        // ① 已经发生的事实：回落过的那一轮必然是本机产出。
        //    （只有 PRE 允许回落，而 PRE 的定义就是「一个字节都没出去」。）
        if (notice != null && notice.producedBy == Locality.DEVICE) {
            return ModelBadge.Device(DEVICE_LABEL)
        }

        // ② 路由器的运行时答复 —— 每次问，不缓存：**凭证被删掉之后答案会变**。
        val localRouter = router
        if (localRouter != null) {
            return when (val outcome = localRouter.dialogFor(sessionId)) {
                is Outcome.Ok -> {
                    if (outcome.value.capabilities.locality == Locality.REMOTE) {
                        remoteBadge(fromTargetRef(sessionId))
                    } else {
                        ModelBadge.Device(DEVICE_LABEL)
                    }
                }

                is Outcome.Err -> ModelBadge.Unavailable(outcome.error.code)
            }
        }

        // ③ 路由器还没接进来：退到用户当初登记的意图。
        return badgeFromRegisteredTarget(targets.of(sessionId))
    }

    /**
     * 由「登记的目标」推徽标。★ [DialogTarget.Unrecognized] 不被任何一句漂亮的默认值吃掉：
     * 库里那行文本认不出来就是认不出来（`Dialog.kt` 的注释已经裁定不许猜成 Device）。
     */
    private suspend fun badgeFromRegisteredTarget(target: DialogTarget?): ModelBadge = when (target) {
        null -> ModelBadge.Unregistered
        DialogTarget.Device -> ModelBadge.Device(DEVICE_LABEL)
        is DialogTarget.Remote -> remoteBadge(target.credentialRef)
        // ★ 不把 `raw` 渲染出来：那是库里的脏文本，界面没有理由替它做扩音器。
        is DialogTarget.Unrecognized -> ModelBadge.Unavailable(UNRECOGNIZED_TARGET)
    }

    private suspend fun fromTargetRef(sessionId: String): CredentialRef? =
        (targets.of(sessionId) as? DialogTarget.Remote)?.credentialRef

    /** ★ 读「配了什么模型 / 指向哪个 host」的唯一入口。读不到 ⇒ Unavailable，不猜。 */
    private suspend fun remoteBadge(ref: CredentialRef?): ModelBadge {
        if (ref == null) return ModelBadge.Unavailable(NO_CREDENTIAL_CONFIG)
        val cfg: EndpointConfig = when (val outcome = creds.configOf(ref)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return ModelBadge.Unavailable(outcome.error.code)
        }
        // ★ 只取归一化的 host，绝不取 cfg.baseUrl —— 见 [ModelBadge.Remote] 的注释。
        return ModelBadge.Remote(modelName = cfg.modelName, host = cfg.hostOf())
    }

    public companion object {
        /** 目标指向某个凭证，但那份凭证读不出来时的机器码。 */
        public const val NO_CREDENTIAL_CONFIG: String = "UI_NO_CREDENTIAL_CONFIG"

        /** 库里的目标文本认不出来（`DialogTarget.Unrecognized`）。不猜，交给 [ModelBadge.Unavailable]。 */
        public const val UNRECOGNIZED_TARGET: String = "UI_TARGET_UNRECOGNIZED"

        /** 端侧标签。`ModelCapabilities` 里没有「本机型号」这个字段，所以不编一个出来。 */
        public const val DEVICE_LABEL: String = "设备内推理"
    }
}
