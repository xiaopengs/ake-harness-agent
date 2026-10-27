package com.ake.data.persist.modeldist

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.contract.ModelAssetState
import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.entity.ModelAssetEntity
import java.io.File

/**
 * # 归档清理的一次执行 —— `retentionPlan` 的生产调用方
 *
 * ## 为什么必须有这一类：`retentionPlan` 此前一个调用方都没有
 *
 * `ModelAssetLedger.retentionPlan(modelId)` 写完以后，工程里没有任何生产代码调它 ——
 * 它有完整的单测、有整页策略论证，唯独没有执行者。后果是静默的、单向的：
 *
 *  · 每次模型版本更新，旧文件被**改名归档**成一行（硬规则 1：绝不删除已装模型）⇒ +2.6 GB；
 *  · 一次中断的下载留下一个 `.part`，行还在、字节也还在，没有任何东西会再去动它；
 *  · 于是「App 用久了越占越多」，且用户收不到任何解释。
 *
 * 所以本类的第一职责不是「实现策略」，而是**存在并且真的被调用**：
 * 把账算清楚，把能安全回收的收回来，把不能自动删的量化出来交给 UI。
 *
 * ## ★★ 它能删什么：只有 `.part`，且五个条件缺一不可
 *
 * 「宁可少删，不可误删」在本类里不是口号，它落到五个必须同时成立的条件上。
 * 任意一个不成立就跳过这一行，并计入对应的 `skipped*` 计数（每个计数都有测试）：
 *
 *  | # | 条件                        | 它排除掉的是什么                            | 计数字段                |
 *  |---|-----------------------------|--------------------------------------------|-------------------------|
 *  | ① | 不在 `keep`（在用 + 上一个可回滚） | 用户正在用的，以及唯一的回滚退路            | 结构性排除（见下）       |
 *  | ② | 不是 `READY`                 | 任何一份可用模型 —— 硬规则 1，本类绝不跨越   | 结构性排除（见下）       |
 *  | ③ | **陈旧**：超过宽限期无人触碰   | 今天刚开始的下载、刚失败还等着被看到的下载   | `skippedFresh`          |
 *  | ④ | **此刻没人在读/写它**         | `VERIFYING` 的行、mtime 还很新的 `.part`    | `skippedBusy` / `skippedFresh` |
 *  | ⑤ | **没有任何残留价值**           | 还有字节可续传的行、仍指向一份可用模型的行   | `skippedResumable`      |
 *
 * ### 为什么 ①② 是「结构性排除」而不是一堆 if
 *
 * 因为它们不在本类里，而在 `ModelRetentionPlan` 的**类型**里：本类拿到的候选集是
 * `plan.incomplete`，而 `ModelArchiveRetention.plan()` 把所有 READY 且 `localPath` 非空的行
 * 都放进 `keep` / `archives`，它们**根本不会出现在 `incomplete` 中**。
 * 想删归档必须显式去取 `plan.archives` —— 这就是硬规则 1 落在类型上的样子。
 *
 * ### ★ 归档本体（READY 的老版本）本类一个都不删
 *
 * `ModelRetentionPlan.deletable()` 要 `userConfirmed` 与 `currentLoadable` 两道闸门同时在。
 * 冷启动这条路径上两个值都拿不到：
 *  - `userConfirmed` 按定义只能来自用户的显式动作；
 *  - `currentLoadable` 要求「刚刚被成功加载过」，而 `:data` 根本不认识 `ModelSession`。
 *
 * ⇒ 归档的删除留给既有的用户触发路径（`:ui` → `ModelLifecyclePort.deleteModelFile`）。
 *   本类只负责把它们**量出来**（`archivesAwaitingUser` / `bytesAwaitingUser`），
 *   让设置页可以说清楚「有 3 份旧版本合计 7.8 GB 可清理」，而不是让用户对着空转盘猜。
 *
 * ## 幂等 / 可重入（两条都必须成立，且都有测试）
 *
 *  - **幂等**：删完之后行上的 `temp_file` 被置 NULL ⇒ 下一轮它不再是候选，
 *    稳态报告的 `tempFilesDiscarded` 必然是 0。
 *    这与 `TaskReconciler.sweep()` 稳态命中 0 行同口径：看这个数就能判断「第二刀是空的」。
 *  - **可重入**：每个判据都在动手之前**重读一次该行**（`dao.byId`）；真正的清指针走
 *    `clearTempFile` 的 CAS（`AND temp_file = :name`），返回 0 行就整笔放弃（`skippedRaced`）。
 *    ⇒ 两个进程或两条协程同时扫，只会有一边真正删成功。
 *
 * ## 每行的执行顺序：先 CAS 摘指针 → 再删文件 → 删失败就把指针还回去
 *
 * 这与 `RetentionJob` 的纪律 1（先改 DB 指针，再删文件）同向，理由在这里更强一层：
 *
 *  - 摘指针是 CAS（`AND temp_file = :name`），它是本类**唯一**能识别「另一个写者刚刚动过
 *    这一行」的机制。只有先拿到它，才谈得上「不删正在被读/写的数据」——
 *    反过来（先删文件）等于把这个守卫降级成事后诸葛亮。
 *  - 删除失败时把指针还回去，是为了避开顺序反过来时的老问题：指针已摘、文件却还在
 *    ⇒ 那份文件从此没有任何行指向它，而磁盘泄漏正是这次要对付的敌人。
 *  - 「文件已经不在、指针还挂着」则是一个**自愈**的中间态：下一轮扫到它，
 *    发现文件不在，照样把指针摘掉。所以中断在任何一步都不会累积。
 *
 * ## 它跑在哪：由 `ModelAssetLedger` 自触发，跑在 `:data` 单线程 writer 上
 *
 * 与两个既有先例完全同形 —— `TaskStateWriter.init { writeScope.launch { reconciler() } }`
 * 与 `ColdStartCoordinator.init { scope.launch { run() } }`：装配方把钩子注入进来，
 * **没有任何人需要记得调它**。单写者纪律因此不被破坏，`:data` 也没有多出一条写线程。
 */
