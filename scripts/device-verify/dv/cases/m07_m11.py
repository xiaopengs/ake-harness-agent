"""
M7–M11 —— 阶段二：核心链路。

这一段全部依赖「模型在场」。模型不在 ⇒ 一律 SKIP，理由写清缺什么。
判据全部落在可查的东西上：`observation` 表的 host / candidate_count / record_row_count、
`task` 表的 status / terminal_reason / backend / elapsed_ms_total、logcat 的固定日志行。

★ 一个必须交代的限制：`observation` 的 payload 与 `chat_message.content` 是**加密区**（R1），
  所以「逐字一致」这类判据只能落在**明文侧**：`observation.host`（刻意不加密，供域名策略
  等值查询）、`record_row_count`、`task.answer_preview`。凡是只能靠密文验证的，本套件
  不假装能验 —— 标注【待真机校准】或直接判为缺口。
"""

from __future__ import annotations

import json
import re
import time
import urllib.request
from typing import Dict, List, Optional, Tuple

from .. import browserfix as BF
from .. import helpers as H
from ..ctx import Ctx, cold_start
from ..model import Assertion, Case, Skip

PHASE = "阶段二 · 核心链路"

KNOWN_BACKENDS = {"CPU", "GPU", "NPU", "GOOGLE_TENSOR"}


def _fetch_manifest(url: str, timeout: float = 30.0) -> Tuple[Optional[dict], str]:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
        return json.loads(body), f"HTTP {resp.status}, {len(body)} B"
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"


def _model_size_from_manifest(m: dict) -> Optional[int]:
    """manifest 结构未在本仓库定型 ⇒ 只做宽容解析，取不到就返回 None（由调用方处理）。"""
    for key in ("size", "sizeBytes", "bytes", "fileSize"):
        if isinstance(m.get(key), int):
            return m[key]
    for key in ("models", "files", "artifacts"):
        v = m.get(key)
        if isinstance(v, list) and v and isinstance(v[0], dict):
            for k in ("size", "sizeBytes", "bytes"):
                if isinstance(v[0].get(k), int):
                    return v[0][k]
    return None


def _ls_models(ctx: Ctx) -> Tuple[str, str]:
    cmd = f"adb shell run-as {ctx.package} ls -l files/models"
    r = ctx.adb.shell(f"run-as {ctx.package} ls -l files/models 2>&1 || true", timeout=60)
    return (r.out or "").strip(), cmd


def _model_files_bytes(ctx: Ctx) -> int:
    """files/models 下所有文件字节数之和。【待真机校准】目录名来自 CoreModule: File(filesDir,"models")。"""
    out, _ = _ls_models(ctx)
    total = 0
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[0].startswith("-"):
            try:
                total += int(parts[4])
            except ValueError:
                pass
    return total


# ─────────────────────────────────────────────────────── M7
def m07(ctx: Ctx) -> List[Assertion]:
    """M7 · 模型下载 2.6GB 成功。"""
    m, detail = _fetch_manifest(ctx.cfg.manifest_url)
    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "manifest 可取回且是合法 JSON", m is not None, "HTTP 200 + JSON 解析成功", detail,
        cmd=f"curl -s {ctx.cfg.manifest_url}", out=detail[:800],
        calibrated=False,
        note="【待真机校准】manifest 的字段结构尚未在仓库里定型，size 字段只能宽容解析",
    ))
    declared = _model_size_from_manifest(m) if m else None

    print("\n    ⏳ 请在设备上启动模型下载，脚本轮询 files/models 直到大小稳定")
    deadline = time.time() + 3600
    last_bytes, stable_rounds = -1, 0
    while time.time() < deadline and stable_rounds < 3:
        time.sleep(10)
        b = _model_files_bytes(ctx)
        stable_rounds = stable_rounds + 1 if b == last_bytes and b > 0 else 0
        last_bytes = b
    out, cmd = _ls_models(ctx)

    svc = ctx.adb.shell(
        f"dumpsys activity services {ctx.package} || true", timeout=60)
    a.append(ctx.assert_true(
        "下载期间出现前台服务（ModelDownloadService）",
        "ModelDownloadService" in ((svc.out or "") + (svc.err or "")),
        "dumpsys activity services 含 ModelDownloadService",
        (svc.out or svc.err)[:400],
        cmd=f"adb shell dumpsys activity services {ctx.package}",
        out=(svc.out or svc.err)[:800],
        calibrated=False,
        note="【待真机校准】下载完成后服务可能已退出；若此时已下完而服务不在，需改判为「曾经出现过」",
    ))
    if declared is not None:
        a.append(ctx.assert_true(
            "模型文件大小 == manifest 声明大小", last_bytes == declared,
            f"{declared} B", f"{last_bytes} B",
            cmd=cmd, out=out[:1000],
        ))
    else:
        a.append(ctx.assert_true(
            "files/models 下有非空模型文件", last_bytes > 100_000_000,
            "> 100,000,000 B（2.6GB 级别的模型）", f"{last_bytes} B",
            cmd=cmd, out=out[:1000],
            note="manifest 里没解析出 size ⇒ 退化为「存在大文件」判据，比逐字节比对弱，已标注",
            calibrated=False,
        ))
    lc = ctx.logcat()
    lc.dump()
    errs = lc.grep(r"ModelDownloadService|ModelDistSync|ModelFileOps", level="E")
    a.append(ctx.assert_true(
        "下载过程无 E 级错误", not errs, "无 E 级日志",
        f"命中 {len(errs)} 条",
        cmd="adb logcat -v time -d | grep -E 'ModelDownloadService|ModelDistSync' （级别 E）",
        out="\n".join(l.text for l in errs[:20]) or "(无)",
    ))
    return a


