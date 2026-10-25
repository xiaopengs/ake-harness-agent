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
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.RedactionGate
import com.ake.core.common.Severity
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.FocusHolder
import com.ake.core.ports.FocusKind
import com.ake.core.ports.FocusLease
import com.ake.core.ports.FocusPriority
import com.ake.core.ports.InferenceFocus
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
        /** 外部持有的仲裁器：`releaseFocus` 的回归测试要求从外面观察租约状态。 */
        focus: InferenceFocus = InferenceFocusArbiter(clock),
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
            focus = focus,
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
        assertThat((events.single() as ChatEvent.Terminal).reason)
            .isEqualTo(ChatLoop.REQUEST_STORE_FAILURE_REASON)
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
        // ★ 报「上一轮还没收尾」，**不能**报 POLICY_DENIED：这里没有任何裁决发生，
        //   说「安全策略拦下了这一步」会让用户去翻一个不存在的策略开关。
        assertThat((second.single() as ChatEvent.Terminal).reason)
            .isEqualTo(ChatTerminalReason.IN_FLIGHT)
        // 反向断言：这条路径上不许出现策略归因（这是本次修正的全部理由）
        assertThat((second.single() as ChatEvent.Terminal).reason)
            .isNotEqualTo(ChatTerminalReason.POLICY_DENIED)

        gate.complete(Unit)
        val firstEvents = first.await()
        assertThat((firstEvents.last() as ChatEvent.Terminal).reason).isEqualTo(ChatTerminalReason.COMPLETED)
    }

    /**
     * ★ 被拒的那一轮**在上一轮结束后必须能正常发出去** —— 只测"拒绝"是不够的。
     *
     * 拒绝对用户只有一个合法含义：「等一下，再发一次就行」。如果"再发一次"仍然被拒，
     * 那句话就从"稍等"变成了假话（`ChatLoop` 是单例 ⇒ 会话会被永久卡住）。
     * 所以这里断的是**恢复**：同一句 `userText`、同一 `turnId`（`retry()` 的真实形态，
     * 见 `:ui` 的 `ChatViewModel.retry`），前一轮收尾后必须 COMPLETED 且有内容流出。
     */
    @Test
    fun theRejectedTurnSucceedsOnceThePreviousTurnFinishes() = runBlocking {
        val model = FakeModelPort()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        model.gate = gate
        val loop = buildLoopWith(model)

        val pending = request("turn-1", "帮我看下今天有什么安排")
        val first = async { loop.turn(pending).toList() }
        withTimeout(5_000) {
            while (model.lastSession?.requests?.isEmpty() != false) yield()
        }

        // 用户手快：同一句话又发了一次（`retry()` 复用同一个 turnId）
        assertThat(loop.turn(pending).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.IN_FLIGHT)

        // 前一轮收尾 ⇒ 闸被真正复位 ⇒ 同一句话这次必须发得出去
        gate.complete(Unit)
        assertThat(first.await().terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED)

        val retried = loop.turn(pending).toList()
        assertThat(retried.terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat(retried.filterIsInstance<ChatEvent.AssistantDelta>()).isNotEmpty()
        // 幂等键没被这次"等一下再发"破坏：库里仍然只有一条用户消息
        assertThat(store.stored.count { it.role.name == "USER" }).isEqualTo(1)
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
            .isEqualTo(ChatTerminalReason.REQUEST_STORE_FAILED)

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
                .isEqualTo(ChatTerminalReason.REQUEST_STORE_FAILED)
        }
        store.appendFails = false
        assertThat(loop.turn(request("t-good", "终于可以发了")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.COMPLETED)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P0-3 + P1-1：落盘失败的**归因**必须与真实发生的事一致
    //
    // 这一组的分界线不是"严重程度"，而是**回答有没有生成过**：
    //   生成前写库失败 ⇒ REQUEST_STORE_FAILED（用户看到"这句话没存下来，本轮没开始"）
    //   生成后提交失败 ⇒ STORE_FAILED         （用户看到"回答生成好了，但没存下来"）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun commitFailureIsAnsweredButNotStored() = runBlocking {
        val loop = buildLoop()
        store.commitFails = true
        val events = loop.turn(request()).toList()

        // ① 同一轮内重试过一次「落盘」（不是重新生成）
        assertThat(store.commitAttempts).isEqualTo(2)
        // ② ★ 回答**真的生成过**：屏幕上流过 delta。这是"答完了没存下来"这句话成立的前提。
        assertThat(events.filterIsInstance<ChatEvent.AssistantDelta>()).isNotEmpty()
        // ③ 终态归因到存储
        assertThat(events.terminalReason()).isEqualTo(ChatTerminalReason.STORE_FAILED)
    }

    /**
     * ★ P1-1 的核心断言：**生成前**的写库失败不能报「回答生成好了，但没存下来」。
     *
     * 上一版把 `append` / `beginAssistant` 的失败也汇到了 `STORE_FAILED`，
     * 而 UI 那一档的文案是「回答生成好了，但没存下来」+「模型已经答完了」。
     * 用户读到这句话的合理推论是"模型答了，只是我看不到" —— 而真相是**一个 token 都没产生**。
     *
     * 所以这里同时断三件事：终态是 `REQUEST_STORE_FAILED`、不是 `STORE_FAILED`、
     * 且**一个 delta 都没有**（用它证明"没有回答"这个事实，而不是靠注释声明）。
     */
    @Test
    fun storeFailureBeforeGenerationMustNotClaimAnAnswerExists() = runBlocking {
        val loop = buildLoop()
        store.beginFails = true
        val events = loop.turn(request()).toList()

        assertThat(events.terminalReason()).isEqualTo(ChatTerminalReason.REQUEST_STORE_FAILED)
        assertThat(events.terminalReason()).isNotEqualTo(ChatTerminalReason.STORE_FAILED)
        // 没有回答 ⇒ 不许出现任何 assistant 内容（否则那句话就不是"归因错"，而是"前后矛盾"）
        assertThat(events.filterIsInstance<ChatEvent.AssistantDelta>()).isEmpty()
    }

    /**
     * 反向断言：**模型**失败仍然是 `MODEL_FAILED`。
     *
     * 没有这一条，把落盘失败改归存储这件事很容易被"顺手"做成
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

    // ══════════════════════════════════════════════════════════════════════════
    // P1-3：`openSession` 失败必须放掉租约（否则派生任务一次都起不来）
    //
    // 关千锤的负向验证：删掉 `releaseFocus(lease)` ⇒ 115 例全绿 ⇒ 这个修复**零覆盖**。
    // 它的故障表现极难排查（聊天侧看起来一切正常，只有任务永远拿不到焦点），
    // 所以这里断的不是"日志好看"，而是**下一个 DERIVED_TASK 能不能拿到租约**。
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun conversationOpenFailureReleasesTheLeaseSoDerivedTasksStillRun() = runBlocking {
        val focus = InferenceFocusArbiter(clock)
        val model = FakeModelPort().apply { failOpenSession = true }
        val loop = buildLoopWith(model, focus = focus)

        // 这一轮注定失败：建不起 Conversation ⇒ MODEL_NOT_LOADED
        assertThat(loop.turn(request("turn-1")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.MODEL_NOT_LOADED)
        assertThat(model.opened).isEqualTo(1)

        // ① 仲裁器里不许留着一个已经没有持有者的 `Chat(sid)`
        assertThat(focus.current()).isNull()

        // ② 这才是后果断言：派生任务必须能拿到焦点。
        //    没 release 的话这里是 Err(FOCUS_BUSY) —— 表现为「任务一次都起不来」。
        val lease = focus.acquire(FocusHolder.Task("task-1", null), FocusPriority.DERIVED_TASK)
        assertThat(lease).isInstanceOf(Outcome.Ok::class.java)
        @Suppress("UNCHECKED_CAST")
        (lease as Outcome.Ok<FocusLease>).value.close()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P1-4：重试不能重复落 USER 消息
    //
    // 幂等键由 `(sessionId, turnId)` 派生（`ChatLoop.userMessageKey`）。
    // 断言的落点是「库里到底有几条 USER 行」—— 不是"键长得对不对"，
    // 因为键长得再对，只要重试时换了键，`UNIQUE` 就形同虚设（这正是原始缺陷）。
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun retryingTheSameTurnDoesNotDuplicateTheUserMessage() = runBlocking {
        val loop = buildLoop()

        // 同一轮重试三次。★ 注意：每次的 `clientRequestId` 都不同 ——
        // 这正是 `:ui` 的真实行为（`UUID.randomUUID()` 每次新建），也是原缺陷的成因。
        val attempts = listOf("inbound-a", "inbound-b", "inbound-c").map { inbound ->
            loop.turn(request("turn-1").copy(clientRequestId = inbound)).toList()
        }

        // ① 每一次都是正常完成的一轮（重试没有被幂等挡掉）
        attempts.forEach { assertThat(it.terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED) }
        // ② 而库里只有**一条**用户消息
        assertThat(store.stored.count { it.role.name == "USER" }).isEqualTo(1)
        // ③ 三次用的是同一个派生键（幂等成立的原因）
        assertThat(store.requestKeys.toSet()).hasSize(1)
        assertThat(store.requestKeys.first()).isEqualTo(ChatLoop.userMessageKey("sess-1", "turn-1"))
    }

    /**
     * 反面：**用户真的又发了一遍**必须落一条新的。
     *
     * 与上一条一起构成完整的判据 —— 只有"重试不重复"而没有"新消息不被吃掉"，
     * 那就变成了另一种数据丢失（用户说两遍一样的话，第二遍消失）。
     */
    @Test
    fun sayingTheSameThingAgainInANewTurnIsStillStored() = runBlocking {
        val loop = buildLoop()

        loop.turn(request("turn-1", "帮我看看今天有什么安排")).toList()
        loop.turn(request("turn-2", "帮我看看今天有什么安排")).toList()

        assertThat(store.stored.count { it.role.name == "USER" }).isEqualTo(2)
        assertThat(store.requestKeys.toSet()).hasSize(2)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 焦点拿不到时的**归因**（同类原则的第三个案例）
    //
    // 上一版 `borrowFocus` 返回 null ⇒ 调用点只能笼统报 `MODEL_FAILED`。
    // 而焦点拿不到时**模型根本没有被调用过**（`opened == 0`），说「模型出错了」
    // 会让用户去换模型 / 清缓存。真实原因通常是「焦点被别的会话或任务占着」——
    // 那是「等一下再发」，不是「坏了」。
    //
    // ★ 两条测试一起构成判据：`FOCUS_BUSY` 归 `IN_FLIGHT`，**其它**码不许跟着沾光。
    //   没有第二条，这次修正很容易被"顺手"做成「凡是焦点失败都是 IN_FLIGHT」——
    //   那是同一个错误的反方向（把真实的引擎故障说成"稍等就好"）。
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun focusBusyIsReportedAsInFlightInsteadOfModelFailed() = runBlocking {
        val focus = ScriptedFocus(FocusOutcome.BUSY)
        val model = FakeModelPort()
        val loop = buildLoopWith(model, focus = focus)

        assertThat(loop.turn(request("turn-1")).toList().terminalReason())
            .isEqualTo(ChatTerminalReason.IN_FLIGHT)
        // 「模型出错了」这句话的事实前提是"模型被调用过并失败" —— 这里它一次都没被调用
        assertThat(model.opened).isEqualTo(0)

        // ★ 恢复：占用者收尾之后，同一句话必须发得出去（否则"等一下"是假话）
        focus.outcome = FocusOutcome.GRANTED
        val retried = loop.turn(request("turn-1", "帮我看下今天有什么安排")).toList()
        assertThat(retried.terminalReason()).isEqualTo(ChatTerminalReason.COMPLETED)
        assertThat(retried.filterIsInstance<ChatEvent.AssistantDelta>()).isNotEmpty()
    }

    @Test
    fun anUnknownFocusFailureMustNotPretendToBeTheInFlightWindow() = runBlocking {
        val focus = ScriptedFocus(FocusOutcome.OTHER_FAILURE)
        val model = FakeModelPort()
        val loop = buildLoopWith(model, focus = focus)

        val reason = loop.turn(request("turn-1")).toList().terminalReason()
        // 不是"别的一轮在跑"，所以不许报 IN_FLIGHT —— 等多久都不会好
        assertThat(reason).isNotEqualTo(ChatTerminalReason.IN_FLIGHT)
        assertThat(reason).isEqualTo(ChatTerminalReason.MODEL_FAILED)
        assertThat(model.opened).isEqualTo(0)
    }

    // ── 便捷构造（保持与 buildLoop 同一套依赖，只是把 model 暴露出来断言 opened 次数）

    private fun buildLoopWith(
        model: FakeModelPort,
        profileStore: FakeProfileStore = FakeProfileStore(),
        focus: InferenceFocus = InferenceFocusArbiter(clock),
    ): ChatLoop {
        val isolator = InjectionIsolator(policy)
        val collector = ChatHistoryCollector { model.estimateTokens(it) }
        return ChatLoop(
            model = model,
            focus = focus,
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

/** [ScriptedFocus] 的三种裁决。 */
private enum class FocusOutcome {
    /** 焦点被别的会话 / 派生任务占着（生产里唯一真实会发生的拒绝）。 */
    BUSY,

    /** 仲裁器给出了本模块没有语义的失败（模拟"意外的实现/状态"）。 */
    OTHER_FAILURE,

    /** 正常签发。 */
    GRANTED,
}

/**
 * 可控的焦点仲裁器假件。
 *
 * 为什么需要它：生产实现 `InferenceFocusArbiter` 里 `FOREGROUND_USER` **恒可抢占**，
 * 于是「聊天轮次拿不到焦点」这件事用真仲裁器**无法触发** —— 而这恰恰是
 * `borrowFocus` 失败路径唯一的存在理由。没有这个假件，那条路径就是零覆盖。
 */
private class ScriptedFocus(var outcome: FocusOutcome = FocusOutcome.BUSY) : InferenceFocus {

    override suspend fun acquire(holder: FocusHolder, priority: FocusPriority): Outcome<FocusLease> =
        when (outcome) {
            FocusOutcome.BUSY -> Outcome.Err(
                AppError(
                    code = InferenceFocusArbiter.CODE_FOCUS_BUSY,
                    reasonKey = "err_focus_busy",
                    severity = Severity.LOW,
                ),
            )

            FocusOutcome.OTHER_FAILURE -> Outcome.Err(
                AppError(code = "ENGINE_NOT_READY", reasonKey = "err_engine_not_ready"),
            )

            FocusOutcome.GRANTED -> Outcome.Ok(
                object : FocusLease {
                    override fun isValid(): Boolean = true
                    override fun close() = Unit
                },
            )
        }

    override fun current(): FocusHolder? = null
}
