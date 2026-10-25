package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Severity
import com.ake.core.common.Outcome
import com.ake.core.common.Ulid
import com.ake.core.ports.AgentProfile
import com.ake.core.ports.AgentProfileDraft
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.AgentProfileSummary
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ProfileOrigin
import com.ake.core.ports.TrustScanVerdict
import com.ake.core.ports.TrustTier
import com.ake.data.persist.bootstrap.TemplateCapabilityBaseline
import com.ake.data.persist.db.dao.AgentProfileDao
import com.ake.data.persist.db.dao.CapabilityGrantDao
import com.ake.data.persist.db.dao.ChatMessageDao
import com.ake.data.persist.db.dao.ChatSessionDao
import com.ake.data.persist.db.dao.MemoryDao
import com.ake.data.persist.db.entity.AgentCapabilityGrantEntity
import com.ake.data.persist.db.entity.AgentProfileEntity
import com.ake.data.persist.guard.CharterScanner
import com.ake.data.persist.redact.PersistenceRedactionGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `AgentProfileStorePort` 的实现（§3.8 / §8.5）。
 *
 * ## ★ 两条不可让步的判据
 *
 *  1. **`upsert` 内部必须跑 `CharterScanner`；命中 REFUSED 时不允许写库。**
 *  2. **完全删除必须逐表枚举，不依赖 CASCADE**（v2 §2.3）：
 *     授权 → 会话 → 消息 → **人格记忆** → 人格。
 *
 * ## ⚠ 删人格会连带删掉它记住的东西（§8.5 选项 A）
 *
 * > **溯源可以断，归属不能悬空。**
 * > `memory_card.source_task_id = SET NULL`（任务删了记忆还在，因为记忆的拥有者是用户）；
 * > `memory_card.agent_id` 的语义是「这条记忆**属于哪个人格**」，人格没了归属就没了。
 *
 * 这是一个**用户可见**的破坏性动作 ⇒ UI 的二次确认必须明示
 * 「会同时删除它记住的东西」。**不能让用户在不知情的情况下丢掉记忆。**
 *
 * ## ★ `agent_id` 系统生成、不可改（§9-U9）
 *
 * 与 `McpServerEntity` 那条注释同一个坑：改名会让 MCP / AppFunctions /
 * 未来 Cloud 的绑定失效。用户可改的只有 `name`。
 */
