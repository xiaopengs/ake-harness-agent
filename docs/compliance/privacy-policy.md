# 隐私政策

版本 v1 · 生效日期 2026-10-27
发布者：阿客实验室（AkeLab） · 联系：privacy@ake.cn

> 使用中英文不一致时，**以中文版为准**（中文版是发布者签署的版本）。

---

# 隐私政策（中文）

## 一、一句话说明

**Ake Harness Agent 是一个完全在你手机上运行的智能体。** 你输入的指令、模型的推理过程、
它对网页的读取结果，全部在你设备内完成，**不会上传到我们的服务器，我们也没有服务器来接收它们**。

唯一的例外，也是你必须知道的一件事：

> **它会以你已登录的身份去访问网站。** 当你让它"去某某网站把我的订单抄下来"时，
> 它打开的是一个**带着你真实 Cookie 的浏览器**。那个网站看到的是"你本人正在浏览"，
> 而不是"一个匿名程序在抓取"。这一点我们无法绕过，也不应绕过 —— 否则它就完成不了你交代的事。

## 二、我们收集什么、存在哪

### 2.1 保存在你设备上（不出设备）

| 内容 | 存在哪 | 是否加密 |
|---|---|---|
| 你输入的任务指令、模型的回答 | 本地数据库 `task` 表 | 是（Android Keystore 密钥） |
| 每一步的工具入参与输出 | `tool_invocation` 表 | 是 |
| 访问过的**域名** | `observation` 表的 `host` 列 | **否（明文）** —— 域名需要被精确匹配才能执行你的访问策略，加密会使这个功能失效 |
| 访问过的**路径与参数** | `observation` 表的 `url_path_enc` | 是 |
| 页面快照（已脱敏） | `filesDir/trace/*.pdom.gz` | **否** —— 仅压缩。已做脱敏处理（去掉敏感字段），但不是密文 |
| 你的聊天记录、Agent 人设、记忆 | 本地数据库 | 是 |
| 端侧模型权重（约 2.6 GB） | `filesDir/models/` | 模型本身是公开权重，无需加密 |

这些数据的**备份与换机迁移已被关闭**（`android:allowBackup="false"` 且 Android 12+ 的备份/换机规则里
云端与设备到设备两条通道全部排除），不会跟着云备份或换机被带走。

### 2.2 会离开你设备的数据

1. **发往你指定网站的数据**：URL、你的 Cookie、以及在页面上填写的内容。
   接收方是**你让 Agent 访问的那个网站**，适用该网站自己的隐私政策，我们无法替它做承诺。
   网站是否使用 HTTPS 也由该网站决定，我们无法强制。
2. **模型下载**：首次使用时从 `https://cdn.ake.cn` 下载模型清单与权重文件（HTTPS）。
   该请求会向我们的 CDN 暴露你的 IP 地址，我们不把它与任何账号关联。
3. **MCP 扩展**：如果你自己在 App 内配置了 MCP 服务端，数据会发往**你填的那个地址**。
   我们不提供、不代管、也不预设任何 MCP 服务端。

### 2.3 我们**不**收集

- 不收集设备标识符、广告 ID、联系人、位置、照片、通讯录、日历。
- 不接入任何统计分析、崩溃上报或广告 SDK —— 应用里没有这类组件。
- 不做用户画像，不出售数据。

## 三、数据保留与删除

数据默认一直保存在你的设备上，直到你删除它。

**App 内「一键清除全部痕迹」会**：先销毁 Android Keystore 中的加密密钥（此时所有加密列在数学上永久不可恢复），
再停掉在途写入，然后删除本地数据库、页面快照、聊天文件、人格头像，以及 WebView 的
Cookie / 缓存 / LocalStorage / 表单数据 / 历史记录。模型文件默认保留（约 2.6 GB，重新下载的代价很高），
你可以单独选择一并删除。

如果其中某一步失败（例如文件被占用），App 会**如实告诉你哪一步没删掉**，不会假装成功。

## 四、儿童

