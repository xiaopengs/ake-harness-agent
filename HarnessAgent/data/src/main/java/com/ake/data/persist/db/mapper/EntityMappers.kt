package com.ake.data.persist.db.mapper

import com.ake.core.contract.ErrorCode
import com.ake.core.contract.InvocationStatus
import com.ake.core.contract.LoopCheckpoint
import com.ake.core.contract.MemoryCard
import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ObservationSummary
import com.ake.core.contract.PageChangeKind
import com.ake.core.contract.PolicyDecisionRecord
import com.ake.core.contract.RetentionClass
import com.ake.core.contract.StepAction
import com.ake.core.contract.StepRecord
import com.ake.core.contract.StepStatus
import com.ake.core.contract.TaskRecord
import com.ake.core.contract.ToolInvocationSummary
import com.ake.core.contract.ToolResult
import com.ake.core.contract.ToolSpec
import com.ake.core.contract.TraceEventRecord
import com.ake.core.contract.decodeSignalFlags
import com.ake.core.contract.decodeWarningFlags
import com.ake.data.persist.db.entity.MemoryCardEntity
import com.ake.data.persist.db.entity.ModelAssetEntity
import com.ake.data.persist.db.entity.ObservationEntity
import com.ake.data.persist.db.entity.PolicyDecisionEntity
import com.ake.data.persist.db.entity.StepEntity
import com.ake.data.persist.db.entity.TaskCheckpointEntity
import com.ake.data.persist.db.entity.TaskEntity
import com.ake.data.persist.db.entity.ToolInvocationEntity
import com.ake.data.persist.db.entity.ToolRegistryEntity
import com.ake.data.persist.db.entity.TraceEventEntity

/**
 * # Entity ↔ 领域模型的**唯一**转换点（数据库 §3.1 铁律 3）
 *
 * > 「铁律：`RoomDatabase` 子类、`@Entity`、`@Dao` 全部关在 `com.ake.data.persist.db.*` 之下，
 * >   其它任何 Gradle 模块都不得 import `androidx.room.*`。」
 *
 * 因此本文件（以及它的调用方 store/）是 `:core:contract` 与 Room 之间唯一的一层翻译。
 * 一旦有人在别处直接 new 一个 Entity，Room 注解就会顺着依赖树流进 L4，
 * 将来换存储实现时无处可藏。
 *
 * ## 关于 `StepAction` 的编解码
 *
 * 动作的 JSON 形状属于 `:agent:planner` 的 Action DSL，不是存储层的知识。
 * 所以这里**只接受两个函数**（`encodeAction` / `decodeAction`），由 `:app` 把
 * `com.ake.agent.planner.StepActionCodec` 的方法引用传进来。
 */
public object EntityMappers {

    // ──────────────────────────────────────────── task

    /**
     * @param goalEnc 已由 `PersistenceRedactionGate.sealField()` 加密。本文件**绝不自己做加密**。
     */
    fun taskToEntity(
        rec: TaskRecord,
        goalEnc: ByteArray?,
        answerEnc: ByteArray?,
        answerPreview: String?,
    ): TaskEntity = TaskEntity(
        id = rec.id.value,
        sessionId = rec.sessionId,
        seqInSession = rec.seqInSession,
        createdAt = rec.createdAt,
        startedAt = rec.startedAt,
        finishedAt = rec.finishedAt,
        lastActivityAt = rec.lastActivityAt,
        status = rec.status,
        terminalReason = rec.terminalReason,
        flavor = rec.flavor,
        memoryTier = rec.memoryTier,
        contextWindow = rec.contextWindow,
        modelId = rec.modelId,
        backend = rec.backend,
        stepsDone = rec.stepsDone,
        prefillTokensTotal = rec.prefillTokensTotal,
        decodeTokensTotal = rec.decodeTokensTotal,
        elapsedMsTotal = rec.elapsedMsTotal,
        goalEnc = goalEnc,
        goalPreview = rec.goalPreview,
        answerEnc = answerEnc,
        answerPreview = answerPreview,
        starred = if (rec.starred) 1 else 0,
        retentionClass = rec.retentionClass,
        redactionCount = rec.redactionCount,
        kvCachePeak = rec.kvCachePeak,
        schemaV = rec.schemaV,
    )

