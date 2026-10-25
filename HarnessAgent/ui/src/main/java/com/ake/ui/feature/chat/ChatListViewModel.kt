package com.ake.ui.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.AgentProfileSummary
import com.ake.core.ports.ChatSessionStorePort
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 会话列表的一行。 */
public data class ChatListItem(
    val sessionId: String,
    val agentId: String,
    /** Agent 名称；查不到时为空串，由界面用「未命名 Agent」兜底。 */
    val agentName: String,
    val title: String,
    val preview: String,
    val lastMessageAt: Long,
    val unreadCount: Int,
)

public data class ChatListUi(
    val items: List<ChatListItem> = emptyList(),
    /** 新建对话时用来挑 Agent。 */
    val agents: List<AgentProfileSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * 对话列表（首页）的 ViewModel。
 *
 * ## 为什么是两条 Flow 的 `combine`，而不是在列表里 join
 *
 * `ChatSessionSummary` 只有 `agentId`（对外短名），要显示"和谁在聊"就得拿人格表。
 * 两种做法：① 给每个会话各查一次人格（N+1，端侧 Room 上就是 N 次跨线程往返）；
 * ② 订阅人格表整体，本地按 `agentId` 建索引。
 *
 * 选 ②：人格数量是"几十个"这个量级（§7.1 `agent_profile` ≤ 百），
 * 而会话是"几百条"。把小的那张表整体拿进来做字典，永远比逐条去查大的那张便宜。
 *
 * ## 排序放在这一层
 *
 * Store 只保证"有数据"，不保证顺序（`sessions()` 的契约里没有 ORDER BY 的承诺）。
 * 首页的第一条必须真的是最近一条 —— 这是产品形态的硬要求，所以在这里显式排序，
 * 不去赌实现细节。
 */
@HiltViewModel
public class ChatListViewModel @Inject constructor(
    private val sessions: ChatSessionStorePort,
    private val profiles: AgentProfileStorePort,
    private val clock: Clock,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatListUi())
    public val ui: StateFlow<ChatListUi> = _ui.asStateFlow()

    /** 相对时间的基准。界面每渲染一次取一次，不做定时器（省电，且列表变化足够频繁）。 */
    public fun nowMillis(): Long = clock.nowMillis()

    init {
        viewModelScope.launch {
            combine(
                sessions.sessions(agentId = null),
                profiles.profiles(),
            ) { sessionList, agentList -> sessionList to agentList }
                .catch { t -> _ui.update { it.copy(loading = false, error = readableError(t)) } }
                .collect { (sessionList, agentList) ->
                    val byAgentId = agentList.associateBy { it.agentId }
                    val items = sessionList
                        .asSequence()
                        .sortedByDescending { it.lastMessageAt }
                        .map { s ->
                            ChatListItem(
                                sessionId = s.id,
                                agentId = s.agentId,
                                agentName = byAgentId[s.agentId]?.name.orEmpty(),
                                title = s.title,
                                preview = s.previewRedacted.orEmpty(),
                                lastMessageAt = s.lastMessageAt,
                                unreadCount = s.unreadCount,
                            )
                        }
                        .toList()
                    _ui.value = ChatListUi(
                        items = items,
                        agents = agentList.filterNot { it.archived },
                        loading = false,
                        error = null,
                    )
                }
        }
    }
}
