package com.ake.llm

import com.ake.data.persist.modeldist.LocalModelFile
import com.ake.data.persist.modeldist.ModelFileInventory
import com.ake.data.persist.modeldist.ModelFileLayout
import com.ake.data.persist.modeldist.ModelManifestEntry
import java.io.File

/**
 * `ModelFileInventory` 的 `:llm` 实现 —— 盘上到底有没有某个版本的模型，只有文件系统知道。
 *
 * ## 为什么按 sha256 找（而不是 revision / 版本号）
 *
 * `archiveNameOf` 的入参就是 sha256 ⇒ 归档名是**内容身份**的函数。
 * 服务端把 revision 改来改去、把 ACTIVE 指针指回旧条目，客户端仍能靠内容认出
 * 「这份我盘上有」—— 这正是「只改配置就能回滚」能成立的前提。
 *
 * ## 为什么**不**顺手认最终文件 `$assetId.litertlm`
 *
 * 最终名里的 `assetId` 是账本主键（ULID），从 `entry` 推导不出来；
 * 而「槽位上现在装的是哪一份」这个问题由账本回答（[ModelResolution.Current]），
 * 不属于"盘上有没有"的范畴。两边职责不混。
 *
 * ## 字节数必须比对
 *
 * 半截文件不算「有」。只认名字的话，一次中断留下的残片会被当成可用模型，
 * 于是 2.6 GB 的下载被跳过，加载阶段才炸，且炸在引擎内部 —— 最难归因的一种失败。
 */
public class ModelDirInventory(
    private val modelsDir: File,
) : ModelFileInventory {

    override fun probe(entry: ModelManifestEntry): LocalModelFile? {
        val name = ModelFileLayout.archiveNameOf(entry.modelId, entry.variant, entry.sha256)
        val file = File(modelsDir, name)
        // isFile 而非 exists()：目录同名的情况不该被当成模型文件
        if (!file.isFile) return null
        val size = file.length()
        if (size != entry.sizeBytes) {
            com.ake.core.common.AkeLog.w(
                TAG,
                "probe: 归档名命中但字节数不符（$size ≠ ${entry.sizeBytes}）⇒ 视为没有：$name",
            )
            return null
        }
        return LocalModelFile(name = name, sizeBytes = size)
    }

    public companion object {
        private const val TAG: String = "ModelInventory"
    }
}
