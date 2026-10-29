package com.ake.cloudai

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.LogLevel
import com.ake.core.common.LogSink
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CloudFailureCodes
import com.ake.core.ports.PlainSecret
import com.ake.core.ports.PromptContext
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * ★★ 凭证不泄漏（架构 08 §6.3 的八条清单 / §9.3 T7–T11）
 *
 * 钉死的是**泄漏通道**，不是字符串：
 *   ① 返回值　② 异常信息　③ 日志　④ 序列化输出
 * 外加最强的一条 —— **`CloudFailure` 在类型上就没有能装秘密的字段**。
 *
 * ★ 负向测试的判据：把明文**注入**进去，再断言它**出不来**。
 */
class CredentialLeakageTest {

    private companion object {
        const val SECRET = "sk-live-abcdef123456"
        const val BASE_URL = "https://gw.example.com/v1?key=$SECRET"

        /** §6.3：这些成员名一旦出现在 `CloudFailure` 上，迟早会被人塞进服务端原文。 */
        val FORBIDDEN_MEMBERS: Set<String> = setOf(
            "message", "cause", "body", "url", "headers", "token", "secret",
        )

        /**
         * ★★ **反射枚举** `CloudFailure` 的**全部**子类型并实例化。
         *
         * ## 为什么必须是反射，而不是手写列表
         *
         * 这是这条守卫**全部价值所在**。手写列表 + 写死数量意味着：
         *  · 新增变体时它**不会**自动被检查；
         *  · 唯一能发现的是那条写死的数字断言红 ⇒ 红得莫名其妙；
         *  · 于是最省事的「修法」就是把数字 +1 ⇒ **守卫被绕过**。
         *
         * 反射枚举 ⇒ 新增变体**自动**纳入检查，且不需要任何魔法数字。
         * 完备性由 [everyVariantIsCovered] 用「反射数 == 实例数」钉死：
         * 新增变体若没被实例化出来，这条会红。
         */
        /**
         * ⚠ 入参是 `Class<*>` 而不是 `Class<out CloudFailure>`：
         * `permittedSubclasses` 的 Kotlin 签名就是 `Array<Class<*>>`（元素类型擦成 `Class<*>`），
         * 传不进 `Class<out CloudFailure>`。**不 cast** —— cast 只是把类型谎报给编译器，
         * 真正兜住「它确实是 CloudFailure」的是下面的 `as? CloudFailure`（返回 null ⇒
         * [everyVariantIsCoveredByTheLeakScan] 立刻红）。这样类型系统说真话。
         */
        private fun instantiate(cls: Class<*>): CloudFailure? {
            runCatching { cls.getDeclaredField("INSTANCE").get(null) }
                .getOrNull()?.let { return it as? CloudFailure }
            val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount == 1 }
            return runCatching { ctor?.newInstance(testPermit()) }.getOrNull() as? CloudFailure
        }

