package com.ake.ui.feature.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.ports.AgentProfile
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.CapabilityRef
import com.ake.ui.model.CapabilityPresence
import com.ake.ui.model.capabilityPresenceOf
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

public data class AgentDetailUi(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val profile: AgentProfile? = null,
    val errorText: String? = null,
    val busy: Boolean = false,
    /** 已授予的能力（只读展示，按 `ref` 排序后渲染，避免每次进来顺序抖动）。 */
    val capabilities: List<CapabilityRef> = emptyList(),
    /** 三态：读不到授权表时必须是 UNKNOWN，不能折成「没有」（见 [CapabilityPresence]）。 */
    val capabilityPresence: CapabilityPresence = CapabilityPresence.UNKNOWN,
)

/**
 * Agent 详情页的 ViewModel。
 *
 * ## ★ 这一页**不做编辑**，是一个刻意的缺口而不是遗漏
 *
 * 想改名字/人格时最自然的实现是「改完点保存」。但 `AgentProfileStorePort` 的写入口
 * 只有 `upsert(draft)`，而 `AgentProfileDraft` 里**没有 id** ——
 * `AgentProfileStoreImpl.upsert` 每次都 `Ulid.next()` 出一个新 id 和新的 `agentId`。
 *
 * 也就是说：按"编辑"去调用它，会在库里静默长出**第二个同名人格**，
 * 而旧的那个连同它的会话与记忆还留在原地。用户看到的是"我改了个名字，结果多出来一个 Agent"。
 *
 * 所以这里的选择是：
 *  - 展示与删除走 Port（都真实可用）；
 *  - 编辑不给入口，改成「以它为模板新建一份」（prefill 到创建页，语义诚实）；
 *  - **能力授权改为真实读取 `CapabilityGrantPort`**（`:data` 的 `CapabilityGrantStoreImpl`
 *    已绑定，早先那句「全工程无实现」的界面文案已经过期）；读到了就列出来，
 *    读到空就明说「它只会聊天」并指向「以它为模板新建」，
 *    读不到就说读不到 —— 三种情况必须分开，不能合并成一句「暂无」。
 *
 * 等 `:data` 补上 `update(id, draft)` 之后，这里加一个编辑入口即可，其余不用动。
 */
@HiltViewModel
public class AgentDetailViewModel @Inject constructor(
    private val profiles: AgentProfileStorePort,
    grants: Optional<CapabilityGrantPort>,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val grants: CapabilityGrantPort? = grants.orElse(null)

    private val profileId: String = savedStateHandle.get<String>(ARG_PROFILE_ID).orEmpty()

    private val _ui = MutableStateFlow(AgentDetailUi())
    public val ui: StateFlow<AgentDetailUi> = _ui.asStateFlow()

    private var grantsJob: Job? = null

    init {
        load()
    }

    /** 相对时间的基准。与 `HomeViewModel` / `ChatListViewModel` 一样由 UI 层只在显示时取。 */
    public fun nowMillis(): Long = clock.nowMillis()

    public fun load() {
        if (profileId.isBlank()) {
            _ui.update { it.copy(loading = false, notFound = true) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            when (val r = profiles.byId(profileId)) {
                is Outcome.Ok -> {
                    _ui.update { it.copy(loading = false, profile = r.value, notFound = false) }
                    watchCapabilities(profileId)
                }
                is Outcome.Err -> _ui.update {
                    it.copy(loading = false, notFound = true, errorText = readableError(r.error))
                }
            }
        }
    }

    /**
     * 订阅授权表。`profileId` 就是 `agent_profile.id`（主键），正是
     * `agent_capability_grant.agent_id` 外键指向的那一列 —— 这里不需要再做 id 归一。
     */
    private fun watchCapabilities(profileId: String) {
        val g = grants ?: run {
            _ui.update { it.copy(capabilityPresence = CapabilityPresence.UNKNOWN) }
            return
        }
        grantsJob?.cancel()
        grantsJob = viewModelScope.launch {
            g.grantsOf(profileId)
                .catch {
                    _ui.update { it.copy(capabilityPresence = CapabilityPresence.UNKNOWN) }
                }
                .collect { map ->
                    val granted = map.filterValues { it }.keys
                        .sortedWith(compareBy({ it.kind.name }, { it.id }))
                    _ui.update {
                        it.copy(
                            capabilities = granted,
                            capabilityPresence = capabilityPresenceOf(map),
                        )
                    }
                }
        }
    }

    public fun archive() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            runCatching { profiles.archive(profileId) }
            _ui.update { it.copy(busy = false) }
        }
    }

    /**
     * 彻底删除。
     *
     * 由调用方在对话框里确认过再进来 —— 这里的 `delete` 会连带删掉会话、消息、人格记忆。
     */
    public fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            when (val r = profiles.delete(profileId)) {
                is Outcome.Ok -> {
                    _ui.update { it.copy(busy = false) }
                    onDeleted()
                }
                is Outcome.Err -> _ui.update { it.copy(busy = false, errorText = readableError(r.error)) }
            }
        }
    }

    public companion object {
        public const val ARG_PROFILE_ID: String = "profileId"
    }
}