# ─────────────────────────────────────────────────────── M8
def m08(ctx: Ctx) -> List[Assertion]:
    """M8 · LiteRT-LM 加载 + 首条推理成功。"""
    lc = ctx.logcat()
    lc.clear()
    t0, got, why = H.trigger_goal(ctx, ctx.cfg.goal or "你好，请用一句话介绍你自己",
                                  timeout_s=getattr(ctx.cfg, "task_timeout", 300))
    lc.dump()

    a: List[Assertion] = []
    engine = lc.first(r"engine ready: backend=", tag="LiteRtModelPort")
    a.append(ctx.assert_true(
        "LiteRT Engine 建成功（logcat: engine ready: backend=…）", engine is not None,
        "LiteRtModelPort 输出 'engine ready: backend=<X> maxNumTokens=… build=…ms'",
        engine.msg if engine else "(未出现)",
        cmd="adb logcat -v time -d | grep 'engine ready'",
        out=lc.excerpt(20, r"engine ready|LiteRt") ,
        calibrated=False,
        note="【待真机校准】这行日志的格式来自 LiteRtModelPort.kt:176，确认存在；"
             "但 minLevel=INFO 是否让它在 release 包里也打出来，需真机确认",
    ))
    bad = lc.grep(r"UnsatisfiedLinkError|Can not find OpenCL library|dlopen failed|"
                  r"probe failed with JNI error")
    a.append(ctx.assert_true(
        "无 native 加载/OpenCL 失败", not bad,
        "logcat 中无 UnsatisfiedLinkError / Can not find OpenCL library / dlopen failed",
        f"命中 {len(bad)} 条",
        cmd="adb logcat -v time -d | grep -E 'UnsatisfiedLinkError|OpenCL|dlopen'",
        out="\n".join(l.text for l in bad[:20]) or "(无命中)",
    ))
    pr = ctx.db().pull("m08")
    assistant = None
    if pr.ok:
        try:
            rows = ctx.db().query(
                pr.local_db,
                "SELECT status, COALESCE(content_preview,'') FROM chat_message "
                "WHERE role='ASSISTANT' AND created_at >= ? ORDER BY created_at DESC LIMIT 5", (t0,))
            assistant = rows
        except Exception as e:  # noqa: BLE001
            rows = []
    a.append(ctx.assert_true(
        "首条推理产生 ASSISTANT 消息", bool(assistant), "chat_message 有新增 ASSISTANT 行",
        f"{assistant[:3] if assistant else '(无)'}",
        cmd="SELECT status, content_preview FROM chat_message WHERE role='ASSISTANT' AND created_at >= t0",
        out=str(assistant)[:800] if assistant else "(无)",
    ))
    if assistant:
        a.append(ctx.assert_true(
            "ASSISTANT 消息不是 STREAMING/FAILED",
            all(r[0] in ("COMPLETE",) for r in assistant),
            "status == COMPLETE", f"{[r[0] for r in assistant]}",
            cmd="同上", out=str([r[0] for r in assistant]),
            calibrated=False,
            note="【待真机校准】流式结束后的终态是否落到 COMPLETE 未实测；若长期停在 STREAMING，"
                 "说明 M23 的 sweep 是唯一兜底 —— 那本身就是一个 FAIL 信号",
        ))
    a.append(H.no_crash_assertion(ctx, lc))
    return a


