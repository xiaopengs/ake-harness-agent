package com.ake.data.persist.store

import com.ake.core.common.Hosts
import com.ake.core.common.ManualClock
import com.ake.core.common.Outcome
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.TlsMode
import com.ake.data.persist.db.entity.CloudCredentialEntity
import com.ake.data.persist.fake.AadStrictCipher
import com.ake.data.persist.fake.FakeCloudCredentialDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★ 云端凭证的落盘语义（docs/09 §4，A5 裁决的落点）。
 *
 * ## 本文件真正想盯住的四件事
 *
 *  ① **完整 BaseURL 也加密** —— 只加密 key 而明文留 URL，等于「自建网关把 key 放 query」
 *     这条清单白写三分之一（docs/08 §6.3 ③）。验证方式是**读回落盘的原始字节**
 *     （`rawBaseUrlBlob`），而不是「seal 再 open 看值对不对」。
 *  ② **host 走 `Hosts` 唯一实现** —— 多一份不等价归一化曾造成真实的黑名单绕过。
 *  ③ **删除 = 密码学粉碎 + 墓碑**，不是 DELETE（审计行要能回溯「当时用的是哪个凭证」）。
 *  ④ **AAD 绑 `credId`** ⇒ 密文被跨行搬移时解不开。
 *
 * ## ★ 负向用例
 *
 * 每个"能杀掉检查器"的地方都配了一条：明文 canary 用**字节比对**（不是值比对）、
 * AAD 用**错位搬移**、拒绝非法端点用**空 host**。
 */
class CloudCredentialStoreImplTest {

    private val ref = CredentialRef("cred-1")
    private val other = CredentialRef("cred-2")

    private fun cfg(
        url: String = "https://OpenAI.example.COM:443/v1?key=sk-live-abc123",
        model: String = "gpt-x",
    ) = EndpointConfig(
        baseUrl = url,
        modelName = model,
        tlsMode = TlsMode.REQUIRED,
        declaredContextWindow = 128_000,
        label = "我的网关",
    )

    private fun store(
        dao: FakeCloudCredentialDao = FakeCloudCredentialDao(),
        clock: ManualClock = ManualClock(1_000L),
    ) = CloudCredentialStoreImpl(dao = dao, cipher = AadStrictCipher(), clock = clock)

    // ───────────────────────────── ① 往返

    @Test
    fun configRoundTripsThroughTheCipher() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        assertThat(s.upsert(ref, cfg(), PlainSecret.of("sk-live-abc123"))).isInstanceOf(Outcome.Ok::class.java)

