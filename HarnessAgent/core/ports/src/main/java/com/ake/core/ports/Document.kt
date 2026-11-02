package com.ake.core.ports

import kotlinx.coroutines.flow.Flow

/**
 * 文档 Port（交付方案 §3.4）。
 *
 * ## 为什么 `localPath: String` 而不是 `Uri`
 *
 * `:core:ports` 是纯 JVM 模块，引 `android.net.Uri` 会让它变 Android 库，
 * 连带把 `:core:ports` 的毫秒级单测拖进 Robolectric。Uri → 本地文件的解析
 * 由 `:app` 的 `FileAccessPort` 实现完成，Port 这一层只认"已经在磁盘上的文件"。
 * 这样做的代价是：调用方必须先落盘（>20MB 才拷，≤20MB 可直接给 fd 路径），
 * 收益是整个文档链路可以在 JVM 上跑。
 *
 * ## 为什么 `pages()` 返回 `Flow` 而不是 `List`
 *
 * 200 页 PDF 的全文约 60MB 字符（≈120MB 堆）。一次性返回 `List<String>` 等于把
 * 堆峰值交给 GC 运气，而本项目的内存预算已经被 2.77GB 模型压到只剩 120MB 余量。
 * 流式还让"逐页落库 + 可取消"成为默认行为，而不是额外机制。
 */
public sealed interface DocumentError {
    /** 有密码。V1 不支持输入密码，直接判不可处理。 */
    public data object Encrypted : DocumentError

    /** 无文本层（扫描件）：采样页的非空白字符数低于阈值。 */
    public data class NotTextual(public val sampledPages: Int, public val avgChars: Int) : DocumentError

    /** 页数超过硬上限（[PageBudget.HARD_LIMIT_PAGES]）。 */
    public data class TooLarge(public val pages: Int, public val limit: Int) : DocumentError

    /** 单页解析失败：跳过该页，不中断整个文档。 */
    public data class PageFailed(public val page: Int, public val reason: String) : DocumentError

    /** IO / 文件损坏 / 权限失效。 */
    public data class Io(public val reason: String) : DocumentError
}

/**
 * 已落盘的文档来源。[byteSize] 由 `FileAccessPort` 填，用于预检（>200MB 直接拒）。
 */
public data class DocumentSource(
    public val localPath: String,
    public val displayName: String,
    public val byteSize: Long,
)

public data class DocumentProfile(
    public val pageCount: Int,
    public val isEncrypted: Boolean,
    /** 采样页平均非空白字符数 ≥ [DocumentPort.TEXTUAL_MIN_CHARS_PER_PAGE] 即判为文本型。 */
    public val isTextual: Boolean,
    public val avgCharsPerPage: Int,
)

public sealed interface DocProbeResult {
    public data class Ok(public val profile: DocumentProfile) : DocProbeResult
    public data class Failed(public val error: DocumentError) : DocProbeResult
}

/** `pages()` 的流元素：要么一页文本，要么一页被跳过（附原因）。 */
public sealed interface PageEvent {
    public data class Text(
        public val page: Int,
        public val chars: Int,
        public val content: String,
    ) : PageEvent

    public data class Skipped(
        public val page: Int,
        public val error: DocumentError,
    ) : PageEvent
}

/**
 * 解析预算。默认 50 页不是拍脑袋：20 页摘要要 ≤90s，12 块是推理上限；
 * 50 页是"能在 90 秒内抽完且不撑爆堆"的经验上界，200 页是绝对拒绝线。
 */
public data class PageBudget(
    public val maxPages: Int = 50,
    public val maxCharsPerPage: Int = 20_000,
) {
    public companion object {
        public val DEFAULT: PageBudget = PageBudget()

        /** 超过此页数直接拒绝，不做"只解析前 N 页"的静默截断 —— 那是产品决策，不是解析器的。 */
        public const val HARD_LIMIT_PAGES: Int = 200
    }
}

public interface DocumentPort {
    /**
     * 只读前 3 页做能力探测。**不解析全文** —— 探测阶段的成本必须是常数级，
     * 否则"选了文件才知道是扫描件"要等 6 秒。
     */
    public suspend fun probe(source: DocumentSource): DocProbeResult

    /**
     * 逐页产出。实现必须保证：
     * ① 页间可取消（`ensureActive()`）；
     * ② 单页失败只 emit [PageEvent.Skipped]，不抛；
     * ③ 不持有已解析页的对象引用（PDFBox 的 PDPage 很重）。
     */
    public fun pages(source: DocumentSource, budget: PageBudget = PageBudget.DEFAULT): Flow<PageEvent>

    public companion object {
        /** 采样页平均非空白字符低于此值 ⇒ 判为扫描件。取自交付方案 §3.3。 */
        public const val TEXTUAL_MIN_CHARS_PER_PAGE: Int = 32
    }
}
