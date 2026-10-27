# Google Play Data Safety 表单申报（v1 · 2026-10 · 韩渡川）

> 本文是**照着代码填的**，不是模板。每一项后面都给了代码依据。
> 与代码不一致的申报是 Play 拒审的头号原因（"Invalid Data safety form"），
> 所以本文件的维护纪律是：**改了落盘/联网行为，必须先改这里**。

## 0. 先给结论：这个 App 最刺眼的一条

**它以用户的登录态访问第三方网站，并且把访问过程完整录了下来。**

- `browser/WebViewFactory.kt`：`CookieManager.getInstance().setAcceptCookie(true)` —— WebView 用**真实 Cookie**。
- 站点侧看到的是一个已登录的用户在浏览；我们侧把 host / 路径 / DOM 快照留在了本机。
- 这不是"我们收集了数据"，而是"我们代替用户去登录态浏览，并且留下了录像"。

按 Google 的官方定义逐条套（见 §2），这件事**必须申报**，且我们**拿不到"用户自行浏览开放网络"的豁免**，
因为 `:browser` 会向页面注入 JS 并抽取 DOM —— 属于"App 控制了 webview 内交付的代码/行为"。

---

## 1. 数据到底落在哪（代码清单）

`WipeEverythingUseCase`（`data/.../persist/wipe/`）是"什么算痕迹"的权威定义。它删的东西 = 我们落盘的东西。

### 1.1 Room 库 `ake_agent.db`（`AkeDatabase.VERSION = 3`，19 张表）

| # | 表 | 关键列 | 是否加密 |
|---|---|---|---|
| 1 | `agent_session` | — | — |
| 2 | `task` | `goal_enc`、`answer_enc` | ✅ |
| 3 | `step` | — | — |
| 4 | `tool_invocation` | `input_args_enc`、`output_text_enc` | ✅ |
| 5 | `observation` | **`host`（明文）**、`url_path_enc`、`title_redacted`、payload 指针 | ⚠ 混合 |
| 6 | `trace_event` | — | — |
| 7 | `policy_decision` | — | — |
| 8 | `task_checkpoint` | `scratchpad_enc` | ✅ |
| 9 | `memory_card` | `content_enc` | ✅ |
| 10 | `model_asset` | — | — |
| 11 | `tool_registry` | — | — |
| 12 | `mcp_server` | 用户自配的 MCP 端点 | — |
| 13 | `app_settings` | — | — |
| 14 | `agent_profile` | `persona_charter_enc` | ✅ |
| 15 | `chat_session` | `draft_enc` | ✅ |
| 16 | `chat_message` | `content_enc` | ✅ |
| 17 | `agent_capability_grant` | — | — |
| 18 | `template_registry` | `disclaimer_enc` | ✅ |
| 19 | `route_decision` | — | — |

加密密钥：AndroidKeystore 条目 `ake.trace.dek.v1`（`WipeEverythingUseCase.KEY_ALIAS`）。

### 1.2 外置目录

| 路径 | 内容 | 加密 |
|---|---|---|
| `filesDir/trace/<taskId>/*.pdom.gz` | DOM 快照（PocketDom，已脱敏） | ❌ **仅 deflate 压缩**（`PayloadStore.write` 只有 `DeflaterOutputStream`，无 cipher） |
| `filesDir/chat/<sessionId>/*.txt.gz` | 聊天消息 payload + checkpoint envelope | ❌ 同上 |
| `filesDir/agents/` | 人格头像 | ❌ |
| `filesDir/models/` | Gemma 4 权重（~2.6 GB） | ❌（模型本来就是公开权重） |
| `dataDir/app_webview` | WebView 的 Cookie / Cache / LocalStorage / History / FormData | 由 WebView 管理 |
| `filesDir/{datastore,mcp,settings}` | 历史兼容残留 | ❌ |

### 1.3 出设备的东西（这才是申报的对象）

| 出设备路径 | 内容 | 去向 |
|---|---|---|
| WebView 请求 | 用户 Cookie、URL、表单输入 | **用户指定的第三方站点** |
| 模型 manifest / 权重下载 | HTTPS GET | `https://cdn.ake.cn/...`（构建期注入，见 `data/build.gradle.kts`） |
| MCP | 由用户在 `mcp_server` 表里自配 | 用户自己填的端点，可能远端 |

### 1.4 明确**不出**设备

用户 goal/prompt、模型推理、DOM 抽取、记忆、聊天记录 —— **全部端侧**。
`allowBackup="false"` + `data_extraction_rules.xml`（cloud-backup 与 device-transfer 全 exclude）
⇒ 备份与换机迁移都带不走。

