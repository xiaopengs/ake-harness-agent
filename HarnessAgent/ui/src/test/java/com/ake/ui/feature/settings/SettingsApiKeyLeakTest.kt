package com.ake.ui.feature.settings

import com.ake.core.common.AkeLog
import com.ake.ui.spi.CloudProbeResult
import com.ake.ui.testsupport.CapturingLogSink
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
import org.junit.After
import org.junit.Test

// ===========================================================================
// ★★ 负向用例：API Key 在这条链路上**不能出现在任何地方**
//
// 一个「不许泄漏」的断言很容易变成永真的废话 —— 只要整条路本来就没 log、没状态、
// 没报错，断言「没有一行日志含 sk-xxx」永远成立，改坏了也不红。
// 所以本文件的每一条都先落一枚**哨兵**：证明这条路确实会产出可见输出，
// 然后在同一份输出里找 Key。找不到才说明真的没漏。
//
// 三个被盯住的出口：
//   ① 日志（`AkeLog` 的 sink）—— 装上 sink，先断言它收到了本页的日志；
//   ② UI 状态（`SettingsUi` 的 toString）—— Key 根本不在里面，半次 empirical 都不给；
//   ③ 失败提示（自检失败与保存失败的消息）—— 这也是§6.3 最容易被漏的一处。
// ===========================================================================
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsApiKeyLeakTest {

    private companion object {
        /** ★★ 这串 Key 同时出现在三个地方：Key 输入框、BaseURL 的 query、模型名之外。 */
        const val KEY: String = "sk-live-SECRETKEY1234567890"

        /** ★ 故意把 Key 塞进 URL 的 query —— 自建网关真这么干（§6.3 ③）。 */
        const val URL: String = "https://gw.example.com/v1?key=sk-live-SECRETKEY1234567890"
        const val MODEL: String = "deepseek-chat"
        const val LABEL: String = "测试网关"
        const val LOG_TAG: String = "AkeCloudSettings"
    }

    private val sink = CapturingLogSink()

    private fun installSink() {
        AkeLog.clear()
        AkeLog.minLevel = com.ake.core.common.LogLevel.VERBOSE
        AkeLog.install(sink)
    }

    @After
    fun tearDown() {
        AkeLog.uninstall(sink)
        AkeLog.clear()
    }

    private fun vm(
        creds: FakeCloudCredentials = FakeCloudCredentials(),
        targets: FakeDialogTargetStore = FakeDialogTargetStore(),
        sessions: FakeChatSessionStore = FakeChatSessionStore(summaries = listOf(chatSummary())),
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

    private fun SettingsViewModel.fillEverything() {
        chooseBackend(com.ake.core.ports.Locality.REMOTE)
        onBaseUrlChange(URL)
        onModelNameChange(MODEL)
        onLabelChange(LABEL)
        onApiKeyChange(KEY)
    }

    // ══════════════════════════════════════════════════════ ① 日志

    /**
     * ★ 这条既要断言「没有一行含 Key」，也要断言「确实有日志被写下来」。
     *
     * 少了后半句，把 `AkeLog.i(...)` 那行删掉也能绿 —— 那不是在测防泄漏，
     * 是在测「这条路一片安静」。哨兵先落地，负向断言才有牙。
     */
    @Test
    fun theKeyIsAbsentFromEveryLogLineTheSavePathEmits() = runUiTest {
        installSink()
        val creds = FakeCloudCredentials()
        val vm = vm(creds = creds)
        advanceUntilIdle()
        vm.fillEverything()

        vm.saveCloudCredential()
        advanceUntilIdle()

        val mine = sink.lines.filter { it.first == LOG_TAG }
        assertWithMessage(
            "这条路径本该打日志（我在 `SettingsViewModel.saveCloudCredential` 里留了一行）。" +
                "哨兵没落地 ⇒ 后面的「没有一行含 Key」是句空话。"
        ).that(mine).isNotEmpty()

        mine.forEach { (_, message) ->
            assertWithMessage("日志里有 Key：$message").that(message).doesNotContain(KEY)
            assertWithMessage("日志里有完整 URL：$message").that(message).doesNotContain("key=")
            assertWithMessage("日志里泄露了 query：$message").that(message).doesNotContain("?key")
        }

        // 反向 sanity：归一化 host 这种「可以有的信息」确实记下来了。
        assertWithMessage("日志里至少要有 host —— 否则排障时无从下手")
            .that(mine.joinToString("\n")).contains("gw.example.com")
    }

    // ══════════════════════════════════════════════════════ ② UI 状态

    /**
     * ★ Key 不进 `SettingsUi` —— 指名点姓地断言它不在任何一个能被 dump 的状态里。
     *
     * 这条能杀死的实现是「给 `CloudBackendUi` 加一个 `apiKey: String` 字段」：
     * 那个改动看起来最自然，而它会立刻让每一个 `collect` 都看见明文。
     */
    @Test
    fun theKeyNeverEntersUiStateEvenWhileTyping() = runUiTest {
        val vm = vm()
        advanceUntilIdle()
        vm.fillEverything()

        val dumped = vm.ui.value.toString()
        assertWithMessage(
            "UI 状态里出现了 Key —— 它会跟着 StateFlow 流到每一个 collect 手上。"
        ).that(dumped).doesNotContain(KEY)
        assertWithMessage("UI 状态里出现了 URL 的 query").that(dumped).doesNotContain("?key=")

        // ★ 反过来证明「状态里确实有东西」：host、模型名都在。
        //   少了这一步，上面两条只是在断言一个空字符串。
        assertWithMessage("UI 状态里本该有派生出来的归一化 host")
            .that(vm.ui.value.cloud.host).isEqualTo("gw.example.com")
        assertWithMessage("UI 状态里本该有模型名")
            .that(vm.ui.value.cloud.modelName).isEqualTo(MODEL)
        assertWithMessage("原文仍在 SecretDraft 里 —— TextField 要用它回填")
            .that(vm.urlDraft.value).isEqualTo(URL)
        assertWithMessage("「填没填」必须是可见的，否则上面的断言只是在检查一个空集合")
            .that(vm.ui.value.cloud.keyFilled).isTrue()
    }

    /** `ApiKeyDraft.toString()` 是最后一道兜底：任何地方 dump 它都只得到掩码。 */
    @Test
    fun theDraftItSelfPrintsAsMask() = runUiTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onApiKeyChange(KEY)

        assertWithMessage("`ApiKeyDraft` 的 toString 必须恒为掩码：它是调试器里最后一次机会")
            .that(vm.apiKey.toString()).isEqualTo("***")
        // ★ 长度也不给 —— 长度是一条侧信道（32 还是 64 字符就砍掉一半候选）。
        assertWithMessage("掩码里不许带长度").that(vm.apiKey.toString()).doesNotContain(KEY.length.toString())
    }

    // ══════════════════════════════════════════════════════ ③ 失败提示

    /** 自检失败时只回显归一化 host，绝不回显 Key 或完整 URL。 */
    @Test
    fun aFailedProbeEchoesOnlyTheNormalisedHost() = runUiTest {
        installSink()
        val probe = ScriptedCloudProbe(result = CloudProbeResult.Failed(com.ake.core.ports.CloudFailure.AuthRejected))
        val vm = vm(probe = probe)
        advanceUntilIdle()
        vm.fillEverything()

        vm.testConnection()
        advanceUntilIdle()

        val state = vm.ui.value.cloud.probe
        assertWithMessage("失败状态必须是 Failed 而不是 Idle").that(state).isInstanceOf(CloudProbeState.Failed::class.java)
        val failed = state as CloudProbeState.Failed

        assertWithMessage("★ 失败回显只能是归一化 host —— 完整 URL 里可能带着 key")
            .that(failed.host).isEqualTo("gw.example.com")
        assertWithMessage("host 里出现了 query").that(failed.host).doesNotContain("?")
        assertThat(failed.host).doesNotContain(KEY)

        // 消息里也不能有（含 `message` 这条排泄渠道）。
        val spoken = (vm.ui.value.cloud.message ?: "") + failed.toString()
        assertWithMessage("失败提示里出现了 Key：$spoken").that(spoken).doesNotContain(KEY)
        assertWithMessage("把 Key 吐在文案里比吐在日志里更糟：它直接坐到用户眼前")
            .that(spoken).doesNotContain("key=")
    }

    /** 保存失败同样不许带 Key：`Outcome` 的 error 里本来也没有它，这里是加成防守。 */
    @Test
    fun aFailedSaveDoesNotEchoTheKeyEither() = runUiTest {
        installSink()
        val creds = FakeCloudCredentials()
        val vm = vm(creds = creds)
        advanceUntilIdle()
        vm.fillEverything()
        vm.saveCloudCredential()
        advanceUntilIdle()

        val spoken = vm.ui.value.cloud.message.orEmpty()
        assertWithMessage("保存成功的提示里出现了 Key").that(spoken).doesNotContain(KEY)
        assertWithMessage("保存提示里出现了完整 URL —— 用户不需要看见它，也不该被保存两次")
            .that(spoken).doesNotContain("key=")

        // ★ Key 用完之后必须当场丢掉：留在草稿里等下一个用户动作，等于延长暴露窗口。
        assertWithMessage("保存之后内存里的明文必须立刻清掉")
            .that(vm.apiKey.value).isEmpty()
        assertWithMessage("UI 状态里的 keyFilled 也要跟着回到 false")
            .that(vm.ui.value.cloud.keyFilled).isFalse()
    }

    /** 顺带（哨兵的另一半）：证明 Key 确实一路走到了 `upsert`，而不是被半路掉包丢了。 */
    @Test
    fun theSecretReallyReachesTheCredentialStore() = runUiTest {
        val creds = FakeCloudCredentials()
        val vm = vm(creds = creds)
        advanceUntilIdle()
        vm.fillEverything()
        vm.saveCloudCredential()
        advanceUntilIdle()

        assertWithMessage("Key 必须真的交到 `CloudCredentialPort` 手上，否则上面的负向断言全为空话")
            .that(creds.upserts).hasSize(1)
        // ★ 用 `toString()` 而不是 `useAsBearer`：即便在这里也不把它读成明文。
        assertWithMessage("转手之后它依然是 `***`")
            .that(creds.secrets.single().useAsBearer { it }).isEqualTo(KEY)
        assertThat(creds.secrets.single().toString()).isEqualTo("***")
    }
}
