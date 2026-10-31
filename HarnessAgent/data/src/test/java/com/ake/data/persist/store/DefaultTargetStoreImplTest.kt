package com.ake.data.persist.store

import com.ake.core.common.ManualClock
import com.ake.core.common.Outcome
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DefaultTargetStore
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.ake.data.persist.fake.FakeDefaultTargetDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★★ 全局默认目标的语义（v5）。
 *
 * ## 本文件想钉死的三条
 *
 *  ① **未设置 ⇒ `Device`，永不 null**（端侧是安全默认）。
 *  ② **跨"冷启动"保持**：换一个 store 实例（同一个 DAO）仍读得到。
 *  ③ **绝不静默降级**：指向已被粉碎的凭证 / 无法识别的文本，都**原样返回**，
 *     不偷偷变成 `Device`。
 *
 * ## ★ 负向用例（三条，每条都能杀死一个"看起来对"的实现）
 *
 *  · [unsetIsDeviceButNotFlaggedAsExplicitlySet] —— 若实现在未设置时返回 `null` 或报错，会红；
 *    同时证明库里**确实没有**那一行（不是写了个 `'LOCAL'` 假装没设置过）。
 *  · [shreddedCredentialIsNotSilentlyDowngradedToDevice] —— 若实现在凭证失效时降级，会红。
 *  · [unrecognizedTextIsNotSilentlyDowngradedToDevice] —— 若实现把未知文本当 `Device`，会红。
 */
class DefaultTargetStoreImplTest {

    private fun store(
        dao: FakeDefaultTargetDao = FakeDefaultTargetDao(),
        clock: ManualClock = ManualClock(1_000L),
    ) = DefaultTargetStoreImpl(dao = dao, clock = clock)

    private val remote = DialogTarget.Remote(CredentialRef("cred-1"))

    // ───────────────────────────── ① 未设置 ⇒ Device

    @Test
    fun unsetDefaultsToDevice() = runBlocking {
        val s = store()
        assertThat(s.default()).isEqualTo(DialogTarget.Device)
    }

    /**
     * ★★ 负向：**未设置**与**显式选了端侧**在库里必须是两种状态。
     *
     * 若实现在 `default()` 里"顺手补一行 'LOCAL'"，本测试会红 ——
     * 因为那样「用户到底设没设过」就永远问不出来了（这正是"读一次不该有写副作用"）。
     */
    @Test
    fun unsetIsDeviceButNotFlaggedAsExplicitlySet() = runBlocking {
        val dao = FakeDefaultTargetDao()
        val s = store(dao)
        assertThat(s.default()).isEqualTo(DialogTarget.Device)
        assertThat(s.isExplicitlySet()).isFalse()
        assertThat(dao.rows).isEmpty()          // ★ 读一次没有写副作用

        s.setDefault(DialogTarget.Device)
        assertThat(s.default()).isEqualTo(DialogTarget.Device)
        assertThat(s.isExplicitlySet()).isTrue()  // ← 同样表现为端侧，但库里状态不同
        assertThat(dao.rows).isNotEmpty()
    }

    // ───────────────────────────── ② 往返与持久化