public class ArchiveRetentionSweep(
    private val dao: ModelAssetDao,
    /**
     * 模型文件目录。**必须与 `:llm` 的 `ModelDownloader.modelsDir` 是同一个目录**，
     * 否则本类删的是别处的垃圾，真正的 `.part` 一个字节都没少。
     */
    private val modelsDir: File,
    private val clock: Clock,
    /** 陈旧宽限期。默认取策略里那个待产品决策的兜底值。 */
    private val graceMillis: Long = ModelArchiveRetention.GRACE_MILLIS_DEFAULT,
) {

    /** 一轮扫描的结果。既是观测面也是单测的断言面，每个数字都可以直接进日志。 */
    public data class SweepReport(
        val modelIdsScanned: Int,
        val rowsScanned: Int,
        /** 真正被回收的 `.part` 数量。**稳态必须为 0**。 */
        val tempFilesDiscarded: Int,
        val bytesReclaimed: Long,
        /** 未过期（还在宽限期内）。 */
        val skippedFresh: Int,
        /** 此刻正在被校验 —— 绝不碰。 */
        val skippedBusy: Int,
        /** 还有残留价值（可续传的字节 / 仍指向一份可用模型）—— 保留。 */
        val skippedResumable: Int,
        /** 动手前发现这一行已被别人改过（CAS 打空），整笔放弃。 */
        val skippedRaced: Int,
        /** 归档本体：够资格，但要 userConfirmed + currentLoadable 才删，本类不动。 */
        val archivesAwaitingUser: Int,
        val bytesAwaitingUser: Long,
        val failures: List<String>,
    ) {

        /** 稳态判据：既没删东西也没出错。第二次及以后的每次冷启动都该是它。 */
        public val isQuiet: Boolean get() = tempFilesDiscarded == 0 && failures.isEmpty()
    }

    /**
     * 扫一轮。
     *
     * @return 报告。DB 不可用时返回空报告并记一条 failure —— **冷启动不该被一段 DB 异常堵死**。
     */
    public suspend fun runOnce(): SweepReport {
        val now = clock.nowMillis()
        val before = now - graceMillis

        val modelIds = runCatching { dao.distinctModelIds() }
            .onFailure { AkeLog.w(TAG, "扫描 model_id 列表失败：${it.message}") }
            .getOrDefault(emptyList())
        if (modelIds.isEmpty()) return empty()

        var rowsScanned = 0
        var discarded = 0
        var bytes = 0L
        var fresh = 0
        var busy = 0
        var resumable = 0
        var raced = 0
        var awaiting = 0
        var awaitingBytes = 0L
        val failures = ArrayList<String>(2)

        for (modelId in modelIds) {
            val entities = runCatching { dao.byModelId(modelId) }
                .onFailure { failures += "scan:$modelId:${it.message}" }
                .getOrDefault(emptyList())
            if (entities.isEmpty()) continue
            rowsScanned += entities.size

            val plan = ModelArchiveRetention.planOf(
                entities = entities,
                graceMillis = graceMillis,
                nowMillis = now,
            )

            // ★ 归档本体只统计、不删除（理由见类注释）。
            awaiting += plan.archives.size
            awaitingBytes += plan.archives.sumOf { it.expectedBytes }

            for (candidate in plan.incomplete) {
                when (val verdict = judge(candidate.id, before)) {
                    is Judge.Collect -> {
                        val target = fileOf(verdict.tempName)
                        val size = if (target.isFile) target.length() else 0L

                        // ① 先摘指针，而且必须是 CAS（`AND temp_file = :name`）。
                        //    打空 ⇒ 这一行在我们读它之后被别的写者改过（一次并发的
                        //    `beginOrResume` 换了新名字）⇒ **整笔放弃，一个字节都不碰**。
                        //    「宁可少删」在这里的具体形态：宁可这轮白跑，也不删别人的半成品。
                        val cleared = runCatching { dao.clearTempFile(verdict.id, verdict.tempName) }
                            .getOrDefault(0)
                        if (cleared == 0) {
                            raced++
                            continue
                        }
                        // ② 再删文件。文件本来就不存在也算成功 —— 那正是「上轮删了、
                        //    本轮来补摘指针」的自愈路径，它让中断在任何一步都不会累积孤儿。
                        if (!reclaim(verdict.tempName)) {
                            // 摘了指针却没删掉 ⇒ 把它还回去，否则这份文件从此没人认识。
                            runCatching { dao.restoreTempFile(verdict.id, verdict.tempName) }
                            failures += "delete:${verdict.tempName}"
                            continue
                        }
                        discarded++
                        bytes += size
                    }

                    is Judge.Skip -> when (verdict.reason) {
                        SkipReason.FRESH -> fresh++
                        SkipReason.BUSY -> busy++
                        SkipReason.RESUMABLE -> resumable++
                        SkipReason.NOOP -> Unit
                    }
                }
            }
        }

        val report = SweepReport(
            modelIdsScanned = modelIds.size,
            rowsScanned = rowsScanned,
            tempFilesDiscarded = discarded,
            bytesReclaimed = bytes,
            skippedFresh = fresh,
            skippedBusy = busy,
            skippedResumable = resumable,
            skippedRaced = raced,
            archivesAwaitingUser = awaiting,
            bytesAwaitingUser = awaitingBytes,
            failures = failures,
        )
        if (!report.isQuiet) {
            AkeLog.i(
                TAG,
                "retention sweep: discarded=$discarded bytes=$bytes " +
                    "fresh=$fresh busy=$busy resumable=$resumable raced=$raced " +
                    "awaitingUser=$awaiting (${awaitingBytes}B) failures=${failures.size}",
            )
        }
        return report
    }

    // ──────────────────────────────────────────────── 内部

    /** 对单个候选行的裁决。**每个判据都在动手之前重读一次该行**（可重入的前提）。 */
    private suspend fun judge(id: String, before: Long): Judge {
        val row = runCatching { dao.byId(id) }.getOrNull() ?: return Judge.Skip(SkipReason.NOOP)

        // ① 没有 `.part` 指针 ⇒ 没有可回收物，也不必往下判。
        val name = row.tempFile
        if (name.isNullOrBlank()) return Judge.Skip(SkipReason.NOOP)

        // ② VERIFYING：正在被校验 = 正在被读 ⇒ 绝不碰。
        if (row.state == ModelAssetState.VERIFYING) return Judge.Skip(SkipReason.BUSY)

        // ③ 陈旧：行上每一次记录在案的活动，都发生在宽限期之前。
        if (lastActivityAt(row) > before) return Judge.Skip(SkipReason.FRESH)

        // ⑤ 残留价值之一：这一行还指向一份可用模型（例如 CORRUPTED 槽位挡回来的旧件）。
        //    删它没有意义 —— 可用本体不在 `temp_file` 里，删掉只会让账本失去产权记录。
        if (!row.localPath.isNullOrBlank()) return Judge.Skip(SkipReason.RESUMABLE)

        // ⑤ 残留价值之二：有已下的字节 ⇒ 删了等于让用户已下的那部分白下载。
        if (row.bytesDownloaded > 0L && row.state != ModelAssetState.CORRUPTED) {
            return Judge.Skip(SkipReason.RESUMABLE)
        }

        // ④ 正在被写的文件，mtime 一定很新。这是唯一能看见「别人正在动它」的信号，
        //    比看 DB 更可靠 —— 写这个文件的那一侧不见得会顺手更新任何一列。
        if (fileOf(name).lastModified() > before) return Judge.Skip(SkipReason.FRESH)

        return Judge.Collect(id = row.id, tempName = name)
    }

    private fun reclaim(name: String): Boolean {
        val target = fileOf(name)
        return !target.isFile || target.delete()
    }

    /** 只接受 basename：历史上 `temp_file` 列有被写成绝对路径的记录。 */
    private fun fileOf(name: String): File = File(modelsDir, File(name).name)

    /**
     * 这一行最后一次「被碰过」的时间。
     *
     * ★ 必须看 entity 而不是 `ModelAssetRecord.updatedAt`：后者的取值是
     *   `lastUsedAt ?: downloadFinishedAt ?: verifiedAt ?: 0`，对一条**从没下完**的残缺行
     *   算出来是 0，即「Unix 元年的古董」⇒ 第一轮扫描就会把它判为过期。
     *   那正好越过宽限期本该保护的东西 —— 今天刚开始下载的那一行。
     *   所以这里把所有活动列（含 `downloadStartedAt` / `lastErrorAt`）取最大值。
     */
    private fun lastActivityAt(row: ModelAssetEntity): Long = maxOf(
        row.downloadStartedAt ?: 0L,
        row.downloadFinishedAt ?: 0L,
        row.verifiedAt ?: 0L,
        row.lastUsedAt ?: 0L,
        row.lastErrorAt ?: 0L,
    )

    private sealed interface Judge {
        data class Collect(val id: String, val tempName: String) : Judge
        data class Skip(val reason: SkipReason) : Judge
    }

    private enum class SkipReason { FRESH, BUSY, RESUMABLE, NOOP }

    private fun empty(): SweepReport = SweepReport(
        modelIdsScanned = 0,
        rowsScanned = 0,
        tempFilesDiscarded = 0,
        bytesReclaimed = 0L,
        skippedFresh = 0,
        skippedBusy = 0,
        skippedResumable = 0,
        skippedRaced = 0,
        archivesAwaitingUser = 0,
        bytesAwaitingUser = 0L,
        failures = emptyList(),
    )

    public companion object {
        public const val TAG: String = "ArchiveRetention"
    }
}
