package com.ake.chat.loop

import com.ake.agent.context.InjectionIsolator
import com.ake.agent.loop.TokenStream
import com.ake.agent.planner.DefaultSystemCharter
import com.ake.chat.ctx.ChatHistoryCollector
import com.ake.chat.ctx.ModeProfileRegistry
import com.ake.chat.egress.ChatEgress
import com.ake.chat.egress.LocalOnlyEgressGate
import com.ake.chat.egress.LocalOnlyRoutingPort
import com.ake.chat.fixtures.FakeChatStore
import com.ake.chat.fixtures.FakeClock
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
import com.ake.core.common.Clock
import com.ake.core.common.RedactionGate
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.FocusKind
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.MessageStatus
import com.ake.core.ports.NoAbortSignal
import com.ake.core.ports.Resolved
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.ports.RoutingPort
import com.ake.core.ports.TurnMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * `ChatLoop` 的端到端行为（用假件跑完整链路，不碰真引擎）。
 *
 * 这一组测试刻意只断言**契约层可见的事实**：事件顺序、终态、落盘结果、会话复用次数。
 * 断言 prompt 的字符串内容是没有意义的 —— 那是 `:agent` 的 `ContextAssembler` 的职责，
 * 而且字符串一改测试就红，会逼着后人删断言。
 */
class ChatLoopTest {

    private val clock = FakeClock()
    private val store = FakeChatStore()
    private val policy = RecordingPolicy()
    private val humanGate = FakeHumanGate(Resolved.Proceed)
    private val grants = FakeGrants(
        mapOf(CapabilityRef.builtin(BrowserTaskSkill.SKILL_ID) to true),
    )
    private val taskRunner = FakeTaskRunner()

    private fun buildLoop(
        routerScript: List<String> = listOf("""{"mode":"reply"}"""),
        answer: String = "好的，我在本地帮你查过了。",
        gate: FakeHumanGate = humanGate,
        routing: RoutingPort = LocalOnlyRoutingPort(clock),
    ): ChatLoop {
        val model = FakeModelPort(routerScript, answer)
        val isolator = InjectionIsolator(policy)
        val collector = ChatHistoryCollector { model.estimateTokens(it) }
        val skills = ChatSkillRegistry(
            handlers = listOf(BrowserTaskSkill(taskRunner, clock)),
            grants = grants,
        )
        return ChatLoop(
            model = model,
            focus = InferenceFocusArbiter(clock),
            store = store,
            profiles = FakeProfileStore(),
            skills = skills,
            policy = policy,
            health = FakeHealth(),
            egress = ChatEgress(
                routing,
                LocalOnlyEgressGate(),
                ChatEgress.AuditSink.NONE,
                clock,
            ),
            humanGate = gate,
            traceStore = FakeTraceStore(),
            clock = clock,
            tokenStream = TokenStream.SINGLE_SHOT,
            registry = ModeProfileRegistry(),
            charter = DefaultSystemCharter(),
            redactionGate = RedactionGate.SCRUB_ONLY,
            rehydrator = ChatRehydrator(store, collector) { model.estimateTokens(it) },
            historyCollector = collector,
            isolator = isolator,
            router = SkillRouterForTest,
        )
    }

    private fun request(
        turnId: String = "turn-1",
        text: String = "帮我看下今天有什么安排",
        allowSkill: Boolean = true,
    ) = ChatTurnRequest(
        sessionId = "sess-1",
        clientRequestId = "req-$turnId",
        turnId = turnId,
        userText = text,
        abort = NoAbortSignal,
        allowSkillInvocation = allowSkill,
    )

    @Test
    fun replyTurnEmitsTheFullEventSkeletonAndPersistsBothMessages() = runBlocking {
        val loop = buildLoop()
        val events = loop.turn(request()).toList()

        // ① 焦点：首次进入必须复水（哪怕历史为空，也要给出真实值而不是乐观值）
        val focus = events.filterIsInstance<ChatEvent.FocusChanged>().first()
        assertThat(focus.kind).isEqualTo(FocusKind.REHYDRATED)
        assertThat(focus.prefillTokens).isAtLeast(0)

        // ② 路由裁决暴露给用户
        assertThat(events.filterIsInstance<ChatEvent.RouterPlanned>().single().mode).isEqualTo(TurnMode.REPLY)

        // ③ 流式增量
        val deltas = events.filterIsInstance<ChatEvent.AssistantDelta>()
        assertThat(deltas).isNotEmpty()
        assertThat(deltas.joinToString("") { it.delta }).isEqualTo("好的，我在本地帮你查过了。")

        // ④ 最后一条必是终结语
        val terminal = events.last() as ChatEvent.Terminal
        assertThat(terminal.reason).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat(terminal.messageId).isNotNull()

        // ⑤ 落盘：用户消息 + 助手消息（后者以 COMPLETE 收敛）
        assertThat(store.stored.map { it.role.name }).containsExactly("USER", "ASSISTANT").inOrder()
        assertThat(store.stored.last().status).isEqualTo(MessageStatus.COMPLETE)
        assertThat(store.stored.last().textRedacted).isEqualTo("好的，我在本地帮你查过了。")
        assertThat(store.commits.single().second).isEqualTo(MessageStatus.COMPLETE)
    }

