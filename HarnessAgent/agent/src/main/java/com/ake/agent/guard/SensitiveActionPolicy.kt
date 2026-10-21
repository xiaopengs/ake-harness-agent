package com.ake.agent.guard

import com.ake.core.common.Url
import com.ake.core.contract.RiskClass
import com.ake.core.contract.StepAction
import com.ake.core.contract.UiElement

/**
 * 动作分级与敏感操作清单（架构 §8.5）。
 *
 * **风险判断必须只在这里发生** —— 架构原话：「不要在 UI 层或 Agent 层判断风险，
 * 那是护栏的职责」。散落的判定一定会漏，而安全代码是唯一不能出错的那类代码。
 *
 * ## 三档分级的处置
 *
 * | 档 | 处置 | 例子 |
 * |---|---|---|
 * | FORBIDDEN | **一律 Denied，不提供确认选项** | 支付、下单、凭据自动填充 |
 * | REQUIRE_CONFIRM | 挂起等 UI（可「本次允许」或「总是允许」） | 提交表单、加购物车、跨域名、首次调用 MCP 工具 |
 * | 其余 | 放行 | 点击、滚动、抽取 |
 *
 * ## 为什么「支付类」连确认都不给
 *
 * 确认对话框的价值依赖**用户真的读懂了它在确认什么**。而「确认支付 ¥1,299」
 * 这类对话框在移动端已经成为最有效的钓鱼界面 —— 用户会本能地点确定。
 * 端侧 MVP 的正确做法是**根本不提供这条路径**（§8.5 原文：「一律禁止 —— 不提供确认选项」），
 * 而不是做一个用户看都不看的确认框。
 */
object SensitiveActionPolicy {

    /**
     * 一律禁止的**动作语义 key**。
     *
     * 判定依据是「元素文本 + 属性」推断出的语义，不是 URL 或选择器 ——
     * 选择器随页面改版会失效，而「结算按钮上写着『去结算』」这件事是稳定的。
     */
    val FORBIDDEN_SEMANTICS: Set<String> = setOf(
        "payment.submit",
        "cart.checkout",
        "order.place",
        "credential.autofill",
        "file.upload_sensitive",
    )

    /** 触发 FORBIDDEN 的元素文本关键词（中英双语；端侧 Gemma 4 E2B 中文可用）。 */
    private val FORBIDDEN_TEXT: List<Regex> = listOf(
        Regex(".*(去结算|立即结算|提交订单|下单|去支付|立即支付|确认支付|付款|结帐|结账|checkout|place order|pay now|confirm payment).*", RegexOption.IGNORE_CASE),
        Regex(".*(确认购买|立即购买|马上抢|buy now|order now).*", RegexOption.IGNORE_CASE),
        Regex(".*(自动填充密码|autofill password|保存密码).*", RegexOption.IGNORE_CASE),
        Regex(".*(上传身份证|上传证件|上传护照).*", RegexOption.IGNORE_CASE),
    )

    /** 需要二次确认的语义 → i18n reasonKey（§8.5 的 map 形态）。 */
    val REQUIRE_CONFIRM: Map<String, String> = mapOf(
        "form.submit_all" to "confirm.submit_form",
        "credential.login" to "confirm.login",
        "cart.add" to "confirm.add_to_cart",
        "download.start" to "confirm.download",
        "external.navigate" to "confirm.cross_domain",
        "message.send" to "confirm.send_message",
        "mcp.tool.invoke" to "confirm.mcp_first_use",
        "account.modify" to "confirm.account_modify",
        "data.delete" to "confirm.data_delete",
    )

    private val CONFIRM_TEXT: Map<String, List<Regex>> = mapOf(
        "form.submit_all" to listOf(Regex(".*(提交|保存更改|确认修改|submit).*", RegexOption.IGNORE_CASE)),
        "credential.login" to listOf(Regex(".*(登录|登陆|sign in|log in).*", RegexOption.IGNORE_CASE)),
        "cart.add" to listOf(Regex(".*(加入购物车|加购|add to cart|add to bag).*", RegexOption.IGNORE_CASE)),
        "download.start" to listOf(Regex(".*(下载|download|导出文件).*", RegexOption.IGNORE_CASE)),
        "message.send" to listOf(Regex(".*(发送|回复|评论|发表|send|post comment).*", RegexOption.IGNORE_CASE)),
        "account.modify" to listOf(Regex(".*(修改资料|更改绑定|注销|解绑).*", RegexOption.IGNORE_CASE)),
        "data.delete" to listOf(Regex(".*(删除|移除|清空|delete|remove).*", RegexOption.IGNORE_CASE)),
    )

    /**
     * 静态风险分级（不看页面的部分）。
     *
     * 只看动作**形态**：抽数是只读，导航/输入会改变状态，调外部工具可能产生域外副作用。
     * 页面相关的部分（这个按钮是不是「去结算」）在 [classifyClick] 里做。
     */
    fun classify(action: StepAction): RiskClass = when (action) {
        is StepAction.Extract -> RiskClass.READ_ONLY
        is StepAction.Finish -> RiskClass.READ_ONLY
        is StepAction.AskHuman -> RiskClass.READ_ONLY
        is StepAction.Scroll -> RiskClass.READ_ONLY

        is StepAction.Click -> RiskClass.PAGE_MUTATING
        is StepAction.Select -> RiskClass.PAGE_MUTATING
        is StepAction.Type -> if (action.submit) RiskClass.SENSITIVE else RiskClass.PAGE_MUTATING

        is StepAction.Navigate -> RiskClass.PAGE_MUTATING
        is StepAction.CallTool -> RiskClass.EXTERNAL_SIDE_EFFECT
    }

