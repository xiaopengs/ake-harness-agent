package com.ake.inference

import com.ake.core.common.Outcome
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudFailureCodes
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.RouteContext
import com.ake.core.ports.TaskKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * ★★ `ModelRouter` 派发（架构 08 §4.5）。
 *
 * ## ★ E6 已删：端侧实现是**注入**的，本模块看不见 `:llm`
 *
 * 于是这里能钉死两件事：
 *  ① 路由器**只**认 [DeviceModelPort] 接口 —— 端侧引擎从哪来不关它的事；
 *  ② 「这个会话没登记过」≠「选了端侧」⇒ 前者交给 `DefaultTargetStore` 兜底，
 *     **不许由路由器猜一个本地**（v5：三级优先 = 会话显式 > 全局默认 > 端侧）。
 *
 * ★ 全部替身都是纯 JVM 的 ⇒ 毫秒级，无需设备（§9.4）。
 */
class DefaultModelRouterTest {

    private class Fixture(
        val device: FakeDevice = FakeDevice(),
        val creds: FakeCredentials = FakeCredentials(),
        val permits: FakePermitSink = FakePermitSink(),
        val targets: InMemoryDialogTargetStore = InMemoryDialogTargetStore(),
        val defaults: InMemoryDefaultTargetStore = InMemoryDefaultTargetStore(),
    ) {
        val localDialog: DialogModelPort = LocalDialogAdapter(device)
        val remote: FakeCloudDialog = FakeCloudDialog()
        val router = DefaultModelRouter(
            device = device,
            localDialog = localDialog,
            remote = remote,
            targets = targets,
            defaults = defaults,
            creds = creds,
            permits = permits,
        )
    }

    // ═══ 1. 三级优先：会话显式 > 全局默认 > 端侧 ═══

    /**
     * ★★ **本轮的头号负例**：未登记会话必须**继承全局默认**。
     *
     * 用户把默认设成云端、新开一个还没登记过的会话 ⇒ 必须拿到 [remote]。
     *
     * ★ 它能杀死「忘了接默认值」这个错误：只要 `dialogFor` 里还是
     *   `?: return Err(NO_TARGET)`（或 `?: DialogTarget.Device`），
     *   这里就会变成 `Err` / `localDialog` ⇒ 测试立刻红。
     *   这条**不是**读回自证 —— 断言的是「用户设置能否穿过路由层」。
     */
    @Test
    fun unregisteredSessionInheritsTheGlobalDefault() = runTest {
        val f = Fixture()
        f.defaults.setDefault(DialogTarget.Remote(CredentialRef("svc-a")))

        val r = f.router.dialogFor("sess-never-configured")

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat((r as Outcome.Ok).value).isSameInstanceAs(f.remote)
        // ★ 派发阶段一次都不读明文
        assertThat(f.creds.secretReads).isEqualTo(0)
    }

    /** 默认**从未设置过** ⇒ 端侧（安全默认）。与 `model_target` 列 `'LOCAL'` 同一套语义。 */
    @Test
    fun unsetDefaultFallsBackToTheDeviceSide() = runTest {
        val f = Fixture()

        val r = f.router.dialogFor("sess-never-configured")

        assertThat((r as Outcome.Ok).value).isSameInstanceAs(f.localDialog)
        assertThat(f.device.openSessionCalls).isEqualTo(0)
    }

    /** ★ 会话显式登记**压过**全局默认（两个方向都钉死）。 */
    @Test
    fun explicitSessionTargetBeatsTheGlobalDefaultInBothDirections() = runTest {
        run {
            val f = Fixture()
            f.defaults.setDefault(DialogTarget.Remote(CredentialRef("svc-a")))
            f.targets.select("sess-1", DialogTarget.Device)

            assertThat((f.router.dialogFor("sess-1") as Outcome.Ok).value)
                .isSameInstanceAs(f.localDialog)
        }
        run {
            val f = Fixture()
            f.defaults.setDefault(DialogTarget.Device)
            f.targets.select("sess-2", DialogTarget.Remote(CredentialRef("svc-a")))

            assertThat((f.router.dialogFor("sess-2") as Outcome.Ok).value)
                .isSameInstanceAs(f.remote)
        }
    }

    /**
     * ★★ 兜底**不许写回会话表**。
     *
     * 若有人图省事写成 `targets.select(sessionId, defaults.default())`：
     *  ① 「从未设置过默认」与「把默认设成 Device」在库里**再也分不开**；
     *  ② 会话目标「会话内不可变」被一次**读**操作破坏；
     *  ③ 兜底被固化 ⇒ 用户之后改默认，老会话不会跟着变。
     *
     * ⇒ 这里断言 `dialogFor` 之后会话表里**仍然没有这一行**。
     */
    @Test
    fun globalDefaultIsNotWrittenBackIntoTheSessionStore() = runTest {
        val f = Fixture()
        f.defaults.setDefault(DialogTarget.Device)

        assertThat(f.router.dialogFor("sess-never-configured")).isInstanceOf(Outcome.Ok::class.java)

        assertThat(f.targets.of("sess-never-configured")).isNull()
        assertThat(f.targets.rawOf("sess-never-configured")).isNull()
    }

