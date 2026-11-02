package com.ake.data.persist.store

import com.ake.core.common.TaskId
import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind
import com.ake.core.contract.PlanStatus
import com.ake.data.persist.db.DbTransaction
import com.ake.data.persist.db.dao.PlanDao
import com.ake.data.persist.db.entity.PlanRevisionEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `PlanStoreImpl` 的 CAS 与版本分配。
 *
 * ## 为什么这块必须测
 *
 * 「确认」是一次 CAS。它失败时的表现不是崩溃，而是**两次执行同一条任务** ——
 * 端侧只有一个推理焦点，第二次会卡在租约上，用户看到的是"点了开始但一直转圈"。
 * 而这类 bug 在真机上极难复现（要在两个界面同时点）。
 *
 * 用假 DAO 而不是真 Room：要断言的是**调用顺序与状态迁移**，
 * 不是 SQL 本身。SQL 的正确性由 `AkeMigrationSchemaTest` 与真机迁移证明。
 */
class PlanStoreImplTest {

    private val taskId = TaskId("01HZZZZZZZZZZZZZZZZZZZZZZZ")
    private val steps = listOf(
        PlanStep(0, PlanStepKind.READ_FILE, "文件"),
        PlanStep(1, PlanStepKind.SUMMARIZE, "正文"),
    )

    @Test
    fun saveDraftAllocatesIncreasingRevisions() = runBlocking {
        val store = store()
        val first = store.saveDraft(taskId, "目标", steps)
        val second = store.saveDraft(taskId, "目标", steps)
        assertThat(first.revision).isEqualTo(1)
        assertThat(second.revision).isEqualTo(2)
        assertThat(store.latest(taskId)?.revision).isEqualTo(2)
    }

    @Test
    fun savingANewDraftSupersedesTheOldOne() = runBlocking {
        val dao = FakePlanDao()
        val store = store(dao)
        store.saveDraft(taskId, "目标", steps)
        store.saveDraft(taskId, "目标", steps)
        assertThat(dao.rows.filter { it.status == PlanStatus.DRAFT }).hasSize(1)
        assertThat(dao.rows.filter { it.status == PlanStatus.SUPERSEDED }).hasSize(1)
    }

    @Test
    fun confirmSucceedsOnlyOnce() = runBlocking {
        val store = store()
        val draft = store.saveDraft(taskId, "目标", steps)
        assertThat(store.confirm(taskId, draft.revision)).isTrue()
        // ★ 第二次必须失败：两个界面同时点「开始」时，第二个点击不能再放行一次执行
        assertThat(store.confirm(taskId, draft.revision)).isFalse()
    }

    @Test
    fun confirmDoesNotResurrectASupersededRevision() = runBlocking {
        val store = store()
        val first = store.saveDraft(taskId, "目标", steps)
        store.saveDraft(taskId, "目标", steps) // 第一版被置为 SUPERSEDED
        assertThat(store.confirm(taskId, first.revision)).isFalse()
    }

    @Test
    fun confirmOnMissingRevisionIsFalseNotAnException() = runBlocking {
        val store = store()
        assertThat(store.confirm(taskId, 42)).isFalse()
    }

    @Test
    fun latestDecodesStepsBackIntoTheSameShape() = runBlocking {
        val store = store()
        store.saveDraft(taskId, "把重点整理出来", steps)
        val latest = store.latest(taskId)!!
        assertThat(latest.goal).isEqualTo("把重点整理出来")
        assertThat(latest.steps.map { it.kind })
            .containsExactly(PlanStepKind.READ_FILE, PlanStepKind.SUMMARIZE).inOrder()
        assertThat(latest.steps.map { it.index }).containsExactly(0, 1).inOrder()
    }

    // ─────────────────────────────────────── 替身

    private fun store(dao: FakePlanDao = FakePlanDao()): PlanStoreImpl =
        PlanStoreImpl(dao = dao, tx = object : DbTransaction {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        })

    private class FakePlanDao : PlanDao {
        val rows = ArrayList<PlanRevisionEntity>()

        override suspend fun insert(row: PlanRevisionEntity): Long {
            if (rows.any { it.taskId == row.taskId && it.revision == row.revision }) return -1L
            rows += row
            return row.revision.toLong()
        }

        override suspend fun maxRevision(taskId: String): Int =
            rows.filter { it.taskId == taskId }.maxOfOrNull { it.revision } ?: 0

        override suspend fun latest(taskId: String): PlanRevisionEntity? =
            rows.filter { it.taskId == taskId }.maxByOrNull { it.revision }

        override fun observeLatest(taskId: String): Flow<PlanRevisionEntity?> =
            flowOf(latestOrNull(taskId))

        private fun latestOrNull(taskId: String): PlanRevisionEntity? =
            rows.filter { it.taskId == taskId }.maxByOrNull { it.revision }

        override suspend fun confirm(taskId: String, revision: Int): Int {
            val i = rows.indexOfFirst {
                it.taskId == taskId && it.revision == revision && it.status == PlanStatus.DRAFT
            }
            if (i < 0) return 0
            rows[i] = rows[i].copy(status = PlanStatus.CONFIRMED)
            return 1
        }

        override suspend fun supersedeDrafts(taskId: String): Int {
            var n = 0
            for (i in rows.indices) {
                if (rows[i].taskId == taskId && rows[i].status == PlanStatus.DRAFT) {
                    rows[i] = rows[i].copy(status = PlanStatus.SUPERSEDED)
                    n++
                }
            }
            return n
        }

        override suspend fun deleteOfTask(taskId: String): Int {
            val before = rows.size
            rows.removeAll { it.taskId == taskId }
            return before - rows.size
        }
    }
}