    @Test
    fun secondTurnReusesTheSameConversation() = runBlocking {
        val model = FakeModelPort()
        val loop = buildLoopWith(model)

        loop.turn(request("turn-1", "第一句")).toList()
        val second = loop.turn(request("turn-2", "第二句")).toList()

        // ★ L1：会话内 KV cache 复用 —— 第二次必须 REUSED 且**没有**重新 openSession
        assertThat((second.first() as ChatEvent.FocusChanged).kind).isEqualTo(FocusKind.REUSED)
        assertThat(model.opened).isEqualTo(1)
    }

    @Test
    fun skillCallDerivesATaskAndSwitchesModeTwice() = runBlocking {
        val loop = buildLoop(
            routerScript = listOf(
                """{"mode":"skill","skill":"browser_task","instruction":"按销量排序取前三条"}""",
                """{"mode":"reply"}""",
            ),
            answer = "前三条是：A/39 元、B/49 元、C/59 元。",
        )
        val events = loop.turn(request(text = "帮我搜一下无线鼠标销量前三")).toList()

        assertThat(events.filterIsInstance<ChatEvent.RouterPlanned>().first().mode).isEqualTo(TurnMode.SKILL_CALL)
        assertThat(events.filterIsInstance<ChatEvent.SkillStarted>()).hasSize(1)
        assertThat(events.filterIsInstance<ChatEvent.SkillProgress>()).isNotEmpty()
        assertThat(events.filterIsInstance<ChatEvent.SkillResult>()).hasSize(1)

        // 模式切换必须显式记账（REPLY→TASK→REPLY），成本不可藏（§2.7 第 3 条）
        val switches = events.filterIsInstance<ChatEvent.ModeSwitched>()
        assertThat(switches).hasSize(2)
        assertThat(switches.first().to).isEqualTo(com.ake.core.ports.LoopMode.TASK)
        assertThat(switches.last().to).isEqualTo(com.ake.core.ports.LoopMode.REPLY)
        assertThat(switches.last().epoch).isGreaterThan(switches.first().epoch)

        // ★ 任务由技能构造：goal 来自校验过的 instruction，id 由 callId 派生（不透传模型 id）
        val task = taskRunner.lastTask!!
        assertThat(task.goal).isEqualTo("按销量排序取前三条")
        assertThat(task.id.value).startsWith("task-call-")
        assertThat(task.maxSteps).isEqualTo(25)

        // 最后仍然走一次 Answer Turn，由模型用人话复述（不把网页原文直接贴给用户）
        val deltas = events.filterIsInstance<ChatEvent.AssistantDelta>().joinToString("") { it.delta }
        assertThat(deltas).contains("前三条")
        assertThat((events.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    @Test
    fun atMostThreeSkillCallsPerTurn() = runBlocking {
        // Router 一直要求调技能 ⇒ 必须被上限拦住，转由回答总结（Q7）
        val loop = buildLoop(
            routerScript = listOf(
                """{"mode":"skill","skill":"browser_task","instruction":"再搜一次"}""",
            ),
        )
        val events = loop.turn(request()).toList()

        assertThat(events.filterIsInstance<ChatEvent.SkillStarted>())
            .hasSize(ChatLoop.MAX_SKILL_CALLS_PER_TURN)
        assertThat((events.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    @Test
    fun decliningTheFirstUseConfirmationSkipsTheSkill() = runBlocking {
        val loop = buildLoop(
            routerScript = listOf(
                """{"mode":"skill","skill":"browser_task","instruction":"打开首页"}""",
                """{"mode":"reply"}""",
            ),
            gate = FakeHumanGate(Resolved.Reject("user_denied")),
        )
        val events = loop.turn(request()).toList()

        // 确认回到气泡里问（不是第二个对话框）
        assertThat(events.filterIsInstance<ChatEvent.ConfirmRequested>()).hasSize(1)
        // 用户拒绝 ⇒ 任务一次都不跑
        assertThat(events.filterIsInstance<ChatEvent.SkillStarted>()).isEmpty()
        assertThat(taskRunner.lastTask).isNull()
        // 但对话仍然要有一个回答（告诉用户「没有执行」）
        assertThat((events.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    @Test
    fun userAbortStopsBeforeAnyGeneration() = runBlocking {
        val loop = buildLoop()
        val events = loop.turn(
            request().copy(abort = object : com.ake.core.ports.AbortSignal {
                override fun isAborted(): Boolean = true
            }),
        ).toList()

        assertThat((events.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.USER_ABORT)
        assertThat(events.filterIsInstance<ChatEvent.AssistantDelta>()).isEmpty()
    }

    @Test
    fun appendFailureSurfacesAsTerminalInsteadOfSilentSwallow() = runBlocking {
        store.appendFails = true
        val loop = buildLoop()
        val events = loop.turn(request()).toList()

        // 落盘失败必须让用户看见（§4.4 不可丢泳道），且不能开始生成
        assertThat(events).hasSize(1)
        assertThat((events.single() as ChatEvent.Terminal).reason).isEqualTo(ChatLoop.STORE_FAILURE_REASON)
    }

    @Test
    fun skillInvocationCanBeDisabledPerTurn() = runBlocking {
        val loop = buildLoop(
            routerScript = listOf("""{"mode":"skill","skill":"browser_task","instruction":"打开首页"}"""),
        )
        val events = loop.turn(request(allowSkill = false)).toList()

        assertThat(events.filterIsInstance<ChatEvent.SkillStarted>()).isEmpty()
        assertThat((events.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    @Test
    fun personaChangeRebuildsTheSessionAndKeepsTheFocus() = runBlocking {
        val model = FakeModelPort()
        val profileStore = FakeProfileStore()
        val loop = buildLoopWith(model, profileStore)

        loop.turn(request("turn-1")).toList()
        assertThat(model.opened).isEqualTo(1)

        // 用户改了人设 ⇒ systemInstruction 变了 ⇒ 必须开新会话（§3.5）
        profileStore.update(com.ake.chat.fixtures.fakeProfile(persona = "你现在是一位金牌法务。"))
        val second = loop.turn(request("turn-2")).toList()

        assertThat(model.opened).isEqualTo(2)
        assertThat((second.first() as ChatEvent.FocusChanged).kind).isEqualTo(FocusKind.REHYDRATED)
        // ★ 回归点：重建会话时不能把自己刚借到的焦点关掉（否则这一轮立刻让出）
        assertThat(second.none { it is ChatEvent.FocusChanged && it.kind == FocusKind.YIELDED }).isTrue()
        assertThat((second.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    @Test
    fun concurrentTurnOnTheSameSessionIsRejectedInsteadOfCorruptingNative() = runBlocking {
        val model = FakeModelPort()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        model.gate = gate
        val loop = buildLoopWith(model)

        val first = async { loop.turn(request("turn-1")).toList() }
        // 等第一轮真的进入生成阶段
        withTimeout(5_000) {
            while (model.lastSession?.requests?.isEmpty() != false) yield()
        }

        val second = loop.turn(request("turn-2")).toList()
        assertThat(second).hasSize(1)
        assertThat((second.single() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.POLICY_DENIED)

        gate.complete(Unit)
        val firstEvents = first.await()
        assertThat((firstEvents.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    @Test
    fun coldStartSweepsStreamingRows() = runBlocking<Unit> {
        val loop = buildLoop()
        assertThat(loop.onColdStart()).isEqualTo(0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P0-1：in-flight 标记的泄漏（关千锤第 3 轮验收）
    //
    // 每一条**提前 return** 的路径各一条测试，断言的都是同一件事：
    //   「这条路径失败过一次之后，**同一会话**还能正常发出下一条消息」。
    //
    // ★ 为什么原来的测试没抓到：它只断言了第一轮（`appendFailure…` 断言了
    //   `events.size == 1` 就结束了）。泄漏的后果**只体现在第二轮**，所以只测第一轮
    //   的测试对这类缺陷是结构性盲的 —— 这也是本组测试存在的理由，而不是"多测一点"。
    // ══════════════════════════════════════════════════════════════════════════

    /** 路径 ①：`resolveScope()` 返回 null（会话不存在 / 人格不可读）。 */
    @Test
    fun unknownSessionReleasesTheFlightMarkerAfterItFails() = runBlocking {
        val loop = buildLoop()
        store.sessionVisible = false

        assertThat(loop.turn(request("turn-1")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.POLICY_DENIED)

        // 会话恢复可读 ⇒ 同一会话必须立刻能发。泄漏时这里会**永远**是 POLICY_DENIED，
        // 而 ChatLoop 是单例 ⇒ 线上只能杀进程。
        store.sessionVisible = true
        val second = loop.turn(request("turn-2", "第二句")).toList()
        assertThat(second.terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat(second.filterIsInstance<ChatEvent.AssistantDelta>()).isNotEmpty()
    }

    /** 路径 ②：`store.append(USER)` 失败（写库失败一次）。 */
    @Test
    fun appendFailureReleasesTheFlightMarker() = runBlocking<Unit> {
        val loop = buildLoop()
        store.appendFails = true
        assertThat(loop.turn(request("turn-1")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.STORE_FAILED)

        store.appendFails = false
        // 关千锤的原话是「写库失败一次 ⇒ 该会话永久发不出消息」。这一行就是那句话的反面。
        val second = loop.turn(request("turn-2", "第二句")).toList()
        assertThat(second.terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat(store.stored.map { it.role.name }).contains("ASSISTANT")
    }

    /** 路径 ③：出云裁决不是 `LOCAL_ONLY`（本模块没有云端实现 ⇒ 明确拒绝）。 */
    @Test
    fun egressRejectionReleasesTheFlightMarker() = runBlocking {
        val routing = ToggleRouting(clock, InferenceTarget.CLOUD_ASSISTED)
        val loop = buildLoop(routing = routing)

        assertThat(loop.turn(request("turn-1")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.POLICY_DENIED)
        // 审计那一笔仍然要落下（T12），但这一轮没有生成
        assertThat(routing.decisions).isEqualTo(1)

        routing.target = InferenceTarget.LOCAL_ONLY
        val second = loop.turn(request("turn-2", "第二句")).toList()
        assertThat(second.terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat(second.filterIsInstance<ChatEvent.AssistantDelta>()).isNotEmpty()
    }

    /** 路径 ④：轮次内部抛异常（`catch (t: Throwable)`）。它和三条 return 共用同一个 `finally`。 */
    @Test
    fun thrownFailureReleasesTheFlightMarker() = runBlocking {
        val model = FakeModelPort()
        model.throwOnGenerate = true
        val loop = buildLoopWith(model)

        assertThat(loop.turn(request("turn-1")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.MODEL_FAILED)

        model.throwOnGenerate = false
        val second = loop.turn(request("turn-2", "第二句")).toList()
        assertThat(second.terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    /** 兜底性质：**连续**多次早退之后仍然可用（泄漏是累积的，一次干净不代表配对正确）。 */
    @Test
    fun repeatedEarlyReturnsNeverAccumulateIntoAPermanentBlock() = runBlocking<Unit> {
        val loop = buildLoop()
        repeat(5) { i ->
            store.appendFails = true
            assertThat(loop.turn(request("t-bad-$i")).toList().terminalReason())
                .isEqualTo(ChatTerminalReason.STORE_FAILED)
        }
        store.appendFails = false
        assertThat(loop.turn(request("t-good", "终于可以发了")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.COMPLETED)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P0-3：落盘失败必须归因到 STORE_FAILED，而不是 MODEL_FAILED
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun storeFailureIsStoreFailedNotModelFailed() = runBlocking {
        val loop = buildLoop()
        store.appendFails = true
        val terminal = loop.turn(request()).toList().terminalReason()

        assertThat(terminal).isEqualTo(ChatTerminalReason.STORE_FAILED)
        // 这一条是本测试的重点：借 MODEL_FAILED 会让 UI 显示「模型出错了」，
        // 而用户照着那条建议去换模型 / 清缓存，一次都不会有用。
        assertThat(terminal).isNotEqualTo(ChatTerminalReason.MODEL_FAILED)
    }

    @Test
    fun beginAssistantFailureIsAlsoStoreFailedAndSkipsGeneration() = runBlocking {
        val loop = buildLoop()
        store.beginFails = true
        val events = loop.turn(request()).toList()

        assertThat(events.terminalReason()).isEqualTo(ChatTerminalReason.STORE_FAILED)
        // 占位行都建不出来 ⇒ 一个 delta 都不该发（否则 UI 会渲染一条永远收不了尾的气泡）
        assertThat(events.filterIsInstance<ChatEvent.AssistantDelta>()).isEmpty()
    }

    @Test
    fun commitFailureIsRetriedOnceThenReportedAsStoreFailed() = runBlocking {
        val loop = buildLoop()
        store.commitFails = true
        val events = loop.turn(request()).toList()

        // ① 同一轮内重试过一次「落盘」（不是重新生成）
        assertThat(store.commitAttempts).isEqualTo(2)
        // ② 模型那边的生成只跑了一次（重试的是 commit，不是整轮）
        assertThat(events.filterIsInstance<ChatEvent.AssistantDelta>()).isNotEmpty()
        // ③ 终态归因到存储
        assertThat(events.terminalReason()).isEqualTo(ChatTerminalReason.STORE_FAILED)
    }

    /**
     * 反向断言：**模型**失败仍然是 `MODEL_FAILED`。
     *
     * 没有这一条，把落盘失败改归 `STORE_FAILED` 这件事很容易被"顺手"做成
     * 「凡是 Err 都是 STORE_FAILED」—— 那是同一个错误的反方向，只是换了一批用户被误导。
     */
    @Test
    fun modelFailureIsStillModelFailed() = runBlocking {
        val model = FakeModelPort()
        val loop = buildLoopWith(model)

        loop.turn(request("turn-1")).toList()
        // 复用同一个会话 ⇒ 让它的下一次 generate 返回 Outcome.Err（不是抛异常）
        model.lastSession!!.failGenerate = true

        assertThat(loop.turn(request("turn-2", "第二句")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.MODEL_FAILED)
    }

    // ── 便捷构造（保持与 buildLoop 同一套依赖，只是把 model 暴露出来断言 opened 次数）

    private fun buildLoopWith(
        model: FakeModelPort,
        profileStore: FakeProfileStore = FakeProfileStore(),
    ): ChatLoop {
        val isolator = InjectionIsolator(policy)
        val collector = ChatHistoryCollector { model.estimateTokens(it) }
        return ChatLoop(
            model = model,
            focus = InferenceFocusArbiter(clock),
            store = store,
            profiles = profileStore,
            skills = ChatSkillRegistry(listOf(BrowserTaskSkill(taskRunner, clock)), grants),
            policy = policy,
            health = FakeHealth(),
            egress = ChatEgress(
                LocalOnlyRoutingPort(clock),
                LocalOnlyEgressGate(),
                ChatEgress.AuditSink.NONE,
                clock,
            ),
            humanGate = humanGate,
            traceStore = FakeTraceStore(),
            clock = clock,
            tokenStream = TokenStream.SINGLE_SHOT,
            registry = ModeProfileRegistry(),
            charter = DefaultSystemCharter(),
            redactionGate = RedactionGate.SCRUB_ONLY,
            rehydrator = ChatRehydrator(store, collector) { model.estimateTokens(it) },
            historyCollector = collector,
            isolator = isolator,
            router = SkillRouterForTest,
        )
    }

    /** 固定时间源的 router：`callId` 里不能出现随机 ULID，否则断言无法逐字节比对。 */
    private val SkillRouterForTest = com.ake.chat.skill.SkillRouter(nowMillis = { 1_000L })
}

/** 最后一个事件必须是 `Terminal`（契约：终结语在最后）。断错形态会立刻炸在这里。 */
private fun List<ChatEvent>.terminalReason(): ChatTerminalReason =
    (last() as ChatEvent.Terminal).reason

/**
 * 可切换裁决的路由端口。
 *
 * 生产里 `RoutingPort` 恒裁决 `LOCAL_ONLY`（MVP），所以「裁决不是 LOCAL_ONLY」这条
 * 提前返回路径**无法用真实实现触发** —— 只能用这个假件。它是 P0-1 路径 ③ 的唯一入口。
 */
private class ToggleRouting(
    private val clock: Clock,
    var target: InferenceTarget = InferenceTarget.LOCAL_ONLY,
) : RoutingPort {

    var decisions: Int = 0
        private set

    override suspend fun decide(ctx: RouteContext): RoutingDecision {
        decisions++
        return RoutingDecision(
            target = target,
            reasonKey = "test_route",
            allowedClasses = emptySet(),
            redactionApplied = true,
            decidedAt = clock.nowMillis(),
        )
    }
}
