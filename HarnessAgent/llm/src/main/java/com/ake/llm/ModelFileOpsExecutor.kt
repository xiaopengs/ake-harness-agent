package com.ake.llm

import com.ake.core.common.AkeLog
import com.ake.data.persist.modeldist.ModelFileOp
import java.io.File

/**
 * 执行 `:data` 账本产出的文件动作（[ModelFileOp]）。账本只描述意图，真正动手的是 `:llm`。
 *
 * ## 两条不能破的纪律
 *
 *  1. **[ModelFileOp.Discard] 只许删 `.part`。**
 *     账本那边已经用类型保证了「不会产出删除已装模型的动作」，但字符串是最容易出错的一层：
 *     一行 SQL 拼错、一次手改就能让 `Discard` 拿到一个 `.litertlm` 名字。
 *     这里再兜一道 —— 名字不以 `.part` 结尾就只告警、不动手。
 *     2.6 GB 的模型删掉就回不来（重下要用户的流量），而 `.part` 删错了只是重下一次。
 *     两边代价差两个数量级，值得一道冗余检查。
 *
 *  2. **动作失败不抛异常。** 改名失败（目标已存在 / 存储只读）时**保留原文件**继续走流程：
 *     账本已经把槽位行写成 `DOWNLOADING` 了，此时抛异常会让 App 停在「槽位在下载、
 *     旧文件还在原地」的状态；而不抛的话，下载完成后 rename 会覆盖旧文件 ——
 *     那正是硬规则 1 要防的事。所以失败必须**升级为高优先级日志**，让它在监控里可见。
 */
internal object ModelFileOpsExecutor {

    /** @return 成功执行的动作数（调用方据此判断是否需要告警）。 */
    public fun apply(modelsDir: File, ops: List<ModelFileOp>): Int = ops.count { op ->
        when (op) {
            is ModelFileOp.Rename -> rename(modelsDir, op)
            is ModelFileOp.Discard -> discard(modelsDir, op)
        }
    }

    private fun rename(modelsDir: File, op: ModelFileOp.Rename): Boolean {
        val from = File(modelsDir, op.fromName)
        if (!from.exists()) {
            // 文件已经不在了（用户清过缓存 / 换过卡）⇒ 账本那行是孤儿，改名无从谈起。
            // 不算失败：继续走下载才是正解，返回 false 只影响计数。
            AkeLog.w(TAG, "rename: 源文件已不存在，跳过 ${op.fromName} → ${op.toName}")
            return false
        }
        val to = File(modelsDir, op.toName)
        val ok = from.renameTo(to)
        if (ok) {
            AkeLog.i(TAG, "rename: 旧模型已归档 ${op.fromName} → ${op.toName}")
        } else {
            // ★ 这里绝不能改成「删掉 from」：那会直接违反硬规则 1。
            AkeLog.w(
                TAG,
                "rename 失败（旧文件保留在 ${op.fromName}，下载完成时的转正会覆盖它）" +
                    " —— 请检查存储是否只读或 ${op.toName} 已存在",
            )
        }
        return ok
    }

    private fun discard(modelsDir: File, op: ModelFileOp.Discard): Boolean {
        val name = op.name
        if (!name.endsWith(PART_SUFFIX)) {
            AkeLog.w(TAG, "discard: 拒绝对非临时文件执行删除 —— $name（只许删 $PART_SUFFIX）")
            return false
        }
        val file = File(modelsDir, name)
        if (!file.exists()) return true
        val ok = file.delete()
        if (!ok) AkeLog.w(TAG, "discard: 删除失败 —— $name")
        return ok
    }

    private const val TAG: String = "ModelFileOps"
    private const val PART_SUFFIX: String = ".part"
}
