// 为什么这里有一份 `android.os.SystemClock`（测试专用影子）
// ===========================================================================
// `StabilityDetector.waitForPageSettled()` 的静默窗口判定用的是
// `android.os.SystemClock.elapsedRealtime()`。而 android.jar 在 JVM 单测里是桩：
//
//   java.lang.RuntimeException: Method elapsedRealtime in android.os.SystemClock not mocked.
//
// 于是这个函数在 JVM 上**一行都跑不了** —— 而它恰恰是「页面没加载完就抽取」这条
// 咽喉副线的唯一防线。不能测 = 只能靠文档担保。
//
// 做法与 `org/json/JvmJsonShim.kt` 同源：在 test 源集里提供同名类，
// 由 AGP 的 classpath 顺序（test classes 先于 mockable android.jar）接管。
//
// ★ 关键：被测字节码是对 stub 的 `public static long elapsedRealtime()` 做的
//   `invokestatic`，所以这里的 `@JvmStatic` 不是可选项 —— 只有它生成的**静态**方法
//   才是真正被 `invokestatic` 命中的那个；object 的实例方法不会被解析到。
//
// 时钟由测试驱动（默认停在 0）：被测代码读到的「墙钟」完全由用例推进，
// 于是 300ms 静默窗口、40 轮兜底、超时三条路径都能在毫秒级真实时间内跑完。
// ===========================================================================

package android.os

public object SystemClock {

    /** 测试可控的单调时钟起点（毫秒）。被测代码通过 [elapsedRealtime] 读它。 */
    @JvmStatic
    public var nowMillis: Long = 0L

    @JvmStatic
    public fun reset(): Unit { nowMillis = 0L }

    @JvmStatic
    public fun advance(deltaMs: Long): Long { nowMillis += deltaMs; return nowMillis }

    @JvmStatic
    public fun elapsedRealtime(): Long = nowMillis

    @JvmStatic
    public fun uptimeMillis(): Long = nowMillis

    @JvmStatic
    public fun elapsedRealtimeNanos(): Long = nowMillis * 1_000_000L
}
