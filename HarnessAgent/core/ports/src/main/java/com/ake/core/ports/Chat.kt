package com.ake.core.ports

import com.ake.core.common.Outcome
import com.ake.core.common.RedactionGate
import com.ake.core.contract.RiskClass
import kotlinx.coroutines.flow.Flow

// =============================================================================
// 聊天化 Agent 契约（`docs/05-chat-agents-design.md` §2.3 + §5 / §6.2）
//
// ★ 本文件是**纯新增**，没有修改 `:core:ports` 的任何既有文件。
//   它落在 L1 契约层的理由与 §2.3 一致：`:ui`（只能看见 core）与 `:data`、
//   `:chat`（L3/L4/L5，彼此不得横向依赖）都要看见同一批类型，
//   **除契约层之外没有任何地方能同时被这三者看见**。
//
// ★ 两处对上游文档**必须做的修正**（上游写的时候没有意识到 core 看不见 :agent）：
//   ① `BudgetEnvelope` 不能携带 `:agent` 的 `ContextBudget`（L1 不能反向依赖 L4）。
//      改为携带它的**投影** `ContextWindowFacts` —— 与本仓库既有的
//      `LoopBudgetFacts`（`Runtime.kt`）完全同一手法：投影是「同一份数字的两种看法」，
//      不是第二套预算。`:chat` 侧由真实 `ContextBudget` 构造它，一行不差。
//   ② `CharterScanner` 的产出 `TrustScanVerdict` 也放在这里（上游写在 `:agent`），
//      因为 `AgentProfileSummary` 要携带它给 UI。
// =============================================================================

// ─────────────────────────────────────────────────────────────────────────────
// 一、会话与消息
// ─────────────────────────────────────────────────────────────────────────────

/** 会话列表项。**首页列表的唯一数据源形状，不含任何 join 出来的量。** */
public data class ChatSessionSummary(
    val id: String,
    /**
     * ★★ 这是 `agent_profile.id`（ULID **主键**），不是对外短名 `agent_id`（`ag-xxxxxxxxxx`）。
     *
     * 名字有歧义，但语义由外键钉死：`chat_session.agent_id REFERENCES agent_profile(id)`。
     * 把这个值传给 [AgentProfileStorePort.byAgentId] 是安全的（读侧两个 id 空间都认），
     * 但**不要**把它当成 MCP / AppFunctions 用的那个短名去拼 AppFunction 标识。
     */
    val agentId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessageAt: Long,
    val messageCount: Int,
    /** 最近一条消息预览（已脱敏），列表页直接渲染 */
    val previewRedacted: String?,
    /** ★ 未读（主理人点名的首页三要素之一；§9-U10） */
    val unreadCount: Int,
    /** 用户已读到哪条 seq。 */
    val lastReadSeq: Int,
    /** 最近一次紧凑后的游标；恢复时从这里读历史 */
    val compactedUptoSeq: Int,
    /** 上次停在哪个模式 —— 决定复水用哪张 SegmentCaps 剖面 */
    val lastMode: LoopMode,
    val modeEpoch: Int,
    val archived: Boolean,
)

public enum class MessageRole { USER, ASSISTANT, SKILL_CALL, SKILL_RESULT, SYSTEM_NOTE }

public enum class MessageKind { TEXT, ROUTER_NOTE, SKILL_CALL, SKILL_RESULT, MODE_NOTE, COMPACT_BOUNDARY }

/**
 * 消息状态（数据库 §2.5）。
 *
 * ★ 它是**数据层的中间态**，不是 [ChatTerminalReason]。
 * 上游 Q6 裁决「聊天 MVP 只做终态」管的是 Port 层的 `ChatEvent.Terminal` ——
 * 两者分层，不得混为一谈，否则 UI 会被迫处理一套它不需要的枚举。
 */
public enum class MessageStatus { STREAMING, COMPLETE, ABORTED, FAILED }

public data class ChatMessageView(
    val id: String,
    val sessionId: String,
    val seq: Int,
    /** ★ 一轮内所有消息共享（§9-U4）。 */
    val turnId: String,
    val role: MessageRole,
    val kind: MessageKind,
    val status: MessageStatus,
    /** 已过 RedactionGate 的文本；UI 不得接触未脱敏原文 */
    val textRedacted: String,
    /** SKILL_CALL / SKILL_RESULT 时指向派生出的任务 */
    val taskId: String? = null,
    val skillRef: CapabilityRef? = null,
    /** ★ SKILL_CALL / SKILL_RESULT 的配对键（§9-U5）。 */
    val callId: String? = null,
    val createdAt: Long,
    val tokensEstimate: Int = 0,
    val compactionLevel: Int = 0,
)

/** 追加一条消息的入参。**显式投影过的领域对象，不是 Entity。 */
public data class AppendMessage(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val role: MessageRole,
    val kind: MessageKind,
    /** 未脱敏原文；由 `:data` 侧过 `PersistenceRedactionGate` 后落盘。 */
    val text: String,
    /**
     * ★ 仅 USER 消息带：落库幂等键。
     *
     * **值由 `:chat` 从 `(sessionId, turnId)` 派生**（`ChatLoop.userMessageKey`），
     * 不是调用方那个随机入站 id —— 随机值在重试路径上必然每次都不同，
     * `UNIQUE(client_request_id)` 就永远不会命中（关千锤第 4 轮 P1-4）。
     * `:data` 侧靠 `INSERT OR IGNORE` + 该列的 UNIQUE 索引完成去重。
     */
    val clientRequestId: String? = null,
    val callId: String? = null,
    val taskId: String? = null,
    val skillRef: CapabilityRef? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val createdAt: Long,
)

public interface ChatSessionStorePort {
    /**
     * @param agentId `null` = 全部人格；否则按 `chat_session.agent_id` 过滤 ——
     *   **该列存的是 `agent_profile.id`（主键）**，所以这里要传主键，不是对外短名。
     */
    public fun sessions(agentId: String?, includeArchived: Boolean = false): Flow<List<ChatSessionSummary>>

    /**
     * 「这个 Agent 下开一个新会话」，返回**会话 id**。
     *
     * @param agentId 传 `agent_profile.id`（主键）或对外短名 `ag-xxxxxxxxxx` **都可以** ——
     *   实现必须归一到主键再落库（`chat_session.agent_id` 的外键指向 `agent_profile(id)`，
     *   传短名会被 SQLite 的外键直接拒掉，`PRAGMA foreign_keys = ON`）。
     *
     * ⚠ 这不是宽容，而是两个 id 空间在真实调用点**同时**出现：
     * UI 路由与 MCP 入站手里是短名，会话行 / 授权行 / 审计行里落的是主键。
     * 唯一的归一化点是实现层，不能让每个调用方各自记住"我该传哪一个"。
     */
    public suspend fun ensureForNewTurn(agentId: String, titleHint: String?): Outcome<String>
    public fun messages(sessionId: String, limit: Int): Flow<List<ChatMessageView>>

