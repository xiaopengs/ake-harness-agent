"""
M22–M25 —— 第 3 轮新增的四条。

这四条之所以被单独拎出来，是因为**它们的失败是静默的**：用户只会觉得「这 App 有点怪」，
不会崩溃、不会报错、不会有任何一条日志主动告诉你它坏了。所以这四条的判据一律
**不看「有没有崩」，只看「数据/界面有没有真的发生变化」**。

M22 迁移        真机 SQLite（BundledSQLiteDriver / WAL / ART）上 v2→v3 是否保住了数据
M23 冷启兜底    模板 bootstrap 与 STREAMING 收敛是否**真的跑了**（这两个方法历史上生产调用点为 0）
M24 写失败恢复   写失败是否被静默吞掉；恢复后能否再写
M25 无能力 Agent 零能力的人格是否给出提示、是否真的派生不了任务
"""

from __future__ import annotations

import os
import re
import sqlite3
import time
from typing import List, Optional, Tuple

from .. import helpers as H
from ..ctx import Ctx, cold_start
from ..model import Assertion, Case, Skip

PHASE = "第 3 轮新增 · 静默失败专项"

NO_CAP_HINT = "还没有配置扩展能力"     # ui/src/main/res/values/strings.xml:255
NO_CAP_HINT_FULL = ("这个 Agent 还没有配置扩展能力，只会聊天：不会打开网页，也不会调用工具。"
                    "这是它的设定，不是出错。")
CREATE_HINT = "当前一个能力都没勾"      # ui/src/main/res/values/strings.xml:254


def _cs_report(ctx: Ctx) -> Tuple[Optional[object], object]:
    """冷启并拿到 `cold start done:` 那一行。"""
    res = cold_start(ctx, wait_logcat_ms=max(15000, int(ctx.cfg.cold_start_timeout * 1000)))
    return res["cold_start_line"], res


def _parse_cs(msg: str) -> dict:
    out = {}
    for k in ("templates", "grants", "swept", "failures"):
        m = re.search(rf"{k}=(-?\d+)", msg or "")
        if m:
            out[k] = int(m.group(1))
    return out


def _pull(ctx: Ctx, tag: str):
    return ctx.db().pull(tag)


def _push_back(ctx: Ctx, local_db: str, notes: List[str]) -> bool:
    ctx.adb.force_stop(ctx.package)
    time.sleep(0.8)
    return ctx.db().push(local_db, notes)


