package com.ake.agent.planner

import com.ake.core.common.Outcome
import com.ake.core.ports.FocusHolder
import com.ake.core.ports.FocusLease
import com.ake.core.ports.FocusPriority
import com.ake.core.ports.InferenceFocus

/**
 * 测试用焦点仲裁器：可控结局 + 可观测的获取/释放次数。
 *
 * `busy = true` 时模拟「聊天正在占用引擎」——计划侧必须立刻失败，
 * 而不是悄悄绕过（绕过就是 F3）。
 */
internal class FakeFocus(
    var busy: Boolean = false,
    /** 置 true 后，所有已签发租约在下一次 `isValid()` 时变 false（模拟被前台抢占）。 */
    var revokeAll: Boolean = false,
) : InferenceFocus {

    val acquired: MutableList<FocusHolder> = ArrayList()
    val priorities: MutableList<FocusPriority> = ArrayList()
    var released: Int = 0
        private set

    private val issued: MutableList<Lease> = ArrayList()

    override suspend fun acquire(holder: FocusHolder, priority: FocusPriority): Outcome<FocusLease> {
        acquired += holder
        priorities += priority
        if (busy) {
            return Outcome.Err(
                com.ake.core.common.AppError(
                    code = "FOCUS_BUSY",
                    reasonKey = "err_focus_busy",
                ),
            )
        }
        val lease = Lease()
        issued += lease
        return Outcome.Ok(lease)
    }

    override fun current(): FocusHolder? = if (busy) FocusHolder.Chat("other", "other") else null

    private inner class Lease : FocusLease {
        private var closed = false

        override fun isValid(): Boolean = !closed && !revokeAll

        override fun close() {
            if (closed) return
            closed = true
            released++
        }
    }
}