    /**
     * ★ 落盘失败必须让用户看见；这里是**同步**语义而不是轨迹表的 trySend 泳道。
     * 用户消息与终态提交走的是不可丢泳道（§4.4）。
     */
    public suspend fun append(msg: AppendMessage): Outcome<Unit>

    /** ★ 助手消息的占位行（流式开始前建行；`status = STREAMING`）。返回消息 id。 */
    public suspend fun beginAssistant(
        sessionId: String,
        turnId: String,
        messageId: String,
    ): Outcome<Unit>

    /** ★ 终态提交：收敛 STREAMING，并一次性记账 token。 */
    public suspend fun commitAssistant(
        messageId: String,
        status: MessageStatus,
        text: String,
        promptTokens: Int,
        decodeTokens: Int,
        kvCacheTokensAfter: Int,
        redactionCount: Int,
    ): Outcome<Unit>

    /**
     * 冷启动 sweep：把所有卡在 STREAMING 的收敛为 **FAILED**（§2.5 纪律 2）。
     *
     * 不能收敛成 `ABORTED` —— 那是「用户主动停」的语义。进程被杀留下的
     * `STREAMING` 若伪装成 `ABORTED`，等于把「进程死了」这类缺陷从统计里擦掉，
     * 将来查「为什么这条消息没回」时数据里根本看不见。
     */
    public suspend fun sweepStreaming(): Int

    /** ★ 焦点释放时写复水基准。**同步写**，是可丢泳道的反面（§4.6）。 */
    public suspend fun recordFocusRelease(
        sessionId: String,
        kvTokens: Int,
        rehydrateTokens: Int,
        mode: LoopMode,
        epoch: Int,
        profile: String,
    ): Outcome<Unit>

    public suspend fun markCompacted(sessionId: String, uptoSeq: Int, level: Int)
    public suspend fun markRead(sessionId: String, seq: Int)
    public suspend fun saveDraft(sessionId: String, draft: String?): Outcome<Unit>
    public suspend fun archive(sessionId: String)

    /** ★ 删除会话的**完整枚举**（§8.2）：解绑 task / 解绑 agent_session / 删消息 / 删会话 / 删文件。 */
    public suspend fun deleteSession(sessionId: String): Outcome<Int>
}

// ─────────────────────────────────────────────────────────────────────────────
// 二、人格与模板
// ─────────────────────────────────────────────────────────────────────────────

public enum class ProfileOrigin { USER_DIRECT, TEMPLATE_BUILTIN, TEMPLATE_MARKET }

public enum class TrustTier {
    /** 用户本机自行撰写入设 */
    USER_AUTHORED,

    /** 出厂内置模板 */
    BUILTIN,

    /** 市场下发 + 验签通过 + 静态扫描通过 */
    MARKET_VERIFIED,

    /** 验签通过但静态扫描命中 ⇒ 可用但强制二次确认 */
    MARKET_QUARANTINED,
}

/**
 * `CharterScanner` 的产出（上游 §8.3 S2）。
 *
 * ⚠ **它是正则手段，不完备**：防不住换词、同义改写、跨句构造。
 * 它只是「安装期的人性化提醒」，**真正的遏制是 S3（授权与文本解耦）与 S4（确认与流派无关）**。
 * 关千锤请注意：**不得在验收报告里写「已通过 CharterScanner 防御人设注入」。**
 */
public data class TrustScanVerdict(
    /** PASS / SANITIZED / FLAGGED / REFUSED */
    val verdict: String,
    /** 被裁掉的子句（仅 N0 触发） */
    val clausesRemoved: List<String>,
    /** 命中的规则号，如 ["N1","N4"] */
    val hits: List<String>,
    val scannedAt: Long,
    val scannerVersion: Int,
) {
    public companion object {
        public const val PASS: String = "PASS"
        public const val SANITIZED: String = "SANITIZED"
        public const val FLAGGED: String = "FLAGGED"
        public const val REFUSED: String = "REFUSED"

        public fun pass(now: Long, version: Int): TrustScanVerdict =
            TrustScanVerdict(PASS, emptyList(), emptyList(), now, version)
    }
}

public data class AgentProfile(
    val id: String,
    /** 对外短名（MCP / AppFunctions / 未来 Cloud 用它引用）。★ 系统生成、不可改（§9-U9） */
    val agentId: String,
    val name: String,
    val avatarUri: String?,
    val description: String,
    /** ★ 用户/模板人设正文，**不含 Charter Core** —— 那是常量，永不可编辑 */
    val personaCharter: String,
    /** Q5 裁决：MVP 只做记录 + SAF，不做 Owner 语义 */
    val workingDirUri: String?,
    val modelId: String,
    val origin: ProfileOrigin,
    val trustTier: TrustTier,
    val templateId: String?,
    val templateRevision: Int?,
    val signatureKeyId: String?,
    val trustScan: TrustScanVerdict,
    /** 由 trustScan 命中项自动置 true */
    val alwaysConfirmSkills: Boolean,
    /** 默认 6（Q2 裁决），硬顶 12 */
    val maxEnabledSkills: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    val archived: Boolean,
)

public data class AgentProfileSummary(
    val id: String,
    val agentId: String,
    val name: String,
    val avatarUri: String?,
    val description: String,
    val origin: ProfileOrigin,
    val trustTier: TrustTier,
    val lastUsedAt: Long?,
    val archived: Boolean,
    /** ★ UI 要据此挂警示徽标（FLAGGED / REFUSED 必须可见）。 */
    val scanVerdict: String,
)

public data class AgentProfileDraft(
    val name: String,
    val avatarUri: String?,
    val description: String,
    /** 用户/模板提供的人格文本。★ 不含 Charter Core */
    val personaCharter: String,
    val workingDirUri: String?,
    val capabilityRequests: List<CapabilityRef> = emptyList(),
    val templateId: String? = null,
)

public interface AgentProfileStorePort {
    public fun profiles(includeArchived: Boolean = false): Flow<List<AgentProfileSummary>>
    public suspend fun byId(id: String): Outcome<AgentProfile>
    public suspend fun byAgentId(agentId: String): Outcome<AgentProfile>

