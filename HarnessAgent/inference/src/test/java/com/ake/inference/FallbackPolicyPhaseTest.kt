package com.ake.inference

import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.EgressPhase
import com.ake.inference.FallbackPolicy.FallbackAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ★★ §4.2 / §4.4 的**唯一**判据：`FallbackPolicy` 的分相与回落决议。
 *
 * ## 为什么这组测试必须是「全变体 × 全字段」
 *
 * 这一份判定决定了「云端失败后能不能静默回落本地」。判错一格的后果不是崩溃，
 * 而是**用户以为没出境的会话其实已经把整段历史发到了第三方服务器**。
 * 它不会报错、不会留下任何用户可见痕迹 ⇒ **只有测试能发现**。
 *
 * ## 双向完备性
 *
 *  · [specTable] 是**手写的 §4.4 转写**（这是唯一允许手写的地方：它就是规格本身，
 *    与实现相互独立才有 oracle 价值）；
 *  · 遍历则走**反射枚举** `CloudFailure.permittedSubclasses`；
 *  · 于是「新增变体忘了表态」会红（反射有、表里没有），
 *    「表里写了不存在的变体」也会红（表里有、反射没有）。
 */
class FallbackPolicyPhaseTest {

    private companion object {

        /**
         * §4.4 精确语义表的逐行转写。
         *
         * 列：phase / 自动重试上限 / 期望动作 / §7.3 终态
         */
        val specTable: Map<CloudFailure, Row> = mapOf(
            // ── PRE：一个字节都没发出去 ──────────────────────────────────
            CloudFailure.NoPermit to Row(EgressPhase.PRE, 0, FallbackAction.FALLBACK_TO_DEVICE, AuditOutcome.NOT_SENT),
            CloudFailure.CredentialMissing to Row(EgressPhase.PRE, 0, FallbackAction.FALLBACK_TO_DEVICE, AuditOutcome.NOT_SENT),
            CloudFailure.ConnectFailed to Row(EgressPhase.PRE, 1, FallbackAction.RETRY_SAME_ENDPOINT, AuditOutcome.NOT_SENT),
            CloudFailure.AuthRejected to Row(EgressPhase.PRE, 0, FallbackAction.FALLBACK_TO_DEVICE, AuditOutcome.NOT_SENT),
            CloudFailure.ProxyAuthRequired to Row(EgressPhase.PRE, 0, FallbackAction.FALLBACK_TO_DEVICE, AuditOutcome.NOT_SENT),
            CloudFailure.InsecureEndpoint to Row(EgressPhase.PRE, 0, FallbackAction.FALLBACK_TO_DEVICE, AuditOutcome.NOT_SENT),
            CloudFailure.PageContextRefused to Row(EgressPhase.PRE, 0, FallbackAction.FALLBACK_TO_DEVICE, AuditOutcome.NOT_SENT),

            // ── POST：请求体已经发出去了 ⇒ 禁止回落 ──────────────────────
            CloudFailure.ModelNotFound to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.FAILED),
            CloudFailure.ContextTooLong to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.FAILED),
            CloudFailure.RateLimited to Row(EgressPhase.POST, 2, FallbackAction.RETRY_SAME_ENDPOINT, AuditOutcome.FAILED),
            CloudFailure.ServerError to Row(EgressPhase.POST, 1, FallbackAction.RETRY_SAME_ENDPOINT, AuditOutcome.FAILED),
            CloudFailure.FirstByteTimeout to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.FAILED),
            CloudFailure.StreamIdleTimeout to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.PARTIAL),
            CloudFailure.StreamTruncated to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.PARTIAL),
            CloudFailure.EmptyDelta to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.FAILED),
            CloudFailure.MalformedResponse to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.FAILED),
            CloudFailure.DeadlineExceeded to Row(EgressPhase.POST, 0, FallbackAction.FAIL_LOUD, AuditOutcome.PARTIAL),
        )

        /** 反射枚举全部子类型。★ 不用手写列表：新增变体会自动进来。 */
        fun allFailures(): List<CloudFailure> =
            CloudFailure::class.java.permittedSubclasses.mapNotNull { cls ->
                runCatching { cls.getDeclaredField("INSTANCE").get(null) }.getOrNull() as? CloudFailure
            }
    }

    private data class Row(
        val phase: EgressPhase,
        val maxRetries: Int,
        val action: FallbackAction,
        val audit: AuditOutcome,
    )

    // ═══ 0. 双向完备 ═══

    @Test
    fun specTableAndImplementationCoverTheSameVariants() {
        val reflected = allFailures().toSet()

        assertThat(reflected).isNotEmpty()
        // 实现里有、表里没有 ⇒ 「新增了失败形态却没人表态」
        assertThat(specTable.keys).containsExactlyElementsIn(reflected)
    }

    // ═══ 1. 逐行核对 §4.4 ═══

    @Test
    fun everyVariantMatchesTheSpecTable() {
        for (v in allFailures()) {
            val row = specTable.getValue(v)

            assertThat(v.phase).isEqualTo(row.phase)
            assertThat(FallbackPolicy.actionFor(v)).isEqualTo(row.action)
            assertThat(FallbackPolicy.maxRetries(v)).isEqualTo(row.maxRetries)
            assertThat(FallbackPolicy.mustSayAlreadySent(v)).isEqualTo(row.phase == EgressPhase.POST)
            assertThat(FallbackPolicy.auditOutcome(v)).isEqualTo(row.audit)
        }
    }

    // ═══ 2. 三条不依赖具体行的结构性不变式 ═══

    /**
     * ★★ **POST 阶段永远不允许回落本地。**
     *
     * 这条不写成「某个变体如何」，而写成**全称命题**：
     * 将来任何新增的 POST 变体都自动被它约束。
     */
    @Test
    fun noPostPhaseVariantEverFallsBackToDevice() {
        for (v in allFailures().filter { it.phase == EgressPhase.POST }) {
            assertThat(FallbackPolicy.actionFor(v)).isNotEqualTo(FallbackAction.FALLBACK_TO_DEVICE)
        }
    }

    /** 允许多少次重试，只由 `retryable` 决定（两者不许打架）。 */
    @Test
    fun retryBudgetIsConsistentWithTheRetryableFlag() {
        for (v in allFailures()) {
            val budget = FallbackPolicy.maxRetries(v)
            assertThat(budget > 0).isEqualTo(v.retryable)
        }
    }

    /** `RETRY_SAME_ENDPOINT` 只可能在 `retryable` 的变体上出现。 */
    @Test
    fun retryActionOnlyOnRetryableVariants() {
        for (v in allFailures()) {
            if (FallbackPolicy.actionFor(v) == FallbackAction.RETRY_SAME_ENDPOINT) {
                assertThat(v.retryable).isTrue()
                assertThat(FallbackPolicy.maxRetries(v)).isGreaterThan(0)
            }
        }
    }

    // ═══ 3. §4.4 里最容易被判错的一格 ═══

    /**
     * ★★ **首字节超时是 POST，绝不允许回落本地。**
     *
     * 这是整份规格里最容易搞错的一行：直觉会说「还没收到响应 ⇒ 等于没连上 ⇒ 回落吧」。
     * 但实际上请求体早就完整发出去了 —— 那一刻用户的整段历史
     * 已经在对方的服务器上。此时再「静默回落本地」，用户看到的终态是
     * 「本地完成了」，而这是一句**无法被用户证伪的假话**。
     *
     * ★ 单独一条、带明确失败信息的用例：改坏它的人会立刻看到为什么不能改。
     */
    @Test
    fun firstByteTimeoutIsPostAndForbiddenToFallBack() {
        val f: CloudFailure = CloudFailure.FirstByteTimeout

        assertThat(f.phase).isEqualTo(EgressPhase.POST)
        assertThat(FallbackPolicy.maxRetries(f)).isEqualTo(0)
        assertThat(FallbackPolicy.actionFor(f)).isEqualTo(FallbackAction.FAIL_LOUD)
        assertThat(FallbackPolicy.mustSayAlreadySent(f)).isTrue()
    }

    /** 对照：**连接失败**才真的一个字节都没发 ⇒ 唯一「可重试且仍在 PRE」的行。 */
    @Test
    fun connectFailedIsTheOnlyPreRowWithRetryBudget() {
        val preWithRetry = allFailures()
            .filter { it.phase == EgressPhase.PRE && FallbackPolicy.maxRetries(it) > 0 }

        assertThat(preWithRetry).containsExactly(CloudFailure.ConnectFailed)
    }

    // ═══ 4. 负向：判定不可能是「静默」的 ═══

    /**
     * ★★ 全称命题：**只有 PRE 才可能存在回落决议。**
     *
     * ## ⚠ 这里我一开始写错了一条，值得记下来
     *
     * 初版我写的是「`FALLBACK_TO_DEVICE` ⇒ `mustSayAlreadySent == true`」，
     * 它**必然红**。原因不是产品有问题，而是我把两个概念混成了一个：
     *
     *  · `mustSayAlreadySent` 的字面义务是「告诉用户**已经发给 host**」——
     *    这句话在 PRE 阶段是**假的**，所以它只能是 `false`；
     *  · 而 §4.4 要求 PRE 回落「必须告知」的是**另一句话**：「已回落到本地」。
     *
     * ⇒ 「能回落」的唯一必要条件是「一个字节都没发出去」，即 `phase == PRE`。
     *   这一条才是可以写成全称命题的东西。
     *
     * ★ 顺带暴露一个真实缺口：PRE 回落那句「已回落到本地」目前**没有任何
     *   API 承载**（`FallbackPolicy` 只有 `mustSayAlreadySent`）。
     *   已在交付报告里列为主理人待裁项，**没有自作主张加成员**。
     */
    @Test
    fun fallbackIsOnlyPossibleWhenNothingHasLeftTheDevice() {
        val fallbacksFromPost = allFailures().filter { v ->
            FallbackPolicy.actionFor(v) == FallbackAction.FALLBACK_TO_DEVICE &&
                v.phase == EgressPhase.POST
        }
        assertThat(fallbacksFromPost).isEmpty()
    }

    /** 同理：`FAIL_LOUD` 必须告知（用户得知道发生了什么）。 */
    @Test
    fun failLoudAlwaysInformsTheUser() {
        val silentLoudFailures = allFailures().filter { v ->
            FallbackPolicy.actionFor(v) == FallbackAction.FAIL_LOUD &&
                !FallbackPolicy.mustSayAlreadySent(v)
        }
        assertThat(silentLoudFailures).isEmpty()
    }

    /**
     * ★ 反向 oracle：**没有任何变体**允许「POST + 回落」。
     *
     * 与 [noPostPhaseVariantEverFallsBackToDevice] 的区别：这一条从**规格表**取 phase
     * （手写，独立于实现），实现若偷偷把自己的 phase 改成 PRE，上一轮
     * [everyVariantMatchesTheSpecTable] 会先红，这两条互为交叉校验。
     */
    @Test
    fun specTableItselfHasNoPostFallbackRow() {
        for ((failure, row) in specTable) {
            if (row.phase == EgressPhase.POST) {
                assertThat(row.action).isNotEqualTo(FallbackAction.FALLBACK_TO_DEVICE)
            }
        }
    }
}
