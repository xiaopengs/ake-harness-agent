package com.ake.ui.model

import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.Locality
import com.ake.core.ports.ModelCapabilities
import com.ake.ui.spi.TurnEgressNotice
import com.ake.ui.testsupport.FakeCloudCredentials
import com.ake.ui.testsupport.FakeDialogPort
import com.ake.ui.testsupport.FakeDialogTargetStore
import com.ake.ui.testsupport.FakeModelRouter
import com.ake.ui.testsupport.runUiTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test

/**
 * `ModelBadgeSource` 的判据 —— 「不可见的路由承诺等于没有承诺」在界面上的那一半。
 *
 * ## ★ 三条负向性质，每条都要能杀死一个具体的实现
 *
 *  A. **运行时胜过意图**：目标写着 Remote、而路由器此刻给出的是设备侧端口
 *     ⇒ 徽标必须是 [ModelBadge.Device]。读 `DialogTarget` 直接画「云端」的实现会红 ——
 *     那正是 §4.2 禁止的那件事在 UI 上的样子：假的承诺。
 *
 *  B. **DOM 红线可见**：`PageContextRefused`（锁 C 的产物）带来的回落告知
 *     ⇒ 徽标必须是 Device，绝不 Remote。
 *
 *  C. **不知道就承认不知道**：没登记 / 认不出 / 路由报错 ⇒ [ModelBadge.Unregistered]
 *     或 [ModelBadge.Unavailable]，**绝不**是 Device。把未知降级成本机，
 *     等于界面替用户承诺了一次「没有出境」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelBadgeTest {

    private companion object {
        const val SESSION: String = "session-1"
        val REF: CredentialRef = CredentialRef("cloud:deepseek-chat@gw.example.com")

        // ★ 这条 URL 里带着 key —— 它绝不允许出现在徽标的任何一个字段上。
        const val LEAKY_URL: String = "https://gw.example.com/v1?key=sk-live-SECRET123456"
        const val SECRET: String = "sk-live-SECRET123456"
        const val MODEL: String = "deepseek-chat"

        fun creds(): FakeCloudCredentials = FakeCloudCredentials(
            mapOf(
                REF to (
                    EndpointConfig(
                        baseUrl = LEAKY_URL,
                        modelName = MODEL,
                        tlsMode = com.ake.core.ports.TlsMode.REQUIRED,
                        declaredContextWindow = null,
                        label = "测试网关",
                    ) to SECRET
                    )
            )
        )

        fun targetStore(target: DialogTarget? = DialogTarget.Remote(REF)): FakeDialogTargetStore =
            FakeDialogTargetStore(
                if (target == null) emptyMap() else mapOf(SESSION to target)
            )
    }

    // ══════════════════════════════════════════ ① 路由器认为的 degradation

    @Test
    fun runtimeLocalityBeatsTheRegisteredIntent() = runUiTest {
        // 用户勾选了云端（目标写着 Remote），但路由器此刻给出的是**设备侧**端口。
        // 这正是凭证失效 / 网页任务 / 网络不通时真实会发生的形状。
        val source = ModelBadgeSource(
            maybeRouter = Optional.of(
                FakeModelRouter(port = FakeDialogPort(ModelCapabilities.DEVICE_DEFAULT))
            ),
            targets = targetStore(DialogTarget.Remote(REF)),
            creds = creds(),
        )

        val badge = source.badgeFor(SESSION)

        assertWithMessage(
            "目标写着云端、运行时给出的是设备侧端口 ⇒ 徽标必须是本机。" +
                "照 `DialogTarget` 直接画「云端」的实现会在这里红 —— " +
                "那种界面会在一条本机产出的回答上贴出错的承诺。"
        ).that(badge).isInstanceOf(ModelBadge.Device::class.java)
    }

    @Test
    fun runtimeRemoteIsRenderedWithModelAndNormalisedHost() = runUiTest {
        val source = ModelBadgeSource(
            maybeRouter = Optional.of(
                FakeModelRouter(port = FakeDialogPort(ModelCapabilities.REMOTE_DEFAULT))
            ),
            targets = targetStore(),
            creds = creds(),
        )

        val badge = source.badgeFor(SESSION)

        assertWithMessage("运行时确实是云端 ⇒ 不许藏起来")
            .that(badge).isInstanceOf(ModelBadge.Remote::class.java)
        val remote = badge as ModelBadge.Remote
        assertThat(remote.modelName).isEqualTo(MODEL)
        // ★ 归一化 host —— 完整 URL（含 query 里的 key）绝不能到界面上来。
        assertThat(remote.host).isEqualTo("gw.example.com")
        assertThat(remote.toString()).doesNotContain(SECRET)
        assertThat(remote.toString()).doesNotContain("key=")
    }

    // ══════════════════════════════════════════ ② DOM 红线：涉及网页任务的会话

    /**
     * ★ 锁 C 在界面上的样子。
     *
     * `PageContextRefused` 是「本会话带页面上下文 ⇒ 云端硬失败」的产物（§5.4），
     * 它是一个 PRE ⇒ 允许且必须回落到本机。于是这一轮的实际产出者就是设备侧。
     */
    @Test
    fun aPageContextRefusedTurnShowsDeviceAndNeverRemote() = runUiTest {
        val source = ModelBadgeSource(
            maybeRouter = Optional.of(
                FakeModelRouter(port = FakeDialogPort(ModelCapabilities.REMOTE_DEFAULT))
            ),
            // 用户选的是云端 —— 选了也不行，这就是红线的意思。
            targets = targetStore(DialogTarget.Remote(REF)),
            creds = creds(),
        )

        val notice = TurnEgressNotice(
            turnId = "turn-1",
            failure = CloudFailure.PageContextRefused,
            host = "gw.example.com",
            producedBy = Locality.DEVICE,
        )

        val badge = source.badgeFor(SESSION, notice)

        assertWithMessage(
            "涉及网页任务的会话，界面上必须呈现端侧 —— " +
                "浏览器任务一旦出现云端徽标，说明有人把 DOM 送出去了或把承诺画错了。"
        ).that(badge).isNotInstanceOf(ModelBadge.Remote::class.java)
        assertThat(badge).isInstanceOf(ModelBadge.Device::class.java)
    }

    @Test
    fun aRemoteNoticeWithRemoteRouterStillShowsRemote() = runUiTest {
        // 反向对照：不是每一种回落都把徽标翻成本机。
        // POST 阶段禁止回落，producedBy 若是 REMOTE，徽标就不能谎报本机。
        val source = ModelBadgeSource(
            maybeRouter = Optional.of(
                FakeModelRouter(port = FakeDialogPort(ModelCapabilities.REMOTE_DEFAULT))
            ),
            targets = targetStore(),
            creds = creds(),
        )

        val badge = source.badgeFor(
            SESSION,
            TurnEgressNotice(
                turnId = "turn-1",
                failure = CloudFailure.ServerError,
                host = "gw.example.com",
                producedBy = Locality.REMOTE,
            ),
        )

        assertWithMessage("POST 阶段没有回落 ⇒ 徽标不许为了好看改口成本机")
            .that(badge).isInstanceOf(ModelBadge.Remote::class.java)
    }

    // ══════════════════════════════════════════ ③ 不知道 ⇒ 承认不知道

    @Test
    fun unregisteredSessionSaysUnregisteredNotDevice() = runUiTest {
        // ★ 路由器不在图里 ⇒ 只剩「登记的意图」这一层可看；那里也是空的。
        val source = ModelBadgeSource(
            maybeRouter = Optional.empty(),
            targets = targetStore(target = null),
            creds = creds(),
        )

        assertWithMessage(
            "没登记过目标 ⇒ [ModelBadge.Unregistered]。" +
                "降级成 Device 会让用户以为「没出境」是被保证过的事情。"
        ).that(source.badgeFor(SESSION)).isEqualTo(ModelBadge.Unregistered)
    }

    @Test
    fun anUnreadableTargetRowIsUnavailableNotDevice() = runUiTest {
        val source = ModelBadgeSource(
            maybeRouter = Optional.empty(),
            targets = targetStore(DialogTarget.Unrecognized("CLOUD:!!!")),
            creds = creds(),
        )

        val badge = source.badgeFor(SESSION)
        assertWithMessage("库里那行认不出来就是认不出来 —— `Dialog.kt` 已裁定不许猜成 Device")
            .that(badge).isInstanceOf(ModelBadge.Unavailable::class.java)
        assertWithMessage("★ 脏文本不许被渲染出来：界面没有理由替一行坏数据做扩音器")
            .that(badge.toString()).doesNotContain("!!!")
    }

    @Test
    fun aRouterErrorIsUnavailableNotDevice() = runUiTest {
        val source = ModelBadgeSource(
            maybeRouter = Optional.of(FakeModelRouter()), // 端口为 null ⇒ 返回 Err
            targets = targetStore(),
            creds = creds(),
        )

        assertWithMessage("路由读不出来就必须是 Unavailable：吞掉异常 fallback 成 DEVICE" + "等于把一次未知说成一次确定的「留在本机」")
            .that(source.badgeFor(SESSION)).isInstanceOf(ModelBadge.Unavailable::class.java)
    }

    @Test
    fun noRouterFallsBackToTheIntentAndSaysNothingInvented() = runUiTest {
        // `ModelRouter` 没绑定时（今天的接线状态）退到登记的意图 ——
        // 这是三级降级里最弱的一级，但它不许凭空多承诺。
        val source = ModelBadgeSource(
            maybeRouter = Optional.empty(),
            targets = targetStore(DialogTarget.Device),
            creds = creds(),
        )
        assertThat(source.badgeFor(SESSION)).isInstanceOf(ModelBadge.Device::class.java)

        val remote = ModelBadgeSource(
            maybeRouter = Optional.empty(),
            targets = targetStore(DialogTarget.Remote(REF)),
            creds = creds(),
        )
        val badge = remote.badgeFor(SESSION)
        assertThat(badge).isInstanceOf(ModelBadge.Remote::class.java)
        assertThat((badge as ModelBadge.Remote).host).isEqualTo("gw.example.com")
    }

    // ══════════════════════════════════════════ ④ §6.3：徽标装不下秘密

    @Test
    fun noBadgeVariantCanCarryASecretOrAFullUrl() {
        val variants: List<ModelBadge> = listOf(
            ModelBadge.Unregistered,
            ModelBadge.Device(ModelBadgeSource.DEVICE_LABEL),
            ModelBadge.Remote(MODEL, com.ake.core.common.Hosts.normalise(LEAKY_URL)),
            ModelBadge.Unavailable(ModelBadgeSource.NO_CREDENTIAL_CONFIG),
        )

        variants.forEach { badge ->
            val printed = badge.toString()
            assertWithMessage("$badge 里出现了 Key").that(printed).doesNotContain(SECRET)
            assertWithMessage("$badge 里出现了完整 URL").that(printed).doesNotContain("key=")
            assertWithMessage("$badge 里出现了完整 URL").that(printed).doesNotContain("?")
        }
    }
}
