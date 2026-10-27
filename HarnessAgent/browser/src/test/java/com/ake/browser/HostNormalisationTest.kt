package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P0 · `PocketDom.host` 的归一化 —— **这是安全代码**
//
// 结论先行：**是安全问题，已修。**
//
// 证据链（全部在仓库里，不靠推断）：
//   ① `core/contract/Records.kt` 的 `ObservationSummary.host` 契约注释写死：
//      「**小写化**裸域名，明文 —— 域名策略要**精确等值查询**」（数据库 §7.4 / §8.1）。
//   ② `agent/loop/ObservationProjector` 用 `dom.host.ifBlank { hostOf(dom.url) }` 填这一列
//      —— 即 `PocketDom.host` 非空时**它就是落库的那一个值**。
//   ③ `agent/guard/DomainPolicy` 的匹配全是精确等值：`host == it || host.endsWith(".$it")`，
//      黑名单是「命中即 Denied，**不提供确认选项**」。
//   ④ `com.ake.core.common.Url.host` / `UrlSanitizer.hostOf` / `ObservationProjector.hostOf`
//      三处**都**做了 lowercase + 去 `www.`；修复前 `DomJsonParser` 是唯一一处没做的
//      host 生产者 —— 而它恰好是主路径。
//
// 于是：带大写 host 的域名既不等于也不 endsWith 归一化后的名单项 ⇒
//   · 黑名单硬拒退化成「跨域名需确认」（护栏强度下降）；
//   · 白名单归类错 ⇒ 按 host 精确等值查询的保留/清理策略漏行（数据留得比策略久）。
// 这正是 `DomainPolicy` 自己的 KDoc 点名的「攻击者绕过白名单的入口」。
//
// 修法：把归一化收进 `DomJsonParser.hostOf`，口径与上面三处**逐字一致**。
// ===========================================================================

class HostNormalisationTest {

    @Test
    fun uppercaseHostCannotEscapeAnAllowlistEntry() {
        // 白名单里存的是归一化后的形式（用户录入时 `normalizeHost` 已小写化）
        val allowHosts = setOf("shop.example.com")
        val observed = DomJsonParser.parse(dom(url = "https://SHOP.Example.COM/list"), topK = 1).host

        assertWithMessage("大小写写法必须归一化到同一个 host，否则白名单被绕过")
            .that(observed).isEqualTo("shop.example.com")
        assertWithMessage("归一化后必须命中白名单的等值比较")
            .that(allowHosts.any { observed == it || observed.endsWith(".$it") }).isTrue()
    }

    @Test
    fun uppercaseHostCannotEscapeABlacklistEntry() {
        // 黑名单同理：`DomainPolicy.blacklistHit` 是 `it == host || host.endsWith(".$it")`
        val blacklist = setOf("pastebin.com")
        val observed = DomJsonParser.parse(dom(url = "https://PasteBin.com/raw/abcd"), topK = 1).host

        assertThat(observed).isEqualTo("pastebin.com")
        assertWithMessage("大写写法必须命中黑名单硬拒，否则硬拒退化成「需确认」")
            .that(blacklist.firstOrNull { it == observed || observed.endsWith(".$it") }).isNotNull()
    }

    @Test
    fun wwwPrefixIsStrippedCaseInsensitively() {
        for (u in listOf(
            "https://www.example.com/a",
            "https://WWW.example.com/a",
            "https://Www.Example.COM/a",
        )) {
            assertWithMessage(u).that(DomJsonParser.parse(dom(url = u), topK = 1).host).isEqualTo("example.com")
        }
    }

    @Test
    fun portUserinfoAndQueryAreNotPartOfTheHost() {
        assertThat(DomJsonParser.parse(dom(url = "https://user:pw@shop.example.com:8443/a?x=1#f"), topK = 1).host)
            .isEqualTo("shop.example.com")
    }

    @Test
    fun normalisationMatchesTheOtherThreeProducersInTheRepo() {
        // ★ 与 `Url.host` 逐字同口径 —— 四个生产者必须给出同一个答案，
        //   否则「同一站点在库里有两个 host」会让按 host 的等值查询静默漏行。
        val urls = listOf(
            "https://WWW.Example.com/a",
            "https://shop.example.com/list?q=1",
            "https://A.B.C.example.cn/x",
            "http://localhost:8080/y",
        )
        for (u in urls) {
            assertWithMessage(u)
                .that(DomJsonParser.parse(dom(url = u), topK = 1).host)
                .isEqualTo(com.ake.core.common.Url(u).host)
        }
    }

    @Test
    fun relativeOrSchemelessUrlsStillYieldEmptyHost() {
        // 归一化不能顺手「发明」出 host —— 相对 URL 没有域，就是没有域
        for (u in listOf("example.com/list", "/checkout", "about:blank", "")) {
            assertWithMessage(u).that(DomJsonParser.parse(dom(url = u), topK = 1).host).isEmpty()
        }
    }

    @Test
    fun nonAsciiAndPunycodeHostsDoNotThrow() {
        // 国际化域名 / 畸形 authority 不能让 parse 抛异常
        for (u in listOf("https://例子.测试/a", "https://xn--fsq.com/a", "https:///a", "https://[::1]/a")) {
            val host = DomJsonParser.parse(dom(url = u), topK = 1).host
            assertWithMessage(u).that(host).isNotNull()
        }
    }
}
