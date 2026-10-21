package com.ake.core.common

/**
 * 时间源抽象。
 *
 * 生产依赖 `System.currentTimeMillis()`，但**任何跟预算/超时/截止有关的代码都不许直接调它** ——
 * 一旦业务逻辑里散落着 `System.currentTimeMillis()`，这些逻辑就无法在毫秒级的 JVM 单测里
 * 快进（架构 §1.3 R3 的全部意义就是这件事）。
 */
public fun interface Clock {
    public fun nowMillis(): Long
}

public object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** 单测用：手动推进的时间源。 */
public class ManualClock(private var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
    public fun advance(deltaMs: Long): Unit = run { now += deltaMs }
    public fun setTo(value: Long): Unit = run { now = value }
}
