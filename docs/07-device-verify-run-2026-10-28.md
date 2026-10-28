# 首次设备执行记录 · 2026-10-28

> 执行人：关千锤（release-gatekeeper）／主理人岑执衡
> 设备：`emulator-5554`，型号 `sdk_gphone64_x86_64`，Android 16（API 36），x86_64
> 启动方式：`-no-accel -gpu swiftshader_indirect -no-window`（**软件模拟，无 KVM**）
> 冷启耗时：280,898 ms（约 4.7 分钟，官方 emulator 自报）

---

## 0. 先说清楚这是什么、不是什么

**这是本项目第一次在真实 Android 系统上执行指令**，之前所有结论都只来自 JVM 单测与编译。

| 它证明了 | 它没证明 |
|---|---|
| APK 能被 `pm install` 接受并落盘 | 真 SoC（Tensor / 骁龙 / 天玑）上的行为 |
| `MainActivity` 能被 `am start` 拉起并 resume 到前台 | GPU / NNAPI / OpenCL 路径（本机 swiftshader 软件渲染） |
| Process 存活、无 FATAL、无 ANR | 2.6GB 模型常驻内存与推理（未加载模型） |
| Room v3 在**真 ART + 真 SQLite** 上建库成功、`user_version=3`、20 张表 | 真机 WebView 的 DOM 抽取（M10，本次未跑到） |
| Hilt 生成的组件能被实例化到 `Application.onCreate()` 完成 | 真机时序下的 ANR 边界（软件模拟更慢，反而更宽松） |

> **不要把它读成"真机验证通过"。** 它是"模拟器验证通过"，价值在于把
> 「零执行」变成「有执行」，而不是把「未验证」变成「已验证」。

---

## 1. 结果

| # | 用例 | 状态 | 耗时 | 说明 |
|---|---|---|---|---|
| M0 | 环境预检 | ✅ PASS | 0ms | adb / 设备 / SDK / APK 齐备 |
| M1 | 解析 AGP 实际 KGP 版本 | ✅ PASS | 16,285ms | Kotlin 2.3.21 |
| M2 | debug APK 产物在场 | ✅ PASS | 44ms | 95.62 MB |
| M3 | APK 可安装 | ✅ PASS | 385,836ms | `pm install` 成功（软件模拟下安装极慢） |
| M4 | App 可冷启动到首页 | ✅ PASS | 157,069ms | 进程存活、前台为 `com.ake.app.MainActivity`、无 crash/ANR |
| M5 | Room 建库（真机 SQLite 上 v3 schema） | ✅ PASS | 110,172ms | `user_version=3`、20 张表、`integrity_check=ok` |
| M6 | Hilt 运行时图 + 遍历四 tab | ⏭️ SKIP | 47,562ms | `uiautomator dump` 返回 `null root node` |

**统计：PASS 6 / FAIL 0 / SKIP 1。** 判定：**未决（NOT-VERIFIED）** —— 没有 FAIL，但 M6 没跑到，门禁既不能放行也不能说没问题。

M6 的 SKIP 是**宿主能力边界**，不是 App 缺陷：软件渲染 + 无窗口的 emulator 上 `UiTestAutomationBridge` 拿不到 root node。它需要真机或带 GPU 的模拟器。

---

## 2. 这次跑出了什么真问题

**三个都是验证脚本自己的 bug，不是 App 的 bug。** 但它们的表现都是"看起来像 App 坏了"，这正是这类脚本最危险的地方。

### 2.1 M4 把 launcher activity 拼错了 → 误报「App 起不来」

脚本硬编码 `f"{package}/.MainActivity"`，解析出 `com.ake.harnessagent.MainActivity`。
而 APK 里真正的 launcher 是 `com.ake.harnessagent/com.ake.app.MainActivity`
（`applicationId` 与源码包名不同：`applicationId = com.ake.harnessagent`，`MainActivity` 在 `com.ake.app` 下）。

`am start` 于是返回：

```
Error type 3
Error: Activity class {com.ake.harnessagent/com.ake.harnessagent.MainActivity} does not exist.
```

