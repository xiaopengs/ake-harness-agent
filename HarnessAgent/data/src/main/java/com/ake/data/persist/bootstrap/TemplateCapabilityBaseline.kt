package com.ake.data.persist.bootstrap

import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef

/**
 * ★★ 每个内置模板「语义上必需」的能力 —— 模板 bootstrap 的**授权基线**。
 *
 * ## 这个文件修的是什么问题
 *
 * `agent_capability_grant` 为空 ⇒ `grantsOf()` 返回空 map ⇒ §5.4 的判据
 * `tool_registry.enabled AND agent_capability_grant[agentId][ref]` 恒为 false
 * ⇒ 人格从模板创建出来是**哑的**：聊天只回文字、永不派生任务。
 * 用户看到的是「功能没接上」，而不是「你没勾权限」—— 这是一次致命体验缺口，
 * 而它在数据层表现为「一张合法的空表」。
 *
 * ## 三层各自的职责（缺一层就会留下一条哑 Agent 的路径）
 *
 * | 层 | 落点 | 负责的时刻 |
 * |---|---|---|
 * | ① **声明**（本文件） | `BASELINE` 表 | 编译期：哪个模板没有它就不成立 |
 * | ② **新建** | `AgentProfileStoreImpl.upsert()` 合并 baseline 后写 grant 行 | 从模板创建人格 |
 * | ③ **存量** | `TemplateBootstrap.bootstrap()` 回填 | 本改动之前就已经建好的人格 |
 *
 * 三层都只是**写 `agent_capability_grant` 这一张表**，谁都没有通往
 * `tool_registry.enabled` 的写口 —— §5.4「Agent 级只能做减法」不受影响（见
 * `CapabilityGrantStoreImpl` 类注释）。所以这里的操作是**开通一个模板的核心技能**，
 * 不是**替用户开放一项全局能力**。
 *
 * ## ★ 为什么这不算「越权」（三条结构性约束，写在 `init` 里）
 *
 *  1. **基线只能是模板自己声明过的能力**：`ref ∈ template.suggestedCapabilities`。
 *     模板没写过的能力**进不了**这张表，`init` 直接 fail ——
 *     而不是靠「写的时候小心一点」。
 *  2. **只允许 `CapabilityKind.BUILTIN`**：MCP 工具 / 插件一律不许进基线。
 *     理由见 §5.4：新增一项对外部世界的能力**必须**发生在通用设置页（那里有风险徽标），
 *     否则「装一个角色」就成了「交出文件系统写权限」的伪装。
 *  3. **基线数量 ≤ [MAX_PER_TEMPLATE]**：`maxEnabledSkills` 默认 6，
 *     基线吃满配额就等于把用户的自选名额挤没了 —— 那是另一种形式的越权。
 *
 * ## ★ 市场模板为什么拿不到基线
 *
 * `requiredFor()` 对任何不在 `BASELINE` 里的 id 返回空表，而 `BASELINE` 的键被
 * `init` 钉死为**内置**模板 id。§6.5 ③「能力的授予永远不由 bundle 决定」因此原样成立：
 * 外部安装来的模板想要什么，仍然只能由用户在创建页逐条勾。
 * ★ 这条边界不要为了让某个市场模板「装完就能用」而放宽 —— 那是本轮最重要的一条护栏。
 */
public object TemplateCapabilityBaseline {

    public const val TAG: String = "TemplateBaseline"

    /** 基线数量上限：低于 `maxEnabledSkills`（默认 6），保证用户仍有自选空间。 */
    public const val MAX_PER_TEMPLATE: Int = 2

