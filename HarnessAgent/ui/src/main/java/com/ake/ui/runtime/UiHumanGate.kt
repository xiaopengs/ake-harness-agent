package com.ake.ui.runtime

import com.ake.agent.guard.SensitiveActionPolicy
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolId
import com.ake.core.ports.HumanGate
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.Resolved
import com.ake.core.ports.ToolPermissionStorePort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `HumanGate` 的**唯一真实实现就属于 UI 层** —— 因为「人」就在 UI 这一侧。
 *
 * 这不是图省事：`AgentLoop` 在 `resolve()` 上挂起等待，而能回答这个问题的只有屏幕上那个弹窗。
 * 把它放在 `:app` 或 `:data` 反而要再绕一层回调回来。
 *
 * ## 挂起的代价
 *
 * `resolve()` 挂起期间，**整个主循环停在这一行**，推理线程也停着。
 * 所以：
 *  - 必须有超时（否则一次「用户把手机放下了」会让任务永久卡死，且看不出是卡死还是在跑）；
 *  - 超时后按 **拒绝** 处理而不是放行 —— 失败方向必须偏向「少做」而不是「多做」。
 *
 * ## 三个放行粒度
 *
 * | 选项 | 语义 | 落点 |
 * |---|---|---|
 * | 允许 | 只这一次 | 一次性 |
 * | 本次任务始终允许 | 同一个 reasonKey 在本任务内不再打扰 | 本进程内存，任务结束即失效 |
 * | 始终允许（记住） | 持久化授权，**可按条撤销** | `ToolPermissionStorePort.grantAlwaysAllow`（Room，非 DataStore） |
 *
 * 只给「本次」和「永久」两档是不够的：永久授权会在换了个任务、换了个域名之后继续生效，
 * 而「本次任务」这一档既能消掉重复打扰，又不会跨任务膨胀权限。
 */
@Singleton
public class UiHumanGate @Inject constructor(
    private val permissions: ToolPermissionStorePort,
) : HumanGate {

    /** 一次挂起请求。deferred 的完成即 Loop 的恢复。 */
    public data class PendingGate(
        val verdict: PolicyVerdict.RequiresConfirm,
        val action: StepAction,
        /** 由 UI 的裁决结果补全；超时则补 `Reject`。 */
        val deferred: CompletableDeferred<Resolved>,
        /** 风险等级由护栏给，UI 不自己判断（架构 §7.1）。 */
        val riskClass: String,
    )

    private val _pending = MutableStateFlow<PendingGate?>(null)
    public val pending: StateFlow<PendingGate?> = _pending.asStateFlow()

    /** 本次任务内的放行集合。任务开始时清空。 */
    private val sessionGrants = HashSet<String>()

    override suspend fun resolve(verdict: PolicyVerdict, action: StepAction): Resolved {
        if (verdict !is PolicyVerdict.RequiresConfirm) return Resolved.Proceed

        // 「本次任务始终允许」命中：不再打扰，但不改任何持久化状态
        if (verdict.reasonKey in sessionGrants) return Resolved.Proceed

        val deferred = CompletableDeferred<Resolved>()
        _pending.value = PendingGate(
            verdict = verdict,
            action = action,
            deferred = deferred,
            riskClass = SensitiveActionPolicy.classify(action).name,
        )

        return try {
            withTimeoutOrNull(GATE_TIMEOUT_MS) { deferred.await() }
                ?: Resolved.Reject("err.gate_timeout")
        } finally {
            _pending.value = null
        }
    }

    // ──────────────────────────────────────────── UI 裁决入口

    /** 只放行这一次。 */
    public fun allowOnce(): Unit = complete(Resolved.Proceed)

    /** 本任务内不再问同一类问题。 */
    public fun allowForThisTask(): Unit {
        _pending.value?.verdict?.reasonKey?.let { sessionGrants.add(it) }
        complete(Resolved.Proceed)
    }

    /**
     * 持久化放行（只对 MCP 工具可用 —— 只有它有稳定的 `ToolId` 可撤销）。
     *
     * @return false 表示这一步不是工具调用，UI 不应展示这个选项。
     */
    public suspend fun allowAlways(): Boolean {
        val pending = _pending.value ?: return false
        val action = pending.action
        if (action !is StepAction.CallTool) return false
        permissions.grantAlwaysAllow(action.toolId)
        complete(Resolved.Proceed)
        return true
    }

    /** 拒绝这一步，让模型换条路走（不是中止任务）。 */
    public fun reject(): Unit = complete(Resolved.Reject("err.user_rejected"))

    /** 中止整个任务。`Resolved.Abort` 必须无条件可用 —— 用户按停止时不该先问一圈。 */
    public fun abort(): Unit = complete(Resolved.Abort)

    /**
     * 修正后执行：把「输入并回车提交」降级成「只输入、不提交」。
     *
     * 这是 [Resolved.Amend] 在 MVP 里唯一有意义的用法 —— 提交是绝大多数网页上
     * 不可逆的那一步，而输入本身是可回退的。
     */
    public fun amendDropSubmit(): Unit {
        val action = _pending.value?.action
        val amended = if (action is StepAction.Type && action.submit) {
            action.copy(submit = false)
        } else {
            action
        }
        complete(if (amended != null) Resolved.Amend(amended) else Resolved.Reject("err.user_rejected"))
    }

    /** 任务重启时清空「本次任务」放行 —— 放行范围绝不能跨任务存活。 */
    public fun resetTaskGrants(): Unit = sessionGrants.clear()

    public fun isGrantedInSession(reasonKey: String): Boolean = reasonKey in sessionGrants

    /** 冷启动 / 任务切换时把门关上，避免上一轮的挂起请求残留。 */
    public fun cancelPending(): Unit {
        _pending.value?.let { if (!it.deferred.isCompleted) it.deferred.complete(Resolved.Reject("err.user_rejected")) }
        _pending.value = null
    }

    private fun complete(r: Resolved) {
        val p = _pending.value ?: return
        if (!p.deferred.isCompleted) p.deferred.complete(r)
        _pending.value = null
    }

    public companion object {
        /**
         * 等待裁决的上限。**挂起期间推理线程是停着的**，
         * 所以这个值不能太长（用户放下手机＝整机推理空转），也不能太短（读不完一段确认文案）。
         */
        public const val GATE_TIMEOUT_MS: Long = 120_000L

        /** 从 MCP 调用的 confirm 参数里反解 ToolId（用于展示「始终允许」）。 */
        public fun toolIdOf(args: List<String>): ToolId? =
            args.firstOrNull()?.takeIf { it.isNotBlank() }?.let { ToolId(it) }
    }
}
