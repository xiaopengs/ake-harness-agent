package com.ake.cloudai

// =============================================================================
// SSE 行解析 —— **全仓唯一一份**（架构 08 §2.5 / §8.5 的「份数断言」盯住）
//
// ## ★★ 为什么自写而不用 ktor 的 SSE 插件（§2.5 三条理由，摘要）
//
//  ① 各家的「兼容」是**表层**的：部分网关在 `data: [DONE]` 前会多发一个空的
//     `data: {}` chunk；Kimi 会返回 OpenAI 枚举外的 `finish_reason`；
//     部分厂商在负载高时直接返回 **HTML 错误页**。插件对这三种情况的默认行为不可控。
//  ② `stream_options: {"include_usage": true}` 的最后一个 chunk 满足 `choices: []`
//     ⇒ 任何「取 `choices[0]`」的写法都会 IndexOutOfBounds。
//  ③ **可测性**：自写解析器吃的是 `ByteArray` ⇒ JVM 单测里用 fixture 字节流毫秒级覆盖
//     每一种畸形。真机跑 20 分钟才看得出的 bug，正是 `docs/07` 警告过的东西。
//
// ## 字节级（不是字符级）缓冲的必要性
//
// 中文会被 chunk 边界切断。若按字符/按行粗暴解码，半个 UTF-8 序列会变成替换字符 ⇒ 乱码。
// 这里只在**遇到 `\n` 时**才解码一整行 —— 而 UTF-8 的多字节序列**绝不可能跨越 `\n`**
// （`0x0A` 只作为单字节 ASCII 出现，不会出现在任何多字节序列的延续字节里）⇒ 安全。
// =============================================================================

/**
 * 一条 SSE 事件。**sealed ⇒ 消费端的 `when` 必须穷尽**。
 */
internal sealed interface SseEvent {

    /** 新增的一段文本。★ 只投非空的（§2.5 语义 ②）。 */
    public data class Delta(val text: String) : SseEvent

    /** 服务端给出的结束原因。**存原文**，不映射到 enum（Kimi 私有值不丢信息）。 */
    public data class Finish(val reasonRaw: String?) : SseEvent

    /** `choices: []` 的 usage chunk。 */
    public data class Usage(val promptTokens: Int?, val completionTokens: Int?) : SseEvent

    /** 部分网关在 `[DONE]` 前多发的空 chunk（`data: {}`）。忽略。 */
    public data object EmptyChunk : SseEvent

    /** `data: [DONE]`。流的正常终态。 */
    public data object Done : SseEvent

    /** 畸形载荷。★ 不带原文 —— 只带**形状标签**（§6.3 ④）。 */
    public data class Malformed(val shape: String) : SseEvent
}

/**
 * SSE 行读取器：**字节级**增量缓冲。
 *
 * 只吐出**完整**的行（以 `\n` 结尾），半行留在缓冲区里等下一块。
 *
 * @property maxLineBytes 单行上限。超过 ⇒ 该行被丢弃并计数（防恶意/故障服务端把内存打爆）。
 */
internal class SseLineReader(private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES) {

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var inOverflow = false

    /** 因超长而被丢弃的行数。 */
    public var droppedLongLines: Int = 0
        private set

    /** 推入一段字节，返回其中完整的行（已去掉行尾 `\n` 与可选的 `\r`）。 */
    public fun push(chunk: ByteArray): List<String> {
        val out = ArrayList<String>(4)
        for (b in chunk) {
            if (b == NL) {
                out += takeLine()
            } else if (!inOverflow) {
                append(b)
            }
        }
        return out
    }

    /**
     * 流结束：把缓冲区里最后那半行当作一行吐出来（若非空）。
     *
     * ★ 这条是「流被截断但已收内容照常落盘」能成立的前提 ——
     *   不 flush 的话，最后一段文本会连同它的畸形态一起被丢掉。
     */
    public fun finish(): List<String> {
        if (size == 0 && !inOverflow) return emptyList()
        return listOf(takeLine())
    }

    private fun append(b: Byte) {
        if (size == buffer.size) {
            val grown = buffer.copyOf((buffer.size * 2).coerceAtMost(maxLineBytes + 1))
            buffer = grown
        }
        buffer[size++] = b
        if (size > maxLineBytes) {
            inOverflow = true
            droppedLongLines++
            size = 0
        }
    }

    private fun takeLine(): String {
        var end = size
        if (end > 0 && buffer[end - 1] == CR) end--
        val text = String(buffer, 0, end, Charsets.UTF_8)
        size = 0
        inOverflow = false
        return text
    }

    public companion object {
        public const val NL: Byte = 0x0A
        public const val CR: Byte = 0x0D
        public const val DEFAULT_MAX_LINE_BYTES: Int = 1024 * 1024
        private const val INITIAL_CAPACITY: Int = 8 * 1024
    }
}

