package com.ake.data.persist.fake

import com.ake.core.common.FieldCipher
import com.ake.data.persist.db.dao.ChatSessionDao
import com.ake.data.persist.db.dao.CloudCredentialDao
import com.ake.data.persist.db.entity.ChatSessionEntity
import com.ake.data.persist.db.entity.CloudCredentialEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 云端凭证 / 出境许可这两个 v4 落点的测试替身（docs/09 §4 / §6）。
 *
 * ## ★ 为什么这里另写一份 `FieldCipher`，而不复用 `FakeFieldCipher`
 *
 * `FakeFieldCipher` 只是「头尾各加一个标记字节」，**AAD 不匹配时它照样返回一段字节**。
 * 而 v4 的落盘纪律里有一条是「AAD 绑 `credId` ⇒ 密文被跨行搬移时必然解不开」
 * （docs/09 §4.3）—— 一个不校验 AAD 的替身会让这条断言**永远为真**，
 * 那就等于没有断言。所以这里给它一个**真的校验 AAD**的替身。
 *
 * ⚠ 它**不是**密码学（单测不验 AES-GCM），它保住的是「AAD 参与解密结果」这一条语义。
 */
internal class AadStrictCipher : FieldCipher {

    override fun seal(plain: ByteArray, aad: ByteArray?): ByteArray {
        val a = aad ?: ByteArray(0)
        require(a.size <= 255) { "替身的 AAD 长度上界 255（credId 远小于此）" }
        val body = ByteArray(plain.size) { i ->
            (plain[i].toInt() xor mask(a, i)).toByte()
        }
        return byteArrayOf(MARK, a.size.toByte()) + a + body
    }

    override fun open(blob: ByteArray, aad: ByteArray?): ByteArray? {
        if (blob.size < 2 || blob[0] != MARK) return null
        val len = blob[1].toInt() and 0xFF
        if (blob.size < 2 + len) return null
        val stored = blob.copyOfRange(2, 2 + len)
        val expected = aad ?: ByteArray(0)
        // ★★ AAD 不匹配 ⇒ 解密失败（这就是 GCM 的认证语义）
        if (!stored.contentEquals(expected)) return null
        val body = blob.copyOfRange(2 + len, blob.size)
        return ByteArray(body.size) { i -> (body[i].toInt() xor mask(expected, i)).toByte() }
    }

    private fun mask(aad: ByteArray, index: Int): Int =
        (aad.getOrNull(index % aad.size.coerceAtLeast(1))?.toInt() ?: 0) xor (index and 0xFF)

    private companion object {
        const val MARK: Byte = 0x7B
    }
}

/**
 * `CloudCredentialDao` 的内存替身。
 *
 * ★ [shred] **照抄**真实 DAO 的守卫（`AND deleted_at IS NULL`）⇒ 重复粉碎是幂等的，
 *   这一点正是「墓碑 + 审计回溯」能成立的前提（docs/09 §2.1 ③）。
 *
 * ⚠ 与所有 DAO 替身一样：**不模拟 SQLite 的约束**（没有 UNIQUE、没有 FK）。
 *   数据库层性质由 `SqliteOracleV4Test` 验。
 */
internal class FakeCloudCredentialDao : CloudCredentialDao {

    val rows = LinkedHashMap<String, CloudCredentialEntity>()
    private val version = MutableStateFlow(0)

    /** ★ 抛异常开关：用来验「任何异常都表现为拿不到」（fail-closed）。 */
    var throwOnWrite: Boolean = false

    override suspend fun insert(row: CloudCredentialEntity) {
        if (throwOnWrite) error("boom")
        if (rows.containsKey(row.credId)) error("UNIQUE constraint failed: cred_id")
        rows[row.credId] = row
        version.value++
    }

    override suspend fun upsert(row: CloudCredentialEntity) {
        if (throwOnWrite) error("boom")
        rows[row.credId] = row
        version.value++
    }

    override suspend fun shred(credId: String, now: Long, shredded: ByteArray): Int {
        if (throwOnWrite) error("boom")
        val old = rows[credId] ?: return 0
        if (old.deletedAt != null) return 0          // ★ 幂等：已粉碎过 ⇒ 0 行
        rows[credId] = old.copy(
            secretEnc = null,
            baseUrlEnc = shredded,
            deletedAt = now,
            updatedAt = now,
        )
        version.value++
        return 1
    }

    override suspend fun deleteHard(credId: String): Int {
        val existed = rows.remove(credId) != null
        version.value++
        return if (existed) 1 else 0
    }

    override suspend fun deleteAll(): Int {
        val n = rows.size
        rows.clear()
        version.value++
        return n
    }

    override suspend fun byId(credId: String): CloudCredentialEntity? = rows[credId]

    override fun liveIds(): Flow<List<String>> = version.map {
        rows.values.filter { it.deletedAt == null }
            .sortedByDescending { it.updatedAt }
            .map { it.credId }
    }

    override suspend fun alive(credId: String): Int {
        val r = rows[credId] ?: return 0
        return if (r.deletedAt == null) 1 else 0
    }

