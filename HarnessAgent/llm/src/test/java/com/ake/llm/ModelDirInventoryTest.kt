package com.ake.llm

import com.ake.data.persist.modeldist.ModelEntryState
import com.ake.data.persist.modeldist.ModelFileLayout
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * `ModelFileInventory.probe`（霍承钧 P1 第 2 条）。
 *
 * 它判的是「**盘上有没有这一份内容**」。回滚能不能秒切、会不会退化成重下 2.6 GB，
 * 全看这一个函数准不准 —— 所以三种边界各钉一条用例。
 */
@RunWith(JUnit4::class)
class ModelDirInventoryTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val dir: File get() = tmp.root
    private val inventory: ModelDirInventory get() = ModelDirInventory(dir)

    private val entry: ModelManifestEntry = ModelManifestEntry(
        modelId = "gemma-4-E2B-it",
        variant = "TEXT_STD",
        revision = 7L,
        version = "2026.10.7",
        url = "https://example.invalid/model.litertlm",
        sizeBytes = 1_024L,
        sha256 = "a".repeat(64),
        minAppVersionCode = 0,
        maxAppVersionCode = null,
        contextWindow = 32_768,
        minRamGb = 8,
        capabilities = listOf("TEXT"),
        state = ModelEntryState.ACTIVE,
    )

    private fun archiveFile(): File =
        File(dir, ModelFileLayout.archiveNameOf(entry.modelId, entry.variant, entry.sha256))

    @Test
    fun `归档名命中且字节数一致 ⇒ 认出来了`() {
        archiveFile().apply { writeBytes(ByteArray(entry.sizeBytes.toInt())) }

        val found = inventory.probe(entry)

        assertThat(found).isNotNull()
        assertThat(found!!.name).isEqualTo(archiveFile().name)
        assertThat(found.sizeBytes).isEqualTo(entry.sizeBytes)
    }

    @Test
    fun `字节数不符 ⇒ 视为没有（半截文件不算有）`() {
        archiveFile().apply { writeBytes(ByteArray(512)) }

        assertThat(inventory.probe(entry)).isNull()
    }

    @Test
    fun `文件不存在 ⇒ null`() {
        assertThat(inventory.probe(entry)).isNull()
    }

    @Test
    fun `sha256 不同 ⇒ 是另一个文件的名字（认的是内容身份 不是 revision）`() {
        archiveFile().apply { writeBytes(ByteArray(entry.sizeBytes.toInt())) }

        val otherSha = entry.copy(sha256 = "b".repeat(64))

        assertThat(inventory.probe(otherSha)).isNull()
    }
}
