package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Severity
import com.ake.core.common.Outcome
import com.ake.core.ports.AgentProfileDraft
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.AgentTemplate
import com.ake.core.ports.ProfileOverrides
import com.ake.core.ports.TemplateStorePort
import com.ake.data.persist.bootstrap.TemplateAssets
import com.ake.data.persist.db.dao.CapabilityGrantDao
import com.ake.data.persist.db.dao.TemplateDao
import com.ake.data.persist.db.entity.TemplateRegistryEntity
import com.ake.data.persist.guard.CharterScanner
import com.ake.data.persist.redact.PersistenceRedactionGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `TemplateStorePort` 的实现（§4.3 / §6.7 / §9-U8）。
 *
 * ## ★ `readCharter()` 是读 charter 的**唯一**入口
 *
 * 上游让内置模板走 assets、市场模板落文件 ⇒ **两条读取路径**，而内置模板升级
 * 会变成两套代码路径。本文的处理：
 *  - 本轮按上游原样实现（内置 `payload_file = null`，正文来自 `TemplateAssets`）；
 *  - 但**读取侧从第一天就只有一个函数** —— [readCharter] 把两种来源关在里面。
 *    将来内置也落文件时，只改这一处（§9-U8）。
 *
 * ## ★ 实例化出来的 Agent 是**独立的**
 *
 * `template_registry → agent_profile` 是 `SET NULL`：删模板不影响已实例化的 Agent。
 * 实例化之后人格是独立的（§2.4）。
 */