public class AgentProfileStoreImpl internal constructor(
    private val profiles: AgentProfileDao,
    private val grants: CapabilityGrantDao,
    private val sessions: ChatSessionDao,
    private val messages: ChatMessageDao,
    private val memory: MemoryDao,
    private val gate: PersistenceRedactionGate,
    private val clock: Clock,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : AgentProfileStorePort {

    override fun profiles(includeArchived: Boolean): Flow<List<AgentProfileSummary>> {
        val src = if (includeArchived) profiles.observeArchived() else profiles.observeActive()
        return src.map { rows -> rows.map { it.toSummary() } }
    }

    override suspend fun byId(id: String): Outcome<AgentProfile> = load(profiles.byId(id))

    override suspend fun byAgentId(agentId: String): Outcome<AgentProfile> = load(profiles.byAgentId(agentId))

    /**
     * ★ 内部跑 `CharterScanner`。
     *
     * - `REFUSED`（N2 凭据猎取 / N3 静默外传）⇒ **不写库**，直接返回 Err；
     * - `FLAGGED`（N1/N4/N5）⇒ 写库，但 `alwaysConfirmSkills = true`；
     * - `SANITIZED`（N0）⇒ 写库，但落的是**裁剪后**的正文，被裁的子句进 `scan_hits_json` 之外的
     *   `TrustScanVerdict.clausesRemoved`，UI 必须显示「我们裁掉了什么」。
     */
    override suspend fun upsert(draft: AgentProfileDraft): Outcome<String> {
        val now = clock.nowMillis()
        val (verdict, text) = CharterScanner.scanAndText(draft.personaCharter, now)
        if (verdict.verdict == TrustScanVerdict.REFUSED) {
            AkeLog.w(TAG, "upsert refused: charter scan hits=${verdict.hits}")
            return Outcome.Err(
                AppError(AppError.CODE_PERMISSION, "err_charter_refused", severity = Severity.HIGH),
            )
        }

        val id = Ulid.next(now)
        val agentId = newAgentShortId(now)
        val sealed = gate.sealField(text, id.toByteArray())
        val row = AgentProfileEntity(
            id = id,
            agentId = agentId,
            name = draft.name.ifBlank { DEFAULT_NAME },
            avatarUri = draft.avatarUri,
            avatarKind = if (draft.avatarUri == null) {
                AgentProfileEntity.AVATAR_IDENTICON
            } else {
                AgentProfileEntity.AVATAR_USER_FILE
            },
            description = draft.description,
            personaCharterEnc = sealed.blob,
            personaChararterPreview = text.take(PREVIEW_CHARS),
            charterHash = sha256Hex(text),
            workingDirUri = draft.workingDirUri,
            modelId = com.ake.core.ports.DEFAULT_MODEL_ID,
            origin = if (draft.templateId == null) {
                AgentProfileEntity.ORIGIN_USER_DIRECT
            } else {
                AgentProfileEntity.ORIGIN_TEMPLATE_BUILTIN
            },
            trustTier = TrustTier.USER_AUTHORED.name,
            templateId = draft.templateId,
            templateRevision = null,
            signKeyId = null,
            scanVerdict = verdict.verdict,
            scanHitsJson = verdict.hits.joinToString(","),
            scannerVersion = CharterScanner.VERSION,
            alwaysConfirmSkills = if (verdict.verdict == TrustScanVerdict.FLAGGED) 1 else 0,
            maxEnabledSkills = DEFAULT_MAX_SKILLS,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
            archived = 0,
        )
        return runCatching {
            transaction {
                profiles.upsert(row)
                replaceGrants(id, draft.capabilityRequests, draft.templateId, now)
            }
        }.fold(
            onSuccess = { Outcome.Ok(id) },
            onFailure = { t ->
                AkeLog.w(TAG, "upsert failed: ${t.message}")
                Outcome.Err(AppError(AppError.CODE_IO, "err_profile_write", t))
            },
        )
    }

    /**
     * ★ 授权是**重写**而不是增量合并：draft 里的清单就是最终清单。
     *
     * 理由：`agent_capability_grant` **只存 granted=1 的行**，
     * 「无行」与 `granted=0` 在 §5.4 的公式里结果完全相同。
     * 若做增量合并，就会出现「上次勾的、这次没提」到底是保留还是撤销的歧义 ——
     * 这正是「有两种写法」最容易漂移的地方。
     *
     * ⚠ 数量上限（≤6，硬顶 12）由 `:chat` 的 `SkillRegistry` 在读取侧截断并 WARN，
     *   这里**不**静默截断 —— Store 不该替用户决定删掉哪个（§5.4）。
     *
     * ## ★★ 模板基线必须在这里合进去（否则「授权表为空 ⇒ 人格是哑的」）
     *
     * 从模板创建人格时，wanted = **模板的语义必需能力（baseline）** ∪ 用户自选。
     * 两件事促成了这个位置：
     *  1. **本类是 `agent_capability_grant` 的唯一写入方** —— 把「模板派生的人格不可能没有
     *     核心技能」这条不变量放在唯一写入点上，它才是结构性的；
     *     放在 `TemplateStoreImpl.instantiate` 则只挡住了一条调用路径。
     *  2. 调用方传空清单是**合法**的（`:ui` 的创建页在用户什么都没勾时就是这样），
     *     而空清单恰恰就是那只哑 Agent。基线在这里兜住它，且**不改变**用户勾选的含义：
     *     用户勾的照旧全部授予，基线只是把「模板成立了却跑不动」这个状态排除掉。
     *
     * ⚠ 基线**只对内置模板非空**（`TemplateCapabilityBaseline.requiredFor`）：
     *   市场模板的 `templateId` 拿不到基线 ⇒ §6.5 ③「授权不由 bundle 决定」原样成立。
     * ⚠ 这里仍然**只写本表**，不碰 `tool_registry.enabled` —— 两级授权取交集，
     *   Agent 级只能做减法（见 `CapabilityGrantStoreImpl` 类注释）。
     */
    private suspend fun replaceGrants(
        profileId: String,
        refs: List<CapabilityRef>,
        templateId: String?,
        now: Long,
    ) {
        grants.revokeAll(profileId)
        val final = TemplateCapabilityBaseline.merge(templateId, refs)
        if (final.isEmpty()) return
        grants.grantAll(
            final.map { ref ->
                AgentCapabilityGrantEntity(
                    agentId = profileId,
                    kind = ref.kind.name,
                    refId = ref.id,
                    granted = 1,
                    grantedAt = now,
                    updatedAt = now,
                )
            },
        )
    }

    override suspend fun touchUsed(id: String) {
        runCatching { profiles.touchUsed(id, clock.nowMillis()) }
            .onFailure { AkeLog.w(TAG, "touchUsed failed: ${it.message}") }
    }

    override suspend fun archive(id: String) {
        runCatching { profiles.archive(id, clock.nowMillis()) }
            .onFailure { AkeLog.w(TAG, "archive failed: ${it.message}") }
    }

    /** ★ 彻底删除：授权 → 会话 → 消息 → 人格记忆 → 人格（**不依赖 CASCADE**）。 */
    override suspend fun delete(id: String): Outcome<Unit> = runCatching {
        transaction {
            grants.revokeAll(id)                       // ① 授权
            val sessionIds = sessions.idsOfAgent(id)
            for (sessionId in sessionIds) {
                messages.deleteBySession(sessionId)    // ② 消息
            }
            sessions.deleteByAgent(id)                 // ③ 会话
            memory.deleteByAgent(id)                   // ④ 人格记忆（§8.5 选项 A）
            profiles.delete(id)                        // ⑤ 人格
        }
    }.fold(
        onSuccess = { Outcome.Ok(Unit) },
        onFailure = { t ->
            AkeLog.w(TAG, "delete failed $id: ${t.message}")
            Outcome.Err(AppError(AppError.CODE_IO, "err_profile_delete", t))
        },
    )

    private fun load(row: AgentProfileEntity?): Outcome<AgentProfile> {
        if (row == null) {
            return Outcome.Err(AppError(AppError.CODE_UNKNOWN, "err_profile_not_found"))
        }
        val text = row.personaCharterEnc?.let { gate.openField(it, row.id.toByteArray()) } ?: ""
        return Outcome.Ok(row.toDomain(text))
    }

    public companion object {
        public const val TAG: String = "AgentProfileStore"

        /** ★ 上游 §4.1 的「默认值原则：什么都不给」。 */
        public const val DEFAULT_NAME: String = "未命名 Agent"

        /** Q2 裁决：默认 6（硬顶 12）。 */
        public const val DEFAULT_MAX_SKILLS: Int = 6
        public const val HARD_MAX_SKILLS: Int = 12

        public const val PREVIEW_CHARS: Int = 160
    }
}

// ─────────────────────────────────────────────────────────────────────────────

internal fun AgentProfileEntity.toDomain(personaText: String): AgentProfile = AgentProfile(
    id = id,
    agentId = agentId,
    name = name,
    avatarUri = avatarUri,
    description = description,
    personaCharter = personaText,
    workingDirUri = workingDirUri,
    modelId = modelId,
    origin = runCatching { ProfileOrigin.valueOf(origin) }.getOrDefault(ProfileOrigin.USER_DIRECT),
    trustTier = runCatching { TrustTier.valueOf(trustTier) }.getOrDefault(TrustTier.USER_AUTHORED),
    templateId = templateId,
    templateRevision = templateRevision,
    signatureKeyId = signKeyId,
    trustScan = com.ake.core.ports.TrustScanVerdict(
        verdict = scanVerdict,
        clausesRemoved = emptyList(),
        hits = scanHitsJson.split(',').filter { it.isNotBlank() },
        scannedAt = updatedAt,
        scannerVersion = scannerVersion,
    ),
    alwaysConfirmSkills = alwaysConfirmSkills != 0,
    maxEnabledSkills = maxEnabledSkills,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    archived = archived != 0,
)

internal fun AgentProfileEntity.toSummary(): AgentProfileSummary = AgentProfileSummary(
    id = id,
    agentId = agentId,
    name = name,
    avatarUri = avatarUri,
    description = description,
    origin = runCatching { ProfileOrigin.valueOf(origin) }.getOrDefault(ProfileOrigin.USER_DIRECT),
    trustTier = runCatching { TrustTier.valueOf(trustTier) }.getOrDefault(TrustTier.USER_AUTHORED),
    lastUsedAt = lastUsedAt,
    archived = archived != 0,
    scanVerdict = scanVerdict,
)

internal fun newAgentShortId(now: Long): String {
    // ★ 对外短名：短、稳定、不含分隔符歧义。用 ULID 尾部 10 字符 + 固定前缀。
    val ulid = Ulid.next(now)
    return "ag-" + ulid.takeLast(10).lowercase()
}

internal fun sha256Hex(s: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
