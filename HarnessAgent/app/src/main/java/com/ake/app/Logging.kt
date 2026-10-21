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
 * 级别映射是刻意的：`VERBOSE` 落到 `Log.v`，`ASSERT` 与 `ERROR` 同落到 `Log.e`
 * （Log 没有 assert 级别，用 `wtf` 会在某些 ROM 上触发额外行为，不划算）。
 */
internal object AndroidLogSink : LogSink {

    override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, cause)
            LogLevel.DEBUG -> Log.d(tag, message, cause)
            LogLevel.INFO -> Log.i(tag, message, cause)
            LogLevel.WARN -> Log.w(tag, message, cause)
            LogLevel.ERROR,
            LogLevel.ASSERT,
            -> Log.e(tag, message, cause)
        }
    }
}
