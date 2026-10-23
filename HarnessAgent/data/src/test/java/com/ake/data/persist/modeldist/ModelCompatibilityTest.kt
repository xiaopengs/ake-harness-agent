package com.ake.data.persist.modeldist

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 兼容矩阵的边界值。
 *
 * ★ 这四条边界是本文件存在的全部理由：`== min` / `== max` / `min-1` / `max+1`。
 * 含端点（inclusive）是刻意的选择 —— 运维写 `max=199` 的意思是「199 及以下是安全的」。
 * 这里一旦差一位，后果不是报错，而是**整批设备被静默判定为不可用**，
 * 而配 manifest 的人在那边看到的只是一个数字。
 */
@RunWith(JUnit4::class)
class ModelCompatibilityTest {

    private fun verdict(appCode: Int, min: Int, max: Int?): CompatVerdict =
        ModelCompatibility.evaluate(ModelDistFixtures.entry(minApp = min, maxApp = max), appCode)

    @Test
    fun `恰好等于 min 视为兼容`() {
        assertThat(verdict(appCode = 100, min = 100, max = 199)).isInstanceOf(CompatVerdict.Compatible::class.java)
    }

    @Test
    fun `恰好等于 max 视为兼容`() {
        assertThat(verdict(appCode = 199, min = 100, max = 199)).isInstanceOf(CompatVerdict.Compatible::class.java)
    }

    @Test
    fun `小于 min 判为 AppTooOld`() {
        val v = verdict(appCode = 99, min = 100, max = 199)
        assertThat(v).isInstanceOf(CompatVerdict.AppTooOld::class.java)
        assertThat((v as CompatVerdict.AppTooOld).minAppVersionCode).isEqualTo(100)
        assertThat(v.appVersionCode).isEqualTo(99)
    }

    @Test
    fun `大于 max 判为 AppTooNew`() {
        val v = verdict(appCode = 200, min = 100, max = 199)
        assertThat(v).isInstanceOf(CompatVerdict.AppTooNew::class.java)
        assertThat((v as CompatVerdict.AppTooNew).maxAppVersionCode).isEqualTo(199)
    }

    @Test
    fun `max 为 null 时不封顶`() {
        assertThat(verdict(appCode = Int.MAX_VALUE, min = 1, max = null))
            .isInstanceOf(CompatVerdict.Compatible::class.java)
    }

    @Test
    fun `min 与 max 相等时只有那一个版本可用`() {
        assertThat(verdict(appCode = 150, min = 150, max = 150)).isInstanceOf(CompatVerdict.Compatible::class.java)
        assertThat(verdict(appCode = 149, min = 150, max = 150)).isInstanceOf(CompatVerdict.AppTooOld::class.java)
        assertThat(verdict(appCode = 151, min = 150, max = 150)).isInstanceOf(CompatVerdict.AppTooNew::class.java)
    }

    @Test
    fun `区间内的每个值都兼容`() {
        for (code in 100..199) {
            assertThat(verdict(appCode = code, min = 100, max = 199))
                .isInstanceOf(CompatVerdict.Compatible::class.java)
        }
    }

    @Test
    fun `DEPRECATED 与 REVOKED 不作为新装目标`() {
        val deprecated = ModelCompatibility.evaluate(
            ModelDistFixtures.entry(state = ModelEntryState.DEPRECATED),
            150,
        )
        val revoked = ModelCompatibility.evaluate(
            ModelDistFixtures.entry(state = ModelEntryState.REVOKED),
            150,
        )
        assertThat(deprecated).isInstanceOf(CompatVerdict.EntryNotActive::class.java)
        assertThat((revoked as CompatVerdict.EntryNotActive).state).isEqualTo(ModelEntryState.REVOKED)
    }

    @Test
    fun `reasonKey 可用于 UI 归因`() {
        assertThat(ModelCompatibility.reasonKeyOf(CompatVerdict.Compatible)).isEqualTo("err_none")
        assertThat(ModelCompatibility.reasonKeyOf(verdict(99, 100, 199))).isEqualTo("err_model_app_too_old")
        assertThat(ModelCompatibility.reasonKeyOf(verdict(200, 100, 199))).isEqualTo("err_model_app_too_new")
    }

    @Test
    fun `isUsable 只认 Compatible`() {
        assertThat(ModelCompatibility.isUsable(verdict(150, 100, 199))).isTrue()
        assertThat(ModelCompatibility.isUsable(verdict(99, 100, 199))).isFalse()
        assertThat(ModelCompatibility.isUsable(verdict(200, 100, 199))).isFalse()
    }
}
