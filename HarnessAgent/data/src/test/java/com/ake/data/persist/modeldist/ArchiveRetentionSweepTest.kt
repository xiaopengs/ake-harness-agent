package com.ake.data.persist.modeldist

import com.ake.core.common.ManualClock
import com.ake.core.contract.ModelAssetState
import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.entity.ModelAssetEntity
import com.ake.data.persist.fake.FakeModelAssetDao
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 归档清理扫描（任务 A）。
 *
 * ## 本文件要钉死的四件事
 *
 *  1. **过期的真被清**：陈旧且零价值的 `.part` 被删，行上的指针同步摘掉。
 *  2. **未过期的不动**：这条是「宁可少删」最容易被破坏的地方 —— 只要把宽限期判据
 *     写成看 `ModelAssetRecord.updatedAt`（残缺行恒为 0），刚创建的下载就会被当场删掉。
 *  3. **清理期间的新数据不受影响**：别人在 ①扫描 与 ②动手 之间改过这一行 ⇒ CAS 打空，
 *     本类一个字节都不碰。
 *  4. **幂等**：第二轮必须完全空转（`isQuiet`）。
 *
 * ⚠ 第 3 条刻意用**行为**断言而不是源码扫描：`ColdStartWiringTest` 的注释已经写过
 *   「一句『我们有守护测试』配上只能被注释骗过的测试，比没有更糟」。
 */
