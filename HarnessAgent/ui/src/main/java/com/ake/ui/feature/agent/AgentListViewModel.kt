package com.ake.ui.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Outcome
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.AgentProfileSummary
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class AgentListUi(
    val items: List<AgentProfileSummary> = emptyList(),
    val loading: Boolean = true,
    val errorText: String? = null,
    /** 待二次确认的删除对象。**删除人格是连带删除**，不允许一键完成。 */
    val pendingDelete: AgentProfileSummary? = null,
    val busy: Boolean = false,
)

/**
 * Agent 列表的 ViewModel。
 *
 * ## 删除为什么要单独走一次确认
 *
 * `AgentProfileStorePort.delete()` 的契约是**逐表删干净**：授权 → 会话 → 消息 →
 * 人格记忆 → 人格。也就是说删掉一个 Agent，它记住的东西一并消失，
 * 而且没有回收站（§8.5 选项 A：溯源可以断，归属不能悬空）。
 * 这种操作放在一个「点一下图标就没了」的列表项上，是设计事故 —— 所以走对话框，
 * 且对话框正文必须明说"连同会话与记忆"。
 *
 * ## 为什么不在这里做「编辑」
 *
 * `AgentProfileStorePort` 只有 `upsert(draft)`，而 `AgentProfileDraft` **不带 id**，
 * 实现里每次都生成新 id / 新 agentId（见 `AgentProfileStoreImpl.upsert`）。
 * 也就是说 Port 层没有"更新已有 Agent"，硬做编辑会静默产生第二个同名人格。
 * 详情页据此做成只读 + 「以它为模板新建」，把这件事摆在明面上。
 */
@HiltViewModel
public class AgentListViewModel @Inject constructor(
    private val profiles: AgentProfileStorePort,
) : ViewModel() {

    private val _ui = MutableStateFlow(AgentListUi())
    public val ui: StateFlow<AgentListUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            profiles.profiles()
                .catch { t -> _ui.update { it.copy(loading = false, errorText = readableError(t)) } }
                // 最近用过的排前面：Agent 是"角色"，用户找的是"上次那个"
                .collect { list ->
                    _ui.update {
                        it.copy(
                            items = list.sortedByDescending { p -> p.lastUsedAt ?: 0L },
                            loading = false,
                            errorText = null,
                        )
                    }
                }
        }
    }

    public fun askDelete(item: AgentProfileSummary): Unit = _ui.update { it.copy(pendingDelete = item) }

    public fun dismissDelete(): Unit = _ui.update { it.copy(pendingDelete = null) }

    public fun confirmDelete() {
        val target = _ui.value.pendingDelete ?: return
        _ui.update { it.copy(busy = true, pendingDelete = null) }
        viewModelScope.launch {
            val result = profiles.delete(target.id)
            _ui.update { s ->
                when (result) {
                    is Outcome.Ok -> s.copy(busy = false)
                    is Outcome.Err -> s.copy(busy = false, errorText = readableError(result.error))
                }
            }
        }
    }

    /** 归档：不删数据，只是从列表里收起来（可在 `profiles(includeArchived=true)` 里找回）。 */
    public fun archive(item: AgentProfileSummary) {
        viewModelScope.launch { runCatching { profiles.archive(item.id) } }
    }
}
