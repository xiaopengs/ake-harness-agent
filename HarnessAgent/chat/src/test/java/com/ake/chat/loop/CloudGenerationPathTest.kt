package com.ake.chat.loop

import com.ake.agent.context.InjectionIsolator
import com.ake.agent.loop.TokenStream
import com.ake.agent.planner.DefaultSystemCharter
import com.ake.chat.ctx.ChatHistoryCollector
import com.ake.chat.ctx.ModeProfileRegistry
import com.ake.chat.egress.ChatEgress
import com.ake.chat.fixtures.FakeChatStore
import com.ake.chat.fixtures.FakeGrants
import com.ake.chat.fixtures.FakeHealth
import com.ake.chat.fixtures.FakeHumanGate
import com.ake.chat.fixtures.FakeModelPort
import com.ake.chat.fixtures.FakeProfileStore
import com.ake.chat.fixtures.FakeTaskRunner
import com.ake.chat.fixtures.FakeTraceStore
import com.ake.chat.fixtures.RecordingPolicy
import com.ake.chat.skill.BrowserTaskSkill
import com.ake.chat.skill.ChatSkillRegistry
import com.ake.chat.skill.SkillRouter
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.RedactionGate
import com.ake.core.common.Severity
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.AnnouncementKind
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.CloudCallOutcome
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.EgressAnnouncementSink
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.FallbackAnnouncement
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.ModelCapabilities
import com.ake.core.ports.ModelRouter
import com.ake.core.ports.NoAbortSignal
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.PromptContext
import com.ake.core.ports.Resolved
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.inference.DefaultEgressCoordinator
import com.ake.inference.DialogRoutingPort
import com.ake.inference.LocalDialogAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★★ docs/11 §4 的 T1–T6：**云端生成路径**的端到端断言。
 *
 * ## ★ 判据是「发出去的字节数」+「审计行」，不是「调没调」
 *
 *  · `bytesSent` 由云端端口替身自己数（请求体 UTF-8 长度）；
 *  · 审计行由**生产注入点**捕获（`ChatEgress.AuditSink` / `EgressPermitSink`）
 *    —— 这两个正是 `:app` 真正绑定的 Port，不是 DAO 读回自己的写入。
 *    `:data` 侧另有 `sqlite3` 直读 `route_decision` 的既有测试
 *    ⇒ **这一层**证明「编排交出了什么」，**那一层**证明「库里真的落了什么」。
 *
 * ## ★★ 一轮云端 = **两行** `route_decision`（主理人裁决 1）
 *
 *  · 决策行：`ChatEgress.decide()` 落（`payloadBytes = 0`、无 verdict、无 idempotencyKey）；
 *  · 出境行：`EgressPermitSink.open()` 落 PENDING → `close()` UPDATE 终态。
 * 两个不同事件，本就该两笔。**不**为了「断言写得顺手」绕过 `egress.decide`。
 */
/** 替身里「这条分支不该被走到」的返回。放到文件级 ⇒ 嵌套类也能用。 */
private fun notUsed(): Outcome<Nothing> = Outcome.Err(
    AppError(code = "TEST_NOT_USED", reasonKey = "err_test", severity = Severity.LOW),
)

/**
 * ★★ 云端那一轮**怎么结束**。
 *
 * 三种形态的差别**只在字节有没有出去**，这正是 PRE / POST 的分界：
 *  · [Pre]  —— `openDialog` 就失败 ⇒ 一个字节都没发（连接 / 鉴权 / 页面上下文）；
 *  · [Post] —— 会话开成了（字节已发），生成阶段才断 ⇒ **禁止**回落；
 *  · [Ok]   —— 完整收到。
 */
private sealed interface Script {
    data class Ok(val text: String, val deliveredBytes: Int) : Script
    data class Pre(val failure: CloudFailure) : Script
    data class Post(val failure: CloudFailure, val deliveredBytes: Int) : Script
}

