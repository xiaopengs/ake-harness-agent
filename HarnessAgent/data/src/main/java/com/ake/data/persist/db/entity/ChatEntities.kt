package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天化 Agent 的 6 张新表（`docs/06-chat-database.md` §3.2–§3.5）。
 *
 * ## 三条本文件共用的纪律（照 `TaskEntities.kt` 头部）
 *
 *  - 主键一律 ULID TEXT（追加写）；时间一律 epoch millis `Long`；枚举一律存 TEXT。
 *  - 密文一律 `| ver:1B | iv:12B | ciphertext | tag:16B |`，且**只能**由
 *    `PersistenceRedactionGate.sealField()` 产出 —— 那条 CI grep
 *    （`rg -n "_enc\s*=" data/src | rg -v "gate\.sealField"`）是它的牙齿。
 *  - **不冗余 `agent_id` 到 `chat_message` 上**（§4.8）：经 `chat_session` 两跳可得，
 *    本轮没有「按人格查消息」的查询。将来要「按人格导出/删除」时是一次加列，不是重建。
 *
 * ## ★ 删除语义（主理人裁决 U3）
 *
 * `chat_session → agent_session` 是 **SET NULL**，不是 CASCADE：
 * 删对话不连带销毁它派生过的任务轨迹（§8.2 B3）。`agent_session` 那侧的列见
 * `TaskEntities.SessionEntity.chatSessionId`。
 */

/**
 * 人格载体（`AgentProfileStorePort` 的落点）。
 *
 * ## ★ `agent_id` 与 `id` 为什么要两个（§9-U9）
 *
 * `id` 是 ULID 主键（字典序即时间序 ⇒ 追加写）；`agent_id` 是**对外短名**
 * （MCP / AppFunctions / 未来 Cloud 用它引用本 Agent）。二者分开的理由与
 * `McpServerEntity` 那条注释完全一致：**改名会让已授权记录失效**。
 * 因此 `agent_id` 由系统生成、**不可改**；用户可改的只有 [name]（§3.2）。
 *
 * ## `persona_charter_enc` 是 R1
 *
 * 用户从剪贴板粘进来的人设很可能来自某个网页（上游 §8 T7），
 * 它既可能是隐私内容、也需要加密 ⇒ 走 `gate.sealField()`。
 */
@Entity(
    tableName = "agent_profile",
    foreignKeys = [
        ForeignKey(
            entity = TemplateRegistryEntity::class,
            parentColumns = ["template_id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        // Q-C1：人格列表（首页 / 创建页）：未归档在前，最近使用在前
        Index(name = "idx_profile_active_used", value = ["archived", "last_used_at"]),
        // Q-C2：按 agent_id 反查（MCP / AppFunctions 入站时）。UNIQUE 是语义的一部分
        Index(name = "idx_profile_agent_id", value = ["agent_id"], unique = true),
        // ★ 消 Room 的 MISSING_INDEX_ON_FOREIGN_KEY_CHILD warning（§0.2 V4：把 warning 清零）
        Index(name = "idx_profile_template", value = ["template_id"]),
    ],
)
data class AgentProfileEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "agent_id") val agentId: String,

    @ColumnInfo(name = "name") val name: String,
    /** filesDir 相对路径。identicon 为 null（不落盘 ⇒ 不在 `WipeEverythingUseCase` 清除范围内）。 */
    @ColumnInfo(name = "avatar_uri") val avatarUri: String?,
    /** ★ 决定 Wipe 要不要去删文件：IDENTICON 无文件（§7.4）。 */
    @ColumnInfo(name = "avatar_kind") val avatarKind: String,
    @ColumnInfo(name = "description") val description: String,

    // ── ⚠ R1 加密区
    @ColumnInfo(name = "persona_charter_enc") val personaCharterEnc: ByteArray?,
    @ColumnInfo(name = "persona_charter_preview") val personaChararterPreview: String,
    /** ★ 人设文本 sha256。与 scanner_version 一起决定「要不要重扫」。 */
    @ColumnInfo(name = "charter_hash") val charterHash: String?,

    // ── Q5 裁决：只做记录 + SAF，不做 Owner 语义 ⇒ 本列只是一个 URI 字符串
    @ColumnInfo(name = "working_dir_uri") val workingDirUri: String?,
    @ColumnInfo(name = "model_id") val modelId: String,

    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "trust_tier") val trustTier: String,

    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "template_revision") val templateRevision: Int?,
    @ColumnInfo(name = "sign_key_id") val signKeyId: String?,

    @ColumnInfo(name = "scan_verdict") val scanVerdict: String,
    @ColumnInfo(name = "scan_hits_json") val scanHitsJson: String,
    @ColumnInfo(name = "scanner_version") val scannerVersion: Int,

    @ColumnInfo(name = "always_confirm_skills") val alwaysConfirmSkills: Int,
    /** 默认 6（Q2 裁决），硬顶 12。 */
    @ColumnInfo(name = "max_enabled_skills", defaultValue = "6") val maxEnabledSkills: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Int,
) {
    companion object {
        const val AVATAR_IDENTICON: String = "IDENTICON"
        const val AVATAR_USER_FILE: String = "USER_FILE"
        const val AVATAR_TEMPLATE_ASSET: String = "TEMPLATE_ASSET"

        const val ORIGIN_USER_DIRECT: String = "USER_DIRECT"
        const val ORIGIN_TEMPLATE_BUILTIN: String = "TEMPLATE_BUILTIN"
        const val ORIGIN_TEMPLATE_MARKET: String = "TEMPLATE_MARKET"

        const val SCAN_PASS: String = "PASS"
        const val SCAN_SANITIZED: String = "SANITIZED"
        const val SCAN_FLAGGED: String = "FLAGGED"
        const val SCAN_REFUSED: String = "REFUSED"
    }
}

