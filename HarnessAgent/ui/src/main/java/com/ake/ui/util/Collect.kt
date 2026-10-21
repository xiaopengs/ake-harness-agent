package com.ake.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * `StateFlow.collectAsStateWithLifecycle()`。
 *
 * ## 为什么这里有一份自己的实现
 *
 * 版本目录里目前只有 `lifecycle-runtime-ktx`，**没有** `lifecycle-runtime-compose`
 * （那个 artifact 才带官方的 `collectAsStateWithLifecycle`）。新增坐标要走邝随行的版本目录，
 * 而这条能力是 Compose 的硬性要求 —— 所以先用 `repeatOnLifecycle` 自己搭一层，
 * 等 `androidx.lifecycle:lifecycle-runtime-compose` 进目录后删掉本文件、改用官方实现即可。
 *
 * ## 为什么不能用 `collectAsState`
 *
 * 端侧 Agent 的事件流是**持续的**：退到后台后它还在分步推进、还在烧预算。
 * `collectAsState` 不管生命周期，会让后台界面继续重组 —— 白白吃掉本就紧张的 CPU，
 * 而 CPU 正是端侧推理的瓶颈。这里按 `STARTED` 收集，界面不可见即停。
 */
@Composable
public fun <T> StateFlow<T>.collectAsStateWithLifecycle(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    context: CoroutineContext = EmptyCoroutineContext,
): State<T> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val initial = value
    val flow: Flow<T> = this
    return androidx.compose.runtime.produceState(initial, this, lifecycleOwner, minActiveState, context) {
        lifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle 会在低于 minActiveState 时取消收集块，回到之上时重启
            lifecycleOwner.repeatOnLifecycle(minActiveState) {
                flow.collect { value = it }
            }
        }
    }
}

/**
 * 把一个「冷启动后需要订阅」的 Flow 以生命周期安全的方式收集。
 *
 * 与上面那个的区别：它没有初始值，用于「先订阅、值来了再渲染」的场景
 * （例如工具目录、模型资产状态）。
 */
@Composable
public fun <T> Flow<T>.collectAsStateWithLifecycleOrNull(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
): State<T?> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val flow: Flow<T> = this
    return androidx.compose.runtime.produceState(null as T?, this, lifecycleOwner, minActiveState) {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(minActiveState) {
                flow.collect { value = it }
            }
        }
    }
}

/** 非 Compose 侧的 StateFlow 默认初值便捷函数。 */
public fun <T> mutableStateFlowOf(initial: T): MutableStateFlow<T> = MutableStateFlow(initial)