/**
 * OpenAI `/chat/completions` 流式响应的增量解码器。
 *
 * 输入任意切分的字节流，输出 [SseEvent] 序列。
 * **不持有任何网络状态** ⇒ 可在 JVM 上用 fixture 字节流毫秒级跑全部畸形用例。
 */
internal class SseDecoder(
    private val json: kotlinx.serialization.json.Json = WireJson,
) {

    private val reader = SseLineReader()
    private val data = StringBuilder()
    private var sawData = false

    /** 是否已经收到过 `data: [DONE]`。★ 流结束时为 false ⇒ 截断（§4.4）。 */
    public var sawDone: Boolean = false
        private set

    /** 累计收到的 content 字符数（用于空 delta 判定）。 */
    public var contentChars: Int = 0
        private set

    /** 最近一次 usage（服务端真值，用于校准估算）。 */
    public var lastUsage: SseEvent.Usage? = null
        private set

    /** 服务端返回的 `id`（对账用，§9.4 ③）。★ 不是秘密，可落审计。 */
    public var requestId: String? = null
        private set

    public fun push(chunk: ByteArray): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        reader.push(chunk).forEach { out += onLine(it) }
        return out
    }

    /**
     * 流结束。
     *
     * @return flush 出来的残余事件，**外加**（若从未见过 `[DONE]`）一条
     *         [SseEvent.Malformed] 标记 `EOF_WITHOUT_DONE`。
     */
    public fun finish(): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        reader.finish().forEach { out += onLine(it) }
        out += onLine("")          // ★ 显式收尾：兼容「最后一个事件后没有空行」的服务端
        if (!sawDone) out += SseEvent.Malformed(SHAPE_EOF_WITHOUT_DONE)
        return out
    }

    private fun onLine(line: String): List<SseEvent> {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return emptyList()          // SSE 注释
        val idx = line.indexOf(':')
        if (idx < 0) return emptyList()
        val field = line.substring(0, idx)
        var value = line.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1) // SSE 规定只吃一个空格
        if (field != FIELD_DATA) return emptyList()           // event/id/retry 一律忽略
        if (sawData) data.append('\n')                        // 多 data 行 ⇒ 用 \n 连接
        data.append(value)
        sawData = true
        return emptyList()
    }

    private fun flush(): List<SseEvent> {
        if (!sawData) return emptyList()
        val raw = data.toString().trim()
        data.clear()
        sawData = false
        if (raw.isEmpty()) return emptyList()
        if (raw == DONE) { sawDone = true; return listOf(SseEvent.Done) }
        return decodeChunk(raw)
    }

    private fun decodeChunk(raw: String): List<SseEvent> {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return listOf(SseEvent.Malformed(SHAPE_NOT_JSON))

        // ★★ 只看 error **键是否存在**，绝不把它的 message 取出来 ——
        //    服务端原文里可能有 URL、URL 里可能有 key（§6.3 ⑤⑥）。
        if (element.jsonObjectOrNull()?.containsKey(FIELD_ERROR) == true) {
            return listOf(SseEvent.Malformed(SHAPE_ERROR_OBJECT))
        }

        val chunk = runCatching {
            WireJson.decodeFromJsonElement(ChatChunk.serializer(), element)
        }.getOrNull() ?: return listOf(SseEvent.Malformed(SHAPE_BAD_SHAPE))

        chunk.id?.let { requestId = it }

        val out = mutableListOf<SseEvent>()

        // ★ `choices: []` 的 usage chunk —— 「取 choices[0]」会 IndexOutOfBounds（§2.5 ②）
        if (chunk.choices.isEmpty()) {
            val u = chunk.usage ?: return listOf(SseEvent.EmptyChunk)
            lastUsage = SseEvent.Usage(u.promptTokens, u.completionTokens)
            return listOf(SseEvent.Usage(u.promptTokens, u.completionTokens))
        }

        val choice = chunk.choices.first()
        // ★ 首个 chunk 通常只有 `delta.role="assistant"` 而无 content ⇒ 不投（§2.5 语义 ②）
        val content = choice.delta?.content
        if (!content.isNullOrEmpty()) {
            contentChars += content.length
            out += SseEvent.Delta(content)
        }
        choice.finishReason?.let { out += SseEvent.Finish(it) }
        chunk.usage?.let { u ->
            val ev = SseEvent.Usage(u.promptTokens, u.completionTokens)
            lastUsage = ev
            out += ev
        }
        if (out.isEmpty()) out += SseEvent.EmptyChunk
        return out
    }

    public companion object {
        public const val DONE: String = "[DONE]"
        public const val SHAPE_EOF_WITHOUT_DONE: String = "eof-without-done"
        public const val SHAPE_NOT_JSON: String = "not-json"
        public const val SHAPE_ERROR_OBJECT: String = "error-object"
        public const val SHAPE_BAD_SHAPE: String = "bad-shape"
        private const val FIELD_DATA: String = "data"
        private const val FIELD_ERROR: String = "error"
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): kotlinx.serialization.json.JsonObject? =
    this as? kotlinx.serialization.json.JsonObject