    /**
     * ★★ 「从未设置过默认」与「显式把默认设成 Device」**仍然可区分**。
     *
     * 两者路由结果相同（都是端侧），但库里的状态不同（NULL vs `'LOCAL'`）：
     * 路由层看不出来 ≠ 可以抹平 —— 将来的「你还没选过后端」引导靠的就是它。
     */
    @Test
    fun neverSetDefaultAndExplicitDeviceDefaultRemainDistinguishable() = runTest {
        val neverSet = Fixture()
        assertThat(neverSet.defaults.isExplicitlySet()).isFalse()
        assertThat(neverSet.defaults.rawOf()).isNull()
        assertThat((neverSet.router.dialogFor("s") as Outcome.Ok).value)
            .isSameInstanceAs(neverSet.localDialog)
        // ★ 读一次之后，**依然**是「从未设置过」
        assertThat(neverSet.defaults.isExplicitlySet()).isFalse()

        val explicit = Fixture()
        explicit.defaults.setDefault(DialogTarget.Device)
        assertThat(explicit.defaults.isExplicitlySet()).isTrue()
        assertThat(explicit.defaults.rawOf()).isEqualTo(DialogTargetCodec.VALUE_DEVICE)
        assertThat((explicit.router.dialogFor("s") as Outcome.Ok).value)
            .isSameInstanceAs(explicit.localDialog)
    }

    /**
     * ★ 全局默认**不绕过**任何既有的守卫：未知文本照样报错、凭证没了照样 fail-closed。
     */
    @Test
    fun globalDefaultGoesThroughTheSameGuardsAsAnExplicitTarget() = runTest {
        run {
            val f = Fixture()
            f.defaults.setDefault(DialogTargetCodec.decode("GARBAGE"))
            val r = f.router.dialogFor("s")
            assertThat((r as Outcome.Err).error.code)
                .isEqualTo(DefaultModelRouter.CODE_UNRECOGNIZED_TARGET)
        }
        run {
            val f = Fixture(creds = FakeCredentials(existing = mutableSetOf()))
            f.defaults.setDefault(DialogTarget.Remote(CredentialRef("svc-a")))
            val r = f.router.dialogFor("s")
            assertThat((r as Outcome.Err).error.code)
                .isEqualTo(CloudFailureCodes.CREDENTIAL_MISSING)
            assertThat(f.creds.secretReads).isEqualTo(0)
        }
    }

    /**
     * ★ `ROUTER_NO_TARGET` **已不可达**：三级优先的末层由 `default()` 兜住，
     * 而它永不返回 `null` ⇒ 任何组合都不该再产出那个码。
     *
     * 它防的是「有人把 `?:` 又改回 `return Err(NO_TARGET)`」。
     */
    @Test
    fun noTargetErrorCodeIsUnreachableAcrossTheMatrix() = runTest {
        // ① 默认从未设置过
        val unset = Fixture()
        // ② 默认 = 端侧
        val deviceDefault = Fixture()
        deviceDefault.defaults.setDefault(DialogTarget.Device)
        // ③ 默认 = 云端（凭证在）
        val cloudDefault = Fixture()
        cloudDefault.defaults.setDefault(DialogTarget.Remote(CredentialRef("svc-a")))
        // ④ 默认 = 云端（凭证已删）
        val cloudNoCred = Fixture(creds = FakeCredentials(existing = mutableSetOf()))
        cloudNoCred.defaults.setDefault(DialogTarget.Remote(CredentialRef("svc-a")))
        // ⑤ 默认 = 未知文本
        val garbageDefault = Fixture()
        garbageDefault.defaults.setDefault(DialogTargetCodec.decode("GARBAGE"))

        listOf(unset, deviceDefault, cloudDefault, cloudNoCred, garbageDefault).forEach { f ->
            val code = (f.router.dialogFor("sess-x") as? Outcome.Err)?.error?.code
            assertThat(code).isNotEqualTo(LEGACY_NO_TARGET_CODE)
        }
        // ★ 顺带钉死「至少有两种组合真的走得通」，防止上面退化成空跑
        assertThat((unset.router.dialogFor("sess-x") as Outcome.Ok).value)
            .isSameInstanceAs(unset.localDialog)
        assertThat((cloudDefault.router.dialogFor("sess-x") as Outcome.Ok).value)
            .isSameInstanceAs(cloudDefault.remote)
    }

    // ═══ 2. 端侧派发 ═══

    @Test
    fun deviceTargetYieldsTheLocalDialogAdapter() = runTest {
        val f = Fixture()
        f.targets.select("sess-1", DialogTarget.Device)

        val r = f.router.dialogFor("sess-1")

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat((r as Outcome.Ok).value).isSameInstanceAs(f.localDialog)
        // ★ 只是「取到入口」，**没有**顺手开会话（开会话必须由调用方显式做）
        assertThat(f.device.openSessionCalls).isEqualTo(0)
    }

    // ═══ 3. 云端派发 ═══

