package com.ake.browser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

// ===========================================================================
// P0 · host 归一化 —— **这是安全代码**
//
// 结论先行：**是安全问题；主路径已修，并且「归一化只有一份」这件事今天终于成立了。**
//
// ## 为什么它是安全问题（证据链，全部在仓库里，不靠推断）
//
//   ① `ObservationSummary.host` 的契约注释写死：「**小写化**裸域名，明文 ——
//      域名策略要**精确等值查询**」（数据库 §7.4 / §8.1）。
//   ② `agent/loop/ObservationProjector` 用 `dom.host.ifBlank { hostOf(dom.url) }` 填这一列
//      —— 即 `PocketDom.host` 非空时**它就是落库的那一个值**；其余路径只在它为空时兜底。
//   ③ `agent/guard/DomainPolicy` 的匹配全是精确等值：`host == it || host.endsWith(".$it")`，
//      黑名单是「命中即 Denied，**不提供确认选项**」。
//
// 于是四种漏法各自都是一条绕过路径：
//   · 少 lowercase           ⇒ `PASTEBIN.com` 既不等于也不 endsWith `pastebin.com`
//   · 少 去尾点              ⇒ `pastebin.com.` 同理（尾点是合法 FQDN 写法，能解析）
//   · userinfo/port 顺序错   ⇒ `user:pw@host:8443` 整条归成 `user`
//   · 少 去 `?` `#`          ⇒ `host?x=1` 被当成 host 的一部分
//
// ## 收敛过程（三轮）
//
//   · 第一轮（本文件最初的形态）：`:browser` 内部收敛成一份 `Hosts.normalise`，
//     `DomJsonParser.hostOf` 改为转发。当时全仓有 **5 处**互相不等价的归一化。
//   · 第二轮（霍承钧）：把这份实现下沉到 `core:common/Hosts.kt`，
//     `core:common/Url.host` 与 `:agent` 两处全部改成转发 ⇒ 5 → 3。
//   · 第三轮（苏映棠 + 本轮）：`:ui` 录入侧收敛成转发；`:browser` 这份在授权到位后
//     改成转发 ⇒ **全仓只剩 1 份实现**，其余 6 个入口全部是转发。
//
// ## 本文件盯住三件事
//
//   A. **三方（其实是四方）口径逐字一致**：同一批畸形输入喂给
//      `DomJsonParser.hostOf` / `com.ake.browser.Hosts.normalise` /
//      `com.ake.core.common.Hosts.normalise` / `com.ake.core.common.Url.host`，
//      输出必须完全相同（向量表从 `core:common/HostsTest.HOST_VECTORS` **机械抽取**，
//      不是在这里手抄一份）。
//   B. **实现的份数**：全仓 `src/main` 里含归一化管道的文件 `containsExactly` 地只剩 1 个。
//   C. **其余入口真的是转发**：登记下来的 6 个转发站点每一个都必须调用
//      `Hosts.normalise` 且自身不含管道 —— 「转发」松掉一天，就会长回第 2 份实现。
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
    fun normalisationMatchesTheOneProducerBrowserCanActuallySee() {
        val urls = listOf(
            "https://WWW.Example.com/a",
            "https://shop.example.com/list?q=1",
            "https://A.B.C.example.cn/x",
            "http://localhost:8080/y",
            "https://SHOP.Example.COM/list",
            "https://shop.example.com:8443/a",
            "https://user:pw@shop.example.com:8443/a?x=1#f",
            "https://例子.测试/a",
            "https://xn--fsq.com/a",
            "https://shop.example.com/list?q=shoe",
        )
        for (u in urls) {
            assertWithMessage(u)
                .that(DomJsonParser.parse(dom(url = u), topK = 1).host)
                .isEqualTo(com.ake.core.common.Url(u).host)
            assertWithMessage("$u —— 两条路径必须在同一口径上")
                .that(Hosts.normalise(u))
                .isEqualTo(com.ake.core.common.Url(u).host)
        }
    }

    @Test
    fun relativeOrSchemelessUrlsStillYieldEmptyHost() {
        // 归一化不能顺手「发明」出 host —— 相对 URL 没有域，就是没有域
        for (u in listOf("example.com/list", "/checkout", "about:blank", "", "//cdn.example.com/a", "mailto:a@b.com")) {
            assertWithMessage(u).that(DomJsonParser.parse(dom(url = u), topK = 1).host).isEmpty()
            assertWithMessage(u).that(Hosts.normalise(u)).isEmpty()
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

    // ───────────────────────────────────────────── ★ 三方口径一致（同源向量表）

    @Test
    fun everyMalformedInputCollapseToOneCanonicalAnswer() {
        // 同一批畸形输入喂进来，`:browser` 的三个入口只允许有一个答案。
        //
        // ★ 向量表改成从 `core:common/HostsTest.HOST_VECTORS` **机械抽取**：
        //   以前这里手抄了 17 条，权威表在同一时间已经涨到 30 条 —— 「少测一条」
        //   在下面这条断言里是完全看不出来的，因为它抄的是自己。
        val vectors = sharedHostVectors()
        for ((url, expected) in vectors) {
            assertWithMessage("DomJsonParser.hostOf($url)")
                .that(DomJsonParser.hostOf(url)).isEqualTo(expected)
            assertWithMessage("Hosts.normalise($url)")
                .that(Hosts.normalise(url)).isEqualTo(expected)
            assertWithMessage("parse().host($url)")
                .that(DomJsonParser.parse(dom(url = url), topK = 1).host).isEqualTo(expected)
        }
    }

    @Test
    fun allHostProducersAgreeVerbatimOnTheSharedMalformedVectors() {
        // ★ 交付点：`:browser` 的三个入口 ↔ `:core:common` 的两个入口，逐字一致。
        //
        //   `:browser` 编译期看得见 `:core:common`（依赖边已声明），所以这四者可以被
        //   同一份测试同时对拍 —— 这是 `HostNormalisationTest` 相对 `:agent` 那份
        //   parity 测试唯一多出来的东西，也是「转发生效」这件事的直接证据。
        //
        //   `:agent` / `:ui` 那两条链由它们的转发 + 静态清单兜住（见文末两条断言）。
        val vectors = sharedHostVectors()
        for ((url, expected) in vectors) {
            assertWithMessage("DomJsonParser.hostOf($url)")
                .that(DomJsonParser.hostOf(url)).isEqualTo(expected)
            assertWithMessage("com.ake.browser.Hosts.normalise($url)")
                .that(Hosts.normalise(url)).isEqualTo(expected)
            assertWithMessage("com.ake.core.common.Hosts.normalise($url)")
                .that(com.ake.core.common.Hosts.normalise(url)).isEqualTo(expected)
            assertWithMessage("com.ake.core.common.Url($url).host")
                .that(com.ake.core.common.Url(url).host).isEqualTo(expected)
        }
    }

    @Test
    fun trailingDotCannotEscapeAnExactMatchPolicy() {
        // ★ 直接对着 `DomainPolicy` 的匹配式打：`host == it || host.endsWith(".$it")`
        val blacklist = setOf("pastebin.com")
        for (u in listOf(
            "https://pastebin.com./raw/abcd",
            "https://PASTEBIN.com./raw/abcd",
            "https://www.pastebin.com./raw/abcd",
        )) {
            val host = DomJsonParser.parse(dom(url = u), topK = 1).host
            assertWithMessage(u).that(host).isEqualTo("pastebin.com")
            assertWithMessage("$u ⇒ host=$host 必须命中黑名单硬拒，否则硬拒退化成「需确认」")
                .that(blacklist.firstOrNull { it == host || host.endsWith(".$it") }).isNotNull()
        }
    }

    @Test
    fun idnHostDoesNotCollapseToEmptyLikeTheUriBasedImplementationDid() {
        // ⚠ 历史实现用 `java.net.URI(url).host`：非 ASCII host 解析失败 ⇒ 空串，
        //   而仓里其余几份产出的是小写后的 Unicode ⇒ 同一个 IDN 站点两个 host
        //   ⇒ 按 host 的等值查询静默漏行。
        assertThat(Hosts.normalise("https://例子.测试/a")).isEqualTo("例子.测试")
        assertThat(Hosts.normalise("https://例子.测试/a")).isEqualTo(com.ake.core.common.Url("https://例子.测试/a").host)
    }

    // ───────────────────────────────────────────── ★ 静态清单：只剩 1 份实现

    @Test
    fun browserHasNoSecondHostNormalisationImplementation() {
        // `:browser` 里现在连一份实现都没有了 —— 谁再抄一份，这里立刻红。
        val sites = browserMainSources()
            .filter { it.readText().contains(HOST_NORMALISATION_MARKER) }
            .map { it.name }
        assertWithMessage(
            "`:browser` 里出现了自己实现 host 归一化的文件。宿主要在 `:browser`，" +
                "实现必须转发到 `com.ake.core.common.Hosts.normalise` —— " +
                "每多一条管道就多一条黑名单绕过路径",
        ).that(sites).isEmpty()

        // 两个本地入口都必须是转发，不许写回管道。
        assertThat(browserSource("java/com/ake/browser/Hosts.kt"))
            .contains("com.ake.core.common.Hosts as SharedHosts")
        assertThat(browserSource("java/com/ake/browser/DomJsonParser.kt"))
            .contains("public fun hostOf(url: String): String = Hosts.normalise(url)")
    }

    @Test
    fun theRepoHasExactlyOneHostNormalisationImplementation() {
        // ★ 机械抽取：扫全仓 `src/main`，凡是还留着一层归一化管道的文件都必须上这条清单。
        //   理想态（本轮达成）：**只剩 1 份** —— `core/common/Hosts.kt`。
        //
        // ── 清单变更登记（2026-10 · 邝随行）—— **3 → 1，收敛到目标态**：
        //   上一版清单里的 `browser/.../Hosts.kt` 与 `ui/.../SettingsViewModel.kt`
        //   各自还持有一份（或一段）管道。`:ui` 那份苏映棠已改成 `Hosts.normalise` 的转发；
        //   `:browser` 这份本轮在授权到位后同样改成转发（`:browser → :core:common` 依赖边
        //   已声明，且被 `checkModuleDependencies` 守住方向）。
        //   ⇒ 连同上一轮已转发的 3 个 `:core:common` / `:agent` 入口，
        //     全仓现在是「1 份实现 + 6 个转发入口」，清单只剩下那一行。
        val found = repoMainKotlinSources()
            .filter { it.readText().contains(HOST_NORMALISATION_MARKER) }
            .map { it.relativeTo(repoRoot()).invariantSeparatorsPath }
            .toSortedSet()

        assertWithMessage(
            "全仓 host 归一化站点清单变了 —— 这是**唯一一份**实现，" +
                "新增一份立刻会让这里变红（这正是设计意图：多一份实现 = 多一条黑名单绕过路径）。" +
                "若确实新增了，让它转发到 `com.ake.core.common.Hosts.normalise`，" +
                "并把新入口登记到 `everyOtherEntryPointIsAForwarder` 的清单里",
        ).that(found).containsExactly(
            "core/common/src/main/java/com/ake/core/common/Hosts.kt",
        )
    }

    @Test
    fun everyOtherEntryPointIsAForwarder() {
        // ★ 「只是转发」这件事同样要被盯住：下面 6 个入口每一个都必须真的调用
        //   `Hosts.normalise`，且自己手里不许再留一层管道。
        //   转发松掉一天，就等于长回第 2 份实现 —— 而且是「看起来还是对的」那种。
        for ((file, what) in HOST_NORMALISATION_FORWARDERS) {
            val src = repoSource(file)
            assertWithMessage(
                "$what 必须转发到 `Hosts.normalise`（$file）—— 它现在是转发入口，" +
                    "不许把管道抄回来",
            ).that(src).containsMatch(HOST_FORWARD_CALL)
            assertWithMessage("$what（$file）不许自己留一层 lowercase/去 www 的管道")
                .that(src).doesNotContain(HOST_NORMALISATION_MARKER)
        }
    }

    // ───────────────────────────────────────────── ★ 锁「修复后」而不是「修复前」

    @Test
    fun theTrailingDotGapIsClosed_urlHostNowAgreesWithBrowserHosts() {
        // ✅ 原「自毁式」用例已翻面（2026-10 · 霍承钧，`core:common` / `agent` 那一轮）。
        //
        // 原来它钉的是**残口**：`core:common/Url.host` 产出带尾点的 `pastebin.com.`，
        // 于是尾点绕过在 `ObservationProjector` / `MemoryStoreImpl` / `PolicyEngine` 这些
        // 直接读 `Url.host` 的路径上仍然成立。
        //
        // 现在 `Url.host` 转发到 `com.ake.core.common.Hosts.normalise`（全仓唯一一份实现），
        // 两条管道逐字等价 ⇒ 断言改成「必须一致」。
        // 用例**故意保留**：它现在是「锁修复后行为」的那条，删掉就等于允许它悄悄退化回去。
        val url = "https://pastebin.com./raw/abcd"
        assertThat(Hosts.normalise(url)).isEqualTo("pastebin.com")
        assertWithMessage(
            "core:common/Url.host 必须与 :browser 的 Hosts.normalise 同口径 —— " +
                "尾点是精确等值匹配下的一条黑名单绕过路径，两边不许再分叉",
        ).that(com.ake.core.common.Url(url).host).isEqualTo("pastebin.com")

        // ★ 补一道机械锁：这一条现在是「锁修复」，将来谁把 `Url.host` 的实现抄回
        //   `Ids.kt`（而不是转发），上面的值断言**仍然会绿** —— 值一样，但分叉已经发生。
        //   所以必须再钉住「`Ids.kt` 里真的没有管道」这件事。
        assertWithMessage(
            "`Url.host` 的实现必须留在 `Hosts.normalise` —— 值断言无法区分" +
                "「转发」与「又抄了一份等价实现」，而后者会长成分叉",
        ).that(repoSource("core/common/src/main/java/com/ake/core/common/Ids.kt"))
            .doesNotContain(HOST_NORMALISATION_MARKER)
    }

    private companion object {
        /** 归一化管道共有的那一步 —— 用来机械识别「这里在做 host 归一化」。 */
        const val HOST_NORMALISATION_MARKER = "removePrefix(\"www.\")"

        /** 「转发」的指纹：`XXXHosts.normalise(`（容忍 `SharedHosts` 别名与全限定写法）。 */
        const val HOST_FORWARD_CALL = """Hosts\s*\.\s*normalise\s*\("""

        /** 全仓除唯一实现之外的 6 个归一化入口 —— 每一个都必须是转发。 */
        val HOST_NORMALISATION_FORWARDERS: List<Pair<String, String>> = listOf(
            "browser/src/main/java/com/ake/browser/Hosts.kt" to "com.ake.browser.Hosts.normalise",
            "browser/src/main/java/com/ake/browser/DomJsonParser.kt" to "DomJsonParser.hostOf",
            "core/common/src/main/java/com/ake/core/common/Ids.kt" to "Url.host",
            "agent/src/main/java/com/ake/agent/loop/ObservationProjector.kt" to "ObservationProjector.hostOf",
            "agent/src/main/java/com/ake/agent/context/PocketDomSerializer.kt" to "UrlSanitizer.hostOf",
            "ui/src/main/java/com/ake/ui/feature/settings/SettingsViewModel.kt" to "SettingsViewModel.normalizeHost",
        )
    }
}