/**
 * Agent 级能力授权（两级授权里的第二级，**只能做减法**：上游 §5.4）。
 *
 * ## ★★ 本表只存 `granted = 1` 的行
 *
 * 公式是 `skillEnabled = global.enabled AND agentGrant[ref] AND ...`。
 * 由于「无行」与「granted=0」在该公式里**结果完全相同**，存 0 值行只会带来两件事：
 *  ① 表随「全局工具数 × Agent 数」膨胀（200 × 50 = 10,000 行噪声）；
 *  ② 出现两种表达同一语义的方式 —— 而「有两种写法」是所有漂移 bug 的起点。
 * 因此：`setGranted(false)` = `DELETE` 一行，`setGranted(true)` = `INSERT OR REPLACE`。
 *
 * `grantsOf(agentId): Flow<Map<CapabilityRef, Boolean>>` 契约**无需改动**：
 * 只对存在行返回 `true`，调用方照旧用 `map[ref] == true`。
 */
@Entity(
    tableName = "agent_capability_grant",
    primaryKeys = ["agent_id", "kind", "ref_id"],
    foreignKeys = [
        ForeignKey(
            entity = AgentProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    // ★ `agent_id` 是复合 PK 首列 ⇒ SQLite 的主键索引已覆盖「删人格时找子行」，
    //   不额外建索引（Room 的 MISSING_INDEX_ON_FOREIGN_KEY_CHILD warning 也不会报）。
)
data class AgentCapabilityGrantEntity(
    @ColumnInfo(name = "agent_id") val agentId: String,
    /** BUILTIN | MCP_TOOL | PLUGIN */
    @ColumnInfo(name = "kind") val kind: String,
    /** builtin 的 id / tool_registry.tool_id / 插件 id。**不建 FK**：上游已定（§2.4） */
    @ColumnInfo(name = "ref_id") val refId: String,
    @ColumnInfo(name = "granted", defaultValue = "1") val granted: Int,
    @ColumnInfo(name = "granted_at") val grantedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val KIND_BUILTIN: String = "BUILTIN"
        const val KIND_MCP_TOOL: String = "MCP_TOOL"
        const val KIND_PLUGIN: String = "PLUGIN"
    }
}

/**
 * 对话容器。**会话列表页的唯一数据源，不 join 任何表。**
 *
 * ## ★ `message_count` 同时是「下一个 seq」
 *
 * 见 `ChatMessageDao.appendWithin()`：分配 seq 与插入消息在**同一个事务**里，
 * `seq = message_count`，随后 `message_count + 1`。这样一次 seq 分配不需要额外的
 * `SELECT MAX(seq)`，且天然不冲突（`UNIQUE(session_id, seq)` 兜底），
 * 与 v2 `task.seq_in_session` 同一手法。
 *
 * ## 三个冗余列为什么值得
 *
 * `preview_redacted` / `message_count` / `unread_count` 都可由 `chat_message` 推导。
 * 冗余它们的唯一理由是**首页列表不能 join 一张万级表**：列表每 250 ms 可能因 Flow
 * 重发一次，join + GROUP BY 的成本与「列表排序要实时正确」相比不划算。
 * ★ 代价是它们必须**与消息写入在同一事务里更新**（§4.5 纪律 3）。
 *
 * ## 为什么没有 `status` 列（§2.6）
 *
 * 「正在生成」是进程内事实，由 F3（一个 Engine 同时只允许一个 Conversation）推出，
 * 整机同一时刻最多只有一个会话在生成。落盘只会得到一个永远对不上的陈旧值。
 * 冷启动要知道「上次哪个会话没聊完」，正确做法是查消息而不是查会话：
 * `SELECT DISTINCT session_id FROM chat_message WHERE status = 'STREAMING'` ——
 * 它就是事实本身，而会话表上的 `status` 只是事实的一份**会漂移的副本**。
 */
@Entity(
    tableName = "chat_session",
    foreignKeys = [
        ForeignKey(
            entity = AgentProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        // Q-S1 ★：首页对话列表 —— 未归档，最近消息在前
        Index(name = "idx_chat_sess_active_last", value = ["archived", "last_message_at"]),
        // Q-S2：Agent 详情页的会话列表 + 删人格时 CASCADE 找子行（消 Room warning）
        Index(name = "idx_chat_sess_agent", value = ["agent_id"]),
    ],
)
data class ChatSessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "agent_id") val agentId: String,

    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "message_count", defaultValue = "0") val messageCount: Int,

    /** 已过 RedactionGate 的最近消息预览。列表页直接渲染。 */
    @ColumnInfo(name = "preview_redacted") val previewRedacted: String?,

    // ── ★ 未读（主理人点名的首页三要素之一，§9-U10）
    @ColumnInfo(name = "unread_count", defaultValue = "0") val unreadCount: Int,
    /** 用户已读到哪条 seq。`markRead` 时取 `MAX(last_read_seq, given)`，防回退。 */
    @ColumnInfo(name = "last_read_seq", defaultValue = "0") val lastReadSeq: Int,

    // ── ★ 草稿：进程被杀时用户打了一半的字不该丢（§9-U2）。R1 加密
    @ColumnInfo(name = "draft_enc") val draftEnc: ByteArray?,
    @ColumnInfo(name = "draft_updated_at") val draftUpdatedAt: Long?,

    // ── 压缩与复水（上游 §3.5 明文要求）
    @ColumnInfo(name = "compacted_upto_seq", defaultValue = "0") val compactedUptoSeq: Int,
    @ColumnInfo(name = "compact_level", defaultValue = "0") val compactLevel: Int,
    @ColumnInfo(name = "context_profile") val contextProfile: String,
    /** ★ 复水时要知道上次停在哪个模式（REPLY / TASK）—— 决定用哪张 SegmentCaps */
    @ColumnInfo(name = "last_mode", defaultValue = "'REPLY'") val lastMode: String,
    @ColumnInfo(name = "mode_epoch", defaultValue = "0") val modeEpoch: Int,
    @ColumnInfo(name = "last_kv_cache_tokens", defaultValue = "0") val lastKvCacheTokens: Int,
    @ColumnInfo(name = "last_rehydrate_tokens", defaultValue = "0") val lastRehydrateTokens: Int,

    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Int,
) {
    companion object {
        const val MODE_REPLY: String = "REPLY"
        const val MODE_TASK: String = "TASK"

        const val PROFILE_NARROW: String = "NARROW"
        const val PROFILE_STANDARD: String = "STANDARD"
        const val PROFILE_WIDE: String = "WIDE"
    }
}

