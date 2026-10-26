"""跨用例的小工具。凡是需要「猜真机输出」的地方，一律带上【待真机校准】标记。"""

from __future__ import annotations

import re
import time
from typing import Optional, Tuple

from .ctx import Ctx
from .model import Assertion


def resumed_activity(ctx: Ctx) -> Tuple[str, str]:
    """当前前台 Activity。返回 (输出, 命令)。"""
    cmd = "adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
    r = ctx.adb.shell(
        "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' || true", timeout=60
    )
    return (r.out or r.err or "").strip(), cmd


def resumed_is_ours(ctx: Ctx) -> Tuple[bool, str, str]:
    out, cmd = resumed_activity(ctx)
    return ctx.package in out, out, cmd


def wait_until(pred, timeout_s: float, interval: float = 0.5) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if pred():
            return True
        time.sleep(interval)
    return pred()


def parse_am_time(out: str) -> Optional[int]:
    m = re.search(r"ThisTime:\s*(\d+)", out or "")
    return int(m.group(1)) if m else None


def parse_am_total(out: str) -> Optional[int]:
    m = re.search(r"TotalTime:\s*(\d+)", out or "")
    return int(m.group(1)) if m else None


def no_crash_assertion(ctx: Ctx, lc, name: str = "无崩溃/无 ANR") -> Assertion:
    crashes = lc.crashes()
    return ctx.assert_true(
        name,
        not crashes,
        "logcat 中无 FATAL EXCEPTION / AndroidRuntime / ANR / Unable to create",
        f"命中 {len(crashes)} 条",
        cmd="adb logcat -v time -d | grep -E 'FATAL EXCEPTION|AndroidRuntime|ANR in|Unable to (create|start)'",
        out=lc.crash_excerpt(),
    )


def installed_version(ctx: Ctx) -> Tuple[str, str]:
    cmd = f"adb shell dumpsys package {ctx.package} | grep -E 'versionCode|versionName'"
    r = ctx.adb.shell(
        f"dumpsys package {ctx.package} | grep -E 'versionCode|versionName' || true", timeout=60
    )
    return (r.out or "").strip(), cmd


def models_dir_listing(ctx: Ctx) -> Tuple[str, str]:
    cmd = f"adb shell run-as {ctx.package} ls -l files/models"
    r = ctx.adb.shell(f"run-as {ctx.package} ls -l files/models 2>&1 || true", timeout=60)
    return (r.out or "").strip(), cmd


def now_ms() -> int:
    import time as _t
    return int(_t.time() * 1000)


def host_of(url: str) -> str:
    m = re.match(r"^[a-zA-Z]+://([^/:?#]+)", url or "")
    return (m.group(1) if m else (url or "")).lower()


def trigger_goal(ctx: Ctx, goal: str, timeout_s: float, poll: float = 5.0) -> Tuple[int, bool, str]:
    """
    触发一条任务 goal，并等到 `task` 表真出现一行（created_at >= t0）为止。

    ★ 为什么默认是「人工触发」而不是全自动 UI 操作：
      对话页 → 选 Agent → 输入 → 发送 这条链路上任何一步的文案/层级变了，自动化就会
      静默地点到别的地方，然后用例以「没等到任务」收场 —— 看起来像 App 的问题，其实是
      脚本的问题。与其这样，不如让脚本把该等的证据等出来：**判据仍然完全由脚本判定**，
      只有「把手机上的按钮点一下」这件事交给人。
    """
    t0 = now_ms()
    if getattr(ctx.cfg, "auto_ui", False):
        ctx.ui().tap_text("对话")
        time.sleep(1.0)
        ctx.adb.input_text(goal)
        time.sleep(0.5)
        ctx.adb.shell("input keyevent 66")
    print(f"\n    ⏳ 请在设备上发送这条 goal（脚本最多等 {timeout_s:.0f}s）：{goal}")

    deadline = time.time() + timeout_s
    last = ""
    while time.time() < deadline:
        time.sleep(poll)
        pr = ctx.db().pull("poll")
        if not pr.ok:
            last = pr.error
            continue
        try:
            rows = ctx.db().query(
                pr.local_db,
                "SELECT id, status, COALESCE(goal_preview,'') FROM task "
                "WHERE created_at >= ? ORDER BY created_at DESC LIMIT 5", (t0,))
        except Exception as e:  # noqa: BLE001
            last = f"查询失败: {e}"
            continue
        if rows:
            return t0, True, f"新增 task {len(rows)} 行: {rows}"
        last = "task 表尚无新行"
    return t0, False, last


def latest_task(ctx: Ctx, local_db: str, t0_ms: int):
    try:
        rows = ctx.db().query(
            local_db,
            "SELECT id, status, terminal_reason, backend, steps_done, elapsed_ms_total, "
            "COALESCE(goal_preview,''), COALESCE(answer_preview,'') "
            "FROM task WHERE created_at >= ? ORDER BY created_at DESC LIMIT 1", (t0_ms,))
    except Exception:  # noqa: BLE001
        return None
    return rows[0] if rows else None


def meminfo_pss_kb(ctx: Ctx) -> Tuple[Optional[int], str, str]:
    cmd = f"adb shell dumpsys meminfo {ctx.package}"
    r = ctx.adb.shell(f"dumpsys meminfo {ctx.package} || true", timeout=90)
    out = r.out or ""
    m = re.search(r"TOTAL\s+(?:PSS:\s+)?(\d+)", out)
    pss = int(m.group(1)) if m else None
    return pss, out[:1500], cmd
