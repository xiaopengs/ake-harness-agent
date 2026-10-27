package com.ake.core.common

/**
 * 跨层共享的标量 ID。
 *
 * 放在 `common`（L1 原语）而不是 `contract`（L2），是为了让 L1 的 `ModelPort` /
 * `ModelSession` 能引用 `StepId` / `SessionId`，而**不必反向依赖 L2**。
 * `agent` / `mcp` / `data` 若历史上按 `com.ake.core.contract.TaskId` 之类引用，
 * `contract` 里备好了同名 typealias，两条 import 路径都指向同一个类，不会产生双份类型。
 *
 * 全部用 `value class`：
 *  - 运行期零开销（内联为 String），但它让 `TaskId` 与 `StepId` **不可互相赋值** ——
 *    这类「把 taskId 当 stepId 传」的错误在追加写 + 不可变 id 的系统里是灾难性的。
 */
@JvmInline
public value class TaskId(public val value: String) {
    public companion object {
        public fun new(): TaskId = TaskId(Ulid.next())
    }
}

@JvmInline
public value class StepId(public val value: String) {
    public companion object {
        public fun new(): StepId = StepId(Ulid.next())
    }
}

@JvmInline
public value class SessionId(public val value: String) {
    public companion object {
        public fun new(): SessionId = SessionId(Ulid.next())
    }
}

/**
 * URL。包装而不是裸 String，是为了把「host 归一化」这件事收敛到一处
 * （数据库 §7.4：域名策略要求 host 精确等值查询，裸 String 会让大小写 / www 前缀漏进来）。
 */
@JvmInline
public value class Url(public val value: String) {
    /**
     * 裸域名：小写、去 `www.`、去端口、去 userinfo、去尾点、IDN 不塌成空串。
     *
     * ★ 实现**不在本文件** —— 转发到 [Hosts.normalise]（全仓的公共一份）。
     *   这里曾经有一份自己的字符串管道，它漏掉了「去尾点」，于是 `pastebin.com.`
     *   在 `DomainPolicy` 的精确等值匹配下既不等于也不 endsWith `pastebin.com`
     *   ⇒ **黑名单硬拒被绕过**，退化成「需确认」。
     *   「同一个类里自己再写一遍」正是这个 bug 能活下来的原因 —— 现在它不存在了。
     *
     * 与 `:agent` 的 `ObservationProjector.hostOf` / `UrlSanitizer.hostOf`、
     * `:browser` 的 `com.ake.browser.Hosts.normalise` 逐字等价（有测试对拍）。
     *
     * 相对 URL / opaque URL **没有 host** ⇒ 空串（不会「发明」出一个域）。
     */
    public val host: String get() = Hosts.normalise(value)

    public val isHttps: Boolean get() = value.startsWith("https://", ignoreCase = true)
}
