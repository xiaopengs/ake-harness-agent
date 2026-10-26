"""
UI 遍历助手（`uiautomator dump` + `input tap`）。

★ 诚实边界：Compose 界面的节点 text 是否出现在 dump 里，取决于 `content-desc` 有没有被
  设置。本套件**不猜** —— 找不到节点就返回 None，用例据此 SKIP 或 FAIL，绝不因为
  "点了一下没崩" 就判过。
"""

from __future__ import annotations

import os
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import List, Optional

from .shell import CmdResult


@dataclass
class Node:
    text: str
    resource_id: str
    content_desc: str
    bounds: tuple  # (l, t, r, b)
    clickable: bool

    @property
    def center(self) -> tuple:
        l, t, r, b = self.bounds
        return ((l + r) // 2, (t + b) // 2)


class Ui:
    REMOTE = "/sdcard/.ake_verify_window.xml"

    def __init__(self, adb, workdir: str):
        self.adb = adb
        self.workdir = workdir
        os.makedirs(workdir, exist_ok=True)
        self._nodes: List[Node] = []
        self._raw: str = ""
        self._last_error: str = ""

    @property
    def last_error(self) -> str:
        return self._last_error

    def available(self) -> (bool, str):
        r = self.adb.shell(f"uiautomator dump {self.REMOTE}", timeout=60)
        if not r.ok or "dumped to" not in (r.out + r.err):
            return False, (r.out + r.err).strip() or "uiautomator dump 无输出"
        return True, (r.out + r.err).strip()

    def dump(self, timeout: float = 60.0) -> bool:
        r = self.adb.shell(f"uiautomator dump {self.REMOTE}", timeout=timeout)
        if not r.ok or "dumped to" not in (r.out + r.err):
            self._last_error = (r.out + r.err).strip() or "uiautomator dump 失败"
            return False
        cat = self.adb.shell(f"cat {self.REMOTE}", timeout=30)
        self._raw = cat.out or ""
        # uiautomator 偶尔会在开头吐一行 garbage，截到第一个 '<'
        i = self._raw.find("<?xml")
        if i < 0:
            i = self._raw.find("<hierarchy")
        if i < 0:
            self._last_error = "dump 出来的不是 XML"
            return False
        self._raw = self._raw[i:]
        try:
            root = ET.fromstring(self._raw)
        except ET.ParseError as e:
            self._last_error = f"XML 解析失败: {e}"
            return False
        self._nodes = []
        for el in root.iter():
            if el.tag != "node":
                continue
            self._nodes.append(
                Node(
                    text=el.attrib.get("text", ""),
                    resource_id=el.attrib.get("resource-id", ""),
                    content_desc=el.attrib.get("content-desc", ""),
                    bounds=_bounds(el.attrib.get("bounds", "")),
                    clickable=el.attrib.get("clickable", "false") == "true",
                )
            )
        self.adb.shell(f"rm -f {self.REMOTE}")
        return True

    @property
    def nodes(self) -> List[Node]:
        return self._nodes

    def find(self, text: Optional[str] = None, resource_id: Optional[str] = None,
             content_desc: Optional[str] = None) -> Optional[Node]:
        for n in self._nodes:
            if text is not None and text in n.text:
                return n
            if resource_id is not None and resource_id in n.resource_id:
                return n
            if content_desc is not None and content_desc in n.content_desc:
                return n
        return None

    def find_all(self, text: str) -> List[Node]:
        return [n for n in self._nodes if text in n.text or text in n.content_desc]

    def tap_text(self, text: str) -> (bool, str):
        if not self.dump():
            return False, self.last_error
        n = self.find(text=text) or self.find(content_desc=text)
        if n is None:
            return False, f"当前界面没有文案「{text}」（可见节点 {len(self._nodes)} 个）"
        x, y = n.center
        r = self.adb.tap(x, y)
        return r.ok, f"tap({x},{y}) → {r.out.strip() or 'ok'}"

    def texts(self) -> List[str]:
        return [n.text for n in self._nodes if n.text]


def _bounds(s: str) -> tuple:
    m = re.findall(r"-?\d+", s or "")
    if len(m) >= 4:
        return tuple(int(v) for v in m[:4])
    return (0, 0, 0, 0)
