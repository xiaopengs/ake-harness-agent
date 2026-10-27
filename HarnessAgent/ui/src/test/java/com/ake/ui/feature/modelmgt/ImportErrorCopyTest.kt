package com.ake.ui.feature.modelmgt

import com.ake.core.ports.LocalModelCandidate
import com.ake.core.ports.ModelAvailability
import com.ake.core.ports.ModelImportErrorCodes
import com.ake.ui.R
import com.ake.ui.testsupport.UiStrings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 「导入失败」那句中文到底说了什么。
 *
 * ## 为什么拿 `UiStrings` 读真文本，而不是比 `R.string` 的 id
 *
 * id 只够证明「两个错误码指向不同的资源」，证明不了资源里写了什么。而这一处的全部价值
 * 就在**字面**：用户能不能一眼看出「这次是文件没传完」还是「传的是另一个版本」，
 * 完全取决于那句话里有没有说出病因和下一步。改了 `strings.xml` 而 Kotlin 一行没动，
 * 是这张表唯一会悄悄腐烂的方式 —— 所以必须读到字。
 *
 * ## ★ 这张表真正在守的一条
 *
 * [ModelImportErrorCodes.SIZE_MISMATCH] 与 [ModelImportErrorCodes.CHECKSUM_MISMATCH]
 * 在后端都只是「验证没过」。一旦有哪天有人把两句合并成一句「导入失败，请重试」，
 * 代价是具体的：一个拿到「摘要不符」的用户会认定是传输出了岔子，于是把同一个错的
 * 文件再传一遍 —— 二十分钟后他看到的还是同一个错误。
 */
class ImportErrorCopyTest {

    private val bigModel = LocalModelCandidate(
        modelId = "gemma-4-e2b-it",
        variant = "cpu",
        revision = 3L,
        version = "2026.10.1",
        sizeBytes = 2_791_731_200L,   // 2.60 GiB
        contextWindow = 8192,
        minRamGb = 6,
        availability = ModelAvailability.ACTIVE,
    )

    private fun copyOf(code: String): String {
        val view = importErrorViewOf(code, bigModel)
        val raw = UiStrings.named(nameOf(view.messageRes))
        // 手工代 %% → % 与位置化参数，跟 Android 的 getString() 同约
        var text = raw.replace("%%", "%")
        view.args.forEachIndexed { i, arg -> text = text.replace("%${i + 1}\$s", arg) }
        return text
    }

    private fun nameOf(resId: Int): String = R.string::class.java.declaredFields
        .first {
            it.isAccessible = true
            it.getInt(null) == resId
        }
        .name

    // ─────────────────────────────────────────────────────── 两个 mismatch

    @Test
    fun sizeMismatchAndChecksumMismatchAreTwoDifferentSentences() {
        val size = copyOf(ModelImportErrorCodes.SIZE_MISMATCH)
        val checksum = copyOf(ModelImportErrorCodes.CHECKSUM_MISMATCH)

        assertWithMessage(
            "文件大小不符和内容不符是两个病因相反的失败，绝不能共用一句文案。" +
                "合并成一句的后果：拿到「摘要不符」的用户会去重传同一个错的文件"
        ).that(size).isNotEqualTo(checksum)
    }

    @Test
    fun sizeMismatchSaysTheFileIsIncompleteAndPointsAtReTransfer() {
        val text = copyOf(ModelImportErrorCodes.SIZE_MISMATCH)

        assertWithMessage("这一路的病是「没传完」，话里必须说出「不完整」")
            .that(text).contains("不完整")
        assertWithMessage("还得把期望多大说出来，否则用户无从判断是自己点错了文件")
            .that(text).contains("2.60 GB")
        assertWithMessage(
            "必须讲清楚不要接着断点继续 —— 只说「重试」会被读成续传，而续传一份残的必然再失败"
        ).that(text).contains("不要接着断点继续")
    }

