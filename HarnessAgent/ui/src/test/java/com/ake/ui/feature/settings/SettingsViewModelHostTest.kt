package com.ake.ui.feature.settings

import com.ake.core.common.Hosts
import com.ake.ui.testsupport.FakeSettingsStore
import com.ake.ui.testsupport.FixedBudgets
import com.ake.ui.testsupport.FixedDeviceHealth
import com.ake.ui.testsupport.NoopPrivacy
import com.ake.ui.testsupport.runUiTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

// ===========================================================================
// 设置页「域名白名单」的录入侧 —— **这是安全代码的入口**
//
// 写进白名单的那个字符串，就是 `DomainPolicy` 精确等值匹配时用的那个字符串。
// 于是录入侧每少一步归一化，就多一条绕过路径（大写 / 尾点 / userinfo / fragment …）。
// 本文件锁两件事：
//
//   ① 录入侧**不再有自己的归一化** —— 带协议的输入必须与 `Hosts.normalise` 逐字相同；
//   ② 判不出 host 的输入（相对 URL / opaque）**必须被拒绝并提示**，绝不许凭空造出 host。
//
// ★ 这里刻意**不**复制一份 `Hosts.normalise` 的期望值来"对拍两份实现"：
//   那正是本次要修掉的病根 —— 5 份不等价实现 + 等价断言 = 要求 5 个文件永远同步。
//   录入侧的期望值只有两种来源：`Hosts.normalise` 本身，或「空串 + 提示」。
// ===========================================================================
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelHostTest {

    // ─────────────────────────────────────────────── 用户最常见的输入不能被拒

    @Test
    fun whatThePlaceholderTellsYouToTypeIsAccepted() = runUiTest {
        // 输入框 placeholder 写的是 `example.com`，不带协议。
        // `Hosts.normalise("example.com")` 返回**空串**（它只认「字母开头 + ://」）——
        // 照搬就会把用户最正常的输入拒掉，所以录入侧要补协议兜住这一层。
        val store = FakeSettingsStore()
        val vm = vm(store)
        advanceUntilIdle()

        vm.onHostDraftChange("example.com")
        vm.addAllowHost()
        advanceUntilIdle()

        assertWithMessage("裸域名必须能进白名单，否则设置页等于不可用")
            .that(store.updates.single().allowHosts).containsExactly("example.com")
        assertThat(vm.ui.value.message).isEqualTo("已加入白名单：example.com（下次任务生效）")
    }

    @Test
    fun everyMalformedInputLandsOnTheSharedAnswer() = runUiTest {
        val vm = vm()
        advanceUntilIdle()

        for ((raw, expected) in HOST_VECTORS) {
            assertWithMessage("normalizeHost($raw)")
                .that(vm.normalizeHost(raw)).isEqualTo(expected)
        }
    }

    @Test
    fun schemefulInputIsByteForByteWhatThePolicySideSees() = runUiTest {
        // ★ 真正的防分叉断言：带协议的输入，录入侧不许有任何自己的加工。
        val vm = vm()
        advanceUntilIdle()

        for ((raw, _) in HOST_VECTORS) {
            if (!raw.contains("://")) continue
            assertWithMessage("normalizeHost($raw) 必须与 Hosts.normalise 同口径")
                .that(vm.normalizeHost(raw)).isEqualTo(Hosts.normalise(raw))
        }
    }

    // ─────────────────────────────────────────────── 没有 host 的输入不许造一个出来

    @Test
    fun aRelativeUrlIsRejectedWithAHintInsteadOfInventingAHost() = runUiTest {
        val store = FakeSettingsStore()
        val vm = vm(store)
        advanceUntilIdle()

        vm.onHostDraftChange("example.com/list")
        vm.addAllowHost()
        advanceUntilIdle()

        assertWithMessage("相对 URL 的首段是 path，不是 host —— 写成白名单就是一条绕过")
            .that(store.updates).isEmpty()
        assertThat(vm.ui.value.message)
            .isEqualTo("域名不合法：请填 example.com，或带协议写成 https://example.com")
    }

    @Test
    fun opaqueAndProtocolRelativeUrlsAreRejectedToo() = runUiTest {
        val store = FakeSettingsStore()
        val vm = vm(store)
        advanceUntilIdle()

        for (raw in listOf("about:blank", "mailto:a@b.com", "//cdn.example.com/a", "urn:isbn:0451450523")) {
            vm.onHostDraftChange(raw)
            vm.addAllowHost()
            advanceUntilIdle()
        }

        assertWithMessage("opaque / 协议相对 URL 没有 host，补协议就等于造一个假域")
            .that(store.updates).isEmpty()
        assertThat(vm.ui.value.message)
            .isEqualTo("域名不合法：请填 example.com，或带协议写成 https://example.com")
    }

    @Test
    fun anEmptyDraftDoesNothingAtAll() = runUiTest {
        val store = FakeSettingsStore()
        val vm = vm(store)
        advanceUntilIdle()

        vm.onHostDraftChange("   ")
        vm.addAllowHost()
        advanceUntilIdle()

        assertThat(store.updates).isEmpty()
        assertWithMessage("空输入只是没填，不是填错了 —— 不该弹任何提示")
            .that(vm.ui.value.message).isNull()
    }

    // ─────────────────────────────────────────────── 黑名单绕过在本入口被堵住

    @Test
    fun aTrailingDotCannotBeWrittenIntoTheAllowlistVerbatim() = runUiTest {
        // ★ 尾点是合法 FQDN 写法且能解析；`DomainPolicy` 是精确等值匹配 ——
        //   `pastebin.com.` 既不等于 `pastebin.com` 也不 endsWith `.pastebin.com` ⇒ 硬拒被绕过。
        val vm = vm()
        advanceUntilIdle()
        val blacklist = setOf("pastebin.com")

        for (raw in listOf(
            "pastebin.com.",
            "PASTEBIN.com.",
            "www.pastebin.com.",
            "https://pastebin.com./raw/abcd",
            "https://WWW.PASTEBIN.COM./raw/abcd",
        )) {
            val host = vm.normalizeHost(raw)
            assertWithMessage(raw).that(host).isEqualTo("pastebin.com")
            assertWithMessage("$raw ⇒ host=$host 必须命中黑名单硬拒")
                .that(blacklist.firstOrNull { it == host || host.endsWith(".$it") }).isNotNull()
        }
    }

    @Test
    fun uppercaseAndFragmentAndUserinfoAllCollapseToTheSameEntry() = runUiTest {
        val store = FakeSettingsStore()
        val vm = vm(store)
        advanceUntilIdle()

        // 四种写法是同一个域 —— 白名单里就该只有一条。多出来一条等于多一个可绕过面。
        for (raw in listOf(
            "https://SHOP.Example.COM/list",
            "https://shop.example.com./a",
            "https://shop.example.com/a?q=1#f",
            "https://user:pw@shop.example.com:8443/a",
            "https://a@b@shop.example.com/a",
        )) {
            vm.onHostDraftChange(raw)
            vm.addAllowHost()
            advanceUntilIdle()
        }

        assertWithMessage("去重靠的是归一化后的等值，不是用户输入的字面值")
            .that(store.updates).hasSize(1)
        assertThat(store.updates.single().allowHosts).containsExactly("shop.example.com")
    }

    @Test
    fun aSecondAddOfTheSameHostSaysSoRatherThanDuplicating() = runUiTest {
        val store = FakeSettingsStore()
        val vm = vm(store)
        advanceUntilIdle()

        vm.onHostDraftChange("example.com")
        vm.addAllowHost()
        advanceUntilIdle()
        vm.onHostDraftChange("https://WWW.example.com/x")
        vm.addAllowHost()
        advanceUntilIdle()

        assertThat(store.updates).hasSize(1)
        assertThat(vm.ui.value.message).isEqualTo("已经在名单里了")
    }

    // ─────────────────────────────────────────────── 内部

    private fun vm(store: FakeSettingsStore = FakeSettingsStore()): SettingsViewModel =
        SettingsViewModel(NoopPrivacy, store, FixedDeviceHealth(), FixedBudgets())

    private companion object {

        /**
         * 设置页的录入向量表。期望值只有两个来源：
         *  · 带协议 ⇒ [Hosts.normalise] 的答案（逐字照抄，不另立口径）；
         *  · 无 host ⇒ 空串（调用方据此提示用户）。
         */
        val HOST_VECTORS: List<Pair<String, String>> = listOf(
            // ── 裸域名（用户最常见的写法，placeholder 就是这个）
            "example.com" to "example.com",
            "  example.com  " to "example.com",
            "PASTEBIN.com" to "pastebin.com",
            "WWW.Example.COM" to "example.com",
            "www.www.example.com" to "www.example.com",
            "example.com:8443" to "example.com",
            "example.com." to "example.com",
            "localhost:8080" to "localhost",
            "[::1]" to "::1",
            "[::1]:8080" to "::1",
            // ── 大写
            "https://SHOP.Example.COM/list" to "shop.example.com",
            "HTTPS://Shop.Example.com/a" to "shop.example.com",
            // ── 端口
            "https://shop.example.com:8443/a" to "shop.example.com",
            "http://localhost:8080/y" to "localhost",
            // ── www 双前缀 / 尾点
            "https://www.www.example.com/a" to "www.example.com",
            "https://shop.example.com./a" to "shop.example.com",
            "https://www.pastebin.com./raw/abcd" to "pastebin.com",
            // ── fragment / query / 多个 `@`
            "https://shop.example.com/a?q=1#f" to "shop.example.com",
            "https://shop.example.com#f" to "shop.example.com",
            "https://a@b@shop.example.com/a" to "shop.example.com",
            // ── IPv6 方括号
            "https://[::1]:8080/a" to "::1",
            // ── ★ 没有 host：相对 / opaque / 协议相对 / 空 authority / 空串
            "example.com/list" to "",
            "/checkout" to "",
            "about:blank" to "",
            "mailto:a@b.com" to "",
            "//cdn.example.com/a" to "",
            "urn:isbn:0451450523" to "",
            "https:///a" to "",
            "" to "",
        )
    }
}