---

## 2. 判定依据（Google 官方定义，逐条对照）

| Google 的规则 | 本项目怎么套 |
|---|---|
| "Collect" = 从设备**传出**数据 | 端侧处理 ⇒ **不在申报范围** |
| 端侧访问/处理 ⇒ 无需申报为 collected | goal / 聊天 / 记忆 / 推理：**不申报** |
| WebView：App 控制其代码/行为 ⇒ **需申报**；用户自行浏览开放网络 ⇒ 不需 | 我们注入 JS + 抽 DOM ⇒ **从严按需申报** |
| "Shared" 豁免之一：用户主动发起 / 显著披露 + 同意 | **当前代码里没有这条显著披露**（`ui/.../strings.xml` 无对应文案）⇒ 从严，**申报为 shared** |
| 端到端加密且开发者不可读 ⇒ 无需申报 | 不适用 |

---

## 3. 表单该这么填

### 3.1 逐数据类型

| 数据类型（Play Console） | Collected | Shared | 用途 | 依据 |
|---|:--:|:--:|---|---|
| **Web browsing → Web browsing history** | **是** | **是** | App functionality | `observation.host` / `url_path_enc`；WebView 带真实 Cookie |
| **Personal info → Other info types**（页面内容可能含个人信息/金融信息） | **是** | **是** | App functionality | 以登录态访问 ⇒ 读到的页面可能含用户个人信息；DOM 快照落盘 |
| **App activity → Other user-generated content**（用户 goal / 聊天 / 记忆） | 否 | 否 | — | 端侧处理，按 Google 定义不属 collected。**隐私政策里仍要写** |
| App info and performance → Crash logs | 否 | 否 | — | 无 Crashlytics / Firebase / Sentry（已核查 `gradle/libs.versions.toml`） |
| Device or other IDs | 否 | 否 | — | 不收集任何 ID |
| Location / Contacts / Photos / Audio / Calendar / Messages | 否 | 否 | — | 未申请对应权限（见 `AndroidManifest.xml`） |

### 3.2 三个追问

| 问题 | 填什么 | 说明 |
|---|---|---|
| 是否加密传输（encrypted in transit） | ⚠ **不能无条件勾"是"** | 模型下载 `https://cdn.ake.cn` 是 HTTPS（`ManifestParserTest` 明确拒绝 `http://`）；但**用户指定的第三方站点是否 HTTPS 不由我们控制** |
| 是否 ephemeral（仅内存、用完即弃） | **否** | 我们持久化到 Room 与 `filesDir` |
| 是否提供删除入口 | **是** | App 内「一键清除全部痕迹」→ `WipeEverythingUseCase` |
| 收集是必需还是可选 | **必需** | 关掉它就没有这个 App |

---

## 4. 如实说：哪几条"按现有代码申报会很难看"

不粉饰，列全：

1. **Web browsing history 是 Collected + Shared 双勾选。** 绝大多数端侧 App 能勾"无收集"，我们不能。
   这是本 App 的本质，不是 bug —— 但它在商店页面上会显示成"会共享浏览记录"，**必须接受**。
2. **拿不到"用户主动发起"的 shared 豁免**：Google 的豁免前提是 App 内有 prominent disclosure + 同意。
   目前代码里没有。**若要降到"仅 Collected"，必须先补这个披露页** —— 那是产品/法务的活，不是本文能代劳的。
3. **`observation.host` 是明文**（设计如此：域名策略要精确等值查询，加密会破坏它，见 `TraceEntities.kt` 注释）。
   于是"数据静态加密"只对 `*_enc` 列成立，**不能笼统说"全部加密存储"**。
4. **DOM 快照文件只压缩不加密**（`filesDir/trace/*.pdom.gz`）。已脱敏（`redaction_count` / `title_redacted`），
   但脱敏 ≠ 加密 —— 隐私政策里不要写成"加密保存"。
5. **"加密传输"不能勾满**：第三方站点不受我们控制。
6. **`ModelDownloadService` 用 `specialUse` 有被判为更像 `dataSync` 的风险** —— 见 `specialuse-fgs.md` §4。

## 5. 复核清单（每次发版前过一遍）

- [ ] `observation` 的 host 是否仍明文（若是，§4.3 继续如实披露）
- [ ] 是否新增了外置落盘目录（若新增，`WipeEverythingUseCase` 与本文 §1.2 必须同步）
- [ ] 是否引入了任何 SDK（一旦引入 analytics/crash 类，Crash logs / Device ID 两行立刻要改）
- [ ] 是否补上了"以登录态访问"的显著披露页（补上后 §3.1 的 Shared 才能降）
