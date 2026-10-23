package com.ake.llm

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.data.persist.modeldist.ModelEntryState
import com.ake.data.persist.modeldist.ModelFileLayout
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.security.MessageDigest

/**
 * `ModelDistributionSync` —— 本轮「通电」的验收点。
 *
 * ## 本文件要钉死的三件事（对应霍承钧 P1 的三条衔接要求）
 *
 *  1. **`beginOrResume` 产出的 `Rename(旧 → 归档)` 必须在开 HTTP 之前执行。**
 *     判据不只是调用顺序的字符串，更是**文件本身**：顺序错了，新内容就会被写进旧模型
 *     那个名字 ⇒ 旧内容消失。所以这里断言归档文件存在、且内容仍是旧版本的字节。
 *  2. **`probe` 按 sha256 认文件**（见 `ModelDirInventoryTest`）。
 *  3. **下载完成后 `finish` 的三个分支都被走到**：PROMOTE / DISCARD_AND_RESTART / GIVE_UP，
 *     且重试有上界 —— 每次循环都是 2.6 GB，没有上界就会烧穿用户流量。
 *
 * ## 关于 `sync.transfer` 接缝
 *
 * `ModelDownloader` 内部有 `StatFs` 与 `HttpURLConnection`：JVM 单测里两者都不可用。
 * 所以这里替换掉的**只有传输**；账本裁决、文件动作、摘要计算全部是真的。
 */
