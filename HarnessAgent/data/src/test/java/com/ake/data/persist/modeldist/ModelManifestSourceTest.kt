package com.ake.data.persist.modeldist

import com.ake.core.common.ManualClock
import com.ake.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * manifest 的三级来源（缓存 / 网络 / 内置兜底）。
 *
 * ## 本文件要钉死的四件事
 *
 *  1. **没有合法签名就没有 manifest**：验签失败的字节**绝不写缓存** ——
 *     写进去等于把一次攻击持久化成「重启也修不好」的状态。
 *  2. **断网不是故障**：网络失败时只要有缓存就必须继续可用（缓存永不因时间过期）。
 *  3. **版本回退是攻击面**：签名合法但 `manifest_version` 变小 ⇒ 拒绝且不写缓存。
 *  4. **未配置就不联网**：URL / 公钥没配时，连传输层都不该被碰到。
 *
 * ★ 签名用的是 `ModelDistFixtures` 里的**真 RSA 密钥对**，不是恒真桩 ——
 *   桩成恒真的话，上面第 1 条就从来没被执行到，测试会绿而它保护的东西是空的。
 */
@RunWith(JUnit4::class)
class ModelManifestSourceTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val clock = ManualClock(1_700_000_000_000L)

    private fun manifest(revision: Long = 5L, version: Long = 7L): String =
        ModelDistFixtures.signedJson(
            ModelDistFixtures.manifestOf(
                ModelDistFixtures.entry(revision = revision),
                manifestVersion = version,
            ),
        )

    private fun configured(bundled: String? = null): ModelManifestConfig = ModelManifestConfig(
        manifestUrl = "https://cdn.ake.cn/model-manifest.json",
        pinnedKeys = mapOf(
            "model-ca-2026" to Base64.getEncoder().encodeToString(ModelDistFixtures.keyPair.public.encoded),
        ),
        bundledManifest = bundled,
    )

    private fun source(
        config: ModelManifestConfig,
        transport: ManifestTransport,
        cache: ManifestCache? = null,
    ): ModelManifestSource = ModelManifestSource(
        config = config,
        transport = transport,
        parser = ModelDistFixtures.parser(),
        cache = cache,
        clock = clock,
    )

    private fun newCache(): ManifestCache = ManifestCache(tmp.newFolder("modeldist"))

    // ──────────────────────────────────────────── ① 未配置

    @Test
    fun `未配置时不联网且报 NOT_CONFIGURED`() {
        val s = source(ModelManifestConfig.UNCONFIGURED, ManifestTransports.failing())

        val r = s.load()

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.MANIFEST_NOT_CONFIGURED)
    }

    @Test
    fun `未配置且有缓存时缓存仍然可用`() {
        val cache = newCache()
        assertThat(cache.write(manifest())).isTrue()
        val s = source(ModelManifestConfig.UNCONFIGURED, ManifestTransports.failing(), cache)

        // 未配置 ⇒ refresh 直接失败 ⇒ load 退到缓存，这正是「断网也能用」的那条路
        val r = s.load()

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat((r as Outcome.Ok).value.manifestVersion).isEqualTo(7L)
    }

    // ──────────────────────────────────────────── ② 首次启动

    @Test
    fun `既无缓存也无兜底则返回 UNAVAILABLE`() {
        val s = source(configured(), ManifestTransports.failing(), newCache())

        val r = s.current()

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.MANIFEST_UNAVAILABLE)
    }

    @Test
    fun `内置兜底在首次启动时顶上`() {
        val s = source(configured(bundled = manifest()), ManifestTransports.failing(), newCache())

        val r = s.current()

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat((r as Outcome.Ok).value.manifestVersion).isEqualTo(7L)
    }

    @Test
    fun `内置兜底同样必须验签`() {
        val evil = manifest().replace("\"manifest_version\":7", "\"manifest_version\":8")
        val s = source(configured(bundled = evil), ManifestTransports.failing(), newCache())

        assertThat(s.current()).isInstanceOf(Outcome.Err::class.java)
    }

    // ──────────────────────────────────────────── ③ 网络刷新

    @Test
    fun `刷新成功后写缓存且缓存可再次读出`() {
        val raw = manifest()
        val cache = newCache()
        val s = source(configured(), ManifestTransports.fixture(raw), cache)

        assertThat(s.refresh()).isInstanceOf(Outcome.Ok::class.java)
        assertThat(cache.read()).isEqualTo(raw)
        // 读缓存会重新验一次签 —— 不会因为「是本地的」就放行
        assertThat(s.current()).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun `验签失败的网络响应绝不写缓存`() {
        val cache = newCache()
        val tampered = manifest().replace("\"revision\":5", "\"revision\":9")
        val s = source(configured(), ManifestTransports.fixture(tampered), cache)

        val r = s.refresh()

        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.SIGNATURE_INVALID)
        assertThat(cache.read()).isNull()
    }

    @Test
    fun `manifest_version 回退视为降级攻击且拒绝写缓存`() {
        val cache = newCache()
        var n = 0
        val s = source(
            configured(),
            ManifestTransport { Outcome.Ok(if (n++ == 0) manifest(version = 9L) else manifest(version = 2L)) },
            cache,
        )

        assertThat(s.refresh()).isInstanceOf(Outcome.Ok::class.java)
        val second = s.refresh()

        assertThat(second).isInstanceOf(Outcome.Err::class.java)
        assertThat((second as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.MANIFEST_DOWNGRADE)
        // 缓存里必须还是那份 v9
        assertThat(cache.read()).contains("\"manifest_version\":9")
    }

    @Test
    fun `网络失败但有缓存时沿用缓存`() {
        val cache = newCache()
        assertThat(cache.write(manifest())).isTrue()
        val s = source(configured(), ManifestTransports.failing(), cache)

        val r = s.load()

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat((r as Outcome.Ok).value.manifestVersion).isEqualTo(7L)
    }

    @Test
    fun `缓存被本地篡改时拒绝并退到内置兜底`() {
        val cache = newCache()
        assertThat(cache.write(manifest().replace("\"revision\":5", "\"revision\":42"))).isTrue()
        val s = source(configured(bundled = manifest(version = 11L)), ManifestTransports.failing(), cache)

        val r = s.current()

        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        assertThat((r as Outcome.Ok).value.manifestVersion).isEqualTo(11L)
    }

    // ──────────────────────────────────────────── ④ 刷新节奏

    @Test
    fun `刷新间隔是节流而不是过期`() {
        val s = source(configured(), ManifestTransports.fixture(manifest()), newCache())

        assertThat(s.shouldRefresh()).isTrue()
        s.refresh()
        assertThat(s.shouldRefresh()).isFalse()
        clock.advance(ModelManifestConfig.DEFAULT_REFRESH_INTERVAL_MS)
        assertThat(s.shouldRefresh()).isTrue()
    }

    @Test
    fun `连续失败时按上次尝试时间退避而不是每次都打网络`() {
        val s = source(configured(), ManifestTransports.failing(), newCache())

        s.refresh()
        assertThat(s.shouldRefresh()).isFalse()
        clock.advance(1_000L)
        assertThat(s.shouldRefresh()).isFalse()
        clock.advance(ModelManifestConfig.DEFAULT_REFRESH_INTERVAL_MS)
        assertThat(s.shouldRefresh()).isTrue()
    }
}