# ─────────────────────────────────────────────────────── M9
def m09(ctx: Ctx) -> List[Assertion]:
    """M9 · backend 降级路径可用。"""
    lc = ctx.logcat()
    lc.dump()
    a: List[Assertion] = []
    engine = lc.first(r"engine ready: backend=", tag="LiteRtModelPort")
    a.append(ctx.assert_true(
        "Engine 建成功且 backend 已知", engine is not None,
        "logcat 出现 engine ready: backend=<CPU/GPU/NPU/GOOGLE_TENSOR>",
        engine.msg if engine else "(未出现)",
        cmd="adb logcat -v time -d | grep 'engine ready'",
        out=lc.excerpt(20, r"engine ready|probe failed"),
        calibrated=False,
    ))
    backend = None
    if engine:
        m = re.search(r"backend=(\w+)", engine.msg)
        backend = m.group(1) if m else None
    a.append(ctx.assert_true(
        "backend 取值在已知集合内", backend in KNOWN_BACKENDS,
        str(sorted(KNOWN_BACKENDS)), str(backend),
        cmd="解析 engine ready 行", out=engine.msg if engine else "(无)",
    ))
    bad = lc.grep(r"probe failed with JNI error|UnsatisfiedLinkError|dlopen failed")
    a.append(ctx.assert_true(
        "无 JNI / 原生库级失败", not bad, "无 probe failed with JNI error / UnsatisfiedLinkError",
        f"命中 {len(bad)} 条",
        cmd="adb logcat -v time -d | grep -E 'probe failed with JNI error|UnsatisfiedLinkError'",
        out="\n".join(l.text for l in bad[:20]) or "(无命中)",
    ))
    ctx.notes.append(
        f"实际 backend={backend}；设备 RAM≈{ctx.env.mem_total_mb}MB, "
        f"SoC={ctx.env.props.get('ro.soc.model') or ctx.env.props.get('ro.board.platform')}"
        f" ⇒ BackendPolicy 期望首选={'GPU' if (ctx.env.mem_total_mb or 0) >= 4096 else 'CPU'}"
        "（仅记录：真降级要经过 probe 失败，App 目前没有强制后端开关）"
    )
    return a


# ─────────────────────────────────────────────────────── M10
#
# ★ `PageScriptHost` 打出来的失败日志（见 browser/JsBootstrap.kt）——
#   「注入自检完整」这件事在 App 侧**只在失败时留痕**，所以 M10 只能据此取反。
#   为免「取反」变成空转，`browserfix.selfcheck_contract()` 会同时盯住那段自检
#   确实还验着 `__stableKeyOf` ⇒ 16 位 hex。
PAGE_SCRIPT_HOST_FAIL = (
    r"JS 运行时注入不完整|注入 JS 运行时脚本时抛异常|为空 —— DOM 抽取脚本缺失"
)