    fun taskToDomain(e: TaskEntity): TaskRecord = TaskRecord(
        id = com.ake.core.common.TaskId(e.id),
        sessionId = e.sessionId,
        seqInSession = e.seqInSession,
        createdAt = e.createdAt,
        startedAt = e.startedAt,
        finishedAt = e.finishedAt,
        lastActivityAt = e.lastActivityAt,
        status = e.status,
        terminalReason = e.terminalReason,
        flavor = e.flavor,
        memoryTier = e.memoryTier,
        contextWindow = e.contextWindow,
        modelId = e.modelId,
        backend = e.backend,
        stepsDone = e.stepsDone,
        prefillTokensTotal = e.prefillTokensTotal,
        decodeTokensTotal = e.decodeTokensTotal,
        elapsedMsTotal = e.elapsedMsTotal,
        goalEnc = e.goalEnc,
        goalPreview = e.goalPreview,
        answerEnc = e.answerEnc,
        answerPreview = e.answerPreview,
        starred = e.starred != 0,
        retentionClass = e.retentionClass.ifBlank { RetentionClass.FULL },
        redactionCount = e.redactionCount,
        kvCachePeak = e.kvCachePeak,
        schemaV = e.schemaV,
    )

    // ──────────────────────────────────────────── step

    fun stepToEntity(
        rec: StepRecord,
        actionKey: String,
        actionJson: String?,
        actionSummary: String,
    ): StepEntity = StepEntity(
        id = rec.id.value,
        taskId = rec.taskId.value,
        seq = rec.index,
        createdAt = rec.createdAtMillis,
        finishedAt = null,
        durationMs = rec.durationMs,
        status = rec.status,
        actionKind = kindOf(rec.action),
        actionSummary = actionSummary,
        actionKey = actionKey,
        actionJson = actionJson,
        modelRawRedacted = rec.modelRawRedacted,
        repairAttempts = rec.repairAttempts,
        modelId = null,
        prefillTokens = rec.prefillTokens,
        decodeTokens = rec.decodeTokens,
        ttftMs = rec.ttftMs,
        decodeTps = rec.decodeTps,
        kvCacheTokensAfter = rec.kvCacheTokensAfter,
        promptTokensEstimate = rec.promptTokensEstimate,
        budgetRemainingPct = rec.budgetRemainingPct,
        observationId = rec.observationId,
        pageChanged = changeOf(rec.result),
        pageDigest = rec.pageDigest,
        errorCode = rec.errorCode,
        errorHint = if (rec.result.isOk) null else rec.result.hintBrief(),
    )

    /**
     * 历史还原。**`action` 无法还原时返回 null**，调用方跳过该行。
     *
     * 为什么不用「占位动作」填充：`StepAction` 是 `sealed interface`，外部模块无法新增实现，
     * 这是好事 —— 它从编译期就杜绝了「拿一个假的 StepAction 混进轨迹」这类做法。
     * 假动作会让轨迹看起来「第 3 步做了个滚动」，那比「第 3 步历史不可用」糟糕得多。
     * 正确的失败方向是**少一行历史**，而不是**一行错的历史**。
     */
    fun stepToDomain(e: StepEntity, decodeAction: (String) -> StepAction?): StepRecord? {
        val action = e.actionJson?.let(decodeAction) ?: return null
        return StepRecord(
            id = com.ake.core.common.StepId(e.id),
            taskId = com.ake.core.common.TaskId(e.taskId),
            index = e.seq,
            createdAtMillis = e.createdAt,
            action = action,
            result = resultOf(e),
            status = e.status,
            modelRawRedacted = e.modelRawRedacted,
            prefillTokens = e.prefillTokens,
            decodeTokens = e.decodeTokens,
            ttftMs = e.ttftMs,
            decodeTps = e.decodeTps,
            kvCacheTokensAfter = e.kvCacheTokensAfter,
            promptTokensEstimate = e.promptTokensEstimate,
            budgetRemainingPct = e.budgetRemainingPct,
            pageDigest = e.pageDigest,
            observationId = e.observationId,
            durationMs = e.durationMs,
            repairAttempts = e.repairAttempts,
            errorCode = e.errorCode,
        )
    }

