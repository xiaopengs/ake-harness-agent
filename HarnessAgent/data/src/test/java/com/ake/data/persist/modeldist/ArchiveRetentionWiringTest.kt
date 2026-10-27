package com.ake.data.persist.modeldist

import com.ake.core.common.ManualClock
import com.ake.data.persist.fake.FakeModelAssetDao
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * ★ 归档保留扫描的**接线**（谁也没调它，但它确实跑了）。
 *
 * ## 为什么这两个测试值得单独一个文件
 *
 * 上一轮 `TemplateBootstrap.bootstrap()` 与 `ChatLoop.onColdStart()` 都有完整单测、
 * 都过了评审，却在生产里**一次都没被调用** —— 根因就是「启动路径上没有调用点」这件事
 * 本身没有被断言过。`ColdStartCoordinator` 的注释里那句提醒在这里同样成立：
 *
 *  > 源码扫描挡不住注释（把调用点注释掉，4 条测试全绿）。一句「我们有守护测试」
 *  > 配上那样的测试，比没有守护测试更糟。
 *
 * 所以这里断言的是**可观测的行为**：构造完单例，等一小会儿，**没有任何人调用任何东西**，
 * `.part` 却已经不在了。把 `ModelAssetLedger.init` 里的 `launch` 注释掉，
 * 下面两条会当场变红。
 */
@RunWith(JUnit4::class)
class ArchiveRetentionWiringTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val old: Long = now - 40L * 24 * 60 * 60 * 1000

    private fun daoWithOneStalePart(id: String, modelsDir: File): Pair<FakeModelAssetDao, File> {
        val dao = FakeModelAssetDao()
        val part = File(modelsDir, ModelFileLayout.partNameOf(id))
        part.writeBytes(byteArrayOf(1, 2, 3))
        part.setLastModified(old)
        dao.rows[id] = entity(id)
        return dao to part
    }

    private fun entity(id: String): com.ake.data.persist.db.entity.ModelAssetEntity =
        com.ake.data.persist.db.entity.ModelAssetEntity(
            id = id,
            modelId = "gemma-4-e2b-it",
            variant = "int4",
            remoteUrl = "https://cdn.ake.cn/x.litertlm",
            localPath = null,
            expectedBytes = 2_782_923_040L,
            sha256 = "a".repeat(64),
            contextWindow = 8192,
            minRamGb = 6,
            capabilitiesJson = "TEXT",
            state = com.ake.core.contract.ModelAssetState.DOWNLOADING,
            bytesDownloaded = 0L,
            downloadStartedAt = old,
            downloadFinishedAt = null,
            tempFile = ModelFileLayout.partNameOf(id),
            verifyAttempts = 0,
            verifyLastResult = null,
            verifiedAt = null,
            actualBytes = null,
            actualSha256 = null,
            lastError = null,
            lastErrorAt = null,
            lastUsedAt = null,
            loadCount = 0,
            lastBackend = null,
            lastLoadMs = null,
        )

    @Test
    fun `装配好钩子之后没有任何人调用它但它自己跑了一轮`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val (dao, part) = daoWithOneStalePart("01JWIRED0000000000000000", tmp.root)
        val sweep = ArchiveRetentionSweep(
            dao = dao,
            modelsDir = tmp.root,
            clock = ManualClock(now),
        )

        // ↓↓↓ 除了构造，下面**没有一行**调用 sweep.runOnce()
        ModelAssetLedger(
            dao = dao,
            clock = ManualClock(now),
            writeScope = scope,
            retentionSweep = { sweep.runOnce() },
        )
        delay(500)
        // ↑↑↑

        assertThat(part.exists()).isFalse()
        assertThat(dao.rows["01JWIRED0000000000000000"]!!.tempFile).isNull()
        executor.shutdown()
    }

    @Test
    fun `装配一次只跑一轮而不是每次用到账本都跑一轮`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        var rounds = 0
        val dao = FakeModelAssetDao()

        val ledger = ModelAssetLedger(
            dao = dao,
            clock = ManualClock(now),
            writeScope = scope,
            retentionSweep = { rounds++ },
        )
        delay(300)
        // 账本随后被反复使用（读裁决 / 读已装清单）—— 清理轮数仍然只能是 1
        runCatching { ledger.retentionPlan("gemma-4-e2b-it") }
        runCatching { ledger.installed() }
        delay(300)

        assertThat(rounds).isEqualTo(1)
        executor.shutdown()
    }
}
