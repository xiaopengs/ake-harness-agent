"""
M1 / M2 / M2b / M3 / M4 / M5 / M6 —— 阶段一：打通产物的存在性。

这一段是整份门禁里性价比最高的：不需要 CDN、不需要模型、不需要 release 签名，
**只要一台手机 + 15 分钟**。对一个从没出过包的工程，M4 就失败是完全正常的。
"""

from __future__ import annotations

import os
import re
import sqlite3
from typing import List

from .. import helpers as H
from ..apk import inspect as inspect_apk
from ..ctx import Ctx, cold_start
from ..model import Assertion, Case, Skip

PHASE = "阶段一 · 产物存在性"

NETWORK_HINTS = (
    "Could not resolve",
    "No cached version",
    "offline mode",
    "Network is unreachable",
    "Could not download",
)


def _libs_kotlin_version(project_dir: str) -> str:
    path = os.path.join(project_dir, "gradle", "libs.versions.toml")
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                m = re.match(r'^\s*kotlin\s*=\s*"([^"]+)"', line)
                if m:
                    return m.group(1)
    except OSError:
        pass
    return ""


def _resolved_version(out: str, prefix: str):
    """
    从 gradle 依赖树里取**解析后**的版本。

    ★ 这里踩过一次坑（值得记下来）：`buildEnvironment` 的输出里
        `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 -> 2.3.21`
      意思是「有人要 2.2.10，Gradle 冲突解析成 2.3.21」。第一版脚本取了箭头**左边**，
      于是报出「声明 2.3.21 / 实际 2.2.10」这种假 FAIL ——
      那不是工程的问题，是脚本的问题。判据必须读箭头右边。
    """
    rx = re.compile(prefix + r"([0-9][^\s\(\)]*)(?:\s*->\s*([0-9][^\s\(\)]*))?")
    got = {}
    for m in rx.finditer(out or ""):
        resolved = m.group(2) or m.group(1)
        got[resolved] = got.get(resolved, 0) + 1
    if not got:
        return None
    # 取出现次数最多的那个解析结果（= Gradle 真正的统一解析值）
    best = max(got.items(), key=lambda kv: kv[1])[0]

    class _M:
        def group(self, i):
            return best

        def __bool__(self):
            return True

    return _M()


# ─────────────────────────────────────────────────────── M1
def m01(ctx: Ctx) -> List[Assertion]:
    """M1 · 解析 AGP 实际解析出来的 Kotlin/KGP 版本。"""
    ctx.require("gradlew")
    prj = ctx.cfg.project_dir
    declared = _libs_kotlin_version(prj)

    from ..shell import run as _run
    res = _run([os.path.join(prj, "gradlew"), "-p", prj, "buildEnvironment"],
               timeout=900, cwd=prj)
    out = (res.out or "") + (res.err or "")
    if not res.ok and any(h in out for h in NETWORK_HINTS):
        raise Skip("Gradle 无法联网解析依赖（离线环境）⇒ 版本栈结论无法在本机取得", ["gradle_network"])

    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "gradlew buildEnvironment 执行成功", res.ok, "rc=0",
        f"rc={res.rc} {res.duration_ms}ms",
        cmd=f"cd {prj} && ./gradlew buildEnvironment",
        out=out[-2000:],
    ))
    kgp = _resolved_version(out, r"org\.jetbrains\.kotlin:kotlin-gradle-plugin:")
    a.append(ctx.assert_true(
        "KGP 版本可解析", kgp is not None,
        "输出中出现 org.jetbrains.kotlin:kotlin-gradle-plugin:<version>",
        kgp.group(1) if kgp else "未出现",
        cmd="./gradlew buildEnvironment | grep kotlin-gradle-plugin",
        out="\n".join(l for l in out.splitlines() if "kotlin-gradle-plugin" in l)[:1200] or "(无命中)",
    ))
    agp = _resolved_version(out, r"com\.android\.tools\.build:gradle:")
    a.append(ctx.assert_true(
        "AGP 版本可解析", agp is not None,
        "输出中出现 com.android.tools.build:gradle:<version>",
        agp.group(1) if agp else "未出现",
        cmd="./gradlew buildEnvironment | grep com.android.tools.build:gradle",
        out="\n".join(l for l in out.splitlines() if "com.android.tools.build:gradle" in l)[:800] or "(无命中)",
    ))
    if declared and kgp:
        a.append(ctx.assert_true(
            "KGP 实际版本 == libs.versions.toml 声明版本", kgp.group(1) == declared,
            declared, kgp.group(1),
            cmd="对比 gradle/libs.versions.toml 的 kotlin 与 buildEnvironment 的解析结果",
            out=f"declared={declared} resolved={kgp.group(1)}",
        ))
    return a


