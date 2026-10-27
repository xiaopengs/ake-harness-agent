package com.ake.data.persist.modeldist

import com.ake.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * manifest 的离线侧载（任务 B 的前半段）。
 *
 * ## 为什么这条链路要有独立测试
 *
 * 它是**整条离线链路唯一的信任入口**：模型文件的合法性（期望摘要是多少）全部由这里
 * 进来的那份 manifest 决定。所以下面钉死的是三件小事但都不能错的事：
 *
 *  1. 我们签的、更新的 ⇒ 收下，并且**真的落进了 `ManifestCache`**
 *     （落不进去等于这次导入在重启后失效，`ModelManifestSource.current()` 还是什么都没有）；
 *  2. 别人签的 / 没签的 ⇒ 拒绝（这里的「宽松一点」等于给任意二进制发了一张通行证）；
 *  3. `manifest_version` 更小的 ⇒ 拒绝（签名合法 ≠ 内容新鲜，重放攻击走的就是这道门）。
 */
@RunWith(JUnit4::class)
class ModelManifestImporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    /** ★ 惰性：`tmp.root` 要等 `@Rule` 起完之后才存在，写成字段会在规则之前求值。 */
    private fun cache(): ManifestCache = ManifestCache(tmp.root)

    private fun importer(trust: ManifestTrustStore = ModelDistFixtures.trust): ModelManifestImporter =
        ModelManifestImporter(parser = ModelDistFixtures.parser(trust), cache = cache())

    private val entry = ModelDistFixtures.entry(revision = 7, sha256 = ModelDistFixtures.sha("a"))

    @Test
    fun `签名合法的 manifest 被收下并写进缓存`() {
        val raw = ModelDistFixtures.signedJson(
            ModelDistFixtures.manifestOf(entry, manifestVersion = 20L),
        )

        val result = importer().import(raw)

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val manifest = (result as Outcome.Ok).value
        assertThat(manifest.models.map { it.revision }).containsExactly(7L)
        // ★★ 真正的关键：落缓存了 ⇒ 断网重启后 `ModelManifestSource.current()` 读得到
        assertThat(cache().read()).isEqualTo(raw)
    }

    @Test
    fun `不是我们签的 manifest 一律拒绝`() {
        // 同一份内容，但换成一把**没有登记**的密钥签出来的
        val rogue = ModelDistFixtures.manifestOf(entry, manifestVersion = 99L)
        val raw = ModelDistFixtures.signedJson(rogue)
        // 用一把别的公钥当信任锚 ⇒ 原签名验不过
        val otherTrust = ManifestTrust.pinned(
            "someone-else" to ManifestTrust.rsaSha256Verifier(
                java.util.Base64.getEncoder().encodeToString(
                    java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                        .generateKeyPair().public.encoded,
                ),
            ),
        )

        val result = importer(otherTrust).import(raw)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_MANIFEST_INVALID)
        assertThat(cache().read()).isNull()
    }

    @Test
    fun `空白内容被拒绝`() {
        val result = importer().import("   ")

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ModelDistErrorCodes.IMPORT_MANIFEST_INVALID)
    }

    @Test
    fun `版本回退的重放 manifest 被拒绝且不覆盖缓存`() {
        val fresh = ModelDistFixtures.signedJson(
            ModelDistFixtures.manifestOf(entry, manifestVersion = 20L),
        )
        val stale = ModelDistFixtures.signedJson(
            ModelDistFixtures.manifestOf(entry, manifestVersion = 7L),
        )
        assertThat(importer().import(fresh)).isInstanceOf(Outcome.Ok::class.java)

        val result = importer().import(stale)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.IMPORT_MANIFEST_DOWNGRADE)
        // ★ 缓存里必须还是那份新的 —— 重放攻击的目的正是把它换掉
        assertThat(cache().read()).isEqualTo(fresh)
    }
}
