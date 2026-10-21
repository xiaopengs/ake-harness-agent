package com.ake.core.ports

import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.common.Url
import com.ake.core.contract.PocketDom
import com.ake.core.contract.StepAction
import com.ake.core.contract.TaskSpec

/**
 * 安全护栏 —— **所有动作执行的唯一合法闸口**（架构 §8.4）。
 *
 * 形态严格按 `agent/EXPECTED_CORE_API.md` §2 原文。
 * 四条「架构只给了名字没给形态」的签名，下面就是霍承钧实际调用的形态，已照其实现。
 */

public interface PolicyPort {
    public suspend fun authorize(action: StepAction, ctx: ActionContext): Outcome<PolicyVerdict>

    /** 网页文本进入上下文前的清洗与可疑标记。同步：它必须在每次拼接 prompt 的路上被无条件调用。 */
    public fun sanitize(text: String, source: ContentSource): Sanitized
}

public sealed interface PolicyVerdict {
    public data object Allow : PolicyVerdict
    public data class RequiresConfirm(val reasonKey: String, val args: List<String>) : PolicyVerdict
    public data class Denied(val reasonKey: String, val severity: Severity) : PolicyVerdict
}

/**
 * 内容来源。**每个来源有自己的长度上限** —— 这条上限是为了对抗「用超长文本把 context 挤爆」
 * 这类攻击：模型在这种情况下会「忘掉」system prompt 里的安全宪章。
 */
public enum class ContentSource(public val maxLen: Int) {
    PAGE(2048),
    TOOL_RETURN(4096),
    USER(1024),
    MODEL(4096),
}

public data class Sanitized(
    val text: String,
    val redacted: Boolean,
    val flagged: Boolean = false,
    val tripwires: Int = 0,
)

public data class ActionContext(
    val currentUrl: Url?,
    val task: TaskSpec,
    val currentDom: PocketDom?,
    val stepIndex: Int,
)

/**
 * 人机交互点（§3.2 `humanGate.resolve`）。
 *
 * **四个分支里 `Abort` 必须无条件可用**：用户按停止时，不该出现「先问一圈再停」的中间态。
 */
public interface HumanGate {
    public suspend fun resolve(verdict: PolicyVerdict, action: StepAction): Resolved
}

public sealed interface Resolved {
    public data object Proceed : Resolved
    public data class Reject(val reason: String) : Resolved
    public data class Amend(val amended: StepAction) : Resolved
    public data object Abort : Resolved
}

/**
 * 用户中止信号。**刻意不要求传 verdict** —— 「查一下有没有中止」必须能在任何时刻、
 * 任何造价都做得到，包括模型正在 decode 的时候。
 */
public interface AbortSignal {
    public fun isAborted(): Boolean
}

/** 无需交互场景的默认实现（例如纯后台任务）。 */
public object NoAbortSignal : AbortSignal {
    override fun isAborted(): Boolean = false
}
