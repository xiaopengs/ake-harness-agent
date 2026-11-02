package com.ake.ui.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Clock
import com.ake.core.ports.MemoryEntry
import com.ake.core.ports.MemoryStorePort
import com.ake.ui.model.relativeTimeOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 记忆中心的一屏状态。 */
public data class MemoryListUi(
    val entries: List<MemoryEntry> = emptyList(),
    val query: String = "",
    val total: Int = 0,
    val loaded: Boolean = false,
)

/**
 * 记忆中心（五 tab 之一）。
 *
 * ## 搜索为什么在内存里做，而不是 SQL LIKE
 *
 * 本期列表上限 100 条（[LIST_LIMIT]），内存里 `contains` 一遍是微秒级；
 * 而 `content_enc` 是密文，SQL 侧只能搜 `content_preview` / `title` 两列明文 ——
 * 建一条 LIKE 查询除了多一个 DAO 方法之外，能搜到的东西**完全一样**。
 * 等 FTS5（`memory_fts`，v6 迁移）落地后，搜索再下沉到 SQL，那时才真的能搜正文。
 *
 * ## 删除为什么不做撤销
 *
 * 记忆卡的内容是密文，删了就是真删（`DELETE`，不是打标记）。做「撤销」意味着要把
 * 明文留在内存或临时表里 —— 与「删除即彻底」直接冲突。所以宁可多一次确认弹窗。
 */
@HiltViewModel
public class MemoryListViewModel @Inject constructor(
    private val memory: MemoryStorePort,
    private val clock: Clock,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    public val query: StateFlow<String> = _query.asStateFlow()

    private val entries: StateFlow<List<MemoryEntry>?> =
        memory.observeRecent(LIST_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    public val ui: StateFlow<MemoryListUi> = combine(entries, _query) { list, q ->
        if (list == null) {
            MemoryListUi(query = q, loaded = false)
        } else {
            val needle = q.trim()
            MemoryListUi(
                entries = if (needle.isEmpty()) {
                    list
                } else {
                    list.filter {
                        it.title.contains(needle, ignoreCase = true) ||
                            it.preview.contains(needle, ignoreCase = true)
                    }
                },
                query = q,
                total = list.size,
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryListUi())

    public fun onQueryChange(v: String) {
        _query.value = v
    }

    public fun delete(id: String) {
        viewModelScope.launch { memory.deleteById(id) }
    }

    public fun relativeTime(atMillis: Long): String = relativeTimeOf(clock.nowMillis(), atMillis)

    public companion object {
        /** 与 `MemoryStoreImpl.MAX_LIST_LIMIT` 对齐；超过 100 条用户也翻不动。 */
        public const val LIST_LIMIT: Int = 100
    }
}
