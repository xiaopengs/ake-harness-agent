package com.ake.data.persist.store

import com.ake.core.common.ManualClock
import com.ake.core.common.Outcome
import com.ake.core.ports.MessageStatus
import com.ake.data.persist.db.dao.ChatMessageDao
import com.ake.data.persist.db.entity.ChatMessageEntity
import com.ake.data.persist.fake.FakeAgentProfileDao
import com.ake.data.persist.fake.FakeFieldCipher
import com.ake.data.persist.fake.FakeTaskDao
import com.ake.data.persist.fake.UnsupportedChatMessageDao
import com.ake.data.persist.fake.UnsupportedChatSessionDao
import com.ake.data.persist.fake.UnsupportedSessionDao
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.write.ChatMessageWriter
import com.ake.data.persist.write.PersistenceMetrics
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * ★★ 冷启收敛的终态语义：遗留 `STREAMING` → **`FAILED`**，不是 `ABORTED`。
 *
 * ## 为什么这条测试值得单独存在
 *
 * `ABORTED` = 用户按了停止（**正常行为**，不该进缺陷统计）；
 * `FAILED` = 链路没走完（**缺陷**，必须能被捞出来）。
 * 进程被杀（LMK / native abort / 崩溃）留下的 `STREAMING` 一旦收敛成 `ABORTED`，
 * 就等于把每一次「进程死了」伪装成「用户主动停的」—— 将来查
 * 「这条消息为什么没回」时，这类事件在数据里**根本不出现**。
 * 这是一个「测试全绿、功能正常、统计全错」的缺陷形态，只能靠断言语义本身防住。
 *
 * ## 为什么断言分三层（而不是一个假 DAO 跑一遍就算完）
 *
 * `:data` 是 androidLibrary，纯 JVM 单测起不了真 Room/SQLite（同
 * `AkeMigrationSchemaTest` 的困境）。于是「收敛到哪个状态」这个**唯一决定语义的字符串**
 * 如果只写死在 `@Query` 里，就永远无法被断言 —— 用一个自己复述答案的假 DAO 去测，
 * 改错了照样绿。所以这条链被拆成三段，各段都盯住一截：
 *
 * | 层 | 断言对象 | 防的是什么 |
 * |---|---|---|
 * | ① SQL | [ChatMessageDao.SWEEP_SQL] 的**真实字面值** | Room 实际执行的语句里写着 `FAILED`，且 `ABORTED` 不出现 |
 * | ② 枚举 | [ChatMessageDao.SWEEP_TERMINAL_STATUS] vs `MessageStatus.FAILED.name` | SQL 里的字符串与枚举各自漂移（改了一边忘了另一边） |
 * | ③ 行为 | `sweepStreaming()` 之后每行的状态 | Store 没有在中途重映射/短路，且**已成终态的行一行都不被覆盖** |
 *
 * ① 之所以成立，靠的是常量进了注解：Room 会把这个 `const val` 内联进生成的
 * `ChatMessageDao_Impl`（`val _sql = "UPDATE chat_message SET status = 'FAILED' ..."`），
 * 所以断言常量 = 断言真机上跑的那条 SQL。
 *
 * ④ 另有两条反向保险：用户主动 `stop()` 走的 [ChatSessionStoreImpl.commitAssistant]
 * 仍然原样写 `ABORTED`（本轮不许把另一个改坏），以及 sweep 失败仍返回 0 不抛
 * （冷启不能被一次 sweep 堵死）。
 */
@RunWith(JUnit4::class)
class ChatSweepStreamingTest {

    // ── 被测对象的装配：只给「本轮真的会走到」的依赖，其余一律 unsupported
    //    （返回空值会伪造「已覆盖」的假象，见 ChatDaoFakes 类注释）

    private val messages = SweepFakeMessageDao()
    private val gate = PersistenceRedactionGate(FakeFieldCipher())
    private val clock = ManualClock(1_000L)

    private val writer = ChatMessageWriter(
        dao = messages,
        gate = gate,
        metrics = PersistenceMetrics(),
        writerContext = Dispatchers.Unconfined,
    )

    private val store = ChatSessionStoreImpl(
        sessions = UnsupportedChatSessionDao(),
        messages = messages,
        tasks = FakeTaskDao(),
        agentSessions = UnsupportedSessionDao(),
        profiles = FakeAgentProfileDao(),
        writer = writer,
        gate = gate,
        clock = clock,
        // 事务在本测试里退化为直接执行：真事务语义不是本类的断言对象。
        transaction = { block -> block() },
    )

