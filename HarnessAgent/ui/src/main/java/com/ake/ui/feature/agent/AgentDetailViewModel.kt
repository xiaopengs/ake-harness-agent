package com.ake.ui.feature.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.ports.AgentProfile
import com.ake.core.ports.AgentProfileStorePort
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class AgentDetailUi(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val profile: AgentProfile? = null,
    val errorText: String? = null,
    val busy: Boolean = false,
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
 *  - 能力授权需要 `CapabilityGrantPort`，它全工程无实现 ⇒ 界面上明说，不给假开关。
 *
 * 等 `:data` 补上 `update(id, draft)` 之后，这里加一个编辑入口即可，其余不用动。
 */
@HiltViewModel
public class AgentDetailViewModel @Inject constructor(
    private val profiles: AgentProfileStorePort,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: String = savedStateHandle.get<String>(ARG_PROFILE_ID).orEmpty()

    private val _ui = MutableStateFlow(AgentDetailUi())
    public val ui: StateFlow<AgentDetailUi> = _ui.asStateFlow()

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
                is Outcome.Ok -> _ui.update { it.copy(loading = false, profile = r.value, notFound = false) }
                is Outcome.Err -> _ui.update {
                    it.copy(loading = false, notFound = true, errorText = readableError(r.error))
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