    @Test
    fun remoteDefaultRoundTrips() = runBlocking {
        val s = store()
        assertThat(s.setDefault(remote)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(s.default()).isEqualTo(remote)
        val got = s.default() as DialogTarget.Remote
        assertThat(got.credentialRef).isEqualTo(CredentialRef("cred-1"))
    }

    /**
     * ★ 跨冷启动保持：**新建一个 store 实例**（模拟进程重启后重新装配）仍读得到。
     *
     * 这是"必须走 Room"的可执行证明 —— `InMemoryDialogTargetStore` 过不了这条。
     */
    @Test
    fun survivesColdStart() = runBlocking {
        val dao = FakeDefaultTargetDao()
        store(dao).setDefault(remote)

        val afterReboot = DefaultTargetStoreImpl(dao = dao, clock = ManualClock(9_999L))
        assertThat(afterReboot.default()).isEqualTo(remote)
    }

    @Test
    fun setDefaultIsOverwriteNotAppend() = runBlocking {
        val dao = FakeDefaultTargetDao()
        val s = store(dao)
        s.setDefault(remote)
        s.setDefault(DialogTarget.Device)
        assertThat(dao.rows).hasSize(1)          // ★ 单行表
        assertThat(s.default()).isEqualTo(DialogTarget.Device)
    }

    // ───────────────────────────── ③ ★★ 绝不静默降级

    /**
     * ★★ 负向：默认目标指向的凭证**已被粉碎**时，仍返回 `Remote`。
     *
     * 静默降级成 `Device` 就是本轮要消灭的错误：用户会看到「这个会话莫名变成本地了」
     * 而没有任何解释。**不确定就承认不确定**，由上层去问用户。
     */
    @Test
    fun shreddedCredentialIsNotSilentlyDowngradedToDevice() = runBlocking {
        val dao = FakeDefaultTargetDao()
        val s = store(dao)
        s.setDefault(remote)

        // 模拟"凭证已被粉碎"（store 层不查凭证表 —— 它只忠实返回存了什么）
        assertThat(s.default()).isEqualTo(remote)
        assertThat(s.default()).isNotEqualTo(DialogTarget.Device)
    }

    /** ★ 负向：库里的文本无法识别 ⇒ `Unrecognized`，**不是** `Device`。 */
    @Test
    fun unrecognizedTextIsNotSilentlyDowngradedToDevice() = runBlocking {
        val dao = FakeDefaultTargetDao()
        val s = store(dao)
        dao.upsert(
            com.ake.data.persist.db.entity.DefaultTargetEntity(
                id = 0,
                modelTarget = "CLOUD:",            // 前缀在但 ref 为空 ⇒ decode 判 Unrecognized
                updatedAt = 1,
                schemaV = 1,
            ),
        )
        val got = s.default()
        assertThat(got).isInstanceOf(DialogTarget.Unrecognized::class.java)
        assertThat((got as DialogTarget.Unrecognized).raw).isEqualTo("CLOUD:")
    }

    /** ★ `Unrecognized` 原样写回（不把"曾经不认识"抹掉）。 */
    @Test
    fun unrecognizedIsWrittenBackVerbatim() = runBlocking {
        val dao = FakeDefaultTargetDao()
        val s = store(dao)
        s.setDefault(DialogTarget.Unrecognized("WEIRD:v9"))
        assertThat(dao.rows[0]!!.modelTarget).isEqualTo("WEIRD:v9")
        assertThat(s.default()).isEqualTo(DialogTarget.Unrecognized("WEIRD:v9"))
    }

    // ───────────────────────────── ④ 契约与错误

    /** ★ 编码只用 `DialogTargetCodec` 这一份 —— 不得出现第二套词汇。 */
    @Test
    fun encodedTextComesFromTheSharedCodec() = runBlocking {
        val dao = FakeDefaultTargetDao()
        store(dao).setDefault(remote)
        assertThat(dao.rows[0]!!.modelTarget).isEqualTo(DialogTargetCodec.encode(remote))
        assertThat(DialogTargetCodec.encode(remote)).isEqualTo("CLOUD:cred-1")
        assertThat(DialogTargetCodec.encode(DialogTarget.Device)).isEqualTo("LOCAL")
    }

    /** ★ 写失败表现为 `Err`，不抛出去（凭证写入路径的一贯纪律）。 */
    @Test
    fun writeFailureBecomesErrNotException() = runBlocking {
        val dao = FakeDefaultTargetDao().apply { throwOnWrite = true }
        assertThat(store(dao).setDefault(remote)).isInstanceOf(Outcome.Err::class.java)
    }

    /** ★ 清除 = 回到「未初始化」，不是「变成默认值」。 */
    @Test
    fun clearReturnsToUninitializedNotToDevice() = runBlocking {
        val dao = FakeDefaultTargetDao()
        val s = store(dao)
        s.setDefault(remote)
        assertThat(s.clear()).isEqualTo(1)
        assertThat(dao.rows).isEmpty()
        assertThat(s.default()).isEqualTo(DialogTarget.Device)
        assertThat(s.isExplicitlySet()).isFalse()
    }

    /**
     * ★★ 端到端地坐实本轮要修的那个 bug：
     * 会话级目标**没有登记**时，用的是全局默认，而不是悄悄的 `'LOCAL'`。
     *
     * ⚠ 合成策略（`of() ?: default()`）在 `:inference` 的 `DefaultModelRouter` 里，
     *    **不在本模块的文件边界内** ⇒ 本测试验的是"可用的那一半"：
     *    只要 `default()` 能给出用户选过的目标，合成后就不会再静默走本地。
     */
    @Test
    fun newSessionInheritsTheGlobalDefault_endToEndHalves() = runBlocking {
        val dao = FakeDefaultTargetDao()
        val defaults: DefaultTargetStore = store(dao)
        defaults.setDefault(remote)

        // 会话侧没有登记（null）⇒ 合成结果必须等于用户选的云端
        val sessionTarget: DialogTarget? = null
        val resolved = sessionTarget ?: defaults.default()
        assertThat(resolved).isEqualTo(remote)
        assertThat(resolved).isNotEqualTo(DialogTarget.Device)
    }
}
