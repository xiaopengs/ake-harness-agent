package com.ake.ui.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatRunnerPort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.MessageStatus
import com.ake.ui.model.CapabilityPresence
import com.ake.ui.model.ChatTurnFailure
import com.ake.ui.model.SkillCardUi
import com.ake.ui.model.capabilityPresenceOf
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Optional
import java.util.UUID
import javax.inject.Inject

/**
 * 一条**库里永远不会收敛**的占位消息，以及它该被渲染成什么。
 *
 * ## 它存在的唯一理由：写路径坏了，而 UI 不能等数据库
 *
 * `commitAssistant` 失败时（`ChatTerminalReason.STORE_FAILED`），助手占位行的
 * `status = STREAMING` 就再也改不动了 —— 写路径本身坏了，任何补写都会继续失败。
 * 那一行要等到**下次冷启的 `sweepStreaming()`** 才会变成 `FAILED`。
 *
 * 用户在这段时间里看到的是一条永远「正在生成」的空气泡。这不是"稍微慢一点"，
 * 而是界面在**持续撒谎**：模型其实早就答完了（文本就在 [text] 里）。
 *
 * 所以这一档不在等数据库收敛，而是由 UI 把这一轮的失败现场**覆盖**到那条消息的渲染上：
 * 立刻显示「回答生成好了，但没存下来」+ 重试。**纯渲染层，不写库** —— 写不进去。
 *
 * @property messageId 被覆盖的那条消息（= Terminal 事件里的 `messageId`，也是 `liveMessageId`）
 * @property failure 本轮失败现场；脚注的标题 / 下一步 / 是否可重试都从它取
 * @property text 失败发生时**已经流到屏幕上的那段回答**。它是这段话唯一的持有者：
 *   库里那行是空的，而 `liveText` 会在下一轮开始时被清空。不在这里冻住，用户已经读过的
 *   回答就会凭空消失 —— 那正好是 `STORE_FAILED` 文案要避免的事（"回答是好的"）。
 */
public data class UnconvergedPlaceholder(
    val messageId: String,
    val failure: ChatTurnFailure,
    val text: String,
)

/**
 * 一次会话的完整可渲染状态。
 *
 * ## 为什么"落库消息"与"飞行中的文本"是两个字段
 *
 * `messages` 来自 `ChatSessionStorePort.messages()`（Room Flow），它是**已经落盘的事实**；
 * `liveText` 是模型正在吐、还没提交终态的那一段。
 *
 * 把 delta 直接拼进 `messages` 会犯三个错：① 消息还没有 id 就进了列表，
 * `LazyColumn` 的 key 只能退回位置，于是每来一个 token 整列表都可能重排；
 * ② 落盘 Flow 一旦推送同一个 messageId 的正式版本，两段文本会并存；
 * ③ 中途失败时无法区分"哪段是模型说的、哪段是我们自己拼的"。
 *
 * 分开之后：气泡优先渲染 `liveText`（当它是这一轮正在生成的那条），
 * 一旦库里出现同一 messageId 且状态不是 STREAMING，就把覆盖层清掉（见 [onMessages]）。
 *
 * ## 第三层：[stuck]
 *
 * `messages` 是「库里的事实」，但它可能是**陈旧的**事实 —— 写路径坏掉时那行会永远停在
 * `STREAMING`。所以还需要一层"已知不会收敛"的覆盖，见 [UnconvergedPlaceholder]。
 * 三层的优先级：`stuck`（界面接管） > `liveText`（正在生成） > `messages`（落盘事实）。
 */
