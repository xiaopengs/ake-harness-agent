package com.ake.data.persist.bootstrap

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.data.persist.db.dao.AgentProfileDao
import com.ake.data.persist.db.dao.CapabilityGrantDao
import com.ake.data.persist.db.dao.TemplateDao
import com.ake.data.persist.db.entity.AgentCapabilityGrantEntity
import com.ake.data.persist.guard.CharterScanner
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.ake.data.persist.store.sha256Hex

/**
 * 内置模板的启动引导（§6.7）。
 *
 * ## ★ 幂等 + 可升级：以 `(template_id, revision)` 为键
 *
 * ```
 * 不存在            → INSERT
 * revision 更高     → UPDATE（保留 installed_at）
 * 已是最新          → 什么都不做
 * ```
 *
 * 这样「模板改一个字」不会变成一次 schema 变更，**也不会在每次冷启时重写一遍**
 * （那会让 `updated_at` 每天都变，审计失去意义）。
 *
 * ## ★ 为什么不在迁移里做
 *
 * 上游 §7.3 已定（本文确认）：模板是**内容**不是 schema。
 * 放迁移里会让「模板改一个字」变成一次 schema 变更。
 *
 * ## ★★ 它同时负责「授权基线」的两端（本轮补的缺口）
 *
 * 模板装好了但授权表为空 ⇒ `agent_capability_grant` 无行 ⇒ §5.4 的交集判据恒 false
 * ⇒ 人格是哑的（聊天只回文字、永不派生任务），而用户看到的是「功能没接上」。
 * 本对象因此有两个入口：
 *
 *  - [bootstrap]：冷启调这一个 —— 写模板 + **回填存量人格的授权基线**；
 *  - [ensureBuiltins] / [ensureCapabilityGrants]：上面那条的两半，单独调也可以。
 *
 * 基线本身（哪个模板必须有哪个能力）在 `TemplateCapabilityBaseline`，
 * 那里还有三条 `require` 保证「只预置模板声明过的能力、不给全量、不碰全局开关」。
 */
public object TemplateBootstrap {

    public const val TAG: String = "TemplateBootstrap"

    /**
     * @return 本次**实际写入**的模板数（幂等重跑返回 0）。
     */
    public suspend fun ensureBuiltins(
        dao: TemplateDao,
        gate: PersistenceRedactionGate,
        clock: Clock,
    ): Int {
        val now = clock.nowMillis()
        var written = 0
        for (meta in TemplateAssets.builtins()) {
            // ★ 出厂模板照样跑一遍扫描：「我们自己写的」不是免检理由。
            val (verdict, _) = CharterScanner.scanAndText(meta.charter, now)
            if (verdict.verdict == com.ake.core.ports.TrustScanVerdict.REFUSED) {
                // 内置模板被自己的扫描器拒绝 = 我们写错了 ⇒ 大声说出来，但**不要**装作没发生
                AkeLog.w(TAG, "builtin template ${meta.templateId} REFUSED by scanner: ${verdict.hits}")
                continue
            }
            val disclaimerEnc = meta.disclaimerOrNull()?.let {
                runCatching { gate.sealField(it, meta.templateId.toByteArray()).blob }.getOrNull()
            }
            val row = meta.toEntity(
                installedAt = now,
                updatedAt = now,
                disclaimerEnc = disclaimerEnc,
                scanVerdict = verdict.verdict,
            )
            val existing = runCatching { dao.byId(meta.templateId) }.getOrNull()
            val changed = when {
                existing == null -> {
                    dao.insert(row)
                    true
                }

                existing.revision < meta.revision -> {
                    // ★ 保留 installed_at：「什么时候装的」不因为升级而漂移
                    dao.upsert(row.copy(installedAt = existing.installedAt))
                    true
                }

                else -> false
            }
            if (changed) written++
        }
        AkeLog.i(TAG, "ensureBuiltins done (written=$written)")
        return written
    }

    /**
     * ★★ 完整引导：模板 + **授权基线回填**。冷启调用这一个入口。
     *
     * ## 为什么回填必须存在（不是可选的「顺手补一下」）
     *
     * 授权基线是**本轮才有的**概念：在此之前建好的人格，`agent_capability_grant` 里
     * 一行都没有 ⇒ §5.4 的交集判据恒为 false ⇒ 升级到本版本之后它们**仍然是哑的**，
     * 用户看到的是「更新完还是不会干活」。只修「新建路径」等于只修了一半。
     *
     * ## 三条性质
     *
     *  ① **幂等**：走 `grantAllIfAbsent`（INSERT OR IGNORE），已存在的行**不动** ——
     *     因此重跑不会把 `granted_at` 刷成现在（那是审计证据）。
     *  ② **不推翻用户决定**：只补「授权表完全为空」的人格（见 [ensureCapabilityGrants]）。
     *     一旦某个人格有过哪怕一条授权，本回填就永远不再碰它 ——
     *     所以用户逐条撤销的能力不会被下一次冷启装回来。
     *  ③ **零噪音**：真补了行才打日志（`inserted > 0`），稳态下这次调用是 5 条 SELECT + 0 写入。
     *
     * @return [Summary]，两个数字都可以直接进日志 / 测试断言。
     */
    public suspend fun bootstrap(
        templates: TemplateDao,
        profiles: AgentProfileDao,
        grants: CapabilityGrantDao,
        gate: PersistenceRedactionGate,
        clock: Clock,
    ): Summary {
        val templatesWritten = ensureBuiltins(templates, gate, clock)
        val grantsInserted = ensureCapabilityGrants(profiles, grants, clock)
        AkeLog.i(TAG, "bootstrap done (templates=$templatesWritten, grantsBackfilled=$grantsInserted)")
        return Summary(templatesWritten = templatesWritten, grantsBackfilled = grantsInserted)
    }