    @Test
    fun remoteTargetYieldsTheRemotePortWhenTheCredentialStillExists() = runTest {
        val f = Fixture()
        f.targets.select("sess-1", DialogTarget.Remote(CredentialRef("svc-a")))

        val r = f.router.dialogFor("sess-1")

        assertThat((r as Outcome.Ok).value).isSameInstanceAs(f.remote)
        // ★★ 派发阶段**一次都不读明文**：只判「存在」，不判「内容」
        assertThat(f.creds.secretReads).isEqualTo(0)
    }

    /**
     * ★ 凭证被删（一键清除之后）⇒ 在**返回远端入口之前**就失败。
     *
     * 关键点：`dialogFor` 绝不把一个「会用空凭证去发请求」的端口交出去。
     */
    @Test
    fun remoteTargetWithDeletedCredentialFailsBeforeHandingOutThePort() = runTest {
        val f = Fixture(creds = FakeCredentials(existing = mutableSetOf()))
        f.targets.select("sess-1", DialogTarget.Remote(CredentialRef("svc-a")))

        val r = f.router.dialogFor("sess-1")

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(CloudFailureCodes.CREDENTIAL_MISSING)
        assertThat(f.creds.secretReads).isEqualTo(0)
    }

    // ═══ 4. DOM 锁 A 的运行时投影 ═══

    /**
     * ★★ 端侧引擎的位置**只**接受 [DeviceModelPort]。
     *
     * 编译期事实：`FakeCloudDialog` 只实现 `DialogModelPort`，
     * 因此 `DefaultModelRouter(device = fakeCloudDialog, …)` **不可编译**。
     * 运行时这里是它的投影 —— 断言这两个类型确实不相交，
     * 于是「云端适配器冒充端侧」在类型层面就不成立。
     *
     * ⚠ 局限：单元测试无法证明「某段代码编译不过」，只能证明类型不相交。
     *   真正的编译期证据是 `:cloudai` 的 `OpenAiCompatibleDialogPort`
     *   **只** `: DialogModelPort`（见该类声明）。
     */
    @Test
    fun cloudDialogIsNotADeviceModelPort() {
        val cloud: Any = FakeCloudDialog()

        assertThat(DeviceModelPort::class.java.isInstance(cloud)).isFalse()
        assertThat(DialogModelPort::class.java.isInstance(cloud)).isTrue()
    }

    // ═══ 5. permit：fail-closed ═══

    /**
     * ★★ 审计行 INSERT 失败 ⇒ **拿不到 permit** ⇒ 一个字节都发不出去。
     *
     * 失败方向必须是安全的：宁可整轮不出云，也不能「出去了但没记账」。
     */
    @Test
    fun permitOpenIsFailClosedWhenTheAuditInsertFails() = runTest {
        val f = Fixture(permits = FakePermitSink().apply { failOpen = true })
        val ctx = routeCtx(InferenceTarget.CloudRequested)
        val decision = DialogRoutingPort(TEST_CLOCK, f.creds).decide(ctx)

        val permit = f.router.openPermit(ctx, decision)

        assertThat(permit).isInstanceOf(Outcome.Err::class.java)
        assertThat(f.permits.opened).isEmpty()
    }

    @Test
    fun permitIsIssuedForTheDecidedTargetAndEndpoint() = runTest {
        val f = Fixture()
        val ctx = routeCtx(InferenceTarget.CloudRequested)
        val decision = DialogRoutingPort(TEST_CLOCK, f.creds).decide(ctx)

        val permit = f.router.openPermit(ctx, decision)

        assertThat(permit).isInstanceOf(Outcome.Ok::class.java)
        assertThat(f.permits.opened).hasSize(1)
        assertThat((permit as Outcome.Ok).value.endpointHost).isNotEmpty()
        // ★ 一次出境只允许一次尝试：重试必须重新申请（§4.4）
        assertThat(permit.value.allowedAttempts).isEqualTo(1)
    }

    @Test
    fun closingAPermitRecordsTheTerminalOutcome() = runTest {
        val f = Fixture()

        val r = f.router.closePermit(
            testPermit(),
            AuditOutcome.PARTIAL,
            EgressPhase.POST,
            deliveredBytes = 512,
            finishReasonRaw = "length",
        )

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat(f.permits.closed).containsExactly(AuditOutcome.PARTIAL)
    }

    private fun routeCtx(requested: InferenceTarget): RouteContext = RouteContext(
        agentId = "agent-1",
        sessionId = "sess-1",
        turnId = "turn-1",
        requested = requested,
        userGrantedThisTurn = true,
        estimatedContextTokens = 1024,
        needsLongContext = false,
        payloadClasses = emptySet(),
        kind = TaskKind.PureDialog,
        page = null,
    )

    private companion object {
        /**
         * ★ 故意用**字面量**而不是 `DefaultModelRouter.CODE_NO_TARGET`：
         *   那个常量已标记 `@Deprecated`（v5 起不可达），引用它会带警告；
         *   而这里要钉的恰恰是「这个码不再出现」，用字面量才不会跟着实现漂移。
         */
        const val LEGACY_NO_TARGET_CODE: String = "ROUTER_NO_TARGET"
    }
}
