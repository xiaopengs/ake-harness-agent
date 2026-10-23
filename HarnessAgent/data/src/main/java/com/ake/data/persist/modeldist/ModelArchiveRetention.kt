package com.ake.data.persist.modeldist

import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState

/**
 * 归档行的**清理策略**（本轮裁决项 C）。
 *
 * ## 问题
 *
 * `ModelAssetLedger` 每次目标版本变化都把旧文件**改名归档**成独立一行（硬规则 1 要求不能删），
 * 于是「归档只增不减」，每次模型更新 +2.6 GB。盘的容量是有限的，
 * 这条策略就是回答「什么时候终于可以删」。
 *
 * ## 裁决
 *
 *  | 类别                       | 判定                                  | 清理闸门                          |
 *  |----------------------------|---------------------------------------|-----------------------------------|
 *  | **保留**                   | READY 行里最新的 [KEEP_VERSIONS] 份     | **永不清理**（自动或手动都不行）   |
 *  | **更早的归档**             | 其余 READY 行                          | 用户显式确认 **且** 当前在用版本已验证可加载 |
 *  | **残缺件**（非 READY）      | DOWNLOADING / PAUSED / CORRUPTED 行     | 用户显式确认即可（它们本来就不在服务）|
 *
 * ### 为什么是「两份」而不是「一份」
 *
 * 只保留在用的一份，等于**取消回滚能力**：服务端把坏版本 REVOKED 之后，
 * 客户端要切回去只能重新下 2.6 GB —— 而「不发版就能回滚」正是选 manifest 方案的唯一理由。
 * 保留两份的成本是 2.6 GB，收益是「回滚永远零下载」。第三份起的边际收益是 0（没人会回滚两代）。
 *
 * ### 为什么「清理前必须校验当前在用版本可正常加载」
 *
 * 这是整个策略里**唯一不可省略**的闸门。若允许「当前版本坏着」时清理归档，
 * 用户会同时失去现在能用的和上一个能用的 ⇒ 只能重下 2.6 GB，
 * 而且是在他刚点了「清理」之后 —— 那是把一次空间优化变成一次事故。
 * 所以 [ModelRetentionPlan.deletable] 在 `currentLoadable = false` 时**返回空表**，
 * 调用方拿不到任何可删的东西（同样是把规则做进类型，而不是写在注释里）。
 *
 * ### 为什么残缺件不需要「可加载」闸门
 *
 * CORRUPTED / 半成品不是「可用模型」，删掉它们不违反硬规则 1（那条保护的是**可用**模型）。
 * 给它们也加闸门只会让残缺件永久占位 —— 而它们往往正是吃掉空间的那部分。
 */
public object ModelArchiveRetention {

    /** 保留份数：1 = 当前在用，2 = 上一个可回滚版本。 */
    public const val KEEP_VERSIONS: Int = 2

    public fun plan(rows: List<ModelAssetRecord>): ModelRetentionPlan {
        // 与 `ModelResolver` 选「在用」用的是同一把尺子（updatedAt 最大者），
        // 否则会出现「策略认为该保留的、和解析器认为在用的不是同一行」这种最难受的分叉。
        val byFreshness = rows.sortedByDescending { it.updatedAt }

        val ready = byFreshness.filter { it.state == ModelAssetState.READY && !it.localPath.isNullOrBlank() }
        // 按 id 排除而不是 `it !in ready`：后者走 data class 的 `equals`，
        // 两行恰好字段全等时会把其中一行误判成「已被保留」。
        val keepIds = ready.mapTo(HashSet()) { it.id }
        val incomplete = byFreshness.filter { it.id !in keepIds }
        // 归档行没有 revision 列（回滚不靠它），所以用「新鲜度」当作版本的代理排序键。
        val keep = ready.take(KEEP_VERSIONS)
        val archives = ready.drop(KEEP_VERSIONS)

        return ModelRetentionPlan(keep = keep, archives = archives, incomplete = incomplete)
    }
}

/**
 * 一次清理裁决的结果。
 *
 * ★ [deletable] 是**唯一**能产出「可以删的文件」的出口，且它带两个开关。
 *   调用方（`:llm` 的 `ModelLifecyclePort.deleteModelFile`）拿到的永远是一个
 *   已经过闸的名单 —— 想绕过闸门只能改本文件，而本文件的注释会把后果写清楚。
 */
public data class ModelRetentionPlan(
    /** 必须保留：在用版本 + 上一个可回滚版本。 */
    public val keep: List<ModelAssetRecord>,
    /** 更早的归档。**双闸门**：用户确认 + 当前在用版本可加载。 */
    public val archives: List<ModelAssetRecord>,
    /** 残缺件（非 READY）。单闸门：只要用户确认。 */
    public val incomplete: List<ModelAssetRecord>,
) {

    /**
     * 实际允许删除的名单。
     *
     * @param userConfirmed 用户在设置里显式确认过（不是默认勾选、不是「顺便」）
     * @param currentLoadable 当前在用版本**刚刚**被成功加载过（不是「数据库里写着 READY」——
     *   那正是它坏掉时仍然显示的值，用它当闸门等于没有闸门）
     */
    public fun deletable(userConfirmed: Boolean, currentLoadable: Boolean): List<ModelAssetRecord> {
        if (!userConfirmed) return emptyList()
        val out = ArrayList<ModelAssetRecord>(incomplete.size + archives.size)
        out += incomplete
        if (currentLoadable) out += archives
        return out
    }

    /** 为什么现在不能删（`null` = 可以删）。UI 直接拿它做 i18n key。 */
    public fun blockedReasonKey(userConfirmed: Boolean, currentLoadable: Boolean): String? = when {
        !userConfirmed -> "err_model_cleanup_need_confirm"
        archives.isNotEmpty() && !currentLoadable -> "err_model_cleanup_current_unverified"
        else -> null
    }

    /** 清理能腾出的字节数（只计我们**确定**知道大小的那些）。 */
    public fun reclaimableBytes(userConfirmed: Boolean, currentLoadable: Boolean): Long =
        deletable(userConfirmed, currentLoadable).sumOf { it.expectedBytes }
}
