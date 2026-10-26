"""
M26 / M27 —— 第 3 轮补记新增的两条（文档 §10.7）。

加进来是因为：文档的映射表要真的完整。说「M1–M25 都脚本化了」但漏掉 M26/M27，
等于在门禁里留了两个没有执行体的编号 —— 那正是本项目反复踩的坑。

M26 冷启日志    §10.4 那条「`Provider.get()` 在 dex 里」的静态证据，唯一的运行时闭环手段
M27 重试不重复  制造一次 commit 失败 ⇒ 点重试 ⇒ 不能出现两条相同的用户消息（N-1）
"""

from __future__ import annotations

import os
import time
from typing import List

from .. import helpers as H
from ..ctx import Ctx, cold_start
from ..model import Assertion, Case

PHASE = "第 3 轮补记 · M26/M27"

DUP_SQL = ("SELECT COALESCE(client_request_id,'<null>') AS rid, COUNT(*) FROM chat_message "
           "WHERE role='USER' GROUP BY rid HAVING COUNT(*) > 1")


# ─────────────────────────────────────────────────────── M26
def m26(ctx: Ctx) -> List[Assertion]:
    """M26 · 冷启日志必须出现 `cold start done: templates=… grants=… swept=…`。

    这条**不需要** run-as / 不需要数据库 —— 只要能 adb 到设备就能跑，
    所以它是整个阶段一里成本最低、信息量最大的一条：它直接闭环 §10.4 的静态证据
    （「`Provider.get()` 那条指令在 dex 里」⇒「它真的构造了对象并把 run() 跑完了」）。
    """
    res = cold_start(ctx, wait_logcat_ms=max(15000, int(ctx.cfg.cold_start_timeout * 1000)))
    lc, line = res["logcat"], res["cold_start_line"]

    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "logcat 出现 ColdStart 的 'cold start done:'", line is not None,
        "存在 tag=ColdStart 且含 'cold start done'",
        line.msg if line else "(未出现)",
        cmd="adb logcat -v time -d | grep 'cold start done'",
        out=lc.excerpt(20, r"cold start done|ColdStart"),
        note="★ 这条日志出现 = AkeApp.onCreate 真的解引用了 ColdStartCoordinator 的 Provider，"
             "且它的 init 真的把 TemplateBootstrap + onColdStart 跑完了。"
             "dex 里有那条指令（§10.4）只能证明「写了」，这条证明「跑了」。",
    ))
    if line is None:
        return a
    import re as _re
    parsed = {k: int(m.group(1)) for k in ("templates", "grants", "swept", "failures")
              for m in [_re.search(rf"{k}=(-?\d+)", line.msg)] if m}
    for key, want in (("failures", 0),):
        a.append(ctx.assert_true(
            f"cold start 报告 {key}={want}", parsed.get(key) == want, str(want),
            str(parsed.get(key)),
            cmd="解析 'cold start done: … failures=N …'", out=line.msg,
        ))
    a.append(ctx.assert_true(
        "四个计数都能解析出来（templates/grants/swept/failures）",
        len(parsed) == 4, "4 个", f"{len(parsed)} 个: {parsed}",
        cmd="解析 cold start done 行", out=line.msg,
        calibrated=False,
        note="【待真机校准】日志格式来自 ColdStartCoordinator.kt:166 的字符串拼接；"
             "若真机上格式微调，这里会 FAIL，按 out 里的原始行改正则即可",
    ))
    return a