public data class ChatUi(
    val sessionId: String? = null,
    val agentId: String? = null,
    val agentName: String = "",
    val messages: List<ChatMessageView> = emptyList(),
    val loadingHistory: Boolean = true,
    val input: String = "",
    val streaming: Boolean = false,
    /** 正在生成的那条 assistant 消息 id；delta 事件自带。 */
    val liveMessageId: String? = null,
    val liveText: String = "",
    /** 本轮内的技能卡片，`callId` → 卡片。 */
    val skills: Map<String, SkillCardUi> = emptyMap(),
    /** 本轮失败现场（贴在气泡里，不是页面横幅）。 */
    val failure: ChatTurnFailure? = null,
    /** 上一轮的终态，用于顶部一句话结论。 */
    val terminal: ChatTerminalReason? = null,
    /**
     * ★ 已知**不会收敛**的占位消息，`messageId` → 覆盖内容。
     *
     * 跨轮存活（`beginTurn` 不清它）：那条消息属于**上一轮**，新一轮开始不该让它的
     * "永远转圈"复活一次。只有库里真的出现非 `STREAMING` 版本时才移除（[onMessages]）。
     */
    val stuck: Map<String, UnconvergedPlaceholder> = emptyMap(),
    /** ★ `ChatRunnerPort` 没有 Hilt 绑定时的降级标志：只读历史，发送禁用。 */
    val runnerMissing: Boolean = false,
    /**
     * 这个 Agent 的扩展能力三态（见 [CapabilityPresence]）。
     *
     * 默认 [CapabilityPresence.UNKNOWN] 而不是 `NONE`：还没读到授权表时保持沉默，
     * 不能拿「还没读到」去冒充「没有」—— 那条提示一旦误报，用户会去重复配置一个
     * 本来就配好的能力，然后认为配置不生效。
     */
    val capabilityPresence: CapabilityPresence = CapabilityPresence.UNKNOWN,
    /** 落库 / 事件流的失败原因（`AppError.reasonKey` 或异常信息）。 */
    val errorText: String? = null,
) {
    /** 这一条是不是"正在生成中的那条"，供气泡决定是否画光标。 */
    public fun isLive(id: String): Boolean = streaming && id == liveMessageId

    /**
     * 某条技能消息对应的卡片。
     *
     * `SKILL_CALL` 与 `SKILL_RESULT` 是两条消息、共享一个 `callId`（§9-U5），
     * 所以配对必须走 `callId`，拿不到时退回消息 id（冷启动历史里可能没存 callId）。
     */
    public fun skillCardOf(message: ChatMessageView): SkillCardUi? = skills[message.callId ?: message.id]
}

/**
 * 聊天页 ViewModel。
 *
 * ## 流式渲染是怎么做到"边收边画"的
 *
 * `ChatRunnerPort.turn()` 返回 `Flow<ChatEvent>`。这里**不是**先 `toList()` 再渲染，
 * 而是 `collect` 到 ViewModel 的 `MutableStateFlow` 里逐事件归约：
 * 每个 `AssistantDelta` 追加一段，Compose 侧只重组那个气泡。
 *
 * 代价与取舍写在代码里：
 *  - 归约是 O(1) 的字符串拼接 + 一次 `copy`，端侧 decode 每秒几个 token，绰绰有余；
 *  - 界面侧的自动滚动按"每 200 字"节流，而不是每个 token 滚一次
 *    （后者会让 `scrollToItem` 每帧抢一次主线程，正是端侧最缺的资源）。
 *
 * ## 依赖说明（重要）
 *
 * 构造参数里 [runner] 是 `Optional`：`ChatRunnerPort` 的实现（`:chat` 的 `ChatLoop`）
 * 目前**没有 Hilt 绑定**，硬注入会让整个 `:app` 编译失败。详见 [com.ake.ui.runtime.ChatWiringModule]。
 * 缺它时本页降级为"只读历史 + 发送禁用 + 明说原因"，而不是假装能发然后静默失败。
 */
