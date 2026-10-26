package com.ake.data.persist.modeldist

import com.ake.core.common.Outcome
import com.ake.data.BuildConfig
import com.ake.data.persist.di.PersistenceBindingsModule
import com.google.common.truth.Truth.assertThat
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * ★★ **`ModelManifestConfig` 只有两条路可以走，本文件把这两条都钉死。**
 *
 * | # | 情形                     | 必须成立的行为                                              |
 * |---|--------------------------|-------------------------------------------------------------|
 * | ① | 配置齐全（https + 有效公钥）| 拉得到 ⇒ 验得过 ⇒ `ModelResolver` 产出 `Download` ⇒ 用户拿到模型 |
 * | ② | 配置缺失 / 不可用         | `MANIFEST_NOT_CONFIGURED` + **传输零次调用** + **一个决议都不产出** |
 *
 * ★ **为什么②的判据是「传输未被调用」而不是「返回了 Err」**
 *
 * 只看 Err 的测试在「配置其实被忽略了、但后面某处恰好也失败了」的情况下照样绿 —— 那是假绿。
 * 真要保证的是：**没有任何东西 download 过**。`:llm` 侧真正的 HTTP 是由
 * `ModelDistributionSync` 拿到 `ModelResolution.Download` 之后才发起的，
 * 所以这里的等价判据是两层：
 *
 *   1. `transport.fetch` 一次都没被调用（连 manifest 都没去拉）；
 *   2. `ModelResolver.resolve` 一次都没被执行 ⇒ **`Download` 这个动作从未产生**。
 *
 * ① 与 ② 必须成对存在：如果有人把「未配置」改成了「悄悄放行任意一个默认 URL」，
 * ② 会红；如果把验签改成了恒假，① 会红。
 *
 * ## 关于密钥
 *
 * 本文件**不引入任何私钥文件**：作为 Pinned Key 用的是 `ModelDistFixtures` 里
 * 运行时现生成的真 RSA-2048 密钥对。`ModelManifestPins` 里那把真实要出货的公钥
 * 只做**结构性验真**（能不能解析、够不够 2048 位）—— 它的另一半在运维那里，
 * 仓库里从来没有，也不该有。
 */
@RunWith(JUnit4::class)
class ModelManifestConfigTest {

    /** 调用计数版的传输层：被调用过一次就说明「联网了」。 */
    private class CountingTransport(private val body: String) : ManifestTransport {
        var calls: Int = 0
            private set

        override fun fetch(url: String): Outcome<String> {
            calls++
            return Outcome.Ok(body)
        }
    }

    /** 一次完整的消费：`ModelManifestSource` → `ModelResolver` —— `:llm` 的 `ensureCurrent` 走的就是这两格。 */
    private class Run(
        val outcome: Outcome<ModelManifest>,
        val transportCalls: Int,
        val resolutions: List<ModelResolution>,
    )

    private val servedManifest: String =
        ModelDistFixtures.signedJson(ModelDistFixtures.manifestOf(ModelDistFixtures.entry()))

    private fun pinOfTestKey(): Map<String, String> = mapOf(
        TEST_KEY_ID to Base64.getEncoder().encodeToString(ModelDistFixtures.keyPair.public.encoded),
    )

    private fun configured(): ModelManifestConfig = ModelManifestConfigFactory.build(
        manifestUrl = MANIFEST_URL,
        pinnedKeys = pinOfTestKey(),
    )

    /**
     * 跑一遍链路。**传输体永远是 [servedManifest]（一份签名合法的 manifest）** ——
     * 这样②里如果出了纰漏（偷偷动了一次 HTTP），这里会是绿灯而不是 Err，
     * 假绿的机会被拿掉了。
     */
    private fun runChain(config: ModelManifestConfig): Run {
        val transport = CountingTransport(servedManifest)
        val source = ModelManifestSource(
            config = config,
            transport = transport,
            parser = ModelManifestParser(config.trustStore()),
            cache = null,
        )
        val loaded = source.load()
        val resolutions = if (loaded is Outcome.Ok) {
            listOf(
                ModelResolver.resolve(
                    manifest = loaded.value,
                    installed = emptyList(),
                    appVersionCode = APP_VERSION_CODE,
                    inventory = ModelFileInventory { null },
                ),
            )
        } else {
            emptyList()
        }
        return Run(loaded, transport.calls, resolutions)
    }

    // ─────────────────────────────────────────────────── ① 配置齐全 ⇒ 链路通

    @Test
    fun `配置齐全时被认定为已配置`() {
        val config = configured()

        assertThat(config.isConfigured).isTrue()
        assertThat(config.manifestUrl).isEqualTo(MANIFEST_URL)
        assertThat(config.pinnedKeys).containsExactly(TEST_KEY_ID, pinOfTestKey()[TEST_KEY_ID])
        assertThat(config.bundledManifest).isNull()
    }

