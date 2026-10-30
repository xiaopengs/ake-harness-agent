package com.ake.ui.feature.settings

import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.Locality
import com.ake.ui.spi.CloudProbeResult
import com.ake.ui.testsupport.FakeChatSessionStore
import com.ake.ui.testsupport.FakeCloudCredentials
import com.ake.ui.testsupport.FakeDialogTargetStore
import com.ake.ui.testsupport.FakeSettingsStore
import com.ake.ui.testsupport.FixedBudgets
import com.ake.ui.testsupport.FixedDeviceHealth
import com.ake.ui.testsupport.NoopPrivacy
import com.ake.ui.testsupport.ScriptedCloudProbe
import com.ake.ui.testsupport.chatSummary
import com.ake.ui.testsupport.runUiTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

/**
 * 设置页云端一块的行为判据。
 *
 * ## ① 自检的四个状态必须互斥，且各自落定
 *
 * 「未测 / 测试中 / 可用 / 失败 / 未接入」五个取值，没有第六种「看起来没问题」。
 * 这里的每一条都断言一个**终态**，不存在中间态 ——
 * 中间态的判据会被写歪：今天是 `Probing` 明天也许就被读作了 `Available`。
 *
 * ## ② 「后端选择」只写还没登记过的会话
 *
 * §3.3：目标是会话级且**会话内不可变**。已经选过的会话一动不动 ——
 * 那些回答的承诺是当时那个后端给的，回头改写等于篡改历史。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCloudBackendTest {

    private companion object {
        const val URL: String = "https://GW.Example.COM./v1"
        const val HOST: String = "gw.example.com"
        const val MODEL: String = "deepseek-chat"
        const val KEY: String = "sk-not-a-real-key"

        /** ★ 大小写 + 尾点 —— 归一化必须把它收成与 `pastebin.com.` 同一条口径。 */
        const val MESSY_URL: String = "https://WWW.Example.com.:8443/v1?key=xxxx"
        const val MESSY_HOST: String = "example.com"
    }

    private fun vm(
        creds: FakeCloudCredentials = FakeCloudCredentials(),
        targets: FakeDialogTargetStore = FakeDialogTargetStore(),
        sessions: FakeChatSessionStore = FakeChatSessionStore(
            summaries = listOf(chatSummary("s1"), chatSummary("s2"), chatSummary("s3"))
        ),
        probe: ScriptedCloudProbe? = null,
    ): SettingsViewModel = SettingsViewModel(
        privacy = NoopPrivacy,
        settings = FakeSettingsStore(),
        health = FixedDeviceHealth(),
        budgets = FixedBudgets(),
        creds = creds,
        targets = targets,
        sessions = sessions,
        probe = Optional.ofNullable(probe),
    )

    private fun SettingsViewModel.fillRemote() {
        chooseBackend(Locality.REMOTE)
        onBaseUrlChange(URL)
        onModelNameChange(MODEL)
        onApiKeyChange(KEY)
    }

    // ══════════════════════════════════════════════ ① 自检状态机

    @Test
    fun probeStartsIdleAndStaysQuiet() = runUiTest {
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.ui.value.cloud.probe).isEqualTo(CloudProbeState.Idle)
    }

    @Test
    fun aWorkingEndpointLandsOnAvailableWithTheNormalisedHost() = runUiTest {
        val probe = ScriptedCloudProbe(result = CloudProbeResult.Ok)
        val vm = vm(probe = probe)
        advanceUntilIdle()
        vm.fillRemote()

        vm.testConnection()
        advanceUntilIdle()

        assertWithMessage("★ 可用也必须带上 host —— 「可用」两个字不说明连到了哪儿")
            .that(vm.ui.value.cloud.probe).isEqualTo(CloudProbeState.Available(HOST))
        // ★ 且只出现归一化 host，不是那条带 query 的原 URL。
        assertThat(vm.ui.value.cloud.probe.toString()).doesNotContain("?key")
    }

    @Test
    fun aFailureLandsOnFailedWithOnlyTheNormalisedHost() = runUiTest {
        val probe = ScriptedCloudProbe(
            result = CloudProbeResult.Failed(CloudFailure.ConnectFailed)
        )
        val vm = vm(probe = probe)
        advanceUntilIdle()
        vm.fillRemote()

        vm.testConnection()
        advanceUntilIdle()

        val state = vm.ui.value.cloud.probe
        assertWithMessage("失败是**一个**确定状态，不是「没成功」这种含糊话")
            .that(state).isInstanceOf(CloudProbeState.Failed::class.java)
        val failed = state as CloudProbeState.Failed

        assertThat(failed.host).isEqualTo(HOST)
        assertWithMessage("失败态不许带完整 URL —— 那条 URL 的 query 里可能就是 key")
            .that(failed.host).doesNotContain("?")
        assertThat(failed.failure).isEqualTo(CloudFailure.ConnectFailed)
        assertThat(failed.failure.phase).isEqualTo(com.ake.core.ports.EgressPhase.PRE)
    }

    /** ★ 没接上就是没接上：不用「能存进去」冒充「能连上」—— 那是最容易假绿的一处。 */
    @Test
    fun anUnwiredProbeSaysSoInsteadOfPretendingItPassed() = runUiTest {
        val vm = vm(probe = null) // ← Optional.empty()
        advanceUntilIdle()
        vm.fillRemote()

        vm.testConnection()
        advanceUntilIdle()

        assertThat(vm.ui.value.cloud.probe).isEqualTo(CloudProbeState.NotWired)
    }

    /** 改了端点之后，上一次「可用」必须作废：那个结论只对那一份配置成立。 */
    @Test
    fun editingTheEndpointInvalidatesTheLastVerdict() = runUiTest {
        val probe = ScriptedCloudProbe(result = CloudProbeResult.Ok)
        val vm = vm(probe = probe)
        advanceUntilIdle()
        vm.fillRemote()
        vm.testConnection()
        advanceUntilIdle()
        assertThat(vm.ui.value.cloud.probe).isEqualTo(CloudProbeState.Available(HOST))

        vm.onBaseUrlChange("https://other.example.com/v1")
        advanceUntilIdle()

        assertWithMessage("换了个地址还挂着「可用」，等于拿 A 的体检报告给 B 用")
            .that(vm.ui.value.cloud.probe).isEqualTo(CloudProbeState.Idle)
    }

    /** ★ 归一化：大小写 / 尾点 / 端口 / query 一律收成同一个 host（唯一实现 `Hosts.normalise`）。 */
    @Test
    fun aMessyUrlIsEchoedOnlyAsTheNormalisedHost() = runUiTest {
        val probe = ScriptedCloudProbe(result = CloudProbeResult.Ok)
        val vm = vm(probe = probe)
        advanceUntilIdle()
        chooseRemoteWith(vm, MESSY_URL)

        vm.testConnection()
        advanceUntilIdle()

        assertThat(vm.ui.value.cloud.probe).isEqualTo(CloudProbeState.Available(MESSY_HOST))
        assertWithMessage("★ 回显的 host 必须过唯一归一化：大写 / 尾点 / 端口 / query 都不许留下")
            .that(vm.ui.value.cloud.host).isEqualTo(MESSY_HOST)
    }

    /** 一个读不出域名的地址：拒绝并给提示，绝不凭空造一个 host。 */
    @Test
    fun aHostlessUrlIsRejectedInsteadOfInventingOne() = runUiTest {
        val creds = FakeCloudCredentials()
        val vm = vm(creds = creds)
        advanceUntilIdle()
        chooseRemoteWith(vm, "example.com/list")

        vm.saveCloudCredential()
        advanceUntilIdle()

        assertWithMessage("相对 URL 的首段是 path，不是 host")
            .that(creds.upserts).isEmpty()
        assertThat(vm.ui.value.cloud.message).contains("读不出域名")
    }

    // ══════════════════════════════════════════════ ② 后端选择的落点

    /** 三个都没登记过目标的会话 ⇒ 三个一起被写上。 */
    @Test
    fun theChoiceIsWrittenToEveryTargetlessSession() = runUiTest {
        val targets = FakeDialogTargetStore()
        val vm = vm(targets = targets)
        advanceUntilIdle()
        vm.chooseBackend(Locality.DEVICE)

        vm.applyBackendChoice()
        advanceUntilIdle()

        assertWithMessage("三个会话都还没有登记过目标 ⇒ 三个都该被写上")
            .that(targets.selects).hasSize(3)
        assertThat(targets.selects.map { it.first }).containsExactly("s1", "s2", "s3")
        assertThat(targets.selects.map { it.second }).containsExactly(
            DialogTarget.Device,
            DialogTarget.Device,
            DialogTarget.Device,
        )
    }

    /** ★ 已经选过的会话一动不动 —— 会话内不可变（§3.3）。 */
    @Test
    fun sessionsThatAlreadyChoseAreLeftAlone() = runUiTest {
        val targets = FakeDialogTargetStore(
            initial = mapOf(
                "s1" to DialogTarget.Device,
                "s2" to DialogTarget.Remote(CredentialRef("cloud:old@old.example.com")),
            )
        )
        val vm = vm(targets = targets)
        advanceUntilIdle()
        vm.chooseBackend(Locality.DEVICE)

        vm.applyBackendChoice()
        advanceUntilIdle()

        assertWithMessage(
            "★ 已经登记过目标的会话不许被改写 —— 那些回答的承诺是当时那个后端给的。" +
                "回头改写，界面上就会出现「这条是本机写的」而它其实是云端写的。"
        ).that(targets.selects.map { it.first }).containsExactly("s3")
    }

    /** 选云端时，落进目标的是 `credentialRef`，不是 key、也不是 URL（§5）。 */
    @Test
    fun choosingRemoteStoresOnlyACredentialRef() = runUiTest {
        val targets = FakeDialogTargetStore()
        val creds = FakeCloudCredentials()
        val vm = vm(creds = creds, targets = targets)
        advanceUntilIdle()
        vm.fillRemote()

        vm.saveCloudCredential()
        advanceUntilIdle()

        val ref = (targets.selects.first().second as DialogTarget.Remote).credentialRef
        assertThat(ref.id).contains(MODEL)
        assertThat(ref.id).contains(HOST)
        assertWithMessage("目标里装不下密钥（§5）").that(ref.id).doesNotContain(KEY)

        // 凭证本体确实进了 store（加密那一层由 `:app` 的 store 负责）
        assertThat(creds.upserts).hasSize(1)
        assertThat(creds.upserts.single().second.hostOf()).isEqualTo(HOST)
    }

    /** 已保存的服务列出来：只有 modelName 与归一化 host，没有 URL、没有 Key。 */
    @Test
    fun theSavedListShowsModelAndHostOnly() = runUiTest {
        val probe = ScriptedCloudProbe()
        val vm = vm(probe = probe)
        advanceUntilIdle()
        vm.fillRemote()
        vm.saveCloudCredential()
        advanceUntilIdle()

        val saved = vm.ui.value.cloud.saved
        assertWithMessage("保存完应当立刻出现在列表里").that(saved).isNotEmpty()
        saved.forEach { item ->
            assertThat(item.host).isEqualTo(HOST)
            assertThat(item.modelName).isEqualTo(MODEL)
            assertWithMessage("列表里不许出现完整 URL").that(item.toString()).doesNotContain("?key")
            assertWithMessage("列表里不许出现 Key").that(item.toString()).doesNotContain(KEY)
        }
    }

    private fun chooseRemoteWith(vm: SettingsViewModel, url: String) {
        vm.chooseBackend(Locality.REMOTE)
        vm.onBaseUrlChange(url)
        vm.onModelNameChange(MODEL)
        vm.onApiKeyChange(KEY)
    }
}
