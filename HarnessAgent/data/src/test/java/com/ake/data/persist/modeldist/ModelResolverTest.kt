package com.ake.data.persist.modeldist

import com.ake.core.contract.ModelAssetState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * 目标选择 + 回滚语义。
 *
 * ## 本文件要钉死的三件事
 *
 *  1. **回滚是「改一次配置」就能生效的**：服务端把旧条目置回 ACTIVE（manifest_version 照样 +1），
 *     客户端下一次 `resolve` 必须自己认出来，且**在旧文件还在盘上时零下载完成切换**。
 *  2. **回滚判定靠 revision 比较，文件认人靠 sha256**：前者是数字、会被服务端改写，
 *     后者是内容身份、改不了。
 *  3. **没有任何裁决会删掉已装模型**：`Download.keep` / `KeepInstalled` / `InstalledRevoked`
 *     都把资产原样带出来，调用方想删只能走用户显式触发的 `deleteModelFile()`。
 */
@RunWith(JUnit4::class)
class ModelResolverTest {

    /** 只认得给定 sha256 集合的假盘点器。 */
    private fun inventory(vararg sha256: String): ModelFileInventory = ModelFileInventory { entry ->
        if (entry.sha256 in sha256) {
            LocalModelFile(
                name = ModelFileLayout.archiveNameOf(entry.modelId, entry.variant, entry.sha256),
                sizeBytes = entry.sizeBytes,
            )
        } else {
            null
        }
    }

    private val emptyInventory: ModelFileInventory = ModelFileInventory { null }

    // ──────────────────────────────────────────── 常态