    /** 「用户能拿到模型」这件事的正向证明：manifest 拉得到、验得过、决议真的要它去下载。 */
    @Test
    fun `配置齐全时整条链路通：拉到验签过并产出 Download`() {
        val r = runChain(configured())

        assertThat(r.outcome).isInstanceOf(Outcome.Ok::class.java)
        assertThat(r.transportCalls).isEqualTo(1)
        assertThat(r.resolutions).hasSize(1)
        val resolution = r.resolutions.single()
        assertThat(resolution).isInstanceOf(ModelResolution.Download::class.java)
        assertThat((resolution as ModelResolution.Download).entry.modelId)
            .isEqualTo(ModelDistFixtures.MODEL_ID)
    }

    /** 注入的地址含首尾空白（local.properties 常见手误）⇒ trim 之后照常可用。 */
    @Test
    fun `地址带首尾空白时被修正`() {
        val config = ModelManifestConfigFactory.build(
            manifestUrl = "  \t$MANIFEST_URL\n",
            pinnedKeys = pinOfTestKey(),
        )

        assertThat(config.isConfigured).isTrue()
        assertThat(config.manifestUrl).isEqualTo(MANIFEST_URL)
    }

    // ─────────────────────────────────────────── ② 配置缺失 ⇒ fail-closed 且不下载