    @Test
    fun checksumMismatchSaysItIsTheWrongFileAndWarnsThatRecopyingIsPointless() {
        val text = copyOf(ModelImportErrorCodes.CHECKSUM_MISMATCH)

        assertWithMessage("字节数是对的，所以病因不是残缺 —— 要说出「不是缺字节」这一层")
            .that(text).contains("不是缺字节")
        assertWithMessage("真正的处理办法是去找配套版本，不是重拷")
            .that(text).contains("版本拿混")
        assertWithMessage(
            "★ 最要紧的一句：必须明说重拷没用。少了这句，用户会用二十分钟复现同一个错"
        ).that(text).contains("重新拷贝没有用")
    }

    // ─────────────────────────────────────────────────────── 其余几个

    @Test
    fun noSpaceQuantifiesHowMuchAndSaysNothingWasWritten() {
        val text = copyOf(ModelImportErrorCodes.NO_SPACE)

        assertWithMessage("空间不足只说「清理空间」不给数字，用户不知道要清到什么程度")
            .that(text).contains("2.60 GB")
        assertWithMessage(
            "要明说一个字节都没写 —— 否则用户会担心盘上留了个半成品，而半成品正是这里刻意避免的"
        ).that(text).contains("一个字节都没写")
    }

    @Test
    fun ioFailedDoesNotBlameTheUserForSomethingTheyDidNotDo() {
        val text = copyOf(ModelImportErrorCodes.IO_FAILED)

        assertWithMessage("读失败常常是别的应用占用或选择方式读不出来，给出可执行的下一步")
            .that(text).contains("拷进手机存储")
    }

    @Test
    fun sourceMissingAsksThemToSimplyPickAgain() {
        assertWithMessage("文件不见了只需要重选，不该让用户去排查别的东西")
            .that(copyOf(ModelImportErrorCodes.SOURCE_MISSING))
            .contains("重新选一次")
    }

    @Test
    fun manifestInvalidExplainsWhyTheManifestMatters() {
        assertWithMessage("要解释为什么非要 manifest 不可，否则用户会问「我只有模型文件行不行」")
            .that(copyOf(ModelImportErrorCodes.MANIFEST_INVALID))
            .contains("manifest")
    }

    /**
     * 降级的裁决是「拒绝」（理由见 `ModelImportViewModel` 末尾裁决二），
     * 于是这句话的重点是**给出路**：用户显然是想要某个旧模型，
     * 得告诉他那条路其实是「拿最新的 manifest」。
     */
    @Test
    fun manifestDowngradeGivesAWayOutInsteadOfJustRefusing() {
        val text = copyOf(ModelImportErrorCodes.MANIFEST_DOWNGRADE)

        assertWithMessage("要说清楚已经被拦下了，不要让用户以为还在进行中")
            .that(text).contains("已经拦下了")
        assertWithMessage(
            "★ 只拒绝不给出路，用户会去找一份更旧的 manifest 反复试 —— 那正是这条防护要防的"
        ).that(text).contains("拿最新的一份")
    }

    // ─────────────────────────────────────────────────────── 形状约束

    @Test
    fun everyKnownCodeMapsToItsOwnResource() {
        val ids = listOf(
            ModelImportErrorCodes.NO_SPACE,
            ModelImportErrorCodes.SIZE_MISMATCH,
            ModelImportErrorCodes.CHECKSUM_MISMATCH,
            ModelImportErrorCodes.IO_FAILED,
            ModelImportErrorCodes.SOURCE_MISSING,
            ModelImportErrorCodes.MANIFEST_INVALID,
            ModelImportErrorCodes.MANIFEST_DOWNGRADE,
        ).map { importErrorViewOf(it, bigModel).messageRes }

        assertWithMessage("七个错误码要给出七段各不相同的说明，不能有哪个偷偷复用别人的")
            .that(ids).containsNoDuplicates()
    }

    @Test
    fun unknownCodeStillSaysSomething() {
        val view = importErrorViewOf("SOMETHING_ELSE", bigModel)

        assertWithMessage("未知码也得有话说，不能显示一片空白让界面看起来像是崩了")
            .that(view.messageRes).isEqualTo(R.string.err_model_import_unknown)
    }
}
