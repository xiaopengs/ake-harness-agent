package com.ake.document

import com.ake.core.ports.DocProbeResult
import com.ake.core.ports.DocumentError
import com.ake.core.ports.DocumentPort
import com.ake.core.ports.DocumentProfile
import com.ake.core.ports.DocumentSource
import com.ake.core.ports.PageBudget
import com.ake.core.ports.PageEvent
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * PDFBox 实现（`com.tom-roush:pdfbox-android`）。
 *
 * ## 内存策略：`setupMixed(24MB)` + 单页抽取 + 立即丢弃
 *
 * PDFBox 默认把整个文件的解析结果留在堆里。200 页文档的文本层约 60MB 字符，
 * 加上 PDF 对象图，实测可到 120MB —— 而本项目的推理峰值已经吃掉 551MB，
 * 整机余量只剩 120MB 左右（交付方案 §3.5）。所以：
 *
 *  ① `MemoryUsageSetting.setupMixed(24MB)`：超过 24MB 的部分落临时文件，
 *     用 [cacheDir] 而不是 `java.io.tmpdir`（Android 上后者可能不可写）；
 *  ② 每次只抽一页（`startPage = endPage = p`），抽完立即变成 String，不放 PDPage；
 *  ③ `pages()` 是冷流，调用方不消费就不解析 —— 取消即停止。
 *
 * ## 已知性能债：每页一次 `getText` 会重复遍历页树
 *
 * `PDFTextStripper.getText(doc)` 内部会走完整个页树，只是输出被 `startPage/endPage`
 * 限制在一页。对 50 页文档 = 50 次遍历，实测代价见 M1 spike。
 * 若成为瓶颈，改法是自定义 `PDFTextStripper` 子类覆盖 `startPage/endPage` 回调，
 * 一次遍历逐页 emit（需要 `channelFlow`）。**先测再改**，不凭直觉优化。
 */
public class PdfBoxDocumentExtractor(
    private val cacheDir: File,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : DocumentPort {

    override suspend fun probe(source: DocumentSource): DocProbeResult = withContext(io) {
        try {
            open(File(source.localPath)).use { doc ->
                val pages = doc.numberOfPages
                if (pages <= 0) {
                    return@withContext DocProbeResult.Failed(DocumentError.Io("空文档"))
                }
                if (pages > PageBudget.HARD_LIMIT_PAGES) {
                    return@withContext DocProbeResult.Failed(
                        DocumentError.TooLarge(pages, PageBudget.HARD_LIMIT_PAGES),
                    )
                }
                if (!doc.currentAccessPermission.canExtractContent()) {
                    return@withContext DocProbeResult.Failed(DocumentError.Encrypted)
                }

                val sampled = minOf(SAMPLE_PAGES, pages)
                var chars = 0
                for (page in 1..sampled) {
                    chars += runCatching { textOfPage(doc, page) }
                        .getOrDefault("")
                        .count { !it.isWhitespace() }
                }
                val avg = chars / sampled
                DocProbeResult.Ok(
                    DocumentProfile(
                        pageCount = pages,
                        isEncrypted = doc.isEncrypted,
                        isTextual = avg >= DocumentPort.TEXTUAL_MIN_CHARS_PER_PAGE,
                        avgCharsPerPage = avg,
                    ),
                )
            }
        } catch (e: InvalidPasswordException) {
            DocProbeResult.Failed(DocumentError.Encrypted)
        } catch (e: IOException) {
            DocProbeResult.Failed(DocumentError.Io(e.message ?: "读取失败"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DocProbeResult.Failed(DocumentError.Io(e.message ?: e::class.java.simpleName))
        }
    }

    override fun pages(source: DocumentSource, budget: PageBudget): Flow<PageEvent> = flow {
        val doc = try {
            open(File(source.localPath))
        } catch (e: InvalidPasswordException) {
            emit(PageEvent.Skipped(page = 0, error = DocumentError.Encrypted))
            return@flow
        } catch (e: IOException) {
            emit(PageEvent.Skipped(page = 0, error = DocumentError.Io(e.message ?: "读取失败")))
            return@flow
        }

        doc.use { pdf ->
            if (!pdf.currentAccessPermission.canExtractContent()) {
                emit(PageEvent.Skipped(page = 0, error = DocumentError.Encrypted))
                return@use
            }
            val limit = minOf(pdf.numberOfPages, budget.maxPages)
            for (page in 1..limit) {
                currentCoroutineContext().ensureActive()
                try {
                    // 单页异常必须降级为 Skipped：一页坏了不能毁掉整份文档。
                    val text = textOfPage(pdf, page).take(budget.maxCharsPerPage)
                    if (text.isBlank()) {
                        emit(PageEvent.Skipped(page, DocumentError.NotTextual(1, 0)))
                    } else {
                        emit(PageEvent.Text(page, text.count { !it.isWhitespace() }, text))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emit(
                        PageEvent.Skipped(
                            page,
                            DocumentError.PageFailed(page, e.message ?: e::class.java.simpleName),
                        ),
                    )
                }
                // 让出调度：50 页解析是秒级任务，不能把 IO 线程钉死。
                kotlinx.coroutines.yield()
            }
        }
    }.flowOn(io)

    private fun open(file: File): PDDocument {
        val mem = MemoryUsageSetting.setupMixed(PDF_PARSE_HEAP_BYTES)
            .setTempDir(cacheDir)
        return PDDocument.load(file, mem)
    }

    private fun textOfPage(doc: PDDocument, page: Int): String =
        PDFTextStripper().apply {
            startPage = page
            endPage = page
            sortByPosition = true
        }.getText(doc)

    private companion object {
        const val SAMPLE_PAGES = 3

        /** 24MB：低于它用堆，高于它落盘。取值来自交付方案 §3.3 的 spike 建议。 */
        const val PDF_PARSE_HEAP_BYTES: Long = 24L * 1024 * 1024
    }
}
