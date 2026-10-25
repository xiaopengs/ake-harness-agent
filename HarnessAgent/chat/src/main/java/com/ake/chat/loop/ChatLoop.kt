package com.ake.chat.loop

import com.ake.agent.context.ContextAssembler
import com.ake.agent.context.ContextBudget
import com.ake.agent.context.InjectionIsolator
import com.ake.agent.context.PromptSections
import com.ake.agent.loop.TokenStream
import com.ake.agent.planner.SystemCharterText
import com.ake.chat.ctx.ChatBudget
import com.ake.chat.ctx.ChatHistoryCollector
import com.ake.chat.ctx.ChatPromptInput
import com.ake.chat.ctx.ChatSystemText
import com.ake.chat.ctx.FixedSystemCharter
import com.ake.chat.ctx.ModeKind
import com.ake.chat.ctx.ModeProfile
import com.ake.chat.ctx.ModeProfileRegistry
import com.ake.chat.ctx.TurnLedger
import com.ake.chat.egress.ChatEgress
import com.ake.chat.skill.ChatSkillRegistry
import com.ake.chat.skill.SkillRouter
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.RedactionGate
import com.ake.core.common.StepId
import com.ake.core.common.Ulid
import com.ake.core.contract.RiskClass
import com.ake.core.contract.StepAction
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.GenerationResult
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ThinkingBudget
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.AgentProfile
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.AppendMessage
import com.ake.core.ports.BudgetEnvelope
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatRunnerPort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.ChatSessionSummary
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.ContentSource
import com.ake.core.ports.DataClass
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.FocusHolder
import com.ake.core.ports.FocusKind
import com.ake.core.ports.FocusLease
import com.ake.core.ports.FocusPriority
import com.ake.core.ports.HumanGate
import com.ake.core.ports.InferenceFocus
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.MessageKind
import com.ake.core.ports.MessageRole
import com.ake.core.ports.MessageStatus
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.Resolved
import com.ake.core.ports.RouteContext
import com.ake.core.ports.SkillCall
import com.ake.core.ports.SkillContext
import com.ake.core.ports.SkillEvent
import com.ake.core.ports.SkillSpec
import com.ake.core.ports.TraceStorePort
import com.ake.core.ports.TurnMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first

/**
 * # 聊天主循环（§3.1 一次轮次的完整形态）
 *
 * ```
 * 用户说话
 *  ① 落 chat_message(USER)                —— 同步写，失败必须让用户看见
 *  ② 出云裁决（MVP 恒 LOCAL_ONLY）         —— RoutingPort + 审计；链路打通、开关不打开
 *  ③ 申请/确认推理焦点                     —— FocusChanged(REUSED | REHYDRATED | YIELDED)
 *  ④ Router Turn（约束解码 ≤48 token）     —— REPLY / SKILL_CALL
 *  ⑤ Skill 执行（≤3 次/轮，派生既有 Task） —— 事件投影回气泡；确认走同一条 HumanGate
 *  ⑥ Answer Turn（真流式）                 —— AssistantDelta* → 结束时一次性落盘
 *  ⑦ 预算结算 + 紧凑判据                   —— ModeSwitched / Compacted / Terminal
 * ```
 *
 * ## 为什么是 `channelFlow` 而不是 `flow`
 *
 * R4 坑 ①：流式回调可能落在**引擎线程**上。`flow {}` 的 `emit` 只能在它自己的协程上下文中调，
 * 从别的线程 emit 是未定义行为。`channelFlow` 的 `send` 走 Channel，**任意线程安全**。
 * 真流式上线那天，这里若还是 `flow {}`，症状是「偶尔少几个 token」，极难复现。
 *
 * ## 唯一的状态：一个槽
 *
 * 物理上只有一条 Conversation（F3），所以只缓存**一个**会话槽。缓存第二个的收益是零，
 * 代价是「另一个槽里的 ModelSession 一定已经失效」——这个坑一定会有人踩。
 *
 * ## 中止
 *
 * `AbortSignal` 在每次生成前后都查。中止时 `:chat` 能做的是**把本模块持有的那条
 * Conversation close 掉**（`ModelSession : AutoCloseable`）——这才是 native 真的停下。
 * `conversation.cancelProcess()` 属于 `:llm`（R4 坑 ③）；仅取消 Flow 不会让 native 停。
 * 代价是丢 KV cache、下一轮复水，而这个代价是显式的（`FocusChanged(REHYDRATED, prefillTokens=…)`）。
 */
