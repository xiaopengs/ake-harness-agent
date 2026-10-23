package com.ake.llm

import com.ake.data.persist.modeldist.ModelFileOp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * 账本意图 → 文件动作。
 *
 * 最要紧的一条是「**拒绝对非 `.part` 执行删除**」：账本那侧已经用类型系统保证了
 * 不会产出删除已装模型的动作，但名字是字符串，字符串是最容易出错的一层。
 * 这里兜最后一道 —— 删错的代价是用户重下 2.6 GB。
 */
@RunWith(JUnit4::class)
class ModelFileOpsExecutorTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val dir: File get() = tmp.root

    @Test
    fun `Rename 把旧模型搬到归档名`() {
        File(dir, "asset-1.litertlm").writeText("old-bytes")

        val ok = ModelFileOpsExecutor.apply(
            dir,
            listOf(ModelFileOp.Rename("asset-1.litertlm", "gemma_TEXT_STD_aaaaaaaaaaaaaaaa.litertlm")),
        )

        assertThat(ok).isEqualTo(1)
        assertThat(File(dir, "asset-1.litertlm").exists()).isFalse()
        assertThat(File(dir, "gemma_TEXT_STD_aaaaaaaaaaaaaaaa.litertlm").readText()).isEqualTo("old-bytes")
    }

    @Test
    fun `Rename 的源不在了 ⇒ 跳过但不算成功`() {
        val ok = ModelFileOpsExecutor.apply(dir, listOf(ModelFileOp.Rename("gone.litertlm", "arch.litertlm")))

        assertThat(ok).isEqualTo(0)
    }

    @Test
    fun `Discard 删掉残缺的 part`() {
        File(dir, "asset-1.litertlm.part").writeText("half")

        val ok = ModelFileOpsExecutor.apply(dir, listOf(ModelFileOp.Discard("asset-1.litertlm.part")))

        assertThat(ok).isEqualTo(1)
        assertThat(File(dir, "asset-1.litertlm.part").exists()).isFalse()
    }

    @Test
    fun `Discard 绝不动非 part 文件（硬规则 1 的最后一道兜底）`() {
        File(dir, "asset-1.litertlm").writeText("precious-2.6GB")

        val ok = ModelFileOpsExecutor.apply(dir, listOf(ModelFileOp.Discard("asset-1.litertlm")))

        assertThat(ok).isEqualTo(0)
        assertThat(File(dir, "asset-1.litertlm").readText()).isEqualTo("precious-2.6GB")
    }
}
