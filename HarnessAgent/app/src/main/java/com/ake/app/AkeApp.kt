package com.ake.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.ake.app.di.MemoryReleaser
import com.ake.app.startup.ColdStartCoordinator
import com.ake.core.common.AkeLog
import com.ake.core.common.StdoutLogSink
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/**
 * 组合根。
 *
 * `onCreate` 只做三件**时序敏感**的事：装日志 sink、触发冷启收尾、注册内存回调。
 * 其余一律由 Hilt 模块装配（见 `di/`）。
 *
 * ★ 2026-10 修正：这里的原文写着「只做三件事：装日志 sink、注册内存回调、把低内存 ⇒
 *   释放模型接上」—— 那份清单漏了 `coldStart.get()`，也就是漏掉了当时本轮真正的修复点。
 *   注释比代码少一条，后人读注释会以为冷启接线不存在（或者以为它发生在别处）。
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

    /**
     * 冷启动收尾（模板 bootstrap + `STREAMING` 收敛）。
     *
     * ★ 同样用 `Provider`，理由同上：这里只要求**它被创建**，不需要它的任何成员。
     *   它的构造函数立刻返回（工作体在 `init` 里 `scope.launch` 出去，跑在 `:data`
     *   的单线程 writer 上），所以这一行不阻塞冷启。
     *
     * ★ 为什么必须有这一行：`TemplateBootstrap.bootstrap()` 与 `ChatLoop.onColdStart()`
     *   上一轮的**生产调用点为 0** —— 方法写得对、单测也过，但没人从启动路径上喊它们，
     *   于是模板市场永久空白、存量人格永久哑、`STREAMING` 永不收敛。
     *   单例的 `init` 自触发（与 `TaskStateWriter` 调 `TaskReconciler` 同款）保证了
     *   「只要装配就一定跑」。
     *
     * ★ 守护这条接线的测试是 `app/src/test/.../ColdStartWiringTest`，它断言的是
     *   **行为**（`onCreate` 真的解引用了这个 provider；coordinator 一被构造就把两条 hook
     *   跑起来），不是源码里有没有那行字 —— 上一版扫字符串的写法在被注释掉的代码面前
     *   永远全绿（关千锤 P1-2）。
     */
    @Inject
    internal lateinit var coldStart: Provider<ColdStartCoordinator>

    override fun onCreate() {
        super.onCreate()
        AkeLog.install(AndroidLogSink)
        // 尽早触发：Activity / Compose 都还没起来，模板库与脏状态收敛走在 UI 第一次读表之前。
        coldStart.get()
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