    /**
     * **新建**一个人格。★ 名字叫 `upsert` 是历史遗留，**本轮语义是纯新建**。
     *
     * ## ★★ 为什么这里没有 `update(profile)`（2026-10 裁决，请勿"顺手补上"）
     *
     * 上一版的 `AgentProfileDraft` 没有 id 字段，所以 [upsert] 每次都会生成新的
     * `Ulid` 与新的对外短名（`ag-xxxxxxxxxx`）——「编辑 Agent」因此**无法实现**，
     * UI 的人格详情页本轮是只读的。
     *
     * 看起来只要加一行 `suspend fun update(profile: AgentProfile): Outcome<Unit>` 就好了。
     * **不行**，因为这个签名会把授权链路的写口直接交给调用方：
     *
     * | 字段 | 若允许 `update(profile)` 整体覆写会怎样 |
     * |---|---|
     * | `trustTier` | 一个 `MARKET_QUARANTINED` 的人格可以被改成 `MARKET_VERIFIED` |
     * | `trustScan` / `alwaysConfirmSkills` | 二次确认可以被静默关掉（S4 失守） |
     * | `origin` / `templateId` / `signatureKeyId` | 溯源链可以伪造 |
     * | `agentId` | 对外短名可改 ⇒ MCP / AppFunctions / 未来 Cloud 的绑定全部失效（§9-U9） |
     * | `maxEnabledSkills` | 6 的裁决由调用方决定 ⇒ 上限形同虚设 |
     *
     * 这些字段的全部意义就是「**不由用户/模板决定**」。一个"整对象回写"的更新契约
     * 等于把它们交还给调用方 —— **特权提升是写在类型里的，不是靠实现自觉的**。
     *
     * 正确的形状是下一轮的窄契约（本轮**不做**，因为 UI 详情页已是只读，加了也没有调用方）：
     *
     * ```kotlin
     * // 只允许改这五项；其余由 Store 从既有行继承，调用方连表达的机会都没有。
     * // 内部仍必须重跑 CharterScanner（改了 personaCharter 就要重扫，charter_hash 是判据）。
     * public suspend fun update(id: String, patch: AgentProfilePatch): Outcome<Unit>
     * public data class AgentProfilePatch(
     *     val name: String? = null,
     *     val avatarUri: String? = null,
     *     val description: String? = null,
     *     val personaCharter: String? = null,
     *     val workingDirUri: String? = null,
     * )
     * ```
     *
     * ⇒ 本轮结论：**编辑不做**。调用 [upsert] 前 UI 必须保证这是一次"新建"。
     *
     * ## 其余不可让步的判据
     *
     * - 内部必须跑 `CharterScanner`；命中 `REFUSED` 时不允许写库。
     * - `SANITIZED`（N0）时落的是**裁剪后**的正文，被裁子句进 `TrustScanVerdict.clausesRemoved`，
     *   UI 必须显示「我们裁掉了什么」。
     * - 返回的 id 是 `agent_profile.id`（ULID 主键），**不是**对外的 `agentId` 短名。
     *   外键 / 会话 / 授权都按主键走，见 `CapabilityGrantPort` 的同类说明。
     */
    public suspend fun upsert(draft: AgentProfileDraft): Outcome<String>

    public suspend fun touchUsed(id: String)
    public suspend fun archive(id: String)

    /**
     * ★ 彻底删除：授权 → 会话 → 消息 → 人格记忆 → 人格，逐表删（不依赖 CASCADE）。
     * ⚠ 它会**连带删除该人格记住的东西**（§8.5 选项 A：溯源可以断，归属不能悬空）
     * ⇒ UI 的二次确认必须明示这一点。
     */
    public suspend fun delete(id: String): Outcome<Unit>
}

public enum class TemplateSource { BUILTIN, INSTALLED }

public data class TemplateTrust(
    val source: TemplateSource,
    /** 验签 keyId（市场来源才有） */
    val signatureKeyId: String?,
    val scan: TrustScanVerdict,
)

public data class AgentTemplate(
    val templateId: String,
    val revision: Int,
    val name: String,
    val avatarAsset: String?,
    val summary: String,
    val tags: List<String>,
    /** 人格正文（不含 Charter Core） */
    val charter: String,
    val suggestedCapabilities: List<CapabilityRef>,
    /** ★ 合规声明，进 SYSTEM 尾部、**不可编辑**（主理人 Q3 裁决） */
    val disclaimer: String? = null,
    val trust: TemplateTrust,
)

public data class ProfileOverrides(
    val name: String? = null,
    val avatarUri: String? = null,
    val description: String? = null,
    val personaCharter: String? = null,
    val capabilities: List<CapabilityRef>? = null,
)

public interface TemplateStorePort {
    public fun catalog(): Flow<List<AgentTemplate>>
    public suspend fun byId(templateId: String): Outcome<AgentTemplate>
    public suspend fun instantiate(templateId: String, overrides: ProfileOverrides): Outcome<String>
    /** ★ 读取 charter 的**唯一**入口（§9-U8：把 assets / 文件两种来源关在一个函数里）。 */
    public suspend fun readCharter(templateId: String): Outcome<String>
    /** 【预留接口】MVP 只跑本地 fixture，不接网络。 */
    public suspend fun import(signedBundlePath: String): Outcome<String>
}

// ─────────────────────────────────────────────────────────────────────────────
// 三、技能与能力授权
// ─────────────────────────────────────────────────────────────────────────────

/** 一个「能力引用」。三种来源共用同一个形状，让 AppFunctions / MCP Server / SkillRegistry 共享同一份描述。 */
public data class CapabilityRef(val kind: CapabilityKind, val id: String) {
    public companion object {
        public fun builtin(id: String): CapabilityRef = CapabilityRef(CapabilityKind.BUILTIN, id)
        public fun tool(toolId: String): CapabilityRef = CapabilityRef(CapabilityKind.MCP_TOOL, toolId)
        public fun plugin(id: String): CapabilityRef = CapabilityRef(CapabilityKind.PLUGIN, id)

        /** 持久化形状：`BUILTIN:builtin.browser_task`（落 `agent_capability_grant.ref_id` / `chat_message.skill_ref`）。 */
        public fun encode(ref: CapabilityRef): String = "${ref.kind.name}:${ref.id}"

        public fun decode(s: String): CapabilityRef? {
            val i = s.indexOf(':')
            if (i <= 0) return null
            val kind = runCatching { CapabilityKind.valueOf(s.substring(0, i)) }.getOrNull() ?: return null
            return CapabilityRef(kind, s.substring(i + 1))
        }
    }

    public fun encode(): String = "${kind.name}:$id"
}

public enum class CapabilityKind { BUILTIN, MCP_TOOL, PLUGIN }

public enum class SkillCostClass {
    /** 一次性 Tool Chip */
    CHEAP_SYNC,

    /** 带进度/中止的任务卡片 */
    LONG_RUNNING,
}

public enum class SkillConfirmPolicy { NEVER, FIRST_USE_PER_AGENT, ALWAYS }

