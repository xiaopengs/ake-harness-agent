package com.ake.agent.planner

import com.ake.core.contract.BrowserAction
import com.ake.core.contract.PocketDom
import com.ake.core.contract.StepAction
import com.ake.core.contract.ToolCall
import com.ake.core.contract.UiElement

/**
 * `StepAction` → `ToolCall`（架构 §3.2 第 6 环：`tools.invoke(action.toToolCall(dom))`）。
 *
 * 关键：**模型输出的 `[n]` 在这里被翻译成 selector / stableKey，而不是被信任。**
 * 翻译时要做三重核对（找不到就返回 null，由 Loop 走定位失败路径）：
 *   ① 编号必须存在于本轮候选集合（§8.6 元素存在性）
 *   ② 优先用 [UiElement.selector]，其次 xpath，最后才退化到 stableKey
 *   ③ 把 stableKey 一并放进 ToolCall，供执行器做「元素是否还是那个元素」的二次确认（§5.6/R3）
 */
fun StepAction.toToolCall(dom: PocketDom?): ToolCall? = when (this) {
    is StepAction.Click -> dom.find(id)?.let { e ->
        ToolCall.Browser(BrowserAction.Click(e.selector, e.xpath, e.stableKey))
    }
    is StepAction.Type -> dom.find(id)?.let { e ->
        ToolCall.Browser(BrowserAction.Type(e.selector, e.xpath, e.stableKey, text, submit))
    }
    is StepAction.Select -> dom.find(id)?.let { e ->
        ToolCall.Browser(BrowserAction.Select(e.selector, e.xpath, e.stableKey, value))
    }
    is StepAction.Scroll -> ToolCall.Browser(BrowserAction.Scroll(dir, px))
    is StepAction.Navigate -> ToolCall.Browser(BrowserAction.Navigate(url))
    is StepAction.Extract -> ToolCall.Browser(
        BrowserAction.ExtractRecords(selectorHint, fields, limit),
    )
    is StepAction.CallTool -> ToolCall.External(toolId, args)
    // ask_human / finish 由 Loop 直接处理，不进 ToolRegistry
    is StepAction.AskHuman -> null
    is StepAction.Finish -> null
}

/** 模型引用的编号 → 本轮候选元素。找不到返回 null（触发定位失败计数） */
fun PocketDom?.find(id: Int): UiElement? = this?.candidates?.firstOrNull { it.id == id }

/**
 * stableKey 重映射（§5.6 协议 1/3）。
 *
 * @param previous 上一轮的 (id → stableKey)
 * @return 需要告知模型的 WARN 列表；为空表示编号空间稳定，无需打扰模型
 */
fun remapWarnings(previous: Map<Int, String>, current: List<UiElement>): List<String> {
    if (previous.isEmpty()) return emptyList()
    val byKey = current.associateBy { it.stableKey }
    val out = ArrayList<String>(2)
    for ((oldId, key) in previous) {
        val now = byKey[key]
        when {
            now == null -> out += "上一步引用的 [$oldId] 在当前页面已不存在（可能已被重绘或移除）。"
            now.id != oldId -> out += "页面已重绘：原 [$oldId]（$key）现在是 [${now.id}]。不要沿用旧编号，请基于本轮 <candidates> 重新选择。"
        }
        if (out.size >= 3) break      // 最多 3 条，再多就吃爆 RECENT_TRACE 预算
    }
    return out
}