/**
 * ★ 测试侧的 `AppError.code → CloudFailure` 映射。
 *
 * `:chat` **看不见** `:cloudai`（L3 边为零），所以这里不能复用
 * `CloudCallAdapter.codeToFailure`。认不出来的码一律按 `MalformedResponse`
 * （= POST、不回落）处理 —— 与生产映射「认不出 ⇒ 不回落」的方向一致。
 */
private fun codeToFailure(code: String): CloudFailure = when (code) {
    CloudFailure.PageContextRefused.code -> CloudFailure.PageContextRefused
    CloudFailure.ConnectFailed.code -> CloudFailure.ConnectFailed
    CloudFailure.AuthRejected.code -> CloudFailure.AuthRejected
    else -> CloudFailure.MalformedResponse
}

class CloudGenerationPathTest {

    // ───────────────────────────── 替身 ─────────────────────────────

    /** 会**数字节**的云端端口。★ `WithPageContext` 硬拒，且**拒在数字节之前**。 */
    private class CountingCloudPort(
        private val script: Script,
    ) : DialogModelPort {
        var bytesSent = 0
            private set
        var openDialogCalls = 0
            private set

        override val capabilities: ModelCapabilities = ModelCapabilities.REMOTE_DEFAULT

        override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)
        override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)
        override fun estimateTokens(text: String): Int = text.length

        override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = notUsed()

        override suspend fun openDialog(
            spec: SessionSpec,
            permit: EgressPermit?,
            context: PromptContext,
        ): Outcome<ModelSession> {
            openDialogCalls++
            // ★★ 锁 C：页面上下文 ⇒ 一个字节都不许发，且**在记账之前**就拒。
            if (context is PromptContext.WithPageContext) {
                return Outcome.Err(
                    AppError(
                        code = CloudFailure.PageContextRefused.code,
                        reasonKey = CloudFailure.PageContextRefused.reasonKey,
                        severity = Severity.MEDIUM,
                    ),
                )
            }
            // ★ PRE 失败发生在「开会话」这一步 ⇒ 请求体根本没构造 ⇒ 零字节。
            if (script is Script.Pre) {
                return Outcome.Err(
                    AppError(
                        code = script.failure.code,
                        reasonKey = script.failure.reasonKey,
                        severity = Severity.MEDIUM,
                    ),
                )
            }
            bytesSent += (spec.modelId + (permit?.endpointHost ?: "")).toByteArray(Charsets.UTF_8).size
            return Outcome.Ok(SinkSession(script))
        }
    }

    private class SinkSession(private val script: Script) : ModelSession {
        override val sessionId = com.ake.core.common.SessionId("cloud")
        override fun close() = Unit
        override suspend fun currentCacheTokens(): Int = 0
        override suspend fun generate(req: GenerationRequest): Outcome<com.ake.core.modelapi.GenerationResult> =
            notUsed()
    }

    /** 端侧引擎替身。**只**用于「路由选了端侧」这条判定，不参与生成。 */
    private class LocalPort : DeviceModelPort {
        override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun unload(): Outcome<Unit> = Outcome.Ok(Unit)
        override val runtimeState: Flow<ModelRuntimeState> = flowOf(ModelRuntimeState.Unloaded)
        override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = notUsed()
        override fun estimateTokens(text: String): Int = text.length
    }

    /** 审计替身：分别记录「决策行」与「出境行」及其投递字节。 */
    private class RecordingPermits : EgressPermitSink {
        val opened = mutableListOf<RoutingDecision>()
        val closed = mutableListOf<AuditOutcome>()
        val deliveredBytes = mutableListOf<Int>()

        override suspend fun open(ctx: RouteContext, decision: RoutingDecision): Outcome<EgressPermit> {
            opened += decision
            return Outcome.Ok(
                EgressPermit(
                    auditRowId = "row-${opened.size}",
                    decidedAt = 1L,
                    endpointHost = "gw.example.com",
                    credentialRef = CredentialRef("svc-a"),
                ),
            )
        }

        override suspend fun close(
            permit: EgressPermit,
            outcome: AuditOutcome,
            phase: EgressPhase,
            deliveredBytes: Int,
            finishReasonRaw: String?,
        ): Outcome<Unit> {
            closed += outcome
            this.deliveredBytes += deliveredBytes
            return Outcome.Ok(Unit)
        }

        override suspend fun sweepPending(olderThanMs: Long): Int = 0
    }

    private class Fixture(
        val script: Script,
        /** true ⇒ 路由选端侧（T1） */
        val chooseDevice: Boolean = false,
        /** true ⇒ 组合根注入的 `PromptContext` 是 `WithPageContext`（T3） */
        val pageContext: Boolean = false,
        val permits: RecordingPermits = RecordingPermits(),
    ) {
        val clock: Clock = Clock { 1_700_000_000_000L }
        val cloud = CountingCloudPort(script)
        /**
         * ★ `ChatEgress.AuditSink` 抓到的**全部**决策行。
         *
         * ⚠ 每一轮本来就有**一行既有的账**：Router Turn（`ChatLoop.kt:297`）也走
         *   `egress.decide`，`agentId` 是人格所属 agent（`agent-1`）、`requested=LocalOnly`。
         *   那是接线之前就有的行为，**不是**本轮新增。⇒ 断言云端账必须按
         *   `agentId == ChatLoop.AGENT_ID_CHAT` 过滤（见 [cloudRows]）。
         */
        val decisions = mutableListOf<Pair<RouteContext, RoutingDecision>>()
        val announcements = mutableListOf<FallbackAnnouncement>()
        val model = FakeModelPort(listOf("""{"mode":"reply"}"""), "本地答案")

        /** ★ 本轮「云端路径」写下的决策账（既有那行 Router 账不在此列）。 */
        val cloudRows: List<Pair<RouteContext, RoutingDecision>>
            get() = decisions.filter { it.first.agentId == ChatLoop.AGENT_ID_CHAT }

        /** ★ 端侧「回答轮」被真正执行过几次（`OutputShape.FreeText` = 回答，不是 Router）。 */
        val localAnswerTurns: Int
            get() = model.lastSession?.requests?.count { it.outputShape is OutputShape.FreeText } ?: 0

        val creds: CloudCredentialPort = object : CloudCredentialPort {
            override suspend fun upsert(
                ref: CredentialRef,
                cfg: EndpointConfig,
                secret: PlainSecret,
            ): Outcome<Unit> = Outcome.Ok(Unit)

            override fun refs(): Flow<List<CredentialRef>> = flowOf(listOf(CredentialRef("svc-a")))
            override suspend fun configOf(ref: CredentialRef): Outcome<EndpointConfig> = notUsed()
            override suspend fun secretOf(ref: CredentialRef): Outcome<PlainSecret> = notUsed()
            override suspend fun delete(ref: CredentialRef): Outcome<Unit> = Outcome.Ok(Unit)
            override suspend fun exists(ref: CredentialRef): Boolean = true
        }

        val router = object : ModelRouter {
            override suspend fun dialogFor(sessionId: String): Outcome<DialogModelPort> =
                Outcome.Ok(if (chooseDevice) LocalDialogAdapter(LocalPort()) else cloud)
        }

        val coordinator = DefaultEgressCoordinator(
            permits,
            EgressAnnouncementSink { announcements += it },
        )

        val store = FakeChatStore()
        val policy = RecordingPolicy()
        val loop: ChatLoop = run {
            val collector = ChatHistoryCollector { model.estimateTokens(it) }
            val skills = ChatSkillRegistry(
                handlers = listOf(BrowserTaskSkill(FakeTaskRunner(), clock)),
                grants = FakeGrants(emptyMap()),
            )
            ChatLoop(
                model = model,
                focus = InferenceFocusArbiter(clock),
                store = store,
                profiles = FakeProfileStore(),
                skills = skills,
                policy = policy,
                health = FakeHealth(),
                egress = ChatEgress(
                    DialogRoutingPort(clock, creds),
                    // ★ 本路径不构造 `OutboundPayload` ⇒ 这道闸在本文件里不参与；
                    //   给它一个真闸（而不是 `LocalOnlyEgressGate`）只为形状对齐。
                    com.ake.chat.egress.CloudCapableEgressGate(),
                    ChatEgress.AuditSink { ctx, decision, _, _, _ -> decisions += ctx to decision },
                    clock,
                ),
                humanGate = FakeHumanGate(Resolved.Proceed),
                traceStore = FakeTraceStore(),
                clock = clock,
                tokenStream = TokenStream.SINGLE_SHOT,
                registry = ModeProfileRegistry(),
                charter = DefaultSystemCharter(),
                redactionGate = RedactionGate.SCRUB_ONLY,
                rehydrator = ChatRehydrator(store, collector) { model.estimateTokens(it) },
                historyCollector = collector,
                isolator = InjectionIsolator(policy),
                router = SkillRouter(nowMillis = { 1_000L }),
                modelRouter = router,
                egressCoordinator = coordinator,
                cloudCall = ChatLoop.CloudCall { port, permit, spec, _ ->
                    // ★ 与 `:app` 注入的适配器同一语义：开会话 → 生成。
                    val context = if (pageContext) {
                        PromptContext.WithPageContext(host = "shop.example.com", domChars = 4096, elementCount = 120)
                    } else {
                        PromptContext.TextOnly
                    }
                    when (val opened = port.openDialog(spec, permit, context)) {
                        // ★ PRE：`openDialog` 就失败 ⇒ 零字节（生产侧由 `:cloudai` 反解码）
                        is Outcome.Err -> CloudCallOutcome.Failed(codeToFailure(opened.error.code), 0)

                        is Outcome.Ok -> when (script) {
                            is Script.Ok -> CloudCallOutcome.Done(script.text, "stop", script.deliveredBytes)
                            is Script.Post -> CloudCallOutcome.Failed(script.failure, script.deliveredBytes)
                            // 开会话已经失败过，走不到这里
                            is Script.Pre -> CloudCallOutcome.Failed(script.failure, 0)
                        }
                    }
                },
            )
        }

        fun turnReq(turnId: String) = ChatTurnRequest(
            sessionId = "sess-1",
            clientRequestId = "req-$turnId",
            turnId = turnId,
            userText = "你好",
            abort = NoAbortSignal,
            allowSkillInvocation = false,
        )

        fun text(events: List<ChatEvent>): String =
            events.filterIsInstance<ChatEvent.AssistantDelta>().joinToString("") { it.delta }
    }

    // ════════════ T1：选 Device ⇒ 云端零调用、零审计行 ════════════

    @Test
    fun t1_deviceTarget_cloudIsNeverCalledAndNoAuditRow() = runBlocking {
        val f = Fixture(Script.Ok("云端答案", 64), chooseDevice = true)

        val events = f.loop.turn(f.turnReq("t1")).toList()

        // ★★ 判据一：云端端口一次都没被碰
        assertThat(f.cloud.openDialogCalls).isEqualTo(0)
        assertThat(f.cloud.bytesSent).isEqualTo(0)
        // ★★ 判据二：云端路径**一行账都没多写**（先派发、后记账的顺序保证了这点）。
        //    只剩 Router Turn 那一行既有账（`agentId=agent-1`），与本轮无关。
        assertThat(f.cloudRows).isEmpty()
        assertThat(f.permits.opened).isEmpty()
        assertThat(f.decisions).hasSize(1)
        assertThat(f.decisions.single().second.target).isEqualTo(InferenceTarget.LocalOnly)
        // ★★ 判据三：端侧照常出字 —— 不是「两边都没跑」
        assertThat(f.localAnswerTurns).isEqualTo(1)
        assertThat(f.text(events)).contains("本地答案")
    }

    // ════════════ T2：Remote + TextOnly ⇒ 有字节、两行账、turnId 一致 ════════════

    @Test
    fun t2_remoteTarget_sendsBytesAndWritesBothAuditRows() = runBlocking {
        val f = Fixture(Script.Ok("云端答案", 64))

        val events = f.loop.turn(f.turnReq("t2")).toList()

        // ★★ 判据一：字节真的出去了
        assertThat(f.cloud.bytesSent).isGreaterThan(0)
        assertThat(f.cloud.openDialogCalls).isEqualTo(1)
        // ★★ 判据二（裁决 1）：云端路径写**两笔**账 —— 决策账 + 出境账
        assertThat(f.cloudRows).hasSize(1)
        assertThat(f.permits.opened).hasSize(1)
        assertThat(f.permits.closed).containsExactly(AuditOutcome.DELIVERED)
        assertThat(f.permits.deliveredBytes).containsExactly(64)
        // ★★ 判据三（主理人指定）：两行 turnId 相同，且等于本轮
        val (ctx, decision) = f.cloudRows.single()
        assertThat(ctx.turnId).isEqualTo("t2")
        assertThat(decision.target).isEqualTo(InferenceTarget.CloudRequested)
        // ★★ 判据四：用的是云端文本，端侧回答轮一次都没跑
        assertThat(f.text(events)).contains("云端答案")
        assertThat(f.localAnswerTurns).isEqualTo(0)
    }

    // ════════════ T3（最关键）：Remote + WithPageContext ⇒ 零字节 ════════════

    /**
     * ★★ DOM 红线唯一能杀死「以为拦住了其实发出去了」的那条断言。
     *
     * ⚠ **本文件的 T3 是「组合根注入了 `WithPageContext`」这一路**。
     *   `:chat` 自身今天恒声明 `TaskKind.PureDialog`、且 `:app` 注入的 `cloudCall`
     *   恒传 `PromptContext.TextOnly` ⇒ 页面上下文**进不来**（见报告「还剩什么」）。
     *   于是这里把注入的 context 换成 `WithPageContext`，验证：
     *   就算它进来了，端口也会在记账之前拒掉，字节数为 0。
     */
    @Test
    fun t3_remoteTargetWithPageContext_sendsZeroBytes() = runBlocking {
        val f = Fixture(Script.Ok("云端答案", 64), pageContext = true)

        val events = f.loop.turn(f.turnReq("t3")).toList()

        // ★★ 判据一（红线）：一个字节都没出去
        assertThat(f.cloud.bytesSent).isEqualTo(0)
        assertThat(f.permits.deliveredBytes).containsExactly(0)
        // ★★ 判据二：出境账**有**一行，且终态是 NOT_SENT（零投递），不是 DELIVERED
        assertThat(f.permits.closed).containsExactly(AuditOutcome.NOT_SENT)
        // ★★ 判据三：PRE 失败 ⇒ 静默切回端侧重新生成（DOM 没离开过设备，本地能处理）
        assertThat(f.localAnswerTurns).isEqualTo(1)
        assertThat(f.text(events)).contains("本地答案")
        assertThat(f.announcements.single().kind).isEqualTo(AnnouncementKind.FELL_BACK_TO_LOCAL)
    }

    // ════════════ T4：PRE 失败 ⇒ 端侧确实执行 + 告知 FELL_BACK_TO_LOCAL ════════════

    @Test
    fun t4_preFailure_localActuallyRunsAndAnnouncesFallback() = runBlocking {
        // ★ PRE 且判据为「回落设备」的那一类（401/403）：`openDialog` 就失败 ⇒ 零字节。
        val f = Fixture(Script.Pre(CloudFailure.AuthRejected))

        val events = f.loop.turn(f.turnReq("t4")).toList()

        // ★★ PRE ⇒ 端侧**确实**重新生成了（不是失败收尾）
        assertThat(f.localAnswerTurns).isEqualTo(1)
        assertThat(f.text(events)).contains("本地答案")
        assertThat(f.cloud.bytesSent).isEqualTo(0)
        // ★★ 出境账收敛为 NOT_SENT
        assertThat(f.permits.closed).containsExactly(AuditOutcome.NOT_SENT)
        // ★★ 告知不是孤儿：kind 对、reasonKey 对
        assertThat(f.announcements).hasSize(1)
        assertThat(f.announcements.single().kind).isEqualTo(AnnouncementKind.FELL_BACK_TO_LOCAL)
        assertThat(f.announcements.single().reasonKey).isEqualTo(CloudFailure.AuthRejected.reasonKey)
        // ★ turnId 与本轮一致
        assertThat(f.cloudRows.single().first.turnId).isEqualTo("t4")
    }

    /**
     * ★★ T4 的**连接拒绝**变体（docs/11 §4 原文明写的那一条）。
     *
     * ⚠ 已知缺陷（`DefaultEgressCoordinator`，`:inference`，本轮不在我的地盘）：
     *   `ConnectFailed` 判据是「重试 1 次」，重试**耗尽后**落到最后那个
     *   `return EgressResult.Failed(failure)` —— 而它**仍然**发出了
     *   `FELL_BACK_TO_LOCAL` 告知 ⇒ 告知说「已由本机完成」，本机却**没跑**。
     *   本测试断言的是 §4.4 表格里**正确**的行为 ⇒ 它会红，红就是证据。
     */
    @Test
    fun t4b_connectRefusedAfterRetry_localStillRuns() = runBlocking {
        val f = Fixture(Script.Pre(CloudFailure.ConnectFailed))

        val events = f.loop.turn(f.turnReq("t4b")).toList()

        // §4.4：连接失败 ⇒ 重试 1 次仍失败 ⇒ 仍属「一个字节没出去」⇒ 允许回落（且已告知）
        assertThat(f.cloud.bytesSent).isEqualTo(0)
        assertThat(f.localAnswerTurns).isEqualTo(1)
        assertThat(f.text(events)).contains("本地答案")
    }

    // ════════════ T5：POST 失败 ⇒ 端侧**不**执行 + ALREADY_SENT + host 非空 ════════════

    @Test
    fun t5_postFailure_localDoesNotRunAndSaysAlreadySent() = runBlocking {
        // ★ 流截断：会话已开成、字节已发 ⇒ POST ⇒ 禁止回落。
        val f = Fixture(Script.Post(CloudFailure.StreamTruncated, 512))

        val events = f.loop.turn(f.turnReq("t5")).toList()

        // ★★ POST ⇒ 端侧一次都没跑（不是「跑了一遍但没显示」）
        assertThat(f.localAnswerTurns).isEqualTo(0)
        assertThat(f.text(events)).isEmpty()
        // ★★ 告知：kind 对、host 非空、不变式成立
        assertThat(f.announcements).hasSize(1)
        assertThat(f.announcements.single().kind).isEqualTo(AnnouncementKind.ALREADY_SENT)
        assertThat(f.announcements.single().host).isNotEmpty()
        assertThat(f.announcements.single().isWellFormed()).isTrue()
        // ★ 出境账留下「已投递 512 字节」这个事实，不能被抹掉
        assertThat(f.permits.deliveredBytes).containsExactly(512)
        // ★ 本轮失败收尾
        val terminal = events.last() as ChatEvent.Terminal
        assertThat(terminal.reason).isEqualTo(ChatTerminalReason.MODEL_FAILED)
    }

    // ════════════ T6：同一 session 三轮 ⇒ 三行、turnId 两两不同 ════════════

    @Test
    fun t6_threeTurns_threeAuditRowsWithDistinctTurnIds() = runBlocking {
        val f = Fixture(Script.Ok("好", 32))

        f.loop.turn(f.turnReq("t6a")).toList()
        f.loop.turn(f.turnReq("t6b")).toList()
        f.loop.turn(f.turnReq("t6c")).toList()

        val turnIds = f.cloudRows.map { it.first.turnId }
        assertThat(turnIds).containsExactly("t6a", "t6b", "t6c").inOrder()
        assertThat(turnIds.toSet()).hasSize(3)
        // ★ 每轮各一条出境账，各收敛一次
        assertThat(f.permits.opened).hasSize(3)
        assertThat(f.permits.closed).containsExactly(
            AuditOutcome.DELIVERED, AuditOutcome.DELIVERED, AuditOutcome.DELIVERED,
        )
        // ★ 云端 session 不跨轮复用：三轮 ⇒ 三次 openDialog
        assertThat(f.cloud.openDialogCalls).isEqualTo(3)
    }

    // ════════════ §五-4：云端路径不碰 conversation，Slot 不 NPE ════════════

    /**
     * ★★ 主理人最担心在真机炸的那一条：**云端轮次不使用 `conversation`**。
     *
     * 云端轮次走 `answerViaCloud`，**不**经 `tokenStream.generate(conversation, …)`，
     * 也不调 `maybeCompact` ⇒ `Slot` 里那条 conversation 一直是「开了但本轮没用」。
     * 断言形状：**云端两轮 + 再一轮端侧**，全程不抛异常、每轮都有终结语。
     */
    @Test
    fun noConversationInCloudPath_doesNotBlowUpTheSlot() = runBlocking {
        val f = Fixture(Script.Ok("云端", 16))

        val e1 = f.loop.turn(f.turnReq("n1")).toList()
        val e2 = f.loop.turn(f.turnReq("n2")).toList()

        assertThat(f.cloud.openDialogCalls).isEqualTo(2)
        assertThat(f.permits.opened).hasSize(2)
        // ★ 每轮都有终结语，且是 COMPLETED（不是「没跑完就断了」）
        assertThat((e1.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat((e2.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)

        // ★★ 同一个 ChatLoop 再跑一轮**端侧**（路由改选 Device）⇒ Slot 必须仍能复用/重建
        val g = Fixture(Script.Ok("云端", 16), chooseDevice = true)
        val e3 = g.loop.turn(g.turnReq("n3")).toList()
        assertThat((e3.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat(g.text(e3)).contains("本地答案")
    }

    /**
     * ★★ 反证：云端路径**没有**把「端侧会话」也顺手关掉。
     *
     * 若有人在 `answerViaCloud` 里顺手 `closeConversation`，下一轮云端轮就会
     * 因为 `slot.conversation == null` 而重建 ⇒ 表现为「每轮都复水、KV 全丢」。
     * 这里用「云端一轮后 slot 仍然持有 conversation（第二轮 REUSED）」锁住。
     */
    @Test
    fun cloudTurnDoesNotTearDownTheDeviceConversation() = runBlocking {
        val f = Fixture(Script.Ok("云端", 16))

        f.loop.turn(f.turnReq("s1")).toList()
        val second = f.loop.turn(f.turnReq("s2")).toList()

        val focus = second.filterIsInstance<ChatEvent.FocusChanged>().first()
        assertThat(focus.kind).isEqualTo(com.ake.core.ports.FocusKind.REUSED)
    }

    // ════════════ 反证：链路是活的 ════════════

    @Test
    fun cloudPortIsActuallyReachable() = runBlocking {
        val f = Fixture(Script.Ok("x", 8))
        f.loop.turn(f.turnReq("r1")).toList()
        assertThat(f.cloudRows.single().second.target).isEqualTo(InferenceTarget.CloudRequested)
    }
}