@HiltViewModel
public class ChatViewModel @Inject constructor(
    private val sessions: ChatSessionStorePort,
    private val profiles: AgentProfileStorePort,
    runner: Optional<ChatRunnerPort>,
    /** 授权表（可选）。只用来回答「这个 Agent 有没有能力」，见 [CapabilityPresence]。 */
    grants: Optional<CapabilityGrantPort>,
    /** 确认闸门。技能在聊天里触发的 CONFIRM 就走这个单例 —— 确认必须回到气泡。 */
    private val gate: UiHumanGate,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val runner: ChatRunnerPort? = runner.orElse(null)
    private val grants: CapabilityGrantPort? = grants.orElse(null)

    private val _ui = MutableStateFlow(ChatUi(runnerMissing = this.runner == null))
    public val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** 人机确认：真实挂起状态在 [UiHumanGate]（全进程唯一的「人」）。 */
    public val pendingConfirm: StateFlow<UiHumanGate.PendingGate?> = gate.pending

    private var messagesJob: Job? = null
    private var turnJob: Job? = null
    private var abortFlag: ChatAbortFlag? = null
    private var capabilityJob: Job? = null

    /**
     * ★ 发送防抖的**权威闸**（不是界面上的禁用态）。
     *
     * 从「点了发送」到「`streaming = true` 真的写进状态」之间有一段异步窗口：
     * 新会话要先 `ensureForNewTurn` 建 sessionId，那是两次挂起调用。
     * 手快的用户在这段窗口里点第二下，两次点击读到的 `streaming` 都是 `false`，
     * 于是同一句话被发成**两轮** ⇒ 后端按并发轮次拒掉后一次，返回
     * `POLICY_DENIED` + `retryable=false`，界面上就出现「安全策略拦下了这一步」。
     * 用户只是点了两下，却被告知策略拒绝 —— 这是**UI 造出来的状态**，
     * 就该在 UI 掐掉，而不是给它写一句像样的文案。
     *
     * 所以 `send()` 在**第一行同步判定**并占位，界面按钮的禁用态只是它的视觉投影。
     */
    private var turnInFlight: Boolean = false

    /**
     * 当前这一轮的序号。
     *
     * 用来解决「旧一轮的 `finally` 打到新一轮头上」：`stop()` 之后立刻再发一句时，
     * 被取消的那个协程的 `finally` 是**稍后**才跑的 —— 它会晚于新的一轮开始，
     * 于是把新轮刚设上的 `streaming = true` 抹回 `false`，顺手把防抖闸也放开。
     * 表现是「发送按钮在生成中出现，点一下又发了一轮」。
     * 只让序号最新的那一轮有权收敛状态，就消除这个窗口。
     */
    private var turnToken: Long = 0L

    /** 上一轮的用户原文与 turnId —— 重试要复用 turnId（§9-U4），不能新开一轮。 */
    private var lastUserText: String? = null
    private var currentTurnId: String? = null

    public fun nowMillis(): Long = clock.nowMillis()

    init {
        val sid = savedStateHandle.get<String>(ARG_SESSION_ID)
        val aid = savedStateHandle.get<String>(ARG_AGENT_ID)
        _ui.update { it.copy(sessionId = sid, agentId = aid) }

        if (sid != null) {
            bindMessages(sid)
            viewModelScope.launch { resolveAgentOfSession(sid) }
        } else if (aid != null) {
            loadAgent(aid)
            // 新会话：消息流等 ensureForNewTurn 建出 sessionId 之后再绑
            _ui.update { it.copy(loadingHistory = false) }
        } else {
            _ui.update { it.copy(loadingHistory = false, errorText = "err_chat_no_session") }
        }
    }

    // ──────────────────────────────────────────────── 输入与发送

    public fun onInputChange(v: String): Unit = _ui.update { it.copy(input = v) }

    public fun send() {
        val text = _ui.value.input.trim()
        if (text.isEmpty()) return
        // ★ 防抖在**这里**，不在按钮上：按钮的 `enabled` 只是一个视觉投影，
        //   真正的闸必须是与状态写入同一个同步段里的这一行（见 [turnInFlight]）。
        if (!beginTurn()) return
        _ui.update { it.copy(input = "") }
        lastUserText = text
        startTurn(text, turnId = UUID.randomUUID().toString())
    }

    /**
     * 重试。
     *
     * 复用 [ChatTurnRequest.turnId]：契约里写明"重试时 turnId 应当复用"，
     * 而 `clientRequestId` 每次新建（它是幂等键，防的是同一次发送被重发两遍）。
     * 两者混用会导致重试被当成新的一轮，界面上出现两段半截回答。
     */
    public fun retry() {
        val text = lastUserText ?: return
        if (!beginTurn()) return
        val turnId = currentTurnId ?: UUID.randomUUID().toString()
        startTurn(text, turnId = turnId)
    }

    /** 停止本轮。`AbortSignal` 是被轮询的，不会立刻断流，所以先本地收敛 UI 状态。 */
    public fun stop() {
        abortFlag?.abort()
        turnJob?.cancel()
        turnJob = null
        endTurn()
    }

    /**
     * 占位并同步把界面切到「正在生成」。
     *
     * @return `false` = 已经有一轮在跑（或正在起跑），本次点击必须被丢掉。
     */
    private fun beginTurn(): Boolean {
        if (turnInFlight || _ui.value.streaming) return false
        turnInFlight = true
        _ui.update {
            it.copy(
                streaming = true,
                liveText = "",
                liveMessageId = null,
                failure = null,
                terminal = null,
                errorText = null,
            )
        }
        return true
    }

    /** 释放闸门。**必须**与 [beginTurn] 配对，否则发送按钮会永久卡住。 */
    private fun endTurn() {
        turnInFlight = false
        _ui.update { it.copy(streaming = false) }
    }

    // ──────────────────────────────────────────────── 确认闸门

    public fun allowOnce(): Unit = gate.allowOnce()

    public fun rejectOnce(): Unit = gate.reject()

    // ──────────────────────────────────────────────── 内部

    private fun startTurn(text: String, turnId: String) {
        val r = runner
        if (r == null) {
            _ui.update { it.copy(runnerMissing = true) }
            endTurn()
            return
        }
        val agentId = _ui.value.agentId
        if (agentId == null && _ui.value.sessionId == null) {
            _ui.update { it.copy(errorText = "err_chat_no_agent") }
            endTurn()
            return
        }

        turnJob?.cancel()
        val token = ++turnToken
        turnJob = viewModelScope.launch {
            try {
                val sid = _ui.value.sessionId ?: ensureSession(agentId!!, text) ?: return@launch
                if (_ui.value.sessionId != sid) {
                    _ui.update { it.copy(sessionId = sid) }
                    bindMessages(sid)
                }

                currentTurnId = turnId
                val abort = ChatAbortFlag().also { abortFlag = it }

                val request = ChatTurnRequest(
                    sessionId = sid,
                    clientRequestId = UUID.randomUUID().toString(),
                    turnId = turnId,
                    userText = text,
                    abort = abort,
                )

                // ★ 逐事件归约，不阻塞、不攒批：每个 delta 立刻进 StateFlow
                r.turn(request).collect { event -> reduce(event) }
            } catch (c: CancellationException) {
                // 用户点了「停止」不是错误。吞掉它而不落到下面的 Throwable 分支，
                // 否则停止之后界面上会多出一行英文异常（`readableError` 的兜底是类名）。
                throw c
            } catch (t: Throwable) {
                _ui.update { it.copy(errorText = readableError(t)) }
            } finally {
                // 只有最新一轮有权收敛状态（见 [turnToken]）
                if (token == turnToken) {
                    endTurn()
                    abortFlag = null
                }
            }
        }
    }

    /**
     * 事件 → 状态。
     *
     * 每个分支都只碰**它该碰的那一块**：`AssistantDelta` 只动文本，
     * `SkillProgress` 只动那张卡片。整块 `copy` 会让旁注、卡片、滚动位置
     * 在每一个 token 上一起重组，端侧跑不动。
     */
    private fun reduce(event: ChatEvent) {
        when (event) {
            is ChatEvent.AssistantDelta -> _ui.update {
                it.copy(liveMessageId = event.messageId, liveText = it.liveText + event.delta)
            }

            is ChatEvent.SkillStarted -> _ui.update { s ->
                s.copy(
                    skills = s.skills + (
                        event.callId to SkillCardUi(
                            callId = event.callId,
                            name = event.labelRedacted?.takeIf { l -> l.isNotBlank() } ?: event.skillRef.id,
                            ref = event.skillRef,
                            taskId = event.derivedTaskId,
                            status = MessageStatus.STREAMING,
                        )
                        ),
                )
            }

            is ChatEvent.SkillProgress -> _ui.update { s ->
                val card = s.skills[event.callId] ?: return@update s
                s.copy(
                    skills = s.skills + (
                        event.callId to card.copy(
                            stepsDone = event.stepsDone ?: card.stepsDone,
                            maxSteps = event.maxSteps ?: card.maxSteps,
                            detail = event.detailRedacted ?: card.detail,
                        )
                        ),
                )
            }

            is ChatEvent.SkillResult -> _ui.update { s ->
                val card = s.skills[event.callId] ?: return@update s
                val blocked = event.escalation == ESCALATION_BLOCKED
                s.copy(
                    skills = s.skills + (
                        event.callId to card.copy(
                            result = event.summaryRedacted,
                            escalation = event.escalation,
                            taskId = event.taskId ?: card.taskId,
                            status = if (blocked) MessageStatus.FAILED else MessageStatus.COMPLETE,
                        )
                        ),
                )
            }

            // 确认卡片由 UiHumanGate.pending 驱动（那里才有 deferred，能真正放行/拒绝）。
            // 事件只用来保证"卡片先出现在气泡里"，所以这里不需要额外状态。
            is ChatEvent.ConfirmRequested -> Unit

            // 模式切换与压缩都已经落库成 MODE_NOTE / COMPACT_BOUNDARY 消息，界面不重复画。
            is ChatEvent.ModeSwitched -> Unit
            is ChatEvent.Compacted -> Unit
            is ChatEvent.RouterPlanned -> Unit
            is ChatEvent.FocusChanged -> Unit

            is ChatEvent.Terminal -> {
                val failure = ChatTurnFailure.of(currentTurnId, event.reason)
                _ui.update { s ->
                    val stuck = unconvergedPlaceholderOf(s, event, failure)
                    s.copy(
                        streaming = false,
                        failure = failure,
                        terminal = event.reason,
                        stuck = if (stuck == null) s.stuck else s.stuck + (stuck.messageId to stuck),
                    )
                }
            }
        }
    }

    /**
     * 这一轮的失败现场要不要**接管**那条占位行的渲染（见 [UnconvergedPlaceholder]）。
     *
     * ## 为什么只认 `STORE_FAILED`
     *
     * 挑的不是"严重程度"，而是「这个终态是否**由写库失败本身定义**」：
     *
     *  - `STORE_FAILED` 的成因就是 `commitAssistant` 返回 `Err` ⇒ 那一行**一定**还停在
     *    `STREAMING`，可以无条件接管，不存在误伤；
     *  - 其余终态（`SKILL_FAILED` / `MODEL_FAILED` …）都是**推理侧**的结论，那一行通常
     *    已经被正常收敛（`SKILL_FAILED` 甚至可能是 `COMPLETE` —— 技能挂了但正文是好的）。
     *    对这些终态强行覆盖，会把一段**好的回答**画成红色失败气泡：用"修转圈"换来了"说谎"。
     *
     * `:chat` 里 `commitAssistant` 失败时如果不是 `COMPLETED` 就保留原终态（`ChatLoop` 第 746 行），
     * 所以「`MODEL_FAILED` 且提交也失败」那种叠加态本文件仍看不到，那条气泡会继续转圈到冷启。
     * 要修它需要 `:chat` 告诉我们"提交也失败了"这个独立事实 —— 那是契约变更，不在 `:ui` 权限内，
     * 已写进本轮汇报。宁可留下一个已知的转圈，也不要在 `:ui` 里猜。
     */
    private fun unconvergedPlaceholderOf(
        s: ChatUi,
        event: ChatEvent.Terminal,
        failure: ChatTurnFailure?,
    ): UnconvergedPlaceholder? {
        val id = event.messageId ?: return null
        if (failure == null) return null
        if (event.reason != ChatTerminalReason.STORE_FAILED) return null
        // ★ 文本必须在这里冻住：下一轮 `beginTurn` 会把 `liveText` 清空，
        //   而库里那行是空的 —— 不留这一份，用户已经读过的回答就没了。
        return UnconvergedPlaceholder(messageId = id, failure = failure, text = s.liveText)
    }

    private suspend fun ensureSession(agentId: String, hint: String): String? =
        when (val r = sessions.ensureForNewTurn(agentId, hint.take(TITLE_HINT_LEN))) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> {
                _ui.update { it.copy(errorText = readableError(r.error)) }
                null
            }
        }

    /**
     * 订阅落库消息。
     *
     * 顺便处理两件退场：
     *  - **覆盖层退场**：当同 messageId 的消息以非 STREAMING 状态出现在库里时，
     *    说明终态已提交 —— 此时必须清掉 `liveText`，否则同一段话会被渲染两遍。
     *  - **失败接管退场**：`stuck` 里的 id 一旦在库里收敛（冷启 sweep 把卡住的
     *    `STREAMING` 收敛成 `FAILED`，或某次补写真的成功了），界面就该把话语权
     *    还给数据库。这也让 `stuck` 不会随会话时长无限长。
     */
    private fun bindMessages(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            sessions.messages(sessionId, MESSAGE_LIMIT)
                .catch { t -> _ui.update { it.copy(loadingHistory = false, errorText = readableError(t)) } }
                .collect { list -> onMessages(list) }
        }
    }

    private fun onMessages(list: List<ChatMessageView>) {
        _ui.update { s ->
            val settled = s.liveMessageId != null &&
                list.any { it.id == s.liveMessageId && it.status != MessageStatus.STREAMING }
            val converged = s.stuck.keys.filter { id ->
                list.any { it.id == id && it.status != MessageStatus.STREAMING }
            }.toSet()
            s.copy(
                messages = list,
                loadingHistory = false,
                liveMessageId = if (settled) null else s.liveMessageId,
                liveText = if (settled) "" else s.liveText,
                stuck = if (converged.isEmpty()) s.stuck else s.stuck - converged,
            )
        }
    }

    private fun loadAgent(agentId: String) {
        viewModelScope.launch {
            when (val r = profiles.byAgentId(agentId)) {
                is Outcome.Ok -> {
                    _ui.update { it.copy(agentId = agentId, agentName = r.value.name) }
                    // ★ 用 `profile.id`（ULID 主键）而不是路由上的 `agentId`（对外短名）：
                    //   `agent_capability_grant.agent_id` 的外键指向 `agent_profile(id)`，
                    //   传短名查出来永远是空 map ⇒ 会把每个 Agent 都误判成「零能力」。
                    watchCapabilities(r.value.id)
                }
                // 人格被删了但会话还在：不让页面空着，给一个可读的兜底名
                is Outcome.Err -> _ui.update { it.copy(agentName = "") }
            }
        }
    }

    /**
     * 订阅这个 Agent 的授权表，只为了回答一个问题：**它有没有能力**。
     *
     * ## 为什么要有这条订阅，而不是在创建时判定一次
     *
     * 「零能力」不是创建那一刻的快照，而是**当前事实**：撤回一次授权、或换一份人格，
     * 都会让它从「能操作浏览器」变回「只会聊天」。提示条必须跟着走，
     * 否则用户撤回授权后还会被告知一切正常，然后看着它什么都不做。
     *
     * 读不到（Port 不在 Hilt 图里 / Flow 抛错）⇒ 停在 [CapabilityPresence.UNKNOWN]，
     * 界面保持沉默，宁可不说也不说错（理由见 `CapabilityPresence` 的类注释）。
     */
    private fun watchCapabilities(profileId: String) {
        val g = grants ?: return
        capabilityJob?.cancel()
        capabilityJob = viewModelScope.launch {
            g.grantsOf(profileId)
                .catch { _ui.update { s -> s.copy(capabilityPresence = CapabilityPresence.UNKNOWN) } }
                .collect { map ->
                    val presence = capabilityPresenceOf(map)
                    _ui.update { s -> s.copy(capabilityPresence = presence) }
                }
        }
    }

    /** 从会话摘要反查 agentId：从列表页点进来时路由上只有 sessionId。 */
    private suspend fun resolveAgentOfSession(sessionId: String) {
        val summary = runCatching {
            sessions.sessions(agentId = null).first().firstOrNull { it.id == sessionId }
        }.getOrNull() ?: return
        loadAgent(summary.agentId)
    }

    /**
     * 本地的中止开关。
     *
     * 它实现的是 `core:ports` 的 [AbortSignal] 契约（`isAborted()` 轮询语义），
     * **不是**某个缺失 Port 的替代实现 —— 契约本身没有 Hilt 绑定概念，
     * 它就是一个由调用方构造的值对象。
     */
    private class ChatAbortFlag : AbortSignal {
        @Volatile
        private var aborted = false

        override fun isAborted(): Boolean = aborted

        fun abort() {
            aborted = true
        }
    }

    public companion object {
        /** 一次读进内存的消息条数上限。更长靠滚动加载，本轮不做（MVP）。 */
        public const val MESSAGE_LIMIT: Int = 200

        /** 会话标题提示取原文前若干字 —— 只是提示，标题生成不在 MVP（§9.2）。 */
        private const val TITLE_HINT_LEN: Int = 24

        private const val ESCALATION_BLOCKED: String = "BLOCKED"

        public const val ARG_SESSION_ID: String = "sessionId"
        public const val ARG_AGENT_ID: String = "agentId"
    }
}