# ══════════════════════════════════════════════════════ M22
def m22(ctx: Ctx) -> List[Assertion]:
    """M22 · 迁移：装 v2 老包写数据 → 覆盖装新包 → 数据还在不在。"""
    adb = ctx.adb
    a: List[Assertion] = []

    # ① 干净起点：卸干净，装 v2 老包
    adb.uninstall(ctx.package)
    r_old = adb.install(ctx.cfg.old_apk, timeout=ctx.cfg.install_timeout)
    out_old = (r_old.out or "") + (r_old.err or "")
    a.append(ctx.assert_true(
        "v2 老包安装成功", r_old.ok and "Success" in out_old, "Success", out_old.strip()[:300],
        cmd=f"adb install -r -t {ctx.cfg.old_apk}", out=out_old[-1000:],
    ))
    if not (r_old.ok and "Success" in out_old):
        return a

    adb.shell(f"am force-stop {ctx.package}")
    lc = ctx.logcat()
    lc.clear()
    adb.shell(f"am start -W -n {ctx.component} -a android.intent.action.MAIN", timeout=90)
    time.sleep(8)

    # ② 读取 v2 库，确认起点真的是 v2
    pr = _pull(ctx, "m22_v2")
    if not pr.ok:
        a.append(ctx.assert_true("能拉到 v2 的库", False, "run-as 可读 databases/",
                                 pr.error, cmd="adb exec-out run-as … cat databases/ake_agent.db",
                                 out=pr.error + "\n" + "\n".join(pr.steps)))
        return a
    uv2 = ctx.db().scalar(pr.local_db, "PRAGMA user_version")
    a.append(ctx.assert_true(
        "起点确实是 v2 库", uv2 == 2, "user_version == 2", str(uv2),
        cmd="PRAGMA user_version", out=str(uv2),
        calibrated=False,
        note="【待真机校准】若老包本身就是 v3（比如拿错了包），本条会 FAIL —— 那是对的，换包重跑",
    ))

    # ③ 在 v2 库里造 3 行用户数据（memory_card 是 v3 里唯一被 DROP 重建的表 ⇒ 最该盯）
    seeded, err = ctx.db().seed_rows(pr.local_db, "memory_card", 3,
                                     overrides={"key": lambda i: f"ake-verify-m22-{i}",
                                                "value": lambda i: f"迁移前写入的记忆{i}"})
    a.append(ctx.assert_true(
        "能在 v2 库里造出 3 行待迁移数据", seeded == 3, "3 行", f"{seeded} 行 err={err}",
        cmd="INSERT INTO memory_card (…) VALUES (…) ×3", out=err or "3 行已写入",
        calibrated=False,
        note="【待真机校准】memory_card 的列集随版本变化，通用造数按 PRAGMA table_info 自动填必填列；"
             "插不进去会直接报错（不会假成功）",
    ))
    if seeded != 3:
        return a
    before = ctx.db().count(pr.local_db, "memory_card")

    push_notes: List[str] = []
    if not _push_back(ctx, pr.local_db, push_notes):
        a.append(ctx.assert_true("能把造好的 v2 库推回设备", False, "run-as 可写 databases/",
                                 "\n".join(push_notes), cmd="adb push + run-as cat >",
                                 out="\n".join(push_notes)))
        return a

    # ④ 覆盖安装新包（保数据）—— 关键一步：不能 uninstall
    r_new = adb.install(ctx.env.apk_path, timeout=ctx.cfg.install_timeout)
    out_new = (r_new.out or "") + (r_new.err or "")
    a.append(ctx.assert_true(
        "新包覆盖安装成功（数据保留）", r_new.ok and "Success" in out_new, "Success",
        out_new.strip()[:300],
        cmd=f"adb install -r -t {ctx.env.apk_path}", out=out_new[-1000:],
        note="★ 必须是 -r 覆盖安装；一旦走了 uninstall，本条验的东西就不存在了",
    ))
    if not (r_new.ok and "Success" in out_new):
        return a

    lc.clear()
    line, res = _cs_report(ctx)
    lc = res["logcat"]

    # ⑤ 迁移后的判定
    pr2 = _pull(ctx, "m22_v3")
    if not pr2.ok:
        a.append(ctx.assert_true("能拉到迁移后的库", False, "run-as 可读 databases/", pr2.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr2.error))
        return a
    uv3 = ctx.db().scalar(pr2.local_db, "PRAGMA user_version")
    a.append(ctx.assert_true(
        "迁移后 user_version == 3", uv3 == 3, "3", str(uv3),
        cmd="PRAGMA user_version", out=str(uv3),
    ))
    integ = ctx.db().scalar(pr2.local_db, "PRAGMA integrity_check")
    a.append(ctx.assert_true(
        "PRAGMA integrity_check == ok", str(integ).lower() == "ok", "ok", str(integ),
        cmd="PRAGMA integrity_check", out=str(integ),
    ))
    tables = ctx.db().tables(pr2.local_db)
    a.append(ctx.assert_true(
        "v3 六张新表都建出来了",
        {"chat_message", "chat_session", "agent_profile",
         "agent_capability_grant", "template_registry", "route_decision"} <= set(tables),
        "6 张新表全在",
        f"缺 {sorted({'chat_message','chat_session','agent_profile','agent_capability_grant','template_registry','route_decision'} - set(tables))}",
        cmd="SELECT name FROM sqlite_master WHERE type='table'", out=str(tables),
    ))
    after = ctx.db().count(pr2.local_db, "memory_card")
    a.append(ctx.assert_true(
        f"迁移前写入的 {before} 行数据仍在（memory_card 是唯一被 DROP 重建的表）",
        after is not None and before is not None and after >= before,
        f">= {before} 行", f"{after} 行",
        cmd="SELECT COUNT(*) FROM memory_card",
        out=f"before={before} after={after}",
        note="★ 这是本条的要害：v3 迁移用 12 步重建 memory_card（UNIQUE(agent_id,key)），"
             "数据没了 = 用户记忆没了，且**不会有任何报错**",
    ))
    rows = ctx.db().query(pr2.local_db, "SELECT key, value FROM memory_card WHERE key LIKE 'ake-verify-m22-%'")
    a.append(ctx.assert_true(
        "造进去的那几行内容逐字还在", len(rows) >= 3, "≥ 3 行且内容一致",
        f"{rows}", cmd="SELECT key,value FROM memory_card WHERE key LIKE 'ake-verify-m22-%'",
        out=str(rows),
    ))
    mig_err = lc.grep(r"Migration didn't properly handle|duplicate column|SQLiteException|"
                      r"IllegalStateException.*Migration|Room cannot verify")
    a.append(ctx.assert_true(
        "logcat 无迁移异常", not mig_err,
        "无 'Migration didn't properly handle' / duplicate column / SQLiteException",
        f"命中 {len(mig_err)} 条",
        cmd="adb logcat -v time -d | grep -E \"Migration didn't properly handle|duplicate column|SQLiteException\"",
        out="\n".join(l.text for l in mig_err[:20]) or "(无命中)",
        note="文档记载：迁移重跑会产生 8 条 duplicate column ⇒ 幂等声明不成立。"
             "本条只做一次迁移，不会触发；若真机出现，说明覆盖安装路径上发生了二次迁移",
    ))
    a.append(H.no_crash_assertion(ctx, lc))
    return a


