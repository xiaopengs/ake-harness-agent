package com.ake.agent.loop

import com.ake.agent.fixtures.FakeHumanGate
import com.ake.agent.fixtures.FakeModelPort
import com.ake.agent.fixtures.LoopHarness
import com.ake.agent.fixtures.checkpoint
import com.ake.agent.fixtures.execResult
import com.ake.agent.fixtures.testDom
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.contract.ExecutionResult
import com.ake.core.contract.PageChangeKind
import com.ake.core.contract.PageState
import com.ake.core.contract.StepAction
import com.ake.core.contract.SuccessCriteria
import com.ake.core.contract.TaskStatus
import com.ake.core.contract.ToolResult
import com.ake.core.ports.BatteryState
import com.ake.core.ports.PolicyVerdict
import com.ake.core.ports.Resolved
import com.ake.core.ports.TaskEvent
import com.ake.core.ports.TerminalReason
import com.ake.core.ports.ThermalState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `AgentLoop` 的回归测试（2026-10）。
 *
 * ## 它守的是哪条性质
 *
 * **主循环的终止判定必须是「结构性谓词 + 模型显式 finish + 终止保护熔断」三重判定的合取**，
 * 模型的意见只占一票（架构 §3.5）。一次跑飞的任务在端侧是 3 分钟的白等，
 * 而端侧最贵的资源不是算力，是用户的耐心。
 *
 * 所以下面每一条用例都指向一个具体的**跑飞形态**：
 *
 *  - 循环（连续三次同一动作）、振荡（URL 环）、无进展（连续四步页面没动）—— 三个判据各测一条；
 *  - 预算耗尽 / 墙钟超时 —— 断点恢复把累计带回来之后仍然要能被掐住；
 *  - 解析失败熔断（连续两次）；
 *  - 用户中止**无条件优先**，且必须发生在付推理费之前；
 *  - 热节流/低电量是**挂起**不是终止，流的终点是 `Suspended` 而不是伪 `Terminal`。
 *
 * ## 关于「流的终点唯一」
 *
 * `TaskEvent.Terminal` 与 `TaskEvent.Suspended` 互斥且穷尽 —— UI 用一个 `collect`
 * 就能表达「跑到结束」。这条性质一旦破坏（多一个终结语、或终结语不在最后），
 * UI 的收尾逻辑就会漏跑。`flowTerminatesExactlyOnce` 一条条场景守着它。
 *
 * ## 未覆盖（需真机）
 *
 *  - LiteRT-LM 真实 session 的 KV cache 复用与跨步时长；
 *  - WebView 真实 settle 检测与 `perceiveMs` 的实际耗时分布；
 *  - 端侧真实模型在长 prompt 下的输出退化（这里用的是脚本化输出）。
 */
class AgentLoopTest {

    private fun loop(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = block)

    private fun task() = com.ake.agent.fixtures.testTask()

    private suspend fun run(h: LoopHarness): List<TaskEvent> =
        h.loop.run(task(), h.opts()).toList()

    private fun List<TaskEvent>.terminal(): TaskEvent.Terminal =
        filterIsInstance<TaskEvent.Terminal>().single()

    // ──────────────────────────────────────────── ① 正常完成

    @Test
    fun `模型 finish 且 answer 可溯源时收敛为 COMPLETED`() = loop {
        val h = LoopHarness(outputs = { LoopHarness.FINISH_JSON })

        val events = run(h)

        // 流的最后一条是终结语，且只有一个
        assertThat(events.last()).isInstanceOf(TaskEvent.Terminal::class.java)
        assertThat(events.count { it.isEndOfStream() }).isEqualTo(1)

        val t = events.terminal()
        assertThat(t.reason).isEqualTo(TerminalReason.COMPLETED)
        assertThat(t.isSuccess).isTrue()
        assertThat(t.outcome).isNotNull()
        assertThat(t.outcome!!.answer).isEqualTo("199")

        // 落盘口径：SUCCEEDED + reasonKey，且 COMPLETED 不留断点
        assertThat(h.tasks.finishes).containsExactly("SUCCEEDED|terminal_completed")
        assertThat(h.checkpoints.cleared).containsExactly("task-test-0001")
        // 感知 → 规划 → 执行 → 验证 都发生了
        assertThat(events.filterIsInstance<TaskEvent.Observed>()).hasSize(1)
        assertThat(events.filterIsInstance<TaskEvent.Planned>()).hasSize(1)
        assertThat(h.observations.calls).isEqualTo(1)
    }

