package com.ake.core.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// host 归一化 —— **这是安全代码**
//
// 本文件是 `core:common/Hosts.normalise` 的回归测试，同时把同一批畸形输入钉成
// 「全仓唯一答案」。这批向量被另外两份测试**原样复用**：
//
//   · `browser/HostNormalisationEquivalenceTest`（:browser 看得见 core:common）
//   · `agent/.../HostNormalisationParityTest`（:agent 看得见 core:common）
//
// 于是 `browser ↔ core:common ↔ agent` 三条路径被**传递地**锁在同一个口径上 ——
// `:agent` 与 `:browser` 互相看不见（架构守卫禁止 `:browser → :agent`），
// 链路只能这样接。
//
// 每一条向量都对应一种**真实存在过的**绕法，不是凑数：
//   大写 / 端口 / www 双前缀 / 尾点 / IDN+punycode / 多 `@` / query+fragment /
//   IPv6 / 相对 URL / 无协议 / opaque / 空 authority
// ===========================================================================

class HostsTest {

    @Test
    fun everyMalformedInputCollapsesToOneCanonicalAnswer() {
        for ((url, expected) in HOST_VECTORS) {
            assertWithMessage("Hosts.normalise($url)")
                .that(Hosts.normalise(url)).isEqualTo(expected)
            assertWithMessage("Url($url).host —— 必须与 Hosts.normalise 同一口径")
                .that(Url(url).host).isEqualTo(expected)
        }
    }

    @Test
    fun trailingDotCannotEscapeAnExactMatchPolicy() {
        // ★ 直接对着 `DomainPolicy` 的匹配式打：`host == it || host.endsWith(".$it")`。
        //   `pastebin.com.` 是合法 FQDN 写法且能解析 —— 不去尾点就是一条硬拒绕过。
        val blacklist = setOf("pastebin.com")
        for (u in listOf(
            "https://pastebin.com./raw/abcd",
            "https://PASTEBIN.com./raw/abcd",
            "https://www.pastebin.com./raw/abcd",
            "https://WWW.PASTEBIN.COM./raw/abcd",
        )) {
            val host = Url(u).host
            assertWithMessage(u).that(host).isEqualTo("pastebin.com")
            assertWithMessage("$u ⇒ host=$host 必须命中黑名单硬拒")
                .that(blacklist.firstOrNull { it == host || host.endsWith(".$it") }).isNotNull()
        }
    }

    @Test
    fun uppercaseCannotEscapeABlacklistEntry() {
        val blacklist = setOf("pastebin.com")
        for (u in listOf(
            "https://PASTEBIN.com/raw/abcd",
            "https://PasteBin.COM/raw/abcd",
            "https://WWW.PasteBin.com/raw/abcd",
        )) {
            val host = Url(u).host
            assertWithMessage(u).that(host).isEqualTo("pastebin.com")
            assertWithMessage("$u ⇒ host=$host 必须命中黑名单硬拒，否则硬拒退化成「需确认」")
                .that(blacklist.firstOrNull { it == host || host.endsWith(".$it") }).isNotNull()
        }
    }

    @Test
    fun userinfoWithColonDoesNotSwallowTheHost() {
        // ★ `UrlSanitizer.hostOf` 的历史写法（先去 `:` 再去 `@`）在这里产出 `user`
        //   —— 那是真真切切的「归一化成不该是 host 的东西」。
        assertThat(Url("https://user:pw@shop.example.com:8443/a").host).isEqualTo("shop.example.com")
        assertThat(Hosts.normalise("https://user:pw@shop.example.com:8443/a")).isEqualTo("shop.example.com")
    }

    @Test
    fun urlsWithoutAHostYieldEmptyStringNeverAnInventedOne() {
        // 空串 ⇒ 下游走「非本域」这条保守分支；编出来的假 host 会让策略以为自己认得这个域。
        for (u in listOf("", "   ", "example.com/list", "/checkout", "about:blank", "mailto:a@b.com",
            "//cdn.example.com/a", "urn:isbn:0451450523", "javascript:alert(1)", "https:///a")) {
            assertWithMessage(u).that(Hosts.normalise(u)).isEmpty()
            assertWithMessage(u).that(Url(u).host).isEmpty()
        }
    }