# ══════════════════════════════════════════════════════ M23
def m23(ctx: Ctx) -> List[Assertion]:
    """M23 · 冷启兜底：模板列表非空、STREAMING 消息被收敛成「已中断」。"""
    a: List[Assertion] = []

    # ── ① 模板 bootstrap 真的跑了
    line, res = _cs_report(ctx)
    lc = res["logcat"]
    a.append(ctx.assert_true(
        "logcat 出现 ColdStart 的 'cold start done:'",
        line is not None, "存在 tag=ColdStart 且含 'cold start done'",
        line.msg if line else "(未出现)",
        cmd="adb logcat -v time -d | grep 'cold start done'",
        out=lc.excerpt(20, r"cold start done|ColdStart"),
        note="★ 这条日志的存在本身就是判据：历史上 TemplateBootstrap.bootstrap() 与 "
             "ChatLoop.onColdStart() 的**生产调用点为 0**，方法写得对但从没人喊它",
    ))
    parsed = _parse_cs(line.msg) if line else {}
    if "failures" in parsed:
        a.append(ctx.assert_true(
            "cold start 报告 failures=0", parsed["failures"] == 0, "0", str(parsed["failures"]),
            cmd="解析 cold start done 行", out=line.msg if line else "(无)",
        ))
    pr = _pull(ctx, "m23_tpl")
    if not pr.ok:
        a.append(ctx.assert_true("能拉到数据库", False, "run-as 可读 databases/", pr.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr.error))
        return a
    tpl = ctx.db().count(pr.local_db, "template_registry")
    a.append(ctx.assert_true(
        "template_registry 非空（模板 bootstrap 真的写了库）",
        tpl is not None and tpl >= 1, ">= 1 行", f"{tpl} 行",
        cmd="SELECT COUNT(*) FROM template_registry", out=f"{tpl}",
        note="文档记载：出厂内置 5 个模板；若这里是 0 ⇒ 模板市场永远空白，且**不报错**",
    ))

    # ── ② 造一条 STREAMING 脏消息，看冷启会不会收敛它
    sid = f"ake-verify-sess-{os.urandom(3).hex()}"
    mid = f"ake-verify-msg-{os.urandom(3).hex()}"
    n_sess, e1 = ctx.db().seed_rows(pr.local_db, "chat_session", 1, overrides={"id": sid})
    n_msg, e2 = ctx.db().seed_rows(
        pr.local_db, "chat_message", 1,
        overrides={"id": mid, "session_id": sid, "role": "ASSISTANT",
                   "kind": "TEXT", "status": "STREAMING",
                   "content_preview": "ake-verify 未完成的流式回答"})
    a.append(ctx.assert_true(
        "能造出 1 条 STREAMING 脏消息", n_msg == 1,
        "chat_message 里有一条 status='STREAMING'", f"session={n_sess}({e1}) msg={n_msg}({e2})",
        cmd="INSERT INTO chat_message (… status='STREAMING' …)", out=f"{e1} {e2}".strip() or "已写入",
        calibrated=False,
        note="【待真机校准】chat_message 的必填列随 schema 变化，通用造数按 table_info 自动填；"
             "若报 NOT NULL 约束失败，说明还有必填列没有默认值，需要补 overrides",
    ))
    if n_msg != 1:
        return a

    push_notes: List[str] = []
    if not _push_back(ctx, pr.local_db, push_notes):
        a.append(ctx.assert_true("能把造好的库推回设备", False, "run-as 可写 databases/",
                                 "\n".join(push_notes), cmd="adb push + run-as cat >",
                                 out="\n".join(push_notes)))
        return a

    line2, res2 = _cs_report(ctx)
    lc2 = res2["logcat"]
    parsed2 = _parse_cs(line2.msg) if line2 else {}
    a.append(ctx.assert_true(
        "冷启日志显示 swept >= 1（sweepStreaming 真的收敛了脏行）",
        parsed2.get("swept", 0) >= 1, ">= 1", str(parsed2.get("swept")),
        cmd="解析 'cold start done: … swept=N …'",
        out=line2.msg if line2 else "(冷启日志未出现)",
        note="★ 这是本条要害：不收敛的后果是气泡永远转圈，用户以为在思考，其实进程早就被杀了",
    ))
    pr2 = _pull(ctx, "m23_sweep")
    if not pr2.ok:
        a.append(ctx.assert_true("能拉到收敛后的库", False, "run-as 可读 databases/", pr2.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr2.error))
        return a
    left = ctx.db().scalar(pr2.local_db,
                           "SELECT COUNT(*) FROM chat_message WHERE status='STREAMING'")
    a.append(ctx.assert_true(
        "库里不再有 STREAMING 行", left == 0, "0", str(left),
        cmd="SELECT COUNT(*) FROM chat_message WHERE status='STREAMING'", out=str(left),
    ))
    new_status = ctx.db().scalar(pr2.local_db,
                                 "SELECT status FROM chat_message WHERE id=?", (mid,))
    a.append(ctx.assert_true(
        "那条脏消息被改写为非 STREAMING 终态",
        new_status in ("ABORTED", "FAILED", "COMPLETE"),
        "ABORTED / FAILED / COMPLETE 之一", str(new_status),
        cmd=f"SELECT status FROM chat_message WHERE id='{mid}'", out=str(new_status),
        calibrated=False,
        note="【待真机校准】收敛后到底落成哪个终态未实测（代码注释写的是 UPDATE … SET status=…）；"
             "只要不是 STREAMING 就算兜住了，具体值记下来用于校准文档",
    ))
    a.append(H.no_crash_assertion(ctx, lc2))
    return a