def _bootstrap_fix_assertions(ctx: Ctx, lc, stat) -> List[Assertion]:
    """① 注入自检通过（缺件清单为空） ② `__stableKeyOf` 在真 WebView 上算出 16 位 hex。"""
    failures = lc.grep(PAGE_SCRIPT_HOST_FAIL, tag="PageScriptHost")
    cmd = ("adb logcat -v time -d | grep PageScriptHost；"
           "python: dv/browserfix.selfcheck_contract()")
    out = lc.excerpt(20, tag="PageScriptHost") or "(本次窗口内没有任何 PageScriptHost 日志)"

    ok_selfcheck, why_selfcheck = stat.get("selfcheck", (False, "静态审计没跑起来"))

    return [
        ctx.assert_true(
            "JsBootstrap 注入自检在真机上通过（缺失的全局名清单为空）",
            not failures,
            "观测窗口内没有 PageScriptHost 的缺件 / asset 缺失 / 注入异常日志 ⇒ AKE_MISSING 为空",
            f"{len(failures)} 条失败日志",
            cmd=cmd, out=out,
            calibrated=False,
            note="【形态】`:browser` 只在**失败**时留痕，成功路径一行不打 —— 所以本条是取反判据。"
                 "它成立的前提有三：(a) 前面几条已经证明目标和抽取真的发生了（⇒ 注入跑过）；"
                 "(b) `dumpsys`/crash 那条证明没有提前崩溃；(c) 下面那条证明自检段还在。"
                 "【待真机校准】若真机上 minLevel 把 E 级日志挡掉，本条会假绿 —— "
                 "届时必须给 PageScriptHost 加一条成功日志，再改成正向判据。",
        ),
        ctx.assert_true(
            "`__stableKeyOf` 在真 WebView 上真的算出 16 位 hex",
            ok_selfcheck and not failures,
            "① 自检段在页面上下文里用 /^[0-9a-f]{16}$/ 校验 __stableKeyOf；"
            "② 上面那条缺件清单为空 ⇒ 真引擎通过了这次校验",
            f"静态={why_selfcheck}；真机={len(failures)} 条缺件日志",
            cmd=cmd, out=f"[静态] {why_selfcheck}\n[真机] {out}",
            calibrated=False,
            note="★ 这就是「真引擎验一遍」：`ensureReady()` 返回 true 之前，`JsBootstrap` 的"
                 "自检段已经在**设备上的 WebView 里**跑过 `__stableKeyOf('a','','','')` 并正则校验。"
                 "常量没注进去 / 远端替换了 asset / 注入顺序错了时它算出 NaN 或短 hex，"
                 "缺件清单会带上 `__stableKeyOf` ⇒ 上面那条立刻变红。"
                 "node 侧的同源码校验在 `browser/StableKeyContractTest`，两边是互补的。"
                 "【待真机校准】若将来需要脱离 App 自检独立求值，得给 App 加一个调试求值入口 —— "
                 "那是 `:browser` 业务逻辑改动，不在本次范围内。",
        ),
    ]


def _host_and_blacklist_assertions(ctx: Ctx, rows, pol_rows, stat) -> List[Assertion]:
    """③ 黑名单对 `PASTEBIN.com` 这类写法仍硬拒 ④ 真机落库的 host 已归一化。"""
    a: List[Assertion] = []

    ok_bl, why_bl = stat.get("blacklist", (False, "静态审计没跑起来"))
    proj = ctx.cfg.project_dir
    a.append(ctx.assert_true(
        "黑名单对 `PASTEBIN.com` 这类写法仍然硬拒（不退化成「需确认」）",
        ok_bl,
        "7 种敌意写法（大写 / 尾点 / www+尾点 / userinfo+端口 / query / fragment）"
        "归一化后必须全部命中 `DomainPolicy` 内置黑名单",
        why_bl,
        cmd=f"python: dv/browserfix.check_blacklist_hard_denies({proj})；"
            "黑名单清单与等式均从 DomainPolicy.kt / core:common 机械抽取",
        out=why_bl,
        note="★ python 侧那个 `normalise` 不是新立一份口径 —— 它被钉在从仓库读出来的"
             "`HostsTest.HOST_VECTORS`（30 条畸形输入）上，由 selftest 的 SELF-8 逐条对拍。"
             "少 lowercase 或去尾点一步，上面这些敌意写法就会漏出硬拒。",
    ))

    bl, why_list = BF.builtin_blacklist(proj) if proj else (set(), "")
    # 真机侧：命中黑名单的 target_host 必须是 DENIED，不许是 REQUIRES_CONFIRM / ALLOW
    soft = [(d, h) for (d, h) in pol_rows if h and BF.blacklist_hit(h, bl) and d != "DENIED"]
    pol_sql = ("SELECT decision, COALESCE(target_host,'') FROM policy_decision "
               "WHERE created_at >= t0")
    a.append(ctx.assert_true(
        "真机上命中黑名单的目标 host 是 DENIED（不是「需确认」）",
        not soft,
        "policy_decision 里命中内置黑名单的每一行 decision == DENIED",
        f"软化 {len(soft)} 行: {soft[:5]}；命中清单 {len(bl)} 条" if bl else f"（黑名单清单为空：{why_list}）",
        cmd=pol_sql, out=str(pol_rows)[:1200],
        calibrated=False,
        note="【形态】本轮的 goal 若没碰黑名单域名，这里会是零行 ⇒ 空集通过属正常；"
             "它拦的是「万一撞上了却只给了确认」。主判据是上面那条静态的。",
    ))

    seen = sorted({h for h in [r[0] for r in rows] if h} | {h for (_d, h) in pol_rows if h})
    weird = [h for h in seen if not BF.is_normalised_host(h)]
    a.append(ctx.assert_true(
        "真机落库的 host 已经是归一化形态（小写 / 无尾点 / 无端口 / 无 `www.` / 无 userinfo）",
        not weird,
        "observation.host 与 policy_decision.target_host 全部已归一化",
        f"落库 host {len(seen)} 个（样本 {seen[:8]}）；未归一化的 {len(weird)} 个: {weird[:8]}",
        cmd=f"SELECT host FROM observation WHERE created_at >= t0；{pol_sql}",
        out=f"observation.host={[r[0] for r in rows][:12]}\npolicy.target_host={[h for (_d, h) in pol_rows][:12]}",
        calibrated=False,
        note="★ 这条是「host 不 lowercase」那个 bug 的真机取证点：修复前 `PASTEBIN.com` 会以"
             "**大写**原样落进 `observation.host`，而 `DomainPolicy` 是精确等值匹配 ⇒ 硬拒变放行。"
             "【待真机校准】只看形态不看来源：若将来业务上确实需要原样保存某个非归一化值，届时要换别的证据。",
    ))
    return a


