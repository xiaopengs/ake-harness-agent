#!/usr/bin/env python3
"""
★ v3 → v4 迁移的**外部 oracle**（docs/09 §7.1 的 L2）。

为什么需要它：`:data` 是 androidLibrary，JVM 单测里没有 SQLite；而 `sqlite3` CLI
在本机不可用、`room-testing`（MigrationTestHelper）的坐标不在本轮可改范围。
于是用 **python3 的 sqlite3 模块** —— 它是**独立于本项目的 SQLite 实现**，
能把迁移 SQL 真跑一遍，而不只是做静态文本比对。

用法（由 `SqliteOracleV4Test` 调用，也可手工跑）：

    python3 v4_migration_oracle.py --schema-dir <.../schemas/com.ake.data.persist.db.AkeDatabase> \
                                   --statements <statements.json> [--json]

退出码 0 = 全部断言通过。
"""
import argparse
import json
import re
import sqlite3
import sys


def table_names(con):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}


def col_info(con, table):
    return {r[1]: dict(type=r[2], notnull=bool(r[3]), dflt=r[4])
            for r in con.execute(f"PRAGMA table_info(`{table}`)")}


ALTER_ADD_RE = re.compile(
    r"^ALTER\s+TABLE\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\s+ADD\s+COLUMN\s+`?([A-Za-z_][A-Za-z0-9_]*)`?",
    re.IGNORECASE,
)


def column_exists(con, table, column):
    return any(r[1] == column for r in con.execute(f"PRAGMA table_info(`{table}`)"))


def run_migration(con, stmts):
    """★ 复现 `CloudMigration3To4.migrate()` 的执行语义，**不是**原样回放文本。

    `ALTER TABLE ... ADD COLUMN` **不能**带 `IF NOT EXISTS`，重跑会抛
    `duplicate column name` —— 这正是 docs/09 §3.3 指出的 `ChatMigration2To3` 缺口。
    v4 用 `PRAGMA table_info` 守卫掉已存在的列；oracle 必须复现同一个守卫，
    否则它测的是「原始 SQL 文本重跑」而不是「迁移重跑」。
    """
    for sql in stmts:
        s = sql.strip()
        m = ALTER_ADD_RE.match(s)
        if m and column_exists(con, m.group(1), m.group(2)):
            continue
        con.execute(sql)


def build_v3(con, schema):
    """用 **3.json 里的 createSql** 造一个 v3 库（不自造 DDL ⇒ 与真机同一份坐标）。"""
    v3 = json.load(open(f"{schema}/3.json"))
    for e in v3["database"]["entities"]:
        con.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for i in (e.get("indices") or []):
            con.execute(i["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    # Room 自己的元数据表（真机 sqlite_master 里数出 20 张表就是它 + 19 张 Entity 表）
    con.execute("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity TEXT)")
    return v3


def seed(con):
    """塞入「老设备上的真实数据」的代表：迁移前后行数必须逐一相等。

    ⚠ `chat_session.agent_id` 外键指向 `agent_profile(**id**)` 而不是 `agent_id`
       ⇒ 这里必须填 profile 的**主键** 'p1'。填错的表现是
       `sqlite3.IntegrityError: FOREIGN KEY constraint failed`，
       而报错点在 seed 而不是在迁移里 —— 极易误判成「迁移有问题」。
    """
    con.execute(
        "INSERT INTO agent_profile (id, agent_id, name, avatar_kind, description, "
        "persona_charter_preview, model_id, origin, trust_tier, scan_verdict, "
        "scan_hits_json, scanner_version, always_confirm_skills, created_at, updated_at) "
        "VALUES ('p1','a1','n','IDENTICON','d','prev','m','LOCAL','T0','OK','[]',1,0,1,1)")
    con.execute(
        "INSERT INTO chat_session (id, agent_id, title, created_at, updated_at, "
        "last_message_at, context_profile) VALUES ('s1','p1','t',1,1,1,'STANDARD')")
    con.execute(
        "INSERT INTO chat_message (id, session_id, seq, turn_id, role, kind, status, "
        "content_preview, created_at, updated_at) "
        "VALUES ('m1','s1',1,'t1','USER','TEXT','COMPLETE','prev',1,1)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema-dir", required=True)
    ap.add_argument("--statements", required=True)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    stmts = json.load(open(args.statements))
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")

    v3 = build_v3(con, args.schema_dir)
    seed(con)
    con.commit()

    before = {t: con.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0]
              for t in table_names(con)}

    # ★ 在**事务内**跑迁移 —— 模拟 Room 的处境（这正是 PRAGMA foreign_keys 失效的场景）
    #   ★ 执行失败必须**被捕获并记进报告**，而不是让进程抛栈退出：
    #     否则调用方只看到 exit != 0，无法区分「断言失败」与「脚本崩了」。
    migration_error = ""
    try:
        con.execute("BEGIN")
        run_migration(con, stmts)
        con.execute("COMMIT")
    except Exception as ex:  # noqa: BLE001
        migration_error = f"{type(ex).__name__}: {ex}"
        try:
            con.execute("ROLLBACK")
        except Exception:  # noqa: BLE001
            pass

    after = {t: con.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0]
             for t in table_names(con)}

    integrity = con.execute("PRAGMA integrity_check").fetchone()[0]
    fk_violations = con.execute("PRAGMA foreign_key_check").fetchall() if not migration_error else ["<migration failed>"]

    # ★ 迁移必须真的加出了 v4 的列
    v4 = json.load(open(f"{args.schema_dir}/4.json"))
    missing = []
    for e in v4["database"]["entities"]:
        if e["tableName"] not in table_names(con):
            missing.append(f"缺表 {e['tableName']}")
            continue
        got = set(col_info(con, e["tableName"]))
        want = {f["columnName"] for f in e["fields"]}
        if got != want:
            missing.append(f"{e['tableName']} 列集不一致 多={got-want} 少={want-got}")

    idempotent = True
    idempotent_err = ""

    if not migration_error:
        # 幂等：再跑一遍（ALTER 不能带 IF NOT EXISTS ⇒ 靠 pragma_table_info 守卫）
        try:
            con.execute("BEGIN")
            run_migration(con, stmts)
            con.execute("COMMIT")
        except Exception as ex:  # noqa: BLE001
            idempotent = False
            idempotent_err = f"{type(ex).__name__}: {ex}"

    # ★ 只比**迁移前就存在的表**：新表在 before 里根本不存在，
    #   拿两边 dict 做全等会把「多了一个键」误判成「数据丢了」。
    preserved = {t: (before[t] == after.get(t)) for t in before}
    lost = [f"{t}: {before[t]} -> {after.get(t)}" for t in before if after.get(t) != before[t]]
    new_tables = sorted(set(after) - set(before))

    report = {
        "sqlite_version": sqlite3.sqlite_version,
        "statement_count": len(stmts),
        "migration_error": migration_error,
        "rows_before": before,
        "rows_after": after,
        "rows_lost": lost,
        "new_tables": new_tables,
        "rows_preserved": not lost,
        "integrity_check": integrity,
        "foreign_key_violations": len(fk_violations),
        "v4_schema_mismatches": missing,
        "idempotent_rerun": idempotent,
        "idempotent_error": idempotent_err,
    }
    ok = (not migration_error and report["rows_preserved"] and integrity == "ok"
          and not fk_violations and not missing and idempotent
          # ★ 新表必须真的被建出来（且为空 —— 迁移不回填）
          and "cloud_credential" in new_tables and after.get("cloud_credential") == 0)
    report["ok"] = ok

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        for k, v in report.items():
            print(f"{k}: {v}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