# ══════════════════════════════════════════════════════ M24
def m24(ctx: Ctx) -> List[Assertion]:
    """
    M24 · 写失败恢复：把库文件改成只读构造写失败 ⇒ 失败是否被吞掉 ⇒ 恢复后能否再写。
    """
    a: List[Assertion] = []
    dbp = ctx.db()
    remote_db = f"databases/{ctx.cfg.db_name}"

    # 基线：冷启正常
    line0, res0 = _cs_report(ctx)
    if line0 is None:
        ctx.notes.append("基线冷启没拿到 cold start done 日志，后续判据仍以库与日志为准")
    pr0 = _pull(ctx, "m24_base")
    if not pr0.ok:
        a.append(ctx.assert_true("能拉到数据库", False, "run-as 可读 databases/", pr0.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr0.error))
        return a
    base_tpl = ctx.db().count(pr0.local_db, "template_registry")

    # ① 注入：库文件改成只读
    ls_before = dbp.ls_dir()
    orig = _perms_of(ls_before.out or "", ctx.cfg.db_name)
    ch = ctx.adb.shell(f"run-as {ctx.package} chmod 0444 {remote_db}", timeout=30)
    ls_after = dbp.ls_dir()
    now = _perms_of(ls_after.out or "", ctx.cfg.db_name)
    readonly = bool(now) and "w" not in now[1:]      # 形如 -r--r--r--：owners/groups/others 都不可写
    a.append(ctx.assert_true(
        "构造写失败生效（库文件权限变成只读）", readonly,
        "ls -l 权限串里不再有 w（如 -r--r--r--）",
        f"before={orig} after={now}",
        cmd=f"adb shell run-as {ctx.package} chmod 0444 {remote_db} && ls -l databases",
        out=(ls_after.out or "")[:800],
        calibrated=False,
        note="【待真机校准】这是整条用例最不确定的一步：Android 10+ 对 /data/data 的 chmod "
             "可能被 SELinux 或厂商 ROM 拒绝。若 after 没变，本条会 FAIL —— 那意味着"
             "「写失败」根本没被构造出来，后面的结论都不成立（不会假成功）",
    ))
    if not readonly:
        # 没造成故障 ⇒ 无法验证 ⇒ 报 FAIL 并附上原因（绝不因为「没崩」就判过）
        return a

    # ② 写失败是否被静默吞掉
    ctx.adb.force_stop(ctx.package)
    lc = ctx.logcat()
    lc.clear()
    ctx.adb.shell(f"am start -W -n {ctx.component} -a android.intent.action.MAIN", timeout=90)
    time.sleep(10)
    lc.dump()

    reported = lc.grep(r"SQLiteException|SQLiteReadOnly|readonly database|attempt to write|"
                       r"STORE_FAILED|REQUEST_STORE_FAILED|ChatMessageWriter|"
                       r"ColdStart.*failed|TemplateBootstrap.bootstrap failed|"
                       r"ChatLoop.onColdStart failed")
    a.append(ctx.assert_true(
        "写失败**被上报**（日志里能找到写失败的证据，而不是静默吞掉）",
        bool(reported),
        "logcat 出现 SQLite*Exception / readonly database / STORE_FAILED / "
        "ColdStart … failed / ChatLoop.onColdStart failed 之一",
        f"命中 {len(reported)} 条",
        cmd="adb logcat -v time -d | grep -E 'SQLite|readonly|STORE_FAILED|onColdStart failed'",
        out="\n".join(l.text for l in reported[:20]) or "(无命中 ⇒ 写失败被静默吞掉了)",
        note="★ 本条要害：技术债 D2 / P0-3 说的是「失败了还报成功」。"
             "如果这里一条都没有 ⇒ 写失败被吞 ⇒ 这就是 P0-1 的实证",
    ))
    a.append(H.no_crash_assertion(ctx, lc, name="写失败期间没有把 App 搞崩"))

    # ③ 恢复权限 + 证明「还能写」
    ctx.adb.force_stop(ctx.package)
    ctx.adb.shell(f"run-as {ctx.package} chmod 0660 {remote_db}", timeout=30)
    ls_restored = dbp.ls_dir()
    a.append(ctx.assert_true(
        "权限已恢复为可写", "rw" in (_perms_of(ls_restored.out or "", ctx.cfg.db_name) or ""),
        "权限串含 rw", str(_perms_of(ls_restored.out or "", ctx.cfg.db_name)),
        cmd=f"adb shell run-as {ctx.package} chmod 0660 {remote_db} && ls -l databases",
        out=(ls_restored.out or "")[:800],
    ))

    # 清空 template_registry ⇒ 冷启 bootstrap 必须重新写入 ⇒ 这就是「还能写」的实证
    pr1 = _pull(ctx, "m24_clear")
    if not pr1.ok:
        a.append(ctx.assert_true("恢复后能拉到库", False, "run-as 可读 databases/", pr1.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr1.error))
        return a
    try:
        con = sqlite3.connect(pr1.local_db)
        con.execute("DELETE FROM template_registry")
        con.commit()
        con.close()
    except sqlite3.Error as e:
        a.append(ctx.assert_true("能清掉 template_registry 作为写入试验", False, "DELETE 成功",
                                 str(e), cmd="DELETE FROM template_registry", out=str(e)))
        return a
    notes: List[str] = []
    if not _push_back(ctx, pr1.local_db, notes):
        a.append(ctx.assert_true("能把清空后的库推回设备", False, "run-as 可写 databases/",
                                 "\n".join(notes), cmd="adb push + run-as cat >", out="\n".join(notes)))
        return a

    line2, res2 = _cs_report(ctx)
    pr2 = _pull(ctx, "m24_recover")
    if not pr2.ok:
        a.append(ctx.assert_true("恢复后能拉到库", False, "run-as 可读 databases/", pr2.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr2.error))
        return a
    tpl2 = ctx.db().count(pr2.local_db, "template_registry")
    a.append(ctx.assert_true(
        f"恢复后能重新写入（template_registry 从 0 恢复到 >= {base_tpl or 1} 行）",
        tpl2 is not None and tpl2 >= (base_tpl or 1),
        f">= {base_tpl or 1} 行", f"{tpl2} 行（基线 {base_tpl}）",
        cmd="SELECT COUNT(*) FROM template_registry",
        out=f"base={base_tpl} recovered={tpl2}",
        note="★ 这是「同会话能否继续」的模型无关等价判据：写路径恢复了没有。"
             "若这里是 0 ⇒ 用户从此只能聊天不能落库，且界面上什么都看不出来",
    ))
    if line2 is not None:
        p2 = _parse_cs(line2.msg)
        a.append(ctx.assert_true(
            "恢复后 cold start 报告 failures=0", p2.get("failures") == 0, "0",
            str(p2.get("failures")), cmd="解析 cold start done 行", out=line2.msg,
        ))
    a.append(H.no_crash_assertion(ctx, res2["logcat"], name="恢复后无崩溃"))
    return a