/**
 * 消息表。**本轮写入最热的表，也是与任务轨迹差异最大的一张。**
 *
 * ## ★ `turn_id`：轮次是 ER 里原本缺失的一层（§9-U4）🔴
 *
 * 上游的 `chat_message` 只有 `seq`（会话内单调序号），没有「轮」这个概念。
 * 但四件事都以轮为单位且全部无处落地：
 *   ① 上游 Q7 裁决「一次回答跨技能调用 ≤ 3 次 / 轮」⇒ 库侧无法断言；
 *   ② `ChatBudget` 的 `maxPrefillTokensPerTurn` 等**每轮**预算 ⇒ 无处记账；
 *   ③ §9.3 要求 fixture 记录「每轮 prefill/decode」⇒ 只能靠日志；
 *   ④ 幂等（`clientRequestId`）的语义边界本身就是「一次 turn」。
 * 成本：一列 + 一个索引。★ **不为此建第 7 张表**（§4.6）。
 *
 * ## ★ `call_id`：技能调用与结果的配对键（§9-U5）🔴
 *
 * 上游 `ChatEvent.SkillStarted/SkillResult` 与 `SkillEvent.*` 都有 `callId`，
 * 但持久化的 `chat_message` 原本**没有承载它的列** ⇒ 冷恢复后 UI 无法把
 * 「结果气泡」挂到「调用气泡」上，也无法把「确认请求」与「确认结果」配对。
 *
 * ## ★ `status`：见 §2.5 的状态机
 *
 * ## ★ `client_request_id` 的 UNIQUE 如何利用 SQLite 的 NULL 语义
 *
 * SQLite 的 UNIQUE 索引把 NULL 视为彼此不同 ⇒ 多行 NULL 不冲突。
 * 于是「只有 USER 消息带幂等键」这件事可以靠**一个普通 UNIQUE 索引**表达，
 * 不需要部分索引（Room 的 `@Entity` 也不支持部分索引，且部分索引与迁移校验
 * 的冲突风险未实测 —— §0.2 R-3）。重试时 `INSERT OR IGNORE` 命中冲突 ⇒ 0 行受影响。
 */
