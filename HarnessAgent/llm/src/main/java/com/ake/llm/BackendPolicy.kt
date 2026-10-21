package com.ake.llm

import com.ake.core.modelapi.InferenceBackend
import com.ake.core.ports.SocProfile

/**
 * Backend 选择与降级阶梯（架构 §6.3）。
 *
 * **为什么必须做降级而不是「一次选定」**：GPU delegate 有一类真实且阴险的失败 ——
 * `initialize()` **成功返回**，但第一条消息以 `Can not find OpenCL library on this device` 失败。
 * 根因是 Android 12+（API 31）限制加载未声明的厂商原生库，缺 `uses-native-library` 声明时就是这样。
 * 故障只在消息边界暴露 ⇒ 只 try-catch `initialize()` 没用，必须配 §6.3 的**预热探针**。
 *
 * 阶梯的收益是实打实的：GPU prefill 3,808 tok/s vs CPU 557 tok/s，**6.8 倍**；
 * 同一 8 步任务 GPU 36 秒 / CPU 87 秒。
 *
 * MVP 结论（§6.3）：**NPU 默认不启用** —— 它要求按 SoC 分发不同模型变体，分发矩阵会爆炸。
 * 只有在**显式指定**且探测到对应厂商时才把它放进阶梯。
 */
public object BackendPolicy {

    /**
     * 按优先级返回候选后端；`LiteRtModelPort.load()` 会逐个 try + 探针，取第一个真正跑得通的。
     *
     * @param preferred 请求的后端。CPU 或 `allowFallback=false` 时返回单项。
     * @param soc       设备能力画像
     */
    public fun ladder(
        preferred: InferenceBackend,
        soc: SocProfile,
        allowFallback: Boolean = true,
    ): List<InferenceBackend> {
        if (preferred == InferenceBackend.CPU || !allowFallback) {
            return listOf(InferenceBackend.CPU.takeIf { preferred == it } ?: preferred)
                .let { if (preferred == InferenceBackend.CPU) it else listOf(preferred) }
        }

        val out = LinkedHashSet<InferenceBackend>()

        // ① NPU 家族：只有探测到对应厂商才把它作为候选
        if (preferred == InferenceBackend.NPU || preferred == InferenceBackend.GOOGLE_TENSOR) {
            when {
                soc.npuBrand == GOOGLE_TENSOR -> out += InferenceBackend.GOOGLE_TENSOR
                soc.npuBrand != null && soc.npuBrand != UNKNOWN -> out += InferenceBackend.NPU
            }
        }

        // ② GPU：首选 GPU，或该机型看起来有可用的 OpenCL
        if (preferred == InferenceBackend.GPU || soc.isGpuOpenClLikely) {
            out += InferenceBackend.GPU
        }

        // ③ CPU 永远兜底 —— 它没有平台依赖，总能跑
        out += InferenceBackend.CPU

        val ordered = out.toMutableList()
        // 首选若命中且没被排到第一，提到最前（尊重调用方意图）
        if (ordered.size > 1 && ordered.first() != preferred && preferred in ordered) {
            ordered.remove(preferred)
            ordered.add(0, preferred)
        }
        return ordered
    }

    /**
     * 按可用内存挑后端。
     *
     * 低内存机型上刻意**不用 GPU**：GPU 显存与 WebView 抢的是同一块物理内存，
     * 而 WebView 恰恰是我们赖以执行动作的组件 —— 被 LMK 杀掉它比慢 2 倍糟糕得多（§11 R1）。
     */
    public fun preferForRam(availMb: Long): InferenceBackend =
        if (availMb >= GPU_RAM_THRESHOLD_MB) InferenceBackend.GPU else InferenceBackend.CPU

    private const val GOOGLE_TENSOR = "GOOGLE_TENSOR"
    private const val UNKNOWN = "UNKNOWN"
    private const val GPU_RAM_THRESHOLD_MB = 4_096L
}