def m10(ctx: Ctx) -> List[Assertion]:
    """M10 · WebView DOM 抽取成功 —— 本项目的咽喉。

    判据（对应文档「抽取出的元素数 > 0 且包含预期的目标元素」）：
      · observation 表新增行 ≥ 1
      · 至少一行 host == --target-url 的 host（observation.host 是明文，刻意不加密）
      · candidate_count > 0  ← 抽取出的候选元素数
      · raw_node_count > 0 且 serialized_chars > 0
      · logcat 真的出现 DomExtractBridge（证明 addJavascriptInterface 的回调来了）

    ★ 2026-10 追加：「这批 bug 修了」而不是「有没有结果」（原先只问后半句）。
      修之前，三段 JS 从未注入 / XHR 计数漂负 / stableKey 两端不等价 / host 不 lowercase
      会让 M10 一跑就挂；修之后若只看 rows>0，则「修复失效」与「目标站今天没数据」不可分辨。
      所以追加四条：**注入自检缺失全局名为空**、**`__stableKeyOf` 在真引擎算出 16 位 hex**、
      **黑名单对 `PASTEBIN.com` 仍硬拒**、**落库 host 已归一化**。
    """
    lc = ctx.logcat()
    lc.clear()
    goal = ctx.cfg.goal
    t0 = H.now_ms()
    H.trigger_goal(ctx, goal, timeout_s=getattr(ctx.cfg, "task_timeout", 300))
    time.sleep(3)
    lc.dump()
    pr = ctx.db().pull("m10")

    a: List[Assertion] = []
    if not pr.ok:
        a.append(ctx.assert_true("能拉到数据库", False, "run-as 可读 databases/",
                                 pr.error, cmd="adb exec-out run-as … cat databases/ake_agent.db",
                                 out=pr.error + "\n" + "\n".join(pr.steps)))
        return a

    rows = ctx.db().query(
        pr.local_db,
        "SELECT host, candidate_count, record_row_count, raw_node_count, pruned_node_count, "
        "serialized_chars, page_digest FROM observation WHERE created_at >= ? "
        "ORDER BY created_at DESC LIMIT 20", (t0,))
    sql = ("SELECT host, candidate_count, record_row_count, raw_node_count, serialized_chars "
           "FROM observation WHERE created_at >= t0")
    a.append(ctx.assert_true(
        "observation 表新增行 ≥ 1（DOM 抽取真的落库了）", len(rows) >= 1, "≥ 1 行",
        f"{len(rows)} 行", cmd=sql, out=str(rows)[:1200],
    ))
    want_host = H.host_of(ctx.cfg.target_url)
    hosts = [r[0] for r in rows]
    a.append(ctx.assert_true(
        f"抽取发生在目标站点（host == {want_host}）", want_host in [h.lower() for h in hosts],
        want_host, str(hosts[:8]),
        cmd=sql, out=str(rows)[:1200],
        note="observation.host 是明文列（§8.1 域名策略要求等值查询，故刻意不加密）⇒ 可作判据",
    ))
    cand = [r[1] for r in rows]
    a.append(ctx.assert_true(
        "candidate_count > 0（抽取出的元素数 > 0）",
        bool(cand) and max(cand) > 0, "max(candidate_count) > 0", f"{cand}",
        cmd=sql, out=str(rows)[:1200],
        calibrated=False,
        note="【待真机校准】candidate_count 是否等于「抽取出的元素数」需真机确认语义；"
             "若该列在某些 page_state 下恒为 0，本条会误 FAIL —— 届时改看 raw_node_count",
    ))
    a.append(ctx.assert_true(
        "raw_node_count > 0 且 serialized_chars > 0",
        bool(rows) and max(r[3] or 0 for r in rows) > 0 and max(r[5] or 0 for r in rows) > 0,
        "两者都 > 0",
        f"raw={[r[3] for r in rows][:8]} chars={[r[5] for r in rows][:8]}",
        cmd=sql, out=str(rows)[:1200],
    ))
    bridge = lc.grep(r".", tag="DomExtractBridge")
    a.append(ctx.assert_true(
        "logcat 出现 DomExtractBridge（JS 桥被 attach 且有回调）", bool(bridge),
        "存在 tag=DomExtractBridge 的日志行", f"{len(bridge)} 行",
        cmd="adb logcat -v time -d | grep DomExtractBridge",
        out=lc.excerpt(20, tag="DomExtractBridge"),
        calibrated=False,
        note="【待真机校准】DomExtractBridge 只在「回调了 unknown id」时才打 W 级日志；"
             "正常成功路径可能**一行都没有**。若真机确认如此，本条应改为观察 observation 表"
             "（那时本条是假 FAIL，请据 out 判断）",
    ))

    # ★ 新增：`:browser` 那批修复在真机上到底生效没有（`dv/browserfix.py` 提供静态那一半）
    stat = BF.run_all(ctx.cfg.project_dir)
    pol_rows: List[Tuple[str, str]] = []
    try:
        pol_rows = ctx.db().query(
            pr.local_db,
            "SELECT decision, COALESCE(target_host,'') FROM policy_decision "
            "WHERE created_at >= ? ORDER BY created_at DESC LIMIT 20", (t0,))
    except Exception as e:  # noqa: BLE001
        ctx.notes.append(f"policy_decision 查询失败: {e}")
    a += _bootstrap_fix_assertions(ctx, lc, stat)
    a += _host_and_blacklist_assertions(ctx, rows, pol_rows, stat)

    a.append(H.no_crash_assertion(ctx, lc))
    return a