@Entity(
    tableName = "chat_message",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        // Q-M1 ★ 主查询：会话内分页 / 复水取最近 N 条。UNIQUE 同时兜住 seq 唯一
        Index(name = "idx_chat_msg_session_seq", value = ["session_id", "seq"], unique = true),
        // Q-M2 ★ 一轮内的技能调用计数（Q7 上限）、每轮预算聚合
        Index(name = "idx_chat_msg_turn", value = ["turn_id"]),
        // Q-M3 ★ 幂等：同一 clientRequestId 只落一条
        Index(name = "idx_chat_msg_request", value = ["client_request_id"], unique = true),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "seq") val seq: Int,
    /** ★ 一轮内所有消息共享。ULID。 */
    @ColumnInfo(name = "turn_id") val turnId: String,

    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "kind") val kind: String,
    /** ★ STREAMING | COMPLETE | ABORTED | FAILED。见 §2.5 */
    @ColumnInfo(name = "status") val status: String,

    /** ★ 仅 USER 消息有值。UNIQUE，NULL 不冲突。 */
    @ColumnInfo(name = "client_request_id") val clientRequestId: String?,
    /** ★ SKILL_CALL / SKILL_RESULT 配对。其余 kind 为 null。 */
    @ColumnInfo(name = "call_id") val callId: String?,

    // ── ⚠ R1 加密区。外置时为 null。
    @ColumnInfo(name = "content_enc") val contentEnc: ByteArray?,
    @ColumnInfo(name = "content_preview") val contentPreview: String,

    // ── 外置（对齐 v2 observation 的既有约定）
    @ColumnInfo(name = "payload_state", defaultValue = "'INLINE'") val payloadState: String,
    @ColumnInfo(name = "payload_file") val payloadFile: String?,
    @ColumnInfo(name = "payload_codec") val payloadCodec: String?,
    @ColumnInfo(name = "payload_bytes", defaultValue = "0") val payloadBytes: Int,

    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "skill_ref") val skillRef: String?,

    /** ★ 上游 §4.1：「这条回答是哪个推理者产生的」—— 未来出云时能追责 */
    @ColumnInfo(name = "model_namespace", defaultValue = "'local'") val modelNamespace: String,

    // ── 预算与复水记账（上游 §3.5 / §9.3 fixture 要求）
    @ColumnInfo(name = "prompt_tokens", defaultValue = "0") val promptTokens: Int,
    @ColumnInfo(name = "decode_tokens", defaultValue = "0") val decodeTokens: Int,
    @ColumnInfo(name = "kv_cache_tokens_after", defaultValue = "0") val kvCacheTokensAfter: Int,
    @ColumnInfo(name = "compaction_level", defaultValue = "0") val compactionLevel: Int,
    @ColumnInfo(name = "redaction_count", defaultValue = "0") val redactionCount: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** ★ 流式快照最后一次落盘时间。冷启时据此判断「中断了多久」。 */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val STATUS_STREAMING: String = "STREAMING"
        const val STATUS_COMPLETE: String = "COMPLETE"
        const val STATUS_ABORTED: String = "ABORTED"
        const val STATUS_FAILED: String = "FAILED"

        const val ROLE_USER: String = "USER"
        const val ROLE_ASSISTANT: String = "ASSISTANT"
        const val ROLE_SKILL_CALL: String = "SKILL_CALL"
        const val ROLE_SKILL_RESULT: String = "SKILL_RESULT"
        const val ROLE_SYSTEM_NOTE: String = "SYSTEM_NOTE"

        const val KIND_TEXT: String = "TEXT"
        const val KIND_ROUTER_NOTE: String = "ROUTER_NOTE"
        const val KIND_SKILL_CALL: String = "SKILL_CALL"
        const val KIND_SKILL_RESULT: String = "SKILL_RESULT"
        const val KIND_MODE_NOTE: String = "MODE_NOTE"
        const val KIND_COMPACT_BOUNDARY: String = "COMPACT_BOUNDARY"

        const val PAYLOAD_INLINE: String = "INLINE"
        const val PAYLOAD_EXTERNAL: String = "EXTERNAL"
        const val PAYLOAD_MISSING: String = "MISSING"
        const val PAYLOAD_DROPPED: String = "DROPPED"

        const val CODEC_GZIP: String = "gzip"

        /** §4.2：通用外置阈值（明文 UTF-8 字节数）。 */
        const val EXTERNAL_THRESHOLD_BYTES: Int = 6_144
        /** §4.2：SKILL_RESULT 的语义例外（它是从网页搬回来的内容，最该被优先清理）。 */
        const val EXTERNAL_THRESHOLD_SKILL_RESULT_BYTES: Int = 2_048
    }
}

