package com.ake.app

import android.content.Context
import android.util.Log
import com.ake.core.common.AkeLog
import com.ake.core.common.LogLevel
import com.ake.core.common.LogSink

/**
 * `core:common` 的日志门面 → Logcat 的转接器。
 *
 * **为什么日志门面要在 core 里**：L2/L4 的单测要能在 JVM 上毫秒级跑，
 * 一旦它们依赖 `android.util.Log`，整层就绕不开 Robolectric。
 * 于是 core 里只有接口，Android 侧在 Application 里 install 这个实现。
 *
 * 级别映射是刻意的：`VERBOSE` 落到 `Log.v`，`ERROR` 落到 `Log.e`。
 *
 * ⚠ `core:common` 的 `LogLevel` **只有 5 级**（VERBOSE/DEBUG/INFO/WARN/ERROR），没有 `ASSERT`。
 * 曾在这里写过 `LogLevel.ASSERT -> Log.e(...)` 而编译不过 —— 不要凭 Android 的
 * `Log.ASSERT` 记忆往 `when` 里加分支，这个 `when` 是**穷举**的（不是 else 兜底），
 * core 侧加新级别时这里必须同步，否则直接编译失败。
 * 另外：不要用 `Log.wtf`，它在部分 ROM 上会触发额外的上报行为，不划算。
 */
internal object AndroidLogSink : LogSink {

    override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, cause)
            LogLevel.DEBUG -> Log.d(tag, message, cause)
            LogLevel.INFO -> Log.i(tag, message, cause)
            LogLevel.WARN -> Log.w(tag, message, cause)
            LogLevel.ERROR -> Log.e(tag, message, cause)
        }
    }
}
