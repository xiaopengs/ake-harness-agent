package com.ake.core.ports

import com.ake.core.common.Hosts
import com.ake.core.common.Outcome
import kotlinx.coroutines.flow.Flow

// =============================================================================
// 云端凭证与出境许可（架构 08 §6 / §7）
//
// ## 为什么在 `:core:ports`（§6.1 三条理由）
//  ① `:ui`（只能看见 `:core:*`）要渲染「已配置的服务」列表与编辑表单；
//  ② `:cloudai` / `:inference` / `:app` 三方都要看见 ⇒ 除契约层外没有别处能同时被三者看见；
//  ③ 放进 `:cloudai` 会让 `:ui` 反向依赖 L3 ⇒ 铁律禁止。
//
// ## ★★ §6.3 的总原则：`CloudFailure` 里**没有地方可以放秘密**
//
// 它刻意没有 `message: String`、`cause: Throwable`、`body: String`、`url: String`
// 中**任何一个**字段。理由：只要有这个字段，就一定会有人在半年后往里塞服务端原文
// —— 而服务端原文里可能有 URL、URL 里可能有 key（ktor 的异常 message 亦如此）。
//
// **让「想泄漏」在字段层面无处可放**，比在注释里写一百遍「不要 log」有效。
// =============================================================================

/** ★ 凭证引用。**不含任何秘密**，可安全落库、可进日志、可进 Intent。 */
public data class CredentialRef(val id: String)

public enum class TlsMode {
    /** https 强制。 */
    REQUIRED,

    /** 例外仅限 RFC1918 / `.local`，且需用户显式勾选。 */
    ALLOW_INSECURE_LAN,
}

/**
 * ★ 端点配置。**不含密钥**。UI 直接渲染它。
 *
 * ⚠ 展示时只给 [hostOf] 的结果，**绝不给 [baseUrl] 原文** ——
 *   自建网关常把 key 放在 query 里（`?key=xxx`），原文就是秘密（§6.3 ③）。
 */
public data class EndpointConfig(
    val baseUrl: String,
    val modelName: String,
    val tlsMode: TlsMode,
    val declaredContextWindow: Int?,
    val label: String,
) {
    /**
     * ★ 唯一允许对外暴露的端点标识。走 `Hosts.normalise`（**全仓唯一归一化实现**）。
     */
    public fun hostOf(): String = Hosts.normalise(baseUrl)

    public companion object {
        public const val DEFAULT_CONTEXT_WINDOW: Int = 8_192
        public const val MIN_CONTEXT_WINDOW: Int = 2_048
        public const val MAX_CONTEXT_WINDOW: Int = 131_072

        /** ★ 云端窗口是**软的**，只夹上下界，不按设备档位夹（§2.8 三条语义 ①）。 */
        public fun clampWindow(declared: Int?): Int =
            (declared ?: DEFAULT_CONTEXT_WINDOW).coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW)
    }
}

