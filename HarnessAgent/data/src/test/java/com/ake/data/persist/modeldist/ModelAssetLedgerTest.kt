package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
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
 * `model_asset` 上的下载/校验状态机。
 *
 * ## 本文件要钉死的三件事
 *
 *  1. **跨重启续传**：`bytes_downloaded` 必须落库并在下一次 `beginOrResume` 原样取回 ——
 *     A2「中断 10 次仍能下完」在**杀进程**前提下唯一的实现方式。
 *  2. **目标版本变化时不删旧模型**：只产生 `Rename`（旧 → 归档），
 *     任何指向旧模型文件的 `Discard` 都是回归。
 *  3. **校验失败可重试、重试有上限**：上限到了必须停在 `CORRUPTED` 而不是无限重下
 *     （2.6 GB × 无限次 = 用户流量账单被烧穿）。
 */
@RunWith(JUnit4::class)
class ModelAssetLedgerTest {

    private val dao = FakeModelAssetDao()
    private val clock = ManualClock(1_700_000_000_000L)
    private val ledger = ModelAssetLedger(dao, clock, maxVerifyAttempts = 3)

    /**
     * `runBlocking` 的 Unit 包装。
     *
     * ★ 不能写成 `@Test fun x() = runBlocking { ... }`：那样方法的返回类型会被推断成
     *   lambda 最后一个表达式的类型（这里是 `Subject`），JUnit4 会报
     *   "Method x() should be void" 并把**整个类**判为无效 —— 一个方法写错，全类不跑。
     */
    private fun step(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = block)

    private val entryV5 = ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"))
    private val entryV7 = ModelDistFixtures.entry(revision = 7, sha256 = ModelDistFixtures.sha("b"))

    // ──────────────────────────────────────────── 开始与续传

    @Test
    fun `首次开始下载落 DOWNLOADING 与 part 文件名`() = step {
        val handle = ledger.beginOrResume(entryV5)

        val row = dao.byId(handle.assetId)!!
        assertThat(handle.resumeFromBytes).isEqualTo(0L)
        assertThat(handle.alreadyReady).isFalse()
        assertThat(handle.pendingFileOps).isEmpty()
        assertThat(row.state).isEqualTo(ModelAssetState.DOWNLOADING)
        assertThat(row.tempFile).isEqualTo(ModelFileLayout.partNameOf(handle.assetId))
        assertThat(row.sha256).isEqualTo(entryV5.sha256)
        assertThat(row.expectedBytes).isEqualTo(entryV5.sizeBytes)
        assertThat(row.capabilitiesJson).isEqualTo("TEXT,TOOLS")
    }

    @Test
    fun `中断后重新开始能取回已下字节`() = step {
        val handle = ledger.beginOrResume(entryV5)
        ledger.progress(handle.assetId, 1_800_000_000L)

        val resumed = ledger.beginOrResume(entryV5)
        assertThat(resumed.assetId).isEqualTo(handle.assetId)
        assertThat(resumed.resumeFromBytes).isEqualTo(1_800_000_000L)
        assertThat(resumed.alreadyReady).isFalse()
    }

    @Test
    fun `已 READY 的目标再次开始时直接判定完成`() = step {
        val handle = ledger.beginOrResume(entryV5)
        ledger.finish(handle.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)

        val again = ledger.beginOrResume(entryV5)
        assertThat(again.alreadyReady).isTrue()
        assertThat(again.pendingFileOps).isEmpty()
    }

    // ──────────────────────────────────────────── 目标变化：归档而非删除

    @Test
    fun `目标版本变化时把旧模型改名归档而不是删除`() = step {
        val h1 = ledger.beginOrResume(entryV5)
        ledger.finish(h1.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)
        val oldFinalName = ModelFileLayout.finalNameOf(h1.assetId)

        val h2 = ledger.beginOrResume(entryV7)

        val renames = h2.pendingFileOps.filterIsInstance<ModelFileOp.Rename>()
        assertThat(renames).hasSize(1)
        assertThat(renames.single().fromName).isEqualTo(oldFinalName)
        assertThat(renames.single().toName)
            .isEqualTo(ModelFileLayout.archiveNameOf(entryV7.modelId, entryV7.variant, entryV5.sha256))
        // ★ 硬规则 1：没有任何一个动作是删掉旧模型文件
        assertThat(h2.pendingFileOps.filterIsInstance<ModelFileOp.Discard>()).isEmpty()
    }

    @Test
    fun `归档行保持 READY 且槽位行被重置`() = step {
        val h1 = ledger.beginOrResume(entryV5)
        ledger.finish(h1.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)

        val h2 = ledger.beginOrResume(entryV7)

        val slot = dao.byId(h2.assetId)!!
        assertThat(slot.sha256).isEqualTo(entryV7.sha256)
        assertThat(slot.state).isEqualTo(ModelAssetState.DOWNLOADING)
        assertThat(slot.bytesDownloaded).isEqualTo(0L)
        assertThat(slot.localPath).isNull()

        val archive = dao.allOnce().first { it.variant != entryV7.variant }
        assertThat(archive.state).isEqualTo(ModelAssetState.READY)
        assertThat(archive.sha256).isEqualTo(entryV5.sha256)
        assertThat(archive.variant).isEqualTo(ModelFileLayout.archiveVariantOf(entryV5.variant, entryV5.sha256))
    }

