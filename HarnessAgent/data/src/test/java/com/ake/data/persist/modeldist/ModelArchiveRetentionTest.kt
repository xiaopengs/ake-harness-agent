package com.ake.data.persist.modeldist

import com.ake.core.contract.ModelAssetState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 归档清理策略（裁决项 C）。
 *
 * ## 本文件要钉死的三件事
 *
 *  1. **永远保留两份**：在用 + 上一个可回滚版本。只留一份等于取消回滚能力，
 *     而「不发版就能回滚」是选 manifest 方案的唯一理由。
 *  2. **更早的归档有两道闸门**：用户显式确认 **且** 当前在用版本刚被成功加载过。
 *     缺任一道 ⇒ `deletable()` 返回空表（调用方**拿不到**任何可删项）。
 *  3. **残缺件只需确认**：CORRUPTED / 半成品不是「可用模型」，
 *     给它们也加「可加载」闸门只会让它们永久占位。
 */
@RunWith(JUnit4::class)
class ModelArchiveRetentionTest {

    private fun ready(
        id: String,
        at: Long,
        bytes: Long = ModelDistFixtures.SIZE,
        state: String = ModelAssetState.READY,
    ) = ModelDistFixtures.record(
        id = id,
        state = state,
        localPath = ModelFileLayout.finalNameOf(id),
        bytesDownloaded = bytes,
        updatedAt = at,
    )

    private val rows = listOf(
        ready("01JCURRENT00000000000000", at = 300L),
        ready("01JPREVIOUS0000000000000", at = 200L),
        ready("01JOLD00000000000000000", at = 100L),
        ready("01JOLDEST000000000000000", at = 50L),
    )

    @Test
    fun `保留在用与上一个可回滚版本共两份`() {
        val plan = ModelArchiveRetention.plan(rows)

        assertThat(plan.keep).hasSize(ModelArchiveRetention.KEEP_VERSIONS)
        assertThat(plan.keep.map { it.id }).containsExactly(
            "01JCURRENT00000000000000",
            "01JPREVIOUS0000000000000",
        )
        assertThat(plan.archives.map { it.id })
            .containsExactly("01JOLD00000000000000000", "01JOLDEST000000000000000")
    }

    @Test
    fun `未确认时什么都不许删`() {
        val plan = ModelArchiveRetention.plan(rows)

        assertThat(plan.deletable(userConfirmed = false, currentLoadable = true)).isEmpty()
        assertThat(plan.blockedReasonKey(userConfirmed = false, currentLoadable = true))
            .isEqualTo("err_model_cleanup_need_confirm")
    }

    @Test
    fun `当前在用版本未验证可加载时归档一个都不能删`() {
        val plan = ModelArchiveRetention.plan(rows)

        assertThat(plan.deletable(userConfirmed = true, currentLoadable = false)).isEmpty()
        assertThat(plan.blockedReasonKey(userConfirmed = true, currentLoadable = false))
            .isEqualTo("err_model_cleanup_current_unverified")
    }

    @Test
    fun `两道闸门都过才允许清理更早的归档`() {
        val plan = ModelArchiveRetention.plan(rows)

        val deletable = plan.deletable(userConfirmed = true, currentLoadable = true)

        assertThat(deletable.map { it.id })
            .containsExactly("01JOLD00000000000000000", "01JOLDEST000000000000000")
        assertThat(plan.blockedReasonKey(userConfirmed = true, currentLoadable = true)).isNull()
        assertThat(plan.reclaimableBytes(true, true)).isEqualTo(ModelDistFixtures.SIZE * 2)
    }

    @Test
    fun `残缺件只需确认即可清理`() {
        val corrupted = ready("01JCORRUPT00000000000000", at = 400L, state = ModelAssetState.CORRUPTED)
        val plan = ModelArchiveRetention.plan(rows + corrupted)

        assertThat(plan.keep.map { it.id }).doesNotContain("01JCORRUPT00000000000000")
        // ★ 即使 currentLoadable=false，残缺件也在可删名单里 —— 它本来就不在服务
        assertThat(plan.deletable(userConfirmed = true, currentLoadable = false).map { it.id })
            .containsExactly("01JCORRUPT00000000000000")
    }

    @Test
    fun `只有一份时它绝不可删`() {
        val plan = ModelArchiveRetention.plan(listOf(rows.first()))

        assertThat(plan.archives).isEmpty()
        assertThat(plan.deletable(userConfirmed = true, currentLoadable = true)).isEmpty()
    }

    @Test
    fun `没有 localPath 的 READY 行不算可用版本`() {
        val orphan = ModelDistFixtures.record(id = "01JORPHAN00000000000000", localPath = null)
        val plan = ModelArchiveRetention.plan(listOf(rows.first(), orphan))

        assertThat(plan.keep.map { it.id }).containsExactly("01JCURRENT00000000000000")
        assertThat(plan.incomplete.map { it.id }).containsExactly("01JORPHAN00000000000000")
    }
}