    /**
     * 内置模板 → 语义上必需的能力。
     *
     * ## 为什么五个模板都是 `builtin.browser_task`
     *
     * 因为**当前只有一个内置能力**（`TemplateAssets.BROWSER_TASK` 是唯一的 `builtin.*` 常量），
     * 而这五个人格的工作流都**以联网查证为前提**：
     * 视频导演要查平台调性与热点、编程专家要查文档与 API、投资顾问要查行情与数据口径、
     * 运营要查选题竞争度、法务要查法条原文与文号。少了它，五个人格的 charter 里
     * 那条「不得编造 / 没有把握就说没有把握」的纪律**无法被遵守** —— 它们只能瞎猜。
     *
     * ★ 将来新增内置能力（例如 `builtin.file_read`）时，**只有真正离不开它的模板**才写进来。
     *   这张表是唯一改动点，②③ 两层自动跟随。
     */
    private val BASELINE: Map<String, List<CapabilityRef>> = mapOf(
        TemplateAssets.VIDEO_DIRECTOR.templateId to listOf(browser()),
        TemplateAssets.CODE_EXPERT.templateId to listOf(browser()),
        TemplateAssets.INVESTMENT_ADVISOR.templateId to listOf(browser()),
        TemplateAssets.SOCIAL_MEDIA_OPERATOR.templateId to listOf(browser()),
        TemplateAssets.LEGAL_COUNSEL.templateId to listOf(browser()),
    )

    /** 全部内置模板的基线（只读，供测试与排障断言）。 */
    public val all: Map<String, List<CapabilityRef>> get() = BASELINE

    /**
     * 某个模板的基线。**非内置模板恒为空** —— 这就是 §6.5 ③ 不被破坏的地方。
     */
    public fun requiredFor(templateId: String?): List<CapabilityRef> =
        if (templateId == null) emptyList() else BASELINE[templateId] ?: emptyList()

    /**
     * 模板创建人格时的**最终授权清单** = 基线 ∪ 用户自选。
     *
     * 顺序上基线在前：那是「模板给的核心」，用户的勾选接在后面，UI 展示与日志都因此可读。
     * 去重是必须的（用户可能恰好也勾了核心技能），否则 `grantAll` 会写重复行
     * —— 复合主键会因 REPLACE 而**把 `granted_at` 重置成现在**，那是审计信息的丢失。
     */
    public fun merge(templateId: String?, requested: List<CapabilityRef>): List<CapabilityRef> {
        val baseline = requiredFor(templateId)
        if (baseline.isEmpty()) return requested.distinct()
        return (baseline + requested).distinct()
    }

    private fun browser(): CapabilityRef = CapabilityRef.builtin(TemplateAssets.BROWSER_TASK)

    init {
        // ── ① 键必须是内置模板 id：手滑写错一个字符，基线就会永远不生效（静默失败）
        val builtins = TemplateAssets.builtins().associateBy { it.templateId }
        val unknown = BASELINE.keys - builtins.keys
        require(unknown.isEmpty()) {
            "TemplateCapabilityBaseline 里有非内置模板 id：$unknown（可用：${builtins.keys}）"
        }

        for ((templateId, refs) in BASELINE) {
            val declared = builtins.getValue(templateId).suggestedCapabilities
            // ── ② 不许越权：基线必须是模板自己声明过的能力
            val undeclared = refs - declared.toSet()
            require(undeclared.isEmpty()) {
                "模板 $templateId 的基线含未声明的能力 ${undeclared.map { it.encode() }}" +
                    "（模板只声明了 ${declared.map { it.encode() }}）"
            }
            // ── ③ 只允许 BUILTIN：MCP / 插件必须由用户在设置页开通
            val notBuiltin = refs.filter { it.kind != CapabilityKind.BUILTIN }
            require(notBuiltin.isEmpty()) {
                "模板 $templateId 的基线含非 BUILTIN 能力 ${notBuiltin.map { it.encode() }}" +
                    "：对外部世界的能力只能由用户在通用设置页开通（§5.4）"
            }
            // ── ④ 数量：基线不许吃满 maxEnabledSkills
            require(refs.size <= MAX_PER_TEMPLATE) {
                "模板 $templateId 的基线有 ${refs.size} 项，超过上限 $MAX_PER_TEMPLATE"
            }
            // ── ⑤ 空基线 = 装完还是哑的，那正是本文件要消灭的东西
            require(refs.isNotEmpty()) { "模板 $templateId 的基线为空：这个模板装出来是哑的" }
        }
    }
}
