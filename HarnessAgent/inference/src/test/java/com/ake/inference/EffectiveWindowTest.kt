package com.ake.inference

import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.EndpointConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ★ 窗口预算的**边界**（架构 08 §2.8）。
 *
 * 这一组只测边界，不测「中间值」—— 中间值错了不影响任何决策，
 * 边界错了会让一个「用户声明 2048 的端点」收到 2047 token 的 prompt 而 400。
 */
class EffectiveWindowTest {

    private val device = DialogTarget.Device
    private val remote = DialogTarget.Remote(CredentialRef("svc-a"))

    // ═══ forPrompt：云端夹到 [2048, 131072] ═══

    @Test
    fun remoteExactlyAtMinIsKept() {
        assertThat(EffectiveWindow.forPrompt(remote, local = 4096, cloud = EndpointConfig.MIN_CONTEXT_WINDOW))
            .isEqualTo(EndpointConfig.MIN_CONTEXT_WINDOW)
    }

    @Test
    fun remoteOneBelowMinIsClampedUp() {
        assertThat(EffectiveWindow.forPrompt(remote, local = 4096, cloud = EndpointConfig.MIN_CONTEXT_WINDOW - 1))
            .isEqualTo(EndpointConfig.MIN_CONTEXT_WINDOW)
    }

    @Test
    fun remoteExactlyAtMaxIsKept() {
        assertThat(EffectiveWindow.forPrompt(remote, local = 4096, cloud = EndpointConfig.MAX_CONTEXT_WINDOW))
            .isEqualTo(EndpointConfig.MAX_CONTEXT_WINDOW)
    }

    @Test
    fun remoteOneAboveMaxIsClampedDown() {
        assertThat(EffectiveWindow.forPrompt(remote, local = 4096, cloud = EndpointConfig.MAX_CONTEXT_WINDOW + 1))
            .isEqualTo(EndpointConfig.MAX_CONTEXT_WINDOW)
    }

    /** ★★ 0 **不**表示「不限」——它表示「用户填错了」，必须夹到下界而不是 0 或 `Int.MAX_VALUE`。 */
    @Test
    fun zeroCloudWindowDoesNotMeanUnlimited() {
        assertThat(EffectiveWindow.forPrompt(remote, local = 4096, cloud = 0))
            .isEqualTo(EndpointConfig.MIN_CONTEXT_WINDOW)
        assertThat(EffectiveWindow.forPrompt(remote, local = 4096, cloud = -1))
            .isEqualTo(EndpointConfig.MIN_CONTEXT_WINDOW)
    }

    /**
     * ★★ 云端窗口**不按设备档位夹**。
     *
     * 用户的手机是 4096 档，但云端声明 131072 ⇒ prompt 预算按 131072 算：
     * 那段上下文**不占设备内存**。按设备档位去夹云端窗口，会让云端能力白白浪费。
     */
    @Test
    fun cloudWindowIgnoresTheDeviceMemoryTier() {
        assertThat(EffectiveWindow.forPrompt(remote, local = 4_096, cloud = 131_072))
            .isEqualTo(131_072)
    }

    /** 反向：端侧就**必须**按档位，云端的 `cloud` 参数在端侧路径上完全不参与。 */
    @Test
    fun deviceWindowIsTheLocalTierVerbatim() {
        for (tier in listOf(4_096, 8_192, 16_384)) {
            assertThat(EffectiveWindow.forPrompt(device, local = tier, cloud = 999_999))
                .isEqualTo(tier)
        }
    }

    // ═══ maxReplayTokens：云端再叠一道 HARD_CAP ═══

    @Test
    fun replayExactlyAtHardCapIsKept() {
        assertThat(EffectiveWindow.maxReplayTokens(remote, EffectiveWindow.HARD_CAP))
            .isEqualTo(EffectiveWindow.HARD_CAP)
    }

    @Test
    fun replayOneAboveHardCapIsCut() {
        assertThat(EffectiveWindow.maxReplayTokens(remote, EffectiveWindow.HARD_CAP + 1))
            .isEqualTo(EffectiveWindow.HARD_CAP)
    }

    /**
     * ★★ 云端重放有硬顶：用户设了 128K 的窗口，**不等于**同意「每轮把 12 万 token 的
     * 历史重发一遍」。硬顶是**独立于窗口**的第二道约束。
     */
    @Test
    fun replayNeverExceedsTheHardCapEvenWithAHugeDeclaredWindow() {
        assertThat(EffectiveWindow.maxReplayTokens(remote, 131_072))
            .isEqualTo(EndpointConfig.MAX_CONTEXT_WINDOW.coerceAtMost(EffectiveWindow.HARD_CAP))
        assertThat(EffectiveWindow.maxReplayTokens(remote, 131_072))
            .isLessThan(131_072)
    }

    @Test
    fun replayZeroIsClampedToMinNotToZero() {
        assertThat(EffectiveWindow.maxReplayTokens(remote, 0))
            .isEqualTo(EndpointConfig.MIN_CONTEXT_WINDOW)
    }

    /**
     * ★ 端侧**没有重放概念**（历史活在 KV cache 里）⇒ 不叠 `HARD_CAP`。
     *
     * 这不是漏写：把 `HARD_CAP` 也套到端侧，会让 32K 档机型每轮白白压缩一次历史。
     */
    @Test
    fun deviceReplayIsNotSubjectToTheHardCap() {
        assertThat(EffectiveWindow.maxReplayTokens(device, 32_768))
            .isEqualTo(32_768)
        assertThat(EffectiveWindow.maxReplayTokens(device, 32_768))
            .isGreaterThan(EffectiveWindow.HARD_CAP)
    }

    // ═══ 端口层：null 走默认值 ═══

    @Test
    fun nullDeclaredWindowFallsBackToTheDocumentedDefault() {
        assertThat(EndpointConfig.clampWindow(null))
            .isEqualTo(EndpointConfig.DEFAULT_CONTEXT_WINDOW)
        assertThat(EndpointConfig.DEFAULT_CONTEXT_WINDOW)
            .isAtLeast(EndpointConfig.MIN_CONTEXT_WINDOW)
        assertThat(EndpointConfig.DEFAULT_CONTEXT_WINDOW)
            .isAtMost(EndpointConfig.MAX_CONTEXT_WINDOW)
    }
}
