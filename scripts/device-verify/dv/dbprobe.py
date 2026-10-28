"""
设备上 Room 库的取证：拉库 → 本地开库 → 查表 → （必要时）改完推回去。

★ 两条必须交代清楚的事：

 1. **WAL**。Room 开的是 `WRITE_AHEAD_LOGGING`，只看主库文件会漏掉尚未 checkpoint 的
    数据。所以 `pull()` 一定把 `-wal` / `-shm` 一起拉下来，放在同一目录、用同一个文件名，
    让 sqlite 自己重放；再跑一次 `PRAGMA wal_checkpoint(TRUNCATE)` 把 WAL 并回主库。
    不做这一步，M22/M23 的「数据还在不在」结论可能是假的。

 2. **read-only 判定**。拉库靠 `run-as`（只有 debuggable 包才行）。
    release 包取不到 ⇒ 相关用例必须 SKIP，不许拿「库文件不存在」当通过。
"""

from __future__ import annotations

import os
import re
import sqlite3
import subprocess
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

from .shell import CmdResult


@dataclass
class PullResult:
    ok: bool
    local_db: str = ""
    local_dir: str = ""
    wal_bytes: int = -1
    shm_bytes: int = -1
    error: str = ""
    steps: List[str] = None  # type: ignore[assignment]

    def __post_init__(self):
        if self.steps is None:
            self.steps = []