本应用不面向 13 岁以下儿童，也不会明知地收集儿童数据。

## 五、变更

政策若有实质变更，我们会在应用内与本页面提示，并更新顶部版本号。

## 六、联系

privacy@ake.cn

---

# Privacy Policy (English)

## 1. The short version

**Ake Harness Agent is an agent that runs entirely on your phone.** Your instructions, the model's
reasoning, and everything it reads from web pages are processed on-device. They are **not uploaded to
any server of ours — we operate no server that receives them.**

There is one exception, and you need to know it:

> **It visits websites as you, while you are logged in.** When you ask it to "go to my account page and
> copy my last five orders," it opens a browser **carrying your real cookies**. The site sees *you*
> browsing — not an anonymous scraper. We cannot bypass this, and we should not: without it, the
> task you asked for cannot be done.

## 2. What is stored, and where

### 2.1 Kept on your device (never transmitted)

| Data | Where | Encrypted |
|---|---|---|
| Your task prompt and the model's answers | local DB, `task` table | Yes (Android Keystore key) |
| Tool inputs and outputs per step | `tool_invocation` table | Yes |
| **Domain names** you visited | `observation` table, `host` column | **No (plaintext)** — the domain must be matched exactly to enforce your access policy; encrypting it would break that |
| **URL path and query** | `observation` table, `url_path_enc` | Yes |
| Page snapshots (redacted) | `filesDir/trace/*.pdom.gz` | **No** — compressed only. Sensitive fields are removed, but this is not ciphertext |
| Your chat history, agent personas, memory | local DB | Yes |
| On-device model weights (~2.6 GB) | `filesDir/models/` | Weights are public; no encryption needed |

Backup and device-to-device transfer are **disabled** (`android:allowBackup="false"`, and both the
cloud-backup and device-transfer channels are excluded in the Android 12+ extraction rules), so this
data does not follow your cloud backup or phone migration.

### 2.2 What leaves your device

1. **Data sent to the sites you name**: URL, your cookies, and anything filled into a page.
   The recipient is **the site you asked the agent to visit**; that site's own privacy policy applies
   and we cannot make promises on its behalf. Whether it uses HTTPS is also the site's choice.
2. **Model download**: on first use, the app fetches the model manifest and weight files from
   `https://cdn.ake.cn` over HTTPS. This exposes your IP address to our CDN; we do not link it to any
   account.
3. **MCP extensions**: if you configure an MCP server yourself, data goes to **the address you entered**.
   We provide, host, and preconfigure no MCP servers.

### 2.3 What we do **not** collect

- No device identifiers, advertising IDs, contacts, location, photos, or calendar data.
- No analytics, crash-reporting, or advertising SDK is bundled — the app contains none.
- No profiling. We do not sell data.

## 3. Retention and deletion

Data stays on your device until you delete it.

**"Erase all traces" in the app** first destroys the encryption key in the Android Keystore (at which
point every encrypted column becomes permanently unrecoverable), stops in-flight writes, then deletes
the local database, page snapshots, chat files, persona avatars, and the WebView's cookies, cache,
local storage, form data and history. Model files are kept by default (re-downloading ~2.6 GB is
expensive); you can choose to delete them too.

If any step fails — a file in use, for example — the app **tells you which step failed** rather than
reporting success.

## 4. Children

Not directed to children under 13; we do not knowingly collect children's data.

## 5. Changes

Material changes will be announced in-app and on this page, with the version number at the top updated.

## 6. Contact

privacy@ake.cn

---

## 附：发布前必须完成的两件事

1. 把本文挂到一个**公开可访问、无需登录、无地域限制**的 URL（Play 拒审的常见原因是
   "URL provided does not link to a valid privacy policy page"：失效证书 / 登录墙 / 跳转到首页 / 地域屏蔽）。
   该 URL 同时是 M21 的判据输入：`./run.py --only M21 --privacy-url <该 URL>`。
2. 应用内要有入口能打开该 URL（Google 要求"应用内也要有链接"）。