    @Test
    fun `目标变化时旧 part 被丢弃但旧模型不会被丢弃`() = step {
        val h1 = ledger.beginOrResume(entryV5)
        ledger.progress(h1.assetId, 500L) // 下了一半，还没 READY

        val h2 = ledger.beginOrResume(entryV7)

        // 未完成的 .part 不是「可用模型」，可以丢；旧模型（不存在）不产生任何 Rename
        assertThat(h2.pendingFileOps).containsExactly(ModelFileOp.Discard(ModelFileLayout.partNameOf(h1.assetId)))
    }

    // ──────────────────────────────────────────── 校验

    @Test
    fun `校验通过则转正为 READY`() = step {
        val handle = ledger.beginOrResume(entryV5)
        val outcome = ledger.finish(handle.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)

        assertThat(outcome.verdict).isEqualTo(VerifyVerdict.MATCH)
        assertThat(outcome.action).isEqualTo(VerifyAction.PROMOTE)
        val row = dao.byId(handle.assetId)!!
        assertThat(row.state).isEqualTo(ModelAssetState.READY)
        assertThat(row.localPath).isEqualTo(ModelFileLayout.finalNameOf(handle.assetId))
        assertThat(row.tempFile).isNull()
        assertThat(row.verifyAttempts).isEqualTo(1)
        assertThat(row.verifyLastResult).isEqualTo("MATCH")
    }

    @Test
    fun `摘要不匹配时丢弃 part 并从头重下`() = step {
        val handle = ledger.beginOrResume(entryV5)
        val outcome = ledger.finish(handle.assetId, entryV5, entryV5.sizeBytes, "b".repeat(64))

        assertThat(outcome.verdict).isEqualTo(VerifyVerdict.HASH_MISMATCH)
        assertThat(outcome.action).isEqualTo(VerifyAction.DISCARD_AND_RESTART)
        assertThat(outcome.attemptsDone).isEqualTo(1)
        assertThat(outcome.pendingFileOps)
            .containsExactly(ModelFileOp.Discard(ModelFileLayout.partNameOf(handle.assetId)))
        val row = dao.byId(handle.assetId)!!
        assertThat(row.state).isEqualTo(ModelAssetState.DOWNLOADING)
        assertThat(row.bytesDownloaded).isEqualTo(0L)
        assertThat(row.lastError).isEqualTo("err_model_checksum_mismatch")
    }

    @Test
    fun `校验失败达到上限后停在 CORRUPTED`() = step {
        val handle = ledger.beginOrResume(entryV5)
        repeat(2) {
            ledger.finish(handle.assetId, entryV5, entryV5.sizeBytes, "b".repeat(64))
        }
        val last = ledger.finish(handle.assetId, entryV5, entryV5.sizeBytes, "b".repeat(64))

        assertThat(last.attemptsDone).isEqualTo(3)
        assertThat(last.action).isEqualTo(VerifyAction.GIVE_UP)
        assertThat(dao.byId(handle.assetId)!!.state).isEqualTo(ModelAssetState.CORRUPTED)
    }

    @Test
    fun `校验失败重试期间保留归档路径以便仍有可用模型`() = step {
        val h1 = ledger.beginOrResume(entryV5)
        ledger.finish(h1.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)
        val archiveName = ModelFileLayout.archiveNameOf(entryV5.modelId, entryV5.variant, entryV5.sha256)
        val h2 = ledger.beginOrResume(entryV7)
        // 模拟 :llm 执行完归档改名后，槽位行指向归档文件（下载期间它仍在服务）
        dao.upsert(dao.byId(h2.assetId)!!.copy(localPath = archiveName))

        ledger.finish(h2.assetId, entryV7, entryV7.sizeBytes, "c".repeat(64))

        assertThat(dao.byId(h2.assetId)!!.localPath).isEqualTo(archiveName)
    }

    @Test
    fun `传输失败保留已下字节并置为 PAUSED`() = step {
        val handle = ledger.beginOrResume(entryV5)
        ledger.progress(handle.assetId, 900L)

        ledger.fail(handle.assetId, AppError("MODEL_DOWNLOAD_FAILED", "err_model_download_failed"))

        val row = dao.byId(handle.assetId)!!
        assertThat(row.state).isEqualTo(ModelAssetState.PAUSED)
        assertThat(row.bytesDownloaded).isEqualTo(900L)
        assertThat(row.lastError).isEqualTo("err_model_download_failed")
    }

    @Test
    fun `installed 返回全部行含归档行`() = step {
        val h1 = ledger.beginOrResume(entryV5)
        ledger.finish(h1.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)
        ledger.beginOrResume(entryV7)

        val installed = ledger.installed()
        // 排序后再断言：槽位行与归档行的插入顺序是实现细节，不该被测试钉死
        assertThat(installed.map { it.sha256 }.sorted())
            .containsExactly(entryV5.sha256, entryV7.sha256)
    }

    @Test
    fun `时间由注入的 clock 决定`() = step {
        clock.setTo(1_700_000_000_000L)
        val handle = ledger.beginOrResume(entryV5)
        clock.setTo(1_700_000_500_000L)
        ledger.finish(handle.assetId, entryV5, entryV5.sizeBytes, entryV5.sha256)

        assertThat(dao.byId(handle.assetId)!!.verifiedAt).isEqualTo(1_700_000_500_000L)
    }
}