# ─────────────────────────────────────────────────────── M2
def m02(ctx: Ctx) -> List[Assertion]:
    """M2 · debug APK 产物在场且是能装的形态。"""
    apk = ctx.env.apk_path
    info = inspect_apk(apk, ["classes.dex", "AndroidManifest.xml", "resources.arsc"])
    if not info.exists and ctx.cfg.build_apk:
        from ..shell import run as _run
        _run([os.path.join(ctx.cfg.project_dir, "gradlew"),
              "-p", ctx.cfg.project_dir, ":app:assembleDebug"], timeout=1800)
        info = inspect_apk(apk, ["classes.dex", "AndroidManifest.xml"])

    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "APK 文件存在", info.exists, "文件存在", f"{apk} exists={info.exists}",
        cmd=f"ls -l {apk}", out=info.error or f"{info.size} B",
    ))
    a.append(ctx.assert_true(
        "是合法 zip 且含 classes.dex + AndroidManifest.xml",
        info.is_zip and "classes.dex" in info.entries and "AndroidManifest.xml" in info.entries,
        "zip 合法且含 classes.dex / AndroidManifest.xml",
        f"is_zip={info.is_zip} entries={info.entries}",
        cmd=f"unzip -l {apk} | grep -E 'classes.dex|AndroidManifest.xml'",
        out=f"entries={info.entries} err={info.error}",
    ))
    a.append(ctx.assert_true(
        "体积在合理区间（>1MB）", info.size > 1_000_000, "> 1,000,000 B", f"{info.size} B",
        cmd="stat -c %s", out=f"{info.size}",
    ))
    return a


# ─────────────────────────────────────────────────────── M2b
def m02b(ctx: Ctx) -> List[Assertion]:
    """M2b · debug APK 已签名 ⇒ 不需要任何额外步骤就能 adb install。"""
    apk = ctx.env.apk_path
    info = inspect_apk(apk)
    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "含 v2/v3 签名块", info.v2_or_v3, "APK Signing Block 里有 v2 或 v3",
        f"blocks={info.sig_blocks} v1={info.v1} 未识别ID={info.unknown_ids} "
        f"block结构完好={info.block_wellformed}",
        cmd="解析 APK Signing Block（APK Sig Block 42 魔数 + ID-Value 对）",
        out=f"blocks={info.sig_blocks} v1={info.v1} unknown={info.unknown_ids} "
            f"wellformed={info.block_wellformed} err={info.error}",
        note="ID 常量是解析本仓库真实 APK 得到的（debug=[v2], release=[v2,v3]），不从记忆抄；"
             "0x42726577 是 verity 填充不是签名，已排除；未识别的 ID 只列出不算签名",
    ))
    if ctx.env.apksigner:
        from ..shell import run as _run
        r = _run([ctx.env.apksigner, "verify", "--print-certs", apk], timeout=180)
        a.append(ctx.assert_true(
            "apksigner verify 通过", r.ok, "rc=0", f"rc={r.rc}",
            cmd=f"apksigner verify --print-certs {apk}",
            out=((r.out or "") + (r.err or ""))[-1500:],
        ))
    return a


# ─────────────────────────────────────────────────────── M3
def m03(ctx: Ctx) -> List[Assertion]:
    """M3 · APK 可安装。"""
    adb, apk = ctx.adb, ctx.env.apk_path
    if ctx.cfg.clean_install:
        adb.uninstall(ctx.package)

    r = adb.install(apk, timeout=ctx.cfg.install_timeout)
    out = (r.out or "") + (r.err or "")
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "INSTALL_FAILED_VERSION_DOWNGRADE" in out:
        adb.uninstall(ctx.package)
        r = adb.install(apk, timeout=ctx.cfg.install_timeout)
        out = (r.out or "") + (r.err or "")

    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "adb install 返回 Success", r.ok and "Success" in out, "输出含 Success",
        out.strip()[:600],
        cmd=f"adb install -r -t {apk}", out=out[-2000:],
    ))
    lp = adb.shell(f"pm list packages {ctx.package}", timeout=30)
    a.append(ctx.assert_true(
        "包出现在 pm list packages", ctx.package in (lp.out or ""), ctx.package,
        (lp.out or "").strip() or "(空)",
        cmd=f"adb shell pm list packages {ctx.package}", out=lp.out or "(空)",
    ))
    ver, vcmd = H.installed_version(ctx)
    a.append(ctx.assert_true(
        "能读到 versionCode/versionName", bool(ver), "dumpsys package 有版本信息",
        ver[:300] or "(空)", cmd=vcmd, out=ver or "(空)",
    ))
    return a


