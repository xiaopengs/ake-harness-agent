package com.ake.app.di

import com.ake.core.ports.ModelLifecyclePort
import com.ake.core.ports.UnloadReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 低内存时释放模型。
 *
 * ### 为什么释放模型是在「保护 WebView」
 *
 * 一个常驻 2.58 GB 模型的进程在中端机上会持续挤压 LMK。系统回收内存时优先杀的
 * 往往**不是我们的 Service，而是 WebView 进程** —— 而我们恰恰依赖 WebView 活着去执行动作。
 * 所以这不是省电优化，是让任务能跑完的前提（架构 §6.4）。
 *
 * 代价是重新 `initialize()` 的秒级冷启 —— 用 `cacheDir` 缓解第二次及以后的加载
 * （官方明示 `cacheDir` 可改善二次加载）。
 */
@Singleton
public class MemoryReleaser @Inject constructor(
    private val lifecycle: ModelLifecyclePort,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 同步语义不重要（释放可以异步），所以给一个可直接在系统回调里调用的非挂起入口。 */
    public fun releaseUnderMemoryPressure() {
        scope.launch {
            runCatching { lifecycle.unload(UnloadReason.LOW_MEMORY) }
        }
    }
}