public class ChatLoop(
    private val model: ModelPort,
    private val focus: InferenceFocus,
    private val store: ChatSessionStorePort,
    private val profiles: AgentProfileStorePort,
    /** 具体类而不是 Port：`requiresConfirm` / `markConfirmed` 是注册表自己的语义。 */
    private val skills: ChatSkillRegistry,
    private val policy: PolicyPort,
    private val health: DeviceHealthPort,
    private val egress: ChatEgress,
    private val humanGate: HumanGate,
    private val traceStore: TraceStorePort,
    private val clock: Clock,
    private val tokenStream: TokenStream = TokenStream.SINGLE_SHOT,
    private val registry: ModeProfileRegistry = ModeProfileRegistry(),
    private val charter: SystemCharterText,
    private val redactionGate: RedactionGate = RedactionGate.SCRUB_ONLY,
    private val rehydrator: ChatRehydrator,
    private val historyCollector: ChatHistoryCollector,
    private val isolator: InjectionIsolator,
    private val router: SkillRouter = SkillRouter(),
) : ChatRunnerPort {

    private val estimate: (String) -> Int = { model.estimateTokens(it) }

    private val slotLock = Any()

    private var slot: Slot = Slot()

    /**
     * 同一会话**不许并发两个轮次**。
     *
     * 为什么必须有：两次 acquire 同一个 `Chat(sid)` 会走「同持有者续租」分支（refs+1，
     * 版本不变），于是两个轮次都认为自己拥有那条 Conversation —— 然后同时往同一个 native
     * session 里灌 prompt。那不会报错，只会串字。UI 上「连点两次发送」就够触发。
     * **只拦会话相同的并发**：会话不同的第二个轮次应该抢占（F3 的排队语义），不是被拒。
     *
     * ★ 2026-10 修正（关千锤 P0-1）：这里原本是**单个** `String?` 槽。单槽有两个独立的
     *   缺陷，且它们的表现都是「这个会话再也发不出消息」：
     *
     *   ① **早退泄漏**：进入时置位，只有正常路径复位 —— 三条提前 `return` 全都漏。
     *      写库失败一次 ⇒ 会话被永久标记成「正在生成中」⇒ 之后所有消息都被拦，
     *      而 `ChatLoop` 是单例 ⇒ **只有杀进程能恢复**。
     *   ② **跨会话覆写**：槽里只装得下一个会话 id。会话 A 在跑时 B 起一轮 ⇒ 槽变成 B，
     *      于是 A 的 `exitFlight(A)` 打空（守卫是 `== sid`），而**第三轮打在 A 上会被放行**
     *      ⇒ 又回到「同会话并发」这个本源上。
     *
     *   所以改成集合：置位/复位天然配对，且互不干扰。复位由 [turn] 里**唯一**的
     *   `try/finally` 负责，**不靠在每个 return 前手写一遍** —— 那种写法迟早漏第四条。
     */
    private val inFlightSessions: MutableSet<String> = mutableSetOf()

    /**
     * 模式控制器**不放在会话槽里**。
     *
     * 踩过一次：它原先跟着 `Slot` 走，而重建会话时 `Slot` 会被整体清空 ⇒ epoch 回到
     * `chat_session.mode_epoch` 的旧值 ⇒ 同一个 epoch 号可能出现两次。
     * epoch 是「本轮 prompt 按第几版剖面排的」唯一证据，重复就等于证据作废。
     */
    private var modeHolder: Pair<String, ModeSwitchController>? = null

    private val systemText = ChatSystemText(charter, isolator, estimate)

    // ══════════════════════════════════════════════════════════════════ 入口

    override fun turn(req: ChatTurnRequest): Flow<ChatEvent> = channelFlow {
        val sid = req.sessionId
        if (!enterFlight(sid)) {
            AkeLog.w(TAG, "turn rejected: session=$sid 已有一轮在跑（同会话并发会让 native 串字）")
            send(ChatEvent.Terminal(sid, null, ChatTerminalReason.POLICY_DENIED))
            return@channelFlow
        }
        // ★★ P0-1：`try` 紧贴 `enterFlight` 成功之后，`finally` 覆盖本块内的**全部**退出路径：
        //   四条提前 return（并发被拒之外的三条 + 早退）、正常结束、`CancellationException`、
        //   任意 `Throwable`。这是「复位」这件事**唯一**的写法 —— 在每条 return 前手写
        //   `exitFlight` 才是上次泄漏的成因（漏了第四条，而且永远会漏）。
        //   注意 `finally` 里**不做 send**：取消风暴里 send 会二次抛出，掩盖真实终态。
        try {
            admit(req, sid, this)
        } finally {
            exitFlight(sid)
        }
    }

    /**
     * 准入之后的全部工作。抽成独立函数是为了让 [turn] 里只剩「置位 → try → 复位」三行：
     * 只要这个函数里的任何一条 `return` 生效，`turn` 的 `finally` 都必然执行。
     */
    private suspend fun admit(
        req: ChatTurnRequest,
        sid: String,
        out: ProducerScope<ChatEvent>,
    ) {
        val budget = ChatBudget.forTier(health.memoryTier())
        val ledger = TurnLedger(budget)
        ledger.startTurn(clock.nowMillis())

        // ── 作用域：sessionId → (agentId, profile)。契约里没有 byId(sessionId)，用列表收敛。
        val scope = resolveScope(sid)
        if (scope == null) {
            AkeLog.w(TAG, "turn rejected: session=$sid 不存在或人格不可读")
            out.send(ChatEvent.Terminal(sid, null, ChatTerminalReason.POLICY_DENIED))
            return
        }

        // ── ① 落用户消息（不可丢泳道）。失败 ⇒ 这一轮不能开始，且必须让用户看见。
        val now = clock.nowMillis()
        // ★ P1-4：落库幂等键由**这一轮的身份**派生，不用 `req.clientRequestId`。
        //   理由见 `userMessageKey`：调用方每次重试都会生成新的随机构，用它当键
        //   等于没有键（UNIQUE 一次都不命中 ⇒ 会话里堆出 N 条一样的用户消息）。
        val requestKey = userMessageKey(sid, req.turnId)
        // 用 lambda 重载：默认级别是 INFO，这行根本不会输出，不该为它拼字符串。
        AkeLog.d(TAG) { "turn start: session=$sid turn=${req.turnId} inbound=${req.clientRequestId} key=$requestKey" }
        val appended = store.append(
            AppendMessage(
                id = Ulid.next(now),
                sessionId = sid,
                turnId = req.turnId,
                role = MessageRole.USER,
                kind = MessageKind.TEXT,
                text = req.userText,
                clientRequestId = requestKey,
                createdAt = now,
            ),
        )
        if (appended is Outcome.Err) {
            AkeLog.w(TAG, "user message append failed: ${appended.error.code}")
            // ★ P1-1：这一处失败时**回答还没有开始生成**，所以不能报 STORE_FAILED
            //   （那档的文案是「回答生成好了，但没存下来」——用户会去找一个不存在的回答）。
            out.send(ChatEvent.Terminal(sid, null, REQUEST_STORE_FAILURE_REASON))
            return
        }

        // ── ② 出云裁决：MVP 恒 LOCAL_ONLY，但审计这一笔必须落下（T12）。
        val decision = egress.decide(routeContext(req, scope.summary.agentId, budget))
        if (decision.target != InferenceTarget.LOCAL_ONLY) {
            // 本轮没有云端实现 ⇒ 明确拒绝，而不是「先允许再在一半失败」。
            AkeLog.w(TAG, "routing=${decision.target} 但本模块没有云端实现，拒绝")
            out.send(ChatEvent.Terminal(sid, null, ChatTerminalReason.POLICY_DENIED))
            return
        }

        val result = try {
            runTurn(req, scope, budget, ledger, out)
        } catch (ce: CancellationException) {
            closeConversation("cancelled")
            throw ce
        } catch (t: Throwable) {
            AkeLog.e(TAG, "turn failed: ${t.message}", t)
            closeConversation("error")
            TurnResult(null, ChatTerminalReason.MODEL_FAILED)
        }
        // 正常结束**不**释放焦点：会话留在 cache 里供下一轮复用（§3.5 的 L1 层）。
        // 释放只发生在「被抢占 / 紧凑 / 中止 / 出错」四条路径上。
        out.send(ChatEvent.Terminal(sid, result.messageId, result.terminal))
    }

    // ══════════════════════════════════════════════════════════════════ 一轮

    private suspend fun runTurn(
        req: ChatTurnRequest,
        scope: Scope,
        budget: ChatBudget,
        ledger: TurnLedger,
        out: ProducerScope<ChatEvent>,
    ): TurnResult {
        val sid = req.sessionId
        val agentId = scope.summary.agentId
        val profile = scope.profile

        val enabledSkills = skills.enabledFor(agentId)
        val mode = modeControllerFor(sid, scope.summary)

        // ── ③ 焦点 + 会话（复用 or 复水）
        val lease = borrowFocus(sid, agentId) ?: return TurnResult(null, ChatTerminalReason.MODEL_FAILED)
        val conversation = ensureConversation(sid, profile, enabledSkills, budget, lease, mode, out)
            ?: run {
                // ★ P0-1 附注（关千锤点名要查的同款问题）：`ensureConversation` 失败时
                //   会话槽已被 `closeConversation("rebuild", keepLease = lease)` 清空，
                //   这条租约**没有任何地方持有**了 —— 不 close 它就等于仲裁器里永远留着
                //   一个 `Chat(sid)` 的 owner。`FOREGROUND_USER` 之间还能互相抢占所以看不出来，
                //   但 `DERIVED_TASK` 会永远拿到 `FOCUS_BUSY` ⇒ 「任务一次都起不来」。
                //   `close()` 内部的 version 守卫保证：这条租约若已被抢占，这里是纯 no-op。
                releaseFocus(lease)
                return TurnResult(null, ChatTerminalReason.MODEL_NOT_LOADED)
            }

        // 本轮历史读一次：Router 与 Answer 两次组装共用（一轮内消息不再变化）。
        val views = store.messages(sid, limit = HISTORY_READ_LIMIT).first()
        val history = historyFor(views, mode.profile())

        var skillCalls = 0
        var skillResult: String? = null
        var derivedTaskId: String? = null

        // ── ④⑤ Router → (Skill)* 循环。单轮技能调用上限 3（Q7）。
        while (true) {
            if (req.abort.isAborted()) return TurnResult(null, ChatTerminalReason.USER_ABORT)
            if (!lease.isValid()) return yielded(sid, out)
            if (budgetExceeded(ledger)) return TurnResult(null, ChatTerminalReason.BUDGET_EXHAUSTED)

            val prompt = assemble(
                chatInput(scope, req, mode, history, skillResult, derivedTaskId, ledger, ROUTER_GOAL),
                budget,
                mode.profile(),
            )
            val routerOut = generate(conversation, routerRequest(prompt, budget), ledger)
            if (routerOut is Outcome.Err) {
                AkeLog.w(TAG, "router turn failed: ${routerOut.error.code}")
                return TurnResult(null, ChatTerminalReason.MODEL_FAILED)
            }
            val raw = (routerOut as Outcome.Ok).value.rawText

            when (val decision = router.decide(raw, enabledSkills)) {
                is SkillRouter.TurnDecision.Reply -> {
                    out.send(ChatEvent.RouterPlanned(sid, TurnMode.REPLY, null, null))
                    break
                }

                is SkillRouter.TurnDecision.Rejected -> {
                    // 模型想调一个不存在 / 未授权 / 参数不合法的技能。**这条痕迹必须留**：
                    // 它要么是模型幻觉，要么是注入在试能力边界（V4 的观测点）。
                    AkeLog.w(TAG, "router rejected: ${decision.code}")
                    out.send(ChatEvent.RouterPlanned(sid, TurnMode.REPLY, null, null))
                    break
                }

                is SkillRouter.TurnDecision.Skill -> {
                    val spec = decision.spec
                    out.send(
                        ChatEvent.RouterPlanned(sid, TurnMode.SKILL_CALL, spec.ref, redactArgs(decision.call)),
                    )
                    if (!req.allowSkillInvocation) {
                        AkeLog.i(TAG, "skill call suppressed by request (allowSkillInvocation=false)")
                        break
                    }
                    if (skillCalls >= MAX_SKILL_CALLS_PER_TURN) {
                        // 超限就把中间结果交给用户，而不是继续默默跑（Q7 的结构性手段）。
                        AkeLog.w(TAG, "skill call cap reached ($MAX_SKILL_CALLS_PER_TURN)；转由回答总结")
                        break
                    }
                    if (!confirmSkill(agentId, spec, decision.call, sid, out)) {
                        skillResult = "（用户拒绝了本次技能调用，因此没有执行。）"
                        break
                    }
                    skillCalls++
                    ledger.onSkillCall()

                    // ── 模式切到 TASK_IN_CHAT：成本显式记账（§2.7 第 3 条）
                    //
                    // ★ 切过去之后**本轮不再切回来**：剩下的 Router / Answer 都跑在
                    //   TASK_IN_CHAT 剖面下，这样任务步的压缩行才进得了 `HISTORY_COMPACT`
                    //   （§3.4 的「双源」）。代价是每次派生任务付一次切换 prefill ——
                    //   而不是每个技能各付两次（来回各一次），账更好看也更接近真实重排次数。
                    val switchingCost = estimate(prompt.render())
                    mode.to(ModeKind.TASK_IN_CHAT, switchingCost)?.let { t ->
                        out.send(ChatEvent.ModeSwitched(sid, t.from, t.to, t.epoch, t.prefillTokens))
                    }

                    val run = runSkill(decision.call, spec, scope.summary.agentId, req, mode, budget, out)
                    skillResult = run.summary
                    derivedTaskId = run.derivedTaskId
                    if (!run.ok) AkeLog.w(TAG, "skill ${spec.name} 未成功（${run.summary.take(80)}），转由回答解释")
                    if (run.aborted) return TurnResult(null, ChatTerminalReason.USER_ABORT)
                    // 技能失败**不是**整轮失败：失败原因同样进 <skill_result>（不可信），
                    // 由 Answer Turn 用自然语言向用户解释 —— 失败也要解释。
                }
            }
        }

        // ── ⑥ Answer Turn（若本轮跑过技能，它跑在 TASK_IN_CHAT 剖面下）
        return answer(
            req, scope, mode, budget, ledger, conversation, lease, history, skillResult, derivedTaskId, out,
        )
    }

    // ══════════════════════════════════════════════════════════════════ 会话与焦点

    /**
     * 借焦点。
     *
     * **会话缓存与租约绑在一起**：槽里那条 Conversation 只有在「它的租约仍然有效」时才可信。
     * 租约失效意味着别人已经抢占并开了自己的会话 —— 此时复用一个死句柄，
     * native 会以最糟的方式告诉你（崩溃或静默错乱）。
     */
    private suspend fun borrowFocus(sid: String, agentId: String): FocusLease? {
        val held = synchronized(slotLock) { slot.lease }
        val cachedSessionId = synchronized(slotLock) { slot.sessionId }
        if (held != null && held.isValid() && cachedSessionId == sid) return held

        return when (val r = focus.acquire(FocusHolder.Chat(sid, agentId), FocusPriority.FOREGROUND_USER)) {
            is Outcome.Ok -> {
                synchronized(slotLock) {
                    slot.lease?.close() // version 守卫会让这条对已被抢占的旧租约变成 no-op
                    slot = slot.copy(lease = r.value)
                }
                r.value
            }

            is Outcome.Err -> {
                AkeLog.w(TAG, "focus denied: ${r.error.code}")
                null
            }
        }
    }

    /**
     * 拿到一条可用的 Conversation。
     *
     * 三条「必须开新会话」的理由（§3.5）：
     *  ① 换了会话（用户切到另一个 Agent 的对话）；
     *  ② 租约失效（KV cache 已经被别人丢掉了）；
     *  ③ `systemInstruction` 变了（改人格 / 改技能清单 —— 它在创建时固定，改不了）。
     */
    private suspend fun ensureConversation(
        sid: String,
        profile: AgentProfile,
        enabledSkills: List<SkillSpec>,
        budget: ChatBudget,
        lease: FocusLease,
        mode: ModeSwitchController,
        out: ProducerScope<ChatEvent>,
    ): ModelSession? {
        val systemCap = mode.profile().caps.system
        // ★ 指纹按「输入」算（persona / 技能清单 / cap），不按渲染结果 ——
        //   渲染结果含随机 nonce，拿它取 hash 会让每轮都判成「人格变了」而重建会话，
        //   KV cache 复用（§3.5 的 L1 层）就静默失效了。见 ChatPromptInput.systemFingerprint。
        val fp = ChatPromptInput.systemFingerprint(profile.personaCharter, enabledSkills, systemCap)

        val cached = synchronized(slotLock) { slot }
        if (cached.conversation != null && cached.sessionId == sid && cached.systemFp == fp && lease.isValid()) {
            out.send(ChatEvent.FocusChanged(sid, FocusKind.REUSED, prefillTokens = 0, elapsedMs = 0))
            return cached.conversation
        }

        val startedAt = clock.nowMillis()
        val system = systemText.build(
            persona = profile.personaCharter,
            skills = enabledSkills,
            disclaimer = null,
            cap = systemCap,
        )
        if (cached.conversation != null) closeConversation("rebuild", keepLease = lease)

        val initial = rehydrator.buildInitialMessages(sid, budget)
        val spec = SessionSpec(
            modelId = profile.modelId,
            // 这不是真正的 task：它是引擎侧给会话起的标签。带 `chat:` 前缀，
            // 排障时一眼能区分「聊天会话」与「任务会话」。
            taskId = "chat:$sid",
            systemInstruction = system,
            maxOutputTokens = budget.answerMaxTokens,
            temperature = 0.4,
            topK = 40,
            topP = 0.95,
            thinking = ThinkingBudget.Off,
            // 每轮各自指定 OutputShape（Router 用 JsonSchema、Answer 用 FreeText），
            // 所以不能把引擎侧约束钉死成 AgentStep。
            enableJsonConstraint = false,
            initialMessages = initial.turns,
        )

        return when (val opened = model.openSession(spec)) {
            is Outcome.Ok -> {
                synchronized(slotLock) {
                    slot = Slot(
                        sessionId = sid,
                        conversation = opened.value,
                        systemFp = fp,
                        lease = lease,
                        rehydrateTokens = initial.tokens,
                        turnsSinceCompact = 0,
                    )
                }
                out.send(
                    ChatEvent.FocusChanged(
                        sid,
                        FocusKind.REHYDRATED,
                        prefillTokens = initial.tokens,
                        elapsedMs = clock.nowMillis() - startedAt,
                    ),
                )
                opened.value
            }

            is Outcome.Err -> {
                AkeLog.w(TAG, "openSession failed: ${opened.error.code}")
                null
            }
        }
    }

    /**
     * 关会话：**先快照 KV，再 close，最后记账**。顺序不能换 ——
     * `currentCacheTokens()` 在 close 之后没有意义，而 `recordFocusRelease` 写的正是这份快照
     * （下一轮的复水基准）。
     */
    private suspend fun closeConversation(reason: String, keepLease: FocusLease? = null) {
        val current = synchronized(slotLock) {
            val s = slot
            slot = Slot()
            s
        }
        val conversation = current.conversation ?: return
        val kv = runCatching { conversation.currentCacheTokens() }.getOrDefault(0)
        runCatching { conversation.close() }
            .onFailure { AkeLog.w(TAG, "conversation.close failed: ${it.message}") }
        // ★ 正要复用的那条租约不能关：重建会话时我们刚借到焦点，关掉它这一轮立刻就会
        //   `isValid() == false`，表现为「每次换人格后的第一轮直接让出」。
        if (current.lease !== keepLease) current.lease?.close()

        val sid = current.sessionId
        val mode = modeHolder?.takeIf { it.first == sid }?.second
        if (sid != null && mode != null) {
            store.recordFocusRelease(
                sessionId = sid,
                kvTokens = kv,
                rehydrateTokens = current.rehydrateTokens,
                mode = mode.loopMode(),
                epoch = mode.epoch,
                profile = mode.profile().name,
            )
        }
        AkeLog.i(TAG, "conversation closed ($reason): session=$sid kv=$kv")
    }

    /**
     * 释放一条**本模块已经不再持有**的租约（会话建不起来时用）。
     *
     * 与 [closeConversation] 的分工：那条处理「会话还在槽里」的情形（先快照 KV、记账、再关），
     * 本函数只处理「槽已经被清掉、租约只剩局部变量引用」的漏口 —— 这条漏口正是
     * `InferenceFocusArbiter.owner` 变成永久脏值的原因。
     */
    private fun releaseFocus(lease: FocusLease) {
        synchronized(slotLock) {
            if (slot.lease === lease) slot = slot.copy(lease = null)
        }
        runCatching { lease.close() }
            .onFailure { AkeLog.w(TAG, "lease.close failed: ${it.message}") }
    }

    /** 被抢占：**不 close** 别人的资源，只丢掉自己那份句柄（并记账）。 */
    private suspend fun yielded(sid: String, out: ProducerScope<ChatEvent>): TurnResult {
        AkeLog.i(TAG, "focus yielded away from session=$sid")
        out.send(ChatEvent.FocusChanged(sid, FocusKind.YIELDED, prefillTokens = 0, elapsedMs = 0))
        closeConversation("yielded")
        return TurnResult(null, ChatTerminalReason.USER_ABORT)
    }

    // ══════════════════════════════════════════════════════════════════ 组装

    private suspend fun chatInput(
        scope: Scope,
        req: ChatTurnRequest,
        mode: ModeSwitchController,
        history: List<Pair<Int, String>>,
        skillResult: String?,
        derivedTaskId: String?,
        ledger: TurnLedger,
        goal: String,
    ): ChatPromptInput = ChatPromptInput(
        agentId = scope.summary.agentId,
        sessionId = req.sessionId,
        turnId = req.turnId,
        userTurn = req.userText,
        historyLines = history,
        skillResult = skillResult,
        kind = mode.kind,
        epoch = mode.epoch,
        remainingBudgetPct = ledger.remainingPct(clock.nowMillis()),
        goal = goal,
        // ★ 任务步 trace 由 `:agent` 落、本模块只读。只有在 TASK_IN_CHAT 剖面下才把它
        //   装进 `HISTORY_COMPACT` —— 这正是 §3.4「双源」里属于任务的那一半，
        //   而压缩本身仍然由 `:agent` 的 `DeterministicCompactor` 做（我们不重抄）。
        taskTrace = if (mode.kind == ModeKind.TASK_IN_CHAT && derivedTaskId != null) {
            runCatching { traceStore.load(com.ake.core.common.TaskId(derivedTaskId)) }.getOrDefault(emptyList())
        } else {
            emptyList()
        },
    )

    private fun assemble(input: ChatPromptInput, budget: ChatBudget, profile: ModeProfile): PromptSections {
        // ★ 组装只有这一处：复用 `:agent` 的 ContextAssembler 与它的降级阶梯。
        //   SYSTEM 段恒空（宪章/人格在 SessionSpec.systemInstruction 里，不进每轮 prompt），
        //   cap 来自 profile —— 同一个 ContextBudget 换剖面，不是第二套预算。
        val assembler = ContextAssembler(
            estimate = estimate,
            isolator = isolator,
            charter = FixedSystemCharter.EMPTY,
        )
        return assembler.assemble(input.toAssembleRequest(budget.contextBudget, profile))
    }

    /**
     * 本轮历史。
     *
     * @param profile 当前剖面 —— cap 取它的 `RECENT_TRACE` 减 [META_RESERVE_TOKENS]。
     */
    private fun historyFor(views: List<ChatMessageView>, profile: ModeProfile): List<Pair<Int, String>> {
        // 给 <meta>(48) 与 OBSERVATION 占位(~20) 留余量：总量顶到 usable 会触发一次降级，
        // 而降级的第一刀就是整段 RECENT_TRACE —— 那等于「预算紧一点，历史全没了」。
        val cap = (profile.caps.recentTrace - META_RESERVE_TOKENS).coerceAtLeast(0)
        val lines = historyCollector.collect(views, cap).lines
        // ★ 防御：本轮用户那句话**必须**在 prompt 里。装箱理论上不会丢最新的，
        //   但「理论上不会」不足以支撑「模型对着空上下文回答」这种故障。
        val last = views.lastOrNull() ?: return lines
        if (lines.lastOrNull()?.first != last.seq) {
            AkeLog.w(TAG, "history packing dropped the newest line; forcing it back")
            return lines + (last.seq to historyCollector.verbatimLine(last))
        }
        return lines
    }

    // ══════════════════════════════════════════════════════════════════ 生成

    private suspend fun generate(
        session: ModelSession,
        req: GenerationRequest,
        ledger: TurnLedger,
    ): Outcome<GenerationResult> {
        val r = session.generate(req)
        if (r is Outcome.Ok) {
            ledger.onPrefill(r.value.stats.prefillTokens)
            ledger.onDecode(r.value.stats.decodeTokens)
        }
        return r
    }

    private fun routerRequest(prompt: PromptSections, budget: ChatBudget): GenerationRequest = GenerationRequest(
        stepId = StepId.new(),
        userTurn = prompt.render(),
        outputShape = OutputShape.JsonSchema(SkillRouter.ROUTER_JSON_SCHEMA),
        maxOutputTokens = ChatBudget.ROUTER_MAX_TOKENS,
        thinking = ThinkingBudget.Off,
        deadlineMs = budget.maxWallClockMsPerTurn,
    )

    private fun answerRequest(prompt: PromptSections, budget: ChatBudget): GenerationRequest = GenerationRequest(
        stepId = StepId.new(),
        userTurn = prompt.render(),
        outputShape = OutputShape.FreeText,
        maxOutputTokens = budget.answerMaxTokens,
        thinking = ThinkingBudget.Off,
        deadlineMs = budget.maxWallClockMsPerTurn,
    )

    private suspend fun answer(
        req: ChatTurnRequest,
        scope: Scope,
        mode: ModeSwitchController,
        budget: ChatBudget,
        ledger: TurnLedger,
        conversation: ModelSession,
        lease: FocusLease,
        history: List<Pair<Int, String>>,
        skillResult: String?,
        derivedTaskId: String?,
        out: ProducerScope<ChatEvent>,
    ): TurnResult {
        val sid = req.sessionId
        if (!lease.isValid()) return yielded(sid, out)

        val messageId = Ulid.next(clock.nowMillis())
        // ★ 先建占位行（STREAMING）：UI 从第一个 delta 起就需要 messageId；
        //   而「流式期间不落盘」这条纪律（R6）靠这一行给出一个可渲染的空助手消息。
        val begun = store.beginAssistant(sid, req.turnId, messageId)
        if (begun is Outcome.Err) {
            AkeLog.w(TAG, "beginAssistant failed: ${begun.error.code}")
            // ★ P1-1：占位行都建不出来 ⇒ 那句「回答生成好了」是纯粹的假话。
            //   用户消息已经落库了（上一步），所以重发时幂等键会挡住重复的用户消息。
            return TurnResult(null, REQUEST_STORE_FAILURE_REASON)
        }

        val prompt = assemble(
            chatInput(scope, req, mode, history, skillResult, derivedTaskId, ledger, ANSWER_GOAL),
            budget,
            mode.profile(),
        )

        val scrubber = DeltaScrubber()
        val buffer = StringBuilder()
        val result = tokenStream.generate(conversation, answerRequest(prompt, budget)) { delta ->
            // 闸 1：控制 token / 零宽字符。跨 chunk 的半个 token 由 DeltaScrubber 负责 ———
            // 两者是两层，任何一层单用都会漏（见 DeltaScrubber 注释）。
            val cleaned = policy.sanitize(scrubber.feed(delta), ContentSource.MODEL).text
            if (cleaned.isNotEmpty()) {
                buffer.append(cleaned)
                out.send(ChatEvent.AssistantDelta(sid, messageId, cleaned))
            }
        }
        val tail = policy.sanitize(scrubber.flush(), ContentSource.MODEL).text
        if (tail.isNotEmpty()) {
            buffer.append(tail)
            out.send(ChatEvent.AssistantDelta(sid, messageId, tail))
        }

        val terminal = when {
            req.abort.isAborted() -> ChatTerminalReason.USER_ABORT
            result is Outcome.Err -> ChatTerminalReason.MODEL_FAILED
            else -> ChatTerminalReason.COMPLETED
        }
        val status = when (terminal) {
            ChatTerminalReason.COMPLETED -> MessageStatus.COMPLETE
            ChatTerminalReason.USER_ABORT -> MessageStatus.ABORTED
            else -> MessageStatus.FAILED
        }

        val stats = (result as? Outcome.Ok)?.value?.stats
        val kv = runCatching { conversation.currentCacheTokens() }.getOrDefault(0)
        val full = buffer.toString()
        // ★ P0-3：**同一轮内**重试一次。这里重试的是「落盘」而不是「重新生成」——
        //   文本还在 `full` 里，`commitAssistant` 是按 messageId 的 UPDATE（幂等），
        //   所以一次重试既不会重复写入，也不需要用户重发。这才是「只重试落盘」唯一
        //   真正可实现的形态：一旦放弃，那段文本就没有第二个持有者了。
        var committed = commitAssistant(messageId, status, full, stats, kv)
        if (committed is Outcome.Err) {
            AkeLog.w(TAG, "commitAssistant failed (${committed.error.code})；同一轮内重试一次")
            committed = commitAssistant(messageId, status, full, stats, kv)
        }

        // ── ⑦ 回到 REPLY：任务已经在 Answer Turn 里被复述完，本轮到此结束。
        //   这次切换是**显式记账**的 —— 退出任务模式同样要重排一次 prompt。
        mode.to(ModeKind.CHAT, estimate(prompt.render()))?.let { t ->
            out.send(ChatEvent.ModeSwitched(sid, t.from, t.to, t.epoch, t.prefillTokens))
        }

        maybeCompact(sid, conversation, budget, out)

        if (committed is Outcome.Err) {
            AkeLog.w(TAG, "commitAssistant failed after retry: ${committed.error.code}")
            // ★ 只有走到这里才有资格说「回答生成好了，但没存下来」：
            //   本轮模型确实答完了（`full` 里就是那段文本，delta 也已经在屏幕上流过了），
            //   卡住的是提交。生成前那两处写库失败走 REQUEST_STORE_FAILURE_REASON（P1-1）。
            //   只在「本轮本来算成功」时改写终态：模型自己失败（MODEL_FAILED）或用户中止
            //   （USER_ABORT）的归因比存储失败更上游，抢走它会变成第二种误归因。
            return TurnResult(
                messageId,
                if (terminal == ChatTerminalReason.COMPLETED) STORE_FAILURE_REASON else terminal,
            )
        }
        return TurnResult(messageId, terminal)
    }

    private suspend fun commitAssistant(
        messageId: String,
        status: MessageStatus,
        text: String,
        stats: com.ake.core.modelapi.InferenceStats?,
        kv: Int,
    ): Outcome<Unit> =
        store.commitAssistant(
            messageId = messageId,
            status = status,
            text = text,
            promptTokens = stats?.prefillTokens ?: 0,
            decodeTokens = stats?.decodeTokens ?: 0,
            kvCacheTokensAfter = kv,
            redactionCount = redactionGate.scrub(text).redactionCount,
        )

    /**
     * 紧凑判据（§3.5 + 既有 §4.3）：KV 超过可用窗口 85%，且距上次紧凑 ≥6 轮。
     *
     * 触发后**主动关会话**：下一次进入按紧凑游标复水，于是「历史变短」这件事在下一轮的
     * `FocusChanged(REHYDRATED, prefillTokens=…)` 里是可见的，而不是悄悄发生。
     */
    private suspend fun maybeCompact(
        sid: String,
        conversation: ModelSession,
        budget: ChatBudget,
        out: ProducerScope<ChatEvent>,
    ) {
        val kv = runCatching { conversation.currentCacheTokens() }.getOrDefault(0)
        val threshold = (budget.usablePromptTokens * COMPACT_THRESHOLD).toInt()
        val turns = synchronized(slotLock) { slot.turnsSinceCompact }
        if (kv <= threshold || turns < MIN_TURNS_BETWEEN_COMPACT) {
            synchronized(slotLock) { slot = slot.copy(turnsSinceCompact = turns + 1) }
            return
        }
        val uptoSeq = store.messages(sid, limit = 1).first().lastOrNull()?.seq ?: 0
        store.markCompacted(sid, uptoSeq, level = 1)
        out.send(ChatEvent.Compacted(sid, uptoSeq, level = 1, droppedTurns = turns))
        AkeLog.i(TAG, "compacted session=$sid upto=$uptoSeq kv=$kv threshold=$threshold")
        closeConversation("compacted")
    }

    // ══════════════════════════════════════════════════════════════════ 技能

    private suspend fun confirmSkill(
        agentId: String,
        spec: SkillSpec,
        call: SkillCall,
        sid: String,
        out: ProducerScope<ChatEvent>,
    ): Boolean {
        // S4：确认只看 ref + always_allow 派生的 confirmPolicy，与「人设里写了什么」无关。
        if (!skills.requiresConfirm(agentId, spec)) return true
        // FORBIDDEN 不提供确认绕过（`SensitiveActionPolicy.FORBIDDEN` 的语义）。
        if (spec.riskClass == RiskClass.FORBIDDEN) return false

        out.send(
            ChatEvent.ConfirmRequested(
                sessionId = sid,
                callId = call.callId,
                skillRef = spec.ref,
                reasonKey = REASON_FIRST_USE_CONFIRM,
                args = call.args.values.toList(),
            ),
        )
        // ★ 确认走的是**同一条 HumanGate**：任务内部的 CONFIRM 也回到聊天气泡，
        //   不允许弹第二个对话框（§2.6 纪律 5 / §8.2）。
        val resolved = humanGate.resolve(
            PolicyVerdict.RequiresConfirm(REASON_FIRST_USE_CONFIRM, listOf(spec.name)),
            StepAction.AskHuman("允许这个 Agent 使用 ${spec.name} 吗？"),
        )
        return when (resolved) {
            Resolved.Proceed -> {
                skills.markConfirmed(agentId, spec)
                true
            }
            // Amend 对「能力首用确认」没有语义（它改的是动作参数，不是能力授权），按拒绝处理。
            is Resolved.Reject, Resolved.Abort, is Resolved.Amend -> false
        }
    }

    private suspend fun runSkill(
        call: SkillCall,
        spec: SkillSpec,
        agentId: String,
        req: ChatTurnRequest,
        mode: ModeSwitchController,
        budget: ChatBudget,
        out: ProducerScope<ChatEvent>,
    ): SkillRun {
        val sid = req.sessionId
        val envelope: BudgetEnvelope = budget.envelopeFor(
            if (mode.kind == ModeKind.CHAT) ModeKind.TASK_IN_CHAT else mode.kind,
        )
        val ctx = SkillContext(
            agentId = agentId,
            sessionId = sid,
            callId = call.callId,
            turnId = req.turnId,
            budgetEnvelope = envelope,
            abort = req.abort,
            humanGate = humanGate,
            redactionGate = redactionGate,
        )

        out.send(
            ChatEvent.SkillStarted(
                sessionId = sid,
                callId = call.callId,
                skillRef = spec.ref,
                labelRedacted = spec.name,
                derivedTaskId = ctx.derivedTaskId(),
            ),
        )

        var summary: String? = null
        var ok = false
        var aborted = false

        skills.invoke(call, ctx).collect { ev ->
            when (ev) {
                // 已发过 ChatEvent.SkillStarted，不重复
                is SkillEvent.Started -> Unit

                is SkillEvent.Progress -> out.send(
                    ChatEvent.SkillProgress(
                        sessionId = sid, callId = call.callId, kind = ev.kind,
                        detailRedacted = ev.detailRedacted, stepSeq = ev.stepSeq,
                        stepsDone = ev.stepsDone, maxSteps = ev.maxSteps,
                    ),
                )

                // 任务内部的 CONFIRM：同样回到气泡（HumanGate 是同一个对象，UI 的答复直接解锁它）
                is SkillEvent.NeedsConfirm -> out.send(
                    ChatEvent.ConfirmRequested(sid, call.callId, spec.ref, ev.reasonKey, ev.args),
                )

                is SkillEvent.Succeeded -> {
                    ok = true
                    summary = ev.outcome.summaryRedacted
                }

                is SkillEvent.Failed -> {
                    ok = false
                    aborted = req.abort.isAborted()
                    summary = ev.reasonRedacted ?: "（技能没有返回可读原因：${ev.code}）"
                }
            }
        }

        // ★ 纪律 ④：任务产出**不是**助手回答。它先过闸 2（`InjectionIsolator`：
        //   逐条围栏 + nonce）进 `<skill_result>`，再由一次 Answer Turn 复述。
        //   两步不能合并 —— 合并等于把网页内容直接贴给用户。
        val isolated = isolator.isolate(summary.orEmpty(), ContentSource.TOOL_RETURN, SKILL_RESULT_TAG)
        out.send(
            ChatEvent.SkillResult(
                sessionId = sid,
                callId = call.callId,
                summaryRedacted = isolated.rendered,
                taskId = ctx.derivedTaskId(),
                redactionCount = redactionGate.scrub(summary.orEmpty()).redactionCount,
                escalation = isolated.escalation.name,
            ),
        )
        return SkillRun(
            ok = ok,
            aborted = aborted,
            summary = isolated.rendered,
            derivedTaskId = ctx.derivedTaskId(),
        )
    }

    // ══════════════════════════════════════════════════════════════════ 小工具

    private suspend fun resolveScope(sessionId: String): Scope? {
        val summary = store.sessions(agentId = null, includeArchived = true).first()
            .firstOrNull { it.id == sessionId } ?: return null
        // ★ `ChatSessionSummary.agentId` 是 `agent_profile.id`（主键），不是对外短名 ——
        //   `chat_session.agent_id` 的外键就是 `REFERENCES agent_profile(id)`。
        //   先按主键查，再退回短名：后者只用于兜住"历史上按短名写入的会话行"，
        //   而那种行在 FK 打开时根本插不进去（见 `ChatSessionStoreImpl.ensureForNewTurn`）。
        //   顺序不能反 —— 反了就等于默认走一条理论上不该存在的路径。
        val profile = (profiles.byId(summary.agentId) as? Outcome.Ok)?.value
            ?: (profiles.byAgentId(summary.agentId) as? Outcome.Ok)?.value
            ?: return null
        return Scope(summary, profile)
    }

    /**
     * 会话级模式控制器。
     *
     * 恢复 `mode_epoch` 从库里带回（不重置为 0 —— 重置会让「同一个 epoch」出现两次，
     * 而 epoch 是「本轮 prompt 按第几版剖面排的」唯一证据）。**起始模式恒为 CHAT**：
     * 一个尚未裁决的新轮次总是从对话开始；`TASK_IN_CHAT` 只由路由裁决进入。
     */
    private fun modeControllerFor(sid: String, summary: ChatSessionSummary): ModeSwitchController {
        val existing = synchronized(slotLock) { modeHolder?.takeIf { it.first == sid }?.second }
        if (existing != null) return existing
        val created = ModeSwitchController(
            tier = health.memoryTier(),
            registry = registry,
            initialKind = ModeKind.CHAT,
            initialEpoch = summary.modeEpoch,
        )
        synchronized(slotLock) { modeHolder = sid to created }
        return created
    }

    private fun enterFlight(sid: String): Boolean = synchronized(slotLock) {
        // ★ `add` 返回 false ⟺ 已在集合里 ⟺ 同会话确实有一轮在跑。置位与判定是同一次操作，
        //   不存在「查一下再设」之间被插队的窗口。
        inFlightSessions.add(sid)
    }

    private fun exitFlight(sid: String) {
        synchronized(slotLock) { inFlightSessions.remove(sid) }
    }

    private fun routeContext(req: ChatTurnRequest, agentId: String, budget: ChatBudget): RouteContext =
        RouteContext(
            agentId = agentId,
            sessionId = req.sessionId,
            turnId = req.turnId,
            requested = if (req.allowEgress) InferenceTarget.CLOUD_ASSISTED else InferenceTarget.LOCAL_ONLY,
            userGrantedThisTurn = req.allowEgress,
            estimatedContextTokens = budget.usablePromptTokens,
            needsLongContext = false,
            payloadClasses = setOf(DataClass.AMBER),
        )

    private fun budgetExceeded(ledger: TurnLedger): Boolean =
        ledger.prefillExceeded() || ledger.wallClockExceeded(clock.nowMillis())

    private fun redactArgs(call: SkillCall): String =
        call.args.entries.joinToString(", ") { (k, v) ->
            "$k=${redactionGate.scrub(v).text.take(40)}"
        }

    /**
     * 冷启动时收敛卡在 `STREAMING` 的助手消息（§2.5 纪律 2）。
     *
     * ★ 2026-10（关千锤 P0-2）：**生产调用点**是 `:app` 的
     *   `startup/ColdStartCoordinator`（`init { scope.launch { … } }` 自触发，
     *   与 `:data` 的 `TaskStateWriter.init` 调 `TaskReconciler.sweep()` 同一手法）。
     *   在这之前它是 0 调用 —— 于是「进程被杀留下的 STREAMING 脏行永不收敛」
     *   这条后果一直成立，而方法本身看起来是好的。
     *   `app/src/test/.../ColdStartWiringTest` 用**行为断言**守住这条线
     *   （2026-10 二次修正，关千锤 P1-2）：上一版那个测试只扫源码里的字符串，
     *   调用点被注释掉之后仍然全绿 —— 它挡不住任何东西。现在它真的构造
     *   `ColdStartCoordinator`（不调任何方法），断言 `chat` / `db` 两个 `Lazy`
     *   各被解引用一次：这行若被摘掉，`chat` 那个依赖 0 次，测试红。
     */
    public suspend fun onColdStart(): Int = store.sweepStreaming()

    private data class Scope(val summary: ChatSessionSummary, val profile: AgentProfile)

    private data class TurnResult(val messageId: String?, val terminal: ChatTerminalReason)

    private data class SkillRun(
        val ok: Boolean,
        val aborted: Boolean,
        val summary: String,
        val derivedTaskId: String,
    )

    /** 会话槽。**只有一个**，理由见类注释。 */
    private data class Slot(
        val sessionId: String? = null,
        val conversation: ModelSession? = null,
        val systemFp: Int = 0,
        val lease: FocusLease? = null,
        val rehydrateTokens: Int = 0,
        val turnsSinceCompact: Int = 0,
    )

    public companion object {
        public const val TAG: String = "ChatLoop"

        /** Q7 裁决：一次助手回答内最多 3 次技能调用。 */
        public const val MAX_SKILL_CALLS_PER_TURN: Int = 3

        /** 读历史的条数上限（DB 侧带 LIMIT，不会全表扫）。 */
        public const val HISTORY_READ_LIMIT: Int = 96

        /** 给 `<meta>` 与 OBSERVATION 占位留的余量，见 [historyFor]。 */
        public const val META_RESERVE_TOKENS: Int = 96

        /** §3.5：KV 超过可用窗口 85% 触发一次重建。 */
        public const val COMPACT_THRESHOLD: Double = 0.85

        /** 「重建后至少间隔 6 轮不再触发」—— 反复紧凑比不紧凑更伤（用户只会觉得这 App 有点怪）。 */
        public const val MIN_TURNS_BETWEEN_COMPACT: Int = 6

        public const val ROUTER_GOAL: String =
            "对用户最新这句话做一次路由裁决：需要网页操作就选技能，否则直接回答。只输出 JSON。"

        public const val ANSWER_GOAL: String =
            "用自然语言回答用户最新这句话。若上面有技能返回，用用户能懂的话复述结论，不要贴原文。"

        public const val REASON_FIRST_USE_CONFIRM: String = "confirm_skill_first_use"
        public const val SKILL_RESULT_TAG: String = "skill_result"

        /**
         * ★ 落盘失败的终态 = [ChatTerminalReason.STORE_FAILED]。
         *
         * 2026-10 修正（关千锤 P0-3）：这里原本是 `MODEL_FAILED` 的**别名**，理由是
         * 「契约枚举里没有 `STORE_FAILED`，本模块不擅自改契约」。那个理由当时是成立的，
         * 代价却由用户承担 —— 落盘失败被显示成「模型出错了」，而 UI 那一档 `retryable = true`，
         * 于是用户会去换模型、清缓存，全都对着空气使劲。
         *
         * ★ 2026-10 二次修正（关千锤 P1-1）：本常量现在**只管 `commitAssistant`**
         *   （= 模型答完了、产物在内存里、提交失败）。生成前那两处写库失败
         *   （`append(USER)` / `beginAssistant`）改用 [REQUEST_STORE_FAILURE_REASON] ——
         *   它们发生时**回答还没有开始生成**，套用本档的「回答生成好了，但没存下来」
         *   是在说假话。两档的分界写在 `ChatTerminalReason` 的注释里，不在这个文件里。
         */
        public val STORE_FAILURE_REASON: ChatTerminalReason = ChatTerminalReason.STORE_FAILED

        /**
         * ★ 生成**之前**的写库失败 ⇒ [ChatTerminalReason.REQUEST_STORE_FAILED]。
         *
         * 覆盖两个调用点：落 USER 消息、建助手占位行。这两处失败时一个 token 都没产生，
         * 所以 UI 只能说「这句话没能存下来，本轮没有开始」，不能说「回答没存下来」。
         *
         * ⚠ 与 [STORE_FAILURE_REASON] 的区别是**阶段**，不是严重程度：两处都要让用户看见，
         * 两处都 `retryable = true`，但指向的动作不同（重发 vs 补写失败后的重发）。
         */
        public val REQUEST_STORE_FAILURE_REASON: ChatTerminalReason =
            ChatTerminalReason.REQUEST_STORE_FAILED

        /**
         * ★★ 用户消息的落库幂等键：**由这一轮的身份派生**（关千锤 P1-4）。
         *
         * ```
         *   key = "user:<sessionId>:<turnId>"
         * ```
         *
         * ## 为什么不用 `ChatTurnRequest.clientRequestId`
         *
         * 那个字段是**入站请求**的标识，由调用方每次发起时生成（`:ui` 用
         * `UUID.randomUUID()`）。而重试恰恰是"再发起一次"⇒ 每次都是新随机值
         * ⇒ `UNIQUE(client_request_id)` 一次都不会命中 ⇒ 用户点三次重试，
         * 会话里出现 4 条一模一样的用户消息。契约里"幂等键"的注释
         * 与实现方向相反，这个键就是把它掰回来的地方。
         *
         * ## 为什么 (sessionId, turnId) 是正确的那对
         *
         * `turnId` 是**轮次分组键**，契约要求「重试时复用」（§9-U4），
         * 而「用户真的又发了一遍」在新的一轮里必然是新的 `turnId`。于是：
         *
         * | 场景 | turnId | 键 | 结果 |
         * |---|---|---|---|
         * | 重试同一轮 | 复用 | 相同 | `INSERT OR IGNORE` 命中 ⇒ 不落第二条 |
         * | 用户又发一遍 | 新建 | 不同 | 正常落一条（哪怕文字一模一样） |
         *
         * 也就是说：**重试与"再说一遍"的分界线是 turnId，不是文本是否相同** ——
         * 拿文本去重会把"用户真的重复说了一句话"吃掉，那是另一种数据丢失。
         *
         * 带上 `sessionId` 是因为 `client_request_id` 的 UNIQUE 索引是**全表**的
         * （`idx_chat_msg_request`），两个会话里同名的 turnId 不该互相压制。
         */
        public fun userMessageKey(sessionId: String, turnId: String): String =
            "user:$sessionId:$turnId"
    }
}
