package com.ake.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * R0 脱敏测试（数据库 §8.1 / F3）。
 *
 * 这里的每一条断言都对应一种**一旦漏掉就是事故**的凭据形态。
 * 脱敏代码的特点：正确时没有任何可见效果，错误时也没有任何可见效果 ——
 * 只有断言能证明它还在工作。
 */
class RedactorTest {

    @Test
    fun `redacts JWTs`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1g"
        val out = Redactor.scrub("Authorization: $jwt")
        assertThat(out.text).doesNotContain(jwt)
        assertThat(out.redactionTypes).contains("JWT")
        assertThat(out.redactionCount).isAtLeast(1)
    }

    @Test
    fun `redacts bearer tokens`() {
        val out = Redactor.scrub("Bearer abcdefghijklmnopqrstuvwxyz0123456789")
        assertThat(out.text).doesNotContain("abcdefghijklmnopqrstuvwxyz0123456789")
    }

    @Test
    fun `redacts cookie headers but keeps the key`() {
        val out = Redactor.scrub("Cookie: sessionid=abcdef123456; other=1")
        assertThat(out.text).contains("Cookie")
        assertThat(out.text).doesNotContain("abcdef123456")
    }

    @Test
    fun `redacts password fields`() {
        val out = Redactor.scrub("""{"password":"hunter2hunter2"}""")
        assertThat(out.text).doesNotContain("hunter2hunter2")
    }

    @Test
    fun `redacts credit card numbers that pass Luhn`() {
        // 4242 4242 4242 4242 是标准的 Luhn 通过样例
        val out = Redactor.scrub("card=4242424242424242")
        assertThat(out.text).doesNotContain("4242424242424242")
        assertThat(out.redactionTypes).contains("CREDIT_CARD")
    }

    @Test
    fun `does NOT redact non-Luhn long digits like order numbers`() {
        // 16 位但 Luhn 不通过 ⇒ 是订单号/时间戳，不能被打码（否则任务数据丢失）
        val order = "1234567890123456"
        val out = Redactor.scrub("order=$order")
        assertThat(out.text).contains(order)
    }

    @Test
    fun `redacts private key blocks`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
        val out = Redactor.scrub(pem)
        assertThat(out.text).doesNotContain("PRIVATE KEY")
    }

    @Test
    fun `empty text yields zero redactions`() {
        val out = Redactor.scrub("")
        assertThat(out.redactionCount).isEqualTo(0)
        assertThat(out.redactionTypes).isEmpty()
    }

    @Test
    fun `containsCredential is consistent with redactionCount`() {
        val clean = Redactor.scrub("hello world")
        assertThat(Redactor.containsCredential("hello world")).isEqualTo(clean.redactionCount > 0)

        val dirty = Redactor.scrub("token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcd1234efgh")
        assertThat(dirty.redactionCount).isGreaterThan(0)
    }
}