    /**
     * ★★ **本文件的核心用例**：没有配置 ⇒ 明确报错，且**一个字节都不下**。
     *
     * 判据是「传输零调用 + 零决议」而不是「返回了 Err」——见本文件类注释。
     */
    @Test
    fun `地址缺失时不下载任何字节`() {
        val config = ModelManifestConfigFactory.build(manifestUrl = "")

        assertThat(config).isSameInstanceAs(ModelManifestConfig.UNCONFIGURED)
        assertThat(config.isConfigured).isFalse()

        val r = runChain(config)

        assertThat(r.outcome).isInstanceOf(Outcome.Err::class.java)
        assertThat((r.outcome as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.MANIFEST_NOT_CONFIGURED)
        // ★★ 真正的判据：既没去拉 manifest，也没产生过任何 Download 决议
        assertThat(r.transportCalls).isEqualTo(0)
        assertThat(r.resolutions).isEmpty()
    }

    @Test
    fun `地址只有空白时同样不下载`() {
        val config = ModelManifestConfigFactory.build(manifestUrl = "  \t\n ")

        assertThat(config.isConfigured).isFalse()

        val r = runChain(config)

        assertThat((r.outcome as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.MANIFEST_NOT_CONFIGURED)
        assertThat(r.transportCalls).isEqualTo(0)
        assertThat(r.resolutions).isEmpty()
    }

    /** 明文地址意味着「下一份 2.6 GB 可执行内容从哪来」可以先于 TLS 被人决定 ⇒ 拒绝启用。 */
    /** 镜像 docs/03 §2.4：注入的地址必须是 https 的最终地址 —— http 一概不启用。 */
    @Test
    fun `http 的地址被拒绝且不下载`() {
        val config = ModelManifestConfigFactory.build(
            manifestUrl = "http://cdn.ake.cn/model-manifest.json",
            pinnedKeys = pinOfTestKey(),
        )

        assertThat(config).isSameInstanceAs(ModelManifestConfig.UNCONFIGURED)

        val r = runChain(config)

        assertThat((r.outcome as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.MANIFEST_NOT_CONFIGURED)
        assertThat(r.transportCalls).isEqualTo(0)
        assertThat(r.resolutions).isEmpty()
    }

    @Test
    fun `一把钥匙都没有时不下载`() {
        val config = ModelManifestConfigFactory.build(manifestUrl = MANIFEST_URL, pinnedKeys = emptyMap())

        assertThat(config).isSameInstanceAs(ModelManifestConfig.UNCONFIGURED)

        val r = runChain(config)

        assertThat((r.outcome as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.MANIFEST_NOT_CONFIGURED)
        assertThat(r.transportCalls).isEqualTo(0)
        assertThat(r.resolutions).isEmpty()
    }

    /**
     * 钥匙串写错格式时 `X509EncodedKeySpec` 是**静默失败**的（docs/03 §2.3 那个坑）：
     * 不在这里当场验真，线上的表现就是「所有 manifest 恒验签失败」，且看不出原因。
     */
    @Test
    fun `不可用的公钥串被丢弃而不是变成恒假验签器`() {
        val config = ModelManifestConfigFactory.build(
            manifestUrl = MANIFEST_URL,
            pinnedKeys = mapOf(
                "broken-not-base64" to "!!! not a DER !!!",
                // PKCS#1 / 裸 modulus 之类：能被 base64 解出来，但不是 SubjectPublicKeyInfo
                "wrong-format" to Base64.getEncoder().encodeToString(byteArrayOf(0x01, 0x02, 0x03)),
                TEST_KEY_ID to pinOfTestKey()[TEST_KEY_ID]!!,
            ),
        )

        // 坏钥匙丢掉，好钥匙留下 ⇒ 链路仍然可用
        assertThat(config.isConfigured).isTrue()
        assertThat(config.pinnedKeys).hasSize(1)
        assertThat(config.pinnedKeys).containsKey(TEST_KEY_ID)
        val r = runChain(config)
        assertThat(r.outcome).isInstanceOf(Outcome.Ok::class.java)
        assertThat(r.resolutions).hasSize(1)
    }

    @Test
    fun `所有公钥串都不可用时退回未配置`() {
        val config = ModelManifestConfigFactory.build(
            manifestUrl = MANIFEST_URL,
            pinnedKeys = mapOf("broken" to "!!! nope !!!"),
        )

        assertThat(config).isSameInstanceAs(ModelManifestConfig.UNCONFIGURED)
        assertThat(runChain(config).transportCalls).isEqualTo(0)
    }

    // ────────────────────────────────────── ③ 真要出货的那把钉必须是真的能用

    /**
     * `ModelManifestPins` 里的每一把钥匙都要能被 `KeyFactory` 解析成 RSA-2048+。
     *
     * 这条用例存在的唯一理由：那把钉哪天被人手改错一个字符 ⇒ 这里立刻红，
     * 而不是等三百台设备同时报 `MANIFEST_SIGNATURE_INVALID`。
     */
    @Test
    fun `内置钉扎的公钥都能解析为 RSA-2048 以上`() {
        assertThat(ModelManifestPins.ALL).isNotEmpty()

        ModelManifestPins.ALL.forEach { (keyId, spki) ->
            assertThat(keyId).isNotEmpty()
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spki)))
            assertThat(key).isInstanceOf(RSAPublicKey::class.java)
            assertThat((key as RSAPublicKey).modulus.bitLength()).isAtLeast(2048)
        }
    }

    /** 钉扎绝不能等价于「验什么都过」：测试钥匙签的东西，内置的信任库必须拒绝。 */
    @Test
    fun `内置钉扎不承认陌生钥匙的签名`() {
        val verifier = ModelManifestConfigFactory.build(manifestUrl = MANIFEST_URL)
            .trustStore()
            .verifierFor(ModelManifestPins.MODEL_CA_2026)

        assertThat(verifier).isNotNull()
        // ModelDistFixtures.sign() 用的是测试密钥对，与内置钉不是一对
        val forged = ModelDistFixtures.sign(ModelDistFixtures.manifestOf(ModelDistFixtures.entry()))
        val signedBytes = ManifestCanonicalForm.render(
            ModelDistFixtures.manifestOf(ModelDistFixtures.entry()),
        )
        assertThat(verifier!!.verify(signedBytes, forged)).isFalse()
    }

    // ─────────────────────────────── ④ Hilt 装配：拿到手的那份配置只有二选一

    /**
     * `provideModelManifestConfig()` 的返回值必须满足**二选一**契约：
     * 要么是一份完整可用的配置（https 地址 + 至少一把能用的公钥），
     * 要么**就是** `UNCONFIGURED` —— 不存在「配了一半」的中间态。
     *
     * ⚠ 这条用例不能依赖本机注入了什么：两种都可能，所以断言的是**契约**而不是具体值。
     */
    @Test
    fun `Hilt 提供的配置要么完整可用要么就是未配置`() {
        val injected = BuildConfig.MODEL_MANIFEST_URL.trim()
        assertThat(injected.isEmpty() || injected.startsWith("https://")).isTrue()

        val config = PersistenceBindingsModule.provideModelManifestConfig()

        if (!config.isConfigured) {
            assertThat(config).isSameInstanceAs(ModelManifestConfig.UNCONFIGURED)
            assertThat(config.manifestUrl).isEmpty()
            assertThat(config.pinnedKeys).isEmpty()
        } else {
            assertThat(config.manifestUrl).isEqualTo(injected)
            assertThat(config.manifestUrl).startsWith("https://")
            assertThat(config.pinnedKeys).isNotEmpty()
            config.pinnedKeys.forEach { (keyId, spki) ->
                assertThat(keyId).isNotEmpty()
                KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spki)))
            }
        }
    }

    /** 真用到的公钥是源码里钉的那把 —— 换钥匙必须提 PR，而不是改构建环境。 */
    @Test
    fun `生效的公钥来自源码钉扎而不是构建期注入`() {
        val config = PersistenceBindingsModule.provideModelManifestConfig()

        if (config.isConfigured) {
            assertThat(config.pinnedKeys).containsExactlyEntriesIn(ModelManifestPins.ALL)
        } else {
            // 未配置时工厂不会保留任何钥匙 ⇒ TRUST_NOTHING，任何 manifest 都验不过
            assertThat(config.trustStore().verifierFor(ModelManifestPins.MODEL_CA_2026)).isNull()
        }
    }

    private companion object {
        const val TEST_KEY_ID: String = "model-ca-2026"
        const val MANIFEST_URL: String = "https://cdn.ake.cn/model-manifest.json"
        /** fixture 条目的兼容窗口是 [100, 199]。 */
        const val APP_VERSION_CODE: Int = 100
    }
}
