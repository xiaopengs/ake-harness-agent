package com.ake.core.common

/**
 * 错误抽象。刻意用 **`code` + `reasonKey`** 两个维度的字符串，而不是异常类名：
 *
 *  - `code`：机器可读。UI / 遥测 / 重试策略 / 单元测试都用它做判断，
 *    并且它是**跨 Release 稳定的契约**（可以重构异常类，但不能悄悄换 code）。
 *  - `reasonKey`：人类可读文案的 key。放在这里是为了**不让底层能力拼中文给用户看** ——
 *    文案是 UI 层的职责（架构 R5：能力层不许知道怎么显示）。
 *  - `cause`：原始异常，只在进程内保留，不进日志也不进 UI。
 *
 * ⚠ 注意：它是 `data class` 的构造参数，**因此参与 `equals`**。
 * 断言两条错误「语义相同」时请用 `code` + `reasonKey` 比较，不要直接比较整个对象。
 */
public data class AppError(
    val code: String,
    val reasonKey: String,
    val cause: Throwable? = null,
    val severity: Severity = Severity.LOW,
) {

    public companion object {

        public const val CODE_UNKNOWN: String = "UNKNOWN"
        public const val CODE_INTERRUPTED: String = "INTERRUPTED"
        public const val CODE_TIMEOUT: String = "TIMEOUT"
        public const val CODE_INVALID_INPUT: String = "INVALID_INPUT"
        public const val CODE_UNSUPPORTED: String = "UNSUPPORTED"
        public const val CODE_IO: String = "IO"
        public const val CODE_PERMISSION: String = "PERMISSION"

        public fun wrap(cause: Throwable): AppError = AppError(
            code = CODE_UNKNOWN,
            reasonKey = "err_unknown",
            cause = cause,
            severity = Severity.MEDIUM,
        )

        public fun unknown(reasonKey: String = "err_unknown"): AppError =
            AppError(code = CODE_UNKNOWN, reasonKey = reasonKey)

        public fun timeout(what: String): AppError =
            AppError(code = CODE_TIMEOUT, reasonKey = "err_timeout", severity = Severity.MEDIUM)

        public fun interrupted(): AppError =
            AppError(code = CODE_INTERRUPTED, reasonKey = "err_interrupted", severity = Severity.LOW)

        public fun unsupported(what: String): AppError =
            AppError(code = CODE_UNSUPPORTED, reasonKey = "err_unsupported", severity = Severity.MEDIUM)

        public fun invalidInput(what: String): AppError =
            AppError(code = CODE_INVALID_INPUT, reasonKey = "err_invalid_input", severity = Severity.LOW)
    }
}

/**
 * 严重度。它决定的是一个错误**值不值得打断用户**，而不是它「严不严重」：
 *  - LOW      ：内部重试即可，用户不需要知道（如一次 calculate javascript 超时）
 *  - MEDIUM   ：需要写 trace 并在 UI 上聚合显示（如一帧 DOM 抽取降级）
 *  - HIGH     ：需要中断当前步骤并向用户解释（如模型加载失败）
 *  - CRITICAL ：安全/隐私相关，必须立刻停止任务并给出明确提示（如检测到注入攻击 succeeded）
 */
public enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }
