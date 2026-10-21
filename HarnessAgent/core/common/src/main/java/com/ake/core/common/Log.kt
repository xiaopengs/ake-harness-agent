package com.ake.core.common

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 日志门面。
 *
 * **为什么它必须在 `core`（零 Android 依赖）里**：端侧 Agent 的所有一切都依赖日志做诊断，
 * 而 L2/L4 的单测要在 JVM 上毫秒级跑 —— 如果它们直接依赖 `android.util.Log`，
 * 这些模块就必须引入 Robolectric，单机编译/测试速度会掉一个数量级（架构 §1.3 R3）。
 *
 * 所以这里是个**门面 + 可插拔 sink**，`app` 模块 install 一个转发到 `Logcat` 的 sink。
 * 没 install 时是 no-op —— 库模块默认安静，这是刻意的。
 *
 * logcat 的成本容易被低估：`Log.d` 在热路径（每步 DOM 抽取 / 每次出现的 traces）里
 * 每秒几千次会明显吃掉 CPU，而这些操作是和推理抢同一个核心的。
 * 因此 `d`/`v` 提供 lambda 版，调用点可以直接写字符串拼接而不会付出代价。
 */
public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, cause: Throwable?)
}

/** JVM / 单元测试下看日志用。生产由 app 模块换成 Logcat sink。 */
public object StdoutLogSink : LogSink {
    override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
        println("${level.name.first()} $tag: $message")
        cause?.printStackTrace()
    }
}

public object AkeLog {

    private val sinks = CopyOnWriteArrayList<LogSink>()

    @Volatile
    public var minLevel: LogLevel = LogLevel.INFO

    public fun install(sink: LogSink): Unit = run { sinks.add(sink) }

    public fun uninstall(sink: LogSink): Unit = run { sinks.remove(sink) }

    public fun clear(): Unit = run { sinks.clear() }

    private fun emit(level: LogLevel, tag: String, message: String, cause: Throwable?) {
        if (level < minLevel) return
        for (s in sinks) {
            runCatching { s.log(level, tag, message, cause) }
        }
    }

    public fun v(tag: String, message: String, cause: Throwable? = null): Unit =
        emit(LogLevel.VERBOSE, tag, message, cause)

    public fun d(tag: String, message: String, cause: Throwable? = null): Unit =
        emit(LogLevel.DEBUG, tag, message, cause)

    public fun i(tag: String, message: String, cause: Throwable? = null): Unit =
        emit(LogLevel.INFO, tag, message, cause)

    public fun w(tag: String, message: String, cause: Throwable? = null): Unit =
        emit(LogLevel.WARN, tag, message, cause)

    public fun e(tag: String, message: String, cause: Throwable? = null): Unit =
        emit(LogLevel.ERROR, tag, message, cause)

    public inline fun d(tag: String, crossinline message: () -> String): Unit = d(tag, message())

    public inline fun v(tag: String, crossinline message: () -> String): Unit = v(tag, message())

    /** 兜住「调用点自己的日志语句炸了」这种荒谬但真实的情况。 */
    public inline fun <T> vv(tag: String, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        e(tag, "log-guarded block failed", t)
        throw t
    }
}

/**
 * 统一 tag 前缀，便于用 `adb logcat -s Ake:*` 只看本项目。
 *
 * 注意要求恒定宽度 —— 这样 `logcat` 的 tag 列不会参差不齐，肉眼追踪一条链路时省一半力气。
 */
public fun AkeTag(name: String): String = if (name.length >= 24) name.substring(0, 24) else name