@RunWith(JUnit4::class)
class ArchiveRetentionSweepTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val clock = ManualClock(now)
    private val grace = ModelArchiveRetention.GRACE_MILLIS_DEFAULT

    private val old: Long = now - 40L * 24 * 60 * 60 * 1000     // 40 天前
    private val fresh: Long = now - 2L * 24 * 60 * 60 * 1000     // 2 天前

    private val dao = FakeModelAssetDao()

    private fun sweep(modelsDir: File = tmp.root): ArchiveRetentionSweep =
        ArchiveRetentionSweep(dao = dao, modelsDir = modelsDir, clock = clock, graceMillis = grace)

    private fun part(id: String, vararg content: Byte): File {
        val f = File(tmp.root, ModelFileLayout.partNameOf(id))
        f.writeBytes(content)
        f.setLastModified(old)
        return f
    }

    private fun row(
        id: String,
        state: String = ModelAssetState.DOWNLOADING,
        modelId: String = "gemma-4-e2b-it",
        variant: String = "int4",
        localPath: String? = null,
        tempFile: String? = ModelFileLayout.partNameOf(id),
        bytes: Long = 0L,
        startedAt: Long? = old,
        finishedAt: Long? = null,
        verifiedAt: Long? = null,
        usedAt: Long? = null,
        errorAt: Long? = null,
    ): ModelAssetEntity = ModelAssetEntity(
        id = id,
        modelId = modelId,
        variant = variant,
        remoteUrl = "https://cdn.ake.cn/x.litertlm",
        localPath = localPath,
        expectedBytes = 2_782_923_040L,
        sha256 = "a".repeat(64),
        contextWindow = 8192,
        minRamGb = 6,
        capabilitiesJson = "TEXT",
        state = state,
        bytesDownloaded = bytes,
        downloadStartedAt = startedAt,
        downloadFinishedAt = finishedAt,
        tempFile = tempFile,
        verifyAttempts = 0,
        verifyLastResult = null,
        verifiedAt = verifiedAt,
        actualBytes = null,
        actualSha256 = null,
        lastError = null,
        lastErrorAt = errorAt,
        lastUsedAt = usedAt,
        loadCount = 0,
        lastBackend = null,
        lastLoadMs = null,
    )

    private suspend fun stored(vararg entities: ModelAssetEntity) {
        entities.forEach { dao.upsert(it) }
    }

    // ─────────────────────────────────────────────── ① 过期的被清

    @Test
    fun `陈旧的零价值 part 被回收且指针被摘掉`() = runBlocking {
        val file = part("01JOLD0000000000000000000", 1, 2, 3)
        stored(row("01JOLD0000000000000000000"))

        val report = sweep().runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(1)
        assertThat(report.bytesReclaimed).isEqualTo(3L)
        assertThat(file.exists()).isFalse()
        // ★ DB 指针必须同步摘掉 —— 否则账本会一直指着一份不存在的文件
        assertThat(dao.byId("01JOLD0000000000000000000")!!.tempFile).isNull()
    }

    // ─────────────────────────────────────────────── ② 未过期的不动

    @Test
    fun `未过期的 part 一律不动`() = runBlocking {
        val file = part("01JFRESH00000000000000000")
        // ★ 关键：这一行除了 downloadStartedAt 之外**没有**任何一列的时间戳。
        //   用 `ModelAssetRecord.updatedAt` 当新鲜度判据的实现，这里会算出 0 ⇒ 当场误删。
        stored(row("01JFRESH00000000000000000", startedAt = fresh))

        val report = sweep().runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(0)
        assertThat(report.skippedFresh).isEqualTo(1)
        assertThat(file.exists()).isTrue()
        assertThat(dao.byId("01JFRESH00000000000000000")!!.tempFile).isNotNull()
    }

    @Test
    fun `文件本身正在被写就绝不动手`() = runBlocking {
        val file = part("01JWRITING000000000000000")
        // 行上的时间戳都很旧，但文件 mtime 是「刚刚」—— 有人正在写它。
        file.setLastModified(now - 1_000)
        stored(row("01JWRITING000000000000000"))

        val report = sweep().runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(0)
        assertThat(report.skippedFresh).isEqualTo(1)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `正在被校验的行绝不动手`() = runBlocking {
        val file = part("01JVERIFY0000000000000000")
        stored(row("01JVERIFY0000000000000000", state = ModelAssetState.VERIFYING))

        val report = sweep().runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(0)
        assertThat(report.skippedBusy).isEqualTo(1)
        assertThat(file.exists()).isTrue()
    }

    // ─────────────────────────────────────────────── ②b 有残留价值的不动

    @Test
    fun `有可续传字节的 part 保留不删`() = runBlocking {
        val file = part("01JRESUME0000000000000000")
        // 只下了 1.8 GB 也是 1.8 GB 的钱 —— 删掉等于让用户白下载这一段
        stored(row("01JRESUME0000000000000000", state = ModelAssetState.PAUSED, bytes = 1_800_000_000L))

        val report = sweep().runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(0)
        assertThat(report.skippedResumable).isEqualTo(1)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `仍指向一份可用模型的行绝不动手`() = runBlocking {
        val file = part("01JHASPATH000000000000000")
        stored(row("01JHASPATH000000000000000", localPath = "gemma-4-e2b-it_int4_aaaaaaaaaaaaaaaa.litertlm"))

        val report = sweep().runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(0)
        assertThat(report.skippedResumable).isEqualTo(1)
        assertThat(file.exists()).isTrue()
    }

    // ─────────────────────────────────────────────── ③ 清理期间的并发写入

    @Test
    fun `扫描后别人改过这一行则整笔放弃`() = runBlocking {
        val id = "01JRACE000000000000000000"
        val file = part(id)
        stored(row(id))

        // 模拟：在这一行被扫描之后、被动手之前，一次并发的 beginOrResume 换了 temp_file。
        val racing: ModelAssetDao = object : ModelAssetDao by dao {
            override suspend fun clearTempFile(id: String, name: String): Int {
                val current = dao.byId(id) ?: return 0
                dao.upsert(current.copy(tempFile = "$id.litertlm.part.b"))
                return dao.clearTempFile(id, name)
            }
        }
        val report = ArchiveRetentionSweep(dao = racing, modelsDir = tmp.root, clock = clock).runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(0)
        assertThat(report.skippedRaced).isEqualTo(1)
        assertThat(report.failures).isEmpty()
        // ★★ 真正的断言在这里：别人的文件一个字节都没少
        assertThat(file.exists()).isTrue()
    }

    // ─────────────────────────────────────────────── ④ 幂等

    @Test
    fun `第二轮完全空转`() = runBlocking {
        stored(row("01JFIRST00000000000000000"))
        part("01JFIRST00000000000000000", 9, 9)

        val first = sweep().runOnce()
        val second = sweep().runOnce()

        assertThat(first.tempFilesDiscarded).isEqualTo(1)
        assertThat(second.tempFilesDiscarded).isEqualTo(0)
        assertThat(second.bytesReclaimed).isEqualTo(0L)
        assertThat(second.isQuiet).isTrue()
    }

    // ─────────────────────────────────────────────── ⑤ 归档本体：只报不删

    @Test
    fun `归档行一个都不删只量出来给 UI`() = runBlocking {
        // 三份 READY：keep 两份 + 一份够资格的老归档
        val current = row(
            "01JCURRENT00000000000000",
            state = ModelAssetState.READY,
            localPath = ModelFileLayout.finalNameOf("01JCURRENT00000000000000"),
            tempFile = null,
            finishedAt = now - 1,
        )
        val previous = row(
            "01JPREVIOUS0000000000000",
            state = ModelAssetState.READY,
            localPath = ModelFileLayout.finalNameOf("01JPREVIOUS0000000000000"),
            tempFile = null,
            finishedAt = now - 2,
        )
        val archive = row(
            "01JARCHIVE000000000000000",
            state = ModelAssetState.READY,
            localPath = ModelFileLayout.finalNameOf("01JARCHIVE000000000000000"),
            tempFile = null,
            finishedAt = old,
            verifiedAt = old,
        )
        stored(current, previous, archive)

        val report = sweep().runOnce()

        assertThat(report.archivesAwaitingUser).isEqualTo(1)
        assertThat(report.bytesAwaitingUser).isEqualTo(2_782_923_040L)
        assertThat(report.tempFilesDiscarded).isEqualTo(0)
        // ★ 三份 READY 一个都没少
        assertThat(dao.rows.values.count { it.state == ModelAssetState.READY }).isEqualTo(3)
    }

    @Test
    fun `回收 part 不动这一行的其它列`() = runBlocking {
        val id = "01JPAUSED0000000000000000"
        part(id)
        stored(row(id, state = ModelAssetState.PAUSED, bytes = 0L))

        val report = sweep().runOnce()

        assertThat(report.tempFilesDiscarded).isEqualTo(1)
        // ★ 只摘 temp_file：状态 / 进度都不是本类该插手的东西
        assertThat(dao.byId(id)!!.state).isEqualTo(ModelAssetState.PAUSED)
    }
}
