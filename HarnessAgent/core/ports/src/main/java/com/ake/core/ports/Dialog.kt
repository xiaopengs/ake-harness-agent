package com.ake.core.ports

import com.ake.core.common.Outcome
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.SessionSpec

// =============================================================================
// 对话侧模型抽象（架构 08 §2.4 / §4.1）
//
// ## ★ 落点与 §2.4 原文的一处偏差（必读，须主理人知悉）
//
// §2.4 原文把本文件的全部类型放进 `:core:model-api`。本轮 `:core:model-api`
// 被钉死为「一行不改」（否则会红 `:agent` / `:chat` 的三处 `FakeModelPort`
// 夹具，见 §2.2 理由 2），而 `:cloudai` 又**必须**看见 `DialogModelPort`，
// 于是这些类型落到了 `:core:ports`，并连带新增一条 core 内部边
// `:core:ports → :core:model-api`（已同步进根 `build.gradle.kts` 的白名单）。
//
// 这是一次**纯位置**调整：类型形状与 §2.4 逐字一致。将来若 `:core:model-api`
// 解封，整文件搬过去即可，无语义变化。
//
// ## DOM 锁死在这里的形态（§5）
//
//  · 锁 A：`DeviceModelPort` 与 `DialogModelPort` 是**两个不同类型**；
//          云端适配器只 implements 后者 ⇒ 它在类型上喂不进 `AgentLoop`。
//  · 锁 C：`PromptContext` 是 sealed ⇒ 每个后端**必须**对 `WithPageContext`
//          显式表态（无 `else` 分支），云端一律硬失败。
//
// ## ⚠ `PromptContext` 为什么挂在 `openDialog` 而不是 `GenerationRequest`
//
// §5.4 原文是给 `GenerationRequest` 加一个 `context` 字段。改 `GenerationRequest`
// 属于改 `:core:model-api`，本轮不允许。于是把它前移到 `openDialog`：
// 语义上反而**更贴** §3.1 —— 目标是**会话级**的，那「这个会话带不带页面上下文」
// 就该在开会话时一次性钉死，而不是每一轮重新声明。
// =============================================================================

/** 推理发生在哪里。★ 是 enum 而不是 Boolean：将来可能出现「本机局域网自托管」第三态。 */
public enum class Locality { DEVICE, REMOTE }

/**
 * ★ 上下文上限。**刻意是 sealed 而不是 Int**。
 *
 * 两种上限的**失效方式完全不同**，合成一个 Int 必然有人拿错：
 *  · KvCache  —— 硬上限。超了是 native 层 OOM/abort，**进程直接消失，没有 Outcome 可返回**；
 *  · Declared —— 软上限。超了是 HTTP 400 `context_length_exceeded`，可预期、可压缩后重试。
 */
public sealed interface ContextLimit {
    public val tokens: Int

    /** 端侧：input + output 的 KV cache 总上限（不是输出上限）。 */
    public data class KvCache(override val tokens: Int) : ContextLimit

    /** 云端：用户声明的窗口。**超过不会崩，只会 400**。 */
    public data class Declared(override val tokens: Int, val source: DeclaredSource) : ContextLimit
}

public enum class DeclaredSource { USER_INPUT, SERVER_REPORTED, DEFAULT }

public enum class StreamingCapability { NONE, WHOLE_TEXT, INCREMENTAL }

/**
 * ★ 复水语义 —— 端侧与云端**代价模型相反**的地方。
 *  · PREFILL_ONCE     端侧：initialMessages 在 openSession 时灌一次建 KV，之后只付增量；
 *  · REPLAY_EACH_TURN 云端：每次 generate 都要全量重放 ⇒ 历史越长，每轮出境字节越多。
 */
public enum class RehydrationMode { PREFILL_ONCE, REPLAY_EACH_TURN }

public enum class JsonConstraintMode { NONE, JSON_OBJECT, JSON_SCHEMA }

public enum class SystemRoleMode { SEPARATE_FIELD, IN_MESSAGES }

public enum class SamplerParam { TEMPERATURE, TOP_P, TOP_K, SEED }

/** token 从哪来。★ 没有它，"8192" 这个数字的真假无法判断。 */
public enum class UsageAccounting { EXACT, ESTIMATED, NONE }

/** ★★ 新增：一次生成里 token 数的来源（§2.8 ③「估算 vs 真值分开」）。 */
public enum class TokenSource { ESTIMATED, REPORTED }

