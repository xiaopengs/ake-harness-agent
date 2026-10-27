package com.ake.agent.context

import com.ake.agent.loop.ObservationProjector
import com.ake.core.common.Hosts
import com.ake.core.common.Url
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// host 归一化 —— `:agent` 这一侧的**口径对拍**（★ 这是安全代码）
//
// ## 为什么这份测试必须存在
//
// `DomainPolicy` 的匹配全是**精确等值**（`host == it || host.endsWith(".$it")`），
// 黑名单「命中即 Denied，不提供确认选项」。于是 `:agent` 里**每一处** host 生产者
// 漏一步，就是一条独立可用的绕过路径。`:agent` 原本有两处：
//
//   ③ `ObservationProjector.hostOf` —— 漏「去尾点」、相对 URL 产出 junk
//   ④ `UrlSanitizer.hostOf`         —— 顺序错（`user:pw@host:8443` ⇒ `user`）、
//                                       漏 `?` / `#`、漏尾点、IPv6 砍成 `[`
//
// 两处现在都转发到 `core:common/Hosts.normalise`。**「转发」这件事本身也必须被盯住** ——
// 否则下次有人为了省一次调用把管道抄回来，差异会默默烂掉。
//
// ## 链路怎么接上的
//
//   ① `browser/Hosts.normalise`      ┐
//   ② `core:common/Url.host`        ┘ 由 `browser/HostNormalisationEquivalenceTest` 对拍
//   ② vs ③④                         由**本文件**对拍
//   ⇒ 传递地：①②③④ 全等。
//
// `:agent` 编译期**看不见** `:browser`（架构守卫只允许 `:browser → core:*`），
// `:browser` 也看不见 `:agent`（禁止 `:browser → :agent`）—— 所以不可能有一份测试
// 同时 import 两边，只能这样两段接。
// ===========================================================================

class HostNormalisationParityTest {

    /** `:agent` 里**所有** host 生产者。新增一处就必须加进这张表，否则它就是新的绕路。 */
    private val agentProducers: List<Pair<String, (String) -> String>> = listOf(
        "ObservationProjector.hostOf" to { u: String -> ObservationProjector.hostOf(u) },
        "UrlSanitizer.hostOf" to { u: String -> UrlSanitizer.hostOf(u) },
    )