/**
 * ★★ 明文密钥的**唯一**容器。
 *
 * ## 为什么刻意**不是** data class
 *
 * data class 会自动生成 `toString()` 打印全部字段 —— 那等于给秘密配了一个
 * 「随时可打印」的默认实现。这里手写 `toString` 覆盖掉它（§6.4）。
 *
 * ## ★★ 为什么**不是** `value class`（主理人裁决）
 *
 * 最初写成 `@JvmInline value class`。**这是错的，而且错得隐蔽**：
 * JVM 上的 value class 必须保留「装箱 ↔ 拆箱」的互操作入口，
 * 编译器会额外生成一个 **public、无参、返回底层 `String`** 的 `unbox-impl()`。
 * 于是「明文只有一个读取口」这条保证**当场失效** ——
 * 反射、Java 互操作、任何 JVM 语言都能直接调用它把明文取回来，
 * 完全绕开 `useAsBearer`。
 *
 * ⇒ 改回普通 `final class`。理由与 §6.4「为什么不是 data class」同源：
 *
 *   value class 的全部收益是**省一次装箱**；而 `PlainSecret` 的生命周期
 *   极短（只在单次 HTTP 调用的 header 装配里活几毫秒）。
 *   用「一个 public 明文读取口」换「省一次装箱」，是典型的
 *   **为微优化付出安全边界**。
 *
 * 代价：每次 `PlainSecret.of(...)` 多一次堆分配。按每轮对话一次计算，可忽略。
 *
 * ## `equals` / `hashCode` 为什么**不重写**（因此可以接受）
 *
 * 不重写 ⇒ 走 JVM 默认的**同一性语义**（`equals` 比引用，`hashCode` 派生自身份标识）。
 *
 *  · `hashCode`：**不可逆**。默认的身份哈希只是一个 int，不含任何明文信息，
 *    也无法倒推出明文 ⇒ 不构成泄漏通道。
 *    （附带说明：即便退一步用 `value.hashCode()`——即 JDK `String.hashCode()`——
 *     它同样是**单向的 31 进制散列**，从一个 int 反推任意长度的原串在信息论上不成立，
 *     因此**也可接受**。本类连这一步都不做，直接不重写。）
 *  · `equals`：同一性语义 ⇒ 不会因为「两个内容相同的 secret 相等」
 *    而让 secret 参与任何基于内容的比较 / 去重 / 字典键 ⇒ 也就没有
 *    「通过 equals 侧信道逐字节爆破明文」的攻击面。
 *
 * ⚠ **边界在哪（本轮明确不做的事）**：
 *   本类保证的是「**编译期可见的 API 上没有明文读取口**」，以及
 *   「误打印 / 误序列化 / 误落盘时只会得到 `***`」。
 *   它**不防御**以下情形，这些都不在本轮范围内：
 *     · `setAccessible(true)` 强行读取 `value` 字段；
 *     · 进程内存 dump / native crash tombstone；
 *     · root / Frida / Xposed 设备；
 *     · IME / 剪贴板。
 *   详见 §9.3「这条我只能保证到这里」。
 */
public final class PlainSecret private constructor(private val value: String) {

    public override fun toString(): String = REDACTED

    /**
     * ★ **唯一**的读取口。明文只活在 [block] 的生命周期内。
     *
     * λ 之外没有任何途径能拿到明文：本类不提供 getter，且 `toString` 恒为 `***`。
     */
    public fun <R> useAsBearer(block: (String) -> R): R = block(value)

    public companion object {
        public const val REDACTED: String = "***"

        public fun of(raw: String): PlainSecret = PlainSecret(raw)
    }
}

/**
 * ★ 凭证的生命周期 Port（§0.2-A 修正后真正缺的那一半：加密设施已有，生命周期没有）。
 */
public interface CloudCredentialPort {

    /** 新建或覆盖。★ secret 只在最后一个参数里出现一次。 */
    public suspend fun upsert(
        ref: CredentialRef,
        cfg: EndpointConfig,
        secret: PlainSecret,
    ): Outcome<Unit>

    /** ★ 可枚举 ref（UI 要列「已配置的服务」）。**不含 secret**。 */
    public fun refs(): Flow<List<CredentialRef>>

    /** ★ 读配置（不含 secret）。UI 渲染用这个，不是 upsert 的回读。 */
    public suspend fun configOf(ref: CredentialRef): Outcome<EndpointConfig>

    /**
     * ★ 唯一能拿到明文的地方。★★ 调用点全仓应该只有 `:cloudai` 里的一处，
     *   由 D4 断言（扫全仓 `secretOf(` 调用点）盯住。
     */
    public suspend fun secretOf(ref: CredentialRef): Outcome<PlainSecret>

    public suspend fun delete(ref: CredentialRef): Outcome<Unit>

    public suspend fun exists(ref: CredentialRef): Boolean

    // ★★ 刻意没有的两个能力（§6.2）：
    //  · testConnection() / validate()
    //      ⇒ 验证必须真的发一次请求（走 ModelRouter 的 probe），
    //        否则「验证」会成为**第二条出境路径**。
    //  · listSecrets() / exportAll()
    //      ⇒ 没有任何调用方需要它。有需要就是设计错了。
}

/**
 * ★★ 出境分相。分界线不是错误类型，而是**字节有没有发出去**（§4.2 / §4.3）。
 */
public enum class EgressPhase {
    /** ★ 一个字节都没发出去。用户「数据还在本地」的认知是**真的**。 */
    PRE,

    /** ★ 请求体已经发出（哪怕服务端一个 token 都没回）。数据**已经出境**。 */
    POST,
}

