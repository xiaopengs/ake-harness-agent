# 真机验证套件（M0–M27）

> 把 `docs/04-release-gate.md` 里那些**文字描述的真机用例**变成**可执行、可判定、能出证据**的东西。
>
> 目标：一个人、一台手机、一条命令，得到一份「哪些过了、哪些没过、没过的证据在哪」的报告。

```
手机插上（开 USB 调试并授权）
        ↓
./run.py --selftest          ← 先证明这套脚本自己会报 FAIL（约 5 秒）
        ↓
./run.py --only M0-M2b,M3-M6,M22-M26     ← 第 1 天：不需要模型/签名/CDN，15–30 分钟
        ↓
看 out/<时间戳>/report.md
```

---

## 1. 目录与入口

| 路径 | 作用 |
|---|---|
| **`run.py`** | **唯一入口**。一条命令跑全部；`--only` 选号；`--selftest` 自检 |
| `dv/ctx.py` | 用例上下文 + **能力门禁**（`require()` ⇒ 不满足抛 `Skip` ⇒ SKIP） |
| `dv/model.py` | 结果模型与**状态推导规则**（不可自我欺骗的内核，见 §4） |
| `dv/envcheck.py` | 环境预检（M0）：adb / 设备 / getprop / APK / 签名 |
| `dv/adb.py` | adb 封装（devices / install / am start / uiautomator / bugreport） |
| `dv/dbprobe.py` | 拉库（含 `-wal`/`-shm` + `wal_checkpoint`）／查表／推库／通用造数 |
| `dv/logcat.py` | `-c` + `-d` 的日志抓取与判读（崩溃模式、按 tag 过滤） |
| `dv/apk.py` | APK 签名块解析（不依赖 apksigner） |
| `dv/ui.py` | `uiautomator dump` → 找节点 → `input tap` |
| `dv/audit.py` | 自检：制造必失败场景，确认脚本报 FAIL |
| `dv/report.py` | 报告输出（JSON + Markdown + 控制台） |
| `dv/cases/m01_m06.py` | M1 / M2 / M2b / M3 / M4 / M5 / M6 |
| `dv/cases/m07_m11.py` | M7 / M8 / M9 / M10 / M11 |
| `dv/cases/m12_m16.py` | M12 / M13 / M14 / M15 / M16 |
| `dv/cases/m17_m21.py` | M17 / M18 / M19 / M20 / M21 |
| `dv/cases/m22_m25.py` | **M22 / M23 / M24 / M25**（第 3 轮新增，静默失败专项） |
| `dv/cases/m26_m27.py` | M26 / M27（第 3 轮补记新增：冷启日志闭环 / 重试不重复） |

依赖：**python3 ≥ 3.8，只用标准库**。没有 pip 安装的步骤 —— 这是刻意的，
验证脚本自己不该成为新的环境障碍。

---

## 2. 常用命令

```bash
# 打印 M 号 → 脚本函数/行号 的映射（docs/04-release-gate.md §11.2 的表由它生成）
./run.py --map

# 第一次必跑：证明脚本会报 FAIL（否则后面所有结论都不可信）
./run.py --selftest

# 第 1 天：只要一台手机，不要模型、不要签名、不要 CDN
./run.py --only M0-M2b,M3-M6,M22-M26

# 迁移专项（需要一个 v2 老包）
./run.py --only M22 --old-apk /path/to/v2-app-debug.apk

# 核心链路（模型已在设备上 + 愿意人工点一下）
./run.py --only M7-M11 --with-model-run \
         --manifest-url https://…/manifest.json \
         --goal "打开某电商页，按销量排序，取前三条" \
         --target-url https://example.com/list \
         --expected-records "A,B,C"

# 安全与终止
./run.py --only M12-M16 --with-model-run \
         --payment-goal "帮我下单付款" \
         --injection-goal "…" --injected-url https://evil.example \
         --budget-goal "…" --confirm-goal "…" \
         --wipe-button-text "一键清除"

# 性能（M19 要 20 分钟，默认不开）
./run.py --only M17-M21 --with-model-run --goal "…" --long \
         --video-dir /path/to/videos --privacy-url https://…/privacy

# 指定设备 / 指定 adb
./run.py --serial 1234567890ABCDEF
./run.py --adb /opt/android-sdk/platform-tools/adb

# 失败时不抓 bugreport（快很多）
./run.py --no-bugreport
```

### 退出码（门禁真正拦得住东西的关键）

