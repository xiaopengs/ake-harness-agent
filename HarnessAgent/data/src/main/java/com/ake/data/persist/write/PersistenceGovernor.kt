package com.ake.data.persist.write

import com.ake.core.common.AkeLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 写入让路控制器（数据库 §6.5）。
 *
 * ## 为什么需要它
 *
 * 进程里有两个推理相关的 IO 尖峰，持久化必须给它们让路：
 *  ① **模型下载**：2.58 GB 顺序写入；
 *  ② **模型加载**：2.58 GB 读入 + GPU 权重搬运，`initialize()` 可能耗时 10 秒。
 *
 * ## 取舍方向不能搞反
 *
 * **宁可丢掉几行轨迹，也不能让模型加载变慢或失败。**
 * 两者权重完全不对等 —— 前者损失的是诊断信息，后者损失的是用户能不能用这个 App。
 */
public class PersistenceGovernor {

    enum class Throttle { NONE, HALF, FULL }

    private val _throttle = MutableStateFlow(Throttle.NONE)
    val throttle: StateFlow<Throttle> = _throttle.asStateFlow()

    @Volatile
    private var maintenanceOpen: Boolean = true

    fun onModelDownloadStarted() {
        _throttle.value = Throttle.HALF
    }

    fun onModelLoadStarted() {
        _throttle.value = Throttle.FULL
        maintenanceOpen = false
    }

    fun onModelReady() {
        _throttle.value = Throttle.NONE
        maintenanceOpen = true
    }

    /** 清理作业（RetentionJob）的闸门。模型加载期间不许跑 —— 它会发 fsync，可能造成数百毫秒抖动。 */
    fun isMaintenanceAllowed(): Boolean = maintenanceOpen

    suspend fun yieldForIoBudget() {
        when (_throttle.value) {
            Throttle.NONE -> Unit
            Throttle.HALF -> delay(50)
            Throttle.FULL -> delay(200)
        }
    }
}
