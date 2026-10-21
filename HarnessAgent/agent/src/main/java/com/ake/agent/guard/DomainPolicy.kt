package com.ake.agent.guard

import com.ake.core.common.Url

/**
 * 域名白/黑名单策略（架构 §8.4 `DomainPolicyStore` 一侧）。
 *
 * ## 为什么做成不可变 data class 而不是一个 Store 接口
 *
 * 存储侧（`data/`）归属我，策略侧（`agent/`）也归属我 —— 但**策略侧不能依赖存储侧**
 * （L4 不许反向依赖 L3 的具体实现，也不许直接碰 Room）。
 * 所以这里只冻结**判定规则**，配置由组合根（`:app`）注入一个不可变快照。
 *
 * 这个形态还有个额外好处：`DomainPolicy` 可以在 JVM 上直接单测，
 * 不需要 Robolectric —— 域名策略是**安全代码**，它必须能在毫秒级回归。
 *
 * ## 三条判定规则（顺序即优先级）
 *
 *  1. **黑名单硬拒**：命中即 `Denied`，不提供确认选项。
 *  2. **白名单优先**：任务声明了 `allowHosts` 时，白名单内直接放行（省掉一次确认打扰）。
 *     空集表示「不限」，交给下面的跨域名确认兜底。
 *  3. **跨域名需确认**：首次离开「当前域名 / 起始域名 / 白名单」时 `RequiresConfirm`。
 *
 * ## host 归一化（数据库 §7.4）
 *
 * 一律用 [Url.host]（小写、去 `www.`、去端口、去 userinfo）。
 * **域名策略绝不能用裸 String 比较** —— `Example.com` 与 `www.example.com`
 * 在裸比较下是两个域名，那正是攻击者绕过白名单的入口。
 */
data class DomainPolicy(
    /** 永久黑名单（用户可增，内置部分不可删）。裸域名，已归一化。 */
    val blacklist: Set<String> = DEFAULT_BLACKLIST,
    /** 全局白名单。非空时作为「额外放行」，不替代黑名单。 */
    val allowlist: Set<String> = emptySet(),
    /** 是否允许非 https。MVP 默认 false —— 端侧 Agent 在登录态下操作，明文 http 不可接受。 */
    val requireHttps: Boolean = true,
) {

    fun blacklistHit(host: String): String? = blacklist.firstOrNull { it == host || host.endsWith(".$it") }

    fun isAllowlisted(host: String): Boolean =
        allowlist.any { it == host || host.endsWith(".$it") }

    /**
     * 该 host 是否在本次任务的会话作用域内（§8.4 `isInSessionScope`）。
     *
     * 会话作用域 = 任务声明的 `allowHosts` ∪ 起始 URL 的 host ∪ 当前 URL 的 host。
     * **不含「曾经访问过的所有 host」** —— 否则一次误点就把攻击者的域名永久纳入作用域，
     * 这被称为「作用域爬升」，是本类策略最经典的失效方式。
     */
    fun isInSessionScope(host: String, allowHosts: Set<String>, currentHost: String?, startHost: String?): Boolean {
        if (host == currentHost || host == startHost) return true
        return allowHosts.any { host == it || host.endsWith(".$it") }
    }

    fun checkScheme(url: Url): Boolean = !requireHttps || url.isHttps

    companion object {

        /**
         * 内置黑名单（架构 §8.4 的形态；MVP 为静态集合）。
         *
         * 刻意**不内置任何具体站点** —— 一个端侧 App 硬编码竞品域名会立刻变成公关事件。
         * 这里放的是**形态**而非名单：常见的短链/跳转/ Pastebin 类域名形态，
         * 它们在注入攻击里的作用是「把带凭据的 URL 送到攻击者手上」（T3）。
         * 具体站点由用户在设置里维护，落在 `data/` 的 Proto DataStore。
         */
        val DEFAULT_BLACKLIST: Set<String> = setOf(
            "pastebin.com",
            "pastebin.pl",
            "hastebin.com",
            "ghostbin.com",
            "dpaste.org",
            "justpaste.it",
            "controlc.com",
            "termbin.com",
            "ix.io",
            "0x0.st",
            "transfer.sh",
        )

        /** 明显的 URL 缩短器：导航到它们等于「目的地未知」。 */
        val URL_SHORTENERS: Set<String> = setOf(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly",
            "buff.ly", "cutt.ly", "rebrand.ly", "shorturl.at", "s.id",
        )

        val DEFAULT: DomainPolicy = DomainPolicy()
    }
}