        fun allFailureVariants(): List<CloudFailure> =
            CloudFailure::class.java.permittedSubclasses.mapNotNull(::instantiate)
    }

    // ═══ 最强的一条：类型上没有地方可以放秘密 ═══

    /** `CloudFailure` 不得声明 `message` / `cause` / `body` / `url` / `headers`。 */
    @Test
    fun cloudFailureDeclaresNoSecretBearingMember() {
        val declared = CloudFailure::class.java.declaredMethods.map { it.name }.toSet() +
            CloudFailure::class.java.declaredFields.map { it.name }.toSet()

        for (word in FORBIDDEN_MEMBERS) {
            assertThat(declared).containsNoneOf(word, word.replaceFirstChar { it.uppercaseChar() })
        }
    }

    /**
     * 每个变体不得暴露**自由文本**；且不得有返回 [String] / [Throwable] 的成员。
     *
     * ## ⚠ 为什么判据是「取值」而不是「类型」
     *
     * 初版这里写的是「declaredMethods 的 returnType 不许出现 String」——
     * 这条**必然误杀**：`CloudFailure` 的 17 个变体全是 `data object`，
     * 而 `data object` 天生带 `toString(): String`。按类型判 ⇒ 全军覆没。
     *
     * 但真正要守的不是「有没有 String 出口」，而是
     * **「有没有能把服务端原文吐出来的出口」**。于是判据换成**取值**：
     *
     *   · 无参 String 出口（`getCode` / `getReasonKey` / `toString`）逐个**调用**；
     *   · 返回值必须 ∈ {`code`, `reasonKey`, 类名}。
     *
     * 服务端原文（`"Incorrect API key provided: sk-live-…"`、响应体片段、
     * ktor 的 `message`）**不可能**恰好等于这三者中的任何一个 ⇒ 一律红。
     *
     * ★ 这条同时覆盖了「有人把变体改成 data class 并加了个 `detail: String`」：
     *   `getDetail()` / `component5()` 都是无参 String 出口，会被抓出来。
     *   而 `copy(…)` 有参数 ⇒ 不调用（它也无法凭空造出文本，只能搬运已有字段）。
     */
    @Test
    fun noCloudFailureVariantDeclaresSecretBearingMember() {
        for (v in allFailureVariants()) {
            val cls = v.javaClass
            val names = cls.declaredMethods.map { it.name }.toSet() +
                cls.declaredFields.map { it.name }.toSet()

            for (word in FORBIDDEN_MEMBERS) {
                assertThat(names).containsNoneOf(word, word.replaceFirstChar { it.uppercaseChar() })
            }

            // ① 类型层面：Throwable 一定带 message；字节/字符数组是藏文本的地方。
            val types = cls.declaredMethods.map { it.returnType } +
                cls.declaredFields.map { it.type }
            assertThat(types).containsNoneOf(
                Throwable::class.java,
                ByteArray::class.java,
                CharArray::class.java,
            )

            // ② 取值层面（真正的 oracle）：
            val allowed = setOf(v.code, v.reasonKey, cls.simpleName)
            for (m in cls.declaredMethods) {
                if (m.parameterCount != 0 || m.returnType != String::class.java) continue
                val got = m.invoke(v) as String
                assertThat(allowed).contains(got)
            }
        }
    }

    /**
     * ★★ 完备性守卫：反射到的变体数 == 实际被检查的实例数。
     *
     * ⇒ 任何人新增一个 `CloudFailure` 变体，若没有把它接进
     *   [allFailureVariants]，这条就红；而红的原因是**明确的**
     *   （「有个变体没被检查」），不是 off-by-one。
     */
    @Test
    fun everyVariantIsCoveredByTheLeakScan() {
        val reflected = CloudFailure::class.java.permittedSubclasses
        val checked = allFailureVariants()

        assertThat(reflected).isNotEmpty()
        assertThat(checked).hasSize(reflected.size)
    }

    /** 每个变体的 `code` 与 `reasonKey` 都必须是非空、且全局唯一的 i18n 常量。 */
    @Test
    fun everyVariantCarriesAStableUniqueMachineCode() {
        val variants = allFailureVariants()

        for (v in variants) {
            assertThat(v.code).isNotEmpty()
            assertThat(v.reasonKey).isNotEmpty()
            // ★ reasonKey 必须是 i18n 常量 ⇒ 不允许空格（防止有人拼了服务端原文）
            assertThat(v.reasonKey).doesNotContain(" ")
        }
        assertThat(variants.map { it.code }.toSet()).hasSize(variants.size)
    }

    // ═══ ① 返回值 ═══

    @Test
    fun secretToStringIsAlwaysRedacted() {
        val s = PlainSecret.of(SECRET)
        assertThat(s.toString()).isEqualTo("***")
        assertThat("$s").isEqualTo("***")
        assertThat(listOf(s).toString()).doesNotContain(SECRET)
        assertThat(mapOf("a" to s).toString()).doesNotContain(SECRET)
        assertThat("bearer=$s").doesNotContain(SECRET)
    }

    /** 明文只在 λ 内可见；λ 之外拿不到（§6.4）。 */
    @Test
    fun useAsBearerIsTheOnlyReadout() {
        val s = PlainSecret.of(SECRET)
        var seen: String? = null
        s.useAsBearer { seen = it }
        assertThat(seen).isEqualTo(SECRET)

        // ★★ 这就是本轮的第二个红：value class 会生成 public 的 `unbox-impl()`，
        //    它是一个**第二个明文读取口**（反射 / Java 互操作都能直接取回明文）。
        //    改成普通 final class 之后，它必须消失。
        val noArgGetters = PlainSecret::class.java.declaredMethods
            .filter { it.parameterCount == 0 }
            .map { it.name }
        assertThat(noArgGetters).containsNoneOf(
            "getValue", "getSecret", "expose", "unbox-impl", "unbox_impl",
        )

        // ★ 逆向确认：真的没有任何一个 public 无参方法能返回明文 String
        val leaksPlaintext = PlainSecret::class.java.methods.any { m ->
            m.parameterCount == 0 && m.returnType == String::class.java && m.name != "toString"
        }
        assertThat(leaksPlaintext).isFalse()
    }

    @Test
    fun endpointConfigHostOfNeverLeaksTheQuery() {
        val cfg = FakeCredentials(BASE_URL).exposedConfig()
        val host = cfg.hostOf()
        assertThat(host).isEqualTo("gw.example.com")
        assertThat(host).doesNotContain(SECRET)
        assertThat(host).doesNotContain("key=")
        assertThat(host).doesNotContain("/v1")
    }

    // ═══ ② 异常信息 ═══

    /** 底层异常（ktor 的 message 常含带 key 的完整 URL）不得被保留。 */
    @Test
    fun appErrorBuiltFromCloudFailureCarriesNoCause() {
        val e = AppError(
            code = CloudFailure.AuthRejected.code,
            reasonKey = CloudFailure.AuthRejected.reasonKey,
            severity = Severity.MEDIUM,
        )
        assertThat(e.cause).isNull()
        assertThat(e.toString()).doesNotContain(SECRET)
        assertThat(e.toString()).doesNotContain("gw.example.com/v1?key")
    }

    /** 即便被包进 RuntimeException，`.message` 也不会含明文。 */
    @Test
    fun secretNeverAppearsInThrowableMessage() {
        val s = PlainSecret.of(SECRET)
        val t = RuntimeException("cloud call failed: $s")
        assertThat(t.message).doesNotContain(SECRET)
        assertThat(t.toString()).doesNotContain(SECRET)
    }

    // ═══ ③ 日志 ═══

    /** 跑一次失败路径，收集全部日志 ⇒ 断言不含秘密与完整 URL。 */
    @Test
    fun logsNeverContainSecretOrFullUrl() = runBlocking {
        val captured = mutableListOf<String>()
        val sink = LogSink { level, tag, message, cause ->
            captured += "$level/$tag/$message"
            if (cause != null) captured += "cause=${cause.message.orEmpty()}"
        }
        AkeLog.install(sink)
        val previous = AkeLog.minLevel
        AkeLog.minLevel = LogLevel.VERBOSE
        try {
            val transport = ScriptedTransport(failWith = CloudTransportOutcome.ConnectFailed)
            val port = OpenAiCompatibleDialogPort(
                FakeCredentials(BASE_URL),
                transport,
                CloudRuntimeConfig.DEFAULT,
                FixedClock(0L),
            )
            val opened = port.openDialog(testSpec(), testPermit(), PromptContext.TextOnly)
            assertThat(opened).isInstanceOf(Outcome.Ok::class.java)
            val session = (opened as Outcome.Ok).value
            val result = session.generateStream(testRequest()) { }
            assertThat(result).isInstanceOf(Outcome.Err::class.java)
        } finally {
            AkeLog.uninstall(sink)
            AkeLog.minLevel = previous
        }

        val joined = captured.joinToString("\n")
        assertThat(joined).doesNotContain(SECRET)
        assertThat(joined).doesNotContain("key=")
    }

    // ═══ ④ 序列化输出 ═══

    /** `PlainSecret` 不带 `@Serializable` ⇒ 编不出来，也就不存在「序列化输出」这条通道。 */
    @Test
    fun secretIsNotSerializable() {
        val annotated = PlainSecret::class.java.isAnnotationPresent(
            kotlinx.serialization.Serializable::class.java,
        )
        assertThat(annotated).isFalse()
    }

    /**
     * ★ 唯一的实例字段必须是 **private**。
     *
     * ⚠ **边界**：本断言只守住「源码声明的可访问性」。
 *   它**不**防御 `setAccessible(true)` 后读取 private 字段 —— JVM 反射一旦放开，
 *   private 就没有意义了。那属于 root / 注入型攻击面，不在本轮范围（§9.3）。
     */
    @Test
    fun plaintextFieldIsPrivate() {
        val field = PlainSecret::class.java.getDeclaredField("value")
        assertThat(java.lang.reflect.Modifier.isPrivate(field.modifiers)).isTrue()
    }

    /** 端点对外文本（`displayOf`）不含 query。 */
    @Test
    fun displayOfExposesHostAndPathOnly() {
        assertThat(CloudEndpoints.displayOf(BASE_URL))
            .isEqualTo("gw.example.com/v1/chat/completions")
        assertThat(CloudEndpoints.displayOf(BASE_URL)).doesNotContain(SECRET)
    }

    /** 拼接后的 URL 不再带用户塞在 query 里的 key。 */
    @Test
    fun composedUrlDropsTheUserQuery() {
        assertThat(CloudEndpoints.chatCompletionsUrl(BASE_URL))
            .isEqualTo("https://gw.example.com/v1/chat/completions")
        assertThat(CloudEndpoints.chatCompletionsUrl(BASE_URL)).doesNotContain(SECRET)
    }

    /**
     * ★★ 请求体里绝不允许出现 Authorization 段。
     *
     * §6.3 ②：`Authorization` 头只在 `:cloudai` 内部一处被拼装，
     * **不进任何返回值、不进任何 data class** —— 请求体属于「文本产物」，
     * 一旦它带着 `Bearer sk-...`，就会随「落盘 / 日志 / 崩溃快照」一起泄漏。
     */
    @Test
    fun requestBodyNeverContainsAuthorization() {
        val body = CloudRequestBody.build(
            cfg = FakeCredentials(BASE_URL).exposedConfig(),
            spec = testSpec(),
            currentTurn = "hello",
            maxOutputTokens = 128,
            stream = true,
            jsonConstraint = false,
        )
        assertThat(body).doesNotContain("Authorization")
        assertThat(body).doesNotContain("Bearer ")
        assertThat(body).doesNotContain(SECRET)
    }
}
