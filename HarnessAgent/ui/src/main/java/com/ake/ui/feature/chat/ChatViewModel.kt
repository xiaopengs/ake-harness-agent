package com.ake.ui.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.ports.AbortSignal
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.ChatEvent
import com.ake.core.ports.ChatMessageView
import com.ake.core.ports.ChatRunnerPort
import com.ake.core.ports.ChatSessionStorePort
import com.ake.core.ports.ChatTerminalReason
import com.ake.core.ports.ChatTurnRequest
import com.ake.core.ports.MessageStatus
import com.ake.ui.model.ChatTurnFailure
import com.ake.ui.model.SkillCardUi
import com.ake.ui.runtime.UiHumanGate
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /** ★ `ChatRunnerPort` 没有 Hilt 绑定时的降级标志：只读历史，发送禁用。 */
    val runnerMissing: Boolean = false,
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
    /** 确认闸门。技能在聊天里触发的 CONFIRM 就走这个单例 —— 确认必须回到气泡。 */
    private val gate: UiHumanGate,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val runner: ChatRunnerPort? = runner.orElse(null)

    private val _ui = MutableStateFlow(ChatUi(runnerMissing = this.runner == null))
    public val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** 人机确认：真实挂起状态在 [UiHumanGate]（全进程唯一的「人」）。 */
    public val pendingConfirm: StateFlow<UiHumanGate.PendingGate?> = gate.pending

    private var messagesJob: Job? = null
    private var turnJob: Job? = null
    private var abortFlag: ChatAbortFlag? = null

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
        if (text.isEmpty() || _ui.value.streaming) return
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
        if (_ui.value.streaming) return
        val turnId = currentTurnId ?: UUID.randomUUID().toString()
        startTurn(text, turnId = turnId)
    }

    /** 停止本轮。`AbortSignal` 是被轮询的，不会立刻断流，所以先本地收敛 UI 状态。 */
    public fun stop() {
        abortFlag?.abort()
        turnJob?.cancel()
        turnJob = null
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
            return
        }
        val agentId = _ui.value.agentId
        if (agentId == null && _ui.value.sessionId == null) {
            _ui.update { it.copy(errorText = "err_chat_no_agent") }
            return
        }

        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            val sid = _ui.value.sessionId ?: ensureSession(agentId!!, text) ?: return@launch
            if (_ui.value.sessionId != sid) {
                _ui.update { it.copy(sessionId = sid) }
                bindMessages(sid)
            }

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
            currentTurnId = turnId
            val abort = ChatAbortFlag().also { abortFlag = it }

            val request = ChatTurnRequest(
                sessionId = sid,
                clientRequestId = UUID.randomUUID().toString(),
                turnId = turnId,
                userText = text,
                abort = abort,
            )

            try {
                // ★ 逐事件归约，不阻塞、不攒批：每个 delta 立刻进 StateFlow
                r.turn(request).collect { event -> reduce(event) }
            } catch (t: Throwable) {
                _ui.update { it.copy(errorText = readableError(t)) }
            } finally {
                _ui.update { it.copy(streaming = false) }
                abortFlag = null
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
                _ui.update {
                    it.copy(
                        streaming = false,
                        failure = failure,
                        terminal = event.reason,
                    )
                }
            }
        }
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
     * 顺便处理"覆盖层的退场"：当同 messageId 的消息以非 STREAMING 状态出现在库里时，
     * 说明终态已提交 —— 此时必须清掉 `liveText`，否则同一段话会被渲染两遍。
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
            s.copy(
                messages = list,
                loadingHistory = false,
                liveMessageId = if (settled) null else s.liveMessageId,
                liveText = if (settled) "" else s.liveText,
            )
        }
    }

    private fun loadAgent(agentId: String) {
        viewModelScope.launch {
            when (val r = profiles.byAgentId(agentId)) {
                is Outcome.Ok -> _ui.update {
                    it.copy(agentId = agentId, agentName = r.value.name)
                }
                // 人格被删了但会话还在：不让页面空着，给一个可读的兜底名
                is Outcome.Err -> _ui.update { it.copy(agentName = "") }
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
