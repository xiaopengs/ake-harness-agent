"""
自检（`--selftest`）。

一个验收脚本如果只能产出 PASS，它就是又一个纸做的守护。所以本套件必须先证明自己
**会报 FAIL**。自检做的事：

  SELF-1  断言一个必然不存在的包已安装              ⇒ 必须 FAIL
  SELF-2  断言一个必然不存在的本地文件存在           ⇒ 必须 FAIL（不依赖设备）
  SELF-3  一条断言都不产出的用例                     ⇒ 必须 ERROR，不能 PASS
  SELF-4  adb 缺失时跑一条设备用例                   ⇒ 必须 SKIP，不能 PASS
  SELF-5  一条必然为真的断言                         ⇒ 必须 PASS（证明它也会说好）
  SELF-6  FAIL 但不带证据的断言                      ⇒ 必须 ERROR（无证据的 FAIL 无法排查）
  SELF-7  环境预检在 adb 缺失时必须把缺项列出来      ⇒ 必须列出 ≥1 条

任何一条不符合预期 ⇒ 自检整体失败 ⇒ 退出码非 0 ⇒ **不要拿这个脚本的结论去放行**。
"""

from __future__ import annotations

import os
import tempfile
from typing import Any, Dict, List, Tuple

from .adb import Adb
from .ctx import Ctx
from .envcheck import EnvReport
from .model import ERROR, FAIL, PASS, SKIP, Assertion, Case, run_case

NONEXISTENT_PACKAGE = "com.ake.harnessagent.__never_exists__"
NONEXISTENT_FILE = "/tmp/__ake_verify_never_exists__/nope.txt"


def _case_negative_package(ctx: Ctx) -> List[Assertion]:
    """必然失败：世界上没有这个包。"""
    r = ctx.adb.shell(f"pm list packages {NONEXISTENT_PACKAGE}", timeout=20)
    found = NONEXISTENT_PACKAGE in (r.out or "")
    return [ctx.assert_true(
        f"包 {NONEXISTENT_PACKAGE} 已安装（这是故意构造的必失败场景）",
        found, "包存在", f"pm list packages 输出里{'有' if found else '没有'}这个包",
        cmd=f"adb shell pm list packages {NONEXISTENT_PACKAGE}",
        out=(r.out or "").strip() or "(空)",
    )]


def _case_negative_file(ctx: Ctx) -> List[Assertion]:
    """必然失败：这个文件不存在。"""
    exists = os.path.exists(NONEXISTENT_FILE)
    return [ctx.assert_true(
        f"文件 {NONEXISTENT_FILE} 存在（故意构造的必失败场景）", exists, "存在",
        "不存在" if not exists else "存在",
        cmd=f"test -e {NONEXISTENT_FILE}", out=f"os.path.exists ⇒ {exists}",
    )]


def _case_no_assertion(ctx: Ctx) -> List[Assertion]:
    """一条断言都不产出 ⇒ 必须被判 ERROR，而不是 PASS。"""
    return []


def _case_device(ctx: Ctx) -> List[Assertion]:
    """一条普通的设备用例，用来验证「没有 adb 时会 SKIP」。"""
    ctx.require("device")
    return [ctx.assert_true("永远为真（占位）", True, "True", "True", cmd="", out="")]


def _case_positive(ctx: Ctx) -> List[Assertion]:
    """必然通过：证明这套机制也会说 PASS。"""
    return [ctx.assert_true("1 == 1", 1 == 1, "True", "True", cmd="python 1==1", out="True")]


def _case_fail_without_evidence(ctx: Ctx) -> List[Assertion]:
    """FAIL 但不给证据 ⇒ 必须被降级成 ERROR。"""
    return [Assertion(name="失败但零证据", ok=False, expected="x", actual="y",
                      evidence_cmd="", evidence_out="")]


def run_selftest(cfg, adb: Any = None, env: Any = None) -> Tuple[bool, List[Dict[str, Any]], List[str]]:
    """返回 (自检是否全部通过, 明细, 无法执行需说明的项)。"""
    from .ctx import Config as _Config

    out_dir = cfg.out_dir
    checks: List[Dict[str, Any]] = []
    not_run: List[str] = []

    real_adb = adb
    env = env or EnvReport()
    has_device = real_adb is not None and getattr(env, "device", None) is not None

    # ── 有设备的场景
    ctx_dev = Ctx(cfg, real_adb, env)
    if has_device:
        for cid, fn, expect in (
            ("SELF-1", _case_negative_package, FAIL),
            ("SELF-2", _case_negative_file, FAIL),
            ("SELF-5", _case_positive, PASS),
            ("SELF-6", _case_fail_without_evidence, ERROR),
            ("SELF-3", _case_no_assertion, ERROR),
        ):
            r = run_case(Case(id=cid, title=f"自检：{cid}", phase="自检", fn=fn), ctx_dev)
            checks.append({
                "id": cid, "expected": expect, "actual": r.status,
                "ok": r.status == expect, "detail": r.reason,
            })
    else:
        not_run.append("SELF-1（断言一个必然不存在的包已安装 ⇒ 必须 FAIL）需要一台在线设备才能跑；"
                       "本次无设备，该条自检未执行 —— 请在真机上跑一次 --selftest 补上它")

    # ── 无 adb 的场景：设备用例必须 SKIP
    cfg_noadb = _Config(**{**cfg.__dict__, "out_dir": out_dir})
    ctx_none = Ctx(cfg_noadb, None, EnvReport())
    r_skip = run_case(Case(id="SELF-4", title="自检：adb 缺失时设备用例必须 SKIP",
                           phase="自检", fn=_case_device), ctx_none)
    checks.append({
        "id": "SELF-4", "expected": SKIP, "actual": r_skip.status,
        "ok": r_skip.status == SKIP,
        "detail": r_skip.reason or "（没有 reason 的 SKIP 也是不合格的）",
    })
    # 顺带确认：adb 缺失时它绝不会是 PASS
    checks.append({
        "id": "SELF-4b", "expected": "非 PASS", "actual": r_skip.status,
        "ok": r_skip.status != PASS,
        "detail": "adb 缺失时若判 PASS，说明存在『假装通过』的路径",
    })

    # ── 无设备的场景下，不依赖设备的必失败场景仍必须能跑
    for cid, fn, expect in (
        ("SELF-2", _case_negative_file, FAIL),
        ("SELF-5", _case_positive, PASS),
        ("SELF-6", _case_fail_without_evidence, ERROR),
        ("SELF-3", _case_no_assertion, ERROR),
    ):
        if any(c["id"] == cid for c in checks):
            continue
        r = run_case(Case(id=cid, title=f"自检：{cid}", phase="自检", fn=fn), ctx_none)
        checks.append({
            "id": cid, "expected": expect, "actual": r.status,
            "ok": r.status == expect, "detail": r.reason,
        })

    # ── SELF-7：环境预检必须把缺项列出来
    from .envcheck import build_env
    saved = (cfg.adb_bin, cfg.serial)
    try:
        cfg.adb_bin = "/nonexistent/path/to/adb"
        env_bad = build_env(cfg)
        listed = len(env_bad.problems) >= 1 and any("adb" in p for p in env_bad.problems)
        checks.append({
            "id": "SELF-7", "expected": "环境预检列出 adb 缺失", "actual": f"{env_bad.problems}",
            "ok": bool(listed), "detail": "环境不满足时必须明确说缺什么，不能静默",
        })
    finally:
        cfg.adb_bin, cfg.serial = saved

    all_ok = all(c["ok"] for c in checks)
    return all_ok, checks, not_run
