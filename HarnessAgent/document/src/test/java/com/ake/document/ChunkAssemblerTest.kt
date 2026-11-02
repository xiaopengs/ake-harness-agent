package com.ake.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 分块算法的纯 JVM 测试（不需要 Robolectric / PDFBox）。
 *
 * 这些断言是 M1 的"可证伪"部分：块数、页码区间、截断位置都能在毫秒内验证，
 * 而 PDF 抽取的正确性另由 [PdfBoxDocumentExtractorTest] 覆盖 —— 两者分开，
 * 是为了让"算法错"和"解析器错"在 CI 里一眼可辨。
 */
class ChunkAssemblerTest {

    private val assembler = ChunkAssembler()

    @Test
    fun `短页合并为一块`() {
        val pages = (1..3).map { PageSlice(it, "甲".repeat(100)) }

        val doc = assembler.assemble(pages)

        assertThat(doc.chunks).hasSize(1)
        assertThat(doc.chunks[0].startPage).isEqualTo(1)
        assertThat(doc.chunks[0].endPage).isEqualTo(3)
        assertThat(doc.isTruncated).isFalse()
    }

    @Test
    fun `跨页累积超预算时封块`() {
        // 每页 300 字 ≈ 318 token（含 1.06 系数）⇒ 3 页 954 > 750，必须切成两块。
        val pages = (1..3).map { PageSlice(it, "乙".repeat(300)) }

        val doc = assembler.assemble(pages)

        assertThat(doc.chunks).hasSize(2)
        assertThat(doc.chunks[0].startPage).isEqualTo(1)
        assertThat(doc.chunks[0].endPage).isEqualTo(2)
        assertThat(doc.chunks[1].startPage).isEqualTo(3)
        assertThat(doc.chunks.map { it.seq }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `单页超长按句切分且不丢字`() {
        val sentence = "丙".repeat(49) + "。"
        val page = PageSlice(1, sentence.repeat(60))   // 3000 字，60 句

        val doc = assembler.assemble(listOf(page))

        assertThat(doc.chunks.size).isAtLeast(3)
        // 所有块都来自第 1 页，且拼接后字符数与原文一致（只差被 trim 的空白）
        assertThat(doc.chunks.all { it.startPage == 1 && it.endPage == 1 }).isTrue()
        val joined = doc.chunks.joinToString("") { it.text }
        assertThat(joined.replace("\n", "").length).isEqualTo(page.text.length)
        // 单块不得超过目标预算的 1.3 倍（硬切按 750 字，加上 trim 不会超）
        assertThat(
            doc.chunks.all { it.tokens <= (ChunkAssembler.DEFAULT_TARGET_TOKENS * 1.3).toInt() },
        ).isTrue()
    }

    @Test
    fun `超过块上限时截断并记录起始页`() {
        // 20 页 × 500 字 ≈ 10600 token ⇒ 理论上 14 块，必须停在 12。
        val pages = (1..20).map { PageSlice(it, "丁".repeat(500)) }

        val doc = assembler.assemble(pages)

        assertThat(doc.chunks).hasSize(ChunkAssembler.DEFAULT_MAX_CHUNKS)
        assertThat(doc.isTruncated).isTrue()
        assertThat(doc.truncatedFromPage).isNotNull()
        assertThat(doc.totalPages).isEqualTo(20)
        // 截断点必须在最后一页之前，否则等于没截
        assertThat(doc.truncatedFromPage!!).isLessThan(20)
    }

    @Test
    fun `空白页被跳过`() {
        val pages = listOf(
            PageSlice(1, "   "),
            PageSlice(2, "戊".repeat(100)),
            PageSlice(3, ""),
        )

        val doc = assembler.assemble(pages)

        assertThat(doc.chunks).hasSize(1)
        assertThat(doc.chunks[0].startPage).isEqualTo(2)
        assertThat(doc.chunks[0].text).isNotEmpty()
    }

    @Test
    fun `token 估算对中文与英文给出保守值`() {
        assertThat(TokenCount.of("中".repeat(100))).isAtLeast(100)
        assertThat(TokenCount.of("a".repeat(360))).isLessThan(120)
        assertThat(TokenCount.of("")).isEqualTo(0)
    }
}
