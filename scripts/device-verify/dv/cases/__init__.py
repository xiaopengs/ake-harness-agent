"""用例注册表。M1–M25 与脚本函数的唯一映射点 —— 改判据就改这里对应的函数。"""

from __future__ import annotations

from typing import List

from ..model import Case
from .m01_m06 import CASES as C1
from .m07_m11 import CASES as C2
from .m12_m16 import CASES as C3
from .m17_m21 import CASES as C4
from .m22_m25 import CASES as C5
from .m26_m27 import CASES as C6

ALL_CASES: List[Case] = C1 + C2 + C3 + C4 + C5 + C6

BY_ID = {c.id: c for c in ALL_CASES}


def select(spec: str) -> List[Case]:
    """--only M3,M4 / --only M22-M25 / 空 = 全部。"""
    spec = (spec or "").strip()
    if not spec:
        return list(ALL_CASES)
    wanted = set()
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = part.split("-", 1)
            lo, hi = lo.strip().lstrip("Mm"), hi.strip().lstrip("Mm")
            try:
                for i in range(int(lo), int(hi) + 1):
                    wanted.add(f"M{i}")
            except ValueError:
                wanted.add(part)
        else:
            wanted.add(part if part.upper().startswith("M") else f"M{part}")
    known_upper = {c.id.upper() for c in ALL_CASES} | {"M0"}   # M0 由 run.py 单独构造
    out = [c for c in ALL_CASES
           if c.id.upper() in {w.upper() for w in wanted} and c.id.upper() != "M0"]
    unknown = {w for w in wanted if w.upper() not in known_upper}
    if unknown:
        raise SystemExit(f"未知用例号: {sorted(unknown)}；可用: {[c.id for c in ALL_CASES]}")
    return out