    @Test
    fun `已装的正是目标版本则无需动作`() {
        val entry = ModelDistFixtures.entry(revision = 5)
        val manifest = ModelDistFixtures.manifestOf(entry)
        val asset = ModelDistFixtures.record(sha256 = entry.sha256)

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.Current::class.java)
        assertThat((r as ModelResolution.Current).entry.revision).isEqualTo(5L)
    }

    @Test
    fun `有更高 revision 且本地没有文件时需要下载且保留已装的`() {
        val installed = ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"))
        val newer = ModelDistFixtures.entry(revision = 7, sha256 = ModelDistFixtures.sha("b"))
        val manifest = ModelDistFixtures.manifestOf(installed, newer)
        val asset = ModelDistFixtures.record(sha256 = installed.sha256)

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.Download::class.java)
        val d = r as ModelResolution.Download
        assertThat(d.entry.revision).isEqualTo(7L)
        assertThat(d.isRollback).isFalse()
        // ★ 硬规则 1：旧资产必须被带出来，不能「顺手清掉」
        assertThat(d.keep?.sha256).isEqualTo(installed.sha256)
    }

    @Test
    fun `目标文件已在盘上则零下载切换`() {
        val old = ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"))
        val newer = ModelDistFixtures.entry(revision = 7, sha256 = ModelDistFixtures.sha("b"))
        val manifest = ModelDistFixtures.manifestOf(old, newer)
        val asset = ModelDistFixtures.record(sha256 = old.sha256)

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, inventory(newer.sha256))

        assertThat(r).isInstanceOf(ModelResolution.UseLocal::class.java)
        val u = r as ModelResolution.UseLocal
        assertThat(u.entry.revision).isEqualTo(7L)
        assertThat(u.isRollback).isFalse()
        assertThat(u.previous?.sha256).isEqualTo(old.sha256)
    }

    // ──────────────────────────────────────────── 回滚

    @Test
    fun `服务端把 ACTIVE 指回旧 revision 时判为回滚且零下载切回`() {
        // 服务端只改配置：新条目 DEPRECATED，旧条目回到 ACTIVE，manifest_version 仍然更大
        val old = ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"))
        val broken = ModelDistFixtures.entry(
            revision = 7,
            sha256 = ModelDistFixtures.sha("b"),
            state = ModelEntryState.DEPRECATED,
        )
        val manifest = ModelDistFixtures.manifestOf(old, broken, manifestVersion = 8L)
        val asset = ModelDistFixtures.record(sha256 = broken.sha256) // 在用的是坏的那个

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, inventory(old.sha256))

        assertThat(r).isInstanceOf(ModelResolution.UseLocal::class.java)
        val u = r as ModelResolution.UseLocal
        assertThat(u.entry.revision).isEqualTo(5L)
        assertThat(u.isRollback).isTrue()
        assertThat(u.previous?.sha256).isEqualTo(broken.sha256)
    }

    @Test
    fun `回滚时旧文件已被清理则退化为下载但仍是回滚语义`() {
        val old = ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"))
        // 用 DEPRECATED 而不是 REVOKED：REVOKED 会在①步就以「停止加载」返回，
        // 那是安全优先的短路，不属于回滚路径（见 `在用模型被 REVOKED 时停止加载但保留文件`）
        val broken = ModelDistFixtures.entry(
            revision = 7,
            sha256 = ModelDistFixtures.sha("b"),
            state = ModelEntryState.DEPRECATED,
        )
        val manifest = ModelDistFixtures.manifestOf(old, broken, manifestVersion = 9L)
        val asset = ModelDistFixtures.record(sha256 = broken.sha256)

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.Download::class.java)
        val d = r as ModelResolution.Download
        assertThat(d.entry.revision).isEqualTo(5L)
        assertThat(d.isRollback).isTrue()
    }

    @Test
    fun `归档行参与候选集：回滚后已装的旧版本直接命中 Current`() {
        val old = ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"))
        val broken = ModelDistFixtures.entry(
            revision = 7,
            sha256 = ModelDistFixtures.sha("b"),
            state = ModelEntryState.DEPRECATED,
        )
        val manifest = ModelDistFixtures.manifestOf(old, broken, manifestVersion = 8L)
        // 归档行（旧版本，最近被用过）与槽位行（坏版本）同时在库里
        val archived = ModelDistFixtures.record(
            id = "01JARCHIVE00000000000000",
            sha256 = old.sha256,
            variant = ModelFileLayout.archiveVariantOf(ModelDistFixtures.VARIANT, old.sha256),
            updatedAt = 1_800_000_000_000L,
        )
        val slot = ModelDistFixtures.record(id = "01JSLOT00000000000000000", sha256 = broken.sha256)

        val r = ModelResolver.resolve(manifest, listOf(slot, archived), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.Current::class.java)
        assertThat((r as ModelResolution.Current).entry.revision).isEqualTo(5L)
    }

    @Test
    fun `在用的那份不在 manifest 里时不宣称回滚但必须保留`() {
        val v10 = ModelDistFixtures.entry(revision = 3, sha256 = ModelDistFixtures.sha("a"))
        val manifest = ModelDistFixtures.manifestOf(v10, manifestVersion = 99L)
        val asset = ModelDistFixtures.record(sha256 = ModelDistFixtures.sha("z")) // 孤儿版本，不在 manifest 里

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, inventory(v10.sha256))

        // 在用的那份不在 manifest 里 ⇒ 无法比较 revision ⇒ 不宣称回滚，但**必须保留它**
        assertThat(r).isInstanceOf(ModelResolution.UseLocal::class.java)
        val u = r as ModelResolution.UseLocal
        assertThat(u.isRollback).isFalse()
        assertThat(u.previous?.sha256).isEqualTo(asset.sha256)
    }

    // ──────────────────────────────────────────── 降级路径

    @Test
    fun `没有任何兼容条目但本地有可用模型时保留它`() {
        val tooNew = ModelDistFixtures.entry(revision = 5, minApp = 300, maxApp = 400)
        val manifest = ModelDistFixtures.manifestOf(tooNew)
        val asset = ModelDistFixtures.record(sha256 = ModelDistFixtures.sha("a"))

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.KeepInstalled::class.java)
        val k = r as ModelResolution.KeepInstalled
        assertThat(k.asset.sha256).isEqualTo(asset.sha256)
        assertThat(k.reasonKey).isEqualTo("err_model_no_compatible_but_installed")
    }

    @Test
    fun `既无兼容条目也无本地模型时阻塞并给出归因`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry(minApp = 300, maxApp = 400))
        val r = ModelResolver.resolve(manifest, emptyList(), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.Blocked::class.java)
        assertThat((r as ModelResolution.Blocked).reasonKey).isEqualTo("err_model_no_compatible")
    }

    @Test
    fun `App 太老时不会去下载要求更高壳的模型`() {
        val entry = ModelDistFixtures.entry(revision = 9, minApp = 200, maxApp = null)
        val manifest = ModelDistFixtures.manifestOf(entry)
        val r = ModelResolver.resolve(manifest, emptyList(), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.Blocked::class.java)
    }

    @Test
    fun `在用模型被 REVOKED 时停止加载但保留文件`() {
        val revoked = ModelDistFixtures.entry(
            revision = 5,
            sha256 = ModelDistFixtures.sha("a"),
            state = ModelEntryState.REVOKED,
        )
        val manifest = ModelDistFixtures.manifestOf(revoked)
        val asset = ModelDistFixtures.record(sha256 = revoked.sha256)

        val r = ModelResolver.resolve(manifest, listOf(asset), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.InstalledRevoked::class.java)
        // ★ 文件仍然在：裁决里带着资产，调用方只是**不再加载**它，而不是删掉
        assertThat((r as ModelResolution.InstalledRevoked).asset.localPath).isNotNull()
    }

    @Test
    fun `非 READY 的资产不参与在用判定`() {
        val entry = ModelDistFixtures.entry(revision = 5)
        val manifest = ModelDistFixtures.manifestOf(entry)
        val downloading = ModelDistFixtures.record(
            sha256 = ModelDistFixtures.sha("x"),
            state = ModelAssetState.DOWNLOADING,
        )

        val r = ModelResolver.resolve(manifest, listOf(downloading), 150, emptyInventory)

        assertThat(r).isInstanceOf(ModelResolution.Download::class.java)
        assertThat((r as ModelResolution.Download).keep).isNull()
    }

    @Test
    fun `按 sha256 认人且大小写不敏感`() {
        val entry = ModelDistFixtures.entry(sha256 = "a".repeat(64))
        val manifest = ModelDistFixtures.manifestOf(entry)
        assertThat(ModelResolver.revisionOf(manifest, "A".repeat(64))).isEqualTo(5L)
        assertThat(ModelResolver.revisionOf(manifest, "b".repeat(64))).isNull()
    }
}