| 码 | 含义 |
|---|---|
| `0` | 本次跑到的用例全部 PASS |
| `1` | 有 FAIL 或 ERROR ⇒ **NO-GO** |
| `2` | 没有 FAIL，但有 SKIP ⇒ **未决**：既不能放行，也不能说没问题 |
| `3` | 自检失败 ⇒ **别用这套脚本的结论** |

---

## 3. 报告

每次运行产出 `out/<时间戳>/`：

```
report.json   机器可读：每条用例的每条断言（期望 / 实测 / 命令 / 原始输出）
report.md     人读：总览表 + 逐条判据表 + FAIL 的证据块
artifacts/<用例号>/   仅失败用例：logcat.txt / dumpsys_meminfo.txt /
                      dropbox.txt / dumpsys_package.txt / ps.txt / bugreport.zip
```

---

## 4. 为什么它不会骗你（改代码前请先读这段）

`dv/model.py` 的 `run_case()` 里有四条**结构性**约束 —— 它们不是约定，是代码路径：

1. **PASS 只能来自断言**。用例函数只交出 `Assertion` 列表；状态由 `run_case()` 算，
   用例自己说不了「我过了」。
2. **空判据 ⇒ ERROR，不是 PASS**。一条断言都没产出，说明这条用例根本没看东西
   —— 和本项目「字符串扫描测试挡不住注释」是同一个坑。
3. **环境不具备 ⇒ `Skip` 异常 ⇒ SKIP**。`Skip` 是异常，走的是和 PASS 完全不同的路径。
   **不存在**「adb 不在就跳过并通过」这种写法的立足之地：要造出这种假绿，必须有人显式
   删掉 `needs` 或把 `require()` 改掉，而这在 review 里一眼可见。
4. **FAIL 必须带证据**。失败断言没有 `evidence_cmd`/`evidence_out` ⇒ 状态降级为 ERROR，
   把问题停在脚本侧，而不是甩给排障的人。

外加一条：**每条 FAIL 自动收集产物**（logcat / dropbox / meminfo / bugreport）。

---

## 5. 【待真机校准】是什么意思

报告里打了 `⚠️未校准` 的判据，表示：**这行命令在真机上到底会输出什么，我没有设备验证过**。
它们的判据是有依据的（依据写在 `note` 里，通常指向某行源码），但输出形态没见过。

第一次跑完请把这些条的 `实测` 值贴回 `docs/04-release-gate.md` 的映射表，把校准标记摘掉。
**在没有校准之前，它们的 FAIL 需要先人工看一眼再下结论** —— 但请注意：
它们**不会**因为没校准就自动变成 PASS，那正是这套东西要防的事。

---

## 6. 已知的能力边界（脚本不装作能做的事）

| 项 | 现状 |
|---|---|
| release 包拉库 | release 非 debuggable ⇒ `run-as` 不可用 ⇒ M11 等依赖 DB 的用例在 release 上**必然 SKIP**。要真跑需先把拉库方案定下来（root / 可调试 release / 应用内导出） |
| `observation` / `chat_message` 正文 | 是加密区（R1）⇒ 「逐字一致」只能落在明文侧：`observation.host`、`record_row_count`、`task.answer_preview` |
| M9 真降级 | App 没有「强制指定 backend」的开关 ⇒ 只能验「选中的后端能跑 + 无 JNI 错误」，验不了「GPU 挂了会自动掉到 CPU」 |
| M15「协程泄漏」 | 没有统一日志形态，也没接 LeakCanary/StrictMode ⇒ 该条判据偏弱，已在报告里标注 |
| M24 写失败的「同会话继续发」 | 需要模型在场；本次给了模型无关的等价判据（清空 `template_registry` 后冷启能否重新写入） |
| M20/M21 的人工部分 | Play Console 提交审核、Data Safety 表单 —— 脚本只判能静态判的部分，其余写进 `manual_pending` |

---

## 7. 触发方式：为什么默认是「人工点一下」

M8–M14/M17/M19 需要模型跑一次任务。对话页 → 选 Agent → 输入 → 发送这条链路上
任何一步的层级或文案变了，自动化就会静默地点到别的地方，然后用例以「没等到任务」收场
—— 看起来像 App 的问题，其实是脚本的问题。

所以默认行为是：**脚本打印「请在设备上发送这条 goal」，然后轮询数据库等证据**。
判据仍然 100% 由脚本判定，只是「把手机上那个按钮点一下」这件事交给人。
想试自动触发就加 `--auto-ui`（实验性，`uiautomator` + `input text` + 回车）。
