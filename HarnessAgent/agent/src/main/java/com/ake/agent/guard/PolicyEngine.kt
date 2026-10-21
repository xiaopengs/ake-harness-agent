package com.ake.agent.guard

import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.common.Ulid
import com.ake.core.common.Url
import com.ake.core.contract.PolicyDecision
import com.ake.core.contract.PolicyDecisionRecord
import com.ake.core.contract.RiskClass
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolId
import com.ake.core.ports.ActionContext
import com.ake.core.ports.ContentSource
import com.ake.core.ports.PolicyDecisionSink
import com.ake.core.ports.PolicyPort
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.Sanitized
import com.ake.core.ports.ToolPermissionStorePort

/**
 * 闸 4 —— `PolicyPort` 的唯一实现（架构 §8.4）。
 *
 * **所有动作执行的唯一合法闸口。** 没有任何路径绕过它：
 * `:tools:mcp` 接入的第三方 MCP 工具完全不知道我们的域名策略，
 * 但只要它走 `ToolRegistry.invoke` → `PolicyPort.authorize` 这条唯一路径，就自动受保护。
 * **单一执行点是防御体系的地基。**
 *
 * ## 判定顺序（顺序即优先级，不能换）
 *
 * ```
 * ① 形态禁止（FORBIDDEN 硬规则：支付/下单/凭据自动填充/证件上传）  → Denied(HIGH)
 * ② 闸 5 参数语义校验（URL scheme / host / 输入长度 / 字段语义）   → Denied
 * ③ 逐工具授权状态（禁用 ⇒ Denied；Always-Allow ⇒ 跳过 ④）
 * ④ §8.5 敏感操作二次确认清单                                      → RequiresConfirm
 * ⑤ ALLOW
 * ```
 *
 * ① 必须在 ② 之前：一个「去结算」按钮即便 URL 合法、参数齐全，也不该走到「要不要确认」这一步。
 * ② 必须在 ③ 之前：参数不合法的动作不配谈授权。
 *
 * ## 所有决策必须落盘
 *
 * 每条判定都写 [PolicyDecisionSink]（非挂起，try-send）。**审计不是可选项**：
 * 当用户问「它为什么点了这个」时，唯一的答案来源就是 `policy_decision` 表。
 * 落盘失败不阻断判定（返回 false 只记 WARN）—— 审计写失败不该拖慢主循环。
 */