# ─────────────────────────────────────────────────────── M11
def m11(ctx: Ctx) -> List[Assertion]:
    """M11 · C1 用例端到端跑通（release 包）。"""
    lc = ctx.logcat()
    lc.clear()
    t0 = H.now_ms()
    H.trigger_goal(ctx, ctx.cfg.goal, timeout_s=getattr(ctx.cfg, "task_timeout", 600))
    time.sleep(3)
    lc.dump()
    pr = ctx.db().pull("m11")

    a: List[Assertion] = []
    if not pr.ok:
        a.append(ctx.assert_true("能拉到数据库", False, "run-as 可读 databases/",
                                 pr.error, cmd="adb exec-out run-as … cat databases/ake_agent.db",
                                 out=pr.error))
        return a

    task = H.latest_task(ctx, pr.local_db, t0)
    a.append(ctx.assert_true(
        "task 终态是 SUCCEEDED", task is not None and task[1] == "SUCCEEDED",
        "status == SUCCEEDED",
        f"{task[:6] if task else '(无 task 行)'}",
        cmd="SELECT id,status,terminal_reason,backend,steps_done,elapsed_ms_total FROM task "
            "WHERE created_at >= t0 ORDER BY created_at DESC LIMIT 1",
        out=str(task)[:800],
    ))
    rows = ctx.db().query(
        pr.local_db,
        "SELECT host, record_row_count, candidate_count FROM observation "
        "WHERE created_at >= ? ORDER BY created_at DESC LIMIT 20", (t0,))
    want_host = H.host_of(ctx.cfg.target_url)
    a.append(ctx.assert_true(
        f"抽取发生在目标站点（host == {want_host}）",
        want_host in [r[0].lower() for r in rows], want_host, str([r[0] for r in rows][:8]),
        cmd="SELECT host, record_row_count FROM observation WHERE created_at >= t0",
        out=str(rows)[:1000],
    ))
    expect_n = len([x for x in ctx.cfg.expected_records.split(",") if x.strip()])
    got_n = max([r[1] or 0 for r in rows], default=0)
    a.append(ctx.assert_true(
        f"record_row_count == {expect_n}（与 --expected-records 条数一致）",
        got_n == expect_n, str(expect_n), str(got_n),
        cmd="SELECT MAX(record_row_count) FROM observation WHERE created_at >= t0",
        out=str([r[1] for r in rows][:10]),
        calibrated=False,
        note="【待真机校准】record_row_count 是否为「抽取出的记录行数」需真机确认",
    ))
    answer = task[7] if task else ""
    missing = [x.strip() for x in ctx.cfg.expected_records.split(",")
               if x.strip() and x.strip() not in (answer or "")]
    a.append(ctx.assert_true(
        "answer_preview 含全部预期记录（逐字）", not missing,
        f"全部命中：{ctx.cfg.expected_records}", f"未命中：{missing}；preview={answer}",
        cmd="SELECT answer_preview FROM task WHERE id=<latest>", out=str(answer)[:800],
        calibrated=False,
        note="【待真机校准】answer_preview 是明文预览，长度可能截断 ⇒ 长答案可能误 FAIL；"
             "真机第一次跑若本条 FAIL 而前几条 PASS，先确认是不是截断",
    ))
    a.append(H.no_crash_assertion(ctx, lc))
    return a


