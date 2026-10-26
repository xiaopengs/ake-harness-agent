"""
结果模型 —— 本文件是整个套件的「不可自我欺骗」内核，改动前请先读 README 的 §4。

设计上只有一条铁律：

    PASS 只能由「一条 ok=True 的 Assertion」产生，且断言列表不能为空。

由此推出四条硬约束（都在 `run_case()` 里强制，不是约定）：

 1. 环境不具备 ⇒ 抛 `Skip` ⇒ 状态 `SKIP`。**没有任何代码路径能把「环境不具备」
    映射成 PASS**，因为 `Skip` 是异常，不经过断言流程。
 2. 用例一条断言都没产出 ⇒ `ERROR`（不是 PASS）。空判据 = 假绿，与本项目
    「字符串扫描测试挡不住注释」是同一个坑：看起来有守护，其实什么都没看。
 3. 每条 FAIL 必须挂证据：Assertion 强制带 `evidence_cmd` / `evidence_out`；
    `run_case()` 会检查 FAIL 的断言有没有证据，没有就把状态降级为 ERROR 并标注。
 4. SKIP 必须写清「缺什么」，由 `Ctx.require()` 统一生成，用例自己不能瞎写。
"""

from __future__ import annotations

import time
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

PASS = "PASS"
FAIL = "FAIL"
SKIP = "SKIP"
ERROR = "ERROR"

# 报告里的人话
STATUS_ZH = {
    PASS: "通过",
    FAIL: "不通过",
    SKIP: "未执行（前置不具备）",
    ERROR: "判据失效（脚本自身的错误）",
}


class Skip(Exception):
    """用例前置不具备。抛出即 SKIP —— 它**永远不会**变成 PASS。"""

    def __init__(self, reason: str, missing: Optional[List[str]] = None):
        super().__init__(reason)
        self.reason = reason
        self.missing = missing or []


@dataclass
class Assertion:
    """一条可判定的断言。FAIL 的来源必须是它，且必须带证据。"""

    name: str
    ok: bool
    expected: str
    actual: str
    evidence_cmd: str = ""
    evidence_out: str = ""
    note: str = ""
    calibrated: bool = True   # False ⇒ 这条判据的真机输出未经校准，见 README §5

    def as_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "ok": self.ok,
            "expected": self.expected,
            "actual": self.actual,
            "evidence_cmd": self.evidence_cmd,
            "evidence_out": _clip(self.evidence_out),
            "note": self.note,
            "calibrated": self.calibrated,
        }


@dataclass
class Evidence:
    """不带判定、只留痕的东西（命令原样输出、产物路径）。"""

    label: str
    cmd: str = ""
    out: str = ""

    def as_dict(self) -> Dict[str, Any]:
        return {"label": self.label, "cmd": self.cmd, "out": _clip(self.out)}


@dataclass
class CaseResult:
    id: str
    title: str
    phase: str
    status: str
    duration_ms: int = 0
    reason: str = ""
    missing: List[str] = field(default_factory=list)
    assertions: List[Assertion] = field(default_factory=list)
    evidence: List[Evidence] = field(default_factory=list)
    calibration: List[str] = field(default_factory=list)
    artifacts: List[str] = field(default_factory=list)
    manual_pending: str = ""

    @property
    def failed_assertions(self) -> List[Assertion]:
        return [a for a in self.assertions if not a.ok]

    def as_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "title": self.title,
            "phase": self.phase,
            "status": self.status,
            "verdict_zh": STATUS_ZH.get(self.status, self.status),
            "duration_ms": self.duration_ms,
            "reason": self.reason,
            "missing": self.missing,
            "assertions": [a.as_dict() for a in self.assertions],
            "evidence": [e.as_dict() for e in self.evidence],
            "calibration": self.calibration,
            "artifacts": self.artifacts,
            "manual_pending": self.manual_pending,
        }


@dataclass
class Case:
    id: str
    title: str
    phase: str
    fn: Callable[..., List[Assertion]]
    needs: List[str] = field(default_factory=list)
    calibration: List[str] = field(default_factory=list)
    manual_pending: str = ""
    doc: str = ""


def _clip(s: str, limit: int = 4000) -> str:
    s = s or ""
    return s if len(s) <= limit else s[:limit] + f"\n…[截断，共 {len(s)} 字符]"


def run_case(case: Case, ctx: Any) -> CaseResult:
    """
    执行单条用例并**推导**状态。

    状态不是用例自己说了算的 —— 用例只负责交出断言列表（或抛 `Skip`），
    状态由这里的规则计算。这样「写用例的人想让它绿」就必须真的造出一条 ok 的断言。
    """
    started = time.time()
    result = CaseResult(
        id=case.id,
        title=case.title,
        phase=case.phase,
        status=ERROR,
        calibration=list(case.calibration),
        manual_pending=case.manual_pending,
    )
    try:
        for cap in case.needs:
            ctx.require(cap)
        produced = case.fn(ctx)
    except Skip as e:
        result.status = SKIP
        result.reason = e.reason
        result.missing = list(e.missing)
        result.duration_ms = _ms(started)
        return result
    except Exception as e:  # noqa: BLE001 —— 脚本自身炸了必须暴露，不能吞
        result.status = ERROR
        result.reason = f"{type(e).__name__}: {e}"
        result.evidence.append(
            Evidence(label="用例执行异常栈", cmd=f"{case.id} 执行栈", out=traceback.format_exc())
        )
        result.duration_ms = _ms(started)
        return result

    assertions = [a for a in (produced or []) if isinstance(a, Assertion)]
    result.assertions = assertions

    if not assertions:
        # ★ 空判据 = 假绿。这是本套件最重要的一行防御。
        result.status = ERROR
        result.reason = "用例没有产出任何断言 —— 空判据等同假绿，按 ERROR 处理（不是 PASS）"
        result.duration_ms = _ms(started)
        return result

    failed = [a for a in assertions if not a.ok]
    if failed:
        result.status = FAIL
        # 没有证据的 FAIL 无法排查 ⇒ 降级为 ERROR，让问题停在脚本侧而不是甩给排障的人
        blind = [a.name for a in failed if not (a.evidence_cmd or a.evidence_out)]
        if blind:
            result.status = ERROR
            result.reason = "FAIL 但缺少证据，按 ERROR 处理：" + "、".join(blind)
    else:
        result.status = PASS

    result.duration_ms = _ms(started)
    return result


def _ms(started: float) -> int:
    return int((time.time() - started) * 1000)