public data class SkillSpec(
    val ref: CapabilityRef,
    val name: String,
    /** ★ 写给模型看的。直接决定 Router 的调用质量。 */
    val description: String,
    val jsonSchema: String,
    val riskClass: RiskClass,
    val costClass: SkillCostClass,
    val confirmPolicy: SkillConfirmPolicy,
    val maxWallClockMs: Long?,
    val enabled: Boolean,
)

public data class SkillCall(
    val callId: String,
    val ref: CapabilityRef,
    /** 已过解析 + schema 校验的参数。**不是模型原文**。 */
    val args: Map<String, String>,
)

public interface SkillRegistryPort {
    public fun all(): Flow<List<SkillSpec>>

    /**
     * 已按两级授权与数量上限收敛后的清单 —— **这是唯一允许注入 SYSTEM 段的来源**。
     *
     * 判据（§5.4）：`global.enabled AND agentGrant[ref] AND ref ∉ FORBIDDEN AND count ≤ 6`。
     */
    public suspend fun enabledFor(agentId: String): List<SkillSpec>

    public suspend fun invoke(call: SkillCall, ctx: SkillContext): Flow<SkillEvent>
}

/** ★ 只能做减法：决定「某个已全局开放的能力，对这个 Agent 是否可用」（§5.4）。 */
public interface CapabilityGrantPort {
    public suspend fun setGranted(agentId: String, ref: CapabilityRef, granted: Boolean)
    public fun grantsOf(agentId: String): Flow<Map<CapabilityRef, Boolean>>
}

// ─────────────────────────────────────────────────────────────────────────────
// 四、循环模式、焦点、预算信封
// ─────────────────────────────────────────────────────────────────────────────

public enum class LoopMode { REPLY, TASK }

/** Router 的一次裁决模式（与 `LoopMode` 刻意分开：一个是「本轮的产出形态」，一个是「循环所处的模式」）。 */
public enum class TurnMode { REPLY, SKILL_CALL }

public enum class FocusPriority { FOREGROUND_USER, DERIVED_TASK, BACKGROUND_HOUSEKEEPING }

public enum class FocusKind { REUSED, REHYDRATED, YIELDED }

public sealed interface FocusHolder {
    public data class Chat(val sessionId: String, val agentId: String) : FocusHolder
    public data class Task(val taskId: String, val parentSessionId: String?) : FocusHolder
}

/**
 * ★ 刻意**不是** `fun interface`：它还继承了 `AutoCloseable.close()`，
 * 而 `fun interface` 只允许一个抽象方法（Kotlin 会把继承来的 `close()` 也算进去）。
 */
public interface FocusLease : AutoCloseable {
    /** 已被抢占时为 false：持有方在每轮开始前查，查到就让出而不是硬撞 native。 */
    public fun isValid(): Boolean
}

/**
 * 单次推理焦点仲裁器。**进程级单例**。
 *
 * 为什么必须存在：F3 —— LiteRT-LM 一个 Engine 同时只允许一个 Conversation。
 * 它不是性能优化，是防止 native 抛 `FAILED_PRECONDITION` 的**正确性组件**。
 * 实现在 `:chat`（`com.ake.chat.loop.InferenceFocusArbiter`）。
 */
public interface InferenceFocus {
    /** 抢占式获取。已有持有者被让出（调用方负责向其抛 `FocusChanged(YIELDED)`）。 */
    public suspend fun acquire(holder: FocusHolder, priority: FocusPriority): Outcome<FocusLease>

    public fun current(): FocusHolder?
}

/**
 * `ContextBudget` 的**投影**（L1 不能依赖 `:agent` 的 L4 类型）。
 *
 * 与本仓库既有的 `LoopBudgetFacts` 同一手法：**同一份数字的两种看法**，不是第二套预算。
 * `:chat` 侧由真实 `ContextBudget` 构造它，于是「设置页显示一套窗口、主循环跑另一套」
 * 这种不一致在结构上不可能发生。
 */
public data class ContextWindowFacts(
    val tierName: String,
    val workingWindow: Int,
    val outputReserve: Int,
    val usablePromptTokens: Int,
)

/**
 * ★ 由 ChatLoop 交给技能的预算信封 —— 任务必须在给它的额度内干活（§2.6）。
 *
 * 携带的是 [ContextWindowFacts] 投影而不是 `:agent` 的 `ContextBudget`（见本文件头部的修正 ①）。
 */
public data class BudgetEnvelope(
    val context: ContextWindowFacts,
    /** TASK / TASK_IN_CHAT */
    val modeProfile: String,
    val maxSteps: Int,
    val maxWallClockMs: Long,
    val maxPrefillTokensTotal: Long,
    val maxDecodeTokensTotal: Long,
) {
    public companion object {
        public const val MODE_PROFILE_TASK: String = "TASK"
        public const val MODE_PROFILE_TASK_IN_CHAT: String = "TASK_IN_CHAT"
    }
}

public data class SkillContext(
    val agentId: String,
    val sessionId: String,
    val callId: String,
    val turnId: String,
    val budgetEnvelope: BudgetEnvelope,
    val abort: AbortSignal,
    /** ★ 人工确认闸门。任务内部触发的 CONFIRM 必须经它回到聊天气泡，而不是另一个对话框。 */
    val humanGate: HumanGate,
    val redactionGate: RedactionGate,
) {
    /** ★ `TaskSpec.id` 由本类派生，不由模型原文透传（§2.6 纪律 2）。 */
    public fun derivedTaskId(): String = "task-$callId"
}

public sealed interface SkillEvent {
    public data class Started(val callId: String, val derivedTaskId: String?) : SkillEvent
    public data class Progress(
        val stepSeq: Int,
        val kind: String,
        val detailRedacted: String?,
        val stepsDone: Int? = null,
        val maxSteps: Int? = null,
    ) : SkillEvent

    public data class NeedsConfirm(val reasonKey: String, val args: List<String>) : SkillEvent
    public data class Succeeded(val outcome: SkillOutcome) : SkillEvent
    public data class Failed(val code: String, val reasonRedacted: String?) : SkillEvent
}

public data class SkillOutcome(
    /** 目标 ≤ ~256 token，进 `<skill_result>` 段 */
    val summaryRedacted: String,
    val derivedTaskId: String?,
    val stepsDone: Int,
    val prefillTokensTotal: Long,
    val decodeTokensTotal: Long,
    val redactionCount: Int,
    /** NONE / FLAGGED / BLOCKED —— 复用 `InjectionIsolator` 的 Escalation 语义 */
    val escalation: String?,
)

// ─────────────────────────────────────────────────────────────────────────────
// 五、聊天主循环
// ─────────────────────────────────────────────────────────────────────────────

public interface ChatRunnerPort {
    public fun turn(req: ChatTurnRequest): Flow<ChatEvent>
}

