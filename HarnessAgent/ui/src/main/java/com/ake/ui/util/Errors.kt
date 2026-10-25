package com.ake.ui.util

import com.ake.core.common.AppError

/**
 * 把失败翻成一句能给用户看的话。
 *
 * ## 为什么不直接 `e.message`
 *
 * `AppError.reasonKey` 是**稳定的可判定键**（`err_template_not_found` 这类），
 * 而 `Throwable.message` 经常是 Room / SQLite 的英文长句，甚至带路径。
 * 前者可以被翻译、被检索、被统计；后者只会在屏幕上吓人。
 * 所以顺序是：AppError 的 reasonKey → 非空 message → 类名。
 *
 * 类名兜底是有意保留的「至少不是空白」：读不出来时给用户一个能截图给我们的东西，
 * 比显示"未知错误"有用。
 */
public fun readableError(e: AppError): String = e.reasonKey

public fun readableError(t: Throwable): String {
    val msg = t.message
    if (!msg.isNullOrBlank()) return msg
    return t::class.java.simpleName.substringAfterLast('.')
}
