"""
用例上下文：环境、能力门禁、取证工具。

★ 能力门禁（capability gate）是本套件「不自我欺骗」的执行机构。

   用例声明 needs=["device", "runas"] ⇒ 不满足就抛 Skip ⇒ 状态 SKIP。

   `Skip` 是异常，走的是和 PASS 完全不同的路径 —— 因此**不存在**「adb 不在就跳过并通过」
   这种写法的立足之地。要让它变成 PASS，必须有人显式删掉 `needs`，或者显式把
   `require()` 改成返回 True，而这两件事在 code review 里一眼可见。
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from .adb import Adb
from .dbprobe import DbProbe
from .logcat import Logcat
from .model import Assertion, Evidence, Skip
from .ui import Ui

PKG = "com.ake.harnessagent"
MAIN_ACTIVITY = f"{PKG}/.MainActivity"
DB_NAME = "ake_agent.db"

MISSING_HELP = {
    "adb": "adb 不在 PATH 且 ANDROID_HOME/ANDROID_SDK_ROOT 下也没有 ⇒ 装 platform-tools 或 --adb 指定路径",
    "device": "adb devices 里没有 state=device 的设备 ⇒ 插上手机开 USB 调试，多设备时用 --serial 指定",
    "apk": "待验 APK 不在场 ⇒ --apk 指定路径（默认 app/build/outputs/apk/debug/app-debug.apk）",
    "install": "设备上没装目标包 ⇒ 先跑 M3（或 --skip-install 关掉依赖安装的用例）",
    "runas": "run-as 不可用（release 包 / 非 debuggable）⇒ 换 debug 包，DB 类用例只能 SKIP",
    "dbwrite": "无法写入 databases/ ⇒ SELinux 或厂商限制，构造类用例（M23/M24/M25）无法执行",
    "model": "设备上没有模型文件 ⇒ 先跑 M7 或 --model-path 指定已推送的模型目录",
    "modelrun": "本次不跑推理 ⇒ 加 --with-model-run（并提供 --goal）",
    "oldapk": "缺 v2 老包 ⇒ --old-apk 指定；没有老包就无法在真机 SQLite 上验迁移",
    "releaseapk": "缺已签名 release 包 ⇒ --release-apk 指定",
    "manifest": "缺 manifest 服务地址 ⇒ --manifest-url",
    "goal": "缺任务 goal ⇒ --goal \"…\"",
    "targeturl": "缺目标页面 URL ⇒ --target-url",
    "targettext": "缺目标页面里预期出现的文字 ⇒ --target-text（M10 的判据必须可比对）",
    "expectedrecords": "缺预期抽取结果 ⇒ --expected-records（逗号分隔，M11 逐字比对用）",
    "paymentgoal": "缺支付类 goal ⇒ --payment-goal",
    "injectiongoal": "缺提示注入 goal ⇒ --injection-goal",
    "injectedurl": "缺注入者诱导的 URL ⇒ --injected-url",
    "budgetgoal": "缺必然超预算的 goal ⇒ --budget-goal",
    "wipetext": "缺一键清除按钮文案 ⇒ --wipe-button-text（该文案未在任何 strings.xml 中登记，必须现场确认后传入）",
    "uiautomator": "uiautomator dump 不可用 ⇒ UI 遍历类用例无法执行",
    "longrun": "未开启长跑 ⇒ --long（M19 需要连续 20 分钟）",
    "videodir": "缺演示视频目录 ⇒ --video-dir",
    "privacyurl": "缺隐私政策 URL ⇒ --privacy-url",
    "gradlew": "工程目录下没有 gradlew ⇒ --project-dir 指到 HarnessAgent 仓库根",
    "gradle_network": "Gradle 无法联网解析依赖 ⇒ 换有网的环境跑 M1，或接受 M1 为 SKIP",
    "retrytext": "缺重试按钮文案 ⇒ --retry-button-text（未在任何 strings.xml 登记，需现场确认）",
}


@dataclass
class Config:
    project_dir: str = "/workspace/HarnessAgent"
    apk: str = ""
    old_apk: str = ""
    release_apk: str = ""
    serial: str = ""
    adb_bin: str = ""
    out_dir: str = ""
    package: str = PKG
    db_name: str = DB_NAME
    # 输入（不给就用 SKIP，绝不猜）
    manifest_url: str = ""
    goal: str = ""
    target_url: str = ""
    target_text: str = ""
    expected_records: str = ""
    payment_goal: str = ""
    injection_goal: str = ""
    injected_url: str = ""
    budget_goal: str = ""
    wipe_button_text: str = ""
    privacy_url: str = ""
    video_dir: str = ""
    with_model_run: bool = False
    long_run: bool = False
    clean_install: bool = False
    build_apk: bool = False
    collect_bugreport: bool = True
    install_timeout: float = 600.0
    cold_start_timeout: float = 60.0
    # 触发方式
    auto_ui: bool = False
    task_timeout: float = 300.0
    # 各条用例的专项输入
    confirm_goal: str = ""
    agent_name: str = "无能力验证Agent"
    retry_button_text: str = ""


class Ctx:
    def __init__(self, cfg: Config, adb: Optional[Adb], env: Any):
        self.cfg = cfg
        self.adb = adb
        self.env = env
        self.package = cfg.package
        self.component = f"{cfg.package}/.MainActivity"
        self.out_dir = cfg.out_dir
        self.artifacts_dir = os.path.join(cfg.out_dir, "artifacts")
        os.makedirs(self.artifacts_dir, exist_ok=True)
        self.db_dir = os.path.join(cfg.out_dir, "db")
        os.makedirs(self.db_dir, exist_ok=True)
        self._caps: Dict[str, Tuple[bool, str]] = {}
        self._logcat: Optional[Logcat] = None
        self._ui: Optional[Ui] = None
        self._db: Optional[DbProbe] = None
        self.notes: List[str] = []

    # ── 工具实例 ──────────────────────────────────────────────────────
    def logcat(self) -> Logcat:
        if self._logcat is None:
            if self.adb is None:
                raise Skip("adb 不可用", ["adb"])
            self._logcat = Logcat(self.adb)
        return self._logcat

    def ui(self) -> Ui:
        if self._ui is None:
            if self.adb is None:
                raise Skip("adb 不可用", ["adb"])
            self._ui = Ui(self.adb, os.path.join(self.out_dir, "ui"))
        return self._ui

    def db(self) -> DbProbe:
        if self._db is None:
            if self.adb is None:
                raise Skip("adb 不可用", ["adb"])
            self._db = DbProbe(self.adb, self.package, self.cfg.db_name, self.db_dir)
        return self._db

    # ── 能力门禁 ──────────────────────────────────────────────────────
    def require(self, cap: str) -> None:
        ok, detail = self._resolve(cap)
        if not ok:
            help_text = MISSING_HELP.get(cap, "")
            raise Skip(f"缺少前置「{cap}」：{detail or help_text}".strip(), [cap])

    def has(self, cap: str) -> bool:
        return self._resolve(cap)[0]

    def _resolve(self, cap: str) -> Tuple[bool, str]:
        if cap in self._caps:
            return self._caps[cap]
        res = self._compute(cap)
        self._caps[cap] = res
        return res

    def _compute(self, cap: str) -> Tuple[bool, str]:
        env = self.env
        cfg = self.cfg
        adb = self.adb

        if cap == "adb":
            return (adb is not None, "" if adb else "adb 未找到")
        if cap == "device":
            if adb is None:
                return False, "adb 未找到"
            d = env.device
            return (d is not None, f"{d.serial} {d.model}" if d else "adb devices 无可用设备")
        if cap == "apk":
            # 未显式给 --apk 时，用 envcheck 解析出来的默认产物路径
            p = cfg.apk or getattr(env, "apk_path", "")
            if not p:
                return False, "未指定 --apk"
            return (os.path.isfile(p), p if os.path.isfile(p) else f"{p} 不存在")
        if cap == "install":
            if adb is None:
                return False, "adb 未找到"
            r = adb.shell(f"pm list packages {self.package}", timeout=20)
            ok = self.package in (r.out or "")
            return ok, (r.out.strip() or "包未安装")
        if cap == "runas":
            if adb is None:
                return False, "adb 未找到"
            return self.db().can_run_as()
        if cap == "dbwrite":
            if adb is None:
                return False, "adb 未找到"
            return self.db().can_write()
        if cap == "model":
            if adb is None:
                return False, "adb 未找到"
            r = adb.shell(f"run-as {self.package} ls -l files/models 2>/dev/null || echo NO_MODELS", timeout=30)
            out = (r.out or "").strip()
            ok = bool(out) and "NO_MODELS" not in out and "No such file" not in out
            return ok, out[:300]
        if cap == "modelrun":
            return cfg.with_model_run, ("已开启 --with-model-run" if cfg.with_model_run else "未开启")
        if cap == "oldapk":
            return (bool(cfg.old_apk) and os.path.isfile(cfg.old_apk), cfg.old_apk or "未指定")
        if cap == "releaseapk":
            return (bool(cfg.release_apk) and os.path.isfile(cfg.release_apk), cfg.release_apk or "未指定")
        if cap == "manifest":
            return bool(cfg.manifest_url), cfg.manifest_url
        if cap == "goal":
            return bool(cfg.goal), cfg.goal
        if cap == "targeturl":
            return bool(cfg.target_url), cfg.target_url
        if cap == "targettext":
            return bool(cfg.target_text), cfg.target_text
        if cap == "expectedrecords":
            return bool(cfg.expected_records), cfg.expected_records
        if cap == "paymentgoal":
            return bool(cfg.payment_goal), cfg.payment_goal
        if cap == "injectiongoal":
            return bool(cfg.injection_goal), cfg.injection_goal
        if cap == "injectedurl":
            return bool(cfg.injected_url), cfg.injected_url
        if cap == "budgetgoal":
            return bool(cfg.budget_goal), cfg.budget_goal
        if cap == "wipetext":
            return bool(cfg.wipe_button_text), cfg.wipe_button_text
        if cap == "uiautomator":
            if adb is None:
                return False, "adb 未找到"
            return self.ui().available()
        if cap == "longrun":
            return cfg.long_run, ("已开启 --long" if cfg.long_run else "未开启")
        if cap == "videodir":
            if not cfg.video_dir:
                return False, "未指定 --video-dir"
            if not os.path.isdir(cfg.video_dir):
                return False, f"{cfg.video_dir} 不是目录"
            n = len([f for f in os.listdir(cfg.video_dir)
                     if f.lower().endswith((".mp4", ".webm"))])
            return (n >= 2, f"{cfg.video_dir} 内视频 {n} 个（需 >= 2）")
        if cap == "privacyurl":
            return bool(cfg.privacy_url), cfg.privacy_url
        if cap == "retrytext":
            return bool(cfg.retry_button_text), cfg.retry_button_text
        if cap == "gradlew":
            p = os.path.join(cfg.project_dir, "gradlew")
            return os.path.isfile(p), p
        return False, f"未知能力 {cap}"

    # ── 取证 ─────────────────────────────────────────────────────────
    def ev(self, label: str, cmd: str = "", out: str = "") -> Evidence:
        return Evidence(label=label, cmd=cmd, out=out)

    def assert_true(self, name: str, ok: bool, expected: str, actual: str,
                    cmd: str = "", out: str = "", note: str = "", calibrated: bool = True) -> Assertion:
        return Assertion(
            name=name, ok=bool(ok), expected=expected, actual=actual,
            evidence_cmd=cmd, evidence_out=out or actual, note=note, calibrated=calibrated,
        )

    def collect_failure_artifacts(self, case_id: str) -> List[str]:
        """失败时一键收集：logcat / dropbox / meminfo /（可选）bugreport。"""
        made: List[str] = []
        if self.adb is None:
            return made
        base = os.path.join(self.artifacts_dir, case_id)
        os.makedirs(base, exist_ok=True)
        jobs = [
            ("logcat.txt", ["logcat", "-v", "time", "-d"], 120.0),
            ("dumpsys_meminfo.txt", ["shell", f"dumpsys meminfo {self.package}"], 120.0),
            ("dropbox.txt", ["shell", "dumpsys dropbox --print data_app_crash data_app_anr"], 180.0),
            ("dumpsys_package.txt", ["shell", f"dumpsys package {self.package}"], 120.0),
            ("ps.txt", ["shell", f"ps -A | grep {self.package}"], 30.0),
        ]
        for fname, args, tmo in jobs:
            try:
                r = self.adb.run(args, timeout=tmo)
                p = os.path.join(base, fname)
                with open(p, "w", encoding="utf-8", errors="replace") as f:
                    f.write(f"$ adb {' '.join(args)}\n\n{r.out}\n{r.err}")
                made.append(p)
            except Exception as e:  # noqa: BLE001
                made.append(f"{fname}: 收集失败 {e}")
        if self.cfg.collect_bugreport:
            try:
                p = os.path.join(base, "bugreport.zip")
                r = self.adb.bugreport(p, timeout=900)
                made.append(p if r.ok else f"bugreport 失败: {r.err.strip()[:200]}")
            except Exception as e:  # noqa: BLE001
                made.append(f"bugreport 异常: {e}")
        return made


def cold_start(ctx: Ctx, wait_logcat_ms: int = 8000) -> Dict[str, Any]:
    """统一冷启动作：force-stop → 清日志 → am start -W → 等冷启日志。"""
    adb = ctx.adb
    adb.shell(f"am force-stop {ctx.package}")
    time.sleep(0.6)
    lc = ctx.logcat()
    lc.clear()
    r = adb.shell(f"am start -W -n {ctx.component} -a android.intent.action.MAIN", timeout=90)
    deadline = time.time() + max(1.0, wait_logcat_ms / 1000.0)
    line = None
    while time.time() < deadline:
        lc.dump()
        line = lc.first(r"cold start done", tag="ColdStart")
        if line:
            break
        time.sleep(0.4)
    lc.dump()
    return {"start": r, "cold_start_line": line, "logcat": lc}
