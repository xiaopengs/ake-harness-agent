"""
环境预检（M0）。

★ 这里的唯一职责是「把缺的东西列清楚」，不是「让流程继续下去」。
  预检不过 ⇒ 后面所有设备用例 SKIP ⇒ 报告顶上红字写明缺什么。
  它**不会**为了让报告好看而把缺失改写成通过。
"""

from __future__ import annotations

import os
import platform
import sqlite3
import sys
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .adb import Adb, Device, find_adb
from .apk import inspect as inspect_apk
from .ctx import Ctx
from .model import Assertion
from .shell import run


@dataclass
class EnvReport:
    adb_path: str = ""
    adb_version: str = ""
    devices: List[Device] = field(default_factory=list)
    device: Optional[Device] = None
    props: Dict[str, str] = field(default_factory=dict)
    mem_total_mb: Optional[int] = None
    apk_path: str = ""
    apk_exists: bool = False
    apk_size: int = 0
    apk_signed_v2_v3: bool = False
    apk_sig_blocks: List[str] = field(default_factory=list)
    apk_error: str = ""
    python_version: str = ""
    sqlite_version: str = ""
    apksigner: str = ""
    problems: List[str] = field(default_factory=list)
    info: Dict[str, Any] = field(default_factory=dict)

    @property
    def ready(self) -> bool:
        return not self.problems

    def as_dict(self) -> Dict[str, Any]:
        return {
            "adb_path": self.adb_path,
            "adb_version": self.adb_version,
            "devices": [{"serial": d.serial, "state": d.state, "model": d.model} for d in self.devices],
            "device": {"serial": self.device.serial, "model": self.device.model} if self.device else None,
            "props": self.props,
            "mem_total_mb": self.mem_total_mb,
            "apk_path": self.apk_path,
            "apk_exists": self.apk_exists,
            "apk_size": self.apk_size,
            "apk_signed_v2_v3": self.apk_signed_v2_v3,
            "apk_sig_blocks": self.apk_sig_blocks,
            "apk_error": self.apk_error,
            "python_version": self.python_version,
            "sqlite_version": self.sqlite_version,
            "apksigner": self.apksigner,
            "problems": self.problems,
            "info": self.info,
        }


def build_env(cfg) -> EnvReport:
    env = EnvReport()
    env.python_version = sys.version.split()[0] + f" ({platform.system()} {platform.machine()})"
    try:
        env.sqlite_version = sqlite3.sqlite_version
    except Exception:  # noqa: BLE001
        env.sqlite_version = "unavailable"

    # ① adb
    env.adb_path = find_adb(cfg.adb_bin) or ""
    if not env.adb_path:
        env.problems.append(
            "adb 不存在：PATH 里没有 adb，ANDROID_HOME/ANDROID_SDK_ROOT 下也没有"
            " ⇒ 装 platform-tools，或用 --adb /path/to/adb 指定"
        )
    else:
        v = run([env.adb_path, "version"], timeout=20)
        env.adb_version = (v.out or v.err).strip().splitlines()[0] if (v.out or v.err) else ""

    # ② 设备
    adb: Optional[Adb] = None
    if env.adb_path:
        adb = Adb(env.adb_path, cfg.serial or None)
        r = adb.run(["devices", "-l"], timeout=20)
        if not r.ok and r.rc != 0:
            env.problems.append(f"adb devices 执行失败：{(r.out + r.err).strip()[:200]}")
        env.devices = adb.devices()
        usable = [d for d in env.devices if d.usable]
        if not env.devices:
            env.problems.append("adb devices 为空：没有连接任何设备（真机需开 USB 调试并授权）")
        elif not usable:
            states = ", ".join(f"{d.serial}={d.state}" for d in env.devices)
            env.problems.append(
                f"有设备但没有一台可用（{states}）"
                " ⇒ unauthorized 需在手机上确认指纹；offline 需重插数据线"
            )
        elif len(usable) > 1 and not cfg.serial:
            env.problems.append(
                "检测到多台可用设备（"
                + ", ".join(d.serial for d in usable)
                + "）⇒ 用 --serial 指定一台，否则结果不知道是哪台机器的"
            )
        else:
            env.device = usable[0]
            if adb:
                env.device.props = adb.props()
                env.mem_total_mb = adb.meminfo_total_mb()
                env.props = env.device.props
                if not env.props.get("ro.product.model"):
                    env.problems.append("getprop 读不到 ro.product.model ⇒ 机型信息缺失，SoC 矩阵无法归档")

    # ③ APK
    env.apk_path = cfg.apk or os.path.join(
        cfg.project_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk"
    )
    info = inspect_apk(env.apk_path, ["classes.dex", "AndroidManifest.xml", "resources.arsc"])
    env.apk_exists = info.exists
    env.apk_size = info.size
    env.apk_sig_blocks = info.sig_blocks
    env.apk_signed_v2_v3 = info.v2_or_v3
    env.apk_error = info.error
    if not info.exists:
        env.problems.append(f"待验 APK 不在场：{env.apk_path} ⇒ 先 assembleDebug 或 --apk 指定")
    elif not info.is_zip or info.error:
        env.problems.append(f"APK 不是合法 zip：{info.error}")
    elif not env.apk_signed_v2_v3:
        env.problems.append(
            f"APK 没有 v2/v3 签名块（只有 v1={info.v1}）⇒ 多数 Android 11+ 设备拒绝安装"
        )

    # ④ 宿主工具
    from .shell import which
    env.apksigner = which("apksigner") or ""
    env.info["apksigner_found"] = bool(env.apksigner)
    env.info["note"] = (
        "apksigner 只是佐证；没有它也能用 APK Signing Block 魔数判 v2/v3"
        if not env.apksigner else "apksigner 在场，签名核对以它为准"
    )
    env.info["adb"] = env.adb_path
    env._adb = adb  # type: ignore[attr-defined]
    return env


def case_m0(ctx: Ctx) -> List[Assertion]:
    """M0 · 环境预检本身也是一条用例，它自己必须有判据。"""
    env: EnvReport = ctx.env
    a: List[Assertion] = []
    a.append(ctx.assert_true(
        "adb 在场", bool(env.adb_path), "adb 可执行",
        env.adb_path or "未找到", cmd="which adb / $ANDROID_HOME/platform-tools/adb",
        out=env.adb_path or "not found",
    ))
    a.append(ctx.assert_true(
        "至少一台可用设备", env.device is not None, "state=device",
        f"{[(d.serial, d.state) for d in env.devices]}",
        cmd="adb devices -l", out=str([(d.serial, d.state) for d in env.devices]),
    ))
    a.append(ctx.assert_true(
        "getprop 可读出机型/SoC", bool(env.props.get("ro.product.model")),
        "ro.product.model 非空",
        f"model={env.props.get('ro.product.model','')} soc={env.props.get('ro.soc.model','') or env.props.get('ro.board.platform','')}",
        cmd="adb shell getprop", out=str(env.props),
    ))
    a.append(ctx.assert_true(
        "待验 APK 在场且已 v2/v3 签名", env.apk_exists and env.apk_signed_v2_v3,
        "文件存在 + 含 v2/v3 签名块",
        f"exists={env.apk_exists} size={env.apk_size} blocks={env.apk_sig_blocks}",
        cmd=f"ls -l {env.apk_path} + APK Sig Block 42 解析",
        out=f"{env.apk_size} B, sig={env.apk_sig_blocks}, err={env.apk_error}",
    ))
    return a
