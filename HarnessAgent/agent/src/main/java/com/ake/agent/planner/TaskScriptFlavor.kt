package com.ake.agent.planner

import com.ake.core.contract.DomSignal
import com.ake.core.contract.PocketDom
import com.ake.core.contract.SuccessCriteria
import com.ake.core.contract.TaskSpec

/**
 * TaskScriptFlavor —— 单步 ReAct 的「全局目标」补偿机制（架构 §3.1）。
 *
 * 为什么需要它：纯单步 ReAct 容易在第 6 步之后丢失全局目标。但我们**不做多步规划**
 * （生成一个 5 步计划要 ~400 token decode ≈ GPU 7.7 秒，抵得上 2–3 个执行步，
 *   而且第一个动作执行后计划就过期了 —— 网页不是静态环境）。
 *
 * 折中方案：flavor 由**确定性规则**判定（关键词 + DOM 特征），**不是模型分析**；
 * 每种 flavor 对应 ①一段固定提示片段 ②一个固定的可执行 [SuccessCriteria]。
 * 「取 N 条数据」这类结构性流程用代码写死，让模型只填参数 —— 这才是端侧的正解。
 */
enum class TaskScriptFlavor {
    SEARCH_LIST,
    FORM_FILL,
    DETAIL_READ,
    NAVIGATE_ONLY,
    GENERIC,
    ;

    companion object {

        /**
         * 确定性判定。规则顺序 = 优先级；命中即返回。
         * 关键词中英双语（端侧 Gemma 4 E2B 的中文理解可用，但用户表述可能混用）。
         */
        fun detect(goal: String, dom: PocketDom?): TaskScriptFlavor {
            val g = goal.lowercase()
            val signals = dom?.signals ?: emptySet()

            val wantsData = g.containsAny(
                "整理", "汇总", "列出", "提取", "导出", "前三条", "前三条", "前 3", "top", "list",
                "extract", "collect", "summar", "几条", "多少条", "结果条数",
            )
            val isListPage = signals.contains(DomSignal.PAGINATION) ||
                signals.contains(DomSignal.RESULTS_COUNT) ||
                (dom?.records?.rows?.size ?: 0) >= 2

            if (wantsData && isListPage) return SEARCH_LIST
            if (wantsData) return SEARCH_LIST // 目标就是取数，页面还没到 ⇒ 仍然是搜索列表型

            if (g.containsAny("填", "输入", "填写", "不提交", "别提交", "fill", "input", "type into", "form")) {
                return FORM_FILL
            }
            if (g.containsAny("打开", "进入", "导航", "跳转到", "open", "navigate", "go to")) {
                return if (g.containsAny("看看", "读", "详情", "内容", "read", "detail")) DETAIL_READ else NAVIGATE_ONLY
            }
            return GENERIC
        }

        /**
         * 每种 flavor 的**可执行谓词**（§3.5 Layer 2）。
         * 注意：这是代码判定，不是自然语言 —— 模型说「做完了」必须同时过这一关。
         */
        fun criteriaOf(flavor: TaskScriptFlavor, goal: String): SuccessCriteria = when (flavor) {
            SEARCH_LIST -> {
                val n = extractTopN(goal) ?: 3
                SuccessCriteria(
                    minRecords = n,
                    requiredFields = extractFields(goal),
                    requireNoErrorText = true,
                )
            }
            FORM_FILL -> SuccessCriteria(
                minRecords = 0,
                requiredFields = emptySet(),
                requireDomSignal = setOf(DomSignal.HAS_FORM),
                requireNoErrorText = true,
            )
            DETAIL_READ -> SuccessCriteria(minRecords = 0, requireNoErrorText = true)
            NAVIGATE_ONLY -> SuccessCriteria(minRecords = 0, requireNoErrorText = true)
            GENERIC -> SuccessCriteria(minRecords = 0, requireNoErrorText = true)
        }

        /** 「前三条 / top 5 / 前 10 」→ N；没有显式数字时按 3 */
        internal fun extractTopN(goal: String): Int? {
            val cn = Regex("前\\s*([0-9一二三四五六七八九十]+)\\s*(条|个|项|名)").find(goal)
            if (cn != null) return cn.groupValues[1].cnToIntOrNull()
            val en = Regex("(?i)top\\s*([0-9]{1,2})").find(goal)
            if (en != null) return en.groupValues[1].toIntOrNull()
            return null
        }

        /** 「商品名、价格、店铺名」→ 字段集合；无显式字段时给默认三件套 */
        internal fun extractFields(goal: String): Set<String> {
            val out = linkedSetOf<String>()
            if (goal.containsAny("商品名", "标题", "名称", "name", "title")) out += "name"
            if (goal.containsAny("价格", "价钱", "多少钱", "price")) out += "price"
            if (goal.containsAny("店铺", "商家", "卖家", "shop", "store", "seller")) out += "shop"
            return if (out.isEmpty()) setOf("name", "price", "shop") else out
        }

        private fun String.containsAny(vararg ks: String) = ks.any { this.contains(it) }

        private fun String.cnToIntOrNull(): Int? = when (this) {
            "一" -> 1; "二" -> 2; "两" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
            "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
            else -> this.toIntOrNull()
        }
    }
}

/**
 * flavor → 固定提示片段（架构：写在 planner 的字符串常量里，便于 A/B）。
 * 原则：**只讲这类任务的标准套路，不讲具体步骤** —— 讲具体步骤就退化成多步规划了。
 */
object FlavorPrompts {
    fun of(f: TaskScriptFlavor): String = when (f) {
        TaskScriptFlavor.SEARCH_LIST ->
            "这是「列表取数」型任务。标准套路：先确认当前已是搜索结果页（看 SIGNALS 是否有 RESULTS_COUNT）；" +
                "需要排序就点排序控件并等待页面稳定；然后用 extract_records 一次性拿到干净的结构化行，" +
                "不要逐个点开商品详情去读 —— 那样既慢又容易丢字段。字段齐了再调用 finish。"
        TaskScriptFlavor.FORM_FILL ->
            "这是「表单填写」型任务。标准套路：逐个定位输入框并 type；**除非任务明确要求提交，" +
                "绝对不要点提交按钮**（用户说「不提交」时，填完就 finish）。遇到密码/银行卡类输入框立即停止并 ask_human。"
        TaskScriptFlavor.DETAIL_READ ->
            "这是「读详情页」型任务。标准套路：先确认 URL 与标题确为目标页，再从正文区域抽取所需字段；" +
                "不要被侧边推荐位的内容干扰。"
        TaskScriptFlavor.NAVIGATE_ONLY ->
            "这是「导航」型任务。到达目标页且页面状态为 READY 即可 finish；不要做多余动作。"
        TaskScriptFlavor.GENERIC ->
            "按目标逐步推进；每步只做一个动作，做完看观察结果再决定下一步。"
    }
}