    /** 写入器在 `init` 里起了一个 drain 循环，用例结束把它停掉（否则线程会活到 JVM 退出）。 */
    @After
    fun tearDown() {
        writer.stopAndDropQueued()
    }

    // ──────────────────────────────────────────── ① SQL 层

    /**
     * ★ 负向验证锚点：把 [ChatMessageDao.SWEEP_TERMINAL_STATUS] 改回 `"ABORTED"`，
     * 本条与下面两条会一起红 —— 因为 SQL 是由该常量拼出来的。
     */
    @Test
    fun `sweep 的 SQL 收敛到 FAILED 且不再出现 ABORTED`() {
        assertThat(ChatMessageDao.SWEEP_SQL).contains("status = 'FAILED'")
        assertThat(ChatMessageDao.SWEEP_SQL).doesNotContain("ABORTED")
    }

    /**
     * 守卫必须留着：sweep 只允许碰中间态。
     *
     * 它同时是「用户主动 `stop()` 的路径不会被 sweep 改写」的**结构性**保证 ——
     * 已 `ABORTED` 的行连 WHERE 都进不去，不依赖调用方自觉。
     */
    @Test
    fun `sweep 只碰 STREAMING 行，已成终态的行进不了 WHERE`() {
        assertThat(ChatMessageDao.SWEEP_SQL).contains("WHERE status = 'STREAMING'")
    }

    // ──────────────────────────────────────────── ② 枚举层

    /** SQL 里的那个字符串必须就是 `MessageStatus.FAILED` —— 两边不许各自漂移。 */
    @Test
    fun `收敛目标态与 MessageStatus FAILED 是同一个词`() {
        assertThat(ChatMessageDao.SWEEP_TERMINAL_STATUS).isEqualTo(MessageStatus.FAILED.name)
        assertThat(ChatMessageDao.SWEEP_TERMINAL_STATUS).isNotEqualTo(MessageStatus.ABORTED.name)
    }

    // ──────────────────────────────────────────── ③ 行为层

    /**
     * ★ 主用例：冷启之后，遗留 `STREAMING` 收敛为 `FAILED`；
     * 用户主动停的 `ABORTED` 与已完成的 `COMPLETE` **逐行不变**。
     *
     * 一条会话里三种行同时存在是真实形态：上一次进程活着时用户停过一条（ABORTED），
     * 聊完一条（COMPLETE），最后一条生成到一半被杀（STREAMING）。sweep 只该动最后那条。
     */
    @Test
    fun `遗留 STREAMING 收敛为 FAILED，用户主动停的 ABORTED 不被动到`() = runBlocking {
        messages.put("m-user-aborted", MessageStatus.ABORTED)
        messages.put("m-complete", MessageStatus.COMPLETE)
        messages.put("m-killed-1", MessageStatus.STREAMING)
        messages.put("m-killed-2", MessageStatus.STREAMING)

        val converged = store.sweepStreaming()

        assertThat(converged).isEqualTo(2)
        assertThat(messages.statusOf("m-killed-1")).isEqualTo(MessageStatus.FAILED.name)
        assertThat(messages.statusOf("m-killed-2")).isEqualTo(MessageStatus.FAILED.name)

        // ★ 反向：用户主动停的那条仍然是 ABORTED —— 不许被「顺手统一」成 FAILED
        assertThat(messages.statusOf("m-user-aborted")).isEqualTo(MessageStatus.ABORTED.name)
        assertThat(messages.statusOf("m-complete")).isEqualTo(MessageStatus.COMPLETE.name)

        // 收敛后再跑一次：0 行（幂等，稳态下不该反复记 WARN）
        assertThat(store.sweepStreaming()).isEqualTo(0)
    }

    /**
     * ★ 反向保险：用户主动 `stop()` 的路径**仍然**写 `ABORTED`。
     *
     * `:chat` 的 `ChatLoop` 把 `USER_ABORT` 映射成 `MessageStatus.ABORTED` 后交给
     * `commitAssistant`；本层唯一要守的性质是**原样透传**（不因为改了 sweep
     * 就顺手在 Store 里把 ABORTED 也改写掉 —— 那会把「用户主动停」变成「缺陷」，
     * 是同一个错误的反方向）。
     */
    @Test
    fun `用户主动 stop 的终态提交仍然原样写 ABORTED`() = runBlocking {
        messages.put("m-partial", MessageStatus.STREAMING)

        val result = store.commitAssistant(
            messageId = "m-partial",
            status = MessageStatus.ABORTED,
            text = "写了一半就停",
            promptTokens = 10,
            decodeTokens = 5,
            kvCacheTokensAfter = 15,
            redactionCount = 0,
        )

        assertThat(result is Outcome.Ok).isTrue()
        assertThat(messages.committedStatuses).containsExactly(MessageStatus.ABORTED.name)
        assertThat(messages.statusOf("m-partial")).isEqualTo(MessageStatus.ABORTED.name)
    }