**这条如果被当成结论，整个项目会被误判为"装了打不开"。**
修法：从设备实际解析结果取（`cmd package resolve-activity --brief -c android.intent.category.LAUNCHER`），失败才回退旧值。

### 2.2 M4 把 `am start -W` 的 `Status: timeout` 当成失败

软件模拟器上 `am start -W` 25 秒超时返回 `Status: timeout`，但此时：

```
topResumedActivity=ActivityRecord{... com.ake.harnessagent/com.ake.app.MainActivity t7}
```

**Activity 已经在前台了。** `Status: ok` 只是"在 -W 的等待窗口内完成"的意思，慢设备上本就不该强求。
修法：判据改为「无活动管理错误」（不含 `does not exist` / `Error type`），
"是否真起来"由「前台 Activity 属于本包」那条独立判定。`Status: ok` 时才检查 `ThisTime`。

### 2.3 M5 用 `/sdcard` 中转拉数据库 → 恒为 0 字节

拉库走 `run-as cp databases/ake_agent.db /sdcard/...` 再 `adb pull`。
实测：`run-as` 可读、`/sdcard` 中转失败 ⇒ 拉到 0 字节 ⇒ 报
「包非 debuggable / 库还没建 / run-as 不可用」，**而当时库好好的**（440KB WAL、20 张表）。

修法：首选 `adb exec-out run-as pkg cat <file>`，把 stdout **直接指向本地文件**
（二进制安全，不经过任何字符串解码）；`/sdcard` 中转降为 fallback。

### 2.4 附带的真机行为发现（非缺陷）

首次启动会请求 `POST_NOTIFICATIONS`，未授予时 `GrantPermissionsActivity` 覆盖前台，
导致 `am start -W` 直接超时。**这是正常的运行时权限流程**，但意味着：
CI / 自动化验证必须先 `pm grant` 或 `install -g`，否则每条用例都会卡在权限弹窗上。

---

## 3. 这次没跑到的

| 用例 | 为什么没跑 | 需要什么 |
|---|---|---|
| M6 | `uiautomator` 在无窗口软件渲染下拿不到 root node | 带 GPU 的模拟器 / 真机 |
| M7–M9 | 需要 2.6GB 模型下载与加载 | 网络 + 至少 8GB 可用内存 | 
| M10 | 需要真 WebView 与真实站点 | **真机或带 GPU 模拟器**（咽喉项） |
| M11–M16 | 依赖 M8–M10 的前置 | 同上 |
| M17–M21 | 性能 / SoC 矩阵 / 热节流 | **必须真 SoC** |
| M22–M27 | 迁移老包、冷启兜底、写失败恢复 | 需要 `--old-apk` 与可注入故障的设备 |

---

## 4. 环境事实（供复现）

| 项 | 值 |
|---|---|
| SDK | `/opt/android-sdk`，build-tools 36.0.0，platforms android-36 |
| system-image | `system-images;android-36;google_apis;x86_64`（4.3 GB） |
| emulator | 863 MB，`-no-accel`（**宿主无 `/dev/kvm`、CPU 无 vmx/svm**） |
| AVD | `ake_test`，pixel_6，`hw.ramSize=4096M` |
| 冷启 | 280,898 ms |
| ReDroid | **不可用** —— 镜像可拉（2.05 GB）但宿主内核无 `binder` / `ashmem`，容器 20 秒退出 |
| 生命周期 | emulator 进程随 shell 回合结束被回收，**无法跨轮常驻**；一次完整验证必须在同一回合内跑完 |

---

## 5. 结论

- **代码侧无新增 P0。** M0–M5 全过，说明"装得上、起得来、库建得出、Hilt 图完整"这四件事在真 Android 上成立。
- **验收判定维持 NO-GO**，但理由进一步收窄到：**M6 及之后的用例尚未执行**，而不是"从未执行过任何指令"。
- **下一个最该跑的仍然是 M10**（DOM 抽取，咽喉项）——它需要能提供真 WebView 的环境。
- 本次修改的三个脚本 bug 已修，`--selftest` 仍全绿（防"修一个坏一个"）。

---

*[财报研究员分析信号] 本记录不含金融内容，此标记按团队交付惯例保留。*
