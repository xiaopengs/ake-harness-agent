package com.ake.cloudai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ★ SSE 行解析的负向测试（架构 08 §2.5 / §9.2 T6）
 *
 * 覆盖每一种畸形：**粘包 / 半行 / `[DONE]` 缺失 / 空 delta / 超长行 / 中文被切断 / HTML 错误页 / 私有 finish_reason**。
 *
 * 全部是纯 JVM 的字节流 fixture ⇒ 毫秒级。
 * 真机跑 20 分钟才看得出的 bug，正是 `docs/07` 警告过的东西。
 */
class SseDecoderTest {

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    private fun chunk(text: String): String =
        """{"id":"c1","choices":[{"index":0,"delta":{"content":${'"'}$text${'"'}}}]}"""

    // ── 正常流 ────────────────────────────────────────────────────────────

    @Test
    fun normalStreamYieldsDeltasThenFinishThenDone() {
        val d = SseDecoder()
        val events = d.push(bytes("data: ${chunk("he")}\n\ndata: ${chunk("llo")}\n\ndata: [DONE]\n\n"))
        val tail = d.finish()

        val deltas = (events + tail).filterIsInstance<SseEvent.Delta>()
        assertThat(deltas.map { it.text }).containsExactly("he", "llo").inOrder()

        assertThat((events + tail)).contains(SseEvent.Done)
        assertThat(d.sawDone).isTrue()
        assertThat(d.requestId).isEqualTo("c1")
    }

    /** ★ 首个 chunk 只有 `delta.role="assistant"` 而无 content ⇒ **不投**（§2.5 语义 ②）。 */
    @Test
    fun roleOnlyFirstChunkIsNotEmittedAsDelta() {
        val d = SseDecoder()
        val events = d.push(
            bytes(
                """data: {"id":"c1","choices":[{"index":0,"delta":{"role":"assistant"}}]}

""",
            ),
        )
        assertThat(events.filterIsInstance<SseEvent.Delta>()).isEmpty()
        // 它只是一条空 chunk（无 content、无 finish_reason）
        assertThat(events.filterIsInstance<SseEvent.EmptyChunk>()).isNotEmpty()
    }

    /** 部分网关在 `[DONE]` 前会多发一个空的 `data: {}` chunk ⇒ 忽略。 */
    @Test
    fun emptyDataObjectIsIgnored() {
        val d = SseDecoder()
        val events = d.push(bytes("data: {}\n\ndata: [DONE]\n\n"))
        assertThat(events.filterIsInstance<SseEvent.EmptyChunk>()).isNotEmpty()
        assertThat(events).contains(SseEvent.Done)
    }

    // ── 粘包 / 半行 ───────────────────────────────────────────────────────

    /** 粘包：一次 push 里带了两个完整事件。 */
    @Test
    fun twoEventsInOneChunkAreBothDecoded() {
        val d = SseDecoder()
        val events = d.push(bytes("data: ${chunk("a")}\n\ndata: ${chunk("b")}\n\n"))
        assertThat(events.filterIsInstance<SseEvent.Delta>().map { it.text })
            .containsExactly("a", "b").inOrder()
    }

    /** 半行：一行被切成三块 ⇒ 只在最后一块到达时才产出。 */
    @Test
    fun splitLineAcrossChunksDecodesOnlyWhenComplete() {
        val d = SseDecoder()
        val whole = "data: " + chunk("hi") + "\n\n"
        val mid = whole.length / 2

        val first = d.push(bytes(whole.substring(0, mid)))
        assertThat(first).isEmpty()

        val second = d.push(bytes(whole.substring(mid)))
        assertThat(second.filterIsInstance<SseEvent.Delta>().map { it.text })
            .containsExactly("hi")
    }

    /** 逐字节喂 ⇒ 只在换行处产出。 */
    @Test
    fun byteByByteFeedingNeverProducesPartialLine() {
        val d = SseDecoder()
        val whole = "data: " + chunk("ok") + "\n\n"
        val events = whole.toByteArray(Charsets.UTF_8)
            .asList().chunked(1) { it.toByteArray() }
            .flatMap { d.push(it) }

        assertThat(events.filterIsInstance<SseEvent.Delta>().map { it.text }).containsExactly("ok")
    }

