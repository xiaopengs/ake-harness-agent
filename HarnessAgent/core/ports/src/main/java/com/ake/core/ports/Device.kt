package com.ake.core.ports

import kotlinx.coroutines.flow.Flow

/**
 * 设备健康 —— **不是可选项，是端侧 Agent 的硬约束之一**（架构 §2.2）。
 *
 * 一句话背景：云上的 Agent 可以假设「资源无限、进程永生」。端侧 Agent 三者都不成立。
 * 2.58 GB 模型 + ~300 MB WebView + ~150 MB App 同抢 8 GB，低内存回调一来，
 * 系统优先杀的往往是我们赖以执行动作的 WebView。
 * 所以「还剩多少内存 / 烫不烫 / 有没有电」必须是**一等公民输入**，而不是运维指标。
 */

/** 内存档位。`ContextBudget.forTier()` 直接消费它 —— 分三档是因为「上下文窗口该开多大」取决于此。 */
public enum class MemoryTier { LOW, MID, HIGH }

public enum class ThermalState {
    UNKNOWN,
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

public data class BatteryState(
    /** 0–100，未知为 -1 */
    val levelPct: Int,
    val isCharging: Boolean,
    val isPowerSave: Boolean,
    val isLow: Boolean,
) {
    public companion object {
        public fun unknown(): BatteryState = BatteryState(-1, false, false, false)
    }
}

/**
 * SoC 能力画像。
 *
 * 这里的结果直接决定是否尝试 GPU / NPU 路径。注意 NPU 走的是**特定 SoC 变体的模型文件**
 * （社区标注形如 `_Google_Tensor_G5` / `.mediatek.mt6993`），**通用版模型文件不能跑 NPU**。
 */
public data class SocProfile(
    val manufacturer: String,
    val socModel: String,
    val npuBrand: String?,
    val isGpuOpenClLikely: Boolean,
    val totalRamMb: Long,
    val isEmulator: Boolean,
) {
    public companion object {
        public fun unknown(): SocProfile = SocProfile("unknown", "unknown", null, false, 0L, false)
    }
}

public interface DeviceHealthPort {
    public fun memoryTier(): MemoryTier

    public fun thermal(): Flow<ThermalState>

    public fun battery(): Flow<BatteryState>

    public fun socProfile(): SocProfile
}
