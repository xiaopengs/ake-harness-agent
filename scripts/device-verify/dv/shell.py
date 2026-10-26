"""本机命令执行。一律带超时，一律留原样输出（不给判据用，只给证据用）。"""

from __future__ import annotations

import os
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence


@dataclass
class CmdResult:
    rc: int
    out: str
    err: str
    cmd: str
    duration_ms: int = 0
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        return self.rc == 0 and not self.timed_out

    @property
    def combined(self) -> str:
        parts = []
        if self.out:
            parts.append(self.out)
        if self.err:
            parts.append(self.err)
        return "\n".join(parts).strip()

    def as_dict(self) -> Dict:
        return {
            "rc": self.rc,
            "cmd": self.cmd,
            "duration_ms": self.duration_ms,
            "timed_out": self.timed_out,
            "out": self.out,
            "err": self.err,
        }


def which(name: str) -> Optional[str]:
    return shutil.which(name)


def run(
    argv: Sequence[str],
    timeout: float = 60.0,
    cwd: Optional[str] = None,
    env: Optional[Dict[str, str]] = None,
    stdin: Optional[bytes] = None,
) -> CmdResult:
    started = time.time()
    cmd_str = " ".join(_q(a) for a in argv)
    try:
        p = subprocess.run(
            list(argv),
            capture_output=True,
            timeout=timeout,
            cwd=cwd,
            env=env,
            input=stdin,
        )
        rc, out, err = p.returncode, _dec(p.stdout), _dec(p.stderr)
        timed_out = False
    except FileNotFoundError:
        rc, out, err, timed_out = 127, "", f"command not found: {argv[0]}", False
    except subprocess.TimeoutExpired as e:
        rc = 124
        out = _dec(e.stdout or b"")
        err = _dec(e.stderr or b"") + f"\n[timeout after {timeout}s]"
        timed_out = True
    return CmdResult(
        rc=rc,
        out=out,
        err=err,
        cmd=cmd_str,
        duration_ms=int((time.time() - started) * 1000),
        timed_out=timed_out,
    )


def _dec(b) -> str:
    if b is None:
        return ""
    if isinstance(b, bytes):
        return b.decode("utf-8", errors="replace")
    return str(b)


def _q(s: str) -> str:
    return s if (" " not in s and '"' not in s) else '"' + s + '"'
