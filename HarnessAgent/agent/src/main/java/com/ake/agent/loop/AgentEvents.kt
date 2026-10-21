package com.ake.agent.loop

/**
 * ★★ 迁移垫片：`com.ake.agent.loop.TaskEvent` → `com.ake.core.ports.TaskEvent`
 *
 * 本模块已不再持有 `TaskEvent` 的定义。新坐标在 `:core:ports`
 * （见 `core/ports/Runtime.kt`），本文件只留一行转发。
 *
 * ## 为什么这里是 alias 而不是删除
 *
 * `:ui` 里有 12 处 `import com.ake.agent.loop.{TaskEvent, TerminalReason, SuspendReason,
 * RunOptions, UsageTotals}`，而摘掉 UI 的 Gradle 边属于「三步走」里的**第 3 步**
 * （`core/ports/Runtime.kt` 顶部注释写明顺序不可颠倒）。直接删会让 UI 编译失败。
 *
 * `typealias` 不是第二份定义 —— 它是同一个类型的另一个名字。UI 按旧坐标写的穷举
 * `when`，与将来按新坐标写的 `when`，匹配的是同一个 sealed interface，
 * 不会出现「两套 TaskEvent」。
 *
 * ## ⚠ Kotlin 限制（真编译实测，非推测）
 *
 * typealias 不代理嵌套 classifier。在本包里写 `TaskEvent.Terminal` 会报
 * `Unresolved reference 'Terminal'`。因此本模块内需要用嵌套分支的文件一律改用
 * `import com.ake.core.ports.TaskEvent` —— 显式导入优先于同包的 alias。
 *
 * ## TODO(migration · step 3)：`:ui` 改完 import 后删除本文件
 *
 *  1. `:ui` 把上述 import 改成 `import com.ake.core.ports.*`；
 *  2. 摘掉 `ui/build.gradle.kts` 里的 `implementation(project(":agent"))`；
 *  3. 回来删除本文件。
 */
public typealias TaskEvent = com.ake.core.ports.TaskEvent
