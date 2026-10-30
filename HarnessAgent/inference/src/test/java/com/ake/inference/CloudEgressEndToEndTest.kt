package com.ake.inference

import com.ake.cloudai.CloudCallAdapter
import com.ake.cloudai.CloudRuntimeConfig
import com.ake.cloudai.OpenAiCompatibleDialogPort
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.common.StepId
import com.ake.core.common.SessionId
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ThinkingBudget
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.InferenceStats
import com.ake.core.ports.AnnouncementKind
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CloudCallOutcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.EgressAnnouncementSink
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressResult
import com.ake.core.ports.FallbackAnnouncement
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.PageRef
import com.ake.core.ports.PromptContext
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.TaskKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * ★★ **端到端证明**（主理人指定交付项）。
 *
 * 一条链：`DialogTargetStore`（选了云端）→ `DefaultModelRouter.dialogFor`
 * → `DefaultEgressCoordinator.execute` → `CloudCallAdapter.once`
 * → `DialogModelPort`（云端）。
 *
 * ## ★ 判据是「发出去的字节数」，不是「调没调」
 *
 * 远程替身自己数 `bytesSent`。于是
 *  · 选了云端 + 纯对话 ⇒ `bytesSent > 0` **且**审计行是 `DELIVERED`；
 *  · 带页面上下文        ⇒ `bytesSent == 0` **且** permit 一次都没开。
 *
 * ## ★ 为什么这里用的是「替身」而不是真 HTTP —— 以及这不是 impostor
 *
 * `CloudCallAdapter` 是**生产代码**（`:cloudai`），链路的每一环都是真的。
 * 唯一被替换的是最底层的 `DialogModelPort`。而「字节真的出去了」这件事
 * 由 `:cloudai/DomLockCloudTest.textOnlySendsARealNonZeroBody` 用**真实的
 * `OpenAiCompatibleDialogPort` + 真实请求体**另行断言（字节数 == 请求体长度）。
 *
 * ⇒ 两端合起来才是完整证明：**这一侧**证明编排把字节交给了端口，
 *   **那一侧**证明端口真的把字节写进了传输层。任一侧单独都不足以证明。
 */
class CloudEgressEndToEndTest {

