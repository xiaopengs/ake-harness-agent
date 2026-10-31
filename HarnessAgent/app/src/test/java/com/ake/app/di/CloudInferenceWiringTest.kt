package com.ake.app.di

import com.ake.core.common.Outcome
import com.ake.core.ports.AuditOutcome
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DefaultTargetStore
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetStore
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.EgressPhase
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.PromptContext
import com.ake.core.ports.RouteContext
import com.ake.core.ports.RoutingDecision
import com.ake.core.modelapi.ModelLoadConfig
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelRuntimeState
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.SessionSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * # ★★ 组合根**真的**把 `defaults` 接到 `ModelRouter` 上（行为断言）
 *
 * ## 为什么不扫源码字符串
 *
 * `ColdStartWiringTest` 的上一版就是扫源码：把 `coldStart.get()` 整行注释掉，
 * 4 条测试**全绿**（注释里的字符串还在）。这里的被测物是「`:app` 传进去的那个
 * `DefaultTargetStore` 是不是被路由器真的用了」—— 只有跑一遍才知道。
 *
 * ## 它能杀死的三种「看起来接了、其实没接」
 *
 * | 写法 | 本测试 |
 * |---|---|
 * | `defaults = InMemoryDefaultTargetStore()`（内存版，**冷启动即丢**） | 红 |
 * | `defaults = object : DefaultTargetStore { … }`（就地造假实现） | 红 |
 * | 参数加了、构造处没传给 `DefaultModelRouter`（今天编译不过，给个默认参数就过） | 红 |
 *
 * 三种情况下 `dialogFor()` 都拿不到我们塞进去的 `Remote` ⇒ 断言 `isSameInstanceAs(remote)` 失败。
 *
 * ## ⚠ 它**不**证明什么
 *
 * 它不断言「注入的实例是不是 Room 版」—— 那由 Dagger **编译期全图校验** +
 * 生成图里的 `DefaultTargetStoreImpl` 落点保证（见交付说明）。
 * 本测试断的是「组合根这条线**通不通**」，两者加起来才是完整证据链。
 */
class CloudInferenceWiringTest {

    @Test
    fun provideModelRouterPassesTheInjectedDefaultStoreThrough() = runBlocking {
        val defaults = RecordingDefaultTargetStore(DialogTarget.Remote(CredentialRef("svc-a")))
        val remote = MarkerDialogPort()
        val local = MarkerDialogPort()

        val router = CloudInferenceModule.provideModelRouter(
            device = NoopDevice(),
            localDialog = local,
            remote = remote,
            targets = EmptySessionTargets(),
            defaults = defaults,
            credentials = CredsThatExist(),
            permits = NoopPermits(),
        )

        // ★ 一个**从未登记过**的新会话：只能靠 defaults 兜底
        val port = router.dialogFor("sess-never-configured")

        // ① 注入进来的那个 store 确实被**读了一次**（没被绕过、没被顶替）
        assertThat(defaults.reads).isEqualTo(1)
        // ② 兜底值真的穿过路由层 ⇒ 拿到的是云端入口，不是本地
        assertThat(port).isInstanceOf(Outcome.Ok::class.java)
        assertThat((port as Outcome.Ok).value).isSameInstanceAs(remote)
    }

    /**
     * ★ 反向对照：把默认设成**端侧**时，同一个组合根必须给出本地入口。
     *
     * 没有这条，上面那条可能只是「恰好永远返回 remote」。
     */
    @Test
    fun sameCompositionRootYieldsTheLocalPortWhenTheDefaultIsDevice() = runBlocking {
        val defaults = RecordingDefaultTargetStore(DialogTarget.Device)
        val remote = MarkerDialogPort()
        val local = MarkerDialogPort()

        val router = CloudInferenceModule.provideModelRouter(
            device = NoopDevice(),
            localDialog = local,
            remote = remote,
            targets = EmptySessionTargets(),
            defaults = defaults,
            credentials = CredsThatExist(),
            permits = NoopPermits(),
        )

        val port = router.dialogFor("sess-never-configured")

        assertThat(defaults.reads).isEqualTo(1)
        assertThat((port as Outcome.Ok).value).isSameInstanceAs(local)
    }

    // ══════════════════════════════ 替身（全部只做记账，不含策略） ══════════════════════

    /** 记账用：记 `default()` 被读了几次。 */
    private class RecordingDefaultTargetStore(
        private val value: DialogTarget,
    ) : DefaultTargetStore {
        var reads: Int = 0
            private set

        override suspend fun default(): DialogTarget {
            reads++
            return value
        }

        override suspend fun setDefault(target: DialogTarget): Outcome<Unit> = Outcome.Ok(Unit)
    }

    /**
     * `DialogModelPort` / `DeviceModelPort` **都**继承 `ModelPort`（`com.ake.core.modelapi`），
     * 于是各有 5 个抽象成员。
     *
     * ★ 全部 `error(...)`：本测试**永不**真的推理，谁被调用了就是接线错了。
     */
    private abstract class InertModelPort : ModelPort {
        override suspend fun load(config: ModelLoadConfig): Outcome<Unit> = error("unused")

        override suspend fun unload(): Outcome<Unit> = error("unused")

        override val runtimeState: Flow<ModelRuntimeState> get() = error("unused")

        override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = error("unused")

        override fun estimateTokens(text: String): Int = error("unused")
    }

    private class MarkerDialogPort : InertModelPort(), DialogModelPort {
        override suspend fun openDialog(
            spec: SessionSpec,
            permit: EgressPermit?,
            context: PromptContext,
        ): Outcome<ModelSession> = error("本测试只走到 dialogFor()，不开会话")
    }

    private class NoopDevice : InertModelPort(), DeviceModelPort

    private class EmptySessionTargets : DialogTargetStore {
        override suspend fun of(sessionId: String): DialogTarget? = null
        override suspend fun select(sessionId: String, target: DialogTarget): Outcome<Unit> =
            Outcome.Ok(Unit)
    }

    private class CredsThatExist : CloudCredentialPort {
        override suspend fun upsert(
            ref: CredentialRef,
            cfg: EndpointConfig,
            secret: PlainSecret,
        ): Outcome<Unit> = error("unused")

        override fun refs(): Flow<List<CredentialRef>> = flowOf(emptyList())

        override suspend fun configOf(ref: CredentialRef): Outcome<EndpointConfig> = error("unused")

        override suspend fun secretOf(ref: CredentialRef): Outcome<PlainSecret> = error("unused")

        override suspend fun delete(ref: CredentialRef): Outcome<Unit> = error("unused")

        /** ★ 唯一会被走到的一条：凭证在 ⇒ 云端路径可通过。 */
        override suspend fun exists(ref: CredentialRef): Boolean = true
    }

    private class NoopPermits : EgressPermitSink {
        override suspend fun open(
            ctx: RouteContext,
            decision: RoutingDecision,
        ): Outcome<EgressPermit> = error("unused")

        override suspend fun close(
            permit: EgressPermit,
            outcome: AuditOutcome,
            phase: EgressPhase,
            deliveredBytes: Int,
            finishReasonRaw: String?,
        ): Outcome<Unit> = error("unused")

        override suspend fun sweepPending(olderThanMs: Long): Int = 0
    }
}