# ─────────────────────────────────────────────────────── M4
def m04(ctx: Ctx) -> List[Assertion]:
    """M4 · App 可冷启动到首页：无崩溃、无 ANR、真的到了前台。"""
    res = cold_start(ctx, wait_logcat_ms=int(ctx.cfg.cold_start_timeout * 1000))
    start, lc = res["start"], res["logcat"]
    out = (start.out or "") + (start.err or "")

    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "am start 返回 Status: ok", "Status: ok" in out, "Status: ok",
        "\n".join(l for l in out.splitlines() if "Status" in l) or out[:300],
        cmd=f"adb shell am start -W -n {ctx.component}", out=out[-1500:],
    ))
    a.append(ctx.assert_true(
        "启动没有超时", "Timeout" not in out, "输出中不含 Timeout",
        "含 Timeout" if "Timeout" in out else "无 Timeout",
        cmd="同上", out=out[-1500:],
    ))
    tt = H.parse_am_time(out)
    a.append(ctx.assert_true(
        "能读到 ThisTime（启动耗时）", tt is not None, "ThisTime: <数字>",
        f"ThisTime={tt} TotalTime={H.parse_am_total(out)}",
        cmd="同上", out=out[-1500:],
    ))
    a.append(H.no_crash_assertion(ctx, lc))
    pid = ctx.adb.pidof(ctx.package)
    a.append(ctx.assert_true(
        "进程存活", pid is not None, "pidof 返回 pid", f"pid={pid}",
        cmd=f"adb shell pidof {ctx.package}", out=f"{pid}",
    ))
    ok, resum, rcmd = H.resumed_is_ours(ctx)
    a.append(ctx.assert_true(
        "目标 Activity 处于前台", ok, f"前台 Activity 属于 {ctx.package}",
        resum[:400] or "(空)", cmd=rcmd, out=resum or "(空)",
        calibrated=False,
        note="【待真机校准】dumpsys 字段名随 Android 版本变化（mResumedActivity / topResumedActivity），"
             "若本条 FAIL 但前 5 条 PASS，多半是字段名问题而非真没起来 —— 请贴出 out 里的原始行再判断",
    ))
    return a


# ─────────────────────────────────────────────────────── M5
def m05(ctx: Ctx) -> List[Assertion]:
    """M5 · Room 建库成功：真机 SQLite 上真的建出 v3 schema。"""
    cold_start(ctx, wait_logcat_ms=int(ctx.cfg.cold_start_timeout * 1000))
    dbp = ctx.db()
    ls = dbp.ls_dir()
    pr = dbp.pull("m05")

    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "设备上 databases/ 里存在 ake_agent.db",
        pr.ok and os.path.isfile(pr.local_db) and os.path.getsize(pr.local_db) > 0,
        "文件存在且非空",
        f"local={pr.local_db} size={os.path.getsize(pr.local_db) if os.path.isfile(pr.local_db) else 0} "
        f"wal={pr.wal_bytes} shm={pr.shm_bytes} err={pr.error}",
        cmd=f"adb shell run-as {ctx.package} ls -l databases",
        out=(ls.out or "")[:800] + "\n" + "\n".join(pr.steps),
    ))
    if not pr.ok:
        return a

    try:
        con = sqlite3.connect(pr.local_db)
        uv = con.execute("PRAGMA user_version").fetchone()[0]
        integ = con.execute("PRAGMA integrity_check").fetchone()[0]
        jm = con.execute("PRAGMA journal_mode").fetchone()[0]
        con.close()
    except sqlite3.Error as e:
        a.append(ctx.assert_true("拉取的库能被 sqlite 打开", False, "sqlite3 可打开",
                                 f"打开失败: {e}", cmd=f"sqlite3 {pr.local_db}", out=str(e)))
        return a

    a.append(ctx.assert_true(
        "PRAGMA user_version == 3", uv == 3, "3", str(uv),
        cmd="PRAGMA user_version", out=str(uv),
    ))
    a.append(ctx.assert_true(
        "PRAGMA integrity_check == ok", str(integ).lower() == "ok", "ok", str(integ),
        cmd="PRAGMA integrity_check", out=str(integ),
    ))
    tables = dbp.tables(pr.local_db)
    a.append(ctx.assert_true(
        "表数量 == 19（v2 的 13 张 + v3 新增 6 张）", len(tables) == 19, "19",
        f"{len(tables)}: {tables}",
        cmd="SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
            "AND name NOT LIKE 'room_%'",
        out=str(tables),
        note="【待真机校准】若 Room 额外建了 room_master_table 之外的辅助表，本条可能误 FAIL；"
             "真机第一次跑请先看 out 里的完整表名清单再决定是否放宽",
        calibrated=False,
    ))
    has_v3 = {"chat_message", "chat_session", "agent_profile",
              "agent_capability_grant", "template_registry", "route_decision"} <= set(tables)
    a.append(ctx.assert_true(
        "v3 六张新表全部存在", has_v3,
        "chat_message/chat_session/agent_profile/agent_capability_grant/template_registry/route_decision 全在",
        f"缺失: {sorted({'chat_message','chat_session','agent_profile','agent_capability_grant','template_registry','route_decision'} - set(tables))}",
        cmd="同上", out=str(tables),
    ))
    ctx.notes.append(f"journal_mode={jm}（仅记录，不作判据；Room 开的是 WRITE_AHEAD_LOGGING）")
    return a