    /**
     * sweep 失败返回 0 而不是抛：冷启不该被一次 DB 异常堵死。
     *
     * 但它必须与「收敛了 0 行」在日志上可区分 —— 见 `ChatSessionStoreImpl.sweepStreaming()`
     * 里的两条分支（这正是本轮把 `runCatching{}.getOrDefault()` 拆成 `onSuccess/onFailure` 的原因）。
     */
    @Test
    fun `sweep 抛异常时返回 0 而不是把冷启炸掉`() = runBlocking {
        messages.sweepThrows = true
        assertThat(store.sweepStreaming()).isEqualTo(0)
    }
}

/**
 * 内存版 `chat_message`，只实现本文件用到的两个写操作。
 *
 * ★ 它的 `sweepStreaming()` 刻意引用 [ChatMessageDao.SWEEP_TERMINAL_STATUS] 而不是
 * 自己写一个 `"FAILED"` 字面量：这样「收敛到哪个状态」只有一个事实来源，
 * 假 DAO 不会替真 SQL 复述答案。（真 SQL 的正确性由上面 ①② 两条断言负责 ——
 * 假 DAO 不是它的证据，也当不了它的证据。）
 */
private class SweepFakeMessageDao : UnsupportedChatMessageDao() {

    val rows: LinkedHashMap<String, ChatMessageEntity> = LinkedHashMap()

    /** `commit()` 收到的状态原样记下来 —— 「有没有被 Store 重映射」只能从这里看。 */
    val committedStatuses: MutableList<String> = ArrayList()

    var sweepThrows: Boolean = false

    fun put(id: String, status: MessageStatus) {
        rows[id] = messageRow(id, status)
    }

    fun statusOf(id: String): String = rows.getValue(id).status

    override suspend fun sweepStreaming(): Int {
        if (sweepThrows) error("simulated DB failure")
        // 与真 SQL 同一条规则：只碰 STREAMING（WHERE 守卫），目标态取同一个常量
        val victims = rows.values.filter { it.status == ChatMessageEntity.STATUS_STREAMING }
        victims.forEach { rows[it.id] = it.copy(status = ChatMessageDao.SWEEP_TERMINAL_STATUS) }
        return victims.size
    }

    override suspend fun commit(
        messageId: String,
        status: String,
        contentEnc: ByteArray?,
        preview: String,
        payloadState: String,
        payloadFile: String?,
        payloadCodec: String?,
        payloadBytes: Int,
        promptTokens: Int,
        decodeTokens: Int,
        kvAfter: Int,
        redactionCount: Int,
        now: Long,
    ): Int {
        committedStatuses += status
        val row = rows[messageId] ?: return 0
        // 真 DAO 的 `WHERE id = ? AND status = 'STREAMING'`：非中间态 ⇒ 0 行
        if (row.status != ChatMessageEntity.STATUS_STREAMING) return 0
        rows[messageId] = row.copy(status = status, contentEnc = contentEnc, contentPreview = preview)
        return 1
    }
}

/** 一行 `chat_message`。只填本测试关心的四件事：谁、第几条、什么状态、什么时候。 */
private fun messageRow(id: String, status: MessageStatus): ChatMessageEntity = ChatMessageEntity(
    id = id,
    sessionId = "s-1",
    seq = 0,
    turnId = "t-1",
    role = "ASSISTANT",
    kind = "TEXT",
    status = status.name,
    clientRequestId = null,
    callId = null,
    contentEnc = null,
    contentPreview = "",
    payloadState = ChatMessageEntity.PAYLOAD_INLINE,
    payloadFile = null,
    payloadCodec = null,
    payloadBytes = 0,
    taskId = null,
    skillRef = null,
    modelNamespace = "local",
    promptTokens = 0,
    decodeTokens = 0,
    kvCacheTokensAfter = 0,
    compactionLevel = 0,
    redactionCount = 0,
    createdAt = 0L,
    updatedAt = 0L,
)
