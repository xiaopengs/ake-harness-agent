package com.ake.data.persist.modeldist

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 校验判定与失败处置。
 *
 * 核心一条：**摘要不匹配 ⇒ 丢弃 `.part` 从头再下，绝不续传**。
 * 续传的前提是「已落盘的字节是对的」，摘要错了说明前缀里已经有坏字节，
 * 而 HTTP Range 不会回头修前面的错 —— 接着传只是把错误固化成 2.6 GB。
 */
@RunWith(JUnit4::class)
class ModelVerifyGateTest {

    private val entry = ModelDistFixtures.entry(sizeBytes = 1000L, sha256 = ModelDistFixtures.sha("a"))

    @Test
    fun `字节数与摘要都对判为 MATCH`() {
        assertThat(ModelVerifyGate.evaluate(entry, 1000L, entry.sha256)).isEqualTo(VerifyVerdict.MATCH)
    }

    @Test
    fun `摘要大小写不同也算 MATCH`() {
        assertThat(ModelVerifyGate.evaluate(entry, 1000L, entry.sha256.uppercase())).isEqualTo(VerifyVerdict.MATCH)
    }

    @Test
    fun `字节数不对判为 SIZE_MISMATCH`() {
        assertThat(ModelVerifyGate.evaluate(entry, 999L, entry.sha256)).isEqualTo(VerifyVerdict.SIZE_MISMATCH)
        assertThat(ModelVerifyGate.evaluate(entry, 1001L, entry.sha256)).isEqualTo(VerifyVerdict.SIZE_MISMATCH)
    }

    @Test
    fun `字节数对但摘要不对判为 HASH_MISMATCH`() {
        assertThat(ModelVerifyGate.evaluate(entry, 1000L, "b".repeat(64))).isEqualTo(VerifyVerdict.HASH_MISMATCH)
    }

    @Test
    fun `没有文件或没算出摘要判为 FILE_MISSING 或 HASH_MISMATCH`() {
        assertThat(ModelVerifyGate.evaluate(entry, null, null)).isEqualTo(VerifyVerdict.FILE_MISSING)
        assertThat(ModelVerifyGate.evaluate(entry, 1000L, null)).isEqualTo(VerifyVerdict.HASH_MISMATCH)
    }

    @Test
    fun `校验通过直接转正不需要任何文件动作`() {
        assertThat(ModelVerifyGate.nextAction(VerifyVerdict.MATCH, 1, 3)).isEqualTo(VerifyAction.PROMOTE)
    }

    @Test
    fun `校验失败在次数未耗尽时重下`() {
        assertThat(ModelVerifyGate.nextAction(VerifyVerdict.HASH_MISMATCH, 1, 3))
            .isEqualTo(VerifyAction.DISCARD_AND_RESTART)
        assertThat(ModelVerifyGate.nextAction(VerifyVerdict.SIZE_MISMATCH, 2, 3))
            .isEqualTo(VerifyAction.DISCARD_AND_RESTART)
    }

    @Test
    fun `校验失败在次数耗尽后放弃`() {
        assertThat(ModelVerifyGate.nextAction(VerifyVerdict.HASH_MISMATCH, 3, 3)).isEqualTo(VerifyAction.GIVE_UP)
        assertThat(ModelVerifyGate.nextAction(VerifyVerdict.HASH_MISMATCH, 4, 3)).isEqualTo(VerifyAction.GIVE_UP)
    }

    @Test
    fun `文件缺失时从头再来而不是放弃`() {
        assertThat(ModelVerifyGate.nextAction(VerifyVerdict.FILE_MISSING, 3, 3))
            .isEqualTo(VerifyAction.DISCARD_AND_RESTART)
    }

    @Test
    fun `sha256 必须是 64 位十六进制`() {
        assertThat(ModelVerifyGate.isSha256Hex("a".repeat(64))).isTrue()
        assertThat(ModelVerifyGate.isSha256Hex("A".repeat(64))).isTrue()
        assertThat(ModelVerifyGate.isSha256Hex("a".repeat(63))).isFalse()
        assertThat(ModelVerifyGate.isSha256Hex("z".repeat(64))).isFalse()
    }
}
