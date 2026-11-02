package com.ake.document

import com.ake.core.ports.DocProbeResult
import com.ake.core.ports.DocumentError
import com.ake.core.ports.DocumentSource
import com.ake.core.ports.PageEvent
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 真实 PDF 的抽取测试。**不是 mock** —— 用 PDFBox 现场生成 PDF，再让被测实现解析。
 *
 * 为什么必须用 Robolectric：`pdfbox-android` 的部分类在静态初始化时引用
 * `android.graphics`，纯 JVM 下会 `NoClassDefFoundError`。Robolectric 提供空实现，
 * 使这条测试能在 CI 上跑（不需要模拟器）。
 *
 * ⚠ 它证明的是"能抽到文本、页数对、空白页会跳过"，**不证明** 200 页的内存峰值 ——
 * 那需要真机 `dumpsys meminfo`，见交付方案 §8.3 的 M5。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfBoxDocumentExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cacheDir: File by lazy {
        File(tmp.root, "pdf-cache").apply { mkdirs() }
    }

    private val extractor: PdfBoxDocumentExtractor by lazy {
        PdfBoxDocumentExtractor(cacheDir)
    }

    /**
     * PDFBox-Android 的标准 14 字体度量（AFM）打包在 AAR 的 assets 里，
     * 不初始化就会在 `PDType1Font` 类初始化时抛
     * `resource '.../Times-Roman.afm' not found` —— 且这个异常发生在**生成 PDF** 时，
     * 不在解析时，很容易误判成"解析器坏了"。
     */
    @Before
    fun initPdfBoxResources() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `probe 返回页数并判定为文本型`() = runBlocking {
        // 每页非空白字符必须 > 32 才会被判为文本型；这里刻意写长，避免测试
        // 退化成"断言的是阈值而不是解析能力"。
        val file = writePdf(
            "Page one has enough text to be counted and recognized as a textual document.",
            "Page two also contains a reasonable amount of readable content for probing.",
        )

        val result = extractor.probe(sourceOf(file))

        assertThat(result).isInstanceOf(DocProbeResult.Ok::class.java)
        val profile = (result as DocProbeResult.Ok).profile
        assertThat(profile.pageCount).isEqualTo(2)
        assertThat(profile.isEncrypted).isFalse()
        assertThat(profile.isTextual).isTrue()
        assertThat(profile.avgCharsPerPage).isAtLeast(32)
    }

    @Test
    fun `pages 逐页产出文本且页码从 1 开始`() = runBlocking {
        val file = writePdf("Alpha content", "Beta content", "Gamma content")

        val events = extractor.pages(sourceOf(file)).toList()

        val texts = events.filterIsInstance<PageEvent.Text>()
        assertThat(texts).hasSize(3)
        assertThat(texts.map { it.page }).containsExactly(1, 2, 3).inOrder()
        assertThat(texts[0].content).contains("Alpha")
        assertThat(texts[2].content).contains("Gamma")
        assertThat(texts[0].chars).isGreaterThan(0)
    }

    @Test
    fun `无文本层的页被跳过而不是中断整份文档`() = runBlocking {
        // 第 2 页不加 content stream ⇒ 空页，模拟扫描件中的一页。
        val file = writePdf("First page text", null, "Third page text")

        val events = extractor.pages(sourceOf(file)).toList()

        assertThat(events.filterIsInstance<PageEvent.Text>()).hasSize(2)
        val skipped = events.filterIsInstance<PageEvent.Skipped>()
        assertThat(skipped).hasSize(1)
        assertThat(skipped[0].page).isEqualTo(2)
        assertThat(skipped[0].error).isInstanceOf(DocumentError.NotTextual::class.java)
    }

    @Test
    fun `maxPages 严格截断`() = runBlocking {
        val file = writePdf("p1 text", "p2 text", "p3 text", "p4 text")

        val events = extractor.pages(
            sourceOf(file),
            com.ake.core.ports.PageBudget(maxPages = 2),
        ).toList()

        assertThat(events.filterIsInstance<PageEvent.Text>().map { it.page })
            .containsExactly(1, 2).inOrder()
    }

    // ──────────────────────────────────────────────────────────────

    private fun sourceOf(file: File) = DocumentSource(
        localPath = file.absolutePath,
        displayName = file.name,
        byteSize = file.length(),
    )

    /** [text] 为 null 时生成一页没有任何文本层的空白页。 */
    private fun writePdf(vararg texts: String?): File {
        val doc = PDDocument()
        try {
            texts.forEach { text ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                if (text != null) {
                    PDPageContentStream(doc, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(50f, 700f)
                        stream.showText(text)
                        stream.endText()
                    }
                }
            }
            val file = File(tmp.root, "sample-${System.nanoTime()}.pdf")
            doc.save(file)
            return file
        } finally {
            doc.close()
        }
    }
}
