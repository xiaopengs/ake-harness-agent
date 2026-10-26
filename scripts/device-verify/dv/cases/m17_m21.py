"""
M17–M21 —— 阶段四：性能与合规。

M17/M18/M19 的量都从 `task` 表直接读（elapsed_ms_total / steps_done / status），
不靠 logcat 时间戳去凑 —— 后者在「logcat 缓冲区被冲掉」时会静默产生假数字。
"""

from __future__ import annotations

import os
import re
import statistics
import time
from typing import List, Tuple

from .. import helpers as H
from ..ctx import Ctx
from ..model import Assertion, Case

PHASE = "阶段四 · 性能与合规"

BASELINE_STEP_MS = 4500          # 文档 §2.3 的 4.5s
STEP_FAIL_FACTOR = 2.0           # 超过基线 2 倍即判 FAIL（9s）


def _per_step_ms(task) -> Tuple[int, int, int]:
    """(steps_done, elapsed_ms_total, per_step_ms)"""
    if not task:
        return 0, 0, 0
    steps = task[4] or 0
    elapsed = task[5] or 0
    return steps, elapsed, (elapsed // steps) if steps else 0


# ─────────────────────────────────────────────────────── M17
def m17(ctx: Ctx) -> List[Assertion]:
    """M17 · 中端机连续 3 次 C1 无 OOM。"""
    lc = ctx.logcat()
    lc.clear()
    t0 = H.now_ms()
    pss_samples: List[int] = []
    for i in range(3):
        print(f"\n    ⏳ 第 {i + 1}/3 次任务：请在设备上发送（或等自动触发）")
        H.trigger_goal(ctx, ctx.cfg.goal, timeout_s=getattr(ctx.cfg, "task_timeout", 300))
        pss, _, _ = H.meminfo_pss_kb(ctx)
        if pss:
            pss_samples.append(pss)
        time.sleep(2)
    lc.dump()
    pr = ctx.db().pull("m17")

    a: List[Assertion] = []
    oom = lc.grep(r"OutOfMemoryError|Low on memory|has died|kill.*oom|LMK")
    a.append(ctx.assert_true(
        "三次任务全程无 OOM / 无进程被杀", not oom,
        "logcat 无 OutOfMemoryError / Low on memory / has died",
        f"命中 {len(oom)} 条",
        cmd="adb logcat -v time -d | grep -E 'OutOfMemoryError|Low on memory|has died'",
        out="\n".join(l.text for l in oom[:20]) or "(无命中)",
    ))
    pid = ctx.adb.pidof(ctx.package)
    a.append(ctx.assert_true(
        "三次跑完进程还活着", pid is not None, "pidof 有值", f"pid={pid}",
        cmd=f"adb shell pidof {ctx.package}", out=str(pid),
    ))
    succ = 0
    if pr.ok:
        rows = ctx.db().query(pr.local_db, "SELECT status FROM task WHERE created_at >= ?", (t0,))
        succ = sum(1 for r in rows if r[0] == "SUCCEEDED")
    a.append(ctx.assert_true(
        "三次任务都 SUCCEEDED", succ >= 3, "≥ 3 行 SUCCEEDED", f"{succ} 行",
        cmd="SELECT status FROM task WHERE created_at >= t0",
        out=str(succ) + " / 3",
    ))
    peak = max(pss_samples) if pss_samples else 0
    total_mb = ctx.env.mem_total_mb or 0
    a.append(ctx.assert_true(
        f"PSS 峰值 < 设备内存的 80%（{int(total_mb * 0.8)} MB）",
        peak > 0 and total_mb > 0 and peak < total_mb * 0.8 * 1024,
        f"< {int(total_mb * 0.8 * 1024)} KB",
        f"peak={peak} KB, 样本={pss_samples}",
        cmd=f"adb shell dumpsys meminfo {ctx.package} | grep TOTAL",
        out=f"peak={peak} KB samples={pss_samples}",
        calibrated=False,
        note="【待真机校准】80% 是我定的工程阈值，不是平台限制；真机第一次跑若本条 FAIL，"
             "请先确认是不是「模型常驻 2.6GB + WebView」的固有占用，再决定是否放宽",
    ))
    a.append(H.no_crash_assertion(ctx, lc))
    return a


# ─────────────────────────────────────────────────────── M18
def m18(ctx: Ctx) -> List[Assertion]:
    """M18 · 单步延迟实测（校准文档里的 4.5s）。"""
    t0 = H.now_ms()
    H.trigger_goal(ctx, ctx.cfg.goal, timeout_s=getattr(ctx.cfg, "task_timeout", 600))
    time.sleep(3)
    pr = ctx.db().pull("m18")
    a: List[Assertion] = []
    if not pr.ok:
        a.append(ctx.assert_true("能拉到数据库", False, "run-as 可读 databases/", pr.error,
                                 cmd="adb exec-out run-as … cat databases/ake_agent.db", out=pr.error))
        return a

    task = H.latest_task(ctx, pr.local_db, t0)
    steps, elapsed, per = _per_step_ms(task)
    a.append(ctx.assert_true(
        "取到有效步数（steps_done >= 1）", steps >= 1, ">= 1", str(steps),
        cmd="SELECT steps_done, elapsed_ms_total FROM task WHERE created_at >= t0",
        out=str(task)[:600],
    ))
    a.append(ctx.assert_true(
        "elapsed_ms_total > 0（耗时被记账了）", elapsed > 0, "> 0", str(elapsed),
        cmd="同上", out=str(task)[:600],
    ))
    a.append(ctx.assert_true(
        f"单步中位耗时 <= {int(BASELINE_STEP_MS * STEP_FAIL_FACTOR)} ms（基线 {BASELINE_STEP_MS} ms 的 "
        f"{STEP_FAIL_FACTOR} 倍）",
        per > 0 and per <= BASELINE_STEP_MS * STEP_FAIL_FACTOR,
        f"<= {int(BASELINE_STEP_MS * STEP_FAIL_FACTOR)} ms",
        f"{per} ms（steps={steps}, elapsed={elapsed} ms）",
        cmd="elapsed_ms_total / steps_done", out=str(task)[:600],
        calibrated=False,
        note="★ 这条是**校准**用的：文档 §2.3 的 4.5s 是估算值，本条给出真实数字。"
             "FAIL 不一定是 bug —— 也可能是 CPU 后端降级（见 M9 的 backend 记录）。",
    ))
    ctx.notes.append(
        f"M18 实测：backend={task[3] if task else '?'} steps={steps} "
        f"elapsed={elapsed}ms 单步={per}ms（基线 {BASELINE_STEP_MS}ms，比值 "
        f"{(per / BASELINE_STEP_MS):.2f}×）"
    )
    return a


# ─────────────────────────────────────────────────────── M19
def m19(ctx: Ctx) -> List[Assertion]:
    """M19 · 连续 20 分钟任务的热节流行为。"""
    lc = ctx.logcat()
    lc.clear()
    t0 = H.now_ms()
    deadline = time.time() + 20 * 60
    rounds, pss_samples, thermals = 0, [], []
    while time.time() < deadline:
        print(f"\n    ⏳ 热节流长跑：剩余 {(deadline - time.time()) / 60:.1f} 分钟（已完成 {rounds} 轮）")
        H.trigger_goal(ctx, ctx.cfg.goal, timeout_s=min(300, max(30, deadline - time.time())))
        rounds += 1
        pss, _, _ = H.meminfo_pss_kb(ctx)
        if pss:
            pss_samples.append(pss)
        th = ctx.adb.shell("dumpsys thermalservice 2>&1 | head -40 || true", timeout=60)
        if th.out:
            thermals.append(th.out.strip()[:400])
    lc.dump()
    pr = ctx.db().pull("m19")

    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "跑满 20 分钟且至少完成 2 轮任务", rounds >= 2, ">= 2 轮", f"{rounds} 轮",
        cmd="循环触发任务 20 分钟", out=f"rounds={rounds}",
    ))
    bad = lc.grep(r"OutOfMemoryError|has died|Low on memory|thermal")
    a.append(ctx.assert_true(
        "全程无 OOM / 无进程死亡", not bad,
        "logcat 无 OutOfMemoryError / has died / Low on memory",
        f"命中 {len(bad)} 条",
        cmd="adb logcat -v time -d | grep -E 'OutOfMemoryError|has died|Low on memory'",
        out="\n".join(l.text for l in bad[:20]) or "(无命中)",
    ))
    pid = ctx.adb.pidof(ctx.package)
    a.append(ctx.assert_true(
        "长跑后进程仍存活", pid is not None, "pidof 有值", f"pid={pid}",
        cmd=f"adb shell pidof {ctx.package}", out=str(pid),
    ))
    a.append(ctx.assert_true(
        "PSS 未单调暴涨（后半程均值 <= 前半程均值 ×1.5）",
        _no_pss_blowup(pss_samples),
        "后半程均值 <= 前半程均值 × 1.5",
        f"samples(KB)={pss_samples}",
        cmd=f"adb shell dumpsys meminfo {ctx.package} | grep TOTAL（每轮采样）",
        out=f"{pss_samples}",
        calibrated=False,
        note="【待真机校准】1.5 倍是工程阈值；另外 `dumpsys thermalservice` 在部分厂商 ROM 上不可用，"
             "降频曲线只能作为证据而非判据",
    ))
    ctx.notes.append(f"热节流采样（前 400 字节/次）：{thermals[:3]}")
    return a


