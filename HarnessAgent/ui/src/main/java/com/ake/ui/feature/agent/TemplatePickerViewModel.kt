package com.ake.ui.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.ports.AgentTemplate
import com.ake.core.ports.TemplateStorePort
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

public data class TemplatePickerUi(
    val templates: List<AgentTemplate> = emptyList(),
    val loading: Boolean = true,
    val errorText: String? = null,
)

/**
 * 模板市场（本轮只有编译进包的 5 个内置模板）。
 *
 * ## 为什么直接用 `catalog()` 的 Flow，而不是 `byId` 逐个拉
 *
 * `TemplateStoreImpl.catalog()` 已经在内部把内置正文（`TemplateAssets`）合进来了，
 * 逐个 `byId` 会把同一张表查 5 次、还各自解一次密。
 *
 * ## 为什么这里不做"安装"
 *
 * 市场下发/云端下载本轮明确不做（`docs/05` §9.2）。这一页的按钮只有"用这个模板"，
 * 它跳到创建页并 prefill —— 真正的写入发生在用户点"创建"那一刻。
 * 这样"看模板"和"造出一个 Agent"之间始终隔着用户的一次确认，
 * 与 §6.5 ③「能力的授予永远不由 bundle 决定」是同一件事。
 */
@HiltViewModel
public class TemplatePickerViewModel @Inject constructor(
    private val templates: TemplateStorePort,
) : ViewModel() {

    private val _ui = MutableStateFlow(TemplatePickerUi())
    public val ui: StateFlow<TemplatePickerUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            templates.catalog()
                .catch { t -> _ui.update { it.copy(loading = false, errorText = readableError(t)) } }
                .collect { list ->
                    _ui.update {
                        it.copy(
                            templates = list.sortedBy { t -> t.revision },
                            loading = false,
                            errorText = null,
                        )
                    }
                }
        }
    }
}