    override suspend fun sessionUseCount(credId: String): Int =
        rows.values.count { it.credId == credId }   // 替身不持 chat_session；由测试直接给数

    override suspend fun touchUsed(credId: String, now: Long): Int {
        val old = rows[credId] ?: return 0
        rows[credId] = old.copy(lastUsedAt = now)
        return 1
    }

    /** ★ 原样回读密文字节 —— D2「落盘字节 Canary」的出口。 */
    override suspend fun rawBaseUrlBlob(credId: String): ByteArray? = rows[credId]?.baseUrlEnc

    override suspend fun rawSecretBlob(credId: String): ByteArray? = rows[credId]?.secretEnc

    override suspend fun countEmptyHost(): Int =
        rows.values.count { it.deletedAt == null && it.endpointHost.isBlank() }
}

/**
 * `ChatSessionDao` 的内存替身：**只**实现 v4 出境链路用到的三条。
 *
 * 其余一律 [unsupported] —— 刻意不返回空值：
 * 静默的空值会让「这条路径被覆盖到了」变成假象（本仓替身的一贯纪律）。
 */
internal class FakeChatSessionDao : ChatSessionDao {

    /** sessionId → 已登记的凭证 id。NULL = 该会话未绑定云端凭证。 */
    val credentialOf = LinkedHashMap<String, String?>()
    val consentAt = LinkedHashMap<String, Long>()

    override suspend fun cloudCredentialIdOf(sessionId: String): String? = credentialOf[sessionId]

    /** sessionId → 落库文本（`LOCAL` / `CLOUD:<ref>`）。NULL = 该会话**从未登记过**目标。 */
    val targetOf = LinkedHashMap<String, String?>()

    override suspend fun modelTargetOf(sessionId: String): String? = targetOf[sessionId]

    override suspend fun bindCredential(
        sessionId: String,
        credId: String?,
        modelTarget: String,
        now: Long,
    ): Int {
        credentialOf[sessionId] = credId
        targetOf[sessionId] = modelTarget
        return 1
    }

    override suspend fun setCloudConsent(sessionId: String, now: Long): Int {
        consentAt[sessionId] = now
        return 1
    }

    override suspend fun insert(session: ChatSessionEntity) = unsupported("insert")
    override suspend fun upsert(session: ChatSessionEntity) = unsupported("upsert")
    override fun observeRecent(limit: Int): Flow<List<ChatSessionEntity>> = unsupported("observeRecent")
    override fun observeByAgent(agentId: String): Flow<List<ChatSessionEntity>> =
        unsupported("observeByAgent")
    override suspend fun byId(id: String): ChatSessionEntity? = unsupported("byId")
    override suspend fun touchForNewMessage(
        sessionId: String,
        now: Long,
        preview: String?,
        nextSeq: Int,
        unreadDelta: Int,
    ) = unsupported("touchForNewMessage")
    override suspend fun touchForAssistant(sessionId: String, now: Long, nextSeq: Int) =
        unsupported("touchForAssistant")
    override suspend fun saveDraft(sessionId: String, draftEnc: ByteArray?, now: Long): Int =
        unsupported("saveDraft")
    override suspend fun markRead(sessionId: String, seq: Int) = unsupported("markRead")
    override suspend fun recordFocusRelease(
        sessionId: String,
        kvTokens: Int,
        rehydrateTokens: Int,
        mode: String,
        epoch: Int,
        profile: String,
        now: Long,
    ) = unsupported("recordFocusRelease")
    override suspend fun rename(sessionId: String, title: String, now: Long) = unsupported("rename")
    override suspend fun markCompacted(sessionId: String, uptoSeq: Int, level: Int, now: Long) =
        unsupported("markCompacted")
    override suspend fun archive(sessionId: String, now: Long) = unsupported("archive")
    override suspend fun delete(sessionId: String) = unsupported("delete")
    override suspend fun deleteByAgent(agentId: String): Int = unsupported("deleteByAgent")
    override suspend fun idsOfAgent(agentId: String): List<String> = unsupported("idsOfAgent")
    override suspend fun oldest(limit: Int): List<String> = unsupported("oldest")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("FakeChatSessionDao.$name 未被本轮测试覆盖")
}

/**
 * ★ `DbTransaction` 的测试替身：**直接执行**，但记录进入次数与并发深度。
 *
 * 它取代的原本是一个 `AkeDatabase` 替身 —— 那个替身造不出来，因为
 * `RoomDatabase.withTransaction` 依赖真开库才存在的 `internalTransactionExecutor`。
 * 这正是 `DbTransaction` 接缝存在的理由（见其类注释）。
 *
 * ⚠ 它**不模拟事务**：没有原子性、没有隔离。它只让「这段逻辑确实被放进事务里」
 *   这件事可被断言，而事务本身的性质由 `SqliteOracleV4Test` 与真机承担。
 */
internal class RecordingTransaction : com.ake.data.persist.db.DbTransaction {

    var entered: Int = 0
        private set

    var maxDepth: Int = 0
        private set

    private var depth: Int = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        entered++
        depth++
        maxDepth = maxOf(maxDepth, depth)
        return try {
            block()
        } finally {
            depth--
        }
    }
}
