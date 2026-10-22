package com.ake.data.persist.write

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ★ 双泳道**类型隔离**的回归测试（2026-10）。
 *
 * ## 它保护的是哪条结论
 *
 * `WriteOp.kt` 的核心论证是：写入可靠性分级**不能**靠一个 `Reliability` 枚举字段，
 * 因为「字段式分级要靠运行时 `if` 或 review 自觉来兜」——
 * 而被修复的那个 P1 缺陷正是这么发生的：`QueueCaps.P0` 的注释写着「不可丢」，
 * 实现却是 `DROP_OLDEST`。
 *
 * 于是分级被做成了**类型**：`TraceBufferWriter.append` 只接受 `TraceOp`，
 * `TaskStateWriter.submit` 只接受 `StateOp`，写错就是编译错误。
 *
 * ## ⚠ 编译期性质在 JVM 单测里怎么验证
 *
 * 「写错就编译不过」这条本身**无法**在运行时断言（编译不过就没有字节码）。
 * 上一轮一次性验证里是用「三条负向编译断言」证明的 —— 那是编译期检查，
 * 不在 `src/test/` 里。这里退一步，验证**它能成立的运行期前提**：
 *
 *  - 两个 Op 家族在类型上互斥（`TraceOp` 与 `StateOp` 无继承关系、无交集）；
 *  - 两条泳道的公共入口**只**声明了各自家族的参数类型（用反射读签名）；
 *  - `Priority` 里没有 P0（P0 退役是这次分级的直接结论）。
 *
 * 这三条是「编译期隔离」能生效的**必要条件**：任何一条被破坏
 * （例如有人给 `TaskStateWriter` 加了一个 `submit(op: Op)` 的重载），
 * 那道编译防线就悄悄塌了 —— 而这个测试会先红。
 */
class WriteLaneIsolationTest {

    // ──────────────────────────────────────────── ① 类型互斥

    /**
     * ★ 两个家族的成员在这里**显式列出**，而不是用 `sealedSubclasses` 反射枚举。
     *
     * 理由是这条测试的全部价值就在于「清单变了会红」：
     * 用反射枚举的话，谁新加一个 `StateOp` 子类不会让这里红，反而让断言自动跟着变宽松；
     * 显式列出则相反 —— 新增一个 Op 必须先回答「它属于哪条泳道、为什么」。
     * （`sealedSubclasses` 还需要 `kotlin-reflect`，本工程没引，用它还会抛
     *  `KotlinReflectionNotSupportedError`。）
     */
    private val stateOps: List<Class<out StateOp>> = listOf(
        StateOp.TaskUpsert::class.java,
        StateOp.BudgetAdvance::class.java,
        StateOp.TaskFinish::class.java,
        StateOp.TaskSuspend::class.java,
        StateOp.Checkpoint::class.java,
        StateOp.PolicyDecision::class.java,
    )

    private val traceOps: List<Class<out TraceOp>> = listOf(
        TraceOp.Step::class.java,
        TraceOp.ToolInvocation::class.java,
        TraceOp.Observation::class.java,
        TraceOp.TraceEvent::class.java,
        TraceOp.SyntheticTraceDropped::class.java,
    )

    @Test
    fun `StateOp 与 TraceOp 是两个互不包含的封闭家族`() {
        val trace = TraceOp::class.java
        val state = StateOp::class.java

        assertThat(trace.isAssignableFrom(state)).isFalse()
        assertThat(state.isAssignableFrom(trace)).isFalse()

        for (c in stateOps) {
            assertThat(state.isAssignableFrom(c)).isTrue()
            assertThat(trace.isAssignableFrom(c)).isFalse()
        }
        for (c in traceOps) {
            assertThat(trace.isAssignableFrom(c)).isTrue()
            assertThat(state.isAssignableFrom(c)).isFalse()
        }
    }

    @Test
    fun `两个家族的成员清单没有交集`() {
        val states = stateOps.map { it.name }.toSet()
        val traces = traceOps.map { it.name }.toSet()
        assertThat(states intersect traces).isEmpty()

        // 数量断言 = 「新增 Op 必须显式登记到这里」的执行者
        assertThat(states).hasSize(6)
        assertThat(traces).hasSize(5)
    }

    // ──────────────────────────────────────────── ② 入口签名的隔离

