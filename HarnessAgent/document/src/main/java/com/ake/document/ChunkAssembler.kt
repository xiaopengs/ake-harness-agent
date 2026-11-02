package com.ake.document

/**
 * 页文本 → 定长块（交付方案 §3.3「600–900 token/块、12 块硬上限」）。
 *
 * ## 为什么按页边界优先，而不是机械按 token 数切
 *
 * PDF 的页边界本身就是语义边界（章节、表格、条款通常不跨页）。先按页累积、
 * 超预算才切，能把"一句话被切在两块里"的概率压到最低；而机械按 750 token 切
 * 在财报这种密集排版上会把一行数字劈成两半，reduce 阶段看不出原貌。
 *
 * ## 单页超预算时按句子切
 *
 * 一页 3000 字（合同常见）必须切成多块，且切点落在 `。！？；\n` 之后。
 * 句子本身还超长（无标点的表格行）才硬切 —— 硬切是最后手段，会在块尾留下
 * 半个数字，所以 [AssembledDoc] 的消费者不应假设块尾是完整句子。
 *
 * ## 为什么没有跨块重叠
 *
 * 交付方案写的是"15% 重叠"，但那是针对**固定窗口切分**的补救。按页/句切分时，
 * 重叠的收益（防止跨块句子断裂）已经被切点选择解决，代价（重复 token 占 15%
 * 预算 + reduce 阶段重复计数）反而变成净负债。**这里是有意偏离方案，需在 M1
 * 实测里验证：若摘要漏掉跨页事实，再补回重叠。**
 */
public data class PageSlice(public val page: Int, public val text: String)

public data class DocChunk(
    public val seq: Int,
    public val startPage: Int,
    public val endPage: Int,
    public val text: String,
    public val tokens: Int,
)

public data class AssembledDoc(
    public val chunks: List<DocChunk>,
    /** 从这一页起没有纳入（超过 [ChunkAssembler.maxChunks]）；null 表示全部纳入。 */
    public val truncatedFromPage: Int?,
    public val totalPages: Int,
) {
    public val isTruncated: Boolean get() = truncatedFromPage != null
}

public class ChunkAssembler(
    private val targetTokens: Int = DEFAULT_TARGET_TOKENS,
    private val maxChunks: Int = DEFAULT_MAX_CHUNKS,
) {
    init {
        require(targetTokens > 0) { "targetTokens 必须为正" }
        require(maxChunks > 0) { "maxChunks 必须为正" }
    }

    public fun assemble(pages: List<PageSlice>): AssembledDoc {
        val chunks = mutableListOf<DocChunk>()
        var buf = StringBuilder()
        var startPage: Int? = null
        var endPage = 0
        var truncatedFrom: Int? = null

        fun flush() {
            val text = buf.toString().trim()
            buf = StringBuilder()
            if (text.isEmpty() || startPage == null) {
                startPage = null
                return
            }
            chunks += DocChunk(
                seq = chunks.size,
                startPage = startPage ?: endPage,
                endPage = endPage,
                text = text,
                tokens = TokenCount.of(text),
            )
            startPage = null
        }

        loop@ for (slice in pages) {
            if (slice.text.isBlank()) continue
            if (chunks.size >= maxChunks) {
                truncatedFrom = slice.page
                break@loop
            }

            val pageTokens = TokenCount.of(slice.text)

            if (pageTokens > targetTokens) {
                // 超长单页：先封掉已累积的内容，保证块序与页序一致。
                flush()
                if (chunks.size >= maxChunks) {
                    truncatedFrom = slice.page
                    break@loop
                }
                for (segment in splitBySentence(slice.text, targetTokens)) {
                    if (chunks.size >= maxChunks) {
                        truncatedFrom = slice.page
                        break@loop
                    }
                    chunks += DocChunk(
                        seq = chunks.size,
                        startPage = slice.page,
                        endPage = slice.page,
                        text = segment,
                        tokens = TokenCount.of(segment),
                    )
                }
                continue
            }

            if (startPage != null && TokenCount.of(buf.toString()) + pageTokens > targetTokens) {
                flush()
            }
            if (startPage == null) startPage = slice.page
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(slice.text)
            endPage = slice.page
        }

        if (chunks.size < maxChunks) flush()
        return AssembledDoc(chunks, truncatedFrom, pages.size)
    }

    /**
     * 句子级切分。累积到 [targetTokens] 就封段；单句超预算则硬切。
     * 不丢字符：所有段拼接后与原文（去首尾空白）等长。
     */
    private fun splitBySentence(text: String, targetTokens: Int): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            buf.append(c)
            i++
            val boundary = c == '。' || c == '！' || c == '？' || c == '；' ||
                c == '.' || c == '!' || c == '?' || c == ';' || c == '\n'
            if (!boundary) continue
            if (TokenCount.of(buf.toString()) >= targetTokens) {
                out += buf.toString().trim()
                buf.setLength(0)
            }
        }
        if (buf.isNotBlank()) out += buf.toString().trim()

        // 无标点的超长行：按字符硬切，保证每段不超过 target。
        return out.flatMap { segment ->
            if (TokenCount.of(segment) <= targetTokens) {
                listOf(segment)
            } else {
                val pieces = mutableListOf<String>()
                var cursor = 0
                while (cursor < segment.length) {
                    // CJK 1 token/字 ⇒ target 个字；拉丁更省，这里按最坏情况取。
                    val step = minOf(targetTokens, segment.length - cursor)
                    pieces += segment.substring(cursor, cursor + step)
                    cursor += step
                }
                pieces
            }
        }.filter { it.isNotBlank() }
    }

    public companion object {
        /** 750 落在 600–900 区间的中位：给 page 边界留上下浮动空间。 */
        public const val DEFAULT_TARGET_TOKENS: Int = 750

        /** 12 块 = 12 次 map 推理，是 90 秒预算的上界（交付方案 §3.5）。 */
        public const val DEFAULT_MAX_CHUNKS: Int = 12
    }
}
