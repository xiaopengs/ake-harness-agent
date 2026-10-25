package com.ake.data.persist.guard

import com.ake.core.ports.TrustScanVerdict

/**
 * `CharterScanner` —— 人设正文的静态扫描（上游 §8.3 S2）。
 *
 * ## ★★ 先说清楚它不是什么
 *
 > **这不是防线。**
 >
 > 它是**正则**，必然不完备：换词、同义改写、跨句构造都能绕过。
 > 更根本的是 —— 我们无法要求一个 2.6B 模型对精心构造的社会工程指令保持鲁棒。
 > 因此它只是「安装/编辑期的人性化提醒」，**真正的遏制是 S3 与 S4**：
 >   - **S3（授权与文本彻底解耦）**：一个 Agent 能调用什么**完全**由
 >     `tool_registry.enabled ∩ agent_capability_grant ∩ FORBIDDEN ∩ 数量上限` 决定，
 *     `<persona>` 段里的任何文本**都不可能**开通或关闭任何一个能力；
 *   - **S4（确认与流派无关）**：`SkillConfirmPolicy` 由 `CapabilityRef` +
 *     `tool_registry.always_allow` 决定，与人设里写了什么无关。
 *
 * 于是恶意人设的爆炸半径被限制在「说话风格可能变坏」——
 * 它拿不到新工具，也变不成 `HumanGate` 的豁免。
 *
 * ⚠ 关千锤请注意：**不得在验收报告里写「已通过 CharterScanner 防御人设注入」。**
 *
 * ## ★ 为什么它落在 `:data` 而不是上游写的 `:agent`（一处刻意的位置修正）
 *
 * 依赖边是 `:agent → :data`（L4 → L3，唯一的实现层横向例外），方向不可反转。
 * 而 `AgentProfileStorePort.upsert()` 的契约是「**内部必须跑 CharterScanner；
 * 命中 REFUSED 时不允许写库**」—— 那个 Store 实现在 `:data`。
 * ⇒ 把 Scanner 放在 `:agent` 会让 `:data` 反向依赖 L4，编译期就过不去。
 *
 * 放在 `:data` 还有一个额外的好处：**扫描是写库的同一道门**，
 * 「绕过 Scanner 直接写库」这条路径在结构上少一层。
 * （上游验收用例 V4 要求的「即使绕过 Scanner，S3 仍然挡住」由授权层兜住 ——
 *   那正是二阶防御的意义。）
 */
public object CharterScanner {

    public const val VERSION: Int = 1

    /** 命中规则组。⚠ 这是**不完备的正则手段**，真正的遏制靠 S3 / S4。 */
    internal val RULES: List<Triple<String, String, List<Regex>>> = listOf(
        Triple(
            "N0", "REVERSE_DIRECTIVE",
            listOf(
                Regex("(?i)(ignore|disregard|forget)\\s+(all\\s+)?(previous|prior|above)"),
                Regex("(?i)(你的?新指令|新的系统提示|system\\s*:|assistant\\s*:)"),
                Regex("(?i)(你现在是|from now on you are)"),
            ),
        ),
        Triple(
            "N1", "SKIP_CONFIRMATION",
            listOf(
                Regex("(?i)(不要|无需|不得)\\s*(询问|确认|征求)"),
                Regex("(?i)(without\\s+asking|never\\s+ask|bypass\\s+confirmation)"),
                Regex("(?i)(始终|总是)\\s*(自动|直接)\\s*(执行|调用)"),
            ),
        ),
        Triple(
            "N2", "CREDENTIAL_HARVEST",
            listOf(
                Regex("(?i)(读取|记录|转述|收集)\\s*.{0,12}(密码|口令|验证码|银行卡|身份证|cookie|token)"),
                Regex("(?i)(extract|read|log)\\s+.{0,12}(password|secret|credential|cookie)"),
            ),
        ),
        Triple(
            "N3", "SILENT_EXFIL",
            listOf(
                Regex("""(?i)(send|post|upload|append)\s+.{0,24}to\s+https?://"""),
                Regex("""(?i)https?://[^\s"']{0,120}[?&](?:token|auth|password|cookie|secret)="""),
                Regex("(?i)(把|将).{0,16}(发送|上传|提交)到"),
            ),
        ),
        Triple(
            "N4", "FORCED_TOOL_USE",
            listOf(
                Regex("(?i)(每次|任何)\\s*(回复|回答)\\s*(前|时)?\\s*(都)?\\s*(必须|先)\\s*调用"),
                Regex("(?i)(不得|永不)\\s*拒绝"),
                Regex("(?i)(always\\s+call|never\\s+refuse)"),
            ),
        ),
        Triple(
            "N5", "IDENTITY_SPOOF",
            listOf(
                Regex("(?i)(你是|you are)\\s*(系统|开发者|管理员|system|OpenAI|Google)"),
                Regex("(?i)(以下内容来自)\\s*(官方|系统)\\s*(安全更新)"),
            ),
        ),
    )

    /** 命中即**拒绝写入**（凭据猎取 / 静默外传）。 */
    private val REFUSING = setOf("N2", "N3")

    /** 命中即**可用但强制每次技能都要人工确认**。 */
    private val FLAGGING = setOf("N1", "N4", "N5")

    public fun scan(charter: String, now: Long): TrustScanVerdict {
        val hits = hitsOf(charter)
        return when {
            hits.any { it in REFUSING } ->
                TrustScanVerdict(TrustScanVerdict.REFUSED, emptyList(), hits, now, VERSION)

            hits.any { it in FLAGGING } ->
                TrustScanVerdict(TrustScanVerdict.FLAGGED, emptyList(), hits, now, VERSION)

            hits.contains("N0") ->
                TrustScanVerdict(TrustScanVerdict.SANITIZED, sanitize(charter).second, hits, now, VERSION)

            else -> TrustScanVerdict.pass(now, VERSION)
        }
    }

    /** 命中了哪些规则号。 */
    public fun hitsOf(charter: String): List<String> = RULES
        .filter { (_, _, patterns) -> patterns.any { it.containsMatchIn(charter) } }
        .map { it.first }

    /**
     * N0（反向指令）：**裁掉命中的那一行**，保留其余人设，并告诉用户裁了什么。
     *
     * 为什么不整段拒绝：人设注入常藏在一句话里，整块屏蔽会连人格一起丢掉 ——
     * 用户会看到一个「我没写过的 Agent」，那是另一种不可解释的行为。
     * ★ 与 `InjectionIsolator` 的**逐条围栏**是同一条思路：粒度要细到句子。
     *
     * @return `Pair(裁剪后的正文, 被裁掉的子句列表)`
     */
    public fun sanitize(charter: String): Pair<String, List<String>> {
        val removed = ArrayList<String>()
        val kept = charter.lineSequence().filter { line ->
            val hit = N0_PATTERNS.any { it.containsMatchIn(line) }
            if (hit) removed += line.trim().take(80)
            !hit
        }.joinToString("\n")
        return kept to removed
    }

    /**
     * ★ 扫描 + 取正文的**一步到位入口** —— 落库只应该用这一个。
     *
     * 把它做成显式返回「应当落库的正文」，是为了让调用方**不可能忘记**：
     * 命中 N0 时写进去的文本**不是**用户输入的原文。
     */
    public fun scanAndText(charter: String, now: Long): Pair<TrustScanVerdict, String> {
        val verdict = scan(charter, now)
        val text = if (verdict.verdict == TrustScanVerdict.SANITIZED) sanitize(charter).first else charter
        return verdict to text
    }

    private val N0_PATTERNS: List<Regex> = RULES.first { it.first == "N0" }.third
}
