"""APK 侧信息：是不是 zip、有没有 dex、v1/v2/v3 签名块。

不依赖 apksigner（很多机器没装）。有 apksigner 时把它当**佐证**，不作为唯一判据。
"""

from __future__ import annotations

import struct
import zipfile
from dataclasses import dataclass, field
from typing import Dict, List, Optional

MAGIC = b"APK Sig Block 42"

# ★ 这几个 ID 不是从记忆里抄的，是**解析本仓库两个真实 APK 得到的**：
#     debug   → [7109871a, 42726577]
#     release → [7109871a, f05368c0, 504b4453, 42726577]
#   两段 block 都通过了「尾部 size == 头部 size 且最终位置落在 end」的完整性校验，
#   所以这些 ID 是真的。v3 与 v2 的长度相同（1632）⇒ 属于同一族签名块。
#   0x42726577 是 VERITY_PADDING（对齐填充，不是签名），必须排除在签名判定之外，
#   否则会给「只有 padding 没有签名」的 APK 误判为已签名。
SIG_ID_V2 = 0x7109871A
SIG_ID_V3 = 0xF05368C0
SIG_ID_V31 = 0x1B93AD61
VERITY_PADDING = 0x42726577
KNOWN_IDS = {SIG_ID_V2: "v2", SIG_ID_V3: "v3", SIG_ID_V31: "v3.1"}
NON_SIG_IDS = {VERITY_PADDING: "verity-padding"}


@dataclass
class ApkInfo:
    path: str
    exists: bool = False
    size: int = 0
    is_zip: bool = False
    entries: List[str] = field(default_factory=list)
    v1: bool = False
    sig_blocks: List[str] = field(default_factory=list)
    unknown_ids: List[str] = field(default_factory=list)
    block_wellformed: bool = False
    error: str = ""

    @property
    def v2_or_v3(self) -> bool:
        return bool({"v2", "v3", "v3.1"} & set(self.sig_blocks))


def inspect(path: str, want_entries: Optional[List[str]] = None) -> ApkInfo:
    import os

    info = ApkInfo(path=path)
    if not os.path.isfile(path):
        info.error = "文件不存在"
        return info
    info.exists = True
    info.size = os.path.getsize(path)

    try:
        with zipfile.ZipFile(path) as z:
            info.is_zip = True
            names = z.namelist()
            info.entries = [n for n in names if n in (want_entries or [])]
            info.v1 = any(
                n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC"))
                for n in names
            )
    except Exception as e:  # noqa: BLE001
        info.error = f"不是合法 zip / 读取失败: {e}"
        return info

    blocks, unknown, wellformed, err = _sig_blocks(path)
    info.sig_blocks = blocks
    info.unknown_ids = unknown
    info.block_wellformed = wellformed
    if err:
        info.error = (info.error + " | " + err).strip(" |")
    return info


def _find_eocd(data: bytes, path: str) -> Optional[int]:
    # 从尾部往前找 EOCD（末尾可能有注释，最多 64K）
    max_back = min(len(data), 65535 + 22)
    start = len(data) - max_back
    i = len(data) - 22
    while i >= start:
        if data[i : i + 4] == b"PK\x05\x06":
            return i
        i -= 1
    return None


def _sig_blocks(path: str):
    """返回 (签名方案列表, 未识别 ID 列表, block 是否结构完好, 错误信息)。"""
    try:
        with open(path, "rb") as f:
            f.seek(0)
            data = f.read()
    except OSError as e:
        return [], [], False, f"读取失败: {e}"

    eocd = _find_eocd(data, path)
    if eocd is None:
        return [], [], False, "找不到 EOCD"

    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset + 24 > len(data) or cd_offset < 24:
        return [], [], False, f"Central Directory 偏移异常: {cd_offset}"

    magic_at = cd_offset - 16
    if data[magic_at : magic_at + 16] != MAGIC:
        return [], [], False, "没有 APK Signing Block（APK Sig Block 42 魔数未命中）"

    trail = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    block_start = cd_offset - trail - 8
    if block_start < 0 or block_start >= magic_at:
        return [], [], False, f"Signing Block 长度异常: {trail}"
    wellformed = struct.unpack_from("<Q", data, block_start)[0] == trail

    found: List[str] = []
    unknown: List[str] = []
    pos = block_start + 8
    end = cd_offset - 24
    while pos + 12 <= end:
        (pair_len,) = struct.unpack_from("<Q", data, pos)
        (pid,) = struct.unpack_from("<I", data, pos + 8)
        if pair_len < 4 or pos + 8 + pair_len > end:
            wellformed = False
            break
        if pid in KNOWN_IDS:
            found.append(KNOWN_IDS[pid])
        elif pid not in NON_SIG_IDS:
            # 不认识就不猜：列出来给人看，绝不把它算成「已签名」
            unknown.append(f"0x{pid:08x}")
        pos += 8 + pair_len
    if pos != end:
        wellformed = False
    return found, unknown, wellformed, ""