    /** 一级判定：这个动作**形态上**就属于禁止类。 */
    fun isForbiddenShape(action: StepAction): Boolean = when (action) {
        is StepAction.CallTool -> action.toolId.value in FORBIDDEN_TOOL_IDS
        else -> false
    }

    /** 二级判定：目标元素的语义是否属于禁止类。 */
    fun forbiddenSemanticOf(el: UiElement): String? {
        val hay = buildString {
            append(el.text)
            append(' ')
            append(el.attrs["aria-label"].orEmpty())
            append(' ')
            append(el.attrs["value"].orEmpty())
            append(' ')
            append(el.attrs["title"].orEmpty())
        }
        for (re in FORBIDDEN_TEXT) {
            if (re.matches(hay)) {
                return when {
                    hay.contains("结算") || hay.contains("订单") || hay.contains("支付") ||
                        hay.contains("checkout", true) || hay.contains("pay", true) -> "payment.submit"
                    hay.contains("购买") || hay.contains("buy", true) -> "order.place"
                    hay.contains("自动填充") || hay.contains("autofill", true) -> "credential.autofill"
                    else -> "file.upload_sensitive"
                }
            }
        }
        return null
    }

    /** 目标元素的语义是否需要二次确认。 */
    fun confirmSemanticOf(el: UiElement): String? {
        val hay = buildString {
            append(el.text)
            append(' ')
            append(el.attrs["aria-label"].orEmpty())
            append(' ')
            append(el.attrs["value"].orEmpty())
        }
        for ((key, res) in CONFIRM_TEXT) {
            if (res.any { it.matches(hay) }) return key
        }
        return null
    }

    /** §8.6：字段语义 —— 判断某个输入框是不是凭据类（禁止向它输入）。 */
    fun looksLikeCredential(el: UiElement): Boolean {
        val type = el.attrs["type"]?.lowercase()
        if (type in CREDENTIAL_INPUT_TYPES) return true
        val ac = el.attrs["autocomplete"]?.lowercase()
        if (ac != null && ac.startsWith("cc-")) return true
        if (ac in CREDENTIAL_AUTOCOMPLETE) return true
        val name = (el.attrs["name"].orEmpty() + el.attrs["id"].orEmpty()).lowercase()
        return CREDENTIAL_NAME_HINTS.any { it in name }
    }

    /** §8.4：这个表单看起来涉及金融信息（提交前需确认）。 */
    fun formLooksFinancial(dom: com.ake.core.contract.PocketDom?): Boolean {
        if (dom == null) return false
        val hay = dom.candidates.joinToString(" ") { it.text + " " + it.attrs.values.joinToString(" ") }
        return FINANCIAL_HINTS.any { it.containsMatchIn(hay) }
    }

    /**
     * URL 里是否携带凭据型参数（T3 数据外泄）。
     *
     * 注意：这是**检测**而不是拦截 —— 拦截发生在 `Navigation` 的域名策略里。
     * 这里的作用是打标并升级严重度，让 `policy_decision` 表里留下可审计的证据。
     */
    fun carriesCredentialInUrl(url: Url): Boolean {
        val q = url.value.substringAfter('?', "")
        if (q.isEmpty()) return false
        return SENSITIVE_QUERY_KEYS.any { k ->
            Regex("(?i)(?:^|[?&])$k=").containsMatchIn(q)
        }
    }

    private val FORBIDDEN_TOOL_IDS: Set<String> = setOf(
        "mcp.payment",
        "mcp.checkout",
        "browser.checkout",
    )

    private val CREDENTIAL_INPUT_TYPES: Set<String> = setOf("password", "email-otp", "tel-otp")

    private val CREDENTIAL_AUTOCOMPLETE: Set<String> = setOf(
        "current-password", "new-password", "one-time-code", "cc-number", "cc-csc",
    )

    private val CREDENTIAL_NAME_HINTS: List<String> = listOf(
        "password", "passwd", "pwd", "passcode", "cvv", "cvc", "cardno", "idcard", "otp", "smscode",
    )

    private val FINANCIAL_HINTS: List<Regex> = listOf(
        Regex("(?i)(银行卡|信用卡|bank card|credit card|cvv|cvc)"),
        Regex("(?i)(支付|付款|结算|pay|payment|checkout)"),
        Regex("(?i)(发票|invoice|税号)"),
    )

    private val SENSITIVE_QUERY_KEYS: Set<String> = setOf(
        "token", "access_token", "refresh_token", "id_token", "auth", "authorization",
        "password", "passwd", "pwd", "secret", "session", "sessionid", "sid",
        "cookie", "cvv", "cvc", "card", "cardno", "idcard", "sig", "signature", "key",
    )
}
