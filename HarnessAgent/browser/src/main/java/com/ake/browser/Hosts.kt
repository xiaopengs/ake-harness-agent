package com.ake.browser

import com.ake.core.common.Hosts as SharedHosts

// ===========================================================================
// host 归一化 —— `:browser` 这一侧**不再是实现，只是转发**
//
// ## 这一轮改了什么（2026-10 · 邝随行）
//
// 本文件原本是 `:browser` 自己那份完整管道（去协议 → 去 path/query/fragment →
// 去 userinfo → 去端口 → 小写 → 去 `www.` 前缀 → 去尾点）。霍承钧把它下沉到了
// `com.ake.core.common.Hosts`，并把 `:core:common` 的 `Url.host`、`:agent` 的
// `ObservationProjector.hostOf` / `UrlSanitizer.hostOf` 改成转发；苏映棠把 `:ui`
// 的录入侧 `SettingsViewModel.normalizeHost` 也收敛成转发。
//
// ★ 授权已到位：**全仓只剩一份实现**，就是 `com.ake.core.common.Hosts`。
//   本文件现在只有一行转发，管道一个字符都不在这里重算。
//
// ## 为什么「转发」这一步必须做，而不是「留着一份等价实现」
//
// 归一化在本工程里是**安全代码**，不是格式化代码：下游 `DomainPolicy` 的匹配全是
// **精确等值**（`host == it || host.endsWith(".$it")`），黑名单是「命中即 Denied，
// 不提供确认选项」。于是归一化每少一步就多一条绕过路径：
//
//   · 少 lowercase         ⇒ `PASTEBIN.com` 绕过黑名单硬拒，退化成「需确认」
//   · 少 去尾点            ⇒ `pastebin.com.` 同理（尾点是合法 FQDN 写法且能解析）
//   · userinfo/port 顺序错 ⇒ `user@host:8443` 整条归一成 `user`
//   · 少 去 query/fragment ⇒ `host?x=1` 被当成 host 的一部分
//
// 「两份等价实现」的结局是可预见的：有人只给其中一份补一步（比如某天发现还要处理
// 双尾点），另一份立刻变成一条**还在生效的绕过路径**，而且没人看得见 ——
// 因为每一份单看都「对自己的测试全绿」。
//
// 所以这里的立场是：**实现的份数要被机械地盯住**，而不是靠注释担保。
// `HostNormalisationTest` 现在扫全仓 `src/main`，断言：
//
//   · 含归一化管道（指标：「去 `www.` 前缀」那一步）的文件 `containsExactly` 地只剩 1 个；
//   · 其余全部登记在转发清单里，且每一个都真的调用 `Hosts.normalise` 而不留管道。
//
// 谁再抄一份，那条清单立刻红 —— 而不是等某天有人用大写域名绕过黑名单才发现。
//
// 口径与失效方式都写在 `@see` 的那一份里，本文件不复述（复述 = 再维护一份注释，
// 下一次改口径时它必然漂移）。
// ===========================================================================

public object Hosts {

    /**
     * URL → 裸域名（小写、去 `www.`、去端口、去 userinfo、去尾点）。
     *
     * ★ **实现不在这里** —— 转发到 [SharedHosts.normalise]，那是全仓唯一的一份。
     *   `:browser` → `:core:common` 这条依赖边是已声明且被 `checkModuleDependencies` 守住的方向。
     *
     * 无 host 时返回空串（相对 URL / `about:blank` / opaque / 协议相对 URL 一律没有 host），
     * **不抛异常**，畸形输入只会退化。
     */
    public fun normalise(url: String): String = SharedHosts.normalise(url)
}