public class TemplateStoreImpl internal constructor(
    private val dao: TemplateDao,
    private val gate: PersistenceRedactionGate,
    private val profiles: AgentProfileStorePort,
    private val grants: CapabilityGrantDao,
    private val clock: Clock,
) : TemplateStorePort {

    /**
     * 目录流。
     *
     * ★ `charter` 只在**内置**模板上被填出来（来自 [TemplateAssets]）；
     *   市场模板的正文落在文件里，而 MVP 尚未接文件读取 ⇒ 那里留空并在日志里记一句。
     *   **UI 若要展示正文，一律走 [readCharter]** —— 那是唯一入口（§9-U8）。
     */
    override fun catalog(): Flow<List<AgentTemplate>> = dao.observeAll().map { rows ->
        val builtins = TemplateAssets.builtins().associateBy { it.templateId }
        rows.map { row ->
            val meta = builtins[row.templateId]
            row.toDomain(
                charter = meta?.charter.orEmpty(),
                capabilities = meta?.suggestedCapabilities ?: emptyList(),
                disclaimer = row.disclaimerEnc?.let { openSealed(it) },
            )
        }
    }

    override suspend fun byId(templateId: String): Outcome<AgentTemplate> {
        val row = runCatching { dao.byId(templateId) }.getOrNull()
            ?: return Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_template_not_found"))
        val meta = TemplateAssets.builtins().firstOrNull { it.templateId == templateId }
        return Outcome.Ok(
            row.toDomain(
                charter = meta?.charter.orEmpty(),
                capabilities = meta?.suggestedCapabilities ?: emptyList(),
                disclaimer = row.disclaimerEnc?.let { openSealed(it) },
            ),
        )
    }

    private fun openSealed(blob: ByteArray): String? =
        runCatching { gate.openField(blob, null) }.getOrNull()

    /**
     * ★ 实例化 = 用模板的人格创建 Agent。
     *
     * ⚠ **能力的授予永远不由 bundle 决定**（§6.5 ③）：
     * `suggestedCapabilities` 只是「建议清单」，最终仍由用户逐条确认后才写
     * `agent_capability_grant`。这里把它交给 `AgentProfileStorePort.upsert()`，
     * 由后者统一跑 `CharterScanner` —— **扫描只有一道门**。
     *
     * ## ★ 内置模板的「语义必需能力」在更下游被合入（本轮补的缺口）
     *
     * 创建页在用户什么都没勾时传下来的是**空清单**，而空清单 = 一只哑 Agent
     * （授权表无行 ⇒ §5.4 交集恒 false ⇒ 永不派生任务）。
     * 兜底点在 `AgentProfileStoreImpl.replaceGrants()`：它把
     * `TemplateCapabilityBaseline` 并进最终授权集 —— 那是 `agent_capability_grant`
     * 的**唯一写入方**，因此这条不变量对**所有**创建路径成立，而不只是本方法。
     * 本方法不做合并是刻意的：两处都写就会有两个「最终清单」的定义。
     *
     * ★ 市场模板拿不到基线（`requiredFor()` 只认内置 id）⇒ 上面那条 §6.5 ③ 原样成立。
     */
    override suspend fun instantiate(templateId: String, overrides: ProfileOverrides): Outcome<String> {
        val template = when (val r = byId(templateId)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return Outcome.Err(r.error)
        }
        val draft = AgentProfileDraft(
            name = overrides.name ?: template.name,
            avatarUri = overrides.avatarUri,
            description = overrides.description ?: template.summary,
            personaCharter = overrides.personaCharter ?: template.charter,
            workingDirUri = null,
            capabilityRequests = overrides.capabilities ?: template.suggestedCapabilities,
            templateId = template.templateId,
        )
        return profiles.upsert(draft)
    }

    /**
     * ★ 读 charter 的唯一入口。
     *
     * 内置：走 `TemplateAssets`（编译期常量）。市场：走 `payload_file`。
     * 两条路被关在同一个函数里 —— 这就是「将来统一时只改这一处」的含义。
     */
    override suspend fun readCharter(templateId: String): Outcome<String> {
        val row = runCatching { dao.byId(templateId) }.getOrNull()
            ?: return Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_template_not_found"))

        row.payloadFile?.let { file ->
            AkeLog.w(TAG, "market template charter from file is not wired yet: $file")
            return Outcome.Err(AppError(AppError.CODE_UNSUPPORTED, "err_template_file_unsupported"))
        }
        val builtin = TemplateAssets.builtins().firstOrNull { it.templateId == templateId }
            ?: return Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_template_charter_missing"))
        // ★ 指纹核对：库里的 charter_hash 与内容不符 ⇒ 模板被改过但 revision 没升
        if (row.charterHash != sha256Hex(builtin.charter)) {
            AkeLog.w(TAG, "charter hash mismatch for $templateId (revision ${row.revision})")
        }
        return Outcome.Ok(builtin.charter)
    }

    /** 【预留接口】MVP 不接网络。 */
    override suspend fun import(signedBundlePath: String): Outcome<String> = Outcome.Err(
        AppError(AppError.CODE_UNSUPPORTED, "err_template_import_unsupported"),
    )

    /** ★ 市场 bundle 的安装闸门（§6.5）：验签 → CharterScanner → 能力逐条确认。 */
    public suspend fun installMarket(
        row: TemplateRegistryEntity,
        charter: String,
    ): Outcome<String> {
        val now = clock.nowMillis()
        val (verdict, text) = CharterScanner.scanAndText(charter, now)
        if (verdict.verdict == com.ake.core.ports.TrustScanVerdict.REFUSED) {
            return Outcome.Err(
                AppError(AppError.CODE_PERMISSION, "err_charter_refused", severity = Severity.HIGH),
            )
        }
        return runCatching { dao.upsert(row.copy(scanVerdict = verdict.verdict, updatedAt = now)) }
            .fold(
                onSuccess = { Outcome.Ok(text) },
                onFailure = { t -> Outcome.Err(AppError(AppError.CODE_IO, "err_template_write", t)) },
            )
    }

    public companion object {
        public const val TAG: String = "TemplateStore"
    }
}

internal fun TemplateRegistryEntity.toDomain(
    charter: String,
    capabilities: List<com.ake.core.ports.CapabilityRef>,
    disclaimer: String?,
): AgentTemplate = AgentTemplate(
        templateId = templateId,
        revision = revision,
        name = name,
        avatarAsset = avatarAsset,
        summary = summary,
        tags = tagsJson.split(',').filter { it.isNotBlank() },
        charter = charter,
        suggestedCapabilities = capabilities,
        disclaimer = disclaimer,
        trust = com.ake.core.ports.TemplateTrust(
            source = runCatching { com.ake.core.ports.TemplateSource.valueOf(source) }
                .getOrDefault(com.ake.core.ports.TemplateSource.INSTALLED),
            signatureKeyId = signKeyId,
            scan = com.ake.core.ports.TrustScanVerdict(
                verdict = scanVerdict,
                clausesRemoved = emptyList(),
                hits = emptyList(),
                scannedAt = updatedAt,
                scannerVersion = CharterScanner.VERSION,
            ),
        ),
    )
