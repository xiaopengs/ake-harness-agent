package com.ake.data.persist.modeldist

import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState
import com.ake.data.persist.db.entity.ModelAssetEntity
import com.ake.data.persist.db.mapper.EntityMappers

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
 * ### 为什么还必须有第三道闸门：「陈旧」（时间）
 *
 * 本策略原先只有前两道。它们回答的是「**要不要**删」，回答不了「**什么时候**终于可以删」——
 * 于是清理只能二选一：立刻删（误删窗口=0，事故）或永远不删（撑爆磁盘）。
 * 时间维度把这两极连起来：够资格 + 够旧 ⇒ 才进待清理名单。
 * 默认值与它的「待产品决策」身份见 [ModelArchiveRetention.GRACE_DAYS_DEFAULT]。
 *
 * ### 为什么残缺件不需要「可加载」闸门
 *
 * CORRUPTED / 半成品不是「可用模型」，删掉它们不违反硬规则 1（那条保护的是**可用**模型）。
 * 给它们也加闸门只会让残缺件永久占位 —— 而它们往往正是吃掉空间的那部分。
 */
public object ModelArchiveRetention {

    /** 保留份数：1 = 当前在用，2 = 上一个可回滚版本。 */
    public const val KEEP_VERSIONS: Int = 2

    // ──────────────────────────────────────────────────────────────────────
    // ★★ 待产品决策（TODO-PRODUCT-DECISION-01）★「陈旧」宽限期
    //
    // ## 背景：本策略原本只有「份数」，没有「时间」
    //
    // `plan()` 初始形态只回答「第几份该留」，完全不关心「它有多久没人碰过」。
    // 缺了时间维度之后，唯一能挡住清理的就是那两道闸门（用户确认 / 当前可加载），
    // 于是任何一次自动清理都在「现在立刻删」和「永远不删」之间二选一 ——
    // 这是清理类功能最典型的两个事故形态（前者误删，后者撑爆磁盘）。
    //
    // ## 为什么默认值取 30 天 —— 以及为什么它**不是**产品答案
    //
    // 30 天取的不是一个「合理 retention」，而是**我们敢在最坏情况下兜得住**的下界：
    //   · 模型更新节奏以周~月计 ⇒ 用户有充足时间发现新版本有问题并回滚；
    //   · 一次长途出差 / 长假停用的设备不会被回到家第一天的清理打到；
    //   · 即便这个数错了，它的方向也是**偏保守**：取值偏大只会让空间晚一点回收。
    //
    // ⚠ 真正的 Product input 还没到（需要：目标盘占用上限、每月模型更新频率、
    //   用户是否愿意为「回滚零下载」付 2.6 GB）。**在那之前这个数是工程侧的兜底值**，
    //   不是产品决策结果。见到本注释的产品同学请直接改这里并删掉本段「待决策」标记。
    //
    // ★ 它是本策略里**唯一**允许在不改结构的前提下调整的数字：清理由
    //   `ArchiveRetentionSweep` 执行，它把这个值作为构造参数（默认取这里）。
    // ──────────────────────────────────────────────────────────────────────
    public const val GRACE_DAYS_DEFAULT: Int = 30

    public const val GRACE_MILLIS_DEFAULT: Long = GRACE_DAYS_DEFAULT.toLong() * 24L * 60L * 60L * 1000L

    /**
     * ★ **「一组账本行 → 清理裁决」的唯一算法入口。**
     *
     * `ModelAssetLedger.retentionPlan(modelId)`（查询入口）与 `ArchiveRetentionSweep`
     * （执行入口）都必须走这里，而不是各自 `map { ... }` 一遍 —— 两处各算一次的结果
     * 迟早会分叉。而「策略认为该留的」与「清理实际打算删的」一旦不是同一份名单，
     * 就是这类功能最难受的一种事故 —— 删错了行，且事后无从复盘。
     */
    public fun planOf(
        entities: List<ModelAssetEntity>,
        graceMillis: Long = 0L,
        nowMillis: Long = Long.MAX_VALUE,
    ): ModelRetentionPlan = plan(
        rows = entities.map(EntityMappers::modelAssetToDomain),
        graceMillis = graceMillis,
        nowMillis = nowMillis,
    )

    /**
     * 只按「份数」裁决，**完全不看时间**（既有语义，供纯策略测试与 `:ui` 的即时预览用）。
     */
    public fun plan(rows: List<ModelAssetRecord>): ModelRetentionPlan =
        plan(rows, graceMillis = 0L, nowMillis = Long.MAX_VALUE)

    /**
     * 带「陈旧闸门」的裁决。
     *
     * @param graceMillis 必须陈旧多久才够资格进入待清理名单（默认见 [GRACE_MILLIS_DEFAULT]）。
     * @param nowMillis 当前时间。
     *
     * ★ [keep] **永不**过这道闸门 —— 在用版本与上一个可回滚版本没有「多久没用」的概念，
     *   它们真正被需要的那一瞬间，可能就是三个月后的一次服务端 REVOKED。
     */
    public fun plan(
        rows: List<ModelAssetRecord>,
        graceMillis: Long,
        nowMillis: Long,
    ): ModelRetentionPlan {
        // 进位安全：`nowMillis = Long.MAX_VALUE` 时减一个正数不会溢出，反向则改用下界。
        val cutoff = if (nowMillis > Long.MIN_VALUE + graceMillis) nowMillis - graceMillis else Long.MIN_VALUE

        // 与 `ModelResolver` 选「在用」用的是同一把尺子（updatedAt 最大者），
        // 否则会出现「策略认为该保留的、和解析器认为在用的不是同一行」这种最难受的分叉。
        val byFreshness = rows.sortedByDescending { it.updatedAt }

        val ready = byFreshness.filter { it.state == ModelAssetState.READY && !it.localPath.isNullOrBlank() }
        // 按 id 排除而不是 `it !in ready`：后者走 data class 的 `equals`，
        // 两行恰好字段全等时会把其中一行误判成「已被保留」。
        val keepIds = ready.mapTo(HashSet()) { it.id }
        // 归档行没有 revision 列（回滚不靠它），所以用「新鲜度」当作版本的代理排序键。
        val keep = ready.take(KEEP_VERSIONS)
        // ★ 陈旧闸门：刚归档三天就把它算进「可清理」名单，等于把回滚窗口缩到三天。
        val archives = ready.drop(KEEP_VERSIONS).filter { it.updatedAt <= cutoff }
        val incomplete = byFreshness.filter { it.id !in keepIds && it.updatedAt <= cutoff }

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