    fun kindOf(a: StepAction): String = when (a) {
        is StepAction.Click -> "CLICK"
        is StepAction.Type -> "TYPE"
        is StepAction.Select -> "SELECT"
        is StepAction.Scroll -> "SCROLL"
        is StepAction.Navigate -> "NAVIGATE"
        is StepAction.Extract -> "EXTRACT"
        is StepAction.CallTool -> "CALL_TOOL"
        is StepAction.AskHuman -> "ASK_HUMAN"
        is StepAction.Finish -> "FINISH"
        else -> "UNKNOWN"
    }

    private fun changeOf(r: ToolResult): String? = when (r) {
        is ToolResult.DomChanged -> PageChangeKind.DOM_CHANGED.name
        is ToolResult.UrlMoved -> PageChangeKind.URL_MOVED.name
        is ToolResult.Noop -> PageChangeKind.NO_CHANGE.name
        is ToolResult.Error -> null
        is ToolResult.Text, is ToolResult.RecordsGot -> null
    }

    /** 结果是有损还原的：完整 `ToolResult` 不落库（它是 prompt 形态，不是存储形态）。 */
    private fun resultOf(e: StepEntity): ToolResult = when (e.status) {
        StepStatus.DENIED -> ToolResult.Error(ErrorCode.DENIED, e.errorHint)
        StepStatus.ERROR -> ToolResult.Error(
            runCatching { ErrorCode.valueOf(e.errorCode ?: "UNKNOWN") }.getOrDefault(ErrorCode.UNKNOWN),
            e.errorHint,
        )

        else -> when (e.pageChanged) {
            PageChangeKind.DOM_CHANGED.name -> ToolResult.DomChanged(0)
            PageChangeKind.URL_MOVED.name -> ToolResult.UrlMoved("")
            PageChangeKind.NO_CHANGE.name -> ToolResult.Noop
            else -> ToolResult.Noop
        }
    }

    // ──────────────────────────────────────────── observation

    fun observationToEntity(s: ObservationSummary): ObservationEntity = ObservationEntity(
        id = s.id,
        taskId = s.taskId.value,
        stepId = s.stepId?.value,
        seq = s.seq,
        createdAt = s.createdAt,
        host = s.host,
        urlPathEnc = null,              // 由 store 加密后覆盖
        titleRedacted = s.titleRedacted,
        pageState = s.pageState,
        warningFlags = s.warningFlags,
        signalFlags = s.signalFlags,
        candidateCount = s.candidateCount,
        recordRowCount = s.recordRowCount,
        rawNodeCount = s.rawNodeCount,
        prunedNodeCount = s.prunedNodeCount,
        serializedChars = s.serializedChars,
        pageDigest = s.pageDigest,
        redactionCount = s.redactionCount,
        redactionTypes = s.redactionTypes.joinToString(",").ifBlank { null },
        payloadCodec = s.payloadCodec,
        payloadFile = s.payloadFile,
        payloadRawBytes = s.payloadRawBytes,
        payloadStoredBytes = s.payloadStoredBytes,
        payloadState = s.payloadState,
    )