public data class ChatTurnRequest(
    val sessionId: String,
    /**
     * 调用方的**入站请求标识**（MCP / AppFunctions / UI 各自生成），只用于日志与审计对齐。
     *
     * ## ★★ 它不是落库的幂等键（2026-10 修正，关千锤第 4 轮 P1-4）
     *
     * 这里原本写的是「幂等键：断网重发/重试也不产生第二条 assistant 消息」。
     * 那句话与实现相反，而且是**结构上不可能**成立的：重试由调用方发起，调用方每发起一次
     * 就生成一个新的随机值（`:ui` 的 `ChatViewModel.startTurn` 就是
     * `UUID.randomUUID()`），于是 `UNIQUE(client_request_id)` 一次都不会命中 ——
     * 用户点三次「重试」，会话里就有 4 条一模一样的用户消息。
     *
     * 「谁能保证同一个值」这件事只有一个答案：**这一轮的身份**，即 [turnId]
     * （重试必须复用它，见下）。所以幂等键由 `:chat` 从 `(sessionId, turnId)` 派生
     * （`ChatLoop.userMessageKey`），调用方传什么都不会影响去重。
     *
     * ⇒ 「重试同一轮」与「用户真的又发了一遍」的分界线是 **[turnId] 换没换**：
     *   前者复用 ⇒ 同一个幂等键 ⇒ `INSERT OR IGNORE` 命中冲突 ⇒ 不落第二条；
     *   后者新 turnId ⇒ 新键 ⇒ 正常落一条。
     */
    val clientRequestId: String,
    /**
     * 轮次分组键。与 `clientRequestId` 分开：后者是入站标识，**幂等去重与重试都以本字段为准**
     * （重试复用、新消息新建，§9-U4）。
     */
    val turnId: String,
    val userText: String,
    val abort: AbortSignal = NoAbortSignal,
    val routerPolicy: RouterPolicy = RouterPolicy.AUTO,
    val allowSkillInvocation: Boolean = true,
    /** 用户本次是否同意出云。MVP 恒 false ⇒ `RoutingPort` 必然裁决 LOCAL_ONLY（Q4）。 */
    val allowEgress: Boolean = false,
)

public enum class RouterPolicy { CONSTRAINED_ROUTER, SENTINEL_INLINE, AUTO }

/** ★ 与 `TaskEvent` 同源同形：**最后一条是终结语**。 */
public sealed interface ChatEvent {
    public val sessionId: String

    /** 获得/重建/让出推理焦点。UI 显示「正在恢复记忆…」【初始值不得硬编码乐观值】 */
    public data class FocusChanged(
        override val sessionId: String,
        val kind: FocusKind,
        val prefillTokens: Int,
        val elapsedMs: Long,
    ) : ChatEvent

    /** 路由器裁决。暴露给用户是为了让他看见「Agent 决定去做一件事」，而不是黑箱跑 40 秒。 */
    public data class RouterPlanned(
        override val sessionId: String,
        val mode: TurnMode,
        val skillRef: CapabilityRef?,
        val argsRedacted: String?,
    ) : ChatEvent

    /** 助手增量。delta 是**新增的那一段**而非累计全文（与既有 `TaskEvent.TokenDelta` 同一约定）。 */
    public data class AssistantDelta(
        override val sessionId: String,
        val messageId: String,
        val delta: String,
    ) : ChatEvent

    public data class SkillStarted(
        override val sessionId: String,
        val callId: String,
        val skillRef: CapabilityRef,
        val labelRedacted: String?,
        val derivedTaskId: String?,
    ) : ChatEvent

    /** 任务步进的**粗粒度**投影。刻意不是每一步一条，否则 UI 会被刷爆。 */
    public data class SkillProgress(
        override val sessionId: String,
        val callId: String,
        val kind: String,
        val detailRedacted: String?,
        val stepSeq: Int?,
        val stepsDone: Int?,
        val maxSteps: Int?,
    ) : ChatEvent

    public data class ConfirmRequested(
        override val sessionId: String,
        val callId: String,
        val skillRef: CapabilityRef,
        val reasonKey: String,
        val args: List<String>,
    ) : ChatEvent

    public data class SkillResult(
        override val sessionId: String,
        val callId: String,
        val summaryRedacted: String,
        val taskId: String?,
        val redactionCount: Int,
        /** NONE / FLAGGED / BLOCKED */
        val escalation: String?,
    ) : ChatEvent

    /** ★ 循环模式切换。代价显式记账：每次切换付一次 prefill，必须可见。 */
    public data class ModeSwitched(
        override val sessionId: String,
        val from: LoopMode,
        val to: LoopMode,
        val epoch: Int,
        val prefillTokens: Int,
    ) : ChatEvent

    public data class Compacted(
        override val sessionId: String,
        val uptoSeq: Int,
        val level: Int,
        val droppedTurns: Int,
    ) : ChatEvent

    public data class Terminal(
        override val sessionId: String,
        val messageId: String?,
        val reason: ChatTerminalReason,
    ) : ChatEvent
}

/**
 * ★ 与 Task 的十二终态 `TerminalReason` 刻意分开。
 * 理由与既有 `TerminalReason` vs `SuspendReason` 的分开完全一致：
 * 混在一个枚举里必然写出错的 UI 分支。
 */
public enum class ChatTerminalReason {
    COMPLETED,
    USER_ABORT,
    MODEL_FAILED,
    BUDGET_EXHAUSTED,
    POLICY_DENIED,
    SKILL_FAILED,
    MODEL_NOT_LOADED,

    /**
     * ★ **请求没能写进存储 ⇒ 这一轮根本没开始（一个 token 都没生成）。**
     *
     * 它和 [STORE_FAILED] 的分界线是**时间线**，不是措辞偏好：
     *
     * ```
     *   ① 落 USER 消息      ──✗──> REQUEST_STORE_FAILED   （还没有"回答"这个东西）
     *   ② 建助手占位行      ──✗──> REQUEST_STORE_FAILED   （还没有"回答"这个东西）
     *   ③ 模型生成 ……          （到这里才开始有"回答"）
     *   ④ 提交终态          ──✗──> STORE_FAILED           （回答已经流到屏幕上了）
     * ```
     *
     * ## 为什么必须拆成两档（关千锤第 4 轮验收 P1-1）
     *
     * [STORE_FAILED] 的语义是「模型答完了，但没存下来」，对应的文案是
     * 「回答生成好了，但没存下来」。而 ①② 两处失败时**一个 token 都没有产生** ——
     * 用户读到那句话，理解成「模型答了，只是我看不到」，于是去找回不存在的回答、
     * 反复重发同一个问题。**归因越界比多一个枚举贵得多。**
     *
     * ## 用户该做什么
     *
     * 先看存储（空间 / 迁移 / 权限），然后**把这句话重发一次** —— 而不是"重试补写"：
     * 没有任何东西可补写。因此本档 `retryable` 为 `true`，但文案必须指向存储，
     * 不能说「回答已经在内存里」。
     *
     * ## 两种触发面
     *
     * | 触发点 | 用户看到的事实 |
     * |---|---|
     * | `append(USER)` 返回 `Err` | 这句话没进库：会话里看不到它，重发是正确的动作 |
     * | `beginAssistant` 返回 `Err` | 这句话进库了，但没有可渲染的回答行：重发时幂等键会挡住重复的用户消息 |
     *
     * 两者对用户是同一件事（**没有回答**），所以共用一档 —— 这不是"一个码打天下"，
     * 而是「回答存在与否」这条唯一的语义边界决定的。
     */
    REQUEST_STORE_FAILED,