    @Test
    fun idnAndPunycodeSurviveAsUnicodeNotAsEmptyString() {
        // `java.net.URI(url).host` 对非 ASCII host 返回 null ⇒ 空串；
        // 而仓里其余几份产出小写后的 Unicode ⇒ 同一个 IDN 站点两个 host ⇒
        // 按 host 的等值查询静默漏行。
        assertThat(Hosts.normalise("https://例子.测试/a")).isEqualTo("例子.测试")
        assertThat(Hosts.normalise("https://XN--FSQ.com/a")).isEqualTo("xn--fsq.com")
        assertThat(Url("https://例子.测试/a").host).isEqualTo("例子.测试")
    }

    @Test
    fun normaliseIsIdempotentAndNeverThrows() {
        // 幂等性：已经是裸域名的输入再归一一次必须不变。
        // 两个例外不参与：`::1`（IPv6 裸写没有方括号就不是 URL）与 `www.…`
        // （`www.www.example.com` 只剥一层 `www.` 是**刻意的**：剥干净会让
        //  `www.www.example.com` 与 `www.example.com` 塌成同一个 host）。
        for ((url, expected) in HOST_VECTORS) {
            if (expected.isBlank() || expected.contains(':') || expected.startsWith("www.")) continue
            assertWithMessage("$url ⇒ 归一化必须幂等").that(Hosts.normalise("https://$expected/a")).isEqualTo(expected)
        }
        // 极端畸形输入只许退化，不许抛
        for (u in listOf("https://", "https://@", "https://[", "https://]/a", "://x", "https://@@@/a", "h://".repeat(50))) {
            Hosts.normalise(u)
        }
    }

    companion object {
        /**
         * **畸形输入向量表 —— 全仓共用一份口径**。
         *
         * 新增归一化步骤（或发现新的绕法）时，往这里加向量；三份测试会同时收紧。
         */
        val HOST_VECTORS: List<Pair<String, String>> = listOf(
            // ── 大写
            "https://SHOP.Example.COM/list" to "shop.example.com",
            "HTTPS://Shop.Example.com/a" to "shop.example.com",
            // ── 端口
            "https://shop.example.com:8443/a" to "shop.example.com",
            "https://shop.example.com:/a" to "shop.example.com",
            "http://localhost:8080/y" to "localhost",
            // ── www 前缀（含大小写与双重前缀）
            "https://WWW.Example.COM/a" to "example.com",
            "https://www.example.com/a" to "example.com",
            "https://www.www.example.com/a" to "www.example.com",
            // ── ★ 尾点（FQDN 合法写法，精确等值匹配下是一条绕过路径）
            "https://shop.example.com./a" to "shop.example.com",
            "https://SHOP.Example.com./a" to "shop.example.com",
            "https://www.pastebin.com./raw/abcd" to "pastebin.com",
            // ── IDN / punycode
            "https://例子.测试/a" to "例子.测试",
            "https://xn--fsq.com/a" to "xn--fsq.com",
            "https://XN--FSQ.com/a" to "xn--fsq.com",
            // ── userinfo（含 userinfo 里带 ':' 的历史坑）/ query / fragment
            "https://user:pw@shop.example.com:8443/a?x=1#f" to "shop.example.com",
            "https://a@b@shop.example.com/a" to "shop.example.com",
            "https://shop.example.com?x=1" to "shop.example.com",
            "https://shop.example.com#f" to "shop.example.com",
            "https://shop.example.com/a?q=1&r=2" to "shop.example.com",
            // ── IPv6：方括号不是 host 的一部分
            "https://[::1]:8080/a" to "::1",
            "https://[::1]/a" to "::1",
            // ── 带空格 / 前导空白
            "  https://Shop.Example.com/a  " to "shop.example.com",
            // ── 无 host：相对 / 无协议 / opaque / 空 authority
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