    @Test
    fun `finish 的 answer 溯源不通过时判为 FABRICATED_ANSWER`() = loop {
        // answer 里的数据片段（8888）在当轮证据里找不到 ⇒ Layer 1 判定编造
        val h = LoopHarness(outputs = { """{"act":"finish","answer":"8888","evidenceRefs":[]}""" })

        val t = run(h).terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.FABRICATED_ANSWER)
        assertThat(t.isSuccess).isFalse()
        // FABRICATED_ANSWER 保留断点供用户手动重试
        assertThat(h.checkpoints.saved).isNotEmpty()
        assertThat(h.checkpoints.cleared).isEmpty()
    }

    @Test
    fun `页面处于风控态时判为 INFEASIBLE 而不是硬试`() = loop {
        val h = LoopHarness(outputs = { LoopHarness.FINISH_JSON }).also {
            it.perception.result = { Outcome.Ok(testDom(state = PageState.CAPTCHA)) }
        }

        val t = run(h).terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.INFEASIBLE)
        assertThat(t.detail).contains("page_state=CAPTCHA")
        // 宪章第 6 条：不绕过风控 —— 一次动作都不该执行
        assertThat(h.browser.executed).isEqualTo(0)
    }

    // ──────────────────────────────────────────── ② 用户中止

    @Test
    fun `用户中止无条件优先且不付一次推理的钱`() = loop {
        val h = LoopHarness().also { it.abort = { true } }

        val events = run(h)
        val t = events.terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.USER_ABORT)
        assertThat(h.model.generateCalls).isEmpty()
        // 顺序：Started → RUNNING → ABORTED → Terminal
        assertThat(events.map { it::class.simpleName })
            .containsExactly("Started", "StatusChanged", "StatusChanged", "Terminal")
            .inOrder()
    }

    @Test
    fun `中止在感知退避期间也能生效`() = loop {
        var faults = 0
        val h = LoopHarness().also {
            it.perception.result = {
                faults++
                Outcome.Err(AppError(code = "PERCEPTION", reasonKey = "err_perception"))
            }
            it.abort = { faults >= 2 }
        }

        val events = run(h)

        // 只发出 1 条 PerceptionFault：第二次感知失败后，循环在 emit 之前先查到了中止信号
        // （`if (opts.abort.isAborted()) continue` 在 `emit(PerceptionFault)` 之前）——
        // 这正是「用户按停止时，不该出现先问一圈再停的中间态」的落点。
        assertThat(events.filterIsInstance<TaskEvent.PerceptionFault>()).hasSize(1)
        assertThat(events.terminal().reason).isEqualTo(TerminalReason.USER_ABORT)
        assertThat(h.model.generateCalls).isEmpty()
    }

    // ──────────────────────────────────────────── ③ 预算 / 墙钟

    @Test
    fun `断点恢复后 prefill 累计已超上界时收敛为 BUDGET_EXHAUSTED`() = loop {
        val h = LoopHarness()
        val events = h.loop.run(
            task(),
            h.opts(resumeFrom = checkpoint(stepSeq = 5, prefill = 200_000L)),
        ).toList()

        assertThat(events.terminal().reason).isEqualTo(TerminalReason.BUDGET_EXHAUSTED)
        // 先查熔断再动，别在无药可救时还付一次推理的钱
        assertThat(h.model.generateCalls).isEmpty()
    }

    @Test
    fun `墙钟超预算时收敛为 TIMEOUT`() = loop {
        val h = LoopHarness()
        val events = h.loop.run(
            task(),
            h.opts(resumeFrom = checkpoint(stepSeq = 5, elapsed = 300_000L)),
        ).toList()

        assertThat(events.terminal().reason).isEqualTo(TerminalReason.TIMEOUT)
        assertThat(h.model.generateCalls).isEmpty()
    }

    @Test
    fun `步数用尽时收敛为 BUDGET_EXHAUSTED`() = loop {
        val h = LoopHarness()
        val events = h.loop.run(
            task(),
            h.opts(resumeFrom = checkpoint(stepSeq = 25)),
        ).toList()

        assertThat(events.terminal().reason).isEqualTo(TerminalReason.BUDGET_EXHAUSTED)
    }

    // ──────────────────────────────────────────── ④ 循环三件套

    @Test
    fun `连续三次完全相同的动作收敛为 LOOP_DETECTED`() = loop {
        val h = LoopHarness(outputs = { """{"act":"scroll","dir":"down","px":800}""" })

        val t = run(h).terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.LOOP_DETECTED)
        // 第 4 次迭代在跑之前就被掐住 ⇒ 只推理了 3 次
        assertThat(h.model.generateCalls).hasSize(3)
        assertThat(h.browser.executed).isEqualTo(3)
        // LOOP_DETECTED 不留断点（架构：不保留一个明知跑飞的现场）
        assertThat(h.checkpoints.cleared).isNotEmpty()
    }

    @Test
    fun `连续四步页面无变化收敛为 NO_PROGRESS`() = loop {
        // 交替两种动作 ⇒ 避免先触发「完全重复」，单独验证无进展这一路
        val h = LoopHarness(
            outputs = { i -> if (i % 2 == 0) """{"act":"click","id":1}""" else """{"act":"type","id":3,"text":"手机"}""" },
        ).also {
            it.browser.exec = { Outcome.Ok(execResult(PageChangeKind.NO_CHANGE)) }
        }

        val t = run(h).terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.NO_PROGRESS)
        assertThat(h.model.generateCalls).hasSize(4)
        assertThat(h.browser.executed).isEqualTo(4)
    }

    @Test
    fun `URL 出现环时收敛为 OSCILLATION`() = loop {
        // A → B → A ⇒ recentUrls 里出现长度 ≥ 3 的环。
        // ★ 三个动作必须两两不同：`repeatsIdenticalAction` 排在 `hasUrlCycle` **之前**，
        //   动作相同的话会先被判成 LOOP_DETECTED，振荡这一路就永远测不到。
        val urls = listOf(
            "https://shop.example.com/a",
            "https://shop.example.com/b",
            "https://shop.example.com/a",
        )
        val actions = listOf(
            """{"act":"click","id":1}""",
            """{"act":"scroll","dir":"down"}""",
            """{"act":"type","id":3,"text":"手机"}""",
        )
        var step = 0
        val h = LoopHarness(outputs = { actions[it % actions.size] }).also {
            it.perception.result = {
                val u = urls[step % urls.size]
                step++
                Outcome.Ok(testDom().copy(url = u, host = "shop.example.com"))
            }
            it.browser.exec = { Outcome.Ok(execResult(PageChangeKind.URL_MOVED)) }
        }

        val t = run(h).terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.OSCILLATION)
        assertThat(h.model.generateCalls).hasSize(3)
    }

    // ──────────────────────────────────────────── ⑤ 解析失败熔断

    @Test
    fun `连续两次输出不可解析收敛为 REPAIR_FAILED`() = loop {
        val h = LoopHarness(outputs = { "这不是 JSON，只是一段自然语言" })

        val events = run(h)
        val t = events.terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.REPAIR_FAILED)
        // 熔断阈值是 2 ⇒ 只推理 2 次
        assertThat(h.model.generateCalls).hasSize(2)
        // 每次失败都要发出修复提示，且带上失败码
        val repairs = events.filterIsInstance<TaskEvent.RepairIssued>()
        assertThat(repairs).hasSize(2)
        assertThat(repairs.map { it.failureCode }).containsExactly("NO_JSON", "NO_JSON")
        // 解析失败不计一步预算
        assertThat(h.trace.appended).isEmpty()
    }

    @Test
    fun `容错通道能读懂带围栏与未加引号键的输出`() = loop {
        // 这一条验证的是 JsonTolerant 的宽容解析真的接住了真实世界的脏输出
        val h = LoopHarness(
            outputs = { i ->
                if (i == 0) "```json\n{act:\"finish\", answer:\"199\"}\n```" else LoopHarness.FINISH_JSON
            },
        )

        val events = run(h)

        assertThat(events.filterIsInstance<TaskEvent.RepairIssued>()).isEmpty()
        assertThat(events.terminal().reason).isEqualTo(TerminalReason.COMPLETED)
    }

    // ──────────────────────────────────────────── ⑥ 挂起（不是终止）

    @Test
    fun `热节流是挂起不是终止`() = loop {
        val h = LoopHarness(thermal = listOf(ThermalState.SEVERE))

        val events = run(h)

        // ★ 流终点是 Suspended，没有 Terminal —— 暂停 ≠ 结束
        assertThat(events.filterIsInstance<TaskEvent.Terminal>()).isEmpty()
        assertThat(events.last()).isInstanceOf(TaskEvent.Suspended::class.java)
        assertThat(events.count { it.isEndOfStream() }).isEqualTo(1)

        val paused = events.filterIsInstance<TaskEvent.Paused>().single()
        assertThat(paused.status).isEqualTo(TaskStatus.PAUSED)
        assertThat(paused.checkpointSaved).isTrue()
        assertThat(paused.resumable).isTrue()

        // 落盘口径：挂起不写 terminal_reason，因为暂停没有终止原因
        assertThat(h.tasks.finishes).containsExactly("PAUSED|")
        assertThat(h.checkpoints.saved).hasSize(1)
        // 一次推理都没付
        assertThat(h.model.generateCalls).isEmpty()
    }

    @Test
    fun `低电量在阈值内先挂起而不是直接终止`() = loop {
        val h = LoopHarness(battery = listOf(BatteryState(12, false, true, true)))

        val events = run(h)

        assertThat(events.last()).isInstanceOf(TaskEvent.Suspended::class.java)
        assertThat(events.filterIsInstance<TaskEvent.Paused>().single().status)
            .isEqualTo(TaskStatus.PAUSED)
    }

    @Test
    fun `电量低于停止线时收敛为 BATTERY_STOP`() = loop {
        val h = LoopHarness(battery = listOf(BatteryState(5, false, true, true)))

        assertThat(run(h).terminal().reason).isEqualTo(TerminalReason.BATTERY_STOP)
    }

    @Test
    fun `断点存不住时挂起仍然发生但必须告知不可恢复`() = loop {
        val h = LoopHarness(thermal = listOf(ThermalState.SEVERE)).also {
            it.checkpoints.failNextSaves = Int.MAX_VALUE
        }

        val events = run(h)
        val paused = events.filterIsInstance<TaskEvent.Paused>().single()

        // ★ 「显示了继续按钮但点了恢复不了」比「直接说不能继续」糟得多 ——
        //   所以 `checkpointSaved` 不允许硬编码 true，而 `resumable` 必须由它推出。
        assertThat(paused.checkpointSaved).isFalse()
        assertThat(paused.resumable).isFalse()
        // 保存失败不会让挂起变成终止
        assertThat(events.last()).isInstanceOf(TaskEvent.Suspended::class.java)
    }

    // ──────────────────────────────────────────── ⑦ 护栏与 HumanGate

    @Test
    fun `被护栏拒绝的动作一步都不会执行`() = loop {
        val h = LoopHarness().also {
            it.policy.verdict = { PolicyVerdict.Denied("err.out_of_scope", Severity.HIGH) }
        }

        val events = run(h)

        assertThat(events.filterIsInstance<TaskEvent.Executed>()).isEmpty()
        assertThat(h.browser.executed).isEqualTo(0)
        assertThat(events.filterIsInstance<TaskEvent.Guarded>().map { it.decision })
            .containsExactly("DENIED", "DENIED", "DENIED")
        // 拒绝后循环继续推进，最终被循环检测掐住而不是卡死
        assertThat(events.terminal().reason).isEqualTo(TerminalReason.LOOP_DETECTED)
    }

    @Test
    fun `HumanGate 选择中止时收敛为 USER_ABORT`() = loop {
        val h = LoopHarness().also {
            it.policy.verdict = { PolicyVerdict.RequiresConfirm("confirm.sensitive", listOf("删除")) }
            it.gate.resolve = { Resolved.Abort }
        }

        val events = run(h)

        assertThat(events.terminal().reason).isEqualTo(TerminalReason.USER_ABORT)
        assertThat(events.filterIsInstance<TaskEvent.ConfirmRequested>()).hasSize(1)
        assertThat(events.filterIsInstance<TaskEvent.Executed>()).isEmpty()
        assertThat(h.gate.calls).isEqualTo(1)
    }

    @Test
    fun `HumanGate 驳回时落一条 DENIED 步并继续`() = loop {
        var n = 0
        val h = LoopHarness(outputs = { """{"act":"click","id":1}""" }).also {
            it.policy.verdict = { PolicyVerdict.RequiresConfirm("confirm.sensitive", emptyList()) }
            it.gate.resolve = {
                n++
                if (n == 1) Resolved.Reject("err.user_rejected") else Resolved.Proceed
            }
        }

        val events = run(h)

        // 第一步被驳回 ⇒ 一条 DENIED 步记录，浏览器一个动作都没收到
        assertThat(h.trace.appended.count { it.status == com.ake.core.contract.StepStatus.DENIED }).isEqualTo(1)
        assertThat(events.filterIsInstance<TaskEvent.Guarded>().map { it.decision }.toSet())
            .containsExactly("REQUIRES_CONFIRM")
        // 之后放行 ⇒ 真的执行了；最终由循环检测收尾
        assertThat(h.browser.executed).isEqualTo(2)
        assertThat(events.terminal().reason).isEqualTo(TerminalReason.LOOP_DETECTED)
    }

    @Test
    fun `Amend 之后主循环用修改后的动作而不是模型原始输出`() = loop {
        val h = LoopHarness(outputs = { """{"act":"click","id":1}""" }).also {
            it.policy.verdict = { PolicyVerdict.RequiresConfirm("confirm.sensitive", emptyList()) }
            // 模型要 click，人工把它改成了 finish
            it.gate.resolve = {
                Resolved.Amend(StepAction.Finish("199", listOf("rec[1].price")))
            }
        }

        val events = run(h)

        // 只有「用的是 Amend 后的 finish」这一条路径能走到 COMPLETED ——
        // 一个 click 永远不可能让 judge 返回 Done。
        assertThat(events.terminal().reason).isEqualTo(TerminalReason.COMPLETED)
        assertThat(events.filterIsInstance<TaskEvent.Executed>()).hasSize(1)
        // finish 不经注册表，也不碰浏览器
        assertThat(h.browser.executed).isEqualTo(0)
    }

    // ──────────────────────────────────────────── ⑧ 模型开不出会话

    @Test
    fun `openSession 失败时收敛为 BUDGET_EXHAUSTED 并带出原因`() = loop {
        val h = LoopHarness().also { it.model.openFails = true }

        val t = run(h).terminal()

        assertThat(t.reason).isEqualTo(TerminalReason.BUDGET_EXHAUSTED)
        assertThat(t.detail).contains("MODEL_LOAD_FAILED")
    }

    // ──────────────────────────────────────────── ⑨ 流的终点唯一

    @Test
    fun `每个场景的流都以且仅以一个终结语收尾`() = loop {
        val scenarios: List<LoopHarness> = listOf(
            LoopHarness(outputs = { LoopHarness.FINISH_JSON }),
            LoopHarness().also { it.abort = { true } },
            LoopHarness(thermal = listOf(ThermalState.SEVERE)),
            LoopHarness(outputs = { """{"act":"scroll","dir":"down"}""" }),
            LoopHarness(outputs = { "garbage" }),
        )

        for (h in scenarios) {
            val events = run(h)
            assertThat(events.count { it.isEndOfStream() }).isEqualTo(1)
            assertThat(events.last().isEndOfStream()).isTrue()
        }
    }

    // ──────────────────────────────────────────── ⑩ 十二终态契约

    @Test
    fun `十二终态的取值与架构口径一致`() {
        assertThat(TerminalReason.ALL).hasSize(12)

        // 每个终态的 status 都必须是八个主状态里的**终态**，不能是暂停态
        for (r in TerminalReason.ALL) {
            assertThat(com.ake.core.contract.TaskStatus.TERMINAL).contains(r.status)
        }
        // reasonKey 唯一 —— 它同时是 i18n key 与落库值
        assertThat(TerminalReason.ALL.map { it.reasonKey }.toSet()).hasSize(12)
        // 「成功」只有 COMPLETED 一个 —— UI 选颜色与统计口径都靠它
        assertThat(TerminalReason.ALL.filter { it.isSuccess }.map { it.name })
            .containsExactly("COMPLETED")
    }

    @Test
    fun `保留断点的终态清单与架构一致`() {
        val keep = TerminalReason.ALL.filter { it.keepCheckpoint }.map { it.name }.toSet()
        // 用户中止 / 电量 / 超时 / 定位失败 / 修复失败 / 编造答案 ⇒ 用户可能想手动重试
        assertThat(keep).containsExactly(
            "FABRICATED_ANSWER", "USER_ABORT", "BATTERY_STOP",
            "TIMEOUT", "LOCATE_FAILED", "REPAIR_FAILED",
        )
    }

    @Test
    fun `挂起原因与终止原因分属两个枚举`() {
        // 「已暂停，点击继续」与「已结束」在 UI 上是两种完全不同的呈现
        assertThat(SuspendReason.entries.map { it.name })
            .containsExactly("THERMAL", "BATTERY_LOW", "HUMAN_GATE")
        assertThat(SuspendReason.entries.map { it.reasonKey }.toSet()).hasSize(3)
        // HumanGate 走独立 status，其余走 PAUSED
        assertThat(SuspendReason.HUMAN_GATE.status).isEqualTo(TaskStatus.WAITING_HUMAN)
        assertThat(SuspendReason.THERMAL.status).isEqualTo(TaskStatus.PAUSED)
        assertThat(SuspendReason.BATTERY_LOW.status).isEqualTo(TaskStatus.PAUSED)
    }
}