/**
 * 能力描述对象。★ 云端与端侧**行为同构、能力不同**，差异用**数据**表达而不是用签名表达 ——
 * 数据是「被读取后做判定」，签名差异会逼着调用方写 `if (port is CloudPort)`。
 */
public data class ModelCapabilities(
    val locality: Locality,
    val contextLimit: ContextLimit,
    val streaming: StreamingCapability,
    val rehydration: RehydrationMode,
    val jsonConstraint: JsonConstraintMode,
    val systemRole: SystemRoleMode,
    val acceptedSamplers: Set<SamplerParam>,
    val usage: UsageAccounting,
    /** ★ 本项目恒 false：云端一律不发 `tools` 字段（§2.6）。 */
    val toolCalls: Boolean = false,
) {
    public companion object {
        /** §2.4：`ModelPort.capabilities` 的默认值。 */
        public val DEVICE_DEFAULT: ModelCapabilities = ModelCapabilities(
            locality = Locality.DEVICE,
            contextLimit = ContextLimit.KvCache(8_192),
            streaming = StreamingCapability.INCREMENTAL,
            rehydration = RehydrationMode.PREFILL_ONCE,
            jsonConstraint = JsonConstraintMode.JSON_OBJECT,
            systemRole = SystemRoleMode.SEPARATE_FIELD,
            acceptedSamplers = SamplerParam.entries.toSet(),
            usage = UsageAccounting.ESTIMATED,
            toolCalls = false,
        )

        /** 云端默认：声明式窗口、每轮全量重放、只支持 `json_object` 兜底档。 */
        public val REMOTE_DEFAULT: ModelCapabilities = ModelCapabilities(
            locality = Locality.REMOTE,
            contextLimit = ContextLimit.Declared(8_192, DeclaredSource.DEFAULT),
            streaming = StreamingCapability.INCREMENTAL,
            rehydration = RehydrationMode.REPLAY_EACH_TURN,
            jsonConstraint = JsonConstraintMode.JSON_OBJECT,
            systemRole = SystemRoleMode.IN_MESSAGES,
            acceptedSamplers = setOf(SamplerParam.TEMPERATURE, SamplerParam.TOP_P),
            usage = UsageAccounting.EXACT,
            toolCalls = false,
        )
    }
}

/**
 * ★★ 「会接触 DOM 的推理」的**唯一**入口类型（锁 A）。
 *
 * 它刻意是**空接口 + 文档**，不加任何方法 —— 加方法反而会给云端实现提供
 * 「照着实现一遍」的模板。
 */
public interface DeviceModelPort : ModelPort {
    /**
     * ★ §2.4 本该加在 `ModelPort` 上（带默认实现，既有实现零改动）。
     * 本轮 `:core:model-api` 冻结，故默认实现下沉到两个子接口 ——
     * **效果等价**：`:llm` 的 `implements DeviceModelPort` 仍然只需加一个符号，
     * 不需要补实现任何成员。
     */
    public val capabilities: ModelCapabilities get() = ModelCapabilities.DEVICE_DEFAULT
}

/**
 * 纯对话入口。**本地与云端共用这一个形状** —— `:chat` 只认它，不认具体实现。
 */
public interface DialogModelPort : ModelPort {

    /** 同上：默认端侧能力，云端实现必须覆盖。 */
    public val capabilities: ModelCapabilities get() = ModelCapabilities.DEVICE_DEFAULT

    /**
     * 开一次对话。
     *
     * @param permit null ⇒ 本次走设备内推理；非 null ⇒ 允许出云
     *               （允许的次数与端点由 permit 钉死，见 §6 / §7）。
     *               ★ 它是**必填形参**而不是 `SessionSpec` 里的可选字段 ——
     *               可选字段会被「顺手传 null」绕过，必填形参意味着
     *               「想开一次云端对话，手上必须先有一条已落库的审计行」。
     * @param context 本会话的 prompt 性质（锁 C）。会话级 ⇒ 只在开会话时声明一次。
     */
    public suspend fun openDialog(
        spec: SessionSpec,
        permit: EgressPermit?,
        context: PromptContext = PromptContext.TextOnly,
    ): Outcome<ModelSession>
}

/**
 * ★ 会话 ⇒ 对话入口的门面（§4.1）。
 *
 * 放在 `:core:model-api` 的同层（这里是 `:core:ports`），因为 `:chat` 与 `:app` 都要看见它。
 * 实现在 `:inference`（L4），**不在这里** —— 它需要看见两个 L3 的实现类。
 */
