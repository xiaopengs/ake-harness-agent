package com.ake.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.ake.app.di.MemoryReleaser
import com.ake.core.common.AkeLog
import com.ake.core.common.StdoutLogSink
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/**
 * 组合根。
 *
 * 只做三件事：装日志 sink、注册内存回调、把「低内存 ⇒ 释放模型」这条链路接上。
 * 其余一律由 Hilt 模块装配（见 `di/`）。
 */
@HiltAndroidApp
public class AkeApp : Application() {

    /**
     * 用 Provider 而不是直接注入实例：
     * 避免 Application 构造期就把 :llm 整条依赖树拉起来（那会拖慢冷启动）。
     * 真正的释放只在收到低内存回调后才发生。
     */
    @Inject
    internal lateinit var memoryReleaser: Provider<MemoryReleaser>

    override fun onCreate() {
        super.onCreate()
        AkeLog.install(AndroidLogSink)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            AkeLog.w(TAG, "onTrimMemory(BACKGROUND): releasing model to protect WebView")
            memoryReleaser.get().releaseUnderMemoryPressure()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AkeLog.w(TAG, "onLowMemory: releasing model")
        memoryReleaser.get().releaseUnderMemoryPressure()
    }

    private companion object {
        const val TAG = "AkeApp"

        init {
            // 单元测试（无 Android）下也保留一个 sink，避免调用点静默无声
            if (!isAndroidRuntime()) AkeLog.install(StdoutLogSink)
        }

        private fun isAndroidRuntime(): Boolean = try {
            Class.forName("android.os.Build")
            true
        } catch (_: Throwable) {
            false
        }
    }
}
