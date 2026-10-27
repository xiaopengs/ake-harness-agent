# 两个 `specialUse` 前台服务的声明与演示视频脚本

（2026-10 · 韩渡川）对应 README 的 P2：*两个 `specialUse` 前台服务各需演示视频*。
本文给出：**类型 → 为什么需要 → 视频逐镜头脚本**。脚本是可以直接照着拍的，不是泛泛描述。

---

## 1. 现状：`AndroidManifest.xml` 里到底声明了什么

```
app/src/main/AndroidManifest.xml
├── <uses-permission FOREGROUND_SERVICE />                      （Android 9+ 通用）
├── <uses-permission FOREGROUND_SERVICE_DATA_SYNC />            ⚠ 见 §4：当前无人使用
├── <uses-permission FOREGROUND_SERVICE_SPECIAL_USE />          （Android 14+ 必需）
│
├── service com.ake.app.execution.AgentExecutionService
│     android:foregroundServiceType="specialUse"
│     <property PROPERTY_SPECIAL_USE_FGS_SUBTYPE
│       value="Runs on-device Gemma 4 inference to complete browser tasks requested by the user." />
│
└── service com.ake.app.download.ModelDownloadService
      android:foregroundServiceType="specialUse"
      <property PROPERTY_SPECIAL_USE_FGS_SUBTYPE
        value="Downloads the on-device Gemma 4 model file requested by the user." />
```

两条 subtype 文案已经就位 —— M20 的静态判据（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 出现 ≥2 次、
两个服务名都在 manifest 里）**当前是通过的**。缺的是演示视频。

---

## 2. 服务一：`AgentExecutionService`

| 项 | 内容 |
|---|---|
| 类型 | `specialUse` |
| 干什么 | 任务执行期间保持进程存活：端侧 Gemma 4 推理 + WebView 逐页浏览，8 步任务约 36 秒 |
| 为什么不用别的类型 | 逐条排除：`dataSync`=数据传输、`mediaPlayback`=音视频、`location`=定位、`camera`/`microphone`=采集、`connectedDevice`=外设、`health`/`remoteMessaging`/`systemExempted` 都不适用。**"在设备上跑本地模型推理"没有任何一个既有类型能装下** —— 这是 `specialUse` 唯一正当的用法：确实没有别的类型匹配 |
| 用户可见性 | 常驻通知 + 通知上的「中止」按钮；没有它用户无法在推理途中干预 |
| 用户可控性 | 通知上直接停止；App 内也可中止 |

**Play 要求的"显著披露"**：启动任务前必须让用户看见"我正在以你的登录态访问网站并跑本地推理"。
⚠ 这条**目前还没有**（`ui/.../strings.xml` 里没有对应文案）—— 它同时是
`data-safety.md` §4.2 拿不到 shared 豁免的原因。**要先补它，再提交。**

---

## 3. 服务二：`ModelDownloadService`

| 项 | 内容 |
|---|---|
| 类型 | `specialUse` |
| 干什么 | 下载约 2.6 GB 的端侧模型权重，持续数十分钟，必须可见、可取消 |
| 为什么需要前台服务 | 2.6 GB 不可能在后台网络里悄悄下完；用户必须看到进度并能随时叫停 |
| 风险 | ⚠ **见 §4** —— 它有被判为更像 `dataSync` 的可能 |

---

## 4. ⚠ 两个必须提前想清楚的风险

### 4.1 `ModelDownloadService` 可能应该叫 `dataSync`

Google 对 `dataSync` 的定义包含"由应用发起的数据传输（上传/下载、备份/恢复）"。
**下载一个用户主动请求的 2.6 GB 模型，字面上是能套进去的。**

`specialUse` 的规则是「**没有**其他类型适用时才能用」。如果审核认为 `dataSync` 适用，
我们会因为"用了不该用的类型"被拒。

- **退路**：把 `ModelDownloadService` 改成 `foregroundServiceType="dataSync"`，
  并把 `FOREGROUND_SERVICE_DATA_SYNC` 权限从"声明但没人用"变成"真的在用"。改动只在 manifest。
- **为什么现在不改**：`dataSync` 在系统里有更严格的超时与配额限制（系统会主动停掉长时间运行的
  dataSync 前台服务），2.6 GB 在被中途停掉的场景下体验极差。**先用 specialUse 申报，被拒再退到 dataSync。**