@RunWith(JUnit4::class)
class ModelDistributionSyncTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var downloader: ModelDownloader
    private lateinit var ledger: FakeModelLedgerGateway
    private lateinit var sync: ModelDistributionSync

    private var srcDir: File? = null

    private fun setup() {
        dir = tmp.newFolder("models")
        downloader = ModelDownloader(dir)
        ledger = FakeModelLedgerGateway()
        sync = ModelDistributionSync(ledger, downloader)
        sync.trace = { ledger.log += "trace:$it" }
    }

    private fun finishes(): List<String> = ledger.log.filter { it.startsWith("finish(") }

    /** `runBlocking` 包装：返回类型必须是 Unit，否则整个类会被 JUnit4 判为无效。 */
    private fun step(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = block)

    // ───────────────────────────────────────────── ① Rename 必须先于 HTTP

    @Test
    fun `目标版本变化时 旧模型先归档再开 HTTP`() = step {
        setup()
        val v1 = srcFile("v1-bytes")
        val v2 = srcFile("v2-bytes-longer")

        val entryV1 = entry(revision = 1, sha = shaOf(v1), size = v1.length())
        val assetId = ledger.seedReady(entryV1)
        File(dir, ModelFileLayout.finalNameOf(assetId)).writeBytes(v1.readBytes())

        val entryV2 = entry(revision = 2, sha = shaOf(v2), size = v2.length())
        sync.transfer = transferThatWrites(v2)

        val outcome = sync.ensure(entryV2)

        // ── 顺序：begin → rename → download
        val iBegin = ledger.log.indexOfFirst { it.startsWith("trace:begin:") }
        val iRename = ledger.log.indexOfFirst { it.startsWith("trace:op:Rename(") }
        val iHttp = ledger.log.indexOf("trace:download:$assetId")
        assertThat(iBegin).isAtLeast(0)
        assertThat(iRename).isGreaterThan(iBegin)
        assertThat(iHttp).isGreaterThan(iRename)

        // ── 硬规则 1 的直接证据：旧内容还在，只是换成了归档名
        val archive = File(dir, ModelFileLayout.archiveNameOf(entryV2.modelId, entryV2.variant, entryV1.sha256))
        assertThat(archive.isFile).isTrue()
        assertThat(archive.readBytes()).isEqualTo(v1.readBytes())

        // ── 新版本转正
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        assertThat(File(dir, ModelFileLayout.finalNameOf(assetId)).readBytes()).isEqualTo(v2.readBytes())
        assertThat(finishes().last()).isEqualTo("finish(MATCH/PROMOTE#1)")
    }

    @Test
    fun `目标版本没变且已 READY 时 不开 HTTP`() = step {
        setup()
        val v1 = srcFile("v1-bytes")
        val entryV1 = entry(revision = 1, sha = shaOf(v1), size = v1.length())
        val assetId = ledger.seedReady(entryV1)
        File(dir, ModelFileLayout.finalNameOf(assetId)).writeBytes(v1.readBytes())

        sync.transfer = { _, _ -> throw AssertionError("不该发起下载") }

        val outcome = sync.ensure(entryV1)

        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        assertThat((outcome as Outcome.Ok).value.name).isEqualTo(ModelFileLayout.finalNameOf(assetId))
        assertThat(ledger.log.filter { it.startsWith("trace:download") }).isEmpty()
    }

    // ───────────────────────────────────────────── ③ finish 的三个分支

    @Test
    fun `下载内容摘要不符时 丢 part 重下 且重试有上界`() = step {
        setup()
        val broken = srcFile("broken-bytes")
        val entryV2 = entry(revision = 2, sha = shaOf(srcFile("the-real-bytes")), size = broken.length())

        var rounds = 0
        sync.transfer = transferThatWrites(broken, onEnter = { rounds++ })

        val outcome = sync.ensure(entryV2)

        assertThat(outcome).isInstanceOf(Outcome.Err::class.java)
        // 账本 maxVerifyAttempts = 3 ⇒ 第 3 次判 GIVE_UP，不无限重下
        assertThat(rounds).isEqualTo(3)
        assertThat(finishes().last()).isEqualTo("finish(HASH_MISMATCH/GIVE_UP#3)")
        assertThat((outcome as Outcome.Err).error.code).isEqualTo("MODEL_CHECKSUM_MISMATCH")
    }

    @Test
    fun `传输层失败时 保留字节等续传 不消耗校验次数`() = step {
        setup()
        val entryV2 = entry(revision = 2, sha = shaOf(srcFile("x")), size = 10L)
        sync.transfer = { _, _ -> Outcome.Err(AppError(AppError.CODE_TIMEOUT, "err_timeout")) }

        val outcome = sync.ensure(entryV2)

        assertThat(outcome).isInstanceOf(Outcome.Err::class.java)
        assertThat(ledger.log).contains("fail(TIMEOUT)")
        assertThat(ledger.log.filter { it.startsWith("finish(") }).isEmpty()
    }

    // ───────────────────────────────────────────── 辅助

    private fun srcFile(content: String): File {
        val root = srcDir ?: tmp.newFolder("src").also { srcDir = it }
        return File(root, "f${content.hashCode()}").apply { writeText(content) }
    }

    private fun shaOf(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { b -> "%02x".format(b) }

    /** 模拟「下载成功」：把内容写到 `ModelFileLayout.finalNameOf(id)` 上。 */
    private fun transferThatWrites(
        content: File,
        onEnter: (() -> Unit)? = null,
    ): suspend (ModelDownloader.Request, (ModelDownloader.Progress) -> Unit) -> Outcome<File> =
        { req, _ ->
            onEnter?.invoke()
            val target = File(dir, ModelFileLayout.finalNameOf(req.id))
            target.writeBytes(content.readBytes())
            Outcome.Ok(target)
        }

    private fun entry(revision: Long, sha: String, size: Long): ModelManifestEntry = ModelManifestEntry(
        modelId = "gemma-4-E2B-it",
        variant = "TEXT_STD",
        revision = revision,
        version = "2026.10.$revision",
        url = "https://example.invalid/model.litertlm",
        sizeBytes = size,
        sha256 = sha,
        minAppVersionCode = 0,
        maxAppVersionCode = null,
        contextWindow = 32_768,
        minRamGb = 8,
        capabilities = listOf("TEXT"),
        state = ModelEntryState.ACTIVE,
    )
}
