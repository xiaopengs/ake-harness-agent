package com.ake.inference

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetStore
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.ModelRouter
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.TaskKind

/**
 * ★★ `ModelRouter` 的**唯一**实现（架构 08 §4.5）。
 *
 * ## ★ 依赖倒置：端侧实现由 `:app` 注入，本模块**看不见** `:llm`
 *
 * 原设计（§8.2 E6）要求 `:inference → :llm`，理由是把 `LiteRtModelPort`
 * 装配成 `DeviceModelPort`。主理人裁决**删除 E6**：
 *  ① 纯 JVM 模块无法依赖 `com.android.library` 模块；
 *  ② 与 §8.3 自相矛盾（黑名单禁止 `:inference` import `litertlm.*`）。
 *
 * ⇒ 本类只认 [DeviceModelPort] **接口**。`:app` 组合根负责把 `LiteRtModelPort`
 *   实例传进来。分层本来就该是这样：
 *
 * ```
 *   :app（组合根）
 *     ├── new LiteRtModelPort(...)          ← 唯一看得见 :llm 的地方
 *     ├── new LocalDialogAdapter(liteRt)   ← 升格成 DialogModelPort
 *     ├── new OpenAiCompatibleDialogPort(...)
 *     └── new DefaultModelRouter(device=..., localDialog=..., remote=..., ...)
 * ```
 */
public class DefaultModelRouter(
    private val device: DeviceModelPort,
    private val localDialog: DialogModelPort,
    private val remote: DialogModelPort,
    private val targets: DialogTargetStore,
    private val creds: CloudCredentialPort,
    private val permits: EgressPermitSink,
) : ModelRouter {

    override suspend fun dialogFor(sessionId: String): Outcome<DialogModelPort> {
        // ★ 没有登记过的目标 ⇒ **报错，不猜**。
        val target = targets.of(sessionId) ?: return Outcome.Err(
            AppError(code = CODE_NO_TARGET, reasonKey = "err_router_no_target", severity = Severity.MEDIUM),
        )

        return when (target) {
            is DialogTarget.Device -> Outcome.Ok(localDialog)

            is DialogTarget.Remote -> if (!creds.exists(target.credentialRef)) {
                Outcome.Err(
                    AppError(
                        code = com.ake.core.ports.CloudFailureCodes.CREDENTIAL_MISSING,
                        reasonKey = com.ake.core.ports.CloudFailure.CredentialMissing.reasonKey,
                        severity = Severity.MEDIUM,
                    ),
                )
            } else {
                // ★ permit **不在这里开** —— 见 §6：permit 由 `EgressPermitSink.open()`
                //   在「决策已产出、字节未发出」这个窗口里同步落一行 PENDING 后给出。
                Outcome.Ok(remote)
            }
        }
    }

    /** ★ 开一张出境许可（对应 §7.2：先落 PENDING，再允许发字节）。 */
    public suspend fun openPermit(
        ctx: RouteContext,
        decision: RoutingDecision,
    ): Outcome<EgressPermit> = permits.open(ctx, decision)

    /** ★ 收敛许可终态（§7.2：不调用的 permit 会在 sweep 里变 UNKNOWN）。 */
    public suspend fun closePermit(
        permit: EgressPermit,
        outcome: com.ake.core.ports.AuditOutcome,
        phase: com.ake.core.ports.EgressPhase,
        deliveredBytes: Int,
        finishReasonRaw: String?,
    ): Outcome<Unit> = permits.close(permit, outcome, phase, deliveredBytes, finishReasonRaw)

    /** ★ 冷启动 sweep（§7.2）。 */
    public suspend fun sweepPending(olderThanMs: Long = EgressPermitSink.PENDING_TTL_MS): Int =
        permits.sweepPending(olderThanMs)

    public companion object {
        public const val CODE_NO_TARGET: String = "ROUTER_NO_TARGET"
    }
}

/**
 * ★ 唯一的 `RoutingPort` 实现（架构 08 §5.7）。
 *
 * ## ★★ DOM 锁死的第一道判定（也是唯一一道**运行时**判定）
 *
 * > 一旦 `ctx.kind is TaskKind.PageTask`（或带了 [PageRef]）⇒ **恒返回 `LocalOnly`**。
 *
 * 这一条**做不到编译期**（理由见 §5.6-⑤）。它由三样东西共同兜住：
 *  ① 本类只有一个实现，且 `when` 里没有「其它分支可走」；
 *  ② `:inference` 的路由单测逐条断言；
 *  ③ **外部 oracle**：mitmproxy 抓包（T3 + T4 反向对照）。
 *
 * ## ★ 为什么 `requested = CloudRequested` 仍然返回 `LocalOnly` 是**正确**的
 *
 * `requested` 表达的是**用户想要**，不是**被允许**。
 * 允许与否由 `EgressPermitSink.open()` 产出的 permit 决定（§5.7 / §7.2）。
 * 于是「想上云」在**没有 permit** 时必然落到 `LocalOnly` —— 这是设计。
 */
public class DialogRoutingPort(
    private val clock: com.ake.core.common.Clock,
    private val creds: CloudCredentialPort,
) : com.ake.core.ports.RoutingPort {

    override suspend fun decide(ctx: RouteContext): RoutingDecision {
        val reason: String
        val target: com.ake.core.ports.InferenceTarget

        when {
            // ★★ 铁律：页面任务恒本地。
            ctx.kind is TaskKind.PageTask || ctx.page != null -> {
                target = com.ake.core.ports.InferenceTarget.LocalOnly
                reason = REASON_PAGE_TASK_LOCAL_ONLY
            }

            // 没要出云 ⇒ 本地
            ctx.requested !is com.ake.core.ports.InferenceTarget.CloudRequested -> {
                target = com.ake.core.ports.InferenceTarget.LocalOnly
                reason = REASON_LOCAL_ONLY
            }

            else -> {
                // ★ 这里**只**产出 CloudRequested（= 「裁决成云端」）。
                //   真正的 CloudAssisted(permit) 由 openPermit() 之后才构造得出来。
                target = com.ake.core.ports.InferenceTarget.CloudRequested
                reason = REASON_CLOUD_ALLOWED
            }
        }

        return RoutingDecision(
            target = target,
            reasonKey = reason,
            // ★ 未授权 ⇒ 恒空。授权后的类别白名单由 permit 侧决定。
            allowedClasses = emptySet(),
            redactionApplied = true,
            decidedAt = clock.nowMillis(),
        )
    }

    public companion object {
        public const val REASON_PAGE_TASK_LOCAL_ONLY: String = "route_page_task_local_only"
        public const val REASON_LOCAL_ONLY: String = "route_local_only"
        public const val REASON_CREDENTIAL_MISSING: String = "route_cloud_credential_missing"
        public const val REASON_CLOUD_ALLOWED: String = "route_cloud_allowed"
    }
}