- 注意：`AgentExecutionService` **没有**这个退路 —— 端侧推理确实无类型可套，它必须留在 `specialUse`。

### 4.2 多了一条没人用的权限

`FOREGROUND_SERVICE_DATA_SYNC` 已声明，但两个服务都用的是 `specialUse`，
**当前没有任何服务声明 `dataSync`**。多余的 FGS 权限声明在审核里是减分项（"声明了你没做的事"）。

- 若 §4.1 的退路走到，这条权限正好用上；
- 若 `specialUse` 通过，**应删掉这条权限声明**（只改 manifest，不影响代码）。

---

## 5. 演示视频脚本

通用要求（两段都要遵守）：

- 时长 30–60 秒，**一镜到尾尽量不剪辑**，不要加速。
- 必须拍到**状态栏的通知图标**，证明服务在前台。
- 必须拍到**用户主动点了什么**，证明是用户发起。
- 用真机，不要用模拟器；机型与 Android 版本在视频里可见（设置页露一下更好）。
- 输出到同一个目录，文件名固定，交给 M20：`./run.py --only M20 --video-dir <该目录>`。
  M20 的判据是「≥2 个 `.mp4`/`.webm` 且每个 > 1 KB」—— 别交占位文件，那个判据挡不住占位文件，
  但审核挡得住。

### 5.1 `fgs-agent-execution.mp4` —— 端侧推理前台服务

| 时间 | 画面 | 必须拍到的东西 |
|---|---|---|
| 0:00–0:05 | 桌面点图标启动 App，停在聊天主界面 | App 在前台 |
| 0:05–0:12 | **手指点击**输入框，输入一个真实 goal（例如"打开我的订单页，抄下最近 5 条订单号"），点发送 | **用户主动发起** |
| 0:12–0:20 | 下拉通知栏，停住：常驻通知"正在执行任务 · 第 N 步"，下方有「中止」按钮 | 前台服务通知 + 用户可停止 |
| 0:20–0:45 | 回到 App：WebView 逐页加载，步骤推进，底部显示当前步 | 任务真的在跑 |
| 0:45–0:52 | 任务完成，通知变成"已完成"，点通知回到 App 看到结果 | 终态 |
| 0:52–1:00 | 再发一个 goal，跑到中途点通知上的「中止」，画面显示任务被停止 | **用户可随时叫停** |

加分镜头（可选，插在 0:45 后）：**打开飞行模式再跑一次同样的 goal** —— 证明推理在端侧，
这段能同时支撑"数据不出设备"的主张。

### 5.2 `fgs-model-download.mp4` —— 模型下载前台服务

| 时间 | 画面 | 必须拍到的东西 |
|---|---|---|
| 0:00–0:08 | 首次启动，停在"需要下载端侧模型（2.6 GB）"确认页 | **用户主动确认** |
| 0:08–0:15 | 点「下载」，立刻下拉通知栏：进度通知显示百分比与「取消」按钮 | 前台服务通知 + 可取消 |
| 0:15–0:40 | 按 Home 退回桌面，**打开另一个 App 并滚动使用**（如下拉刷新一个网页） | **后台持续下载**，通知仍可见 |
| 0:40–0:48 | 回通知栏点「取消」，通知消失 | 用户叫停 |
| 0:48–0:55 | （可选佐证）`adb shell run-as com.ake.harnessagent ls filesDir/models` —— 无完整权重文件 | 取消是真的取消了 |
| 0:55–1:10 | 重新点下载，进度走到 100%，通知变为"下载完成"后消失，App 进入可用状态 | 完整成功路径 |

### 5.3 交付物

```
compliance-videos/
├── fgs-agent-execution.mp4      # §5.1
└── fgs-model-download.mp4       # §5.2
```

上传到 Play Console 的「前台服务类型」申报页，同时把该目录路径交给 M20。

---

## 6. 提交前检查表

- [ ] 两个服务的 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 都在 manifest 里（M20 静态判据已覆盖）
- [ ] App 内有"以你的登录态访问网站"的显著披露 + 同意（§2 / `data-safety.md` §4.2）
- [ ] 两段视频按 §5 拍完，放在同一目录
- [ ] `./run.py --only M20 --video-dir <目录>` 通过
- [ ] 决定 §4.1 退路：`specialUse` 被拒则 `ModelDownloadService` 改 `dataSync`
- [ ] 若 `specialUse` 通过，删掉没人用的 `FOREGROUND_SERVICE_DATA_SYNC`（§4.2）
