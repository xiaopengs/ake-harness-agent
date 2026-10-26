"""报告输出：JSON（机器读）+ Markdown（人读）+ 控制台摘要。"""

from __future__ import annotations

import json
import os
from datetime import datetime
from typing import Any, Dict, List

from .model import ERROR, FAIL, PASS, SKIP, CaseResult, STATUS_ZH

ICON = {PASS: "✅", FAIL: "❌", SKIP: "⏭️", ERROR: "💥"}


def verdict(results: List[CaseResult]) -> Dict[str, Any]:
    n = {k: sum(1 for r in results if r.status == k) for k in (PASS, FAIL, SKIP, ERROR)}
    if n[FAIL] or n[ERROR]:
        gate = "NO-GO"
        why = f"{n[FAIL]} 条 FAIL / {n[ERROR]} 条 ERROR"
    elif n[SKIP]:
        gate = "未决（NOT-VERIFIED）"
        why = f"没有 FAIL，但有 {n[SKIP]} 条没跑到 —— 门禁既不能放行也不能说没问题"
    else:
        gate = "GO（仅就本次跑到的用例而言）"
        why = "全部 PASS"
    return {"counts": n, "gate": gate, "why": why}


def write_json(path: str, payload: Dict[str, Any]) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def write_markdown(path: str, payload: Dict[str, Any]) -> None:
    env = payload["env"]
    v = payload["verdict"]
    L: List[str] = []
    L.append("# 真机验证报告 · HarnessAgent")
    L.append("")
    L.append(f"- 生成时间：{payload['generated_at']}")
    L.append(f"- 设备：{payload['device_desc']}")
    L.append(f"- 判定：**{v['gate']}** —— {v['why']}")
    L.append(f"- 统计：PASS {v['counts']['PASS']} / FAIL {v['counts']['FAIL']} / "
             f"SKIP {v['counts']['SKIP']} / ERROR {v['counts']['ERROR']}")
    L.append("")
    if env.get("problems"):
        L.append("## ⛔ 环境缺失（这些不解决，下面的 SKIP 就不会变成 PASS）")
        L.append("")
        for p in env["problems"]:
            L.append(f"- {p}")
        L.append("")
    L.append("## 总览")
    L.append("")
    L.append("| # | 用例 | 状态 | 耗时 | 说明 |")
    L.append("|---|---|---|---|---|")
    for r in payload["results"]:
        why = r["reason"] or (", ".join(a["name"] for a in r["assertions"] if not a["ok"])
                              if r["status"] == FAIL else "")
        L.append(f"| {r['id']} | {r['title']} | {ICON.get(r['status'],'')} {r['status']} "
                 f"| {r['duration_ms']}ms | {why[:80]} |")
    L.append("")
    L.append("## 逐条明细")
    L.append("")
    for r in payload["results"]:
        L.append(f"### {ICON.get(r['status'],'')} {r['id']} · {r['title']} —— {r['status']}"
                 f"（{STATUS_ZH.get(r['status'],'')}）")
        L.append("")
        if r["reason"]:
            L.append(f"- **原因**：{r['reason']}")
        if r["missing"]:
            L.append(f"- **缺什么**：{', '.join(r['missing'])}")
        if r["manual_pending"]:
            L.append(f"- **本次未覆盖**：{r['manual_pending']}")
        if r["calibration"]:
            L.append("- **【待真机校准】**：")
            for c in r["calibration"]:
                L.append(f"  - {c}")
        if r["assertions"]:
            L.append("")
            L.append("| 判据 | 结论 | 期望 | 实测 |")
            L.append("|---|---|---|---|")
            for a in r["assertions"]:
                mark = "✅" if a["ok"] else "❌"
                calib = "" if a["calibrated"] else " ⚠️未校准"
                L.append(f"| {a['name']}{calib} | {mark} | {a['expected']} | {a['actual']} |")
            L.append("")
            for a in r["assertions"]:
                if not a["ok"]:
                    L.append(f"**FAIL 证据 · {a['name']}**")
                    L.append("")
                    L.append(f"```\n$ {a['evidence_cmd']}\n{a['evidence_out']}\n```")
                    L.append("")
        if r["artifacts"]:
            L.append(f"- 失败产物：{', '.join(r['artifacts'])}")
        L.append("")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))


def print_console(payload: Dict[str, Any]) -> None:
    v = payload["verdict"]
    env = payload["env"]
    print("")
    print("=" * 78)
    if env.get("problems"):
        print("⛔ 环境预检不过 —— 下面所有设备用例都会是 SKIP，不是 PASS：")
        for p in env["problems"]:
            print(f"   · {p}")
        print("=" * 78)
    print("| #    | 状态 | 耗时     | 用例")
    print("|------|------|----------|------")
    for r in payload["results"]:
        print(f"| {r['id']:<4} | {ICON.get(r['status'],'')}{r['status']:<5} "
              f"| {r['duration_ms']:>7}ms | {r['title']}")
        if r["status"] in (SKIP, ERROR):
            print(f"|      |      ↳ {r['reason'][:100]}")
        elif r["status"] == FAIL:
            for a in r["assertions"]:
                if not a["ok"]:
                    print(f"|      |      ↳ ✗ {a['name']}：期望 {a['expected']} / 实测 {a['actual']}")
    print("=" * 78)
    print(f"判定：{v['gate']} —— {v['why']}")
    print(f"统计：PASS {v['counts'][PASS]} / FAIL {v['counts'][FAIL]} / "
          f"SKIP {v['counts'][SKIP]} / ERROR {v['counts'][ERROR]}")
    print("=" * 78)


def build_payload(cfg, env, results: List[CaseResult], selftest=None) -> Dict[str, Any]:
    dev = env.device
    desc = "无设备"
    if dev:
        props = env.props or {}
        desc = (f"{props.get('ro.product.manufacturer','')} {props.get('ro.product.model','')} "
                f"(serial={dev.serial}, android={props.get('ro.build.version.release','')}, "
                f"sdk={props.get('ro.build.version.sdk','')}, "
                f"soc={props.get('ro.soc.model') or props.get('ro.board.platform','未知')}, "
                f"RAM≈{env.mem_total_mb}MB)")
    return {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "device_desc": desc,
        "config": {
            "apk": env.apk_path,
            "old_apk": cfg.old_apk,
            "release_apk": cfg.release_apk,
            "serial": cfg.serial,
            "with_model_run": cfg.with_model_run,
            "long_run": cfg.long_run,
        },
        "env": env.as_dict(),
        "results": [r.as_dict() for r in results],
        "verdict": verdict(results),
        "selftest": selftest or {},
    }
