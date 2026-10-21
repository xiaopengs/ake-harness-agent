package com.ake.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `Outcome` 与 `AppError` 的契约测试。
 *
 * 为什么值得为这么小的东西写测试：整个项目**没有任何一处抛异常向上冒** ——
 * 全靠 `Outcome` 承载失败。它一旦行为漂移（比如 `map` 吞了 Err，或 `bind` 短路失效），
 * 症状会是「某些错误被静默忽略」，而这类 bug 在真机上几乎不可能靠肉眼发现。
 */
class OutcomeTest {

    @Test
    fun `map only transforms Ok`() {
        val ok: Outcome<Int> = Outcome.Ok(2)
        assertThat(ok.map { it * 3 }).isEqualTo(Outcome.Ok(6))

        val err: Outcome<Int> = Outcome.Err(AppError("X", "x"))
        assertThat(err.map { it * 3 }).isSameInstanceAs(err)
    }

    @Test
    fun `bind short-circuits on Err and does not call the lambda`() {
        val failure = AppError("E_STALE", "err_element_stale")
        var called = false

        val result: Outcome<String> = Outcome.Err(failure)
            .bind<Int, String> { called = true; Outcome.Ok("never") }

        assertThat(called).isFalse()
        assertThat(result).isEqualTo(Outcome.Err(failure))
    }

    @Test
    fun `bind propagates the inner failure`() {
        val inner = AppError("E_TIMEOUT", "err_timeout")
        val result = Outcome.Ok(1).bind<Int, Int> { Outcome.Err(inner) }
        assertThat(result).isEqualTo(Outcome.Err(inner))
    }

    @Test
    fun `getOrElse and getOrNull`() {
        val err: Outcome<Int> = Outcome.Err(AppError("E", "e"))
        assertThat(err.getOrElse { 42 }).isEqualTo(42)
        assertThat(err.getOrNull()).isNull()

        val ok: Outcome<Int> = Outcome.Ok(7)
        assertThat(ok.getOrElse { 42 }).isEqualTo(7)
        assertThat(ok.getOrNull()).isEqualTo(7)
    }

    @Test
    fun `chained map and bind behave like a monad`() {
        val result = Outcome.Ok(10)
            .map { it + 5 }                        // 15
            .bind { v -> Outcome.Ok(v * 2) }        // 30
            .map { "v=$it" }

        assertThat(result).isEqualTo(Outcome.Ok("v=30"))
    }

    @Test
    fun `Err is covariant to any Outcome`() {
        val e: Outcome<Nothing> = Outcome.Err(AppError("E", "e"))
        val widened: Outcome<String> = e
        assertThat(widened.getOrNull()).isNull()
    }
}

class AppErrorTest {

    @Test
    fun `defaults to LOW severity and null cause`() {
        val e = AppError(code = "E_X", reasonKey = "err_x")
        assertThat(e.severity).isEqualTo(Severity.LOW)
        assertThat(e.cause).isNull()
    }

    @Test
    fun `wrap preserves cause`() {
        val boom = RuntimeException("native boom")
        val e = AppError.wrap(boom)
        assertThat(e.cause).isSameInstanceAs(boom)
        assertThat(e.code).isEqualTo(AppError.CODE_UNKNOWN)
    }

    @Test
    fun `same code and reasonKey are equal when no cause is attached`() {
        val a = AppError("E", "r")
        val b = AppError("E", "r")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `different causes are not equal - compare code and reasonKey instead`() {
        val a = AppError("E", "r", RuntimeException("1"))
        val b = AppError("E", "r", RuntimeException("2"))
        assertThat(a).isNotEqualTo(b)
        // 断言「同一类错误」时应比较语义字段，而不是整个对象
        assertThat(a.code == b.code && a.reasonKey == b.reasonKey).isTrue()
    }
}