class PolicyEngine(
    private val domainPolicy: DomainPolicy = DomainPolicy.DEFAULT,
    private val clock: Clock,
    private val sink: PolicyDecisionSink,
    private val toolPermissions: ToolPermissionStorePort? = null,
    /** 工具风险查询。由 `CompositeToolRegistry` 提供；未提供时按动作形态兜底。 */
    private val toolRisk: suspend (ToolId) -> RiskClass? = { null },
    private val sanitizer: ContentSanitizer = ContentSanitizer(),
) : PolicyPort {

    override fun sanitize(text: String, source: ContentSource): Sanitized =
        sanitizer.sanitize(text, source)

    override suspend fun authorize(action: StepAction, ctx: ActionContext): Outcome<PolicyVerdict> {
        val verdict = evaluate(action, ctx)
        record(action, ctx, verdict)
        return Outcome.Ok(verdict)
    }

    // ──────────────────────────────────────────── 判定主体

    private suspend fun evaluate(action: StepAction, ctx: ActionContext): PolicyVerdict {
        // ① 形态禁止
        if (SensitiveActionPolicy.isForbiddenShape(action)) {
            return PolicyVerdict.Denied("err.forbidden_action", Severity.HIGH)
        }

        return when (action) {
            is StepAction.Navigate -> checkNavigate(action.url, ctx)
            is StepAction.Click -> checkClick(action, ctx)
            is StepAction.Select -> checkSelect(action, ctx)
            is StepAction.Type -> checkType(action, ctx)
            is StepAction.Scroll -> PolicyVerdict.Allow
            is StepAction.Extract -> checkExtract(action)
            is StepAction.CallTool -> checkTool(action, ctx)
            is StepAction.AskHuman -> PolicyVerdict.Allow
            is StepAction.Finish -> PolicyVerdict.Allow
        }
    }

    // ── ② 导航：T3 数据外泄的头号通道，判定最严
    private suspend fun checkNavigate(raw: String, ctx: ActionContext): PolicyVerdict {
        val url = parseUrl(raw)
            ?: return PolicyVerdict.Denied("err.bad_scheme", Severity.HIGH)

        if (!domainPolicy.checkScheme(url)) {
            return PolicyVerdict.Denied("err.insecure_scheme", Severity.HIGH)
        }
        val host = url.host
        if (host.isBlank()) return PolicyVerdict.Denied("err.bad_host", Severity.HIGH)

        domainPolicy.blacklistHit(host)?.let {
            return PolicyVerdict.Denied("err.domain_blacklisted", Severity.HIGH)
        }
        // 凭据型 query：不拦（拦截由域名策略负责），但升级为必须确认
        if (SensitiveActionPolicy.carriesCredentialInUrl(url)) {
            return PolicyVerdict.Denied("err.credential_in_url", Severity.CRITICAL)
        }

        if (domainPolicy.isAllowlisted(host)) return PolicyVerdict.Allow

        val startHost = ctx.task.startUrl?.host
        val currentHost = ctx.currentUrl?.host
        if (!domainPolicy.isInSessionScope(host, ctx.task.allowHosts, currentHost, startHost)) {
            return PolicyVerdict.RequiresConfirm("confirm.cross_domain", listOf(host))
        }
        return PolicyVerdict.Allow
    }

    // ── 点击：语义分级是这里的全部难点
    private suspend fun checkClick(action: StepAction.Click, ctx: ActionContext): PolicyVerdict {
        val el = findElement(ctx, action.id)
            ?: return PolicyVerdict.Denied("err.element_not_found", Severity.LOW)

        SensitiveActionPolicy.forbiddenSemanticOf(el)?.let {
            return PolicyVerdict.Denied("err.forbidden_action", Severity.HIGH)
        }
        SensitiveActionPolicy.confirmSemanticOf(el)?.let { key ->
            val reasonKey = SensitiveActionPolicy.REQUIRE_CONFIRM[key] ?: "confirm.generic"
            return PolicyVerdict.RequiresConfirm(reasonKey, listOf(action.id.toString(), el.text.take(48)))
        }
        return PolicyVerdict.Allow
    }

    private suspend fun checkSelect(action: StepAction.Select, ctx: ActionContext): PolicyVerdict {
        if (findElement(ctx, action.id) == null) {
            return PolicyVerdict.Denied("err.element_not_found", Severity.LOW)
        }
        return PolicyVerdict.Allow
    }

    // ── 输入：T6 凭据泄露的直接现场
    private suspend fun checkType(action: StepAction.Type, ctx: ActionContext): PolicyVerdict {
        val el = findElement(ctx, action.id)
            ?: return PolicyVerdict.Denied("err.element_not_found", Severity.LOW)

        if (SensitiveActionPolicy.looksLikeCredential(el)) {
            return PolicyVerdict.Denied("err.credential_field", Severity.CRITICAL)
        }
        if (action.text.length > MAX_INPUT_LEN) {
            return PolicyVerdict.Denied("err.input_too_long", Severity.LOW)
        }
        // 凭据型文本即便输到「看起来普通」的框里也不允许（可能是注入诱导的搬运）
        if (com.ake.core.common.Redactor.containsCredential(action.text)) {
            return PolicyVerdict.Denied("err.credential_text", Severity.CRITICAL)
        }
        if (action.submit && SensitiveActionPolicy.formLooksFinancial(ctx.currentDom)) {
            return PolicyVerdict.RequiresConfirm("confirm.submit_financial", listOf(el.text.take(48)))
        }
        if (action.submit) {
            return PolicyVerdict.RequiresConfirm("confirm.submit_form", listOf(el.text.take(48)))
        }
        return PolicyVerdict.Allow
    }

    private suspend fun checkExtract(action: StepAction.Extract): PolicyVerdict {
        if (action.limit > MAX_RECORDS) return PolicyVerdict.Denied("err.limit_too_large", Severity.LOW)
        return PolicyVerdict.Allow
    }

    // ── 外部工具：§8.5「首次调用某 MCP 工具必确认」+ 逐工具 Always-Allow
    private suspend fun checkTool(action: StepAction.CallTool, ctx: ActionContext): PolicyVerdict {
        val id = action.toolId
        val declared = toolRisk(id)
        if (declared == RiskClass.FORBIDDEN) {
            return PolicyVerdict.Denied("err.forbidden_action", Severity.HIGH)
        }
        val store = toolPermissions
        if (store == null) {
            // 无授权存储 ⇒ 保守：仍然要求确认（宁可多问一次）
            return PolicyVerdict.RequiresConfirm("confirm.mcp_first_use", listOf(id.value))
        }
        if (!runCatching { store.isEnabled(id) }.getOrDefault(false)) {
            return PolicyVerdict.Denied("err.tool_disabled", Severity.MEDIUM)
        }
        if (runCatching { store.isAlwaysAllow(id) }.getOrDefault(false)) return PolicyVerdict.Allow
        return PolicyVerdict.RequiresConfirm("confirm.mcp_first_use", listOf(id.value))
    }

    // ──────────────────────────────────────────── 辅助

    private fun findElement(ctx: ActionContext, id: Int) =
        ctx.currentDom?.candidates?.firstOrNull { it.id == id }

    /** 只接受 http/https。`javascript:` / `data:` / `file:` 一律拒（§8.6 URL 合法性）。 */
    private fun parseUrl(raw: String): Url? {
        val v = raw.trim()
        if (!v.startsWith("http://", true) && !v.startsWith("https://", true)) return null
        // 含控制字符或用 `@` 伪装 host（http://evil.com@bank.com 的真实解析是 evil.com）
        if (v.any { it.isISOControl() || it.isWhitespace() }) return null
        val authority = v.substringAfter("://").substringBefore('/')
            .substringBefore('?').substringBefore('#')
        if (authority.count { it == '@' } > 1) return null
        if (authority.substringAfterLast('@').isBlank()) return null
        return Url(v)
    }

    // ──────────────────────────────────────────── 审计

    private fun record(action: StepAction, ctx: ActionContext, verdict: PolicyVerdict) {
        val (decision, severity, reasonKey, args) = when (verdict) {
            is PolicyVerdict.Allow -> Quad(PolicyDecision.ALLOW, null, null, emptyList())
            is PolicyVerdict.RequiresConfirm ->
                Quad(PolicyDecision.REQUIRES_CONFIRM, Severity.MEDIUM, verdict.reasonKey, verdict.args)

            is PolicyVerdict.Denied ->
                Quad(PolicyDecision.DENIED, verdict.severity, verdict.reasonKey, emptyList())
        }

        val rec = PolicyDecisionRecord(
            id = Ulid.next(clock.nowMillis()),
            taskId = ctx.task.id,
            stepId = null,
            createdAt = clock.nowMillis(),
            decision = decision,
            severity = severity?.name,
            reasonKey = reasonKey,
            reasonArgs = args,
            actionKind = kindOf(action),
            targetHost = ctx.currentUrl?.host ?: ctx.task.startUrl?.host,
            riskClass = riskOf(action).name,
        )
        val ok = runCatching { sink.record(rec) }.getOrDefault(false)
        if (!ok) AkeLog.w(TAG, "policy decision dropped (queue full): ${rec.decision} ${rec.reasonKey}")
    }

    private fun riskOf(action: StepAction): RiskClass = SensitiveActionPolicy.classify(action)

    private fun kindOf(action: StepAction): String = when (action) {
        is StepAction.Click -> "CLICK"
        is StepAction.Type -> "TYPE"
        is StepAction.Select -> "SELECT"
        is StepAction.Scroll -> "SCROLL"
        is StepAction.Navigate -> "NAVIGATE"
        is StepAction.Extract -> "EXTRACT"
        is StepAction.CallTool -> "CALL_TOOL"
        is StepAction.AskHuman -> "ASK_HUMAN"
        is StepAction.Finish -> "FINISH"
    }

    /** 轻量四元组：避免为一次映射引入额外类型。 */
    private data class Quad(
        val decision: String,
        val severity: Severity?,
        val reasonKey: String?,
        val args: List<String>,
    )

    companion object {
        const val TAG: String = "PolicyEngine"

        /** §8.6 / planner 的 MAX_INPUT_LEN：512。 */
        const val MAX_INPUT_LEN: Int = 512

        const val MAX_RECORDS: Int = 100
    }
}
