package com.ake.core.ports

import com.ake.core.common.Outcome
import com.ake.core.common.TaskId
import com.ake.core.contract.LoopCheckpoint
import com.ake.core.contract.MemoryCard
import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ObservationSummary
import com.ake.core.contract.PolicyDecisionRecord
import com.ake.core.contract.StepRecord
import com.ake.core.contract.TaskBudgetPatch
import com.ake.core.contract.TaskRecord
import com.ake.core.contract.TaskSpec
import com.ake.core.contract.ToolId
import com.ake.core.contract.ToolSpec
import kotlinx.coroutines.flow.Flow

/**
 * 持久化 Port 家族（架构 §2.2 + 数据库 §3.1 + EXPECTED_CORE_API.md §3）。
 *
 * **接口参数是 `:core:contract` 里的领域对象，绝不返回 Room Entity** ——
 * Entity 一旦出现在 Port 签名里，Room 注解就流到了 L4，抽象泄漏且不可逆转（数据库 §3.1 铁律 2）。
 */
public interface TraceStorePort {
    public suspend fun append(step: StepRecord)

    public suspend fun load(taskId: TaskId): List<StepRecord>
}

public interface MemoryStorePort {
    public suspend fun put(card: MemoryCard)

    public suspend fun relevantTo(task: TaskSpec): List<MemoryCard>
}

// ──────────────────────────────────────────────────────────────
// agent 模块要求新增的 6 个持久化 Port（EXPECTED_CORE_API.md §3）
// ──────────────────────────────────────────────────────────────

public interface TaskStorePort {
    public suspend fun create(rec: TaskRecord)

    public suspend fun advance(taskId: TaskId, patch: TaskBudgetPatch)

    public suspend fun finish(
        taskId: TaskId,
        status: String,
        reason: String?,
        answerEnc: ByteArray?,
        answerPreview: String?,
    )

    public suspend fun load(taskId: TaskId): TaskRecord?

    public fun observe(taskId: TaskId): Flow<TaskRecord?>

    /**
     * 最近的任务列表（供 UI 冷启动后的「历史任务」页）。
     *
     * ★ **签名由邝随行于 2026-10 定稿，`:data` 侧实现必须与之一字不差**
     * （后端霍承钧同步实现）。改签名请先在群里对齐，否则两侧各写各的，
     * 症状是「编译期看着没问题，UI 历史列表永远为空」。
     *
     * @param limit 取最近多少条。实现侧应 `coerceAtLeast(0)` 后下发到 SQL `LIMIT`。
     * @return 按「最近活动」倒序（`last_activity_at DESC`），最多 [limit] 条。
     */
    public suspend fun recent(limit: Int): List<TaskRecord>
}

public interface CheckpointStorePort {
    public suspend fun save(cp: LoopCheckpoint)

    public suspend fun load(taskId: TaskId): LoopCheckpoint?

    public suspend fun clear(taskId: TaskId)

    public suspend fun findResumable(): List<TaskId>
}

public interface ObservationStorePort {
    /**
     * ★ **非挂起**（数据库 §6.3 / F9）。
     *
     * 主循环各处都有 `withTimeout(budget.perceiveMs / decodeMs / actionMs)`。
     * 若这里是阻塞式 DB 写入，**这些 timeout 会被落盘 IO 吃掉**，而落盘不属于任何一个环节的职责。
     * 所以实现必须是 try-send：`false` 表示队列满了、这一条被丢弃（要写 trace 记录 `TRACE_DROPPED`）。
     */
    public fun append(summary: ObservationSummary, payload: String?): Boolean

    public suspend fun loadPayload(observationId: String): String?
}

/** §8.5 逐工具授权。落 Room 不落 DataStore（数据库 F7）：逐工具撤销若整体重写文件会有可感知延迟。 */
public interface ToolPermissionStorePort {
    public suspend fun isEnabled(toolId: ToolId): Boolean

    public suspend fun isAlwaysAllow(toolId: ToolId): Boolean

    public suspend fun grantAlwaysAllow(toolId: ToolId)

    public suspend fun revokeAlwaysAllow(toolId: ToolId)

    public suspend fun setEnabled(toolId: ToolId, enabled: Boolean)

    public suspend fun upsertDiscovered(tools: List<ToolSpec>)

    public fun catalog(): Flow<List<ToolSpec>>
}

public interface PolicyDecisionSink {
    /** ★ **非挂起**：同 `ObservationStorePort` 的理由。审计写失败不能拖慢主循环。 */
    public fun record(d: PolicyDecisionRecord): Boolean
}

public interface ModelAssetStorePort {
    public suspend fun currentReady(): ModelAssetRecord?
}

/**
 * 模型加载/卸载生命周期与安全/设备水准有关的所有副作用收敛处（架构 §6.4 §11 R1）。
 *
 * 【待仲裁】EXPECTED_CORE_API.md 只列了名字没给形态，这里是我补的形态；
 * 若你的 `AgentLoop` 需要别的入口（比如 `preload()`），扩展它不影响已有签名。
 */
public interface ModelLifecyclePort {
    /** 幂等：模型就绪则直接返回；否则下载 → 校验 → 加载（含 §6.3 预热探针与后端降级）。 */
    public suspend fun ensureReady(modelId: String): Outcome<ModelAssetRecord>

    public suspend fun unload(reason: UnloadReason)

    public fun assetState(): Flow<ModelAssetRecord>

    /** §8.5 ⑦：模型文件删不删由用户单独决定（2.58 GB，重下代价高）。 */
    public suspend fun deleteModelFile(modelId: String): Outcome<Unit>
}

public enum class UnloadReason {
    USER_STOP,
    BACKGROUND_GRACE_EXPIRED,
    IDLE_GRACE_EXPIRED,
    LOW_MEMORY,
    THERMAL_SEVERE,
    SWITCHING_MODEL,
}
