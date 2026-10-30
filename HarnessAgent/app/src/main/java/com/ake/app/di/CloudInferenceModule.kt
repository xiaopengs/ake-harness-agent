package com.ake.app.di

import com.ake.cloudai.CloudRuntimeConfig
import com.ake.cloudai.OpenAiCompatibleDialogPort
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.ports.AnnouncementKind
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.DialogTargetStore
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.EgressAnnouncementSink
import com.ake.core.ports.EgressCoordinator
import com.ake.core.ports.EgressPermitSink
import com.ake.core.ports.FallbackAnnouncement
import com.ake.core.ports.Locality
import com.ake.core.ports.ModelRouter
import com.ake.inference.DefaultEgressCoordinator
import com.ake.inference.DefaultModelRouter
import com.ake.inference.DialogRoutingPort
import com.ake.inference.LocalDialogAdapter
import com.ake.inference.FallbackPolicy
import com.ake.ui.spi.TurnEgressNotice
import com.ake.ui.spi.TurnEgressNoticeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * # 端云推理的**唯一** Hilt 装配点（docs/08 §8.2 / docs/10 §6.3）
 *
 * ## ★★ 这就是 E6 删除后「由 `:app` 注入」的落地兑现
 *
 * ```
 *   :app（组合根）
 *     ├── LiteRtModelPort          ← :llm（唯一看得见 LiteRT 的地方）
 *     ├── LocalDialogAdapter(它)   ← 升格成 DialogModelPort（本地路径）
 *     ├── OpenAiCompatibleDialogPort(...)  ← :cloudai（云端路径）
 *     └── DefaultModelRouter(device, localDialog, remote, targets, creds, permits)
 * ```
 *
 * `:inference` 是**纯 JVM**，它只认 [DeviceModelPort] 接口，看不见 `:llm`
 * （实测：`:inference` 编译类路径上 `project :llm` 出现 **0 次**）。
 * 于是 `:inference` 保住了毫秒级 JVM 单测，而装配知识集中在组合根 ——
 * 这正是当初删 E6 想要的两件事。
 *
 * ## ★ 为什么「本地」与「远端」各是一个 `DialogModelPort`
 *
 * 两条路径在**调用点上形状完全一致**（`openDialog(spec, permit, context)`），
 * 于是 `:chat` 里不需要写 `if (target is Remote)` 这种运行时分支。
 * 差别只在这里装配一次。
 */
@Module
@InstallIn(SingletonComponent::class)
public object CloudInferenceModule {

    /** 本地路径：端侧引擎升格成对话入口。 */
    @Provides
    @Singleton
    @LocalDialog
    public fun provideLocalDialog(device: DeviceModelPort): DialogModelPort =
        LocalDialogAdapter(device)

    /**
     * 云端路径：OpenAI 兼容适配器。
     *
     * ★ 用 public 构造（`engine` 默认 `OkHttp.create()`）⇒ `:app` 不需要自己
     *   拼装 ktor 管线。ktor-okhttp 坐标**已存在**于版本目录（`:cloudai` 在用），
     *   本轮**零新增第三方坐标**。
     */
    @Provides
    @Singleton
    @RemoteDialog
    public fun provideRemoteDialog(
        credentials: CloudCredentialPort,
        clock: Clock,
    ): DialogModelPort = OpenAiCompatibleDialogPort(
        credentials = credentials,
        config = CloudRuntimeConfig.DEFAULT,
        clock = clock,
    )

    /**
     * ★★ `ModelRouter` —— 派发的唯一实现。
     *
     * 三个注入项分别来自：`:llm`（经 [DeviceModelPort]）、本模块两条路径、`:data`。
     */
    @Provides
    @Singleton
    public fun provideModelRouter(
        device: DeviceModelPort,
        @LocalDialog localDialog: DialogModelPort,
        @RemoteDialog remote: DialogModelPort,
        targets: DialogTargetStore,
        credentials: CloudCredentialPort,
        permits: EgressPermitSink,
    ): ModelRouter = DefaultModelRouter(
        device = device,
        localDialog = localDialog,
        remote = remote,
        targets = targets,
        creds = credentials,
        permits = permits,
    )

    /** 纯对话路由（页面任务恒本地）。`ChatServices` 用它替换 `LocalOnlyRoutingPort`。 */
    @Provides
    @Singleton
    public fun provideDialogRoutingPort(
        clock: Clock,
        credentials: CloudCredentialPort,
    ): DialogRoutingPort = DialogRoutingPort(clock, credentials)

    /** ★ 出境编排（docs/10 §6.2-C 缝隙的实现）。 */
    @Provides
    @Singleton
    public fun provideEgressCoordinator(
        permits: EgressPermitSink,
        announce: EgressAnnouncementSink,
    ): EgressCoordinator = DefaultEgressCoordinator(permits, announce)

