package com.ake.browser

/**
 * `String` → JS 字符串字面量。
 *
 * 抽取脚本里到处都要把 selector 拼进 JS，转义必须**只在一处**实现：
 * 手写转义漏掉反斜杠时，轻则该选择器静默失配（表现为「点了没反应」），
 * 重则构造出可执行的注入片段 —— 这是本模块里少数会直接变成安全问题的地方。
 *
 * ★ **JS 的行终止符有四个，不只是 `\n`**：`\n` U+000A、`\r` U+000D、
 *   U+2028（LINE SEPARATOR）、U+2029（PARAGRAPH SEPARATOR）。
 *   后三个在**单引号字符串字面量里是非法的**（`StringLiteral` 不允许 LineTerminator）。
 *   历史实现只转义了 `\n` ⇒ 属性里带 CR / U+2028 时拼出的脚本是**语法错误**
 *   ⇒ 整个注入脚本一行都跑不了 ⇒ bridge 超时 ⇒ 空观察。
 *   selector 是从页面上反推出来的，属性值里出现 CR / U+2028 不是不可能，是「迟早」。
 *
 * 其余控制字符（U+0000–U+001F）同样转义，避免 `</script>` 之外的边界情况。
 */
public fun String.js(): String = "'" +
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029") + "'"
