package com.ake.core.contract

/**
 * 动作的两级表示。
 *
 * `StepAction` 是**模型输出的形状**（由 `:agent:planner` 的 StepActionParser 解析出来）；
 * `BrowserAction` 是**执行器收到的形状**。两者之间必须经过 `toToolCall(dom)` 翻译
 * （在 `:agent:planner`，架构 §3.2 第 6 环）。
 *
 * **为什么要两级**：模型输出里的 `id` 是每轮快照的临时编号，**不能被信任**。
 * 翻译时会强制查本轮 candidates：`id → (selector, xpath, stableKey)`。
 * 查不到就直接失败走定位失败路径。这一步是闸 5（参数语义校验）的一部分。
 */

/**
 * 模型每一步的输出。刻意做成**扁平单动作**：`act` + 少量字段。
 *
 * 端侧 decode 只有 ~52 tok/s，schema 越深、字段越多 ⇒ 输出 token 越多、格式错误率越高。
 * 这不是偏好，是被 decode 速度逼出来的形状。
 */
public sealed interface StepAction {

    public val tool: String

    /** 压缩成 trace 一行时的参数区（确定性压缩，架构 §4.5）。必须短：额目标是 15–25 token/步。 */
    public fun compactArgs(): String

    public data class Click(val id: Int) : StepAction {
        override val tool: String get() = "click"
        override fun compactArgs(): String = "[$id]"
    }

    public data class Type(val id: Int, val text: String, val submit: Boolean = false) : StepAction {
        override val tool: String get() = "type"
        override fun compactArgs(): String =
            "[$id],\"${text.take(24)}\"${if (submit) ",submit" else ""}"
    }

    public data class Select(val id: Int, val value: String) : StepAction {
        override val tool: String get() = "select"
        override fun compactArgs(): String = "[$id],\"${value.take(24)}\""
    }

    public data class Scroll(val dir: String, val px: Int) : StepAction {
        override val tool: String get() = "scroll"
        override fun compactArgs(): String = "$dir,$px"
    }

    public data class Navigate(val url: String) : StepAction {
        override val tool: String get() = "goto"
        override fun compactArgs(): String = url.take(40)
    }

    public data class Extract(val selectorHint: String, val fields: List<String>, val limit: Int) : StepAction {
        override val tool: String get() = "extract"
        override fun compactArgs(): String = "$selectorHint,$limit"
    }

    public data class CallTool(val toolId: ToolId, val args: Map<String, String>) : StepAction {
        override val tool: String get() = "tool"
        override fun compactArgs(): String = toolId.value
    }

    public data class AskHuman(val question: String) : StepAction {
        override val tool: String get() = "ask"
        override fun compactArgs(): String = question.take(40)
    }

    public data class Finish(val answer: String, val evidenceRefs: List<String>) : StepAction {
        override val tool: String get() = "finish"
        override fun compactArgs(): String = answer.take(32)
    }
}

/**
 * 执行器收到的动作。**携带 selector / xpath / stableKey 三重定位信息**，
 * 让浏览器在执行前还能二次校验「这个元素是不是还是那个元素」（§5.6 / R3）。
 */
public sealed interface BrowserAction {

    public data class Click(val selector: String, val xpath: String, val stableKey: String) : BrowserAction

    public data class Type(
        val selector: String,
        val xpath: String,
        val stableKey: String,
        val text: String,
        val submit: Boolean,
    ) : BrowserAction

    public data class Select(
        val selector: String,
        val xpath: String,
        val stableKey: String,
        val value: String,
    ) : BrowserAction

    public data class Scroll(val dir: String, val px: Int) : BrowserAction

    public data class Navigate(val url: String) : BrowserAction

    public data class ExtractRecords(
        val selectorHint: String,
        val fields: List<String>,
        val limit: Int,
    ) : BrowserAction
}
