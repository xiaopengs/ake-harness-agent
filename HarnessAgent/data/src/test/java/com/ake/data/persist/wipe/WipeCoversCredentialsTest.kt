package com.ake.data.persist.wipe

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ★ 「一键清除全部痕迹」必须覆盖 `cloud_credential`（docs/09 §4.4）。
 *
 * ## 为什么这里是**源码序断言**而不是行为断言
 *
 * `WipeEverythingUseCase.invoke()` 第一步就要 `Context.deleteDatabase(...)`
 * ⇒ JVM 单测里构造不了它（android.jar 的 `Context` 在 unit test 下是 stub）。
 * 于是退一步，断言**两件事的先后顺序**：凭证的 `purgeAll()` 必须排在删库**之前**。
 *
 * ⚠ 这是**弱断言**（它验的是源码文本，不是执行）。它存在的理由是：
 *   顺序本身就是设计意图（万一删库失败，密文也不能留在原库里），
 *   而顺序一旦被挪动，行为测试也未必会红 ⇒ 用一条便宜的断言把它钉住。
 *   真正的执行验证留给真机 L3（`docs/07` 的 M5 已能拉库检查）。
 */
class WipeCoversCredentialsTest {

    private fun src(): String {
        val f = File("src/main/java/com/ake/data/persist/wipe/WipeEverythingUseCase.kt").takeIf { it.isFile }
            ?: File("data/src/main/java/com/ake/data/persist/wipe/WipeEverythingUseCase.kt")
        assertThat(f.isFile).isTrue()
        return f.readText()
    }

    @Test
    fun wipePurgedCloudCredentialsBeforeDeletingTheDatabase() {
        val text = src()
        val purge = text.indexOf("credentials.purgeAll()")
        val dropDb = text.indexOf("context.deleteDatabase")
        assertThat(purge).isGreaterThan(0)
        assertThat(dropDb).isGreaterThan(0)
        // ★ 顺序：先粉碎密文，再删库文件（库删不掉时密文也不能留）
        assertThat(purge).isLessThan(dropDb)
    }

    /** ★ 凭证清除失败必须**进 failures 清单**，而不是被静默吞掉。 */
    @Test
    fun credentialPurgeFailureIsRecordedNotSwallowed() {
        val text = src()
        assertThat(text).contains("step(\"cloud_credential.purgeAll\"")
    }
}