    /**
     * ★ **模型答完了，但没存下来。**
     *
     * 与 [MODEL_FAILED] 的区别不是措辞，而是**用户下一步该做什么**：
     *
     * | 终态 | 用户看到 | 用户会去做什么 | 有用吗 |
     * |---|---|---|---|
     * | `MODEL_FAILED` | 「模型出错了」 | 换模型 / 清缓存 / 重下权重 | 全都是白费 |
     * | `STORE_FAILED` | 「回答没存下来」 | 清存储 / 稍后再试 | 才是对症的 |
     *
     * 在加入本值之前，落盘失败只能**借** [MODEL_FAILED] 表达（`:chat` 的
     * `ChatLoop.STORE_FAILURE_REASON` 曾是它的别名），于是每一次「磁盘满 / 迁移失败 /
     * 事务被打回」都被归因成「模型坏了」—— 归因错了，重试多少次都不会好，
     * 用户看到的却是「可重试」。
     *
     * ## 语义边界（写在这里，防止后人把它当垃圾桶）
     *
     * 本值**只**表示「推理链路跑通了、产物在内存里、**回答的终态提交**失败」：
     *  - ✅ `commitAssistant` 返回 `Err`（重试一次之后仍失败）；
     *  - ❌ `append(USER)` / `beginAssistant` 失败（那是 [REQUEST_STORE_FAILED]：
     *    回答根本没开始生成，说"回答生成好了"是假话）；
     *  - ❌ 模型 `generate` 返回 `Err`（那是 [MODEL_FAILED]，与磁盘无关）；
     *  - ❌ 预算 / 策略 / 焦点（各有自己的值）。
     *
     * ## 可重试性
     *
     * **可重试，但重试的是「整轮重发」而不是「补写一次」** —— 本枚举不携带文本，
     * 模型产出的那一段只活在产生它的那一轮的内存里；一旦 `commitAssistant` 放弃，
     * 那一段就没有第二个持有者了。所以：
     *  - 瞬时抖动：由 `:chat` 在**同一轮内**对 `commitAssistant` 重试一次兜住（UPDATE by id，幂等）；
     *  - 真失败：把整轮重发（幂等键由 `ChatLoop.userMessageKey(sessionId, turnId)` 派生，
     *    USER 消息不会重复落盘）。
     *
     * ★ 因此 `:ui` 侧这一档的 `retryable` 应为 `true`，但**文案必须指向存储**
     *   （「清一点空间再试」），不能沿用模型失败那一条。
     */
    STORE_FAILED,

    /**
     * ★ **上一轮还没收尾，所以这一轮根本没开始。**
     *
     * ## 它为什么必须存在（同一条原则的第三个案例）
     *
     * 在加入本值之前，`ChatLoop.turn` 里 `enterFlight` 拒绝并发轮次时报的是
     * [POLICY_DENIED] —— 而那条路径上**没有任何裁决发生过**：没有
     * `PolicyPort.authorize`、没有出云裁决、没有授权表查询、没有人点过确认。
     * 所谓「安全策略拦下了这一步」是凭空的。
     *
     * 用户在屏幕上连点两次发送（或 `stop()` 之后立刻再发，几毫秒窗口内旧一轮还没
     * `exitFlight`）就会看到那句话，于是去翻设置、找那个不存在的策略开关 ——
     * 找不到就只能认为 App 坏了。**归因越界比多一个枚举贵得多**，这次拆分的理由
     * 与 [REQUEST_STORE_FAILED] / [STORE_FAILED] 完全同源。
     *
     * ## 语义边界（唯一判据：这一轮**为什么**没开始）
     *
     * | 真实发生的事 | 本值？ | 该用哪个 |
     * |---|---|---|
     * | 同一会话已有一轮在跑（`enterFlight` 拒绝） | ✅ | 本值 |
     * | 推理焦点被别的会话 / 派生任务占着（`FOCUS_BUSY`） | ✅ | 本值 |
     * | 出云裁决拒了 / 技能未授权 / `FORBIDDEN`（**真有一次裁决**） | ❌ | [POLICY_DENIED] |
     * | 模型或引擎没就绪（等收尾也没用，要等加载） | ❌ | [MODEL_NOT_LOADED] |
     * | 生成过程中失败 | ❌ | [MODEL_FAILED] |
     * | 存储 / 预算 | ❌ | [REQUEST_STORE_FAILED] / [STORE_FAILED] / [BUDGET_EXHAUSTED] |
     *
     * ## 为什么「焦点被占」也与「同一会话的上一轮」共用一档
     *
     * 两者对用户是**同一句话**：「现在有别的一轮（或别的任务）在跑，你这一轮还没开始，
     * 等它完再发」。能做的动作完全相同，终端事件里也没有第二个会话的 id 可点
     * （`ChatEvent.Terminal` 只带本会话 `sessionId`）—— 单开一个值只会让 `:ui`
     * 维护一句一模一样的话，而用户拿不到任何新信息。
     * 精确码（`FOCUS_BUSY`）留在**日志**里：**归因精度留给排障，文案分档只按用户下一步**。
     *
     * ## 可重试性
     *
     * `retryable = true`，但重试的是「**等前一轮结束，把这句话再发一次**」：
     * 本值不携带任何产物，也没有任何东西在后台替用户续跑。`:ui` 的重试必须复用
     * `(sessionId, turnId)` 派生出的同一个幂等键（`ChatLoop.userMessageKey`），
     * 否则「重发」会变成两条一模一样的用户消息。
     *
     * ⚠ **不许当未知失败的垃圾桶**：只允许 `enterFlight` 拒绝与 `FOCUS_BUSY` 两处产出。
     * 拿不准的失败应当新增一个值，而不是借这一个 —— 那正是本值存在的理由。
     */
    IN_FLIGHT,
}

