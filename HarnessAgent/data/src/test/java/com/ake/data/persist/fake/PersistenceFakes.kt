package com.ake.data.persist.fake

import com.ake.core.contract.TaskStatus
import com.ake.data.persist.db.dao.CheckpointDao
import com.ake.data.persist.db.dao.PolicyDao
import com.ake.data.persist.db.dao.TaskDao
import com.ake.data.persist.db.entity.AppSettingsEntity
import com.ake.data.persist.db.entity.McpServerEntity
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import com.ake.data.persist.db.entity.TaskCheckpointEntity
import com.ake.data.persist.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 单测用的**内存 DAO**（不是 mock）。
 *
 * ## 为什么手写而不是用 Mockito / mockk
 *
 *  1. **本项目没有 mock 依赖**，加坐标要邝随行统一处理；
 *  2. 更重要的：**mock 会把「SQL 里的守卫」一起 mock 掉**。
 *     例如 `convergeToPaused` 的真实语义里有 `AND status IN ('CREATED','RUNNING')`，
 *     用 mock 桩成「返回 1」之后，`TaskReconciler` 的「不把终态掀回中间态」这条性质
 *     就**没有被执行到** —— 测试会绿，而它声称要保护的东西从来没被验证。
 *
 * 所以这里把那两条 UPDATE 的守卫语义（`TaskDao` 注释里论证过的）在内存里重新实现一遍。
 * 它验证的不是 SQL 字符串（那是真机的事），而是**调用方与守卫之间的契约**。
 */

/** 内存里的一行 `task`。只保留 `TaskReconciler` 真正会读写的那几列。 */
internal data class FakeTaskRow(
    var status: String,
    var lastActivityAt: Long,
    var terminalReason: String? = null,
    var finishedAt: Long? = null,
)

/**
 * `TaskDao` 的内存实现。
 *
 * ★ 三条被刻意保留的真实语义（它们正是要被回归的东西）：
 *  - [staleActiveIds] 只收 `status == expectedStatus` 且心跳早于 `before` 的行，
 *    按心跳升序、受 `limit` 截断 —— 对应 SQL 的 `WHERE` / `ORDER BY` / `LIMIT`；
 *  - [convergeToPaused] / [convergeAborted] 带 `status IN ('CREATED','RUNNING')` 守卫，
 *    打空返回 0 —— 对应 `TaskReconciler` 的 `RACED` 分支；
 *  - 其余方法留给未被本轮测试触达的路径，一律抛 UnsupportedOperation 而不是静默返回
 *    一个「看起来对」的值 —— 静默返回值会让后来者以为那条路径已被覆盖。
 */