/**
 * ★★ 出境许可。**它的存在本身就是一条已落库的审计行**（§7.2）。
 *
 * 与 `ChatEgress` 的懒构造 payload 同一手法，但更强一档：
 * 懒构造是「裁决不允许时 payload 不会被造出来」；
 * permit 是「**没有已落库的审计行，就构造不出一个 CLOUD_ASSISTED 目标**」。
 */
public data class EgressPermit(
    /** route_decision.id */
    val auditRowId: String,
    val decidedAt: Long,
    /** ★ 已过 `Hosts.normalise` */
    val endpointHost: String,
    val credentialRef: CredentialRef,
    /**
     * ★ 本次许可允许的出境次数。重试必须重新申请
     * ⇒ **每次重试都有独立审计行**。
     */
    val allowedAttempts: Int = 1,
)

public enum class AuditOutcome {
    /** 已申请，未收敛。★ 冷启动 sweep 的判据。 */
    PENDING,

    /** PRE 阶段失败 ⇒ 一个字节都没发出。 */
    NOT_SENT,

    /** 请求已发出，等待响应。 */
    SENT,

    /** 完整收到 `[DONE]`。 */
    DELIVERED,

    /** 流被截断，但已收到部分内容。 */
    PARTIAL,

    /** POST 阶段失败。 */
    FAILED,

    /** ★ 进程死了 / 没收敛 ⇒ 诚实标注，不伪装成 NOT_SENT。 */
    UNKNOWN,
}

/**
 * ★ 出境许可的落点（§7.2）。`:data` 侧用 Room 实现，本接口看不见 Room。
 */
public interface EgressPermitSink {

    /**
     * ★ **先落一行 `outcome = PENDING`，同步返回 permit，然后才允许发字节。**
     *
     * 与「先 decision 后 audit」的差别是**方向**：
     * ```
     *   现在  决策 → 记账        ⇒ 中间崩了：有决策、无记账（缺口不可见）
     *   本设计 记账(PENDING) → 决策 → 执行 → UPDATE 终态
     *         ⇒ 中间崩了：有一行 PENDING，而 PENDING 可被发现、被报警、被 sweep
     * ```
     * 全部的收益就在这里：**把「审计缺口」变成「一行未收敛的 PENDING」**。
     * 前者事后无法察觉，后者冷启动一扫就知道。
     *
     * ★ fail-closed：INSERT 失败 ⇒ 返回 Err ⇒ **拿不到 permit ⇒ 构造不出 CloudAssisted
     *   ⇒ 一个字节都发不出去**。失败方向是安全的（T16）。
     */
    public suspend fun open(ctx: RouteContext, decision: RoutingDecision): Outcome<EgressPermit>

    /** 收敛终态。★ 必须被调用；不调用的 permit 会在 sweep 里变成 UNKNOWN。 */
    public suspend fun close(
        permit: EgressPermit,
        outcome: AuditOutcome,
        phase: EgressPhase,
        deliveredBytes: Int,
        finishReasonRaw: String?,
    ): Outcome<Unit>

    /** 冷启动 sweep：把超时未收敛的 PENDING → UNKNOWN，返回条数（UI 必须显示）。 */
    public suspend fun sweepPending(olderThanMs: Long = PENDING_TTL_MS): Int

    public companion object {
        public const val PENDING_TTL_MS: Long = 10L * 60 * 1000
    }
}

/**
 * ★★ 云端失败的**唯一**形状（§6.3）。
 *
 * ★ 它刻意**没有** `message: String`、`cause: Throwable`、`body: String`、`url: String`
 *   任何一个字段 —— **让「想泄漏」在字段层面无处可放**。
 *
 * 每个变体在 §4.4 的语义表里都有确定的 phase / 重试上限 / 是否允许回落。
 * ★ sealed ⇒ `FallbackPolicy` 的 `when` 必须穷尽，新增一种失败形态时**编译期就会被逼表态**。
 */
public sealed interface CloudFailure {
    /** 稳定的机器码。 */
    public val code: String

    /** i18n 常量。**不拼接任何服务端文本**（§6.3 ⑦）。 */
    public val reasonKey: String

    /** PRE / POST。★ 决定「能不能静默回落」。 */
    public val phase: EgressPhase

