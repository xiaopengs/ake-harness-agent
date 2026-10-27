package com.ake.core.common

// ===========================================================================
// host 归一化 —— **全仓的公共一份**（★ 这是安全代码，不是格式化代码）
//
// ## 为什么它必须待在 `:core:common`
//
// 归一化在本工程里不是「显示得更整齐」，而是**域名策略的判定前提**：
// 下游 `DomainPolicy` 的匹配全是**精确等值** —— `host == it || host.endsWith(".$it")`，
// 黑名单是「命中即 Denied，不提供确认选项」。于是归一化每少一步，就多一条绕过路径：
//
//   · 少 lowercase         ⇒ `PASTEBIN.com` 绕过黑名单硬拒，退化成「需确认」
//   · 少 去尾点            ⇒ `pastebin.com.` 同理（尾点是合法 FQDN 写法且能解析）
//   · userinfo/port 顺序错 ⇒ `user@host:8443` 整条归一成 `user`
//   · 少 去 query/fragment ⇒ `host?x=1` 被当成 host 的一部分
//
// 依赖方向决定了它只能落在这里：
//   · `:browser` → `:core:common` ✅（已声明）
//   · `:agent`   → `:core:common` ✅（已声明）
//   · `:browser` → `:agent`       ❌ **架构守卫明令禁止**
// 所以 `:agent` 不可能成为共享实现的宿主 —— 放在 `:agent` 就等于逼 `:browser`
// 要么再抄一份、要么加一条被禁的依赖边。`core:common` 是两者唯一共同的可见层，
// 且本模块零依赖（架构 §1.3 R6），放这里不会污染任何引擎的依赖树。
//
// ## 与 `:browser` 那份的关系
//
// 邝随行率先在 `com.ake.browser.Hosts` 收敛出了同一条管道，本文件是它的**下沉版本**，
// 两条管道逐字等价，由 `HostNormalisationEquivalenceTest`（:browser）与
// `HostNormalisationParityTest`（:agent）用同一批畸形输入两边对拍。
// `:browser` 那份应改为转发到本文件 —— 那次改动在 `:browser` 的地盘，不在本次授权内，
// 已作为待办登记。
// ===========================================================================

public object Hosts {

    /**
     * 只认「字母开头 + `://`」的层级 URL。
     *
     * 相对 URL（`example.com/list`）、`about:blank`、`mailto:x@y`、
     * 协议相对 URL（`//cdn.example.com/a`）、opaque（`urn:isbn:...`）
     * **一律没有 host** ⇒ 空串。
     *
     * 归一化不能顺手「发明」出一个 host —— 空串会让下游走「非本域」这条保守分支，
     * 而一个编出来的假 host 会让策略以为自己认得这个域。
     */
    private val HIERARCHICAL_SCHEME: Regex = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")

    /**
     * URL → 裸域名（小写、去 `www.`、去端口、去 userinfo、去尾点）。
     *
     * ★ 去尾点（`shop.example.com.`）不是美化：`www` 后的 FQDN 尾点是合法写法且能解析，
     *   而 `DomainPolicy` 是精确等值匹配 —— `pastebin.com.` 既不等于 `pastebin.com`
     *   也不 endsWith `.pastebin.com` ⇒ **黑名单硬拒被绕过**。
     *
     * ★ 用纯字符串管道而不是 `java.net.URI(url).host`：后者对非 ASCII host（IDN）
     *   返回 null ⇒ 归一成空串，而仓里其余几份产出的是小写后的 Unicode 形式 ——
     *   「同一个 IDN 站点在库里有两个 host」同样会让按 host 的等值查询静默漏行。
     *
     * @return 归一化后的裸域名；无 host 时返回空串（**不会抛**，畸形输入只会退化）。
     */
    public fun normalise(url: String): String {
        val s = url.trim()
        if (!HIERARCHICAL_SCHEME.containsMatchIn(s)) return ""

        // ① 去协议 → ② 去 path / query / fragment
        val authority = s.substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#')

        // ③ 去 userinfo：用**最后一个** `@`（userinfo 里可以再含 `@`，
        //   取第一个会把 `a@b@host` 归成 `b@host`）
        val withoutUserinfo = authority.substringAfterLast('@')

        // ④ 去端口：IPv6 必须用方括号包住才能和端口区分（`[::1]:8080`），
        //    直接 `substringBefore(':')` 会把 `[::1]` 砍成 `[`
        val withoutPort = if (withoutUserinfo.startsWith("[")) {
            withoutUserinfo.substringBefore(']').removePrefix("[")
        } else {
            withoutUserinfo.substringBefore(':')
        }

        // ⑤ 先小写（大小写不敏感地剥前缀）→ ⑥ 去 `www.` → ⑦ 去尾点
        return withoutPort
            .lowercase()
            .removePrefix("www.")
            .removeSuffix(".")
    }
}