// ─────────────────────────────────────────────────────────────────────────────
// 六、端云协同（Q4：本轮恒裁决 LOCAL，但接口与审计链路必须打通）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ★★ 推理目标 —— **sealed，不是 enum**（§11-A3 的落地）。
 *
 * ## 为什么必须是 sealed 而不是 enum
 *
 * 主理人红线：**不许用运行时 if 守边界**。
 * 「允许出云」这件事如果是一个 enum 值（`CLOUD_ASSISTED`），
 * 那「这次出云到底有没有许可」就只能在运行时靠 `require(permit != null)` 兜
 * —— 而 `require` 抛的是 `IllegalArgumentException`，**它出现在半年后某个调用点上，
 * 表现形式是一次崩溃，而不是一次编译失败**。
 *
 * sealed + [CloudAssisted] 携带 [EgressPermit] ⇒
 * **「想上云但没审计行」这种状态在类型上就构造不出来**（编译期绑死）。
 *
 * ## ★★ 三个变体，不是一个 enum 的两个值（这是一处被迫的偏差，必读）
 *
 * 架构 08 原文只给了两个变体：`LocalOnly` 与 `CloudAssisted(permit)`。
 * **直接照抄会与 §7.2 形成循环依赖**：
 *
 * ```
 *   §7.2  EgressPermitSink.open(ctx, decision) → permit
 *   §5.7  RoutingDecision.target : InferenceTarget
 *   §5.7  InferenceTarget.CloudAssisted(permit)
 *
 *   ⇒ 要 open() 就得先有一个 RoutingDecision；
 *     要构造 target = CloudAssisted(permit) 就得先有 permit；
 *     而 permit 正是 open() 的产物。
 *   ⇒ 死锁：一个 `decide()` 里带着 permit 的决策**永远构造不出来**。
 * ```
 *
 * 于是拆出一个 permit-free 的意图变体 [CloudRequested]：
 *
 * ```
 *   RouteContext.requested   : InferenceTarget  ∈ { LocalOnly, CloudRequested }   ← 持久化形态
 *   RoutingDecision.target   : InferenceTarget  ∈ { LocalOnly, CloudAssisted }    ← 运行时授权形态
 * ```
 *
 * 分工与 §2.4 是一致的：**「选了什么」与「被授权成什么」本来就是两件事**，
 * 前者要能落库（permit 不可持久化，它依赖进程内的 audit row id 与一次性许可次数）。
 *
 * ## 落库码
 *
 * `CloudAssisted` 的 permit **不落库**（它是一次性的运行时凭据）。
 * 因此从库里读回 `CLOUD_ASSISTED` 只能还原成 [CloudRequested] —— 这是**有意的**：
 * 库里存的是「当时裁决成了云端」，不是「现在还被允许出云」。
 */
public sealed interface InferenceTarget {

    /** 稳定落库码。 */
    public val code: String

    /**
     * ★ 兼容别名（**待拆**）：迁移前 `:data` 落库用的是旧 enum 的 `.name`。
     *
     * ⚠ 拆除条件：`:data` 的 v4 迁移（宋砚秋）落地后，
     *   `RouteDecisionSink.record` 改用 `decision.target.code`，
     *   并删除本成员。留着就是「看起来能用」的陷阱。
     */
    @Deprecated(
        message = "改用 InferenceTarget.code；DAO 侧落库码由 InferenceTarget.codeOf 解析",
        replaceWith = ReplaceWith("code"),
    )
    public val name: String get() = code

    /** 本机内推理。 */
    public data object LocalOnly : InferenceTarget {
        override val code: String get() = CODE_LOCAL_ONLY
    }

    /**
     * ★ 「**想**出云」这个意图。**没有 permit ⇒ 还不具备出云能力**。
     *
     * 这是 [RouteContext.requested] 一侧的取值：持久化形态，permit-free。
     */
    public data object CloudRequested : InferenceTarget {
        override val code: String get() = CODE_CLOUD_REQUESTED
    }

    /**
     * ★★ 「**允许**出云」这件事**必须携带一张 permit**。
     *
     * 构造 [CloudAssisted] 的唯一办法就是给一个 permit，
     * 而 permit 只能由 [EgressPermitSink.open]（已落库）产出。
     * ⇒ 「想上云但没审计行」这种状态**构造不出来**。
     */
    public data class CloudAssisted(val permit: EgressPermit) : InferenceTarget {
        override val code: String get() = CODE_CLOUD_ASSISTED
    }

    public companion object {
        public const val CODE_LOCAL_ONLY: String = "LOCAL_ONLY"
        public const val CODE_CLOUD_REQUESTED: String = "CLOUD_REQUESTED"
        public const val CODE_CLOUD_ASSISTED: String = "CLOUD_ASSISTED"

        /**
         * 落库码 → 目标。★ 无法识别返回 `null`（fail-closed：认不出来 ⇒ 不按云端处理）。
         *
         * ⚠ [CODE_CLOUD_ASSISTED] 还原成 [CloudRequested]：**permit 不落库**，
         *   库里的这行只说明「当时裁决成了云端」。
         */
        public fun codeOf(raw: String?): InferenceTarget? = when (raw) {
            CODE_LOCAL_ONLY -> LocalOnly
            CODE_CLOUD_ASSISTED, CODE_CLOUD_REQUESTED -> CloudRequested
            else -> null
        }

        // ── 兼容别名（★ 待拆，见文件头部说明）─────────────────────────────
        //
        // `:data` / `:chat` 里既有的 `InferenceTarget.LOCAL_ONLY` / `.CLOUD_ASSISTED`
        // 引用靠这两个别名继续编译。它们指向的是**意图形态**（permit-free），
        // 语义与迁移前在这两处是一致的：
        //   · `RouteDecisionSink.shouldAudit` 比较的是「请求了什么 / 裁决了什么」
        //   · `ChatLoop` 构造 `requested` 时本来也没有 permit
        //
        // ⚠ 拆除条件：`:data` 的 v4 迁移（宋砚秋）与 `:chat` 的 P5 接线各自落地后，
        //   这两个别名必须删除 —— 留着就是「看起来能用」的陷阱。

        @Deprecated(
            message = "改用 InferenceTarget.LocalOnly",
            replaceWith = ReplaceWith("InferenceTarget.LocalOnly"),
        )
        public val LOCAL_ONLY: InferenceTarget get() = LocalOnly

        @Deprecated(
            message = "请求出云改用 InferenceTarget.CloudRequested；" +
            "已获许可的出云用 InferenceTarget.CloudAssisted(permit)",
            replaceWith = ReplaceWith("InferenceTarget.CloudRequested"),
        )
        public val CLOUD_ASSISTED: InferenceTarget get() = CloudRequested

        /** 「permit-free 的取值集合」，给 `requested` 的遍历用。`CloudAssisted` 不在其中。 */
        public val entries: List<InferenceTarget> = listOf(LocalOnly, CloudRequested)
    }
}