    /** 是否值得重试。★ 每次重试都是一个**独立出境** ⇒ 必须新 permit + 新审计行。 */
    public val retryable: Boolean

    // ── PRE：一个字节都没发出去 ──────────────────────────────────────────

    /** 手上没有 permit ⇒ 本次根本没有出云能力。 */
    public data object NoPermit : CloudFailure {
        override val code: String get() = CloudFailureCodes.NO_PERMIT
        override val reasonKey: String get() = "err_cloud_no_permit"
        override val phase: EgressPhase get() = EgressPhase.PRE
        override val retryable: Boolean get() = false
    }

    /** 凭证不存在（被删 / 一键清除之后）。 */
    public data object CredentialMissing : CloudFailure {
        override val code: String get() = CloudFailureCodes.CREDENTIAL_MISSING
        override val reasonKey: String get() = "err_cloud_cred_missing"
        override val phase: EgressPhase get() = EgressPhase.PRE
        override val retryable: Boolean get() = false
    }

    /** DNS 失败 / 连接超时 / TLS 握手失败。 */
    public data object ConnectFailed : CloudFailure {
        override val code: String get() = CloudFailureCodes.CONNECT_FAILED
        override val reasonKey: String get() = "err_cloud_connect_failed"
        override val phase: EgressPhase get() = EgressPhase.PRE
        override val retryable: Boolean get() = true
    }

    /** HTTP 401 / 403。 */
    public data object AuthRejected : CloudFailure {
        override val code: String get() = CloudFailureCodes.AUTH_REJECTED
        override val reasonKey: String get() = "err_cloud_auth_rejected"
        override val phase: EgressPhase get() = EgressPhase.PRE
        override val retryable: Boolean get() = false
    }

    /** HTTP 407（代理）。 */
    public data object ProxyAuthRequired : CloudFailure {
        override val code: String get() = CloudFailureCodes.PROXY_AUTH_REQUIRED
        override val reasonKey: String get() = "err_cloud_proxy_auth"
        override val phase: EgressPhase get() = EgressPhase.PRE
        override val retryable: Boolean get() = false
    }

    /** 端点不满足 TLS 要求（http 且非 LAN 例外）。★ 结构级：一个字节都没发。 */
    public data object InsecureEndpoint : CloudFailure {
        override val code: String get() = CloudFailureCodes.INSECURE_ENDPOINT
        override val reasonKey: String get() = "err_cloud_insecure_endpoint"
        override val phase: EgressPhase get() = EgressPhase.PRE
        override val retryable: Boolean get() = false
    }

    /** ★ 锁 C 的产物：本会话带页面上下文 ⇒ 云端一律硬失败（一个字节都没发）。 */
    public data object PageContextRefused : CloudFailure {
        override val code: String get() = CloudFailureCodes.PAGE_CONTEXT_REFUSED
        override val reasonKey: String get() = "err_cloud_page_context_refused"
        override val phase: EgressPhase get() = EgressPhase.PRE
        override val retryable: Boolean get() = false
    }

    // ── POST：请求体已经发出去了 ─────────────────────────────────────────

    /** HTTP 404 / `model_not_found`。 */
    public data object ModelNotFound : CloudFailure {
        override val code: String get() = CloudFailureCodes.MODEL_NOT_FOUND
        override val reasonKey: String get() = "err_cloud_model_not_found"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }

    /** HTTP 400 `context_length_exceeded`。★ 先压缩再让用户决定，不自动重试。 */
    public data object ContextTooLong : CloudFailure {
        override val code: String get() = CloudFailureCodes.CONTEXT_TOO_LONG
        override val reasonKey: String get() = "err_cloud_context_too_long"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }

    /** HTTP 429。★ ≤2 次，尊重 `Retry-After`。 */
    public data object RateLimited : CloudFailure {
        override val code: String get() = CloudFailureCodes.RATE_LIMITED
        override val reasonKey: String get() = "err_cloud_rate_limited"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = true
    }

    /** HTTP 5xx。★ ≤1 次。 */
    public data object ServerError : CloudFailure {
        override val code: String get() = CloudFailureCodes.SERVER_ERROR
        override val reasonKey: String get() = "err_cloud_server_error"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = true
    }