    // ── 截断 ───────────────────────────────────────────────────────────────

    /** ★ 无 `[DONE]` 就 EOF ⇒ 产出 EOF_WITHOUT_DONE 标记（§4.4）。 */
    @Test
    fun eofWithoutDoneIsFlagged() {
        val d = SseDecoder()
        d.push(bytes("data: ${chunk("partial")}\n\n"))
        val tail = d.finish()

        assertThat(tail).contains(SseEvent.Malformed(SseDecoder.SHAPE_EOF_WITHOUT_DONE))
        assertThat(d.sawDone).isFalse()
    }

    /** 断在半行中间 ⇒ flush 后无法解析 ⇒ Malformed，但**不崩**。 */
    @Test
    fun eofMidLineIsMalformedNotCrash() {
        val d = SseDecoder()
        d.push(bytes("data: {\"choices\":[{\"delta\":{\"cont"))
        val tail = d.finish()

        assertThat(tail.filterIsInstance<SseEvent.Malformed>()).isNotEmpty()
        assertThat(d.sawDone).isFalse()
    }

    // ── 空 delta ─────────────────────────────────────────────────────────

    /** ★ 收到 `[DONE]` 但 content 全空 ⇒ 没有任何 Delta（§4.4「空 delta」）。 */
    @Test
    fun doneWithNoContentYieldsNoDelta() {
        val d = SseDecoder()
        val events = d.push(
            bytes(
                """data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

""",
            ),
        )
        val tail = d.finish()

        assertThat((events + tail).filterIsInstance<SseEvent.Delta>()).isEmpty()
        val finish = (events + tail).filterIsInstance<SseEvent.Finish>()
        assertThat(finish).hasSize(1)
        assertThat(finish.single().reasonRaw).isEqualTo("stop")
    }

    /** 只收到 usage chunk、无 content ⇒ 同样没有 Delta（§4.4 倒数第 3 行）。 */
    @Test
    fun usageOnlyChunkYieldsNoDelta() {
        val d = SseDecoder()
        val events = d.push(
            bytes(
                """data: {"id":"c1","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":0}}

data: [DONE]

""",
            ),
        )
        assertThat(events.filterIsInstance<SseEvent.Delta>()).isEmpty()
        assertThat(events.filterIsInstance<SseEvent.Usage>()).hasSize(1)
    }

    // ── 超长行 ───────────────────────────────────────────────────────────

    /** 超长行 ⇒ 被丢弃并计数，**不会**把内存打爆。 */
    @Test
    fun overLongLineIsDroppedAndCounted() {
        val reader = SseLineReader(maxLineBytes = 64)
        val lines = reader.push(bytes("data: " + "x".repeat(500) + "\n"))
        val tail = reader.finish()

        assertThat(reader.droppedLongLines).isEqualTo(1)
        // 这一行被整段丢弃 ⇒ 后续仍有完整的一行（空行）可被 flush
        assertThat(lines + tail).isNotEmpty()
    }

    /** 恰好在上限内 ⇒ 不丢。 */
    @Test
    fun lineAtLimitIsKept() {
        val reader = SseLineReader(maxLineBytes = 100)
        val lines = reader.push(bytes("data: " + "y".repeat(90) + "\n"))
        assertThat(reader.droppedLongLines).isEqualTo(0)
        assertThat(lines).hasSize(1)
    }

    // ── 中文 / UTF-8 ────────────────────────────────────────────────────

    /**
     * ★★ 中文被 chunk 边界切断 ⇒ **不乱码**。
     *
     * 这是「字节级缓冲」的判据：若按字符/按行粗暴解码，
     * `你`（E4 BD A0）被切成 `E4 BD` + `A0` ⇒ 会出现替换字符。
     */
    @Test
    fun multiByteCharacterSplitAcrossChunksStaysIntact() {
        val raw = ("data: " + chunk("你好世界") + "\n\n").toByteArray(Charsets.UTF_8)

        // ★ 遍历**每一个**切分点：无论 TCP 在哪里断，解出来的文本都必须完整
        for (cut in 0..raw.size) {
            val d = SseDecoder()
            val events = mutableListOf<SseEvent>()
            events += d.push(raw.copyOfRange(0, cut))
            events += d.push(raw.copyOfRange(cut, raw.size))
            events += d.finish()

            val deltas = events.filterIsInstance<SseEvent.Delta>().map { it.text }
            assertThat(deltas).hasSize(1)
            assertThat(deltas.single()).isEqualTo("你好世界")
            assertThat(deltas.single()).doesNotContain("�")
        }
    }

