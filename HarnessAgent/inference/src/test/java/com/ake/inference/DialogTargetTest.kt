package com.ake.inference

import com.ake.core.common.Outcome
import com.ake.core.ports.CloudPeerKind
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.ake.core.ports.PromptContext
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * ★ `DialogTarget` 的选择 / 持久化 / 升格（架构 08 §2.4 / §3.3）。
 *
 * ## 三件必须分开的事
 *
 * | 情形 | 期望 |
 * |---|---|
 * | 会话**没登记过**目标 | `of()` 返回 **null** ⇒ 上层报错 |
 * | 登记了端侧 | `Device` |
 * | 落库文本**解析不了**（脏数据/降级安装） | fail-closed → `Device` |
 *
 * ★ 第一行与第三行**不能**合并成同一个返回值 ——
 * 「没选过」是用户的意图缺失，「认不出来」是数据损坏。前者必须打断用户，
 * 后者必须保证不出网。合起来就会让损坏的数据被当成「没选过」而静默走端侧。
 */
class DialogTargetTest {

    // ═══ 1. 会话级选择与落库形态 ═══

    @Test
    fun deviceTargetRoundTripsThroughTheStore() = runTest {
        val store = InMemoryDialogTargetStore()

        store.select("sess-1", DialogTarget.Device)

        assertThat(store.of("sess-1")).isEqualTo(DialogTarget.Device)
        // ★ 落库文本恰好是常量本身，没有版本前缀、没有 JSON —— UI 与 :data 读的是同一份
        assertThat(store.rawOf("sess-1")).isEqualTo("LOCAL")
    }

    @Test
    fun remoteTargetRoundTripsThroughTheStore() = runTest {
        val store = InMemoryDialogTargetStore()

        store.select("sess-1", DialogTarget.Remote(CredentialRef("svc-a")))

        assertThat(store.of("sess-1")).isEqualTo(DialogTarget.Remote(CredentialRef("svc-a")))
        assertThat(store.rawOf("sess-1")).isEqualTo("CLOUD:svc-a")
    }

    /** ★★ 「没选过」必须与「选了端侧」可区分。 */
    @Test
    fun unregisteredSessionIsNullNotDevice() = runTest {
        val store = InMemoryDialogTargetStore()

        assertThat(store.of("never-seen")).isNull()
        assertThat(store.of("never-seen")).isNotEqualTo(DialogTarget.Device)
    }

    // ═══ 2. 编解码：fail-closed ═══

    /**
     * ★ docs/10 §6.2-D：**不再**静默降级成 Device。
     *
     *  · `null` / 空 / 纯空白 ⇒ `Device`（「没登记过」是合法状态，不是脏数据）；
     *  · 认不出的文本         ⇒ `Unrecognized`（承认不认识，交回上层报错）。
     */
    @Test
    fun unparsableTextBecomesUnrecognizedNotDevice() {
        val empty = listOf(null, "", "   ")
        for (raw in empty) {
            assertThat(DialogTargetCodec.decode(raw)).isEqualTo(DialogTarget.Device)
        }

        val garbage = listOf("GARBAGE", "cloud_svc-a", "LOCALX", "CLOUD:", "CLOUD:   ")
        for (raw in garbage) {
            val d = DialogTargetCodec.decode(raw)
            assertThat(d).isInstanceOf(DialogTarget.Unrecognized::class.java)
        }
    }

    /** ★ 未知态写回时**原样保留**，不许被抹成 `LOCAL`。 */
    @Test
    fun unrecognizedRoundTripsWithoutLosingTheEvidence() {
        val d = DialogTargetCodec.decode("GARBAGE")

        assertThat(DialogTargetCodec.encode(d)).isEqualTo("GARBAGE")
    }

