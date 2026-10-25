package com.ake.chat.loop

import com.ake.core.common.AppError
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.ports.FocusHolder
import com.ake.core.ports.FocusLease
import com.ake.core.ports.FocusPriority
import com.ake.core.ports.InferenceFocus

/**
 * # 单次推理焦点仲裁器（§2.5 硬约束 / M4）
 *
 * **契约一句话：同一时刻整机只有一条活跃的 `ModelSession`（一个 LiteRT-LM Conversation）。**
 * 这不是性能优化，是防止 native 抛 `FAILED_PRECONDITION` 的**正确性组件**。
 *
 * ## 租约是怎么真的生效的（不是文档复述）
 *
 * 整个类只靠三个字段：
 *
 * ```
 *   owner: Owner?        // holder + 优先级 + version + refs
 *   ```
 *
 * 每次**易主**都把 `version` 加一。租约对象里只存它被签发时的那个 `version`，
 * 于是 `isValid()` 就是一次纯比较：
 *
 * ```
 *   isValid() = owner != null && owner.version == myVersion
 * ```
 *
 * 由此得到三条性质，它们是这个类存在的全部价值：
 *
 *  ① **抢占立刻生效，且不需要通知对方**。抢占 = `version++`。被抢占的一方下一次查
 *     `isValid()` 就得到 false，它自己停下 —— 而不是「被回调打断」。为什么不用回调：
 *     回调可能落在对方正在跑的 native 调用中间，那时候让它「优雅退出」的窗口并不存在。
 *
 *  ② **旧租约的 `close()` 不会拆掉新持有者**。`close()` 先比 version，不等就直接返回。
 *     这是这类代码最经典的 bug：A 被抢 → B 拿到 → A 的 finally 里 close() 把 owner 清空 →
 *     两方都以为自己是持有者，然后 native 崩在第二个 openSession 上。测试
 *     `staleLeaseCloseDoesNotEvictNewOwner` 专门卡这一条。
 *
 *  ③ **同会话的派生任务不换焦点**（§2.5 表格第二行）。`Chat(S)` 持有时来一个
 *     `Task(parentSessionId = S)`，它拿到的是一个**共享同一 version** 的租约（refs+1）——
 *     因为任务和聊天用的是同一条 Conversation，切焦点只会白丢 KV cache。
 *     释放时 refs 减一，只有减到 0 才真正放开。
 *
 * ## 优先级表（可抢占性）
 *
 * | 新来的 ↓ / 当前 → | FOREGROUND_USER | DERIVED_TASK | BACKGROUND |
 * |---|---|---|---|
 * | FOREGROUND_USER | ✅ 抢 | ✅ 抢 | ✅ 抢 |
 * | DERIVED_TASK | ❌ 拒（FOCUS_BUSY） | ❌ 拒 | ✅ 抢 |
 * | BACKGROUND | ❌ 拒 | ❌ 拒 | ❌ 拒 |
 *
 * 三条拒绝都有现实的对应场景：后台记账不该把用户正在看的回答打断；
 * 两个派生任务同时抢意味着「一个请求衍生出十分钟默默执行」，那是 Q7 明确要防的
 * （`DERIVED_TASK vs DERIVED_TASK` 拒绝而不是互相顶掉 —— 顶掉会让两个任务都半途而废）。
 *
 * ## 线程安全
 *
 * 全部状态在一个 `synchronized` 块里改。**刻意不用 `Mutex`**：`acquire` 是 `suspend` 只是
 * 因为契约这么写，它内部没有任何挂起点 —— 用挂起锁会让「查一下焦点在谁手上」变成
 * 可能被取消的操作，而焦点状态恰恰是取消风暴里最需要保持可读的东西。
 */
