package com.ake.app.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.ake.core.common.AkeLog
import com.ake.core.ports.BatteryState
import com.ake.core.ports.DeviceHealthPort
import com.ake.core.ports.MemoryTier
import com.ake.core.ports.SocProfile
import com.ake.core.ports.ThermalState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `DeviceHealthPort` 的 Android 实现。
 *
 * 三条数据各有各的用途，都不是「运维指标」：
 *  - **内存档位** ⇒ 决定上下文工作窗口开多大（`ContextBudget.forTier`）。这是防 OOM 的主闸门。
 *  - **热状态** ⇒ SEVERE 时降频会让单步从 4.5 秒劣化到十几秒，必须暂停而不是硬扛。
 *  - **电量** ⇒ 低电量下跑 2.6 GB 模型的代价是用户可能会没电，交给用户决定。
 *
 * `thermal()` / `battery()` 用轮询实现：
 * Android 的 `PowerManager.getThermalHeadroom` 支持回调但需要 API 30+ 且各厂商差异大，
 * 轮询到 5 秒一次代价极低，比维护多套回调路径可靠。
 */
@Singleton
public class AndroidDeviceHealth @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : DeviceHealthPort {

    private val activityManager: ActivityManager by lazy {
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private val powerManager: PowerManager by lazy {
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    override fun memoryTier(): MemoryTier {
        val info = ActivityManager.MemoryInfo()
        return try {
            activityManager.getMemoryInfo(info)
            val totalMb = info.totalMem / (1024 * 1024)
            when {
                totalMb >= RAM_HIGH_MB -> MemoryTier.HIGH
                totalMb >= RAM_MID_MB -> MemoryTier.MID
                else -> MemoryTier.LOW
            }
        } catch (t: Throwable) {
            AkeLog.w(TAG, "getMemoryInfo failed, assume LOW", t)
            MemoryTier.LOW
        }
    }

    override fun thermal(): Flow<ThermalState> = channelFlow {
        while (true) {
            send(readThermal())
            delay(THERMAL_POLL_MS)
        }
    }

    override fun battery(): Flow<BatteryState> = channelFlow {
        while (true) {
            send(readBattery())
            delay(BATTERY_POLL_MS)
        }
    }

    override fun socProfile(): SocProfile {
        val totalMb = try {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.totalMem / (1024 * 1024)
        } catch (_: Throwable) {
            0L
        }
        val brand = detectNpuBrand()
        return SocProfile(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            socModel = Build.HARDWARE ?: "unknown",
            npuBrand = brand,
            // 模拟器上 OpenCL 常见为软件实现 / 不可用 ⇒ 直接走 CPU，避免探针白白失败一遍
            isGpuOpenClLikely = !isEmulator() && gpuLikelyUsable(),
            totalRamMb = totalMb,
            isEmulator = isEmulator(),
        )
    }

    /**
     * 模拟器判定。**必须是成员函数而不是 `socProfile()` 里的局部变量** ——
     * `detectNpuBrand()` 同样要用它（模拟器上报的 `SOC_MANUFACTURER` 经常是假值，
     * 会误判出 NPU ⇒ LiteRT 去初始化一个不存在的后端 ⇒ `initialize()` 直接失败）。
     */
    private fun isEmulator(): Boolean = (
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86")
        )

    private fun readThermal(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.UNKNOWN
        return try {
            val headroom = powerManager.getThermalHeadroom(THERMAL_WINDOW_SECONDS)
            when {
                headroom >= 1.0f -> ThermalState.CRITICAL
                headroom >= 0.85f -> ThermalState.SEVERE
                headroom >= 0.70f -> ThermalState.MODERATE
                headroom >= 0.60f -> ThermalState.LIGHT
                else -> ThermalState.NOMINAL
            }
        } catch (_: Throwable) {
            ThermalState.UNKNOWN
        }
    }

    private fun readBattery(): BatteryState = try {
        val ps = powerManager.isPowerSaveMode
        BatteryState(
            levelPct = -1,
            isCharging = powerManager.isInteractive,
            isPowerSave = ps,
            isLow = ps,
        )
    } catch (_: Throwable) {
        BatteryState.unknown()
    }

    // ────────────────────────────────────────────────────────────── 探测

    /**
     * NPU 厂商探测 —— **保守返回**。
     *
     * 这里刻意不去做激进的 `ro.*` 属性嗅探：厂商 ROM 的属性名不稳定，
     * 误判的代价是「用通用模型去跑 NPU」⇒ initialize 直接失败（§6.3）。
     * 探测不到就返回 null ⇒ 阶梯里根本不会出现 NPU ⇒ MVP 默认走 CPU/GPU，行为可预期。
     */
    private fun detectNpuBrand(): String? = when {
        isEmulator() -> null
        Build.HARDWARE?.contains("tensor", true) == true ||
            Build.SOC_MODEL?.contains("tensor", true) == true -> "GOOGLE_TENSOR"

        Build.SOC_MANUFACTURER?.contains("mediatek", true) == true ||
            Build.HARDWARE?.startsWith("mt", true) == true -> "MEDIATEK"

        Build.SOC_MANUFACTURER?.contains("qti", true) == true ||
            Build.SOC_MANUFACTURER?.contains("qualcomm", true) == true -> "QUALCOMM"

        else -> null // 探测不到就不启用 NPU（MVP 默认）
    }

    /**
     * 只在明显没有 GPU 能力的环境（如无硬件加速的容器）返回 false，其余给 GPU 一个机会。
     * 真正的裁决交给 `:llm` 的预热探针 —— 它能处理「initialize 成功但首条消息失败」。
     *
     * ⚠ 这里曾是 `try { true }`（**没有 catch/finally**）⇒ Kotlin 直接语法错误
     *   `Expecting 'catch' or 'finally'`。`try` 在 Kotlin 里是**表达式**且必须配 catch 或 finally，
     *   不像 Java 允许裸 try-with-nothing。MVP 里没有需要兜的异常，索性去掉 try。
     */
    private fun gpuLikelyUsable(): Boolean = true

    private companion object {
        const val TAG = "AndroidDeviceHealth"
        const val RAM_MID_MB = 6_144L
        const val RAM_HIGH_MB = 11_264L
        const val THERMAL_POLL_MS = 5_000L
        const val BATTERY_POLL_MS = 10_000L
        const val THERMAL_WINDOW_SECONDS = 10
    }
}