    // ── 畸形载荷 ─────────────────────────────────────────────────────────

    /** 网关在高负载时返回 HTML 错误页 ⇒ Malformed(NOT_JSON)，且**不保留 body**。 */
    @Test
    fun htmlErrorPageIsMalformedWithoutBody() {
        val d = SseDecoder()
        val events = d.push(bytes("data: <html><body>502 Bad Gateway</body></html>\n\n"))
        val malformed = events.filterIsInstance<SseEvent.Malformed>()
        assertThat(malformed).hasSize(1)
        assertThat(malformed.single().shape).isEqualTo(SseDecoder.SHAPE_NOT_JSON)
        // ★ 形状标签里不含任何服务端原文
        assertThat(malformed.single().shape).doesNotContain("502")
        assertThat(malformed.single().shape).doesNotContain("Bad Gateway")
    }

    /** 带 `error` 键 ⇒ Malformed(ERROR_OBJECT)，**不去读 error.message**。 */
    @Test
    fun errorObjectIsMalformedWithoutMessage() {
        val d = SseDecoder()
        val secretLeak = "sk-live-abcdef123456"
        val events = d.push(
            bytes("""data: {"error":{"message":"bad key $secretLeak","code":"invalid_api_key"}}

"""),
        )
        val malformed = events.filterIsInstance<SseEvent.Malformed>().single()
        assertThat(malformed.shape).isEqualTo(SseDecoder.SHAPE_ERROR_OBJECT)
        assertThat(malformed.shape).doesNotContain(secretLeak)
    }

    /** 私有 finish_reason ⇒ 存原文，不映射、不报错（§4.4 最后一行）。 */
    @Test
    fun unknownFinishReasonIsPreservedVerbatim() {
        val d = SseDecoder()
        val events = d.push(
            bytes(
                """data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"kimi_private_stop_v2"}]}

data: [DONE]

""",
            ),
        )
        val finish = events.filterIsInstance<SseEvent.Finish>().single()
        assertThat(finish.reasonRaw).isEqualTo("kimi_private_stop_v2")
    }

    // ── 行边界 / 编码 ────────────────────────────────────────────────────

    /** `\r\n` ⇒ CR 被吃掉，不会污染 JSON。 */
    @Test
    fun crlfLineEndingIsStripped() {
        val d = SseDecoder()
        val events = d.push(bytes("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"z\"}}]}\r\n\r\n"))
        assertThat(events.filterIsInstance<SseEvent.Delta>().map { it.text }).containsExactly("z")
    }

    /** SSE 注释行（`:` 开头）与未知字段（`event:` / `id:`）⇒ 忽略。 */
    @Test
    fun commentsAndUnknownFieldsAreIgnored() {
        val d = SseDecoder()
        val events = d.push(
            bytes(
                """: keep-alive
event: ping
id: 42
data: {"id":"c1","choices":[{"index":0,"delta":{"content":"q"}}]}

""",
            ),
        )
        assertThat(events.filterIsInstance<SseEvent.Delta>().map { it.text }).containsExactly("q")
    }

    /** 多个 `data:` 行属于同一事件 ⇒ 用 `\n` 连接后再整体解析。 */
    @Test
    fun multipleDataLinesAreJoined() {
        val d = SseDecoder()
        val events = d.push(bytes("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ab\"}}]}\n\n"))
        assertThat(events.filterIsInstance<SseEvent.Delta>().map { it.text }).containsExactly("ab")
    }

    /** `data:` 后跟一个空格 ⇒ 只吃一个（SSE 规范）。 */
    @Test
    fun dataPrefixSpaceIsStrippedExactlyOnce() {
        val d = SseDecoder()
        val events = d.push(bytes("data:  {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"  s\"}}]}\n\n"))
        val delta = events.filterIsInstance<SseEvent.Delta>().single()
        // 第一个空格被吃掉，第二个属于 JSON 字符串内部 ⇒ 保留
        assertThat(delta.text).isEqualTo("  s")
    }
}
