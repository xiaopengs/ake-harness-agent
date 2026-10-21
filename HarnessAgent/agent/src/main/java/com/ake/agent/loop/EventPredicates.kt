package com.ake.agent.loop

import com.ake.core.ports.TaskEvent

/**
 * 事件流的两个谓词。它们在 `TaskEvent` 下移到 `:core:ports` 之前就在本模块，
 * 语义未变，仅 receiver 换成了 core 那一份。
 *
 * ⚠ **归属待主理人裁决**：这两个是纯展示谓词，唯一调用方是 UI（通知栏去重、
 * `collect` 收尾）。第 3 步摘掉 `:ui → :agent` 的 Gradle 边之后 UI 就拿不到它们了，
 * 届时有两条路：随 `TaskEvent` 一起搬进 `core:ports`（需改 `core/`，不在本次边界），
 * 或由 UI 侧用同样的 `when` 就地重写。本轮先保持在 `:agent`，行为零变化。
 */

/**
 * 便于 UI 折叠事件：只有这几类值得在通知栏出现。
 *
 * [TaskEvent.TokenDelta] 刻意不在列 —— 它是每步一条（未来每 token 一条）的高频事件，
 * 拿它去刷通知栏会把通知栏变成噪音源。
 */
public fun TaskEvent.isUserVisible(): Boolean = when (this) {
    is TaskEvent.Started,
    is TaskEvent.ConfirmRequested,
    is TaskEvent.Paused,
    is TaskEvent.Suspended,
    is TaskEvent.Terminal,
    is TaskEvent.GuardTriggered,
    -> true

    else -> false
}

/**
 * ★ 流终点判定。UI 的 `collect` 收尾逻辑（停计时器、刷新可恢复列表）挂在它上面。
 *
 * 与「`status` 是不是终态」是**两个问题**：`Suspended` 也是流的终点，但它不代表任务结束。
 */
public fun TaskEvent.isEndOfStream(): Boolean =
    this is TaskEvent.Terminal || this is TaskEvent.Suspended