def _no_pss_blowup(samples: List[int]) -> bool:
    if len(samples) < 4:
        return False       # 样本不够 ⇒ 判不了 ⇒ FAIL（不是 PASS）
    half = len(samples) // 2
    early = statistics.mean(samples[:half])
    late = statistics.mean(samples[half:])
    return late <= early * 1.5


# ─────────────────────────────────────────────────────── M20
def m20(ctx: Ctx) -> List[Assertion]:
    """M20 · Play Console 前台服务声明 + 两段演示视频（声明部分可判，提交/审核是人工项）。"""
    manifest = os.path.join(ctx.cfg.project_dir, "app", "src", "main", "AndroidManifest.xml")
    a: List[Assertion] = []
    try:
        with open(manifest, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        a.append(ctx.assert_true("能读到 AndroidManifest.xml", False, "文件存在", str(e),
                                 cmd=f"cat {manifest}", out=str(e)))
        return a

    a.append(ctx.assert_true(
        "声明了 FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in text,
        "manifest 含该权限", "含" if "FOREGROUND_SERVICE_SPECIAL_USE" in text else "不含",
        cmd=f"grep FOREGROUND_SERVICE_SPECIAL_USE {manifest}", out="(见 out)",
    ))
    ok_props = text.count("PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
    a.append(ctx.assert_true(
        "两个前台服务都写了 specialUse 说明", ok_props >= 2, ">= 2 处 property",
        f"{ok_props} 处",
        cmd=f"grep -c PROPERTY_SPECIAL_USE_FGS_SUBTYPE {manifest}", out=f"{ok_props}",
    ))
    svc_ok = ("AgentExecutionService" in text) and ("ModelDownloadService" in text)
    a.append(ctx.assert_true(
        "两个服务都在 manifest 里声明", svc_ok, "AgentExecutionService + ModelDownloadService",
        "都在" if svc_ok else "缺一个",
        cmd=f"grep -E 'AgentExecutionService|ModelDownloadService' {manifest}", out="(见 out)",
    ))

    vids = []
    if ctx.cfg.video_dir and os.path.isdir(ctx.cfg.video_dir):
        vids = [f for f in os.listdir(ctx.cfg.video_dir)
                if f.lower().endswith((".mp4", ".webm"))]
    sizes = {v: os.path.getsize(os.path.join(ctx.cfg.video_dir, v)) for v in vids} \
        if ctx.cfg.video_dir else {}
    a.append(ctx.assert_true(
        "两段演示视频在场且非空", len(vids) >= 2 and all(s > 1024 for s in sizes.values()),
        ">= 2 个视频文件且每个 > 1KB",
        f"{len(vids)} 个: {sizes}",
        cmd=f"ls -l {ctx.cfg.video_dir}", out=str(sizes),
    ))
    return a


# ─────────────────────────────────────────────────────── M21
def m21(ctx: Ctx) -> List[Assertion]:
    """M21 · 隐私政策 URL 可访问（Data Safety 表单是人工项，脚本不装作能判）。"""
    import urllib.request
    url = ctx.cfg.privacy_url
    a: List[Assertion] = []
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            code, length = resp.status, len(body)
    except Exception as e:  # noqa: BLE001
        code, length, body = 0, 0, f"{type(e).__name__}: {e}"
    a.append(ctx.assert_true(
        "隐私政策 URL 返回 HTTP 200", code == 200, "200", str(code),
        cmd=f"curl -sS -o /dev/null -w '%{{http_code}}' {url}", out=str(code),
    ))
    a.append(ctx.assert_true(
        "页面非空（> 500 字符）", length > 500, "> 500", f"{length}",
        cmd=f"curl -s {url} | wc -c", out=str(length),
    ))
    a.append(ctx.assert_true(
        "内容里出现隐私相关字样", ("隐私" in body) or ("privacy" in body.lower()),
        "含「隐私」或 privacy", "含" if (("隐私" in body) or ("privacy" in body.lower())) else "不含",
        cmd=f"curl -s {url} | grep -i 隐私", out=body[:300],
        calibrated=False,
        note="【待真机校准】这是弱判据，只用来挡住「URL 通了但挂了个空页/占位页」",
    ))
    return a


CASES = [
    Case(id="M17", title="中端机连续 3 次 C1 无 OOM", phase=PHASE, fn=m17,
         needs=["adb", "device", "install", "runas", "model", "modelrun", "goal"],
         doc="docs/04-release-gate.md §5.2 阶段四",
         calibration=["386 项 JVM 测试无一触碰内存 ⇒ 本条是真机首次验证",
                      "PSS 80% 阈值是工程阈值，非平台限制"],
         manual_pending=""),
    Case(id="M18", title="单步延迟实测（校准 4.5s 基线）", phase=PHASE, fn=m18,
         needs=["adb", "device", "install", "runas", "model", "modelrun", "goal"],
         doc="docs/04-release-gate.md §5.2 阶段四",
         calibration=["4.5s 是文档估算值，本条产出真实数字",
                      "若 M9 显示 backend=CPU（87s/8 步），单步会远超基线 ⇒ FAIL 需结合 backend 判读"]),
    Case(id="M19", title="连续 20 分钟任务热节流行为", phase=PHASE, fn=m19,
         needs=["adb", "device", "install", "model", "modelrun", "goal", "longrun"],
         doc="docs/04-release-gate.md §5.2 阶段四",
         calibration=["dumpsys thermalservice 在部分厂商 ROM 不可用 ⇒ 曲线只作证据",
                      "PSS 1.5 倍阈值是工程阈值"]),
    Case(id="M20", title="Play Console 前台服务声明 + 两段演示视频", phase=PHASE, fn=m20,
         needs=["videodir"],
         doc="docs/04-release-gate.md §5.2 阶段四",
         calibration=["manifest 声明部分可静态判定；Play Console 提交与审核是人工项，脚本不判定"],
         manual_pending="Play Console 提交 + 审核通过是人工项，本条只判「声明齐全 + 视频在场」"),
    Case(id="M21", title="隐私政策 URL 可访问", phase=PHASE, fn=m21,
         needs=["privacyurl"],
         doc="docs/04-release-gate.md §5.2 阶段四",
         calibration=["Data Safety 表单填报是人工项，脚本不判定"],
         manual_pending="Data Safety 表单（登录态访问如实申报）是人工项，本条只判 URL"),
]
