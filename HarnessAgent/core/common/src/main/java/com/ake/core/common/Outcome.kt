package com.ake.core.common

/**
 * 全链路唯一的「可能失败的值」类型（架构 §2.2）。
 *
 * 为什么不用 `Result<T>`：`Result` 只能装 `Throwable`，而我们的错误是**带机器可读语义的**
 * `AppError(code / reasonKey / severity)` —— 需要一个稳定 code 去做环比比如 NOT_HIT，
 * 需要一个 `reasonKey` 去做 i18n 与 UI 归因。`Throwable` 串不出这些。
 *
 * 形态严格按 agent 模块 EXPECTED_CORE_API.md §0：
 *  - `Ok(value)` / `Err(error)`
 *  - `map` / `bind`（flatMap 的项目内叫法）/ `getOrElse` / `getOrNull`
 */
public sealed interface Outcome<out T> {

    public data class Ok<T>(val value: T) : Outcome<T>

    public data class Err(val error: AppError) : Outcome<Nothing>

    public companion object {
        public fun <T> ok(value: T): Outcome<T> = Ok(value)
        public fun err(error: AppError): Outcome<Nothing> = Err(error)
    }
}

public inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(f(value))
    is Outcome.Err -> this
}

public inline fun <T, R> Outcome<T>.bind(f: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Ok -> f(value)
    is Outcome.Err -> this
}

public inline fun <T> Outcome<T>.getOrElse(f: (AppError) -> T): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> f(error)
}

public fun <T> Outcome<T>.getOrNull(): T? = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> null
}

// ──────────────────────────────────────────────────────────────
// 补充 combinators（纯增量，不改上面任何一条签名的形态）
// ──────────────────────────────────────────────────────────────

public inline fun <T> Outcome<T>.onOk(action: (T) -> Unit): Outcome<T> = also {
    if (it is Outcome.Ok) action(it.value)
}

public inline fun <T> Outcome<T>.onErr(action: (AppError) -> Unit): Outcome<T> = also {
    if (it is Outcome.Err) action(it.error)
}

public inline fun <T, R> Outcome<T>.fold(onOk: (T) -> R, onErr: (AppError) -> R): R = when (this) {
    is Outcome.Ok -> onOk(value)
    is Outcome.Err -> onErr(error)
}

public fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Err)?.error

/** 失败时返回 `Outcome.Err`，成功时返回 lambda 结果；用于把「可能抛」的代码包成 Outcome。 */
public inline fun <T> runOutcome(block: () -> T): Outcome<T> =
    try {
        Outcome.Ok(block())
    } catch (e: Throwable) {
        Outcome.Err(AppError.wrap(e))
    }

/**
 * 把 `runCatching` 结果换成 `Outcome`。
 *
 * ⚠ **`CancellationException` 必须放行**（架构 §6.2 坑 3）：协程取消是正常控制流，
 * 吞掉它会导致「用户点停止」被当成引擎故障，并破坏结构化并发。
 */
public fun <T> Result<T>.toOutcome(mapError: (Throwable) -> AppError = AppError::wrap): Outcome<T> =
    fold(onSuccess = { Outcome.Ok(it) }, onFailure = { Outcome.Err(mapError(it)) })

/** 失败重映射（保留 cause），用于给底层异常贴上本项目语义 code。 */
public fun <T> Outcome<T>.mapError(f: (AppError) -> AppError): Outcome<T> = when (this) {
    is Outcome.Ok -> this
    is Outcome.Err -> Outcome.Err(f(error))
}