    @Test
    fun allAgentProducersAgreeWithTheSharedImplementationOnEveryMalformedInput() {
        // ★ 向量表与 `core:common/HostsTest` 逐字相同 —— 同一批畸形输入，
        //   喂给所有路径，输出必须一致。
        for ((url, expected) in HOST_VECTORS) {
            assertWithMessage("core:common/Hosts.normalise($url)")
                .that(Hosts.normalise(url)).isEqualTo(expected)
            assertWithMessage("core:common/Url.host($url)")
                .that(Url(url).host).isEqualTo(expected)
            for ((name, produce) in agentProducers) {
                assertWithMessage("$name($url) —— 必须与共享实现同一口径")
                    .that(produce(url)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun agentProducersDoNotLeakTheTrailingDot() {
        // ★ `pastebin.com.` 是合法 FQDN 写法且能解析；不去尾点 ⇒ 既不等于也不 endsWith
        //   ⇒ 硬拒退化成「需确认」。`ObservationProjector` 填的是 `ObservationSummary.host`，
        //   那一列要按 host 精确等值查询 —— 尾点会让同一个站点在库里变成两行。
        val blacklist = setOf("pastebin.com")
        for (u in listOf(
            "https://pastebin.com./raw/abcd",
            "https://PASTEBIN.com./raw/abcd",
            "https://www.pastebin.com./raw/abcd",
        )) {
            for ((name, produce) in agentProducers) {
                val host = produce(u)
                assertWithMessage("$name($u)").that(host).isEqualTo("pastebin.com")
                assertWithMessage("$name($u) ⇒ host=$host 必须命中黑名单硬拒")
                    .that(blacklist.firstOrNull { it == host || host.endsWith(".$it") }).isNotNull()
            }
        }
    }

    @Test
    fun userinfoWithColonNoLongerCollapsesTheHostToUser() {
        // ★ `UrlSanitizer.hostOf` 的历史 bug：先去 `:` 再去 `@`
        //   ⇒ `user:pw@host:8443` 的第一个 `:` 落在 userinfo 里 ⇒ 产出 `user`。
        val u = "https://user:pw@shop.example.com:8443/a?x=1#f"
        assertThat(UrlSanitizer.hostOf(u)).isEqualTo("shop.example.com")
        assertThat(ObservationProjector.hostOf(u)).isEqualTo("shop.example.com")
    }

    @Test
    fun queryAndFragmentAreNotPartOfTheHost() {
        // ★ `UrlSanitizer.hostOf` 的历史 bug：只截了 `/` 与 `:`，没截 `?` / `#`。
        for (u in listOf("https://shop.example.com?x=1", "https://shop.example.com#f")) {
            assertThat(UrlSanitizer.hostOf(u)).isEqualTo("shop.example.com")
            assertThat(ObservationProjector.hostOf(u)).isEqualTo("shop.example.com")
        }
    }

    @Test
    fun ipv6AuthorityIsNotTruncatedAtTheFirstColon() {
        assertThat(UrlSanitizer.hostOf("https://[::1]:8080/a")).isEqualTo("::1")
        assertThat(ObservationProjector.hostOf("https://[::1]:8080/a")).isEqualTo("::1")
    }

    @Test
    fun agentHasNoSecondHostNormalisationImplementation() {
        // ★ 机械断言：`:agent` 主源码里不许再出现「自己写一遍归一化管道」的文件。
        //   判据与 `:browser` 那份一致 —— 归一化共有的那一步 `removePrefix("www.")`。
        //   谁在 `:agent` 里再抄一份，这里立刻红。
        val offenders = agentMainSources()
            .filter { it.readText().contains("""removePrefix("www.")""") }
            .map { it.name }
        assertWithMessage(
            ":agent 出现了第二份 host 归一化实现。必须转发到 core:common/Hosts.normalise —— " +
                "每多一份就多一条黑名单绕过路径",
        ).that(offenders).isEmpty()
    }

    @Test
    fun bothAgentProducersDelegateToTheSharedImplementation() {
        // 「转发」也要盯住：这两个函数体必须是对 `Hosts.normalise` 的单行转发，
        // 不许哪天为了省一次调用把管道抄回来。
        for ((file, fn) in listOf(
            "loop/ObservationProjector.kt" to "ObservationProjector.hostOf",
            "context/PocketDomSerializer.kt" to "UrlSanitizer.hostOf",
        )) {
            val src = agentSource(file)
            assertWithMessage("$fn 必须转发到 core:common/Hosts.normalise")
                .that(src).contains("Hosts.normalise(url)")
            assertWithMessage("$fn 不许自己再写一遍小写/去 www 的管道")
                .that(src).doesNotContain("""removePrefix("www.")""")
        }
    }

    // ──────────────────────────────────────────────────────────── 静态审计工具

    private fun repoRoot(): java.io.File =
        generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }

    private fun agentMainSources(): List<java.io.File> =
        java.io.File(repoRoot(), "agent/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun agentSource(relativeToMainJava: String): String {
        val f = java.io.File(repoRoot(), "agent/src/main/java/com/ake/agent/$relativeToMainJava")
        check(f.isFile) { "找不到 $f —— :agent 主源码结构变了，静态审计需要同步" }
        return f.readText()
    }

    companion object {
        /** 与 `core:common/HostsTest.HOST_VECTORS` 逐字相同的畸形输入向量表。 */
        private val HOST_VECTORS: List<Pair<String, String>> = listOf(
            "https://SHOP.Example.COM/list" to "shop.example.com",
            "HTTPS://Shop.Example.com/a" to "shop.example.com",
            "https://shop.example.com:8443/a" to "shop.example.com",
            "https://shop.example.com:/a" to "shop.example.com",
            "http://localhost:8080/y" to "localhost",
            "https://WWW.Example.COM/a" to "example.com",
            "https://www.example.com/a" to "example.com",
            "https://www.www.example.com/a" to "www.example.com",
            "https://shop.example.com./a" to "shop.example.com",
            "https://SHOP.Example.com./a" to "shop.example.com",
            "https://www.pastebin.com./raw/abcd" to "pastebin.com",
            "https://例子.测试/a" to "例子.测试",
            "https://xn--fsq.com/a" to "xn--fsq.com",
            "https://XN--FSQ.com/a" to "xn--fsq.com",
            "https://user:pw@shop.example.com:8443/a?x=1#f" to "shop.example.com",
            "https://a@b@shop.example.com/a" to "shop.example.com",
            "https://shop.example.com?x=1" to "shop.example.com",
            "https://shop.example.com#f" to "shop.example.com",
            "https://shop.example.com/a?q=1&r=2" to "shop.example.com",
            "https://[::1]:8080/a" to "::1",
            "https://[::1]/a" to "::1",
            "  https://Shop.Example.com/a  " to "shop.example.com",
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