    /** 首字节超时。★ 是 POST 不是 PRE：请求体早就发完了。 */
    public data object FirstByteTimeout : CloudFailure {
        override val code: String get() = CloudFailureCodes.FIRST_BYTE_TIMEOUT
        override val reasonKey: String get() = "err_cloud_first_byte_timeout"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }

    /** 流空闲超时（两个 chunk 之间隔太久）。 */
    public data object StreamIdleTimeout : CloudFailure {
        override val code: String get() = CloudFailureCodes.STREAM_IDLE_TIMEOUT
        override val reasonKey: String get() = "err_cloud_stream_idle_timeout"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }

    /**
     * ★ 总 deadline 到了（`GenerationRequest.deadlineMs`）。
     *
     * ★ 它是 POST：请求体早就发出去了。已收内容照常落盘 ⇒ PARTIAL。
     */
    public data object DeadlineExceeded : CloudFailure {
        override val code: String get() = CloudFailureCodes.DEADLINE_EXCEEDED
        override val reasonKey: String get() = "err_cloud_deadline_exceeded"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }

    /** ★ 流被截断：没有 `data: [DONE]` 就 EOF。已收内容照常落盘。 */
    public data object StreamTruncated : CloudFailure {
        override val code: String get() = CloudFailureCodes.STREAM_TRUNCATED
        override val reasonKey: String get() = "err_cloud_stream_truncated"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }

    /**
     * ★ 收到 `[DONE]` 但 content 全空（或只有 usage chunk）。
     * ★★ **绝不允许「云端空了就用本地补一段」** —— 那正是静默回落的变体（§4.4）。
     */
    public data object EmptyDelta : CloudFailure {
        override val code: String get() = CloudFailureCodes.EMPTY_DELTA
        override val reasonKey: String get() = "err_cloud_empty_delta"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }

    /** 收到 HTML 错误页 / 非 JSON 响应。★ 只记 content-type，**不记 body**。 */
    public data object MalformedResponse : CloudFailure {
        override val code: String get() = CloudFailureCodes.MALFORMED_RESPONSE
        override val reasonKey: String get() = "err_cloud_malformed_response"
        override val phase: EgressPhase get() = EgressPhase.POST
        override val retryable: Boolean get() = false
    }
}

/**
 * ★ 稳定的机器码。UI / 遥测 / 重试策略 / 单测全部用它做判断
 * （与 `AppError.code` 同一条理由：跨 Release 稳定，可以重构类名但不能悄悄换码）。
 */
public object CloudFailureCodes {
    public const val NO_PERMIT: String = "CLOUD_NO_PERMIT"
    public const val CREDENTIAL_MISSING: String = "CLOUD_CRED_MISSING"
    public const val CONNECT_FAILED: String = "CLOUD_CONNECT_FAILED"
    public const val AUTH_REJECTED: String = "CLOUD_AUTH_REJECTED"
    public const val PROXY_AUTH_REQUIRED: String = "CLOUD_PROXY_AUTH_REQUIRED"
    public const val MODEL_NOT_FOUND: String = "CLOUD_MODEL_NOT_FOUND"
    public const val CONTEXT_TOO_LONG: String = "CLOUD_CONTEXT_TOO_LONG"
    public const val RATE_LIMITED: String = "CLOUD_RATE_LIMITED"
    public const val SERVER_ERROR: String = "CLOUD_SERVER_ERROR"
    public const val FIRST_BYTE_TIMEOUT: String = "CLOUD_FIRST_BYTE_TIMEOUT"
    public const val STREAM_IDLE_TIMEOUT: String = "CLOUD_STREAM_IDLE_TIMEOUT"
    public const val STREAM_TRUNCATED: String = "CLOUD_STREAM_TRUNCATED"
    public const val DEADLINE_EXCEEDED: String = "CLOUD_DEADLINE_EXCEEDED"
    public const val EMPTY_DELTA: String = "CLOUD_EMPTY_DELTA"
    public const val MALFORMED_RESPONSE: String = "CLOUD_MALFORMED_RESPONSE"

    /** ★ 锁 C 的产物（§5.4）。 */
    public const val PAGE_CONTEXT_REFUSED: String = "CLOUD_PAGE_CONTEXT_REFUSED"

    public const val INSECURE_ENDPOINT: String = "CLOUD_INSECURE_ENDPOINT"
}