    /**
     * ★★ P0：**必须绑定**的告知出口（docs/08 §4.4「PRE 回落必须告知」）。
     *
     * 缺绑 ⇒ `[Dagger/MissingBinding]` ⇒ 编不出包。这就是「承载不可绕过」的
     * 那一段：它把「忘了告知」从运行时静默错误变成**装配期错误**。
     *
     * ⚠ **边界（诚实说明）**：本实现目前只把告知**落日志 + 记住最后一条**，
     *   **还没有**接到气泡 UI —— 展示层属 `:ui`（苏映棠并行在写，本轮不得触碰）。
     *   接入点已经留好：`:ui` 只要注入 [EgressAnnouncementSink] 或读取
     *   `AnnouncementCenter.last` 即可渲染，无需再改 `:inference` / `:chat`。
     */
    @Provides
    @Singleton
    public fun provideEgressAnnouncementSink(): EgressAnnouncementSink =
        AnnouncementCenter.asSink()

    /**
     * ★ 2026-10-31 收口：`:ui` 的 [TurnEgressNoticeSource] **由同一个对象实现**。
     *
     * `:ui` 里 `CloudWiringModule` 是 `@BindsOptionalOf` —— 不提供也能编译，
     * 但那样徽标与回落告知永远是空的（静默降级的 UI 版本）。
     * 在这里显式提供，等于把「有没有人显示」从运行时运气变成装配期事实。
     */
    @Provides
    @Singleton
    public fun provideTurnEgressNoticeSource(): TurnEgressNoticeSource = AnnouncementCenter

    /** 本地 / 远端两条 `DialogModelPort` 的限定符 —— 防止 Dagger 分不清两个同类型绑定。 */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    public annotation class LocalDialog

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    public annotation class RemoteDialog
}

/**
 * 告知的**进程内落点**，同时是 `:ui` 的 [TurnEgressNoticeSource] 实现。
 *
 * ## ★ 为什么一个对象实现两个接口（2026-10-31 收口）
 *
 * 并行开发时 `:ui` 与 `:core:ports` 各自定义了一条「告知」通道：
 * `TurnEgressNoticeSource`（拉取式，UI 需要 `turnId` + `producedBy`）
 * 与 `EgressAnnouncementSink`（推入式，协调器只负责喊一声）。
 * 两条通道 = 两套真相源，这个项目已经因为「同一事实多份实现」出过
 * 一次真实的域名黑名单绕过 bug（host 归一化曾有 5 份不等价实现）。
 *
 * 收口方式不是删掉一个，而是让**同一个对象**同时实现两侧：
 *  · 生产侧只认 [EgressAnnouncementSink]（`:inference` 只能调 `announce`）；
 *  · 消费侧只认 [TurnEgressNoticeSource]（`:ui` 只读 `notices`）；
 *  · 中间只有这里一份 `MutableStateFlow` —— 单一真相源。
 *
 * ★ 语义映射（诚实性关键）：
 *  · `FELL_BACK_TO_LOCAL` ⇒ `producedBy = DEVICE`（本轮确实由本机产出）；
 *  · `ALREADY_SENT` ⇒ `producedBy = REMOTE`。请求体**确实**到了远端，
 *    哪怕没收到回答也必须显示云端 —— 否则就是用「失败了」掩盖「已出境」。
 */
public object AnnouncementCenter : TurnEgressNoticeSource {

    /** 保留最近 N 条。UI 只需要渲染当前会话的未读，无限增长没有意义。 */
    private const val MAX_KEPT: Int = 32

    private val all: MutableStateFlow<List<FallbackAnnouncement>> =
        MutableStateFlow(emptyList())

    public val last: FallbackAnnouncement? get() = all.value.lastOrNull()

    public fun asSink(): EgressAnnouncementSink = EgressAnnouncementSink { a ->
        all.value = (all.value + a).takeLast(MAX_KEPT)
        // ★ 日志里**只有** i18n key 与归一化 host，没有服务端原文（§6.3 ⑦）
        AkeLog.w(
            TAG,
            "announce: kind=${a.kind} reasonKey=${a.reasonKey} host=${a.host.orEmpty()} turn=${a.turnId.orEmpty()}",
        )
    }

    public const val TAG: String = "AnnouncementCenter"

    /** ★ 判据：凡是 `mustAnnounceFallback` 的失败形态，都必须有一条告知。 */
    public fun isSatisfiedFor(failure: com.ake.core.ports.CloudFailure): Boolean =
        last?.reasonKey == failure.reasonKey && FallbackPolicy.mustAnnounceFallback(failure)

    /**
     * ★ UI 的唯一读取口。
     *
     * 只下发 `turnId != null` 的条目：挂不到具体一轮的告知，UI 无法渲染成气泡，
     * 强行下发只会变成一条孤儿消息（那和不说没区别）。
     * `sessionId` 为空视为「广播」——诊断路径没有会话上下文，但事实仍应可见。
     */
    override fun notices(sessionId: String): Flow<List<TurnEgressNotice>> =
        all.map { list ->
            list.asSequence()
                .filter { it.turnId != null }
                .filter { it.sessionId == null || it.sessionId == sessionId }
                .map(::toNotice)
                .toList()
        }

    private fun toNotice(a: FallbackAnnouncement): TurnEgressNotice =
        TurnEgressNotice(
            turnId = requireNotNull(a.turnId),
            failure = a.failure,
            // ★ 完整 URL 永不进 UI 对象（§6.3 ③）；这里本来就只是归一化 host
            host = a.host.orEmpty(),
            producedBy = when (a.kind) {
                AnnouncementKind.FELL_BACK_TO_LOCAL -> Locality.DEVICE
                AnnouncementKind.ALREADY_SENT -> Locality.REMOTE
            },
        )
}