    fun observationToDomain(e: ObservationEntity): ObservationSummary = ObservationSummary(
        id = e.id,
        taskId = com.ake.core.common.TaskId(e.taskId),
        stepId = e.stepId?.let { com.ake.core.common.StepId(it) },
        seq = e.seq,
        createdAt = e.createdAt,
        host = e.host,
        urlPath = "",                   // 加密列，需要时由 store 解密
        titleRedacted = e.titleRedacted,
        pageState = e.pageState,
        warningFlags = e.warningFlags,
        signalFlags = e.signalFlags,
        candidateCount = e.candidateCount,
        recordRowCount = e.recordRowCount,
        rawNodeCount = e.rawNodeCount,
        prunedNodeCount = e.prunedNodeCount,
        serializedChars = e.serializedChars,
        pageDigest = e.pageDigest,
        redactionCount = e.redactionCount,
        redactionTypes = e.redactionTypes?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
        payloadCodec = e.payloadCodec,
        payloadFile = e.payloadFile,
        payloadRawBytes = e.payloadRawBytes,
        payloadStoredBytes = e.payloadStoredBytes,
        payloadState = e.payloadState,
    )

    // ──────────────────────────────────────────── tool invocation / policy / event

    fun invocationToEntity(s: ToolInvocationSummary): ToolInvocationEntity = ToolInvocationEntity(
        id = s.id,
        taskId = s.taskId.value,
        stepId = s.stepId.value,
        seqInStep = s.seqInStep,
        createdAt = s.createdAt,
        startedAt = null,
        finishedAt = null,
        durationMs = s.durationMs,
        toolId = s.toolId,
        toolName = s.toolName,
        origin = s.origin,
        riskClass = s.riskClass,
        mcpServerId = s.mcpServerId,
        status = s.status,
        errorCode = s.errorCode,
        errorMessageRedacted = s.errorMessageRedacted,
        attempts = s.attempts,
        inputArgsEnc = s.inputArgsEnc,
        inputSchemaHint = s.inputSchemaHint,
        outputTextEnc = s.outputTextEnc,
        outputTruncated = if (s.outputTruncated) 1 else 0,
        outputBytes = s.outputBytes,
        confirmRequired = if (s.confirmRequired) 1 else 0,
        userDecision = s.userDecision,
    )

    /**
     * ★ v3：聊天场景的审计增强（上游 §8 S9）。
     *
     * ⚠ **两列目前只能填 null** —— `core:contract` 的 `PolicyDecisionRecord`
     * 还没有承载 `agentId` / `chatSessionId` 的字段，而 `:core` 不在本轮改动范围内。
     * 这里把带这两个参数的重载**先开出来**（V3 用例「两条都落带 agent_id 的
     * policy_decision」一旦接线即可调用），并在回传里把确切签名交给邝随行补进
     * `PolicyDecisionRecord`：
     *
     * ```
     * public data class PolicyDecisionRecord(
     *     // ...既有字段不动...
     *     val agentId: String? = null,
     *     val chatSessionId: String? = null,
     * )
     * ```
     *
     * 补齐之前，这两列在库里恒为 NULL —— **这是已知的、写下来的缺口**，
     * 不是「忘了写」：先建列（加列零成本、表结构评审只发生一次），后接数据源。
     */
    fun policyToEntity(
        r: PolicyDecisionRecord,
        agentId: String? = null,
        chatSessionId: String? = null,
    ): PolicyDecisionEntity = PolicyDecisionEntity(
        id = r.id,
        taskId = r.taskId.value,
        stepId = r.stepId?.value,
        createdAt = r.createdAt,
        decision = r.decision,
        severity = r.severity,
        reasonKey = r.reasonKey,
        reasonArgsJson = joinArgs(r.reasonArgs),
        actionKind = r.actionKind,
        targetHost = r.targetHost,
        riskClass = r.riskClass,
        agentId = agentId,
        chatSessionId = chatSessionId,
    )

