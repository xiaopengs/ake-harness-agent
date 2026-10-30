package com.ake.inference

import com.ake.core.ports.DialogTarget
import com.ake.core.ports.EndpointConfig

/**
 * ★ 端侧 token 预算与云端 context window 的统一表达（架构 08 §2.8）。
 *
 * ## 不统一成一个数字 —— 统一成一个「取哪个」的判定，且判定只有一份
 *
 * @param local 端侧档位上限（4096 / 8192 / 16384，来自 `DeviceHealthPort.memoryTier`）
 * @param cloud 用户声明的云端窗口（默认 8192，夹到 [2048, 131072]）
 */
public object EffectiveWindow {

    /**
     * @return **组装 prompt 时用的那个数**，不是「模型能力」。
     *
     * 判据：云端的 context **不占设备内存** ⇒ 不受端侧档位夹。
     */
    public fun forPrompt(target: DialogTarget, local: Int, cloud: Int): Int = when (target) {
        is DialogTarget.Device -> local
        // ★★ 云端**不**按设备档位夹 —— 但两端都不能越过声明窗口本身
        is DialogTarget.Remote -> EndpointConfig.clampWindow(cloud)

        /**
         * ★ 目标认不出来（docs/10 §6.2-D）⇒ 按**最保守**的那一侧算：端侧档位。
         *
         * 理由：预算是「这次最多喂多少」。给一个未知目标发**更大**的预算
         * 是往不安全方向猜；给更小的预算只是少喂一点，代价对称且可控。
         * ★ 注意：这只决定预算，**不**决定去哪 —— 去哪由 `DefaultModelRouter` 拒绝。
         */
        is DialogTarget.Unrecognized -> local
    }

    /**
     * ★ 每轮最大重放 token（**云端独有**，§2.8 ②）。
     *
     * 超过 ⇒ 由调用方调 `:agent` 的 `DeterministicCompactor` 压一轮再发。
     * 理由：不设顶的话，一个 60 轮的会话每轮都会把几十 KB 原文送出去 ——
     * 用户设置了「用云端」**不等于**同意「每轮把整段历史再发一遍」。
     */
    public fun maxReplayTokens(target: DialogTarget, declared: Int): Int = when (target) {
        // 端侧没有「重放」概念：历史活在 KV cache 里，整个档位都可用
        is DialogTarget.Device -> EndpointConfig.clampWindow(declared)
        // 云端：在声明窗口之上再加一道硬顶
        is DialogTarget.Remote -> EndpointConfig.clampWindow(declared)
            .coerceAtMost(EndpointConfig.MAX_CONTEXT_WINDOW)
            .coerceAtMost(HARD_CAP)

        /**
         * ★ 未知目标 ⇒ 按**端侧**算（与 [forPrompt] 同一条理由：往保守方向猜）。
         *
         * 「重放」是云端独有的概念；目标不明时不该假设存在一条出境通道，
         * 因此不加 `HARD_CAP`（没有重放 ⇒ 没有重放上限可言）。
         */
        is DialogTarget.Unrecognized -> EndpointConfig.clampWindow(declared)
    }

    /** §2.8 ②：硬顶 16384。 */
    public const val HARD_CAP: Int = 16_384
}