CASES = [
    Case(id="M7", title="模型下载 2.6GB 成功", phase=PHASE, fn=m07,
         needs=["adb", "device", "install", "manifest"],
         doc="docs/04-release-gate.md §5.2 阶段二",
         calibration=["manifest 字段结构未定型 ⇒ size 只做宽容解析",
                      "files/models 目录名来自 CoreModule.kt:137，需真机确认"]),
    Case(id="M8", title="LiteRT-LM 加载 + 首条推理成功", phase=PHASE, fn=m08,
         needs=["adb", "device", "install", "runas", "model", "modelrun"],
         doc="docs/04-release-gate.md §5.2 阶段二",
         calibration=["'engine ready: backend=' 出自 LiteRtModelPort.kt:176",
                      "release 包 minLevel=INFO 是否放行该行需真机确认"]),
    Case(id="M9", title="backend 降级路径可用", phase=PHASE, fn=m09,
         needs=["adb", "device", "install", "runas", "model", "modelrun"],
         doc="docs/04-release-gate.md §5.2 阶段二",
         calibration=["真降级需要 probe 失败；App 目前没有强制后端开关 ⇒ 只能验「选中的后端能跑」"]),
    Case(id="M10", title="WebView DOM 抽取成功（本项目咽喉）", phase=PHASE, fn=m10,
         needs=["adb", "device", "install", "runas", "model", "modelrun", "goal", "targeturl"],
         doc="docs/04-release-gate.md §5.2 阶段二",
         calibration=["candidate_count 的语义（是否=抽取元素数）未实测",
                      "DomExtractBridge 成功路径可能不打日志 ⇒ 该条可能假 FAIL",
                      "payload 是加密区 ⇒ 「包含预期目标元素」退化为 host + candidate_count",
                      "★ 新增 4 条（修复生效）判据首次真机验证：注入自检 / stableKey 16 位 hex "
                      "两条是**取反**判据（成功不打日志），若 release 的 minLevel 挡掉 E 级日志会假绿 —— "
                      "届时给 PageScriptHost 补一条成功日志，或改读 policy_decision 的成功侧",
                      "黑名单 / host 归一化两条的主判据在静态侧（dv/browserfix.py）；"
                      "真机侧 policy_decision 出现 0 行也可能只是本轮 goal 没碰黑名单域名"]),
    Case(id="M11", title="C1 端到端跑通（release 包）", phase=PHASE, fn=m11,
         needs=["adb", "device", "install", "runas", "releaseapk", "model", "modelrun",
                "goal", "targeturl", "expectedrecords"],
         doc="docs/04-release-gate.md §5.2 阶段二",
         calibration=["release 包非 debuggable ⇒ run-as 拉不到库，本条在 release 上必然 SKIP；"
                      "要真跑需先确认拉库方案（root / 可调试 release / 应用内导出）"]),
]