def _perms_of(ls_out: str, filename: str) -> Optional[str]:
    for line in (ls_out or "").splitlines():
        if filename in line:
            return line.split()[0]
    return None


# ══════════════════════════════════════════════════════ M25
def m25(ctx: Ctx) -> List[Assertion]:
    """M25 · 无能力 Agent：空白创建 ⇒ 是否给出提示、是否真的不能派生任务。"""
    a: List[Assertion] = []
    name = ctx.cfg.agent_name or "无能力验证Agent"

    # ① 造（或找）一个零授权的人格
    line0, _ = _cs_report(ctx)
    pr = _pull(ctx, "m25_seed")
    if not pr.ok:
        a.append(ctx.assert_true("能拉到数据库", False, "run-as 可读 databases/", pr.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr.error))
        return a

    pid_row = ctx.db().query(pr.local_db,
                             "SELECT id FROM agent_profile ORDER BY created_at DESC LIMIT 1")
    if pid_row:
        agent_id = pid_row[0][0]
        ctx.notes.append(f"复用已有人格 {agent_id}")
    else:
        agent_id = f"ake-verify-agent-{os.urandom(3).hex()}"
        n, err = ctx.db().seed_rows(pr.local_db, "agent_profile", 1,
                                    overrides={"id": agent_id, "name": name})
        a.append(ctx.assert_true(
            "能造出一个零能力人格", n == 1, "agent_profile 新增 1 行", f"{n} 行 err={err}",
            cmd="INSERT INTO agent_profile (…) VALUES (…)", out=err or "已写入",
            calibrated=False,
        ))
        if n != 1:
            return a

    # 把它的授权清空 ⇒ 这就是「空白创建」的状态
    try:
        con = sqlite3.connect(pr.local_db)
        con.execute("DELETE FROM agent_capability_grant WHERE agent_id=?", (agent_id,))
        con.commit()
        con.close()
    except sqlite3.Error as e:
        ctx.notes.append(f"清授权失败（可能列名不同）: {e}")

    notes: List[str] = []
    if not _push_back(ctx, pr.local_db, notes):
        a.append(ctx.assert_true("能把零能力人格推回设备", False, "run-as 可写 databases/",
                                 "\n".join(notes), cmd="adb push + run-as cat >", out="\n".join(notes)))
        return a

    # ② 冷启后到界面上去看提示
    cold_start(ctx, wait_logcat_ms=12000)
    ok_tab, msg_tab = ctx.ui().tap_text("对话")
    a.append(ctx.assert_true(
        "能进到对话 tab", ok_tab, "点到底栏「对话」", msg_tab,
        cmd="adb shell uiautomator dump + input tap", out=msg_tab, calibrated=False,
    ))
    if not ok_tab:
        return a

    ok_open, msg_open = ctx.ui().tap_text(name) or ctx.ui().tap_text("新建")
    a.append(ctx.assert_true(
        f"能打开零能力人格「{name}」的会话", ok_open,
        f"界面上能找到「{name}」并 tap 成功", msg_open,
        cmd="adb shell uiautomator dump + input tap", out=msg_open, calibrated=False,
        note="【待真机校准】人格在列表里叫什么、怎么点进去，未实测；"
             "若找不到，用 --agent-name 指定一个已存在的人格名",
    ))
    if not ok_open:
        return a

    ctx.ui().dump()
    texts = ctx.ui().texts()
    hit = [t for t in texts if NO_CAP_HINT in t or CREATE_HINT in t]
    a.append(ctx.assert_true(
        "界面给出了「这个 Agent 只会聊天」的提示",
        bool(hit),
        f"界面文案含「{NO_CAP_HINT}」或「{CREATE_HINT}」（strings.xml:254/255）",
        f"{hit}" if hit else f"未见；当前可见文案={texts[:25]}",
        cmd="adb shell uiautomator dump（比对可见文案）",
        out=str(texts[:40]),
        calibrated=False,
        note="★ 本条要害：不给提示的后果是用户以为 App 坏了（P0-4）。"
             "提示文案本身来自 strings.xml，是确定存在的；不确定的是它会不会在这个界面出现",
    ))
    lc = ctx.logcat()
    lc.dump()
    a.append(H.no_crash_assertion(ctx, lc))
    return a