/**
 * 已安装模板（含出厂内置）。
 *
 * ## 为什么落 Room 而不是 assets + 文件
 *
 * 与 v2 `ConfigEntities` 类注释第 1 条同理由：**清除范围只有一处判据。**
 * 模板带 `sign_key_id` / `scan_verdict` / `installed_at`，属于「这台机器上装过什么」
 * 的证据链，必须能被一键清除一并带走，也必须能被「按条撤销」。
 *
 * ## 内置模板的填充不放迁移里（上游 §7.3 已定）
 *
 * 模板是**内容**不是 schema。放迁移里会让「模板改一个字」变成一次 schema 变更。
 * 由 `bootstrap/TemplateBootstrap.kt` 按 `(template_id, revision)` 幂等写入（§6.7）。
 */
@Entity(
    tableName = "template_registry",
    indices = [
        Index(name = "idx_tpl_source", value = ["source"]),
    ],
)
data class TemplateRegistryEntity(
    /** 业务键（`builtin.video_director`），非 ULID —— 它是稳定标识符，改名 = 换模板 */
    @PrimaryKey @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "revision") val revision: Int,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "avatar_asset") val avatarAsset: String?,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    /** canonicalForm(manifest ∪ charter ∪ meta) 的 sha256 */
    @ColumnInfo(name = "charter_hash") val charterHash: String,

    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "sign_key_id") val signKeyId: String?,
    @ColumnInfo(name = "scan_verdict") val scanVerdict: String,

    /** 内置为 null（走 assets）；市场来源指向 filesDir 下的 charter 文件。§9-U8 */
    @ColumnInfo(name = "payload_file") val payloadFile: String?,
    /** 拼进 SYSTEM 尾部，不可编辑、不可被对话抹除（主理人 Q3 裁决） */
    @ColumnInfo(name = "disclaimer_enc") val disclaimerEnc: ByteArray?,

    @ColumnInfo(name = "installed_at") val installedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SOURCE_BUILTIN: String = "BUILTIN"
        const val SOURCE_INSTALLED: String = "INSTALLED"
    }
}