internal class FakeTaskDao(
    /**
     * 三张表共享的写入日志。
     *
     * ★ 共享是刻意的：断言「`task` 行先于 `task_checkpoint` 行落盘」需要一个跨表的总序，
     *   各表各记一份就拼不出这个顺序 —— 而那个顺序正是外键约束成立的前提。
     */
    val journal: MutableList<String> = ArrayList(),
) : TaskDao {

    val rows: LinkedHashMap<String, FakeTaskRow> = LinkedHashMap()

    /** 每一次 UPDATE 的调用记录，供断言「收敛方向」用。 */
    val convergeCalls: MutableList<String> = ArrayList()

    /**
     * 在 `converge*` **之前**执行的钩子。
     *
     * 它模拟的是「扫描与收敛之间隔着一个挂起点，而正常的 finish 恰好在那时空发生」
     * —— 真实 SQL 里这条缝隙由 `AND status = ?` 兜住，这里用它来构造那次竞争。
     */
    var beforeConverge: ((taskId: String) -> Unit)? = null

    /** 让 `staleActiveIds` 抛异常，用于验证「冷启动不该被一段 DB 异常堵死」。 */
    var scanThrows: Boolean = false

    fun put(id: String, status: String, lastActivityAt: Long) {
        rows[id] = FakeTaskRow(status = status, lastActivityAt = lastActivityAt)
    }

    // ── TaskReconciler 真正用到的三个方法 ──────────────────────────────

    override suspend fun staleActiveIds(expectedStatus: String, before: Long, limit: Int): List<String> {
        if (scanThrows) error("simulated DB failure")
        return rows.entries
            .filter { (_, r) -> r.status == expectedStatus && r.lastActivityAt < before }
            .sortedWith(compareBy({ it.value.lastActivityAt }, { it.key }))
            .take(limit)
            .map { it.key }
    }

    override suspend fun convergeToPaused(id: String, at: Long): Int {
        beforeConverge?.invoke(id)
        convergeCalls += "paused:$id"
        val r = rows[id] ?: return 0
        if (r.status !in setOf(TaskStatus.CREATED, TaskStatus.RUNNING)) return 0
        r.status = TaskStatus.PAUSED
        r.terminalReason = null
        r.finishedAt = null
        r.lastActivityAt = at
        return 1
    }

    override suspend fun convergeAborted(id: String, at: Long, reason: String): Int {
        beforeConverge?.invoke(id)
        convergeCalls += "aborted:$id:$reason"
        val r = rows[id] ?: return 0
        if (r.status !in setOf(TaskStatus.CREATED, TaskStatus.RUNNING)) return 0
        r.status = TaskStatus.ABORTED
        r.terminalReason = reason
        r.finishedAt = at
        r.lastActivityAt = at
        return 1
    }

    override suspend fun countAll(): Int = rows.size

    // ── 落盘路径（TaskStateWriter 用） ─────────────────────────────────

    /** 按提交顺序记录的落盘动作。断言保序与「零丢失」都靠它。 */
    val log: MutableList<String> get() = journal

    /** 让写路径抛异常，用于验证状态泳道的「失败重试」而不是「丢弃」。 */
    var failNextWrites: Int = 0

    private fun maybeFail() {
        if (failNextWrites > 0) {
            failNextWrites--
            error("simulated write failure")
        }
    }

    override suspend fun upsert(task: TaskEntity) {
        maybeFail()
        log += "upsert:${task.id}"
    }

    override suspend fun finish(
        id: String,
        status: String,
        reason: String?,
        finishedAt: Long,
        answerEnc: ByteArray?,
        answerPreview: String?,
    ) {
        maybeFail()
        log += "finish:$id:$status:$reason"
    }

    override suspend fun markSuspended(id: String, status: String, at: Long) {
        maybeFail()
        log += "suspend:$id:$status"
    }

    override suspend fun advance(
        id: String,
        stepsDone: Int,
        prefillTotal: Long,
        decodeTotal: Long,
        elapsedTotal: Long,
        lastActivityAt: Long,
    ) {
        maybeFail()
        log += "advance:$id:$stepsDone"
    }

    // ── 未被本轮触达的路径：显式 unsupported ───────────────────────────

    override suspend fun byId(id: String): TaskEntity? = unsupported("byId")
    override fun observe(id: String): Flow<TaskEntity?> = unsupported("observe")
    override fun ofSession(sessionId: String): Flow<List<TaskEntity>> = unsupported("ofSession")
    override fun finished(limit: Int): Flow<List<TaskEntity>> = unsupported("finished")
    override suspend fun recent(limit: Int): List<TaskEntity> = unsupported("recent")
    override fun observeRecent(limit: Int): Flow<List<TaskEntity>> = unsupported("observeRecent")
    override suspend fun raiseKvCachePeak(id: String, peak: Int): Unit = unsupported("raiseKvCachePeak")
    override suspend fun addRedactionCount(id: String, delta: Int): Unit = unsupported("addRedactionCount")
    override suspend fun markRunning(id: String, at: Long): Unit = unsupported("markRunning")
    override suspend fun setStarred(id: String, starred: Int): Unit = unsupported("setStarred")
    override suspend fun pickForDigest(before: Long, limit: Int): List<String> = unsupported("pickForDigest")
    override suspend fun pickExpired(before: Long, limit: Int): List<String> = unsupported("pickExpired")
    override suspend fun setRetentionClass(ids: List<String>): Unit = unsupported("setRetentionClass")
    override suspend fun pickOldest(limit: Int): List<String> = unsupported("pickOldest")
    override suspend fun delete(id: String): Unit = unsupported("delete")

    // ── v3：与 chat_message 的解绑（§8.2 B1）。本轮 FakeTaskDao 不覆盖聊天路径。
    override suspend fun unbindChatMessage(messageId: String): Int = unsupported("unbindChatMessage")
    override suspend fun unbindChatSession(sessionId: String): Int = unsupported("unbindChatSession")
    override suspend fun byChatMessage(messageId: String?): List<TaskEntity> = unsupported("byChatMessage")
    override suspend fun countFull(): Int = unsupported("countFull")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException(
            "FakeTaskDao.$name 未被本轮测试覆盖。若断言需要它，请在此处补内存语义，" +
                "而不是让它静默返回一个空值 —— 空值会让「覆盖到了」变成假象。",
        )
}