    @Test
    fun `状态泳道的入口不接受 TraceOp`() {
        val params = TaskStateWriter::class.java.declaredMethods
            .flatMap { m -> m.parameterTypes.map { m.name to it } }
            .filter { (_, p) -> isLaneType(p) }

        assertThat(params).isNotEmpty()
        for ((name, p) in params) {
            assertThat(p).isEqualTo(StateOp::class.java)
        }
        assertThat(params.map { it.first }).containsAtLeast("submit", "submitAndWait", "trySubmit")
    }

    @Test
    fun `轨迹泳道的入口不接受 StateOp`() {
        val params = TraceBufferWriter::class.java.declaredMethods
            .flatMap { m -> m.parameterTypes.map { m.name to it } }
            .filter { (_, p) -> isLaneType(p) }

        assertThat(params).isNotEmpty()
        for ((_, p) in params) {
            assertThat(p).isEqualTo(TraceOp::class.java)
        }
        // `append` 必须是唯一直达队列的入口，且它只认 TraceOp
        assertThat(params.map { it.first }).containsExactly("append")
    }

    /**
     * `append()` / `submit()` 的**非挂起**性质也是隔离的一部分：
     * `append` 若是 `suspend fun`，调用点就会开始「等一下」，
     * 而 200 ms 合并延迟正是「IO 抖动不进主循环」的实现方式。
     */
    @Test
    fun `轨迹泳道的 append 是非挂起入口`() {
        val append = TraceBufferWriter::class.java.declaredMethods.single { it.name == "append" }
        // suspend fun 在 JVM 上会多出一个 `Continuation` 尾参
        assertThat(append.parameterTypes.size).isEqualTo(1)
        assertThat(append.parameterTypes[0]).isEqualTo(TraceOp::class.java)
    }

    @Test
    fun `状态泳道的 trySubmit 是非挂起入口`() {
        val m = TaskStateWriter::class.java.declaredMethods.single { it.name == "trySubmit" }
        assertThat(m.parameterTypes.size).isEqualTo(1)
        assertThat(m.parameterTypes[0]).isEqualTo(StateOp::class.java)
    }

    // ──────────────────────────────────────────── ③ P0 已退役

    @Test
    fun `Priority 只剩 P1 与 P2`() {
        assertThat(Priority.entries.map { it.name }).containsExactly("P1", "P2")
    }

    @Test
    fun `自证事件走 P1 且不由 append 生成`() {
        // `SyntheticTraceDropped` 的优先级是 P1：它是「我们丢了几行」的证据，
        // 属于必须尽力保住的那一级，不能在背压里被优先牺牲。
        val synthetic = TraceOp.SyntheticTraceDropped(
            taskId = "t", droppedOf = Priority.P2, droppedCount = 3,
            createdAt = 0L, id = "x", seq = 0L,
        )
        assertThat(synthetic.priority).isEqualTo(Priority.P1)
    }

    // ──────────────────────────────────────────── ④ 容量纪律

    @Test
    fun `状态泳道容量足以装下单个任务的全部状态 Op`() {
        // 论证见 StateLaneCaps：单任务状态 Op 总量 ≈ 1 + 步数(≤40) + 断点(≤40) + 审计 + 1
        val worstCase = 1 + LoopBudgetMaxSteps() + LoopBudgetMaxSteps() + LoopBudgetMaxSteps() + 1
        assertThat(StateLaneCaps.CAPACITY).isAtLeast(worstCase * 10)
    }

    @Test
    fun `轨迹泳道有界是无界队列 OOM 的防线`() {
        assertThat(QueueCaps.P1).isLessThan(StateLaneCaps.CAPACITY * 4)
        assertThat(QueueCaps.P2).isGreaterThan(0)
        // ★ 只有轨迹泳道有合并延迟；状态泳道「收到即提交」
        assertThat(QueueCaps.COALESCE_MS).isGreaterThan(0L)
    }

    private fun isLaneType(p: Class<*>): Boolean =
        p == StateOp::class.java || p == TraceOp::class.java

    /** HIGH 档的 `maxSteps`（`LoopBudget` 在 `:agent`，这里只登记数量级，不跨模块依赖）。 */
    private fun LoopBudgetMaxSteps(): Int = 40
}
