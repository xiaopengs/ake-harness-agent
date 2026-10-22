package com.ake.data.persist.store

import app.cash.turbine.test
import com.ake.core.common.ManualClock
import com.ake.core.ports.LocalSettings
import com.ake.data.persist.db.entity.AppSettingsEntity
import com.ake.data.persist.fake.FakeAkeDatabase
import com.ake.data.persist.fake.FakeConfigDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `SettingsStoreImpl` 的单测（`app_settings` 单行表）。
 *
 * ## 三条要钉住的语义
 *
 *  1. **`maxSteps` 的 `null` 与 `0` 是两回事**：`0` = 一步都不许跑，`null` = 跟随设备档位。
 *     用 `if (it == 0) null else it` 这种「顺手」的写法会让「设为 0」这个操作永远存不住。
 *     这是本文件里**唯一一条错了会造成安全后果**的语义（白名单与步数都是护栏输入）。
 *  2. **主键恒为 `SINGLE_ROW_ID`**：无论调用方传入什么，落到库里的行主键都必须是 0，
 *     否则单行表会悄悄变成多行表，而「最新值」取决于谁最后写。
 *  3. **`updatedAt` 由 `clock` 决定**：不直接 `System.currentTimeMillis()`
 *     （架构 §1.3 R3：任何跟预算/超时/截止有关的代码都不许直接读系统时钟）。
 *
 * ## ⚠ 本轮**未**覆盖的路径
 *
 * `update()` / `clearAll()` 的外层事务边界（`db.withTransaction { ... }`）需要真正打开的
 * Room 连接，JVM 单测起不来 —— 见 `FakeAkeDatabase` 的注释与回传里的「剩余风险」。
 * 因此 [update 后的值能被 values 观察到] 走的是「直接播种一行」的等价路径。
 */
@RunWith(JUnit4::class)
class SettingsStoreImplTest {

    private val dao = FakeConfigDao()
    private val db = FakeAkeDatabase(dao)
    private val clock = ManualClock(5_000L)
    private val store = SettingsStoreImpl(db, clock)

    // ──────────────────────────────────────────── 读取

    @Test
    fun `库里还没有行时 values 发出默认值`() = runBlocking {
        store.values().test {
            assertThat(awaitItem()).isEqualTo(LocalSettings())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update 后的值能被 values 观察到`() = runBlocking {
        // 等价路径：直接播种一行（真正的 update() 外层有事务，见类注释）
        dao.upsertSettings(store.toEntity(LocalSettings(allowHosts = listOf("a.cn")), 1L))

        store.values().test {
            assertThat(awaitItem().allowHosts).containsExactly("a.cn")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `values 去重：同一份值不重复 emit`() = runBlocking {
        dao.upsertSettings(store.toEntity(LocalSettings(maxSteps = 3), 1L))

        store.values().test {
            assertThat(awaitItem().maxSteps).isEqualTo(3)
            // ★ 再写一次**相同**的值：下游（ViewModel collect → Compose 重组）不该被唤醒
            dao.upsertSettings(store.toEntity(LocalSettings(maxSteps = 3), 2L))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `values 在值变化后重新 emit`() = runBlocking {
        dao.upsertSettings(store.toEntity(LocalSettings(maxSteps = 3), 1L))

        store.values().test {
            assertThat(awaitItem().maxSteps).isEqualTo(3)
            dao.upsertSettings(store.toEntity(LocalSettings(maxSteps = 4), 2L))
            assertThat(awaitItem().maxSteps).isEqualTo(4)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ──────────────────────────────────────────── 映射层

    @Test
    fun `maxSteps 为 null 时往返后仍是 null 而不是 0`() = runBlocking {
        val v = LocalSettings(maxSteps = null)

        val round = store.toDomain(store.toEntity(v, 1L))

        assertThat(round.maxSteps).isNull()
    }

    @Test
    fun `maxSteps 为 0 时往返后仍是 0 而不是 null`() = runBlocking {
        val v = LocalSettings(maxSteps = 0)

        val round = store.toDomain(store.toEntity(v, 1L))

        assertThat(round.maxSteps).isEqualTo(0)
    }

    @Test
    fun `allowHosts 与 showDomPayload 往返一致`() = runBlocking {
        val v = LocalSettings(
            allowHosts = listOf("example.com", "shop.example.com", "a-b.cn"),
            showDomPayload = false,
        )

        assertThat(store.toDomain(store.toEntity(v, 1L))).isEqualTo(v)
    }

    @Test
    fun `toEntity 强制写入单行主键`() = runBlocking {
        val row = store.toEntity(LocalSettings(), 1L)

        assertThat(row.id).isEqualTo(AppSettingsEntity.SINGLE_ROW_ID)
    }

    @Test
    fun `坏 allowHostsJson 降级为空列表（空集 = 不限，由跨域名确认兜底）`() = runBlocking {
        val row = AppSettingsEntity(
            id = AppSettingsEntity.SINGLE_ROW_ID,
            allowHostsJson = "[[[ broken",
            maxSteps = null,
            showDomPayload = 1,
            updatedAt = 1L,
        )

        assertThat(store.toDomain(row).allowHosts).isEmpty()
    }

    @Test
    fun `update 用 clock 写 updated_at 而不是系统时钟`() = runBlocking {
        clock.setTo(77L)

        val row = store.toEntity(LocalSettings(maxSteps = 1), clock.nowMillis())

        assertThat(row.updatedAt).isEqualTo(77L)
    }
}