/**
 * 路由审计（`RoutingPort` 每一次裁决的落点）。
 *
 * ## ★★ 本表**不得**有任何承载 payload 原文的字段
 *
 * `payload_bytes` 是大小（数字），`*_classes_json` 是**类别白名单**（枚举集合），
 * 都不是内容。上游 §6.2 裁决 2 说「EgressGate 的最后一道不是正则而是类型断言」——
 * 库这一侧必须同构：**不存在一个可以把原文放进去的列**，于是「顺手记一份」
 * 在物理上做不到（§5.1 / §5.4）。
 *
 * ## MVP 恒裁决 LOCAL ⇒ 本表大概率恒为空
 *
 * 写行判据见 `store/RouteDecisionSink.shouldAudit()`：只在「有人想出云」、
 * 「实际出云了」、或 debug 包的强制记账开关下才落行。表空不代表链路没通。
 */
@Entity(
    tableName = "route_decision",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_message_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        // 消 Room 的 FK 子列 warning + 「这条消息 routing 了什么」
        Index(name = "idx_route_message", value = ["chat_message_id"]),
        // 保留策略清理（§5.6：90 天 + 行数上限）
        Index(name = "idx_route_decided_at", value = ["decided_at"]),
        // 「有没有真的出过云」—— 未来放开云端后的第一查询。
        // ★ §10.4 判为「可裁」（MVP 恒空表上的纯成本），本文仍建：
        //   加索引在空表上的成本是零，而「半年后没人知道是有意还是忘了」的代价不是。
        Index(name = "idx_route_decided", value = ["decided", "decided_at"]),
    ],
)
data class RouteDecisionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** 可为 null：路由裁决发生在生成之前，此时可能还没有消息行 */
    @ColumnInfo(name = "chat_message_id") val chatMessageId: String?,
    @ColumnInfo(name = "agent_id") val agentId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "turn_id") val turnId: String,

    /** LOCAL_ONLY | CLOUD_ASSISTED —— 用户/策略**请求**的目标 */
    @ColumnInfo(name = "requested") val requested: String,
    /** ★ 请求侧的分级集合。没有它，事后无法回答「当时想发什么」（§9-U6） */
    @ColumnInfo(name = "requested_classes_json") val requestedClassesJson: String,
    /** 实际裁决。MVP 恒 LOCAL_ONLY */
    @ColumnInfo(name = "decided") val decided: String,
    /** i18n key，UI 必须显示给用户 */
    @ColumnInfo(name = "reason_key") val reasonKey: String,
    @ColumnInfo(name = "allowed_classes_json") val allowedClassesJson: String,
    /** ★ EgressGate 的结论。恒 LOCAL 时为 null（根本没走出口） */
    @ColumnInfo(name = "egress_gate_verdict") val egressGateVerdict: String?,

    @ColumnInfo(name = "redaction_applied", defaultValue = "0") val redactionApplied: Int,
    /** ★ 「发了多少」的唯一载体 —— 只有数字，没有内容 */
    @ColumnInfo(name = "payload_bytes", defaultValue = "0") val payloadBytes: Int,
    /** ★ 用户撤回本次出云的时间（上游 §6.2 裁决 3 要求提供撤回） */
    @ColumnInfo(name = "revoked_at") val revokedAt: Long?,

    @ColumnInfo(name = "decided_at") val decidedAt: Long,
    @ColumnInfo(name = "schema_v", defaultValue = "1") val schemaV: Int,
) {
    companion object {
        const val TARGET_LOCAL: String = "LOCAL_ONLY"
        const val TARGET_CLOUD: String = "CLOUD_ASSISTED"

        const val EGRESS_PASS: String = "PASS"
        const val EGRESS_BLOCKED: String = "BLOCKED"

        /** §5.2：MVP 期间唯一的裁决理由键。 */
        const val REASON_MVP_CLOUD_DISABLED: String = "MVP_CLOUD_DISABLED"

        const val SCHEMA_VERSION: Int = 1
    }
}
