package com.ake.data.persist.modeldist

import com.ake.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 本地模型的手动导入（任务 B）。
 *
 * ## 本文件最关键的一条断言：`discoveredByProbe()` 那一组
 *
 * 「能不能用」的判据不是我们说了算，而是 `:llm` 的 `ModelDirInventory.probe()`：
 * 它只认 `modelsDir/<modelId>_<variant>_<sha256 前 16 位>.litertlm`，且字节数必须相符。
 * 所以这里逐字段复刻了它的判据（名字 + isFile + length），**没有任何额外宽松** ——
 * 一旦导入的落点歪了，这条断言会比任何人先变红。
 *
 * ⚠ 它是复刻而不是调用：`:llm` 的实现不能从 `:data` 反向引入（那是一条反向的依赖边）。
 *   真正端到端的确认需要 `:llm` 侧补一条测试（owner：邝随行），已在回传里点名。
 *
 * ## 四条失败路径都必须「一个字节的脏数据都不留」
 *
 * 合法成功 / 长度损坏 / 摘要不符 / 空间不足 —— 后三条除了断言 `Outcome.Err`，
 * 还要断言 `modelsDir` 里**除了已有的东西之外什么都没多出来**。
 */
@RunWith(JUnit4::class)
class ModelFileImporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    /** 刻意与源文件分根：modelsDir 里多出任何一个文件都该让断言变红。 */
    private val modelsDir: File get() = File(tmp.root, "models")

    private fun modelsFiles(): List<File> = modelsDir.listFiles()?.toList() ?: emptyList()

    private val goodContent: ByteArray = ByteArray(MODEL_BYTES.toInt()) { (it % 251).toByte() }

    /** ★ 摘要必须是内容的真实摘要 —— 用一串 "bbb..." 当期望值的测试永远只会走到失败分支。 */
    private val entry = ModelDistFixtures.entry(
        revision = 7,
        sha256 = sha256Of(goodContent),
        sizeBytes = MODEL_BYTES,
    )

    private fun importer(free: Long = Long.MAX_VALUE / 4): ModelFileImporter =
        ModelFileImporter(modelsDir = modelsDir, freeBytes = { free })

    /** `:llm` 的 `ModelDirInventory.probe()` 的判据，逐字段对齐。 */
    private fun discoveredByProbe(e: ModelManifestEntry): File? {
        val name = ModelFileLayout.archiveNameOf(e.modelId, e.variant, e.sha256)
        val f = File(modelsDir, name)
        if (!f.isFile) return null
        if (f.length() != e.sizeBytes) return null
        return f
    }

    private var srcSeq = 0

    private fun sourceIn(content: ByteArray = goodContent): File {
        val dir = tmp.newFolder("src${srcSeq++}")
        val f = File(dir, "downloaded.litertlm")
        f.writeBytes(content)
        return f
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ─────────────────────────────────────────────── ① 合法文件导入成功

    @Test
    fun `合法文件导入成功且落在 probe 认得出的位置上`() = runBlocking {
        val source = sourceIn()
        val expected = File(modelsDir, ModelFileLayout.archiveNameOf(entry.modelId, entry.variant, entry.sha256))

        val result = importer().import(entry, source)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val receipt = (result as Outcome.Ok).value
        assertThat(receipt.fileName).isEqualTo(expected.name)
        assertThat(receipt.sizeBytes).isEqualTo(MODEL_BYTES)
        assertThat(receipt.sha256).isEqualTo(sha256Of(goodContent))
        assertThat(receipt.copied).isTrue()

        // ★★ 关键判据：ModelSession 那条加载路径不需要任何改动就能认出它
        assertThat(discoveredByProbe(entry)).isEqualTo(expected)
        // 导入不得留下任何 .part —— 半成品若留在盘上会被当成「已经有了」
        assertThat(modelsFiles().any { it.name.endsWith(".part") }).isFalse()
    }

    @Test
    fun `重复导入同一份文件不再拷一次`() = runBlocking {
        assertThat(importer().import(entry, sourceIn())).isInstanceOf(Outcome.Ok::class.java)
        // 幂等：第二次连预检都不用过就是「已经有」
        assertThat(importer().isPresent(entry)).isTrue()

        val second = importer().import(entry, sourceIn())

        val receipt = (second as Outcome.Ok).value
        assertThat(receipt.copied).isFalse()
    }

    @Test
    fun `源文件不存在时被拒`() = runBlocking {
        val result = importer().import(entry, File(tmp.root, "nope.litertlm"))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_SOURCE_MISSING)
        assertThat(modelsFiles()).isEmpty()
    }

    // ─────────────────────────────────────────────── ② 长度损坏

    @Test
    fun `截断的源文件被拒且一个字节都不落地`() = runBlocking {
        val truncated = File(tmp.newFolder("src2"), "truncated.litertlm")
        truncated.writeBytes(goodContent.copyOf(goodContent.size / 2))

        val result = importer().import(entry, truncated)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_SIZE_MISMATCH)
        assertThat(modelsFiles()).isEmpty()
    }

    @Test
    fun `比期望更长的源文件被拒且不留残片`() = runBlocking {
        val longer = File(tmp.newFolder("src3"), "longer.litertlm")
        longer.writeBytes(goodContent + byteArrayOf(9, 9, 9))

        // 文件的 File 重载会先验长度 ⇒ SIZE_MISMATCH；这里走流重载，验证拷贝过程中的止损
        val result = importer().importStream(entry) { longer.inputStream() }

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_SIZE_MISMATCH)
        assertThat(modelsFiles()).isEmpty()
    }

    // ─────────────────────────────────────────────── ③ sha256 不匹配

    @Test
    fun `sha256 与 manifest 不符的文件被拒且 part 被清掉`() = runBlocking {
        // 长度一致、内容不同 —— 「静默损坏」的现实形态
        val tampered = goodContent.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()
        val source = sourceIn(tampered)

        val result = importer().import(entry, source)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        val err = (result as Outcome.Err).error
        assertThat(err.code).isEqualTo(ModelDistErrorCodes.IMPORT_CHECKSUM_MISMATCH)
        assertThat(err.reasonKey).isEqualTo("err_model_import_checksum_mismatch")
        // ★ 没留 .part：留下来就会被 probe 当成「这份模型已经在盘上了」
        assertThat(modelsFiles().filter { it.name.endsWith(".part") }).isEmpty()
        assertThat(discoveredByProbe(entry)).isNull()
    }

    // ─────────────────────────────────────────────── ④ 磁盘不足

    @Test
    fun `磁盘不足时拒绝且一个字节都不写`() = runBlocking {
        val source = sourceIn()

        val result = importer(free = MODEL_BYTES - 1).import(entry, source)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_NO_SPACE)
        // ★★ 磁盘满了 ⇒ 连 .part 都不该创建：写下哪怕一个字节都是新的脏数据
        assertThat(modelsFiles()).isEmpty()
        assertThat(tmp.root.listFiles()!!.any { it.name.endsWith(".part") }).isFalse()
    }

    @Test
    fun `读取中断时被拒且不留残片`() = runBlocking {
        // 「不是文件」是读取失败最容易被构造的形态：它是 IOException，与磁盘坏道同路径。
        val aDirectory = tmp.newFolder("src4")

        val result = importer().import(entry, aDirectory)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_SOURCE_MISSING)
        assertThat(modelsFiles()).isEmpty()
    }

    @Test
    fun `流在中途抛错时被拒且 part 被清掉`() = runBlocking {
        val source = sourceIn()
        val booby: () -> java.io.InputStream = {
            object : java.io.FilterInputStream(source.inputStream()) {
                private var seen = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    // 前两块正常，之后抛 —— 模拟 USB 拔了 / SD 卡读错了
                    if (seen++ >= 1) throw java.io.IOException("device gone")
                    return super.read(b, off, len)
                }
            }
        }

        val result = importer().importStream(entry, booby)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_IO_FAILED)
        // ★ 半截的 part 必须已经不存在
        assertThat(modelsFiles()).isEmpty()
    }

    private companion object {
        /** 200 KB —— 足够让 .part 真实落盘又不必在单测里搬运 GB。 */
        const val MODEL_BYTES: Long = 200L * 1024
    }
}
