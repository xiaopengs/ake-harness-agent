package com.ake.browser

/**
 * `String` → JS 字符串字面量。
 *
 * 抽取脚本里到处都要把 selector 拼进 JS，转义必须**只在一处**实现：
 * 手写转义漏掉反斜杠时，轻则该选择器静默失配（表现为「点了没反应」），
 * 重则构造出可执行的注入片段 —— 这是本模块里少数会直接变成安全问题的地方。
 */
public fun String.js(): String = "'" +
    replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