# ─────────────────────────────────────────────────────── M27
def m27(ctx: Ctx) -> List[Assertion]:
    """M27 · 制造一次 commit 失败 ⇒ 点「重试」⇒ 不能出现两条相同的用户消息。"""
    remote_db = f"databases/{ctx.cfg.db_name}"
    dbp = ctx.db()
    a: List[Assertion] = []

    # ① 基线
    cold_start(ctx, wait_logcat_ms=12000)
    pr0 = dbp.pull("m27_base")
    if not pr0.ok:
        a.append(ctx.assert_true("能拉到数据库", False, "run-as 可读 databases/", pr0.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr0.error))
        return a
    base_users = ctx.db().scalar(pr0.local_db, "SELECT COUNT(*) FROM chat_message WHERE role='USER'") or 0

    # ② 造写失败：库文件只读
    ctx.adb.shell(f"run-as {ctx.package} chmod 0444 {remote_db}", timeout=30)
    ls = dbp.ls_dir()
    perms = _perms(ls.out or "", ctx.cfg.db_name)
    readonly = bool(perms) and "w" not in perms[1:]
    a.append(ctx.assert_true(
        "写失败已构造（库文件权限变只读）", readonly,
        "ls -l 权限串里没有 w", f"{perms}",
        cmd=f"adb shell run-as {ctx.package} chmod 0444 {remote_db} && ls -l databases",
        out=(ls.out or "")[:600],
        calibrated=False,
        note="【待真机校准】与 M24 同一个不确定点：chmod 可能被 SELinux 拒绝。"
             "拒绝了就构造不出失败 ⇒ 本条 FAIL（不是 SKIP，因为「没构造出来」本身就是结论之一）",
    ))
    if not readonly:
        return a

    # ③ 在只读状态下发一条消息（人工点）
    lc = ctx.logcat()
    lc.clear()
    t0 = H.now_ms()
    H.trigger_goal(ctx, ctx.cfg.goal, timeout_s=getattr(ctx.cfg, "task_timeout", 240))
    time.sleep(3)
    lc.dump()
    a.append(ctx.assert_true(
        "写失败被上报（日志里能找到证据）",
        bool(lc.grep(r"SQLiteException|readonly database|STORE_FAILED|REQUEST_STORE_FAILED|ChatMessageWriter")),
        "logcat 出现写失败相关日志", lc.excerpt(15, r"SQLite|readonly|STORE_FAILED"),
        cmd="adb logcat -v time -d | grep -E 'SQLite|readonly|STORE_FAILED'",
        out=lc.excerpt(15, r"SQLite|readonly|STORE_FAILED|ChatMessageWriter"),
    ))

    # ④ 恢复权限，点「重试」
    ctx.adb.force_stop(ctx.package)
    ctx.adb.shell(f"run-as {ctx.package} chmod 0660 {remote_db}", timeout=30)
    cold_start(ctx, wait_logcat_ms=12000)
    ok, msg = ctx.ui().tap_text(ctx.cfg.retry_button_text)
    a.append(ctx.assert_true(
        f"能点到「{ctx.cfg.retry_button_text}」", ok,
        f"界面上有「{ctx.cfg.retry_button_text}」并 tap 成功", msg,
        cmd="adb shell uiautomator dump + input tap", out=msg, calibrated=False,
        note="【待真机校准】重试按钮的文案未在 strings.xml 定位到 ⇒ 必须 --retry-button-text 传入",
    ))
    if not ok:
        return a

    time.sleep(5)
    pr1 = dbp.pull("m27_after")
    if not pr1.ok:
        a.append(ctx.assert_true("重试后能拉到库", False, "run-as 可读 databases/", pr1.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr1.error))
        return a

    dup = ctx.db().query(pr1.local_db, DUP_SQL)
    a.append(ctx.assert_true(
        "没有出现两条 client_request_id 相同的 USER 消息", not dup, "0 组重复",
        f"{dup}", cmd=DUP_SQL, out=str(dup),
        note="★ N-1 的要害：ChatViewModel.retry() 若复用上一轮的 clientRequestId，"
             "唯一索引 idx_chat_msg_request 会让它变成插入失败（而不是重复行）；"
             "这两种症状都要抓，所以下面还查了条数",
    ))
    users = ctx.db().scalar(pr1.local_db, "SELECT COUNT(*) FROM chat_message WHERE role='USER'") or 0
    a.append(ctx.assert_true(
        "USER 消息只新增了 1 条（失败 + 重试没有变成两条）",
        users == base_users + 1, f"{base_users + 1}", f"{users}（基线 {base_users}）",
        cmd="SELECT COUNT(*) FROM chat_message WHERE role='USER'",
        out=f"base={base_users} after={users}",
        calibrated=False,
        note="【待真机校准】若失败发生在 beginAssistant 之前，那条 USER 可能根本没落库，"
             "此时 after 可能 == base ⇒ 需要人工判读：那是 REQUEST_STORE_FAILED 的正确行为，不是 bug",
    ))
    a.append(H.no_crash_assertion(ctx, ctx.logcat()))
    return a


def _perms(ls_out: str, filename: str) -> str:
    for line in (ls_out or "").splitlines():
        if filename in line:
            return line.split()[0]
    return ""


CASES = [
    Case(id="M26", title="冷启日志出现 cold start done:（§10.4 静态证据的运行时闭环）",
         phase=PHASE, fn=m26, needs=["adb", "device", "install"],
         doc="docs/04-release-gate.md §10.7",
         calibration=["日志格式来自 ColdStartCoordinator.kt:166",
                      "不需要 run-as，是阶段一里成本最低、信息量最大的一条"]),
    Case(id="M27", title="重试：制造 commit 失败 ⇒ 不产生两条相同用户消息", phase=PHASE, fn=m27,
         needs=["adb", "device", "install", "runas", "dbwrite", "model", "modelrun",
                "goal", "retrytext"],
         doc="docs/04-release-gate.md §10.7（N-1）",
         calibration=["重试按钮文案未定位 ⇒ 必须 --retry-button-text",
                      "唯一索引 idx_chat_msg_request 会让「重复」表现为插入失败而非重复行，两种都要查"]),
]