public interface ModelRouter {
    /**
     * @return 一个 `DialogModelPort`。★ 调用方**看不出**背后是 native 引擎还是 HTTP。
     */
    public suspend fun dialogFor(sessionId: String): Outcome<DialogModelPort>
}

/**
 * ★ 本会话 prompt 的性质。**sealed ⇒ 每个后端必须显式处理每一个变体**。
 *
 * 将来若新增一种形态（比如 `WithScreenshot`），Kotlin 的 `when` 穷尽性会让
 * **所有**后端当场编译失败，逼每一个后端对新形态表态 —— 而不是悄悄走默认分支。
 */
public sealed interface PromptContext {

    public data object TextOnly : PromptContext

    /**
     * ★ 本会话含页面上下文（DOM 或对 DOM 的操作意图）。
     *
     * ★★ 它**不携带 DOM 原文**，携带的是**元数据**：host（已过 `Hosts.normalise`）、
     *     字符数、元素数。与「`route_decision` 表没有任何一列能装原文」同一手法 ——
     *     **让「顺手把原文塞进来」在字段上就无处可放**。
     */
    public data class WithPageContext(
        val host: String,
        val domChars: Int,
        val elementCount: Int,
    ) : PromptContext
}

// =============================================================================
// §4.1 会话选定的目标
// =============================================================================

/**
 * ★ 会话级目标（§3.1：会话创建时选定，会话内不可变）。
 *
 * 与 `InferenceTarget` 的分工：
 *  · `DialogTarget`        —— 用户**选了什么**（持久化形态，permit-free）
 *  · `InferenceTarget`     —— 这一轮**被授权成什么**（运行时形态，`CloudAssisted` 必须带 permit）
 */
public sealed interface DialogTarget {
    public data object Device : DialogTarget

    /**
     * ★ `credentialRef` 而不是 key：**目标里装不下密钥**（§5）。
     */
    public data class Remote(val credentialRef: CredentialRef) : DialogTarget
}

/**
 * ★ `DialogTarget` 的持久化（§3.3：**会话级**目标，会话内不可变）。
 *
 * 实现放 `:data`（v4 迁移后由 `chat_session.model_target` 列承载），
 * 本模块看不见 Room —— 与 `ChatSessionStorePort` 同一手法。
 *
 * ## 为什么它是 Port 而不是 `:inference` 里的具体类
 *
 * 「切换模型 = 开一个新会话，旧会话保留只读」这条产品语义（§3.3）
 * 需要跨 `:inference`（写）与 `:ui`（读列表）共用同一份事实来源。
 */
public interface DialogTargetStore {

    /**
     * @return 该会话选定的目标；`null` ⇒ **没有登记过目标**。
     *
     * ★ `DefaultModelRouter` 遇到 `null` **不猜**（不会默认走端侧），而是返回
     *   `Err(NO_TARGET)` —— 「没选过目标」和「选了端侧」必须是两件不同的事。
     */
    public suspend fun of(sessionId: String): DialogTarget?

    /** 登记 / 改写某会话的目标。★ 会话内不可变 ⇒ 重复调用同一 sessionId 是设计外的用法。 */
    public suspend fun select(sessionId: String, target: DialogTarget): Outcome<Unit>
}

/**
 * 目标 ↔ 落库文本的互相转换。★ 全仓只有这一份（对应 §3.3 的 `chat_session.model_target`）。
 */
public object DialogTargetCodec {

    public const val PREFIX_REMOTE: String = "CLOUD:"

    public fun encode(target: DialogTarget): String = when (target) {
        is DialogTarget.Device -> "LOCAL"
        is DialogTarget.Remote -> PREFIX_REMOTE + target.credentialRef.id
    }

    /**
     * ★ fail-closed：无法识别的文本一律按 [DialogTarget.Device] 处理
     * （认不出来的目标 ⇒ 不出网，而不是猜一个端点）。
     */
    public fun decode(raw: String?): DialogTarget {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return DialogTarget.Device
        if (!s.startsWith(PREFIX_REMOTE)) return DialogTarget.Device
        val ref = s.removePrefix(PREFIX_REMOTE).trim()
        if (ref.isEmpty()) return DialogTarget.Device
        return DialogTarget.Remote(CredentialRef(ref))
    }
}
