package com.ake.chat.loop

import com.ake.chat.fixtures.FakeClock
import com.ake.core.common.Outcome
import com.ake.core.ports.FocusHolder
import com.ake.core.ports.FocusLease
import com.ake.core.ports.FocusPriority
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 焦点仲裁的租约语义。
 *
 * 这一组测试是 F3 的**唯一机械保障**：一个 Engine 同时只允许一个 Conversation，
 * 而「谁现在持有」这件事只能靠租约版本号说清楚。下面每一条都对应一个真实会发生的场景。
 */
class InferenceFocusArbiterTest {

    private val clock = FakeClock()
    private val arbiter = InferenceFocusArbiter(clock)

    private fun acquire(
        holder: FocusHolder,
        priority: FocusPriority = FocusPriority.FOREGROUND_USER,
    ): Outcome<FocusLease> = runBlocking { arbiter.acquire(holder, priority) }

    private fun leaseOf(holder: FocusHolder, priority: FocusPriority = FocusPriority.FOREGROUND_USER) =
        (acquire(holder, priority) as Outcome.Ok).value

    @Test
    fun firstAcquireTakesTheFocus() {
        val lease = leaseOf(FocusHolder.Chat("s1", "a1"))
        assertThat(lease.isValid()).isTrue()
        assertThat(arbiter.current()).isEqualTo(FocusHolder.Chat("s1", "a1"))
    }

    @Test
    fun preemptionInvalidatesTheOldLeaseWithoutAnyCallback() {
        val a = leaseOf(FocusHolder.Chat("s1", "a1"))
        val b = leaseOf(FocusHolder.Chat("s2", "a1"))

        // ★ 这条是核心：A 不需要被通知，它下一次查 isValid() 就知道自己已经让出了
        assertThat(a.isValid()).isFalse()
        assertThat(b.isValid()).isTrue()
        assertThat(arbiter.current()).isEqualTo(FocusHolder.Chat("s2", "a1"))
    }

    @Test
    fun staleLeaseCloseDoesNotEvictTheNewOwner() {
        val a = leaseOf(FocusHolder.Chat("s1", "a1"))
        val b = leaseOf(FocusHolder.Chat("s2", "a1"))

        // A 在被抢占之后才跑到 finally（真实代码几乎总是这个顺序）
        a.close()

        assertThat(b.isValid()).isTrue()
        assertThat(arbiter.current()).isEqualTo(FocusHolder.Chat("s2", "a1"))
        assertThat(arbiter.snapshot()?.version).isNotNull()
    }

    @Test
    fun sameHolderReacquireKeepsTheFocusAndIsRefCounted() {
        val first = leaseOf(FocusHolder.Chat("s1", "a1"))
        val second = leaseOf(FocusHolder.Chat("s1", "a1"))

        assertThat(first.isValid()).isTrue()
        assertThat(second.isValid()).isTrue()
        assertThat(arbiter.snapshot()?.refs).isEqualTo(2)

        second.close()
        // 还有一个存活租约 ⇒ 焦点仍然在
        assertThat(first.isValid()).isTrue()
        first.close()
        assertThat(arbiter.current()).isNull()
    }

    @Test
    fun derivedTaskOfTheSameSessionSharesTheFocus() {
        // §2.5 表格第二行：前台会话触发浏览器任务 ⇒ **不切焦点**（同一条 Conversation）
        val chat = leaseOf(FocusHolder.Chat("s1", "a1"))
        val task = acquire(
            FocusHolder.Task(taskId = "task-1", parentSessionId = "s1"),
            FocusPriority.DERIVED_TASK,
        )

        assertThat(task).isInstanceOf(Outcome.Ok::class.java)
        assertThat(chat.isValid()).isTrue()
        assertThat((task as Outcome.Ok).value.isValid()).isTrue()
        // 焦点仍报给聊天会话（任务是它的派生，不是新主人）
        assertThat(arbiter.current()).isEqualTo(FocusHolder.Chat("s1", "a1"))
    }

    @Test
    fun releasingTheDerivedTaskKeepsTheChatFocus() {
        val chat = leaseOf(FocusHolder.Chat("s1", "a1"))
        val task = (acquire(
            FocusHolder.Task("task-1", "s1"),
            FocusPriority.DERIVED_TASK,
        ) as Outcome.Ok).value

        task.close()

        assertThat(chat.isValid()).isTrue()
        assertThat(arbiter.current()).isEqualTo(FocusHolder.Chat("s1", "a1"))
    }

    @Test
    fun backgroundCannotPreemptForeground() {
        leaseOf(FocusHolder.Chat("s1", "a1"), FocusPriority.FOREGROUND_USER)
        val r = acquire(FocusHolder.Task("bg-1", null), FocusPriority.BACKGROUND_HOUSEKEEPING)

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(InferenceFocusArbiter.CODE_FOCUS_BUSY)
        assertThat(arbiter.current()).isEqualTo(FocusHolder.Chat("s1", "a1"))
    }

    @Test
    fun derivedTaskCannotPreemptAnotherDerivedTask() {
        leaseOf(FocusHolder.Task("t1", "s1"), FocusPriority.DERIVED_TASK)
        val r = acquire(FocusHolder.Task("t2", "s2"), FocusPriority.DERIVED_TASK)

        // 互相顶掉会让两个任务都半途而废 —— 拒绝是刻意的
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(InferenceFocusArbiter.CODE_FOCUS_BUSY)
    }

    @Test
    fun derivedTaskCanPreemptBackgroundHousekeeping() {
        leaseOf(FocusHolder.Task("bg", null), FocusPriority.BACKGROUND_HOUSEKEEPING)
        val r = acquire(FocusHolder.Task("t1", "s1"), FocusPriority.DERIVED_TASK)
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun foregroundAlwaysWins() {
        leaseOf(FocusHolder.Task("bg", null), FocusPriority.BACKGROUND_HOUSEKEEPING)
        val first = acquire(FocusHolder.Chat("s1", "a1"), FocusPriority.FOREGROUND_USER)
        assertThat(first).isInstanceOf(Outcome.Ok::class.java)

        // 用户在另一个会话里再次输入 ⇒ 后到者赢（旧的一方的租约立刻失效）
        val second = acquire(FocusHolder.Chat("s2", "a1"), FocusPriority.FOREGROUND_USER)
        assertThat(second).isInstanceOf(Outcome.Ok::class.java)
        assertThat((first as Outcome.Ok).value.isValid()).isFalse()
    }

    @Test
    fun closeIsIdempotent() {
        val lease = leaseOf(FocusHolder.Chat("s1", "a1"))
        lease.close()
        lease.close()
        assertThat(arbiter.current()).isNull()
    }

    @Test
    fun releasedFocusCanBeTakenAgainWithANewVersion() {
        val first = leaseOf(FocusHolder.Chat("s1", "a1"))
        val v1 = arbiter.snapshot()?.version
        first.close()

        val second = leaseOf(FocusHolder.Chat("s1", "a1"))
        val v2 = arbiter.snapshot()?.version

        assertThat(second.isValid()).isTrue()
        assertThat(v2).isGreaterThan(v1)
    }
}
