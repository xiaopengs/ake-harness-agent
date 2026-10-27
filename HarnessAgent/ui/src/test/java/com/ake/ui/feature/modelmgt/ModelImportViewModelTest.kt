package com.ake.ui.feature.modelmgt

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.ports.LocalManifest
import com.ake.core.ports.LocalModelCandidate
import com.ake.core.ports.LocalModelReceipt
import com.ake.core.ports.ModelAvailability
import com.ake.core.ports.ModelImportErrorCodes
import com.ake.core.ports.ModelImportPort
import com.ake.ui.testsupport.runUiTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

/**
 * 导入流程的状态机（第一步 + 挑条目）。
 *
 * ## 为什么这里不测第三步（拷那 2.6 GB）
 *
 * 那一段要采样速度 / 剩余时间，用的是 `SystemClock.elapsedRealtime()` —— 它在纯 JVM
 * 单测里是不存在的（android.jar 里的方法一律 `throw "Stub!"`），本项目也没有 Robolectric。
 * 而这段逻辑本身也不缺这一层 coverage：
 *
 *   · 「拷了多少字节」这个数是 `ModelImportPort` 那头给的（适配器在源流上数的真实值），
 *     它不由 UI 算，UI 没有可断言的计算规则；
 *   · 「有字节给百分比、没字节给不确定」这个判断在 `ModelImportScreen` 里，只读一个
 *     `copiedBytes`，一眼能对上；
 *   · 真正值得锁死的是下面这些：**到哪一步、允不允许往下走、错了退回哪**。
 *
 * ⇒ 所以这里测状态机；那段二十分钟的拷贝留给 instrumentation 去覆盖。
 *   不为「覆盖数字好看」在 JVM 里 mock 一个时钟 —— 那测出来的是 mock，不是行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelImportViewModelTest {

    // ─────────────────────────────────────────────────────── 第一步

    @Test
    fun backingOutOfThePickerChangesNothing() = runUiTest {
        val vm = ModelImportViewModel(FakeImportPort())
        advanceUntilIdle()

        vm.onManifestPicked(null)     // 用户在系统选择器里按了返回
        advanceUntilIdle()

        assertWithMessage("用户只是退出了选择器，不是失败了 —— 不该弹错")
            .that(vm.ui.value.errorCode).isNull()
        assertThat(vm.ui.value.step).isEqualTo(ImportStep.IDLE)
    }

    @Test
    fun aVerifiedManifestAdvancesToPickingAModel() = runUiTest {
        val port = FakeImportPort(manifest = Outcome.Ok(manifestOf(active, deprecated, revoked)))
        val vm = ModelImportViewModel(port)
        advanceUntilIdle()

        vm.onManifestPicked("content://documents/manifest.json")
        advanceUntilIdle()

        val ui = vm.ui.value
        assertThat(ui.step).isEqualTo(ImportStep.CHOOSE_MODEL)
        assertThat(ui.manifestVersion).isEqualTo(7L)
        assertThat(ui.rows).hasSize(3)
        assertWithMessage(
            "默认要落在第一个 ACTIVE 上 —— 让用户一进来就能直接点下一步，而不是先想选哪个"
        ).that(ui.selected?.modelId).isEqualTo(active.modelId)
    }

    @Test
    fun candidatesAlreadyOnDeviceAreFlaggedBeforeCopying() = runUiTest {
        val port = FakeImportPort(
            manifest = Outcome.Ok(manifestOf(active)),
            already = setOf(active.modelId),
        )
        val vm = ModelImportViewModel(port)
        advanceUntilIdle()

        vm.onManifestPicked("content://whatever")
        advanceUntilIdle()

        assertWithMessage(
            "2.6 GB 之前先说一句「这份已经在设备上了」，比拷完才发现有意义得多"
        ).that(vm.ui.value.rows.single().alreadyOnDevice).isTrue()
    }

    @Test
    fun aRejectedManifestStaysOnStepOneWithItsCode() = runUiTest {
        val port = FakeImportPort(
            manifest = Outcome.Err(
                AppError(ModelImportErrorCodes.MANIFEST_DOWNGRADE, "err_manifest_import_downgrade"),
            ),
        )
        val vm = ModelImportViewModel(port)
        advanceUntilIdle()

        vm.onManifestPicked("content://old.json")
        advanceUntilIdle()

        assertWithMessage("降级被拒后必须停回第一步，UI 才能把那句「去拿最新版」显示出来")
            .that(vm.ui.value.errorCode).isEqualTo(ModelImportErrorCodes.MANIFEST_DOWNGRADE)
        assertThat(vm.ui.value.step).isEqualTo(ImportStep.IDLE)
    }

    // ─────────────────────────────────────────────────────── 挑条目

    @Test
    fun aRevokedEntryCannotBeSelected() = runUiTest {
        val vm = ModelImportViewModel(
            FakeImportPort(manifest = Outcome.Ok(manifestOf(active, revoked))),
        )
        advanceUntilIdle()
        vm.onManifestPicked("content://x")
        advanceUntilIdle()

        vm.select(vm.ui.value.rows.first { it.candidate.availability == ModelAvailability.REVOKED })
        advanceUntilIdle()

        assertWithMessage(
            "REVOKED 的条目是「已下线、不许加载」的。让它被选上，用户会花两个小时" +
                "拷进去一个引擎根本不会加载的模型"
        ).that(vm.ui.value.selected?.availability).isNotEqualTo(ModelAvailability.REVOKED)
        assertThat(vm.ui.value.selected?.modelId).isEqualTo(active.modelId)
    }

    @Test
    fun anActiveEntryCanBeSelected() = runUiTest {
        val vm = ModelImportViewModel(
            FakeImportPort(manifest = Outcome.Ok(manifestOf(active, deprecated))),
        )
        advanceUntilIdle()
        vm.onManifestPicked("content://x")
        advanceUntilIdle()

        vm.select(vm.ui.value.rows.last { it.candidate.availability == ModelAvailability.DEPRECATED })
        advanceUntilIdle()

        assertWithMessage("DEPRECATED 只是「不再推荐」，仍然可以用 —— 不该拦")
            .that(vm.ui.value.selected?.availability).isEqualTo(ModelAvailability.DEPRECATED)
    }

    @Test
    fun pickingAModelFileWithoutASelectionDoesNothing() = runUiTest {
        val vm = ModelImportViewModel(FakeImportPort())
        advanceUntilIdle()

        // 还没进来过 manifest ⇒ selected 为空 ⇒ 这一步必须原样忽略，而不是拿空指针去开拷。
        vm.onModelFilePicked("content://model.litertlm")
        advanceUntilIdle()

        assertWithMessage("没挑过条目就不许开拷 —— 那时无从知道该期待多大、摘要该是多少")
            .that(vm.ui.value.step).isEqualTo(ImportStep.IDLE)
    }

    // ─────────────────────────────────────────────────────── 夹具

    private val active = LocalModelCandidate(
        modelId = "gemma-4-e2b-it",
        variant = "cpu",
        revision = 3L,
        version = "2026.10.1",
        sizeBytes = 2_791_731_200L,
        contextWindow = 8192,
        minRamGb = 6,
        availability = ModelAvailability.ACTIVE,
    )

    private val deprecated = active.copy(revision = 2L, availability = ModelAvailability.DEPRECATED)
    private val revoked = active.copy(revision = 1L, availability = ModelAvailability.REVOKED)

    private fun manifestOf(vararg items: LocalModelCandidate) =
        LocalManifest(manifestVersion = 7L, candidates = items.toList())

    private class FakeImportPort(
        private val manifest: Outcome<LocalManifest> =
            Outcome.Err(AppError("STUB", "err_unknown")),
        private val already: Set<String> = emptySet(),
    ) : ModelImportPort {
        override suspend fun importManifest(sourceUri: String): Outcome<LocalManifest> = manifest

        override suspend fun importModelFile(
            candidate: LocalModelCandidate,
            sourceUri: String,
            onProgress: (Long) -> Unit,
        ): Outcome<LocalModelReceipt> {
            // 数得上字节的那一段的意义在于「字节是真的」，这里给三条假但可比的点就够断言了。
            onProgress(candidate.sizeBytes / 4)
            onProgress(candidate.sizeBytes / 2)
            onProgress(candidate.sizeBytes)
            return Outcome.Ok(
                LocalModelReceipt(
                    fileName = "x.litertlm",
                    sizeBytes = candidate.sizeBytes,
                    sha256 = "ab",
                    modelId = candidate.modelId,
                    variant = candidate.variant,
                    copied = true,
                ),
            )
        }

        override suspend fun alreadyImported(candidate: LocalModelCandidate): Boolean =
            candidate.modelId in already
    }
}