    /** ★★ 未知目标 ⇒ 路由器**报错**，不许猜成端侧（docs/10 §6.2-D 的落点）。 */
    @Test
    fun routerRefusesAnUnrecognizedTargetInsteadOfGuessing() = runTest {
        val store = InMemoryDialogTargetStore()
        store.select("s1", DialogTargetCodec.decode("GARBAGE"))
        val router = DefaultModelRouter(
            device = FakeDevice(),
            localDialog = LocalDialogAdapter(FakeDevice()),
            remote = FakeCloudDialog(),
            targets = store,
            creds = FakeCredentials(),
            permits = FakePermitSink(),
        )

        val r = router.dialogFor("s1")

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code)
            .isEqualTo(DefaultModelRouter.CODE_UNRECOGNIZED_TARGET)
    }

    @Test
    fun codecRoundTripsBothVariants() {
        assertThat(DialogTargetCodec.decode(DialogTargetCodec.encode(DialogTarget.Device)))
            .isEqualTo(DialogTarget.Device)

        val remote = DialogTarget.Remote(CredentialRef("svc-b"))
        assertThat(DialogTargetCodec.decode(DialogTargetCodec.encode(remote))).isEqualTo(remote)
    }

    // ═══ 3. 负向：目标里装不下密钥 ═══

    /**
     * ★★ `DialogTarget.Remote` **只有一个字段**，类型是 [CredentialRef]。
     *
     * 这是「§5：目标里装不下密钥」的**类型级**证据：
     * 想塞一个 `apiKey: String` 进来，必须先改这个数据类的形状 ——
     * 那不是顺手，是一次需要过评审的改动。
     */
    @Test
    fun remoteTargetCanOnlyCarryACredentialReference() {
        val fields = DialogTarget.Remote::class.java.declaredFields

        // ★ `peerKind` 是 docs/10 §6.2-A 的对端形态（默认 `CompletionEndpoint`），
        //   它与「密钥」无关 ⇒ 字段白名单里必须显式包含它，否则这条守卫会误报。
        assertThat(fields.map { it.name }).containsExactly("credentialRef", "peerKind")
        assertThat(fields.map { it.type })
            .containsExactly(CredentialRef::class.java, CloudPeerKind::class.java)
        assertThat(CredentialRef::class.java.declaredFields.map { it.name }).containsExactly("id")
        // id 是「引用名」，不是密文本身：它只被当作 key 用，不参与任何加密/拼装
        assertThat(CredentialRef::class.java.declaredFields.map { it.type })
            .containsExactly(String::class.java)
    }

    /** 落库文本里出现 `sk-` / `Bearer` 就说明有人把密钥当 ref 存了。 */
    @Test
    fun storedRemoteRowNeverLooksLikeASecret() = runTest {
        val store = InMemoryDialogTargetStore()
        store.select("sess-1", DialogTarget.Remote(CredentialRef("svc-a")))

        val raw = store.rawOf("sess-1").orEmpty()

        assertThat(raw).doesNotContain("sk-")
        assertThat(raw).doesNotContain("Bearer")
        assertThat(raw).isEqualTo(DialogTargetCodec.PREFIX_REMOTE + "svc-a")
    }

    // ═══ 4. LocalDialogAdapter：升格，但放行页面上下文 ═══

    /**
     * ★★ 端侧**允许** `WithPageContext` —— 与云端的硬拒绝构成对照。
     *
     * 这一条是 §5.4 锁 C 的反面：锁 C 禁的是「页面上下文出境」，
     * 不是「页面上下文存在」。把端侧也拒掉会让浏览器任务根本跑不起来。
     */
    @Test
    fun localAdapterAcceptsPageContext() = runTest {
        val device = FakeDevice()
        val adapter = LocalDialogAdapter(device)
        val page = PromptContext.WithPageContext(host = "shop.example.com", domChars = 900, elementCount = 30)

        val r = adapter.openDialog(testSpec(), permit = null, context = page)

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(device.openSessionCalls).isEqualTo(1)
    }

    /** ★ `permit` 在端侧**被忽略但必须在场**：本地推理不需要出境许可。 */
    @Test
    fun localAdapterIgnoresThePermitButKeepsTheParameter() = runTest {
        val device = FakeDevice()
        val adapter = LocalDialogAdapter(device)

        adapter.openDialog(testSpec(), permit = testPermit(), context = PromptContext.TextOnly)
        adapter.openDialog(testSpec(), permit = null, context = PromptContext.TextOnly)

        // 两次都一样地落到端侧引擎 —— permit 的有无不改变行为
        assertThat(device.openSessionCalls).isEqualTo(2)
    }

    /** ★ spec 必须**原样**透传（KV cache 复水靠 `initialMessages`，丢了就每轮重来）。 */
    @Test
    fun localAdapterPassesTheSpecThroughUnchanged() = runTest {
        val device = FakeDevice()
        val adapter = LocalDialogAdapter(device)
        val spec = testSpec(modelId = "gemma-4b")

        adapter.openDialog(spec, permit = null, context = PromptContext.TextOnly)

        assertThat(device.lastSpec).isEqualTo(spec)
    }

    @Test
    fun localAdapterDelegatesCapabilitiesAndTokenEstimation() {
        val device = FakeDevice()
        val adapter = LocalDialogAdapter(device)

        assertThat(adapter.capabilities).isEqualTo(device.capabilities)
        assertThat(adapter.estimateTokens("abcd")).isEqualTo(4)
    }
}
