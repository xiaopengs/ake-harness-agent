package com.ake.inference

import com.ake.core.common.Outcome
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudFailureCodes
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.DialogTarget
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
 *  ② 「没选过目标」≠「选了端侧」⇒ 前者必须报错，**不许猜**。
 *
 * ★ 全部替身都是纯 JVM 的 ⇒ 毫秒级，无需设备（§9.4）。
 */
class DefaultModelRouterTest {

    private class Fixture(
        val device: FakeDevice = FakeDevice(),
        val creds: FakeCredentials = FakeCredentials(),
        val permits: FakePermitSink = FakePermitSink(),
        val targets: InMemoryDialogTargetStore = InMemoryDialogTargetStore(),
    ) {
        val localDialog: DialogModelPort = LocalDialogAdapter(device)
        val remote: FakeCloudDialog = FakeCloudDialog()
        val router = DefaultModelRouter(
            device = device,
            localDialog = localDialog,
            remote = remote,
            targets = targets,
            creds = creds,
            permits = permits,
        )
    }

    // ═══ 1. 「没选过目标」≠「选了端侧」 ═══

    /**
     * ★★ 未登记目标 ⇒ **Err**，不是默默走端侧。
     *
     * 若这里返回端侧，「用户明明选了云端、但会话悄悄跑在本地」就会变成一个
     * 用户无法察觉的静默降级 —— 与 §4.2 禁止静默回落是同一条理由。
     */
    @Test
    fun unregisteredTargetIsAnErrorNotASilentLocalFallback() = runTest {
        val f = Fixture()

        val r = f.router.dialogFor("sess-never-configured")

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(DefaultModelRouter.CODE_NO_TARGET)
        assertThat(f.device.openSessionCalls).isEqualTo(0)
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
}
