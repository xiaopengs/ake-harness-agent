package com.ake.inference

import com.ake.core.ports.DataClass
import com.ake.core.ports.InferenceTarget
import com.ake.core.ports.PageRef
import com.ake.core.ports.RouteContext
import com.ake.core.ports.TaskKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * ★★ DOM 锁在 `:inference` 这一层的**唯一运行时判定**（架构 08 §5.7）。
 *
 * > 一旦 `ctx.kind is TaskKind.PageTask`（或带了 `PageRef`）⇒ **恒返回 `LocalOnly`**。
 *
 * ## 这一层能证明什么、不能证明什么
 *
 * 能证明：**只要**调用方走 `RoutingPort`，页面任务就**拿不到**出云许可。
 * 不能证明：有人绕过 `RoutingPort` 直接 `new OpenAiCompatibleDialogPort(...)`。
 *   ⇒ 那一条由 `:cloudai` 侧的
 *   `DomLockCloudTest.pageContextIsRefusedBeforeAnyByteLeaves` 兜住（`calls == 0`）。
 *
 * ★ 这里的判据是**全称命题**（遍历所有 kind × requested 组合），不是「我试了一个例子」。
 */
class DialogRoutingPortTest {

    private fun port(): DialogRoutingPort =
        DialogRoutingPort(TEST_CLOCK, FakeCredentials())

    private fun ctx(
        requested: InferenceTarget,
        kind: TaskKind = TaskKind.PureDialog,
        page: PageRef? = null,
    ): RouteContext = RouteContext(
        agentId = "agent-1",
        sessionId = "sess-1",
        turnId = "turn-1",
        requested = requested,
        userGrantedThisTurn = true,
        estimatedContextTokens = 1024,
        needsLongContext = false,
        payloadClasses = emptySet(),
        kind = kind,
        page = page,
    )

    private val aPage = PageRef(host = "shop.example.com", domChars = 8_192, elementCount = 240)

    // ═══ 1. 铁律 ═══

    @Test
    fun pageTaskIsAlwaysLocalEvenWhenTheUserAskedForCloud() = runTest {
        val d = port().decide(ctx(InferenceTarget.CloudRequested, TaskKind.PageTask, aPage))

        assertThat(d.target).isEqualTo(InferenceTarget.LocalOnly)
        assertThat(d.reasonKey).isEqualTo(DialogRoutingPort.REASON_PAGE_TASK_LOCAL_ONLY)
    }

    /**
     * ★ 防御纵深：`kind` 还是 `PureDialog`，但**带了 page 元数据** ⇒ 同样本地。
     *
     * 这一条守的是「造 ctx 的人忘了改 kind」这种最现实的手滑 ——
     * 只要 page 非空，就当页面任务处理。
     */
    @Test
    fun pageMetadataAloneIsEnoughToForceLocal() = runTest {
        val d = port().decide(ctx(InferenceTarget.CloudRequested, TaskKind.PureDialog, aPage))

        assertThat(d.target).isEqualTo(InferenceTarget.LocalOnly)
    }

    /**
     * ★★ 负向：`PageTask` 配 `page = null` 是**畸形** ctx（[RouteContext.isWellFormed] == false），
     * 但路由器仍然不许因此放行 —— 畸形不应导致「更宽的权限」。
     */
    @Test
    fun malformedPageTaskWithoutMetadataStillStaysLocal() = runTest {
        val malformed = ctx(InferenceTarget.CloudRequested, TaskKind.PageTask, page = null)
        assertThat(RouteContext.isWellFormed(malformed)).isFalse()

        assertThat(port().decide(malformed).target).isEqualTo(InferenceTarget.LocalOnly)
    }

    // ═══ 2. 纯对话：意图才起作用 ═══

    @Test
    fun pureDialogWithCloudIntentIsRoutedToCloudRequested() = runTest {
        val d = port().decide(ctx(InferenceTarget.CloudRequested))

        assertThat(d.target).isEqualTo(InferenceTarget.CloudRequested)
        assertThat(d.reasonKey).isEqualTo(DialogRoutingPort.REASON_CLOUD_ALLOWED)
    }

    @Test
    fun pureDialogWithLocalIntentStaysLocal() = runTest {
        val d = port().decide(ctx(InferenceTarget.LocalOnly))

        assertThat(d.target).isEqualTo(InferenceTarget.LocalOnly)
        assertThat(d.reasonKey).isEqualTo(DialogRoutingPort.REASON_LOCAL_ONLY)
    }

    // ═══ 3. 全称命题：任何组合都产不出 CloudAssisted ═══

    /**
     * ★★ `decide()` **永远**不能返回 `CloudAssisted`。
     *
     * 理由（§5.7 的循环依赖）：`CloudAssisted` 的构造需要一个 `EgressPermit`，
     * 而 permit 是 `EgressPermitSink.open()` 在**决策之后**才发的。
     * 决策阶段就能产出 permit ⇒ 审计行可以被跳过 ⇒ DOM 锁与审计同时失效。
     *
     * ★ 判据是全组合遍历，不是抽样。
     */
    @Test
    fun decideNeverProducesCloudAssistedForAnyCombination() = runTest {
        val kinds = listOf(TaskKind.PureDialog, TaskKind.PageTask)
        val pages = listOf(null, aPage)
        val requests = listOf<InferenceTarget>(InferenceTarget.LocalOnly, InferenceTarget.CloudRequested)

        for (kind in kinds) {
            for (page in pages) {
                for (req in requests) {
                    val d = port().decide(ctx(req, kind, page))
                    assertThat(d.target).isNotInstanceOf(InferenceTarget.CloudAssisted::class.java)
                }
            }
        }
    }

    /** ★ 未授权 ⇒ `allowedClasses` 恒空：不存在「默认允许某个数据类别出境」。 */
    @Test
    fun noDataClassIsPreAuthorised() = runTest {
        val d = port().decide(ctx(InferenceTarget.CloudRequested))

        assertThat(d.allowedClasses).isEmpty()
        assertThat(d.redactionApplied).isTrue()
    }

    /** 决策时间必须取时钟，不能是 `System.currentTimeMillis()` 的野生调用。 */
    @Test
    fun decisionCarriesTheInjectedClock() = runTest {
        val d = port().decide(ctx(InferenceTarget.CloudRequested))

        assertThat(d.decidedAt).isEqualTo(TEST_CLOCK.nowMillis())
    }

    /** 页面任务即使 `payloadClasses` 为空也必须本地（不是「没数据所以能上云」）。 */
    @Test
    fun pageTaskStaysLocalEvenWithEmptyPayloadClasses() = runTest {
        val empty = ctx(InferenceTarget.CloudRequested, TaskKind.PageTask, aPage).copy(
            payloadClasses = emptySet<DataClass>(),
            userGrantedThisTurn = false,
        )

        assertThat(port().decide(empty).target).isEqualTo(InferenceTarget.LocalOnly)
    }
}
