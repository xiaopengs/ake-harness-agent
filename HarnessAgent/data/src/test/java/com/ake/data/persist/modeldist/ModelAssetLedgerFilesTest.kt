package com.ake.data.persist.modeldist

import com.ake.core.common.ManualClock
import com.ake.core.contract.ModelAssetState
import com.ake.data.persist.fake.FakeModelAssetDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 删除路径上的**命名对齐**（交接项 B2）。
 *
 * ## 要钉死的两件事
 *
 *  1. **按 modelId 能列出这个模型拥有的全部文件名**，而不只是「某一个」。
 *     归档件、半成品 `.part`、以及旧数据里 assetId == modelId 的那份，全都要在名单里 ——
 *     漏掉任何一个，用户点了「删除」之后 2.6 GB 还在盘上。
 *  2. **清理裁决与解析器选「在用」用的是同一把尺子**（`updatedAt` 最大者），
 *     否则会出现「策略觉得该留的」和「解析器觉得在用的」不是同一行。
 */
@RunWith(JUnit4::class)
class ModelAssetLedgerFilesTest {

    private val dao = FakeModelAssetDao()
    private val clock = ManualClock(1_700_000_000_000L)
    private val ledger = ModelAssetLedger(dao, clock)

    private val entryV5 = ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"))
    private val entryV7 = ModelDistFixtures.entry(revision = 7, sha256 = ModelDistFixtures.sha("b"))

    private fun step(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = block)

    @Test
    fun `按 modelId 列出槽位行归档行与 part 的全部名字`() = step {
        val h1 = ledger.beginOrResume(entryV5)
        ledger.finish(h1.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)
        // 目标版本变化 ⇒ 旧文件被归档成独立一行，槽位行回到下载中（带 .part）
        ledger.beginOrResume(entryV7)

        val names = ledger.ownedFileNames(entryV5.modelId)

        // ① 旧命名（`:llm` 的 `delete(modelId)` 认的就是它俩）
        assertThat(names).containsAtLeast(
            ModelFileLayout.finalNameOf(entryV5.modelId),
            ModelFileLayout.partNameOf(entryV5.modelId),
        )
        // ② 槽位行的正名与半成品
        assertThat(names).containsAtLeast(
            ModelFileLayout.finalNameOf(h1.assetId),
            ModelFileLayout.partNameOf(h1.assetId),
        )
        // ③ 归档件（`<modelId>_<variant>_<sha16>.litertlm`）来自归档行的 local_path
        assertThat(names).contains(
            ModelFileLayout.archiveNameOf(entryV5.modelId, entryV5.variant, entryV5.sha256),
        )
        assertThat(names.toSet()).hasSize(names.size) // 去重
    }

    @Test
    fun `没有记录时至少给出旧命名以便清理遗留文件`() = step {
        val names = ledger.ownedFileNames("gemma-4-e2b-it")

        assertThat(names).containsExactly(
            "gemma-4-e2b-it.litertlm",
            "gemma-4-e2b-it.litertlm.part",
        )
    }

    @Test
    fun `清理裁决保留当前与上一个版本`() = step {
        val h1 = ledger.beginOrResume(entryV5)
        ledger.finish(h1.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)
        val h2 = ledger.beginOrResume(entryV7)
        ledger.finish(h2.assetId, entryV7, entryV7.sizeBytes, entryV7.sha256)

        val plan = ledger.retentionPlan(entryV5.modelId)

        assertThat(plan.keep).hasSize(2)
        // 两份都必须是真的能用（READY 且有路径）；DOWNLOADING 的槽位行不算
        assertThat(plan.keep.all { it.state == ModelAssetState.READY }).isTrue()
        assertThat(plan.deletable(userConfirmed = true, currentLoadable = true))
            .containsNoneIn(plan.keep)
    }
}
