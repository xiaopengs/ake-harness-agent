package com.ake.data.persist.db

/**
 * ★★ 纯 SQL 的自检探针 —— **给真机 / `adb` / device-verify 直接跑的那一档**（docs/09 §7.1 L3）。
 *
 * ## 为什么要把 SQL 单独抽成常量
 *
 * `RouteDecisionDao.probeTotalRows()` 只能被 App 进程调用。而「这张表到底有没有行」
 * 这个问题，最硬的验证方式是**不经过我们的 DAO**：把库拉下来，用 `sqlite3` CLI
 * （或 python 的 sqlite3 模块）直接跑一句 SQL。两者必须**字面一致**，
 * 否则「DAO 说 0 行」与「真机上 0 行」就是两件互不作证的事。
 *
 * ## 用法（三选一，同一句 SQL）
 *
 * ```
 * # ① 真机 / 模拟器
 * adb exec-out run-as com.ake.harnessagent cat databases/ake_agent.db > probe.db
 * python3 -c "import sqlite3;c=sqlite3.connect('probe.db');print(c.execute('''$PROBE_ROUTE_DECISION_ROWS''').fetchone())"
 *
 * # ② device-verify 脚本（M5 已能拉库）
 * python3 -c "import sqlite3;c=sqlite3.connect('out/ake_agent.db');print(c.execute('''$PROBE_ROUTE_DECISION_ROWS''').fetchone())"
 *
 * # ③ App 进程内
 * routeDecisionDao.probeTotalRows()
 * ```
 *
 * ## ★ S9：「`route_decision` 今日恒空」这条推理的可证伪出口
 *
 * docs/09 §1.2-N1 由三条代码事实推出「全量设备上本表行数为 0」：
 *  ① ~~`LocalOnlyRoutingPort.decide()` 必返 `LocalOnly`~~ **已于 2026-10 失效**：
 *     `:chat/ChatServices` 的 `routing` 已换成 `:inference` 的 `DialogRoutingPort`
 *     （docs/10 §6.3 接线）。纯对话 + 用户选了云端 ⇒ 现在**会**判 `CloudRequested`
 *     ⇒ `route_decision` **开始有行** ⇒ S9「本表恒空」的预测正式过期。
 *  ② 没有任何 UI 入口能表达"想出云"；
 *  ③ `forceAudit` 装配时根本没绑（`PersistenceModule.provideRouteDecisionSink` 不传它）。
 *
 * **这是 docs/09 全文唯一靠推理而非实测的前提**，而 §5.3「存量保留字面量、不重写」
 * 的全部取舍都建立在它之上 ⇒ 必须有一个出口能直接推翻它。下面这两句 SQL 就是那个出口。
 *
 * ★ **预测已过期（2026-10）**：`:chat` 已接到 `DialogRoutingPort`，本表开始有行。
 *    ⇒ 本探针的语义从「证明恒空」变成「**量**有多少行、是否全是规范值」。
 *    §5.3 的**结论**仍然成立（新行写的都是规范值，`PASS`/`BLOCKED` 永远不再被写入）。
 */
public object AkeDatabaseProbe {

    /**
     * ① `route_decision` 总行数。docs/09 §1.2-N1 预测：**0**。
     *
     * 非 0 ⇒ 说明存在一条我没看见的写入路径 ⇒ 回 docs/09 §5.3 重做取舍
     * （**先诊断是谁写的，再决定要不要清洗**，不要就地 UPDATE）。
     */
    public const val PROBE_ROUTE_DECISION_ROWS: String =
        "SELECT COUNT(*) FROM route_decision"

    /**
     * ② 非规范 `egress_gate_verdict` 字面量计数。docs/09 §5.3 预测：**0**。
     *
     * 非 0 ⇒ 存量里存在 `PASS` / `BLOCKED` 之类 ⇒ 需要重新评估「保留字面量」的取舍。
     */
    public const val PROBE_NON_CANONICAL_VERDICTS: String =
        "SELECT COUNT(*) FROM route_decision " +
            "WHERE egress_gate_verdict IS NOT NULL " +
            "AND egress_gate_verdict NOT IN ('UNSCREENED','SCREENED','GATE_DENIED')"

    /** ③ 未收敛的审计行（sweep 的目标面）。UI 用它显示「上次有 N 次出云记录未完成」。 */
    public const val PROBE_UNCONVERGED_ROWS: String =
        "SELECT COUNT(*) FROM route_decision " +
            "WHERE outcome IN ('PENDING','SENT','UNKNOWN')"

    /** ④ 开库即校验的三件套（docs/09 §7.1 L3 步骤 7）。 */
    public const val PROBE_USER_VERSION: String = "PRAGMA user_version"
    public const val PROBE_INTEGRITY: String = "PRAGMA integrity_check"
    public const val PROBE_FOREIGN_KEY_CHECK: String = "PRAGMA foreign_key_check"

    /** ★ 迁移后应为 4。 */
    public const val EXPECTED_USER_VERSION: Int = 4
}