public class InferenceFocusArbiter(
    private val clock: Clock,
) : InferenceFocus {

    private val lock = Any()

    private var owner: Owner? = null

    /**
     * 租约代次。**只增不减**，且与 [owner] 的生死无关。
     *
     * 为什么不能拿 `owner?.version ?: 0` 推下一条：owner 被 release 清空之后，
     * 下一个持有者会拿到**和上一代相同**的版本号，于是一个「早就被抢占但忘了 close」的
     * 旧租约会在这一刻神奇地复活（`isValid()` 只比版本号）。这个 bug 的表现是
     * 「两个会话同时以为自己拥有引擎」，而那正是 F3 要防的事。
     */
    private var generation: Long = 0L

    override suspend fun acquire(holder: FocusHolder, priority: FocusPriority): Outcome<FocusLease> {
        synchronized(lock) {
            val cur = owner

            if (cur == null) {
                return Outcome.Ok(install(holder, priority))
            }

            // ① 同一持有者续租：refs+1，version 不变（续租不是易主）
            if (cur.holder == holder) {
                owner = cur.copy(refs = cur.refs + 1)
                return Outcome.Ok(Lease(cur.version))
            }

            // ② 同会话派生：Task(parentSessionId = Chat.sessionId) 共享同一 version
            val parent = (cur.holder as? FocusHolder.Chat)?.sessionId
            val derived = (holder as? FocusHolder.Task)?.parentSessionId
            if (parent != null && derived != null && parent == derived) {
                owner = cur.copy(refs = cur.refs + 1)
                AkeLog.i(TAG, "focus shared: task ${holder.taskIdOrNull()} 沿用会话 $parent（不换焦点、不丢 KV cache）")
                return Outcome.Ok(Lease(cur.version))
            }

            // ③ 抢占
            if (!canPreempt(priority, cur.priority)) {
                return Outcome.Err(
                    AppError(
                        code = CODE_FOCUS_BUSY,
                        reasonKey = "err_focus_busy",
                        severity = Severity.LOW,
                    ),
                )
            }
            AkeLog.i(
                TAG,
                "focus preempt: ${cur.holder.describe()} (${cur.priority}) → ${holder.describe()} ($priority), " +
                    "version ${cur.version} → ${cur.version + 1}",
            )
            return Outcome.Ok(install(holder, priority))
        }
    }

    override fun current(): FocusHolder? = synchronized(lock) { owner?.holder }

    /** 诊断用：当前持有者 + 优先级 + 存活租约数。只在日志/测试里用。 */
    public fun snapshot(): FocusSnapshot? = synchronized(lock) {
        owner?.let { FocusSnapshot(it.holder, it.priority, it.refs, it.version, clock.nowMillis()) }
    }

    private fun install(holder: FocusHolder, priority: FocusPriority): FocusLease {
        generation += 1
        owner = Owner(holder, priority, generation, refs = 1)
        return Lease(generation)
    }

    private fun canPreempt(incoming: FocusPriority, current: FocusPriority): Boolean = when (incoming) {
        FocusPriority.FOREGROUND_USER -> true
        FocusPriority.DERIVED_TASK -> current == FocusPriority.BACKGROUND_HOUSEKEEPING
        FocusPriority.BACKGROUND_HOUSEKEEPING -> false
    }

    private fun release(version: Long) {
        synchronized(lock) {
            val cur = owner ?: return
            // ★ ②：旧租约的 close() 不得影响新持有者
            if (cur.version != version) {
                AkeLog.d(TAG, "release ignored: stale lease v$version (current v${cur.version})")
                return
            }
            if (cur.refs > 1) {
                owner = cur.copy(refs = cur.refs - 1)
                return
            }
            owner = null
            AkeLog.i(TAG, "focus released: ${cur.holder.describe()} v$version")
        }
    }

    /** 一个租约对象。**刻意轻**：它只认 version，不认「我是谁」。 */
    private inner class Lease(private val version: Long) : FocusLease {

        @Volatile
        private var closed = false

        override fun isValid(): Boolean = synchronized(lock) {
            if (closed) return false
            val cur = owner ?: return false
            cur.version == version
        }

        override fun close() {
            if (closed) return
            closed = true
            release(version)
        }

        override fun toString(): String = "FocusLease(v=$version, closed=$closed)"
    }

    private data class Owner(
        val holder: FocusHolder,
        val priority: FocusPriority,
        val version: Long,
        val refs: Int,
    )

    public data class FocusSnapshot(
        val holder: FocusHolder,
        val priority: FocusPriority,
        val refs: Int,
        val version: Long,
        val atMillis: Long = -1L,
    )

    public companion object {
        public const val TAG: String = "InferenceFocus"

        /** 焦点被更强或同级的持有者占着。**可读错误**，不是 native 的 `FAILED_PRECONDITION`。 */
        public const val CODE_FOCUS_BUSY: String = "FOCUS_BUSY"
    }
}

private fun FocusHolder.taskIdOrNull(): String? = (this as? FocusHolder.Task)?.taskId

internal fun FocusHolder.describe(): String = when (this) {
    is FocusHolder.Chat -> "Chat($sessionId/$agentId)"
    is FocusHolder.Task -> "Task($taskId, parent=${parentSessionId ?: "-"})"
}