    fun policyToDomain(e: PolicyDecisionEntity): PolicyDecisionRecord = PolicyDecisionRecord(
        id = e.id,
        taskId = com.ake.core.common.TaskId(e.taskId),
        stepId = e.stepId?.let { com.ake.core.common.StepId(it) },
        createdAt = e.createdAt,
        decision = e.decision,
        severity = e.severity,
        reasonKey = e.reasonKey,
        reasonArgs = splitArgs(e.reasonArgsJson),
        actionKind = e.actionKind,
        targetHost = e.targetHost,
        riskClass = e.riskClass,
    )

    fun eventToEntity(r: TraceEventRecord): TraceEventEntity = TraceEventEntity(
        id = r.id,
        taskId = r.taskId.value,
        stepId = r.stepId?.value,
        seq = r.seq,
        createdAt = r.createdAt,
        level = r.level,
        kind = r.kind,
        messageRedacted = r.messageRedacted,
        kvJson = r.kvJson,
    )

    // ──────────────────────────────────────────── checkpoint

    fun checkpointToEntity(cp: LoopCheckpoint, scratchpadEnc: ByteArray?): TaskCheckpointEntity =
        TaskCheckpointEntity(
            taskId = cp.taskId.value,
            updatedAt = cp.updatedAt,
            statusSnapshot = cp.statusSnapshot,
            currentStepSeq = cp.currentStepSeq,
            prefillTokensTotal = cp.prefillTokensTotal,
            decodeTokensTotal = cp.decodeTokensTotal,
            elapsedMsTotal = cp.elapsedMsTotal,
            seenDigestJson = joinArgs(cp.seenDigests),
            scratchpadEnc = scratchpadEnc,
            lastUrl = cp.lastUrl,
            lastObservationId = cp.lastObservationId,
            lastKvCacheTokens = cp.lastKvCacheTokens,
            compactionLastStep = cp.compactionLastStep,
            humanGatePending = if (cp.humanGatePending) 1 else 0,
            humanGateJson = cp.humanGateJson,
        )

    fun checkpointToDomain(e: TaskCheckpointEntity, scratchpad: String?): LoopCheckpoint = LoopCheckpoint(
        taskId = com.ake.core.common.TaskId(e.taskId),
        updatedAt = e.updatedAt,
        statusSnapshot = e.statusSnapshot,
        currentStepSeq = e.currentStepSeq,
        prefillTokensTotal = e.prefillTokensTotal,
        decodeTokensTotal = e.decodeTokensTotal,
        elapsedMsTotal = e.elapsedMsTotal,
        seenDigests = splitArgs(e.seenDigestJson),
        scratchpad = scratchpad,
        lastUrl = e.lastUrl,
        lastObservationId = e.lastObservationId,
        lastKvCacheTokens = e.lastKvCacheTokens,
        compactionLastStep = e.compactionLastStep,
        humanGatePending = e.humanGatePending != 0,
        humanGateJson = e.humanGateJson,
    )

    // ──────────────────────────────────────────── memory / model / tool registry

    fun memoryToEntity(
        card: MemoryCard,
        contentEnc: ByteArray?,
        /** ★ v3 人格隔离（上游 S7）：null = 全局共享。默认按 agent_id 隔离。 */
        agentId: String? = null,
    ): MemoryCardEntity {
        val now = card.createdAtMillis
        return MemoryCardEntity(
            id = card.id,
            agentId = agentId,
            key = memoryKey(card),
            kind = card.kind,
            scope = if (card.host.isBlank()) "GLOBAL" else "DOMAIN",
            scopeHost = card.host.ifBlank { null },
            title = card.text.lineSequence().firstOrNull()?.take(64) ?: card.kind,
            contentEnc = contentEnc,
            contentPreview = card.text.take(160),
            createdAt = now,
            updatedAt = now,
            lastUsedAt = now,
            useCount = 0,
            pinned = 0,
            sourceTaskId = null,
            expiresAt = null,
        )
    }

    fun memoryToDomain(e: MemoryCardEntity, text: String): MemoryCard = MemoryCard(
        id = e.id,
        host = e.scopeHost.orEmpty(),
        kind = e.kind,
        text = text,
        createdAtMillis = e.createdAt,
    )