class DbProbe:
    def __init__(self, adb, package: str, db_name: str, workdir: str):
        self.adb = adb
        self.package = package
        self.db_name = db_name
        self.workdir = workdir
        os.makedirs(workdir, exist_ok=True)

    # ── 设备侧路径 ────────────────────────────────────────────────────
    @property
    def remote_dir(self) -> str:
        return "databases"

    @property
    def remote_db(self) -> str:
        return f"databases/{self.db_name}"

    def _run_as(self, cmd: str, timeout: float = 60.0) -> CmdResult:
        return self.adb.shell(f"run-as {self.package} {cmd}", timeout=timeout)

    # ── 能力探测 ─────────────────────────────────────────────────────
    def can_run_as(self) -> Tuple[bool, str]:
        r = self._run_as("id", timeout=20)
        return (r.ok and "uid=" in r.out), (r.out + r.err).strip()

    def can_write(self) -> Tuple[bool, str]:
        """能不能往 databases/ 里写。这是 M23/M24/M25 构造前置条件的能力门槛。"""
        probe = "databases/.ake_probe"
        r1 = self._run_as(f"sh -c 'echo probe > {probe} && ls -l {probe} && rm -f {probe}'", timeout=30)
        if not r1.ok:
            return False, (r1.out + r1.err).strip() or "run-as 写入失败（可能非 debuggable 或 SELinux 限制）"
        if ".ake_probe" not in r1.out:
            return False, f"写入未生效: {r1.out.strip()}"
        return True, r1.out.strip()

    def ls_dir(self, remote: str = "databases") -> CmdResult:
        return self._run_as(f"ls -l {remote}", timeout=30)

    # ── 拉库 ─────────────────────────────────────────────────────────
    def pull(self, tag: str = "db") -> PullResult:
        res = PullResult(ok=False)
        local_dir = os.path.join(self.workdir, tag)
        os.makedirs(local_dir, exist_ok=True)
        res.local_dir = local_dir
        local_db = os.path.join(local_dir, self.db_name)
        res.local_db = local_db

        for suffix, attr in (("", "db"), ("-wal", "wal"), ("-shm", "shm")):
            remote = f"databases/{self.db_name}{suffix}"
            local = local_db + suffix
            # ── 首选：adb exec-out + 二进制直写文件 ──────────────────────
            # `run()` 是文本管道（解码后再写回会破坏 SQLite 的二进制页），
            # 但 exec-out 本身就是为二进制设计的：把 stdout 直接指向本地文件，
            # 中间不经过任何字符串转换。
            #
            # 走 /sdcard 中转是这里曾经的唯一路径，但它在「App 数据目录 → 外存」
            # 这一步会因 scoped storage / run-as 的权限差异失败，且会污染外存。
            # 实测：真机上 run-as 可读、/sdcard 中转失败 ⇒ 拉库恒为 0 字节，
            # 用例报"包非 debuggable / 库还没建"，而实际上库好好的。
            got = -1
            try:
                argv = [self.adb.binary]
                if self.adb.serial:
                    argv += ["-s", self.adb.serial]
                argv += ["exec-out", f"run-as {self.package} cat {remote}"]
                with open(local, "wb") as fh:
                    pr = subprocess.run(argv, stdout=fh, stderr=subprocess.PIPE, timeout=180)
                if pr.returncode == 0 and os.path.isfile(local) and os.path.getsize(local) > 0:
                    got = os.path.getsize(local)
            except (OSError, subprocess.SubprocessError):
                got = -1
            if got >= 0:
                setattr(res, f"{attr}_bytes", got)
                res.steps.append(f"{remote}: {got} B (exec-out)")
                continue

            # ── 回退：/sdcard 中转 ────────────────────────────────────────
            tmp = f"/sdcard/.ake_verify_{self.db_name}{suffix}"
            r2 = self._run_as(f"sh -c 'cp {remote} {tmp} 2>/dev/null && chmod 644 {tmp} && echo COPIED'")
            if "COPIED" not in (r2.out or ""):
                setattr(res, f"{attr}_bytes", -1)
                res.steps.append(f"{remote}: 不存在或不可读")
                self.adb.shell(f"rm -f {tmp}")
                continue
            p = self.adb.run(["pull", tmp, local], timeout=180)
            self.adb.shell(f"rm -f {tmp}")
            if p.ok and os.path.isfile(local):
                setattr(res, f"{attr}_bytes", os.path.getsize(local))
                res.steps.append(f"{remote}: {os.path.getsize(local)} B")
            else:
                setattr(res, f"{attr}_bytes", -1)
                res.steps.append(f"{remote}: pull 失败 {p.err.strip()}")

        if not os.path.isfile(local_db) or os.path.getsize(local_db) == 0:
            res.error = "主库文件没拉到（包非 debuggable / 库还没建 / run-as 不可用）"
            return res

        # WAL 并回主库，保证后续查询看到全部已提交数据
        try:
            con = sqlite3.connect(local_db)
            con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            con.commit()
            con.close()
        except sqlite3.Error as e:
            res.steps.append(f"wal_checkpoint 失败: {e}")
        res.ok = True
        return res

    # ── 查询 ─────────────────────────────────────────────────────────
    @staticmethod
    def query(local_db: str, sql: str, params: Tuple = ()) -> List[Tuple]:
        con = sqlite3.connect(local_db)
        try:
            con.row_factory = sqlite3.Row
            return [tuple(r) for r in con.execute(sql, params).fetchall()]
        finally:
            con.close()

    @staticmethod
    def scalar(local_db: str, sql: str, params: Tuple = ()) -> Any:
        rows = DbProbe.query(local_db, sql, params)
        return rows[0][0] if rows and rows[0] else None

    @staticmethod
    def tables(local_db: str) -> List[str]:
        rows = DbProbe.query(
            local_db,
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name",
        )
        return [r[0] for r in rows]

    @staticmethod
    def count(local_db: str, table: str) -> Optional[int]:
        try:
            return DbProbe.scalar(local_db, f"SELECT COUNT(*) FROM {table}")
        except sqlite3.Error:
            return None

    # ── 推库（构造前置条件用） ────────────────────────────────────────
    def push(self, local_db: str, note: List[str]) -> bool:
        """把改过的库推回去。**调用前必须先 force-stop**，否则 Room 还开着库。"""
        tmp = f"/sdcard/.ake_verify_push_{self.db_name}"
        up = self.adb.run(["push", local_db, tmp], timeout=300)
        if not up.ok:
            note.append(f"push 到 /sdcard 失败: {up.err.strip()}")
            return False
        # 清掉 WAL/SHM —— 旧 WAL 与新主库混在一起会重放出幽灵数据
        cp = self._run_as(
            f"sh -c 'cat {tmp} > {self.remote_db} && rm -f {self.remote_db}-wal {self.remote_db}-shm "
            f"&& ls -l {self.remote_db}'"
        )
        self.adb.shell(f"rm -f {tmp}")
        if not cp.ok:
            note.append(f"写回 databases/ 失败: {(cp.out + cp.err).strip()}")
            return False
        note.append(f"已写回 {self.remote_db}")
        return True

    # ── 通用造数：按表结构自动填必填列 ─────────────────────────────────
    @staticmethod
    def seed_rows(
        local_db: str,
        table: str,
        rows: int,
        overrides: Optional[Dict[str, Any]] = None,
        distinct_prefix: str = "ake-verify",
    ) -> Tuple[int, str]:
        """
        往表里插 N 行。只显式提供 `overrides` 里的列，其余 NOT NULL 且无默认值的列
        按类型自动填占位值（text→带前缀的串、int→序号、blob→空）。

        ★ 为什么这么做而不是手写 INSERT：手写 INSERT 一旦 schema 变了就静默插错，
          而这里是从设备上拉下来的真实 schema 反推的 —— 插不进去会直接报错，不会假成功。
        """
        overrides = overrides or {}
        con = sqlite3.connect(local_db)
        try:
            cur = con.execute(f"PRAGMA table_info({table})")
            cols = cur.fetchall()
            if not cols:
                return 0, f"表 {table} 不存在或读不到列信息"
            inserted = 0
            for i in range(rows):
                values: Dict[str, Any] = {}
                for c in cols:
                    name = c[1]
                    ctype = (c[2] or "").upper()
                    notnull = bool(c[3])
                    default = c[4]
                    pk = bool(c[5])
                    if name in overrides:
                        v = overrides[name]
                        values[name] = v(i) if callable(v) else v
                        continue
                    if not notnull and not pk:
                        continue
                    if "INT" in ctype:
                        values[name] = i
                    elif "BLOB" in ctype:
                        values[name] = b""
                    else:
                        values[name] = f"{distinct_prefix}-{i}-{os.urandom(3).hex()}"
                names = ",".join(values.keys())
                marks = ",".join("?" for _ in values)
                con.execute(
                    f"INSERT INTO {table} ({names}) VALUES ({marks})", list(values.values())
                )
                inserted += 1
            con.commit()
            return inserted, ""
        except sqlite3.Error as e:
            con.rollback()
            return 0, f"插入 {table} 失败: {e}"
        finally:
            con.close()
