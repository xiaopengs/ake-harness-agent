#!/usr/bin/env python3
"""
HarnessAgent · 真机验证套件入口

一条命令：

    ./run.py                        # 跑全部（M0–M27，缺什么就 SKIP 什么）
    ./run.py --only M3-M6,M22-M25   # 只跑阶段一 + 第 3 轮新增
    ./run.py --selftest             # 先证明这套脚本自己会报 FAIL（建议第一次必跑）

退出码（这是门禁能真正拦住东西的关键）：
    0  全部 PASS
    1  存在 FAIL 或 ERROR           ⇒ NO-GO
    2  没有 FAIL，但有 SKIP/ERROR   ⇒ 未决，门禁不能放行
    3  自检失败                     ⇒ 脚本本身不可信，别用它的结论
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dv import report as R                     # noqa: E402
from dv.adb import Adb, find_adb               # noqa: E402
from dv.audit import run_selftest              # noqa: E402
from dv.cases import select                    # noqa: E402
from dv.ctx import Ctx, Config                 # noqa: E402
from dv.envcheck import build_env, case_m0     # noqa: E402
from dv.model import ERROR, FAIL, PASS, SKIP, Case, run_case  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "out")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="run.py",
        description="HarnessAgent 真机验证套件（M0–M27）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--project-dir", default="/workspace/HarnessAgent", help="工程根目录")
    p.add_argument("--apk", default="", help="待验 APK（默认 debug 产物路径）")
    p.add_argument("--old-apk", default="", help="【M22】v2 老包 —— 没有它无法验迁移")
    p.add_argument("--release-apk", default="", help="【M11】已签名 release 包")
    p.add_argument("--adb", default="", help="adb 路径（不指定则自动找）")
    p.add_argument("--serial", default="", help="设备序列号（多设备必须指定）")
    p.add_argument("--package", default="com.ake.harnessagent")
    p.add_argument("--out", default="", help="报告输出目录（默认 ./out/<时间戳>）")

    p.add_argument("--only", default="", help="只跑指定用例，如 M3-M6 或 M22,M23")
    p.add_argument("--skip-env-case", action="store_true", help="不把环境预检当成一条用例")
    p.add_argument("--clean-install", action="store_true", help="M3 前先卸载")
    p.add_argument("--build", action="store_true", help="APK 不在时先 assembleDebug")
    p.add_argument("--no-bugreport", action="store_true", help="失败时不抓 bugreport（快很多）")

    # 阶段二/三/四的输入 —— 不给就 SKIP，脚本绝不替你猜
    p.add_argument("--with-model-run", action="store_true",
                   help="本次会真的跑推理（M8–M14/M17–M19 的前置）")
    p.add_argument("--auto-ui", action="store_true",
                   help="尝试用 uiautomator + input text 自动发送 goal（实验性，默认人工触发）")
    p.add_argument("--manifest-url", default="")
    p.add_argument("--goal", default="", help="【M8/M10/M11/M17/M18/M19】任务 goal")
    p.add_argument("--target-url", default="", help="【M10/M11】目标站点 URL")
    p.add_argument("--target-text", default="", help="（保留位：payload 是加密区，暂未用作判据）")
    p.add_argument("--expected-records", default="", help="【M11】预期抽取结果，逗号分隔")
    p.add_argument("--payment-goal", default="", help="【M12】支付类 goal")
    p.add_argument("--injection-goal", default="", help="【M13】提示注入 goal")
    p.add_argument("--injected-url", default="", help="【M13】注入者诱导的 URL")
    p.add_argument("--budget-goal", default="", help="【M14】必然超预算的 goal")
    p.add_argument("--confirm-goal", default="", help="【M15】会触发确认弹窗的 goal")
    p.add_argument("--wipe-button-text", default="", help="【M16】一键清除按钮文案（未登记，必须现场给）")
    p.add_argument("--agent-name", default="无能力验证Agent", help="【M25】零能力人格名")
    p.add_argument("--retry-button-text", default="", help="【M27】重试按钮文案（未登记，需现场给）")
    p.add_argument("--video-dir", default="", help="【M20】两段演示视频目录")
    p.add_argument("--privacy-url", default="", help="【M21】隐私政策 URL")
    p.add_argument("--long", action="store_true", help="【M19】允许 20 分钟长跑")
    p.add_argument("--task-timeout", type=float, default=300.0, help="等待单条任务完成秒数")
    p.add_argument("--cold-start-timeout", type=float, default=25.0, help="等待冷启日志的秒数")
    p.add_argument("--install-timeout", type=float, default=600.0)
    p.add_argument("--selftest", action="store_true", help="只跑自检：证明脚本会报 FAIL")
    p.add_argument("--map", action="store_true",
                   help="打印 M 号 → 脚本函数/文件/行号的映射（文档 §11.2 的表就是它生成的）")
    return p


def print_map() -> None:
    """打印 M 号 → 执行体的映射。docs/04-release-gate.md §11.2 的表由本函数生成。
    改了判据请重跑一次 `./run.py --map` 把表同步回去 —— 表漂移就是映射失效。"""
    import inspect
    from dv.cases import ALL_CASES
    print("| # | 用例 | 执行体（文件:行） | 函数 | 前置 |")
    print("|---|---|---|---|---|")
    print("| **M0** | 环境预检（adb/设备/getprop/APK/签名） | `dv/envcheck.py` | `case_m0()` | — |")
    for c in ALL_CASES:
        src, line = inspect.getsourcelines(c.fn)
        mod = c.fn.__module__.replace("dv.cases.", "")
        print(f"| **{c.id}** | {c.title} | `{mod}.py:{line}` | `{c.fn.__name__}()` "
              f"| {', '.join(c.needs) or '—'} |")
        for note in c.calibration:
            print(f"| | ⚠ {note} | | | |")


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)

    out_dir = args.out or os.path.join(DEFAULT_OUT, datetime.now().strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out_dir, exist_ok=True)

    cfg = Config(
        project_dir=args.project_dir,
        apk=args.apk,
        old_apk=args.old_apk,
        release_apk=args.release_apk,
        serial=args.serial,
        adb_bin=args.adb,
        out_dir=out_dir,
        package=args.package,
        manifest_url=args.manifest_url,
        goal=args.goal,
        target_url=args.target_url,
        target_text=args.target_text,
        expected_records=args.expected_records,
        payment_goal=args.payment_goal,
        injection_goal=args.injection_goal,
        injected_url=args.injected_url,
        budget_goal=args.budget_goal,
        wipe_button_text=args.wipe_button_text,
        privacy_url=args.privacy_url,
        video_dir=args.video_dir,
        with_model_run=args.with_model_run,
        long_run=args.long,
        clean_install=args.clean_install,
        build_apk=args.build,
        collect_bugreport=not args.no_bugreport,
        install_timeout=args.install_timeout,
        cold_start_timeout=args.cold_start_timeout,
        auto_ui=args.auto_ui,
        task_timeout=args.task_timeout,
        confirm_goal=args.confirm_goal,
        agent_name=args.agent_name,
        retry_button_text=args.retry_button_text,
    )

    print("HarnessAgent 真机验证套件 · M0–M27")
    print(f"输出目录：{out_dir}")

    if args.map:
        print_map()
        return 0

    env = build_env(cfg)
    adb = None
    if env.adb_path:
        adb = Adb(env.adb_path, cfg.serial or None)
        if env.device:
            adb.serial = env.device.serial
    print(f"设备：{env.device.serial + ' ' + env.device.model if env.device else '无'}")
    if env.problems:
        print("⛔ 环境预检发现问题：")
        for p in env.problems:
            print(f"   · {p}")

    if args.selftest:
        ok, checks, not_run = run_selftest(cfg, adb, env)
        print("\n自检结果（这一步是证明「脚本会报 FAIL」）：")
        for c in checks:
            mark = "✅" if c["ok"] else "❌"
            print(f"  {mark} {c['id']}: 期望 {c['expected']} / 实际 {c['actual']}"
                  + (f" —— {c['detail']}" if c.get("detail") else ""))
        for n in not_run:
            print(f"  ⏭️  {n}")
        print(f"\n自检{'通过' if ok else '失败 —— 不要用这套脚本的结论去放行'}。")
        return 0 if ok else 3

    ctx = Ctx(cfg, adb, env)
    cases = select(args.only)
    results = []

    if not args.skip_env_case:
        results.append(run_case(Case(id="M0", title="环境预检", phase="前置",
                                     fn=case_m0, needs=[]), ctx))

    print(f"\n开始执行 {len(cases)} 条用例（{', '.join(c.id for c in cases)}）\n")
    for c in cases:
        print(f"  ▶ {c.id} {c.title} …", flush=True)
        r = run_case(c, ctx)
        results.append(r)
        icon = {PASS: "✅", FAIL: "❌", SKIP: "⏭️", ERROR: "💥"}[r.status]
        print(f"    {icon} {r.status} ({r.duration_ms}ms)"
              + (f" —— {r.reason}" if r.reason else ""), flush=True)
        if r.status in (FAIL, ERROR):
            r.artifacts = ctx.collect_failure_artifacts(c.id)
            if r.artifacts:
                print(f"    ↳ 已收集产物：{len(r.artifacts)} 项")

    payload = R.build_payload(cfg, env, results)
    R.write_json(os.path.join(out_dir, "report.json"), payload)
    R.write_markdown(os.path.join(out_dir, "report.md"), payload)
    R.print_console(payload)
    print(f"\n报告：{os.path.join(out_dir, 'report.md')}")
    print(f"机器可读：{os.path.join(out_dir, 'report.json')}")

    n = payload["verdict"]["counts"]
    if n[FAIL] or n[ERROR]:
        return 1
    if n[SKIP]:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