        val back = s.configOf(ref)
        assertThat(back).isInstanceOf(Outcome.Ok::class.java)
        val c = (back as Outcome.Ok).value
        assertThat(c.baseUrl).isEqualTo(cfg().baseUrl)
        assertThat(c.modelName).isEqualTo("gpt-x")
        assertThat(c.label).isEqualTo("我的网关")
        assertThat(c.tlsMode).isEqualTo(TlsMode.REQUIRED)
        assertThat(c.declaredContextWindow).isEqualTo(128_000)
    }

    @Test
    fun secretRoundTripsThroughTheCipher() = runBlocking {
        val s = store()
        s.upsert(ref, cfg(), PlainSecret.of("sk-live-abc123"))
        val got = s.secretOf(ref)
        assertThat(got).isInstanceOf(Outcome.Ok::class.java)
        (got as Outcome.Ok).value.useAsBearer { assertThat(it).isEqualTo("sk-live-abc123") }
    }

    // ───────────────────────────── ② ★★ 落盘字节 Canary（D2）

    /**
     * ★★ **磁盘上的字节不是明文**。
     *
     * 只做「seal 再 open 值相等」是不够的：一个直接返回明文的 no-op 实现也能通过
     * （它原样返回明文，值当然相等）。必须验的是**落盘字节本身**。
     */
    @Test
    fun baseUrlIsEncryptedOnDisk_notJustRedactedOnRead() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        s.upsert(ref, cfg(), PlainSecret.of("sk-live-abc123"))

        val blob = dao.rawBaseUrlBlob("cred-1")!!
        val plain = cfg().baseUrl.toByteArray()
        assertThat(blob).isNotEqualTo(plain)
        // ★ 明文（含 query 里的 key）不得以任何子串形式出现在落盘字节里
        assertThat(String(blob, Charsets.UTF_8)).doesNotContain("sk-live-abc123")
        assertThat(String(blob, Charsets.UTF_8)).doesNotContain("OpenAI.example.COM")
    }

    /** ★ 同样地，secret 在盘上也必须不是明文。 */
    @Test
    fun secretIsEncryptedOnDisk() = runBlocking {
        val dao = FakeCloudCredentialDao()
        store(dao).upsert(ref, cfg(), PlainSecret.of("sk-live-abc123"))
        val blob = dao.rawSecretBlob("cred-1")!!
        assertThat(String(blob, Charsets.UTF_8)).doesNotContain("sk-live-abc123")
    }

    // ───────────────────────────── ③ host 归一走唯一实现

    /** ★ `endpoint_host` 必须**等于** `Hosts.normalise(baseUrl)` —— 不许另起一份归一化。 */
    @Test
    fun endpointHostIsExactlyHostsNormalise() = runBlocking {
        val dao = FakeCloudCredentialDao()
        store(dao).upsert(ref, cfg(), PlainSecret.of("k"))
        val row = dao.byId("cred-1")!!
        assertThat(row.endpointHost).isEqualTo(Hosts.normalise(cfg().baseUrl))
        // 归一化结果：小写、去端口、去 www
        assertThat(row.endpointHost).isEqualTo("openai.example.com")
        // ★ 且与 EndpointConfig.hostOf() 同源（它是唯一允许的对外端点标识）
        assertThat(row.endpointHost).isEqualTo(cfg().hostOf())
        assertThat(dao.countEmptyHost()).isEqualTo(0)
    }

    /**
     * ★ 非法端点**拒收**（fail-closed），不猜一个端点。
     *
     * 「猜」的代价是：一个编出来的假 host 会让 `DomainPolicy` 以为自己认得这个域。
     */
    @Test
    fun unparseableBaseUrlIsRejected() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        for (bad in listOf("not-a-url", "about:blank", "//cdn.example.com/a", "")) {
            val r = s.upsert(ref, cfg(url = bad), PlainSecret.of("k"))
            assertThat(r).isInstanceOf(Outcome.Err::class.java)
        }
        assertThat(dao.rows).isEmpty()
    }

    // ───────────────────────────── ④ ★ AAD 绑 credId

    /**
     * ★★ 密文被**跨行搬移**时必须解不开 ⇒ `secretOf` 返回 Err 而不是拿到别人的密钥。
     *
     * ⚠ 这条断言只有配上 `AadStrictCipher`（真的校验 AAD）才有意义
     *    —— 用 `FakeFieldCipher` 它会永远为真。
     */
    @Test
    fun ciphertextCannotBeMovedToAnotherRow() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        s.upsert(ref, cfg(), PlainSecret.of("sk-live-abc123"))
        val stolen = dao.rawSecretBlob("cred-1")!!

        // 把密文原样搬到另一行（同一个替身库，AAD 不同）
        dao.upsert(
            dao.byId("cred-1")!!.copy(credId = "cred-2", secretEnc = stolen),
        )
        assertThat(s.secretOf(other)).isInstanceOf(Outcome.Err::class.java)
    }

    // ───────────────────────────── ⑤ ★ 删除 = 粉碎 + 墓碑（不是 DELETE）

    @Test
    fun deleteShredsCiphertextButKeepsTheRow() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        s.upsert(ref, cfg(), PlainSecret.of("sk-live-abc123"))

        assertThat(s.delete(ref)).isInstanceOf(Outcome.Ok::class.java)

        val row = dao.byId("cred-1")
        // ★ 行还在（审计行 `route_decision.credential_id` 还能解析出「当时用的是哪个凭证」）
        assertThat(row).isNotNull()
        assertThat(row!!.deletedAt).isNotNull()
        assertThat(row.secretEnc).isNull()
        // ★ 密文占位必须不是合法 GCM 信封 —— 否则「粉碎」只是把一段密文留在那儿
        assertThat(row.baseUrlEnc.size).isLessThan(com.ake.core.common.SecretEnvelope.MIN_LEN)

        // 读不到 / 列不出来
        assertThat(s.secretOf(ref)).isInstanceOf(Outcome.Err::class.java)
        assertThat(s.configOf(ref)).isInstanceOf(Outcome.Err::class.java)
        assertThat(s.exists(ref)).isFalse()
        assertThat(s.refs().first()).isEmpty()
    }

    /** ★ 重复删除是幂等的（真实 DAO 的守卫 `AND deleted_at IS NULL`）。 */
    @Test
    fun deleteTwiceIsIdempotent() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        s.upsert(ref, cfg(), PlainSecret.of("k"))
        assertThat(s.delete(ref)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(s.delete(ref)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(dao.byId("cred-1")!!.deletedAt).isEqualTo(1_000L)
    }

    /** 墓碑行不出现在 `refs()`（UI 列「已配置的服务」）里；存活行按 `updated_at` 倒序。 */
    @Test
    fun refsListsOnlyAliveCredentials() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val clock = ManualClock(1_000L)
        val s = store(dao, clock)
        s.upsert(ref, cfg(), PlainSecret.of("k"))
        clock.setTo(2_000L)
        s.upsert(other, cfg(), PlainSecret.of("k"))
        assertThat(s.refs().first().map { it.id }).containsExactly("cred-2", "cred-1").inOrder()
        s.delete(ref)
        assertThat(s.refs().first().map { it.id }).containsExactly("cred-2").inOrder()
    }

    // ───────────────────────────── ⑥ upsert 的保留字段 / 一键清除

    /** ★ 覆盖写入时 `created_at` 与 `last_used_at` 必须被保留 —— 它们是"事实"，不是元数据。 */
    @Test
    fun upsertPreservesCreatedAtAndLastUsedAt() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val clock = ManualClock(1_000L)
        val s = store(dao, clock)
        s.upsert(ref, cfg(), PlainSecret.of("k1"))
        dao.touchUsed("cred-1", 2_000L)

        clock.setTo(9_000L)
        s.upsert(ref, cfg(model = "gpt-y"), PlainSecret.of("k2"))

        val row = dao.byId("cred-1")!!
        assertThat(row.createdAt).isEqualTo(1_000L)
        assertThat(row.lastUsedAt).isEqualTo(2_000L)
        assertThat(row.updatedAt).isEqualTo(9_000L)
        assertThat(row.modelName).isEqualTo("gpt-y")
        (s.secretOf(ref) as Outcome.Ok).value.useAsBearer { assertThat(it).isEqualTo("k2") }
    }

    /** ★ 一键清除走物理删除（`purgeAll`），且此时 Keystore 别名已被先删 ⇒ 无需再解密。 */
    @Test
    fun purgeAllRemovesEveryRowIncludingTombstones() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        s.upsert(ref, cfg(), PlainSecret.of("k"))
        s.upsert(other, cfg(), PlainSecret.of("k"))
        s.delete(ref)
        assertThat(s.purgeAll()).isEqualTo(2)
        assertThat(dao.rows).isEmpty()
    }

    // ───────────────────────────── ⑦ fail-closed

    /** ★ 写失败表现为 Err，**不抛** —— 凭证写入路径上的异常不得把 UI 崩掉。 */
    @Test
    fun writeFailureBecomesErrNotException() = runBlocking {
        val dao = FakeCloudCredentialDao().apply { throwOnWrite = true }
        val s = store(dao)
        assertThat(s.upsert(ref, cfg(), PlainSecret.of("k"))).isInstanceOf(Outcome.Err::class.java)
        assertThat(s.delete(ref)).isInstanceOf(Outcome.Err::class.java)
    }

    /** ★ `secretOf` 对「行不存在」与「密钥已销毁解不开」返回**不同的错误** —— 否则用户无从判断。 */
    @Test
    fun missingRowAndUnreadableCiphertextAreDistinctErrors() = runBlocking {
        val dao = FakeCloudCredentialDao()
        val s = store(dao)
        s.upsert(ref, cfg(), PlainSecret.of("k"))

        val missing = (s.secretOf(other) as Outcome.Err).error.code
        // 用**错误的 AAD**（模拟密钥被换 / 密文被搬）⇒ 应得到"解不开"而不是"不存在"
        dao.upsert(dao.byId("cred-1")!!.copy(secretEnc = AadStrictCipher().seal("x".toByteArray(), "cred-1".toByteArray())))
        val readable = s.secretOf(ref)
        assertThat(readable).isInstanceOf(Outcome.Ok::class.java)

        val unreadable = run {
            dao.upsert(dao.byId("cred-1")!!.copy(secretEnc = byteArrayOf(0x7B, 0x03, 1, 2, 3, 9, 9, 9)))
            (s.secretOf(ref) as Outcome.Err).error.code
        }
        assertThat(missing).isEqualTo(com.ake.core.ports.CloudFailureCodes.CREDENTIAL_MISSING)
        assertThat(unreadable).isEqualTo(CloudCredentialStoreImpl.CODE_UNREADABLE)
        assertThat(unreadable).isNotEqualTo(missing)
    }

    @Test
    fun schemaVersionIsStampedOnEveryRow() = runBlocking {
        val dao = FakeCloudCredentialDao()
        store(dao).upsert(ref, cfg(), PlainSecret.of("k"))
        assertThat(dao.byId("cred-1")!!.schemaV).isEqualTo(CloudCredentialEntity.SCHEMA_VERSION)
    }
}