/** `CheckpointDao` 的内存实现：只关心「某个任务有没有断点」。 */
internal open class FakeCheckpointDao(
    private val journal: MutableList<String> = ArrayList(),
) : CheckpointDao {

    private val present = LinkedHashSet<String>()

    /** 让 `countOfTask` 抛异常，用于验证「一次查询失败不该让整轮对账崩掉」。 */
    open var countThrows: Boolean = false

    /** 让 `save` 抛异常，用于验证「失败重试」而非丢弃。 */
    var failNextWrites: Int = 0

    fun put(taskId: String) {
        present += taskId
    }

    override suspend fun countOfTask(taskId: String): Int {
        if (countThrows) error("simulated checkpoint dao failure")
        return if (taskId in present) 1 else 0
    }

    override suspend fun save(cp: TaskCheckpointEntity) {
        if (failNextWrites > 0) {
            failNextWrites--
            error("simulated checkpoint write failure")
        }
        present += cp.taskId
        journal += "checkpoint:${cp.taskId}"
    }

    override suspend fun byTask(taskId: String): TaskCheckpointEntity? = unsupported("byTask")
    override suspend fun findResumable(): List<String> = unsupported("findResumable")
    override suspend fun delete(taskId: String): Unit = unsupported("delete")
    override suspend fun deleteStale(before: Long): Int = unsupported("deleteStale")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("FakeCheckpointDao.$name 未被本轮测试覆盖")
}

/** `PolicyDao` 的内存实现：只关心「审计行有没有进同一个事务」。 */
internal class FakePolicyDao(
    private val journal: MutableList<String> = ArrayList(),
) : PolicyDao {

    val inserted: MutableList<String> = ArrayList()

    var failNextWrites: Int = 0

    override suspend fun insert(items: List<PolicyDecisionEntity>) {
        if (failNextWrites > 0) {
            failNextWrites--
            error("simulated policy write failure")
        }
        items.forEach { e -> journal += "policy:${e.id}" }
        inserted += items.map { it.id }
    }

    override suspend fun ofTask(taskId: String): List<PolicyDecisionEntity> = unsupported("ofTask")
    override fun observeTask(taskId: String): Flow<List<PolicyDecisionEntity>> = unsupported("observeTask")
    override suspend fun countDecisionSince(decision: String, since: Long): Int = unsupported("countDecisionSince")
    override suspend fun recentDenials(since: Long, limit: Int): List<PolicyDecisionEntity> =
        unsupported("recentDenials")
    override suspend fun deleteOfTask(taskId: String): Unit = unsupported("deleteOfTask")
    override suspend fun deleteOrphans(): Int = unsupported("deleteOrphans")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("FakePolicyDao.$name 未被本轮测试覆盖")
}

// ────────────────────────────────────────────────────────────────
// 测试用的行构造
// ────────────────────────────────────────────────────────────────

internal fun taskEntity(
    id: String,
    status: String = TaskStatus.RUNNING,
    lastActivityAt: Long = 0L,
): TaskEntity = TaskEntity(
    id = id,
    sessionId = "sess-test",
    seqInSession = 0,
    createdAt = 0L,
    startedAt = null,
    finishedAt = null,
    lastActivityAt = lastActivityAt,
    status = status,
    terminalReason = null,
    flavor = null,
    memoryTier = null,
    contextWindow = 8192,
    modelId = null,
    backend = null,
    stepsDone = 0,
    prefillTokensTotal = 0L,
    decodeTokensTotal = 0L,
    elapsedMsTotal = 0L,
    goalEnc = null,
    goalPreview = null,
    answerEnc = null,
    answerPreview = null,
    starred = 0,
    retentionClass = "FULL",
    redactionCount = 0,
    kvCachePeak = 0,
    schemaV = 1,
)

internal fun checkpointEntity(taskId: String): TaskCheckpointEntity = TaskCheckpointEntity(
    taskId = taskId,
    updatedAt = 0L,
    statusSnapshot = TaskStatus.RUNNING,
    currentStepSeq = 3,
    prefillTokensTotal = 0L,
    decodeTokensTotal = 0L,
    elapsedMsTotal = 0L,
    seenDigestJson = null,
    scratchpadEnc = null,
    lastUrl = null,
    lastObservationId = null,
    lastKvCacheTokens = null,
    compactionLastStep = null,
    humanGatePending = 0,
    humanGateJson = null,
)

internal fun mcpServerEntity(
    id: String,
    headersJson: String = "{}",
    enabled: Int = 1,
    updatedAt: Long = 0L,
): McpServerEntity = McpServerEntity(
    id = id,
    label = id,
    url = "http://127.0.0.1:8899/mcp",
    headersJson = headersJson,
    enabled = enabled,
    transport = "STREAMABLE_HTTP",
    callTimeoutMs = 8_000L,
    connectTimeoutMs = 6_000L,
    maxReconnectAttempts = 3,
    updatedAt = updatedAt,
)

internal fun appSettingsEntity(
    allowHostsJson: String = "[]",
    maxSteps: Int? = null,
    showDomPayload: Int = 1,
): AppSettingsEntity = AppSettingsEntity(
    id = AppSettingsEntity.SINGLE_ROW_ID,
    allowHostsJson = allowHostsJson,
    maxSteps = maxSteps,
    showDomPayload = showDomPayload,
    updatedAt = 0L,
)

internal fun <T> flowOfValue(v: T): Flow<T> = flowOf(v)