# ─────────────────────────────────────────────────────── M6
TABS = ["对话", "Agent", "任务", "设置"]


def m06(ctx: Ctx) -> List[Assertion]:
    """M6 · Hilt 运行时图完整：冷启 + 遍历底栏四个 tab，全程不炸。"""
    cold_start(ctx, wait_logcat_ms=int(ctx.cfg.cold_start_timeout * 1000))
    lc = ctx.logcat()
    a: List[Assertion] = []

    for tab in TABS:
        lc.clear()
        ok, msg = ctx.ui().tap_text(tab)
        a.append(ctx.assert_true(
            f"能点到「{tab}」tab", ok, "uiautomator dump 找得到该文案并 tap 成功", msg,
            cmd=f"adb shell uiautomator dump + input tap（文案取自 ui/src/main/res/values/strings.xml）",
            out=msg,
            calibrated=False,
            note="【待真机校准】Compose 节点是否把 text 暴露给 uiautomator 视 content-desc 而定；"
                 "找不到节点 ⇒ SKIP/FAIL，不因为「没崩」就判过",
        ))
        if not ok:
            continue
        import time as _t
        _t.sleep(1.2)
        lc.dump()
        ours, resum, rcmd = H.resumed_is_ours(ctx)
        a.append(ctx.assert_true(
            f"进入「{tab}」后仍在本 App", ours, f"前台仍是 {ctx.package}",
            resum[:300] or "(空)", cmd=rcmd, out=resum or "(空)", calibrated=False,
        ))
        a.append(H.no_crash_assertion(ctx, lc, name=f"「{tab}」页面无崩溃"))

    lc.dump()
    di = lc.grep(r"Hilt|No injection site|NoClassDefFoundError|Unable to create|"
                 r"IllegalArgumentException|lateinit property .* has not been initialized")
    a.append(ctx.assert_true(
        "全程无 DI/初始化类异常", not di,
        "logcat 无 Hilt / No injection site / lateinit 未初始化 等",
        f"命中 {len(di)} 条",
        cmd="adb logcat -v time -d | grep -E 'Hilt|No injection site|lateinit|Unable to create'",
        out="\n".join(l.text for l in di[:20]) or "(无命中)",
        note="★ 本条是 M6 的要害：release 包 663 个 Hilt *_Factory 被 R8 树摇（文档 §2.9 N-5），"
             "冷启能过不代表进二级页面还能过",
    ))
    return a


CASES = [
    Case(id="M1", title="解析 AGP 实际 KGP 版本", phase=PHASE, fn=m01, needs=["gradlew"],
         doc="docs/04-release-gate.md §5.2 阶段一"),
    Case(id="M2", title="debug APK 产物在场", phase=PHASE, fn=m02, needs=[],
         doc="docs/04-release-gate.md §5.2 阶段一"),
    Case(id="M2b", title="debug APK 已 v2/v3 签名（可直接装）", phase=PHASE, fn=m02b, needs=[],
         doc="docs/04-release-gate.md §5.2 阶段一"),
    Case(id="M3", title="APK 可安装", phase=PHASE, fn=m03, needs=["adb", "device", "apk"],
         doc="docs/04-release-gate.md §5.2 阶段一"),
    Case(id="M4", title="App 可冷启动到首页（无崩溃/无 ANR）", phase=PHASE,
         fn=m04, needs=["adb", "device", "install"],
         doc="docs/04-release-gate.md §5.2 阶段一",
         calibration=["am start -W 的 ThisTime/TotalTime 各厂商都可能少打，用 Status/Timeout 兜底"]),
    Case(id="M5", title="Room 建库成功（真机 SQLite 上是 v3 schema）", phase=PHASE,
         fn=m05, needs=["adb", "device", "install", "runas"],
         doc="docs/04-release-gate.md §5.2 阶段一",
         calibration=["表数量 19 的判据来自 @Database 的 19 个 entity；Room 是否额外建辅助表需真机确认",
                      "WAL 是否已 checkpoint 会影响行数，脚本已强制 wal_checkpoint(TRUNCATE)"]),
    Case(id="M6", title="Hilt 运行时图完整（冷启 + 遍历四个 tab）", phase=PHASE,
         fn=m06, needs=["adb", "device", "install", "uiautomator"],
         doc="docs/04-release-gate.md §5.2 阶段一 / §2.9 N-5",
         calibration=["底栏文案来自 strings.xml：对话/Agent/任务/设置",
                      "Compose 节点是否出现在 uiautomator dump 里未经验证"]),
]
