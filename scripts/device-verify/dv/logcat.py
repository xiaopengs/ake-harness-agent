"""logcat 抓取与判读。

用法：用例开头 `lc = ctx.logcat(); lc.clear()`，动作跑完 `lc.dump()` 拿到这一段时间的日志。
`-c` + `-d` 的组合保证「抓到的就是这次动作产生的」，不需要靠时间戳猜。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List, Optional

LINE = re.compile(
    r"^(?P<ts>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+"
    r"(?P<level>[VDIWEF])\s+"
    r"(?P<tag>[^:]*?)\s*:\s*(?P<msg>.*)$"
)


@dataclass
class LogLine:
    ts: str
    pid: int
    tid: int
    level: str
    tag: str
    msg: str

    @property
    def text(self) -> str:
        return f"{self.ts} {self.level}/{self.tag}: {self.msg}"


class Logcat:
    def __init__(self, adb):
        self.adb = adb
        self._raw: str = ""
        self._lines: List[LogLine] = []

    def clear(self) -> None:
        self.adb.run(["logcat", "-c"], timeout=15)
        self._raw = ""
        self._lines = []

    def dump(self, extra_args: Optional[List[str]] = None, timeout: float = 60.0) -> "Logcat":
        args = ["logcat", "-v", "time", "-d"] + list(extra_args or [])
        r = self.adb.run(args, timeout=timeout)
        self._raw = r.out or ""
        self._lines = self.parse(self._raw)
        return self

    @staticmethod
    def parse(raw: str) -> List[LogLine]:
        out: List[LogLine] = []
        for line in (raw or "").splitlines():
            m = LINE.match(line)
            if m:
                out.append(
                    LogLine(
                        ts=m.group("ts"),
                        pid=int(m.group("pid")),
                        tid=int(m.group("tid")),
                        level=m.group("level"),
                        tag=m.group("tag").strip(),
                        msg=m.group("msg"),
                    )
                )
        return out

    @property
    def lines(self) -> List[LogLine]:
        return self._lines

    @property
    def raw(self) -> str:
        return self._raw

    def grep(self, pattern: str, tag: Optional[str] = None, level: Optional[str] = None) -> List[LogLine]:
        rx = re.compile(pattern)
        out = []
        for l in self._lines:
            if tag and l.tag != tag:
                continue
            if level and l.level != level:
                continue
            if rx.search(l.msg) or rx.search(l.tag):
                out.append(l)
        return out

    def has(self, pattern: str, tag: Optional[str] = None, level: Optional[str] = None) -> bool:
        return bool(self.grep(pattern, tag, level))

    def first(self, pattern: str, tag: Optional[str] = None) -> Optional[LogLine]:
        got = self.grep(pattern, tag)
        return got[0] if got else None

    def excerpt(self, n: int = 40, pattern: Optional[str] = None, tag: Optional[str] = None) -> str:
        src = self.grep(pattern, tag) if pattern else self._lines
        return "\n".join(l.text for l in src[:n]) or "(空)"

    # ── 预置的坏味道 ──────────────────────────────────────────────────
    CRASH_PATTERNS = [
        r"FATAL EXCEPTION",
        r"AndroidRuntime",
        r"ANR in ",
        r"java\.lang\.RuntimeException: Unable to (create|start)",
        r"java\.lang\.NoClassDefFoundError",
        r"java\.lang\.ClassNotFoundException",
        r"java\.lang\.UnsatisfiedLinkError",
        r"java\.lang\.OutOfMemoryError",
        r"android\.database\.sqlite\.SQLiteException",
        r"Process .* has died",
    ]

    def crashes(self) -> List[LogLine]:
        out: List[LogLine] = []
        for p in self.CRASH_PATTERNS:
            out += self.grep(p)
        return out

    def crash_excerpt(self, n: int = 30) -> str:
        got = self.crashes()
        return "\n".join(l.text for l in got[:n]) or "(无崩溃迹象)"