    /**
     * 为「从内置模板实例化出来的人格」补齐 baseline 授权。
     *
     * ## ★ 判据是「这个人格的授权表**完全为空**」，不是「缺哪条补哪条」
     *
     * 差别的后果很实在：
     *  - 「缺哪条补哪条」会在**每次冷启**把用户手动撤销的核心能力又装回去 ——
     *    那是在跟用户意志打架，且完全静默；
     *  - 「授权表为空才补」只命中**本改动之前**的数据形态（那一批人格的行数恒为 0），
     *    补过一次之后行数 ≥ 1，规则自动不再命中 ⇒ **一次性、可收敛**。
     *    用户此后逐条撤销任何能力（包括核心能力），都不会被下一次冷启推翻。
     *
     * 极端情况仍然存在、并且是**刻意选的失败方向**：用户把某个人格的授权
     * **一条不剩**地全撤销 ⇒ 它退回「授权表为空」这个状态，下次冷启核心能力会回来。
     * 理由是「撤销到零」与「本改动之前建好的人格」在库里不可区分，
     * 而两种失败里 —— 「多给一个模板核心能力」远轻于「留一只永远不回话的哑 Agent」。
     *
     * 只遍历 [TemplateCapabilityBaseline.all] 的键（= 内置模板），
     * 因此市场模板的人格**永远不会**被回填 —— §6.5 ③ 的边界不在这一步被放宽。
     *
     * @return 真正插入的 grant 行数（幂等重跑为 0）。
     */
    public suspend fun ensureCapabilityGrants(
        profiles: AgentProfileDao,
        grants: CapabilityGrantDao,
        clock: Clock,
    ): Int {
        val now = clock.nowMillis()
        var inserted = 0
        var alreadyHad = 0
        for ((templateId, refs) in TemplateCapabilityBaseline.all) {
            val agentIds = runCatching { profiles.idsOfTemplate(templateId) }
                .onFailure { AkeLog.w(TAG, "idsOfTemplate($templateId) failed: ${it.message}") }
                .getOrDefault(emptyList())
            if (agentIds.isEmpty()) continue
            for (agentId in agentIds) {
                // ★ 已有任何授权的人格不碰：那说明它已经过了本改动的新建路径，
                //   或者用户已经在它身上做过决定（逐条撤销也不该被推翻）。
                val existing = runCatching { grants.countOf(agentId) }
                    .onFailure { AkeLog.w(TAG, "countOf($agentId) failed: ${it.message}") }
                    .getOrNull()
                    ?: continue
                if (existing > 0) {
                    alreadyHad++
                    continue
                }
                val rows = refs.map { ref ->
                    AgentCapabilityGrantEntity(
                        agentId = agentId,
                        kind = ref.kind.name,
                        refId = ref.id,
                        // ★ 恒为 1：本表不存 0 值行（见 CapabilityGrantDao 类注释）
                        granted = 1,
                        // 回填时「首次授予时间」只能取现在 —— 真实时间点已不可考，
                        // 而写 0 会让「早于 App 安装」这种不可能的时间进审计。
                        grantedAt = now,
                        updatedAt = now,
                    )
                }
                val rowIds = runCatching { grants.grantAllIfAbsent(rows) }
                    .onFailure { AkeLog.w(TAG, "backfill grants failed agent=$agentId: ${it.message}") }
                    .getOrDefault(emptyList())
                inserted += rowIds.count { it != -1L }
            }
        }
        if (inserted > 0) {
            AkeLog.i(TAG, "capability grants backfilled: $inserted row(s) (skipped $alreadyHad agent(s))")
        }
        return inserted
    }

    /** [bootstrap] 的结果。 */
    public data class Summary(
        /** 本次实际写入/升级的模板数。 */
        val templatesWritten: Int,
        /** 本次真正补上的授权行数（稳态为 0）。 */
        val grantsBackfilled: Int,
    )

    /** ★ 内容指纹变了也要升级：charter 改一个字但忘了升 revision 是最容易漏的一种。 */
    public fun charterFingerprint(charter: String): String = sha256Hex(charter)
}
