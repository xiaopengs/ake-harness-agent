"""
adb 封装。

★ 立场：本套件**不允许**在 adb 缺失时「优雅退化」。
  adb 不在 ⇒ `Adb.find()` 返回 None ⇒ 环境预检报缺失 ⇒ 所有设备用例 SKIP。
  任何 `if adb is None: return PASS` 在本套件里都是 bug，不是容错。
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

from .shell import CmdResult, run, which


def find_adb(explicit: Optional[str] = None) -> Optional[str]:
    """找 adb。找不到就返回 None —— 调用方必须据此报「缺什么」，不许假装能跑。"""
    if explicit:
        return explicit if os.path.isfile(explicit) else None
    p = which("adb")
    if p:
        return p
    for env_key in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_ROOT"):
        root = os.environ.get(env_key)
        if root:
            cand = os.path.join(root, "platform-tools", "adb")
            if os.path.isfile(cand):
                return cand
    for cand in ("/opt/android-sdk/platform-tools/adb", "/usr/lib/android-sdk/platform-tools/adb"):
        if os.path.isfile(cand):
            return cand
    return None


@dataclass
class Device:
    serial: str
    state: str
    model: str = ""
    props: Dict[str, str] = field(default_factory=dict)

    @property
    def usable(self) -> bool:
        return self.state == "device"


class Adb:
    def __init__(self, binary: str, serial: Optional[str] = None, default_timeout: float = 30.0):
        self.binary = binary
        self.serial = serial
        self.default_timeout = default_timeout

    # ── 底层 ──────────────────────────────────────────────────────────
    def _argv(self, args: Sequence[str]) -> List[str]:
        argv = [self.binary]
        if self.serial:
            argv += ["-s", self.serial]
        argv += list(args)
        return argv

    def run(self, args: Sequence[str], timeout: Optional[float] = None) -> CmdResult:
        return run(self._argv(args), timeout=timeout or self.default_timeout)

    def shell(self, cmd: str, timeout: Optional[float] = None) -> CmdResult:
        return self.run(["shell", cmd], timeout=timeout)

    def exec_out(self, cmd: str, timeout: Optional[float] = None) -> CmdResult:
        return self.run(["exec-out", cmd], timeout=timeout)

    # ── 设备发现 ──────────────────────────────────────────────────────
    def devices(self) -> List[Device]:
        r = self.run(["devices", "-l"], timeout=15)
        out: List[Device] = []
        for line in r.out.splitlines()[1:]:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            model = ""
            for p in parts[2:]:
                if p.startswith("model:"):
                    model = p.split(":", 1)[1]
            out.append(Device(serial=serial, state=state, model=model))
        return out

    def usable_devices(self) -> List[Device]:
        return [d for d in self.devices() if d.usable]

    # ── 属性 ─────────────────────────────────────────────────────────
    PROP_KEYS = [
        "ro.product.model",
        "ro.product.device",
        "ro.product.manufacturer",
        "ro.board.platform",
        "ro.soc.model",
        "ro.soc.manufacturer",
        "ro.hardware",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "dalvik.vm.heapsize",
        "ro.debuggable",
    ]

    def props(self, keys: Optional[Sequence[str]] = None) -> Dict[str, str]:
        keys = list(keys or self.PROP_KEYS)
        r = self.shell("getprop")
        props: Dict[str, str] = {}
        for line in r.out.splitlines():
            m = re.match(r"\[([^\]]+)\]:\s*\[(.*)\]", line)
            if m:
                props[m.group(1)] = m.group(2)
        return {k: props.get(k, "") for k in keys}

    def meminfo_total_mb(self) -> Optional[int]:
        r = self.shell("cat /proc/meminfo")
        m = re.search(r"MemTotal:\s+(\d+)\s*kB", r.out)
        return int(m.group(1)) // 1024 if m else None

    # ── 常用动作 ─────────────────────────────────────────────────────
    def install(self, apk: str, timeout: float = 300.0, reinstall: bool = True) -> CmdResult:
        args = ["install"]
        if reinstall:
            args.append("-r")
        args += ["-t", apk]
        return self.run(args, timeout=timeout)

    def uninstall(self, package: str) -> CmdResult:
        return self.run(["uninstall", package], timeout=120)

    def force_stop(self, package: str) -> CmdResult:
        return self.shell(f"am force-stop {package}")

    def start_activity(self, component: str, wait: bool = True, extra: str = "") -> CmdResult:
        args = "am start"
        if wait:
            args += " -W"
        args += f" -n {component}"
        if extra:
            args += " " + extra
        return self.shell(args, timeout=60)

    def pidof(self, package: str) -> Optional[int]:
        r = self.shell(f"pidof {package}")
        m = re.search(r"\d+", r.out or "")
        return int(m.group(0)) if m else None

    def dumpsys(self, what: str, timeout: float = 60.0) -> CmdResult:
        return self.shell(f"dumpsys {what}", timeout=timeout)

    def tap(self, x: int, y: int) -> CmdResult:
        return self.shell(f"input tap {x} {y}")

    def input_text(self, text: str) -> CmdResult:
        # 空格要转义，否则 shell 会把句子切成多个参数
        safe = text.replace(" ", "%s").replace("'", "").replace('"', "")
        return self.shell(f"input text {safe}")

    def uiautomator_dump(self, remote: str = "/sdcard/ake_verify_window.xml") -> CmdResult:
        return self.shell(f"uiautomator dump {remote}", timeout=60)

    def pull(self, remote: str, local: str, timeout: float = 300.0) -> CmdResult:
        return self.run(["pull", remote, local], timeout=timeout)

    def push(self, local: str, remote: str, timeout: float = 300.0) -> CmdResult:
        return self.run(["push", local, remote], timeout=timeout)

    def bugreport(self, local_path: str, timeout: float = 600.0) -> CmdResult:
        return self.run(["bugreport", local_path], timeout=timeout)