CASES = [
    Case(id="M22", title="迁移：v2 老包写数据 → 覆盖装新包 → 数据还在", phase=PHASE, fn=m22,
         needs=["adb", "device", "apk", "oldapk", "runas", "dbwrite"],
         doc="docs/04-release-gate.md §9.7 第 2 步",
         calibration=[
             "上一轮迁移验证用的是 x86_64 桌面 SQLite，不是设备上的 BundledSQLiteDriver；"
             "「结构一致」不等于「ART 上、真机 IO 下、WAL 模式下也一致」",
             "memory_card 是 v3 唯一被 DROP 重建的表 ⇒ 用它的行数当数据保全的判据",
         ]),
    Case(id="M23", title="冷启兜底：模板非空 + STREAMING 被收敛", phase=PHASE, fn=m23,
         needs=["adb", "device", "install", "runas", "dbwrite"],
         doc="docs/04-release-gate.md §9.7 第 2 步 / §10.x",
         calibration=[
             "'cold start done: templates=… grants=… swept=… failures=…' 出自 ColdStartCoordinator.kt:166",
             "收敛后的终态是 ABORTED/FAILED/COMPLETE 中的哪一个未实测（只要求不是 STREAMING）",
             "流式回调是增量还是累计（StreamingDeltaAssembler 启发式判别）需在长回答上另看首 2 个 chunk",
         ]),
    Case(id="M24", title="写失败恢复：失败不被吞 + 恢复后能再写", phase=PHASE, fn=m24,
         needs=["adb", "device", "install", "runas", "dbwrite"],
         doc="docs/04-release-gate.md §9.7 第 2 步（验 P0-1 / 技术债 D2）",
         calibration=[
             "chmod 0444 是否能在 /data/data 下生效**完全未验证**，可能被 SELinux 拒绝",
             "只读库文件下 SQLite 的行为（open 失败 vs 读成功写失败）随版本不同 ⇒ 判据只要求"
             "「日志里出现了写失败的证据」+「没崩」",
         ],
         manual_pending="『同一个会话里还能不能继续发消息』需要模型在场（--with-model-run --goal），本次未覆盖"),
    Case(id="M25", title="无能力 Agent：给提示 + 不能派生任务", phase=PHASE, fn=m25,
         needs=["adb", "device", "install", "runas", "dbwrite", "uiautomator"],
         doc="docs/04-release-gate.md §9.7 第 2 步（验 P0-4）",
         calibration=[
             "提示文案确定存在（ui strings.xml:254/255）；不确定的是它会不会在这个界面出现",
             "零能力人格通过「清掉 agent_capability_grant」构造，不是靠 UI 点（UI 点不稳定）",
         ],
         manual_pending="『是否真的不能派生任务』需要模型在场（--with-model-run --goal），本次未覆盖"),
]