/** 数据分级（上游 §6.2）。库侧只有「类别白名单」与「体积」两种表达，★ 没有任何一列能装原文。 */
public enum class DataClass { RED, AMBER, GREEN, INBOUND }

public data class RouteContext(
    val agentId: String,
    val sessionId: String,
    val turnId: String,
    /**
     * ★ 本轮**请求**的目标。
     *
     * ⚠ 它表达的是意图，不是授权 —— 取值只能是 `LocalOnly` / `CloudRequested`
     *   （`CloudAssisted` 需要 permit，而 permit 是 `decide()` **之后**才有的，
     *   见 [InferenceTarget] 头部的循环依赖说明）。
     */
    val requested: InferenceTarget,
    val userGrantedThisTurn: Boolean,
    val estimatedContextTokens: Int,
    val needsLongContext: Boolean,
    val payloadClasses: Set<DataClass>,
    /**
     * ★ 任务性质。**有默认值 ⇒ 既有调用点零改动**；新调用点必须显式给。
     *
     * ★★ [TaskKind.PageTask] 一旦取这个值，路由实现**必须**返回 `LocalOnly`
     * （由唯一实现 + 测试 + 抓包共同保证，§5.7）。
     */
    val kind: TaskKind = TaskKind.PureDialog,
    /**
     * ★ 页面元数据。**不含 DOM 原文**（与 `PromptContext.WithPageContext` 同一手法）。
     */
    val page: PageRef? = null,
) {
    public companion object {
        /** 纯对话。 */
        public fun pureDialog(
            agentId: String,
            sessionId: String,
            turnId: String,
            requested: InferenceTarget,
            userGrantedThisTurn: Boolean,
            estimatedContextTokens: Int,
            needsLongContext: Boolean,
            payloadClasses: Set<DataClass>,
        ): RouteContext = RouteContext(
            agentId = agentId,
            sessionId = sessionId,
            turnId = turnId,
            requested = requested,
            userGrantedThisTurn = userGrantedThisTurn,
            estimatedContextTokens = estimatedContextTokens,
            needsLongContext = needsLongContext,
            payloadClasses = payloadClasses,
            kind = TaskKind.PureDialog,
            page = null,
        )

        /**
         * ★ 页面任务。★ `page` **必填** —— 「造一个 PageTask 的 ctx」必须显式走这里，
         *   不能靠改字段蒙混。
         */
        public fun pageTask(
            agentId: String,
            sessionId: String,
            turnId: String,
            requested: InferenceTarget,
            userGrantedThisTurn: Boolean,
            estimatedContextTokens: Int,
            needsLongContext: Boolean,
            payloadClasses: Set<DataClass>,
            page: PageRef,
        ): RouteContext = RouteContext(
            agentId = agentId,
            sessionId = sessionId,
            turnId = turnId,
            requested = requested,
            userGrantedThisTurn = userGrantedThisTurn,
            estimatedContextTokens = estimatedContextTokens,
            needsLongContext = needsLongContext,
            payloadClasses = payloadClasses,
            kind = TaskKind.PageTask,
            page = page,
        )

        /** ★ 不变式：PageTask 必须带 page 元数据。由 D5 断言盯住。 */
        public fun isWellFormed(ctx: RouteContext): Boolean =
            ctx.kind !is TaskKind.PageTask || ctx.page != null
    }
}

/**
 * ★ 任务性质（§5.7）。
 *
 * ## 为什么是 sealed 而不是 `RouteContext` 上加 `allowCloud: Boolean`
 *
 * 后者正是主理人明令禁止的形态：boolean 配置项的结局必然是「某个调用点忘了判」，
 * 而它**没有任何编译期信号**。sealed ⇒ `when` 穷尽性会逼每一个路由实现表态。
 */
public sealed interface TaskKind {
    public data object PureDialog : TaskKind

    /**
     * ★★ 页面任务（会接触 DOM）。一旦取这个值，路由实现**必须**返回 `LocalOnly`。
     */
    public data object PageTask : TaskKind
}

/**
 * ★ 页面元数据。★ **不含 DOM 原文** —— 让「顺手把原文塞进来」在字段上就无处可放。
 */
public data class PageRef(val host: String, val domChars: Int, val elementCount: Int)

public data class RoutingDecision(
    val target: InferenceTarget,
    /** i18n，UI 必须显示给用户 */
    val reasonKey: String,
    /** 允许离开设备的类别白名单 */
    val allowedClasses: Set<DataClass>,
    val redactionApplied: Boolean,
    val decidedAt: Long,
)

/** ★ 唯一的出云决策入口。默认 deny。任何失败 / 异常 ⇒ LOCAL_ONLY。 */
public interface RoutingPort {
    public suspend fun decide(ctx: RouteContext): RoutingDecision
}

/**
 * ★ 唯一的出云出口。它的最后一道不是正则而是**类型断言**：
 * D0 类别的对象**在类型系统上**就不能被构造为 `OutboundPayload`。
 * **正则可以被绕过，类型不能。**
 */
public sealed interface OutboundPayload {
    /** D2：能力清单（SkillSpec 的 name/description/schema）。 */
    public data class CapabilityCatalog(val json: String) : OutboundPayload

    /** D2：抽象计划（无实体参数）。 */
    public data class AbstractPlan(val steps: List<String>) : OutboundPayload

    /** D2：版本号与延迟统计。 */
    public data class TelemetryFreeStats(val schemaVersion: Int) : OutboundPayload
}

public data class ScreenedPayload(val bytes: Int)

/** ★ 唯一的出云出口（接口与审计链路本轮打通；MVP 恒不走出口）。 */
public interface EgressGate {
    public fun screen(payload: OutboundPayload, decision: RoutingDecision): Outcome<ScreenedPayload>
}

/**
 * ★ 全仓**唯一一份**出境裁决常量（§7.5，收口「不一致 C」）。
 *
 * 迁移前存在两套互不相认的字面量：
 *  · `:data` 的 `RouteDecisionEntity.EGRESS_PASS` / `EGRESS_BLOCKED`
 *  · `:chat` 的 `ChatEgress.VERDICT_SCREENED` / `VERDICT_GATE_DENIED`
 * MVP 下两边都没被写进库（恒 null）所以没爆；一旦上云，`:chat` 会往库里写一个
 * DAO 层与前端统计都不认识的枚举值 —— 这正是「两份等价实现」的翻版，只是这次是常量。
 *
 * ⇒ 两套**同时删除**，改引用本对象。由 D3 断言（扫全仓字面量）盯住。
 */
public object EgressVerdict {
    public const val SCREENED: String = "SCREENED"
    public const val GATE_DENIED: String = "GATE_DENIED"
    public const val NOT_SENT: String = "NOT_SENT"
    public const val DELIVERED: String = "DELIVERED"
    public const val PARTIAL: String = "PARTIAL"
}