    fun memoryKey(card: MemoryCard): String =
        "${card.host.ifBlank { "-" }}|${card.kind}|${card.id}"

    fun modelAssetToDomain(e: ModelAssetEntity): ModelAssetRecord = ModelAssetRecord(
        id = e.id,
        variant = e.variant,
        remoteUrl = e.remoteUrl,
        localPath = e.localPath,
        expectedBytes = e.expectedBytes,
        bytesDownloaded = e.bytesDownloaded,
        sha256 = e.sha256,
        state = e.state,
        contextWindow = e.contextWindow,
        minRamGb = e.minRamGb,
        updatedAt = e.lastUsedAt ?: e.downloadFinishedAt ?: e.verifiedAt ?: 0L,
    )

    fun toolSpecToEntity(spec: ToolSpec, now: Long): ToolRegistryEntity = ToolRegistryEntity(
        toolId = spec.id.value,
        name = spec.name,
        description = spec.description,
        jsonSchema = spec.jsonSchema,
        schemaHash = stableHash(spec.jsonSchema),
        origin = spec.origin.name,
        serverId = serverIdOf(spec.id.value),
        declaredRiskClass = spec.riskClass.name,
        riskClassOverride = null,
        enabled = if (spec.enabled) 1 else 0,
        alwaysAllow = 0,
        grantedAt = null,
        discoveredAt = now,
        lastSeenAt = now,
        missingSince = null,
    )

    fun toolSpecToDomain(e: ToolRegistryEntity): ToolSpec = ToolSpec(
        id = com.ake.core.contract.ToolId(e.toolId),
        name = e.name,
        description = e.description,
        jsonSchema = e.jsonSchema,
        origin = runCatching { com.ake.core.contract.ToolOrigin.valueOf(e.origin) }
            .getOrDefault(com.ake.core.contract.ToolOrigin.LOCAL),
        riskClass = runCatching { com.ake.core.contract.RiskClass.valueOf(e.riskClassOverride ?: e.declaredRiskClass) }
            .getOrDefault(com.ake.core.contract.RiskClass.READ_ONLY),
        enabled = e.enabled != 0,
    )

    /** `mcp.<serverId>.<name>` 里取出 serverId；LOCAL 工具没有 server。 */
    internal fun serverIdOf(toolId: String): String? {
        if (!toolId.startsWith("mcp.")) return null
        val rest = toolId.removePrefix("mcp.")
        val dot = rest.indexOf('.')
        return if (dot <= 0) null else rest.substring(0, dot)
    }

    /** schema 变了要重新注入 SYSTEM 段 ⇒ 需要一个稳定的短摘要。不用加密哈希（没必要抗碰撞）。 */
    internal fun stableHash(s: String): String {
        var h = 0xcbf29ce4L.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h.toUInt().toString(16)
    }

    // ──────────────────────────────────────────── 小工具

    /** 逗号分隔。值里含逗号时做百分号转义 —— 不做 JSON（一行 KV 不值得引入解析开销）。 */
    internal fun joinArgs(args: List<String>): String? {
        if (args.isEmpty()) return null
        return args.joinToString(",") { it.replace("%", "%25").replace(",", "%2C") }
    }

    internal fun splitArgs(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(',').map { it.replace("%2C", ",").replace("%25", "%") }
    }

    internal val DEFAULT_INVOCATION_STATUS: String = InvocationStatus.SUCCESS

    /** 供 UI 反解位掩码（避免 L5 直接 import core 的扩展函数后与 Room 混在一起）。 */
    fun signalsOf(e: ObservationEntity): Set<String> = decodeSignalFlags(e.signalFlags).mapTo(mutableSetOf()) { it.name }

    fun warningsOf(e: ObservationEntity): Set<String> = decodeWarningFlags(e.warningFlags).mapTo(mutableSetOf()) { it.name }
}