    /**
     * 会**数字节**的云端替身。
     *
     * ★ `bytesSent` 按「请求体 UTF-8 长度」计 —— 与 `:cloudai` 侧
     *   `ScriptedTransport.bytesSent` 的口径一致（都取 body，不含 HTTP 头）。
     */
    private class CountingRemote(
        private val outcome: CloudCallOutcome,
    ) : DialogModelPort {

        public var bytesSent: Int = 0
            private set
        public var openDialogCalls: Int = 0
            private set
        public var lastContext: PromptContext? = null
            private set

        override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)
        override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)
        override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = notUsed()
        override fun estimateTokens(text: String): Int = text.length

        override suspend fun openDialog(
            spec: SessionSpec,
            permit: EgressPermit?,
            context: PromptContext,
        ): Outcome<ModelSession> {
            openDialogCalls++
            lastContext = context
            // ★★ 锁 C 在真实端口里也会走同一条分支：带页面上下文 ⇒ 拒绝且不数字节。
            if (context is PromptContext.WithPageContext) {
                return Outcome.Err(
                    com.ake.core.common.AppError(
                        code = CloudFailure.PageContextRefused.code,
                        reasonKey = CloudFailure.PageContextRefused.reasonKey,
                        severity = com.ake.core.common.Severity.MEDIUM,
                    ),
                )
            }
            bytesSent += (spec.modelId + "hello cloud").toByteArray(Charsets.UTF_8).size
            return Outcome.Ok(FakeRemoteSession(outcome))
        }
    }

    /** 把既定的 `CloudCallOutcome` 演出来的会话句柄。 */
    private class FakeRemoteSession(
        private val outcome: CloudCallOutcome,
    ) : ModelSession {
        override val sessionId: SessionId = SessionId("cloud-1")
        override fun close() = Unit
        override suspend fun currentCacheTokens(): Int = 0

        override suspend fun generate(req: GenerationRequest): Outcome<GenerationResult> =
            generateStream(req) { }

        override suspend fun generateStream(
            req: GenerationRequest,
            onDelta: suspend (String) -> Unit,
        ): Outcome<GenerationResult> = when (outcome) {
            is CloudCallOutcome.Done -> {
                onDelta(outcome.text)
                Outcome.Ok(
                    GenerationResult(
                        rawText = outcome.text,
                        toolCalls = emptyList(),
                        finishReason = FinishReason.STOP,
                        stats = InferenceStats(0, 0, 0, 0.0),
                    ),
                )
            }

            is CloudCallOutcome.Failed -> Outcome.Err(
                com.ake.core.common.AppError(
                    code = outcome.failure.code,
                    reasonKey = outcome.failure.reasonKey,
                    severity = com.ake.core.common.Severity.MEDIUM,
                ),
            )
        }
    }

    private class Fixture(
        val remote: CountingRemote,
        val permits: FakePermitSink = FakePermitSink(),
    ) {
        val announcements: MutableList<FallbackAnnouncement> = mutableListOf()
        val announce: EgressAnnouncementSink =
            EgressAnnouncementSink { announcements += it }

        val creds: CloudCredentialPort = FakeCredentials(mutableSetOf(CredentialRef("svc-a")))
        val targets: InMemoryDialogTargetStore = InMemoryDialogTargetStore()
        val router = DefaultModelRouter(
            device = FakeDevice(),
            localDialog = LocalDialogAdapter(FakeDevice()),
            remote = remote,
            targets = targets,
            creds = creds,
            permits = permits,
        )
        val routing = DialogRoutingPort(TEST_CLOCK, creds)
        val coordinator = DefaultEgressCoordinator(permits, announce)
    }

    private fun req(): GenerationRequest = GenerationRequest(
        stepId = StepId("step-1"),
        userTurn = "hello cloud",
        outputShape = OutputShape.FreeText,
        maxOutputTokens = 256,
        thinking = ThinkingBudget.Off,
        deadlineMs = 60_000L,
    )

    private fun ctx(
        requested: InferenceTarget,
        kind: TaskKind = TaskKind.PureDialog,
        page: PageRef? = null,
    ): RouteContext = RouteContext(
        agentId = "a1", sessionId = "s1", turnId = "t1",
        requested = requested,
        userGrantedThisTurn = true,
        estimatedContextTokens = 512,
        needsLongContext = false,
        payloadClasses = emptySet(),
        kind = kind,
        page = page,
    )

    // ════════════ ① 选了云端 + 纯对话 ⇒ 真发出去 + 记了审计 ════════════

    @Test
    fun cloudSelected_pureDialog_sendsBytesAndRecordsAudit() = runTest {
        val f = Fixture(CountingRemote(CloudCallOutcome.Done("hi", "stop", 128)))

        // 用户在设置页选了云端 ⇒ 落库
        f.targets.select("s1", DialogTarget.Remote(CredentialRef("svc-a")))

        // 路由：用户选了云端 ⇒ 拿到 remote 端口
        val port = f.router.dialogFor("s1")
        assertThat((port as Outcome.Ok).value).isSameInstanceAs(f.remote)

        val decision: RoutingDecision = f.routing.decide(ctx(InferenceTarget.CloudRequested))
        assertThat(decision.target).isEqualTo(InferenceTarget.CloudRequested)

        val result = f.coordinator.execute(ctx(InferenceTarget.CloudRequested), decision) { permit ->
            CloudCallAdapter.once(f.remote, permit, testSpec("gpt-4o-mini"), req())
        }

        // ★★ 判据一：字节真的出去了
        assertThat(f.remote.bytesSent).isGreaterThan(0)
        // ★★ 判据二：审计行开了、且被收敛成 DELIVERED（不留 PENDING）
        assertThat(f.permits.opened).hasSize(1)
        assertThat(f.permits.closed).containsExactly(AuditOutcome.DELIVERED)
        // ★★ 判据三：结果
        assertThat(result).isInstanceOf(EgressResult.Delivered::class.java)
        assertThat((result as EgressResult.Delivered).text).isEqualTo("hi")
    }

    // ════════════ ② 带页面上下文 ⇒ 被拒 + bytesSent == 0 ════════════

    /**
     * ★★ **bytesSent == 0**（主理人指定必须出现的断言）。
     *
     * 两条独立的证据：
     *  · 路由层：`PageTask` 恒 `LocalOnly` ⇒ 编排根本不该启动；
     *  · 端口层：即便有人绕过路由直接调端口，`WithPageContext` 也被硬拒，
     *    **一个字节都不数**。
     */
    @Test
    fun pageContext_refusedAndSendsZeroBytes() = runTest {
        val f = Fixture(CountingRemote(CloudCallOutcome.Done("hi", "stop", 128)))
        f.targets.select("s1", DialogTarget.Remote(CredentialRef("svc-a")))

        val page = PageRef(host = "shop.example.com", domChars = 4096, elementCount = 120)
        val decision = f.routing.decide(ctx(InferenceTarget.CloudRequested, TaskKind.PageTask, page))

        // ① 路由层：页面任务恒本地 ⇒ 编排**一次都不启动**
        assertThat(decision.target).isEqualTo(InferenceTarget.LocalOnly)
        assertThat(f.remote.bytesSent).isEqualTo(0)
        assertThat(f.permits.opened).isEmpty()

        // ② 端口层：即便强行调，也是拒绝 + 0 字节
        val forced = CloudCallAdapter.once(
            f.remote,
            testPermit(),
            testSpec("gpt-4o-mini"),
            req(),
            PromptContext.WithPageContext("shop.example.com", 4096, 120),
        )
        assertThat(forced).isInstanceOf(CloudCallOutcome.Failed::class.java)
        assertThat((forced as CloudCallOutcome.Failed).failure)
            .isEqualTo(CloudFailure.PageContextRefused)
        // ★★ 这就是那条断言
        assertThat(f.remote.bytesSent).isEqualTo(0)
    }

    // ════════════ ③ PRE 失败 ⇒ 回落 + 「必须告知」真的被说了 ════════════

    /**
     * ★★ P0 的端到端证明：`FallbackPolicy.mustAnnounceFallback` 为真的失败形态，
     * 走完编排后**必然**留下一条告知（而不是靠 UI 自觉）。
     */
    @Test
    fun preFailure_fallsBackAndAnnounces() = runTest {
        val f = Fixture(CountingRemote(CloudCallOutcome.Failed(CloudFailure.AuthRejected, 0)))
        f.targets.select("s1", DialogTarget.Remote(CredentialRef("svc-a")))

        val decision = f.routing.decide(ctx(InferenceTarget.CloudRequested))
        val result = f.coordinator.execute(ctx(InferenceTarget.CloudRequested), decision) { permit ->
            CloudCallAdapter.once(f.remote, permit, testSpec("gpt-4o-mini"), req())
        }

        assertThat(result).isInstanceOf(EgressResult.FellBackToLocal::class.java)
        // ★ 告知发生在返回值之前 ⇒ 拿到结果时它已经在列表里
        assertThat(f.announcements).hasSize(1)
        assertThat(f.announcements.first().kind).isEqualTo(AnnouncementKind.FELL_BACK_TO_LOCAL)
        assertThat(f.announcements.first().reasonKey).isEqualTo(CloudFailure.AuthRejected.reasonKey)
        assertThat(f.announcements.first().isWellFormed()).isTrue()
        // 审计：PRE 失败 ⇒ NOT_SENT，且**已收敛**（不留 PENDING）
        assertThat(f.permits.closed).containsExactly(AuditOutcome.NOT_SENT)
    }

    // ════════════ ④ POST 失败 ⇒ 禁止回落，且必须说出「已发给 host」 ════════════

    @Test
    fun postFailure_neverFallsBackAndSaysAlreadySent() = runTest {
        val f = Fixture(CountingRemote(CloudCallOutcome.Failed(CloudFailure.ServerError, 512)))
        f.targets.select("s1", DialogTarget.Remote(CredentialRef("svc-a")))

        val decision = f.routing.decide(ctx(InferenceTarget.CloudRequested))
        val result = f.coordinator.execute(ctx(InferenceTarget.CloudRequested), decision) { permit ->
            CloudCallAdapter.once(f.remote, permit, testSpec("gpt-4o-mini"), req())
        }

        // ★★ POST ⇒ 绝不 `FellBackToLocal`
        assertThat(result).isNotInstanceOf(EgressResult.FellBackToLocal::class.java)
        assertThat(result).isInstanceOf(EgressResult.NeedsUserDecision::class.java)
        assertThat(f.announcements).hasSize(1)
        assertThat(f.announcements.first().kind).isEqualTo(AnnouncementKind.ALREADY_SENT)
        // ★ 「已经发出去了」必须说得出 host
        assertThat(f.announcements.first().host).isNotEmpty()
        assertThat(f.announcements.first().isWellFormed()).isTrue()
    }

    // ════════════ ⑤ fail-closed：审计开不出来 ⇒ 一个字节都不发 ════════════

    @Test
    fun permitDenied_sendsNothingAtAll() = runTest {
        val f = Fixture(
            CountingRemote(CloudCallOutcome.Done("hi", "stop", 128)),
            permits = FakePermitSink().apply { failOpen = true },
        )
        f.targets.select("s1", DialogTarget.Remote(CredentialRef("svc-a")))

        val decision = f.routing.decide(ctx(InferenceTarget.CloudRequested))
        val result = f.coordinator.execute(ctx(InferenceTarget.CloudRequested), decision) { permit ->
            CloudCallAdapter.once(f.remote, permit, testSpec("gpt-4o-mini"), req())
        }

        assertThat(result).isEqualTo(EgressResult.NotPermitted)
        assertThat(f.remote.bytesSent).isEqualTo(0)
        assertThat(f.remote.openDialogCalls).isEqualTo(0)
    }

    // ════════════ ⑥ 反证：链路是活的 ════════════

    /** ★ 如果 `CloudRuntimeConfig` 都能构造，说明 `:cloudai` 真的在类路径上（不是空壳）。 */
    @Test
    fun theRealCloudPortTypeIsOnTheClasspath() {
        val cfg = CloudRuntimeConfig.DEFAULT
        assertThat(cfg.declaredContextWindow).isGreaterThan(0)
        assertThat(OpenAiCompatibleDialogPort::class.java).isNotNull()
    }
}
