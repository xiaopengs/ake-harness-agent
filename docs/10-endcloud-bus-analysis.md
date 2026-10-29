# 《本地端 GUI Agent》· 端云是否需要"通信总线"：架构分析

> 撰写：顾图南（Silas Frame）· CVE 阿客实验室研发部　主理人岑执衡调度
> 上游：`docs/05` §6（端云协同 Harness 框架）／`docs/08`（端云双栈架构）／`docs/09`（v3→v4 与密钥处置）
> 本轮**只读分析**，不改任何 `.kt` / `.gradle.kts`。`docs/01/05/08/09` 不覆写。
> 所有结论以**当前工作区源码**为准（`:cloudai` / `:inference` / `:core:ports` / `:chat` / `:app` 逐一读核）。

---

## 0. 先给三条结论（后面逐条论证）

| # | 结论 |
|---|---|
| **C1** | **今天不需要总线，因为今天不存在"两个 harness"。** 云端那侧是一个**无状态补全端点适配器**，九项 harness 构成要素一项都不具备。"两个对等 harness 之间的通信总线"在当前架构里是**伪命题**——不是"现在不做"，而是**它的所指不存在**。 |
| **C2** | **总线与三条红线在结构上互斥。** 总线要成立，对端必须有执行权或至少能主动发起；而红线钉死了"端侧唯一执行权 + 云端无执行权 + 端侧握最终裁决权"。⇒ 只要红线不变，阶段 2（对等协同）**不可能被触发**。这一点应当写进设计意图，而不是留成"以后再说"。 |
| **C3** | **要埋缝隙，但只有 4 处，全是加法，不逼 `:app` 装配与 UI 押后。** 唯一带编译期涟漪的一处（`DialogTargetCodec` 的未知态）**恰恰现在做最便宜**——因为 `:app` 还没接线、`:chat` 还在用 `ModelPort` + `LocalOnlyRoutingPort`。 |

---

## 1. 核实结果（先摆事实，含三处与转述不一致）

### 1.1 已核实属实

| 项 | 核实 |
|---|---|
| `:cloudai`（L3，kotlinJvm）+ `:inference`（L4，kotlinJvm）已建 | ✅ `settings.gradle.kts` L83-84；根白名单 L93 `put(":inference", archCoreModules + ":cloudai")` |
| `:cloudai` 依赖白名单**刻意不含 `:core:contract`** | ✅ `cloudai/build.gradle.kts` 注释明写；且 `:core:ports` 已把 `:core:contract` 由 `api` 收紧为 `implementation`（`core/ports/build.gradle.kts` L40）⇒ **双重保险落地** |
| 云端不发 `tools` | ✅ `ModelCapabilities.toolCalls` 恒 `false`（`Dialog.kt` L94-95） |
| `CloudFailure` 17 变体 + `EgressPermit` + `PlainSecret` + `CloudCredentialPort` | ✅ `CloudEgress.kt` 全文核实；且 `PlainSecret` 已按主理人裁决**从 `value class` 退回 `final class`**（L72-88，理由：JVM 会生成 public 的 `unbox-impl()`） |
| `FallbackPolicy`（PRE/POST 分相）、`EffectiveWindow` | ✅ `inference/` 下四个文件在 |

### 1.2 ★★ 三处需要修正的事实（影响第 5 条的判断）

#### 修正 A：**`DialogRoutingPort` 已写好，但运行时路径还没接上它**

```
RoutingPort 实现有两个并存：
  :chat  ... /ChatEgress.kt        L116  LocalOnlyRoutingPort   ← MVP，硬返回 LOCAL_ONLY
  :inference .../DefaultModelRouter.kt L115  DialogRoutingPort  ← 新的，有 TaskKind 判定
EgressGate 实现只有一个：
  :chat  ... /ChatEgress.kt        L158  LocalOnlyEgressGate    ← 从未真正放行过一次
```

而 `:chat` 的 DI 门面 `ChatServices` 仍然绑的是 MVP 那一对：

```kotlin
// chat/.../ports/ChatServices.kt L110-112
public val routing: RoutingPort = LocalOnlyRoutingPort(clock)
public val egressGate: EgressGate = LocalOnlyEgressGate()
// L74: private val model: ModelPort,   ← 仍是 ModelPort，不是 DeviceModelPort / DialogModelPort
```

⇒ **docs/08 的路由裁决以"模块"形式落地了，但还没有进入运行时图。**
⇒ 对第 5 条的含义：**缝隙现在埋是"加法"；等 `:app` 接完线再埋就是"返工"。**

#### 修正 B：**`:app` 组合根完全没有接线 `:cloudai` / `:inference`**

```
app/build.gradle.kts 的 project(...) 里：core:common / contract / ports / model-api
                                        / :llm / :browser / :agent / :mcp / :data / :chat / :ui
                                        ★ 没有 :cloudai，没有 :inference
app/src/main/java/com/ake/app/di/ 九个 Module，零处引用 com.ake.cloudai / com.ake.inference
```
（唯一命中是 `CoreModule.kt` L38 的 `@InferenceDispatcher` 注解，与本话题无关。）

⇒ 用户第 5 条问的"要不要押后 `:app` 装配"**正是当下**。下文 §5 的回答是：**不用押后**。

#### 修正 C：**`EgressCoordinator` 被引用但不存在**

```kotlin
// cloudai/.../CloudChatRunner.kt L14（注释）
// 重试次数与 permit 的关系由 :inference 的 FallbackPolicy / EgressCoordinator 管。
```
全仓搜索 `EgressCoordinator` ⇒ **只有这一处注释，没有类型、没有实现。**

这是一个**正在张开的缝隙**：permit 生命周期 + 重试计数 + 审计收敛 + 回落决策这四件事目前**没有归属**。若不在 `:app` 装配前定下来，它们会被就近写进 `:chat` 或 `:app`，那时再抽出来就是返工。⇒ §5 的缝隙 C 直接对应它。

### 1.3 两条对第 3 条裁决有决定性的既有事实

| # | 事实 | 出处 |
|---|---|---|
| **D1** | **MCP 客户端已存在，但被钉死为回环专用**：`McpServerConfig.DEFAULT_LOOPBACK_ONLY = true`、`isLoopback()` 认 IPv4/IPv6/localhost 三种写法；类注释明写"MVP 把这条回路**物理切断**：只连回环地址" | `core/ports/.../McpServers.kt` L44-77 |
| **D2** | **`CheckpointEnvelope` 未实现**，v3 迁移里只有一行预留注释："CheckpointEnvelope 外置【预留接口：MVP 只建列，不生成文件】" | `data/.../db/ChatMigration2To3.kt` L69 / `TaskEntities.kt` L189 |

⇒ D1 意味着：**§6.3 的 B 通道（端侧作为 MCP Server）不仅"没开"，其**对偶方向**（端侧作为 MCP Client 出站）也被限死在本机**。这是今天离"能力总线"最近的一块，而它是**故意关着的**。

---

## 2. 【问题 1】先拆概念：两端各有一个 "harness" 吗？

### 2.1 先给 harness 一个**可数的**定义（不用形容词，用清单）

从本仓库源码里抽出"端侧 harness"实际具备的九项构成要素：

| # | 构成要素 | 端侧的实现（有出处） |
|---|---|---|
| 1 | **循环** | `AgentLoop`（ReAct 主循环）、`ChatLoop.turn`（轮次循环） |
| 2 | **工具/能力注册表 + 调用** | `SkillRegistryPort` / `ToolRegistryPort` / `McpConnectionPort` |
| 3 | **状态机** | `TaskEvent` 十二终态、`MessageStatus`、`McpServerState.Phase` |
| 4 | **轨迹持久化** | `task_step` / `task_checkpoint` / `chat_message` / `StepRecord` |
| 5 | **策略执行点** | `PolicyPort` / `HumanGate` / `SensitiveActionPolicy` |
| 6 | **执行权** | `BrowserRuntime` / `WebViewExecutor` / `DomExtractBridge` |
| 7 | **资源仲裁** | `InferenceFocus` / `InferenceFocusArbiter`（F3，防 native 串字） |
| 8 | **上下文预算与压缩** | `ContextBudget` / `DeterministicCompactor` / `EngineTokenBudget` |
| 9 | **信任与隔离** | `InjectionIsolator` / `RedactionGate` / `PersistenceRedactionGate` |

### 2.2 云端那侧逐项对照

以 `OpenAiCompatibleDialogPort` + `CloudChatRunner` + `KtorCloudTransport` + `OpenAiChatWire` + `Sse` 五个文件的实际内容为限：

| # | 要素 | 云端侧 | 证据 |
|---|---|---|---|
| 1 | 循环 | **无** | `CloudChatRunner.runOnce()` 是单次调用；类注释明写"★ 它**不做重试**" |
| 2 | 工具/能力 | **无** | `toolCalls = false`；`tools` 字段一律省略（docs/08 §2.6） |
| 3 | 状态机 | **无** | `CloudDialogSession` 只是 `(spec, cfg, secret, endpoint)` 的持有者；`RehydrationMode.REPLAY_EACH_TURN` 明写"每次 generate 都要全量重放" |
| 4 | 轨迹持久化 | **无** | 无落盘；审计在端侧 `route_decision` |
| 5 | 策略执行点 | **无** | 类注释："`FallbackPolicy` 决定怎么办，不在这里" |
| 6 | 执行权 | **无** | 无任何执行面 |
| 7 | 资源仲裁 | **无** | 无（且不占用 `InferenceFocus`——这反而缓解 F3） |
| 8 | 预算与压缩 | **无** | `estimateTokens` 是本地估算 ×1.15；压缩在端侧 `DeterministicCompactor` |
| 9 | 信任与隔离 | **部分** | 有 `CloudEndpointPolicy`（TLS 强制）、`Hosts.normalise`（唯一实现）；**无**证书钉扎（见 §4.3-B4） |

### 2.3 裁决

> **云端那侧不是 harness，是一个"无状态补全函数"（stateless completion function）的客户端适配器。**
> 严格说它连"推理后端"都算不上——后端是 DeepSeek/通义/Kimi 的服务器；我们写的只是**一个 HTTP 客户端**。

因此：

**「两个对等 harness 之间的通信总线」在当前架构里是伪命题**，但不是因为"时机未到"，而是因为**两个前置条件都不成立**：

1. **不存在第二个 harness**（§2.2 九项全无）；
2. **在红线约束下，第二个 harness 不允许出现**（§3.3 论证）。

### 2.4 ★ 但"云的 harness"这个词还有第二种所指，必须分开

| 所指 | 它是什么 | 是否 harness | 与总线的关系 |
|---|---|---|---|
| **A. 我们的云端组件** | `:cloudai` 这个模块 | ❌ 不是（§2.2） | 不适用 |
| **B. 我们调用的服务商的模型服务** | DeepSeek / 通义 / Kimi 的 `/chat/completions` | ❌ 不是——它是**无状态函数**，没有循环、没有工具、不持有任务 | 不适用（调函数不需要总线） |
| **C. 用户自建网关里跑的第三方 Agent** | 例如用户在家里跑的自治 agent | ✅ **是**——它有循环、工具、状态 | ★ 这才是唯一一个"对等 harness"的候选 |

⇒ **关键区分**：今天的 `:cloudai` 只覆盖 **A 与 B**，都不构成 harness。
**C 才是总线问题的真实对象**——但 C 不是"我们的云端 harness"，它是**别人的 agent**。这属于**端 ↔ 外部主体**的集成问题（A2A / MCP B 通道的范畴），不属于"端云"这个二分法。

**这个区分决定了裁决的落点**：我们今天不该问"要不要给端和云拉一条总线"，而该问"**要不要支持用户接入一个外部 Agent 作为对等方**"。这是两个不同的产品问题，前者的答案受红线封死，后者是一个独立的产品选项。

### 2.5 业界参照（三家，用来校准而非背书）

| 参照 | 它的分层主张 | 对本项目的含义 |
|---|---|---|
| **MCP（Anthropic）** | "agent ↔ 工具/资源"，**intra-agent** 层；传输 stdio / HTTP+SSE / streamable HTTP | 本项目**已有** MCP client，且被钉死为回环专用（D1）。它就是"能力总线"的正确位置，而我们已经故意把它关在本机 |
| **A2A（Google）** | "agent ↔ agent"，**inter-agent** 层；控制模型是"**接收方 agent 决定怎么干**"，有 Agent Card 做发现、有 task 生命周期 | ★ 它的适用前提是**对端是自主 agent**。我们的对端是无状态补全端点，控制模型是"我们决定"⇒ **A2A 不适用**。只有 §2.4 的 **C 类**（用户自建的第三方 agent）才落进 A2A 的适用场景 |
| **端云 split inference** | 把 Transformer 分层切开，只传中间激活 | ★ 见 §4.4 的"传递性原则"——它对本项目的价值不是方案，而是一记**警告** |

### 2.6 被否决的备选理解

| 备选理解 | 否决理由 |
|---|---|
| "`:cloudai` 就是云 harness 的雏形，总线是它的自然演进" | 演进方向被红线封死（§3.3）。把"补全端点"说成"harness 雏形"会诱导后续按 harness 去扩它——而每扩一项都撞一条红线 |
| "MCP 已经是现成的能力总线，接上就行" | MCP 在我们这里是**入方向**（agent 调本机工具）且回环专用（D1）。把它当"端云总线"用，等于要求云端来调我们的工具 ⇒ 违反"云端无执行权" |
| "先建总线占位，将来填实现" | 空接口会被当成既有承诺，半年后有人按它写代码。本项目已有教训：`ChatEgress` 的懒构造 lambda 从未被调用——**占位不等于通行** |

---

## 3. 【问题 2】`:cloudai` 已是最薄通道吗？与总线的九个维度对比

### 3.1 对比表（逐维度，不笼统）

| 维度 | 总线的典型形态（MCP / A2A / 消息总线） | `:cloudai` 现状 | 差距性质 |
|---|---|---|---|
| **① 方向性** | 双向；对端可主动发起（A2A 的 `input-required`、webhook、SSE push） | **严格单向出站**。`CloudTransport.stream(req, onBytes)` 是唯一出口；无任何入站路径、无 webhook、无长连接监听 | **结构性缺失**（不是没写，是没有反向路径） |
| **② 状态性** | 对端持有会话/任务状态，生命周期跨请求 | **无状态**。`RehydrationMode.REPLAY_EACH_TURN` 明写"每次 generate 全量重放"；`CloudDialogSession` 只是参数持有者 | 语义不同（非缺陷，是红线要求的形态） |
| **③ 多路复用** | 一条连接承载多个逻辑会话/多种能力 | **1 HTTP 请求 = 1 逻辑会话**。HTTP/2 的多路复用是传输层的，不是逻辑层的 | 缺失 |
| **④ 服务发现** | Agent Card（`/.well-known/agent.json`）、`tools/list`、能力协商 | **零发现**。用户手填 BaseURL + 模型名；`ModelCapabilities.REMOTE_DEFAULT` 是**本地硬编码常量** | 缺失 |
| **⑤ 生命周期** | connect / session / task / stream 各层独立状态机 | 只有"一次 HTTP 请求"这一层。`McpServerState.Phase`（DISCONNECTED→READY→DEAD）这种东西在云端侧**一个都没有** | 缺失 |
| **⑥ 背压/流式** | 双向流控；至少消费端能反压生产端 | **有流式、无背压语义**。`onDelta` 无应用层流控；消费端慢 ⇒ 只靠 TCP 窗口反压 | 部分具备 |
| **⑦ 寻址** | 名字/能力寻址（tool name、agent id） | **URL 寻址**（一个端点 + 一个模型名） | 范式不同（非缺失） |
| **⑧ 错误语义** | 协议级错误码 + 重试 + 状态恢复 | ★ **`CloudFailure` 17 变体 + PRE/POST 分相 + `FallbackPolicy` 穷尽 `when`** | **已具备，且明显强于多数通用总线** |
| **⑨ 拓扑** | 多对多 | **严格 1:1**（一个 App 进程 ↔ 一个用户填的端点） | 缺失 |

### 3.2 结论

> **`:cloudai` 是"最薄的单向请求-响应通道"，不是总线。**
> 九个维度里，它在 ⑧（错误语义）上比通用总线更强，在 ⑥ 上部分具备，其余七个维度**不具备总线的任何特征**。

### 3.3 ★★ 关键判断：每一个"缺失"的维度，补上都会撞红线

这是本节最重要的一句——**"薄"不是进度落后，薄是红线约束下的正确形态**：

| 若补上这个维度 | 会得到什么 | 撞哪条红线 |
|---|---|---|
| ① 方向性（云可发起） | 云端可以请求端侧做事 | **端侧唯一执行权** + 端侧握最终裁决权 |
| ③ 多路复用（一连接多会话） | 一个出境连接承载多个会话 | 审计粒度碎化 ⇒ 无法回答"这次出境属于哪个会话"（docs/08 §7 的结构保证失效） |
| ④ 服务发现 | 云端可宣告能力 | 引入 inbound 信任问题（§6.5 的 D3 反方向），且今天无 pinning（§4.3-B4） |
| ⑤ 生命周期（对端持有状态） | 轨迹/状态在云端 | **"轨迹留在本地"**；且违背"断掉任何一根外部连线，端侧仍能完整完成承诺" |
| ⑨ 多对多拓扑 | 多个对端 | 与"用户自填一个端点"的产品形态冲突，且每次出境都要独立 permit |

⇒ **由此得出 §0 的 C2**：总线与红线**在结构上互斥**。这不是"现在不做"，是"红线不变则永不成立"。这个结论应当被显式写进设计意图，从而把"以后要不要做总线"从"待办"变成"**有判据的开关**"（§6）。

### 3.4 被否决的备选

| 备选 | 否决理由 |
|---|---|
| "`:cloudai` 已经是一条总线了，只是很薄" | 说它是总线会误导：总线的本质是**对端可发起 + 能力寻址 + 生命周期**三点，我们一个都没有。名称错了会导致后面按总线的期望去评审它 |
| "把 SSE 换成 WebSocket 就有双向了" | 传输层换协议不改变**控制权归属**。云端仍不能主动要求端侧执行任何事。换协议只改变 §3.1-⑥ 的背压特性，是被 `CloudTransport` 接缝吸收的局部变化 |

---

## 4. 【问题 3】`docs/05` §6 SyncPlane 的 B1–B4：逐条裁决

> 先纠正用户初判的两条（按纪律，不顺着你）。

### 4.1 先核实你的两个初判

| 你的初判 | 核实结果 |
|---|---|
| "B2 隐私路由已随 RoutingPort/EgressGate 落地" | ⚠️ **部分成立，需要修正**。`RoutingPort`/`EgressGate` 的**类型与审计链路**确实在（`:core:ports/Chat.kt` L1121-1145 + `EgressPermitSink`），但：**① `RoutingPort` 有两个实现并存**（`LocalOnlyRoutingPort` 与 `DialogRoutingPort`）；**② 运行时路径仍绑 MVP 那一对**（修正 A）；**③ `EgressGate` 仍只有一个实现且从未放行过一次**。⇒ B2 是"**类型落地、运行时未切换**" |
| "B4 验签在模型分发 manifest 已有雏形" | ⚠️ **方向对，但落的不是 B4 的位置**。`ManifestTrust.pinned` / `ModelVerifyGate` / `ModelManifestPins.MODEL_CA_2026_SPKI` 确实存在且 fail-closed——**但它是模型分发用的**。云端端点侧（`KtorCloudTransport`）**零证书钉扎**，靠系统信任链。⇒ **B4 对"云端端点"是未落地**，不是"已有雏形" |

### 4.2 B1–B4 逐条裁决表

| 项 | §6 的设定 | 今天的状态 | 裁决 |
|---|---|---|---|
| **B1 能力总线·MCP** | 【预留接口】 | **已落地但被故意锁死**：`McpServerConfig` + `McpConnectionPort` + `:mcp` 客户端齐全；`DEFAULT_LOOPBACK_ONLY = true`，`isLoopback()` 认三种写法（D1） | **维持现状，且维持"回环锁"**。这是本项目里唯一一个"能力总线"的正确位置，它待在本机是对的。★ **B1 不需要补任何东西** |
| **B2 隐私路由 RoutingPort + EgressGate** | 【预留接口】 | **类型已落地（本轮新增 `DialogRoutingPort` + `EgressPermitSink`），运行时未切换**（修正 A） | **本轮要做的不是"补接口"，是"切换绑定"**：`:app` 装配时把 `RoutingPort` 绑定到 `DialogRoutingPort`，让 `LocalOnlyRoutingPort` 退成测试件。**这是装配工作，不是设计缺口** |
| **B3 检查点搬运·只读结构化信封** | 【预留接口】 | **未实现**。v3 只建了列，注释写明"MVP 只建列，不生成文件"（D2） | **维持预留**。⇒ 但 §5 的缝隙 B（schemaVer）与它直接相关：**列已经在，将来写文件时不需再迁移**。这是免费的 |
| **B4 信任·pinned SPKI 验签** | 【预留接口】 | 模型分发侧 ✅ 已落地且 fail-closed；**云端端点侧 ❌ 零钉扎** | ★ **这是本轮唯一一条我认为该补的"接口缝隙"**：见 §4.3 |

### 4.3 ★ B4 的缺口：云端端点零钉扎

```kotlin
// 现状：KtorCloudTransport 用 OkHttp engine，走系统信任链
// 任何被系统信任链接受的 CA 签发的证书都能冒充用户填的 BaseURL
// ⇒ 中间人 / 恶意网关可以拿到出境内容
```

**为什么这条不是"以后再说"**：我们刚刚建立了一整套"出境必须有 permit、必须有审计行"的结构（docs/08 §7）。**如果传输层不做钉扎，那套结构保证的是"我们知道自己发给了谁"，而不是"我们真的发给了那个人"**。审计会诚实地记录一次发给 `api.deepseek.com` 的出境，而实际接收方可能是别人。

⇒ **建议补，但只补最小形态**：

```kotlin
// :core:ports/CloudEgress.kt —— 加法，默认值不破坏既有装配
public data class EndpointConfig(
    …,
    /**
     * ★ 可选的 SPKI 钉扎（sha256/… base64）。null ⇒ 走系统信任链（默认，兼容自建网关）。
     *
     * ★ 为什么默认 null 而不是默认强制：
     *   自建网关 / 局域网自签名场景拿不到公共 CA 签发的证书，
     *   强制钉扎会把 ALLOW_INSECURE_LAN 那条合法路径一起堵死。
     *   ⇒ 提供能力，但不替用户做决定 —— 与 TlsMode.ALLOW_INSECURE_LAN 同一取舍。
     */
    val pinnedSpkiSha256: Set<String> = emptySet(),
)
```

复用的既有的件：`ManifestTrust.rsaSha256Verifier(SPKI base64)` 的算法与 `ModelManifestPins` 的编码形态（`:data:persist.modeldist`），**不重抄一份验签**。

### 4.4 §6.3 "B 通道不开端口"的四条理由，2026-10 是否仍成立

| # | 原理由 | 今天是否仍成立 | 依据 |
|---|---|---|---|
| ① | Ktor server 要跑在前台服务里，退后台被回收 | ✅ **仍成立**。Android 后台限制只会更严不会更松 | Android 平台行为（外部 oracle） |
| ② | 随机端口 + 本地 bearer + 会话白名单，防本机其它 App 未授权发现 | ✅ **仍成立** | 同上 |
| ③ | 与 WebView、2.6GB 模型争内存 | ✅ **仍成立，且更紧**：`docs/07` 实测 debug APK 95.62 MB，+551MB RSS 逼近 OOM | `docs/07` M2 |
| ④ | 一旦暴露 `run_browser_task`，等于给本机其它 App 一个"在你的登录态里上网"的能力 | ✅ **仍成立，且被本轮红线强化**：DOM 永不出境 ⇒ 更不可能暴露一个让第三方借登录态的入口 | 红线 |

⇒ **四条全部仍成立，B 通道继续维持【预留接口】，不开端口。**

### 4.5 需要现在补的接口缝隙（小结）

| # | 缝隙 | 归属 | 性质 |
|---|---|---|---|
| **G1** | `EndpointConfig.pinnedSpkiSha256` | §4.3 | 加法（默认空） |
| **G2** | `RoutingPort` 绑定切换到 `DialogRoutingPort` | §4.2-B2 | **装配工作**，不是设计 |
| **G3** | `EgressCoordinator` 的归属 | §1.2 修正 C / §5 缝隙 C | 新增 Port |
| **G4** | `DialogTargetCodec` 未知态处理 | §5 缝隙 D | 小改动 |

---

## 5. 【问题 4】红线约束下，总线最多能传什么？它还算"harness 间通信"吗？

### 5.1 先算一下：红线把可传内容压到什么范围

按 §6.2 的 D0–D3 分级，叠加三条红线（DOM 不出境 / 云端无执行权 / 凭据不同步）：

| 级别 | 内容 | 在红线下的可传性 |
|---|---|---|
| **D0 RED** | 凭据、Cookie/localStorage、**PocketDom 序列化原文**、同源 DOM 文本、已填表单值 | ❌ 类型级封死（`:cloudai` 拿不到 `PocketDom` 符号） |
| **D1 AMBER** | 用户原始话语、任务 goal、当前 host、抽取结果、任务摘要 | ⚠️ **今天也不传**——`:cloudai` 的入参只有 `systemInstruction` + `initialMessages` + 本轮 `userTurn`，没有独立通道承载 D1 |
| **D2 GREEN** | 能力清单（SkillSpec 的 name/description/schema）、抽象计划（无实体参数）、版本号与延迟统计 | ✅ `OutboundPayload` 的 sealed 三变体就是这个；但★ **今天一次都没被发出去**（`LocalOnlyEgressGate` 从未放行） |
| **D3 INBOUND** | 模板/技能 bundle（云→端） | ✅ 有 §6.5 的验签设计；未实现 |

### 5.2 严格结论：**今天实际在传的东西，比 D2 还要窄**

```
今天真正出境的字节 = ChatCompletionsMessages.of(system, history, currentTurn)
                   序列化后的 JSON
```

它既不是 D0（类型封死）、也不是 D2（`OutboundPayload` 三变体一次都没用上），而是**用户自己的对话文本**——按 §6.2 的分级它是 **D1**。⇒ **当前实现实际上在传 D1，而 D1 的原设定是"默认不出，需用户显式选择 + 同口径脱敏"**。

⇒ 这是一处**应该被主理人知悉的偏差**：`docs/08` 把"云端只服务纯对话"写成了红线 ① 的正面表述，但没有显式说明"**纯对话文本 = D1，因而每一轮出境都需要一次显式授权（permit）**"。所幸 `EgressPermit` 机制恰好就是这个授权（docs/08 §7.2），**机制是对的，只是分级标签没对齐**。建议补一句：云端对话的 payload 类别恒为 `{AMBER}`，`RouteContext.payloadClasses` 如实填它。

### 5.3 ★ 业界的"传递性原则"：为什么"只传 D2"也不能宣称安全

2026 年 split-inference 的分析给出一个很硬的结论（外部 oracle，非本项目断言）：

> **传递性原则（transitivity principle）**：任何产生用户可见输出的云端阶段，都会学到该输出的内容——无论字节过境时是什么形态。
> ⇒ 分割推理是**传输层防御**（防的是网络路径上的窃听者、中间盒、日志），**不是端点防御**。它对云服务商、对针对服务商的法律程序、对攻破服务商的攻击者**一概无效**。

对本项目的直接含义有三条：

1. **"只传抽象计划所以安全"是错的。** 如果云端产出了用户要读的答案，运营商**通过输出侧传递性地**获得了内容。⇒ **D2 出境 ≠ 无信息出境，只是输入侧受控。**
2. **这反过来证明红线是对的，而且锁对了地方。** 浏览器任务必须锁死的不是"DOM 这个数据"，而是**"任务"本身**——因为只要云端参与产出页面任务的任何一步，输出侧就传递性泄露。锁数据不够，必须锁任务。这正是 `TaskKind.PageTask ⇒ 恒 LocalOnly` 这条运行时判定的真正理由（它也是 docs/08 §5.6 承认做不到编译期的那一条）。
3. **UI 文案不能写"你的数据没有出境"。** 可写的只有"**你的网页内容不会用于云端推理**"。这个措辞差别在合规上是实质性的。

### 5.4 定性：它还能叫"harness 间通信"吗？

> **不能。它的准确名称是「请求-响应 + 只读结构化信封搬运」。**

三条判据：

| 判据 | harness 间通信 | 本项目现状 |
|---|---|---|
| 对端是否有自己的目标与循环 | 有（"handle this"，接收方决定怎么做） | **无**（"complete this"，我们决定怎么做） |
| 是否需要对等协商（谁做什么、谁等谁） | 需要（A2A 的 task 状态机、`input-required`） | **不需要**（一次请求一次响应，失败即终态） |
| 是否需要双向发起 | 需要 | **不需要，且被红线禁止** |

⇒ 用 §2.5 的业界分层来说：**MCP 是 agent↔tool，A2A 是 agent↔agent，而我们这个是 agent↔function**。三个层，我们落在最下面的、也最薄的那一层。

★ **这个定性不是贬低，是对"薄"的正名**：agent↔function 这一层只需要**一个函数签名 + 一套错误语义**，而后者我们做得比多数总线还好（§3.1-⑧）。

### 5.5 若真到阶段 2，"最多能传什么"（为将来留档，不是现在做）

假设红线被主理人显式推翻（这是阶段 2 的前置条件，§6），可传集合的上界：

```
① 压缩后的 history     —— 形状 #3 click([12]销量)→ok [dom+18]，天然不含 D0（§6.4）
② D2 能力清单 / 抽象计划 —— OutboundPayload 三变体
③ 只读信封 CheckpointEnvelope —— D0 绝不进信封（§6.4 不变量 1）
④ 任务委托：goal + 预算信封 + 允许的能力子集
✗ 绝不包含：DOM 原文、凭据、Cookie、执行结果回写（执行权不出端）
```

⇒ 即便在阶段 2，可传的东西仍然是**一堆只读的、结构化的、可审计的投影**，不是共享状态。**它依然更像"带状态的 RPC 信封搬运"，而不是"两个进程共享一个世界模型"。** 这个上界值得现在记下，因为它说明：**即便推翻红线，总线的收益也没有直觉上那么大。**

---

## 6. 【问题 5】★ 接口缝隙：要不要埋、埋什么、会不会逼 `:app`/UI 返工

> 先给答案：**要埋，4 处，全是加法；不必押后 `:app` 装配与 UI。**
> 唯一带编译期涟漪的一处，**现在做最便宜**。

### 6.1 为什么"现在埋"比"以后埋"便宜（用修正 A/B/C 的证据）

| 若现在不埋 | 未来做什么时要返工 | 返工面 |
|---|---|---|
| `:chat` 直接注入具体 `OpenAiCompatibleDialogPort` | 换对端形态 | `:chat` + `:app` |
| `:app` 自己写 "permit 开 → 调 → 重试 → 收敛" 这段编排 | 加第二种对端 | `:app`（组合根不该有这段逻辑） |
| `DialogTarget` 只有 Device/Remote 两态 | 加第三态（外部 Agent） | `when` 穷尽性 ⇒ **全仓红一遍**，包括 `:ui` |
| `DialogTargetCodec.decode` 把未知文本静默降级为 Device | 第三态上线后旧数据被读成 Device | 数据语义（用户看到会话莫名变端侧） |
| UI 徽标按 boolean "是云/不是云" 渲染 | 加第三态 | `:ui` 表单 + 徽标 + i18n |

⇒ **前四条的成本在 `:chat`/`:app`，第五条在 `:ui`。而现在 `:chat` 和 `:app` 都还没接线（修正 A/B），`:ui` 也还没做切换 UI** ⇒ 缝隙的成本处于历史最低点。

### 6.2 缝隙清单（签名级，4 处 + 1 处可选）

#### ★ 缝隙 A：对端形态 `CloudPeerKind`（最重要）

```kotlin
// :core:ports/CloudEgress.kt —— 新增（纯加法）

/**
 * ★★ 对端的**形态**。这是"要不要总线"这个开关在类型上的落点。
 *
 * ## 它不是"协议枚举"，是"控制权归属"的声明
 *  · CompletionEndpoint —— 对端是**函数**：我们决定它做什么，它无权发起任何事。
 *  · AgentEndpoint      —— 对端是**主体**：它可以有自己的循环、工具、子任务，
 *                          并且可能反过来向我们要东西（A2A 的 `input-required`）。
 *
 * ## ★ 为什么今天就要有这个类型（即使 `AgentEndpoint` 没有实现）
 *
 * 不是预留实现，是**预留「编译期逼你表态的位置」**：
 *   · 今天所有 `when (peerKind)` 只有一条分支；
 *   · 明天若真要支持 AgentEndpoint，加一个变体 ⇒ `DialogRoutingPort`、
 *     `FallbackPolicy`、`EgressCoordinator`、UI 徽标的 `when` **当场编译失败**，
 *     逼每一个调用点回答「对端是主体时，这一条还成立吗」。
 *
 * ★ 这比「在注释里写『将来可能支持 agent』」强的地方在于：
 *   注释会被忽略，编译错误不会。
 */
public sealed interface CloudPeerKind {
    public data object CompletionEndpoint : CloudPeerKind
    // public data object AgentEndpoint : CloudPeerKind   ← 今天**不**加，见下
}

/** ★ 今天唯一的取值。用类型别名而不是散落的常量，方便将来一处改。 */
public val PEER_KIND_DEFAULT: CloudPeerKind get() = CloudPeerKind.CompletionEndpoint
```

**★ 关键取舍：今天 `sealed` 里只放一个变体，不加 `AgentEndpoint`。**
理由：加一个空变体会让今天所有 `when` 都必须写一个 `-> TODO()` 分支，而 `TODO()` 会被忘记、会进生产。**留一个单变体 sealed，未来加第二个变体时编译期自动逼宫**——这才是缝隙该有的样子：**不为将来付今天的税，但保证将来加东西时不会被静默忽略。**

挂在哪：**`DialogTarget.Remote`**，不是 `EndpointConfig`：

```kotlin
// :core:ports/Dialog.kt —— 加法（默认参数 ⇒ 既有构造点零改动）
public data class Remote(
    val credentialRef: CredentialRef,
    val peerKind: CloudPeerKind = PEER_KIND_DEFAULT,   // ★ 缝隙 A
) : DialogTarget
```

理由：`DialogTarget` 是"会话选了什么"。对端形态变化改变的是"**选的性质**"（从"用哪个模型"变成"委托给谁"），这是会话级事实；而 `EndpointConfig` 只是连接参数。

#### 缝隙 B：决策 schema 版本（落库，防未来迁移盲区）

```kotlin
// :core:ports/Chat.kt —— 加法（默认值 ⇒ 既有构造点零改动）
public data class RoutingDecision(
    …,
    /** ★ 决策语义的版本。落库 ⇒ 未来改判据时能区分新旧行（docs/09 §3 全 additive 思路）。 */
    val decisionSchemaV: Int = DECISION_SCHEMA_V1,
) { public companion object { public const val DECISION_SCHEMA_V1: Int = 1 } }
```

对应 `route_decision` v4 加一列 `decision_schema_v INTEGER NOT NULL DEFAULT 1`。**它同时是 §4.2-B3 的免费铺垫**：`CheckpointEnvelope` 的列已在 v3 建好，将来写文件时不需要再迁移。

#### ★ 缝隙 C：`EgressCoordinator` Port（直接补 §1.2 修正 C 那个洞）

```kotlin
// :core:ports/CloudEgress.kt —— 新增接口（加法）

/**
 * ★★ 一次「出境」的完整编排：开许可 → 调 → 重试（每次新许可）→ 收敛 → 决定回落。
 *
 * ## 为什么它必须是 Port 而不是 `:inference` 里的具体类
 *
 * 这段编排是「**端云之间那一次交互**」的全部语义。今天它是 `chat/completions` 一次调用；
 * 未来若对端形态变了（缝隙 A），变的**只应该是这个接口的实现**，
 * 而不是 `:chat` / `:app` / `:ui` 里的调用点。
 *
 * ## ★ 为什么现在就要定它（修正 C）
 *
 * `CloudChatRunner` 的注释已经写了「由 FallbackPolicy / EgressCoordinator 管」，
 * 但 `EgressCoordinator` **目前不存在**。若不在此刻定归属，
 * `:app` 装配时这段逻辑会被就近写进组合根 ——
 * 那时它就从「一个 Port 的实现」变成「散在组合根里的一段胶水」，再抽出来就是返工。
 */
public interface EgressCoordinator {

    /**
     * 执行一次出境，返回**最终**结果与已完成的审计收敛。
     *
     * 伪码（不写实现）：
     * ```
     *   permit  = permits.open(ctx, decision)            // 同步落 PENDING；失败 ⇒ fail-closed
     *   attempt = 0
     *   loop {
     *       attempt++
     *       result = remote.generate(req, permit)
     *       if (result.ok)      { permits.close(permit, DELIVERED, POST, bytes, finishRaw); return Delivered }
     *       action = FallbackPolicy.actionFor(result.failure)
     *       if (action == FallBackToLocal && failure.phase == PRE) { permits.close(permit, NOT_SENT, PRE, 0, null); return FellBackToLocal }
     *       // ★ POST 阶段**绝不**自动回落（docs/08 §4.2）——返回给 UI 让它显式问用户
     *       if (action == AskUser)   { permits.close(permit, FAILED, POST, bytes, code); return NeedsUserDecision(failure) }
     *       if (attempt >= FallbackPolicy.maxRetries(failure)) { permits.close(...); return Failed(failure) }
     *       permit = permits.open(ctx, decision)          // ★ 重试 = 新出境 = 新许可 + 新审计行
     *   }
     * ```
     */
    public suspend fun execute(
        ctx: RouteContext,
        decision: RoutingDecision,
        call: suspend (permit: EgressPermit) -> CloudCallOutcome,
    ): EgressResult
}

/** 一次调用的原始结果（由 `:cloudai` 给出，只含「发生了什么」）。 */
public sealed interface CloudCallOutcome {
    public data class Done(val text: String, val finishReasonRaw: String?, val deliveredBytes: Int) : CloudCallOutcome
    public data class Failed(val failure: CloudFailure, val deliveredBytes: Int) : CloudCallOutcome
}

/** 编排的终态。★ UI 必须能穷尽地处理它。 */
public sealed interface EgressResult {
    public data class Delivered(val text: String) : EgressResult
    /** ★ 仅 PRE 阶段可达。UI 必须显示「未上云，已由本机完成」。 */
    public data object FellBackToLocal : EgressResult
    /** ★ POST 阶段失败 ⇒ **不自动回落**，UI 必须显示「已发给 <host>」并让用户选。 */
    public data class NeedsUserDecision(val failure: CloudFailure) : EgressResult
    public data class Failed(val failure: CloudFailure) : EgressResult
}
```

**归属**：Port 在 `:core:ports`，实现 `DefaultEgressCoordinator` 放 `:inference`（它能看见 `FallbackPolicy` 与 `CloudCredentialPort`）。**零新 Gradle 边。**

#### 缝隙 D：`DialogTargetCodec` 的未知态（唯一带编译期涟漪的一处）

现状：

```kotlin
// Dialog.kt L265-272 —— fail-closed，安全
public fun decode(raw: String?): DialogTarget { … 认不出 ⇒ return DialogTarget.Device }
```

问题：认不出 ⇒ 静默变 `Device`。今天是安全的（宁可本地），但**用户会看到"这个会话莫名变成本地了"而没有任何解释**。未来若加了第三态，旧数据/新版本读旧行会被静默降级。

```kotlin
// :core:ports/Dialog.kt —— 改动
public sealed interface DialogTarget {
    public data object Device : DialogTarget
    public data class Remote(…) : DialogTarget
    /** ★ 落库文本无法识别。★ 它不是「猜成 Device」，是「承认不认识」。 */
    public data class Unrecognized(val raw: String) : DialogTarget
}

public fun decode(raw: String?): DialogTarget {
    …
    // 认不出 ⇒ Unrecognized，由调用方（DefaultModelRouter）返回 Err，不猜
}
```

⇒ 涟漪：`DefaultModelRouter.dialogFor` 的 `when` 与 `DialogTargetCodec.encode` 的 `when` 需要处理新变体（**编译期红 2 处，5 分钟改完**），以及 `DialogTargetTest` 加一条用例。
⇒ **这是唯一一个"要改动现有形状"的缝隙，而它的成本现在几乎为零**（`:chat`/`:app` 未接线，`:ui` 未做）。

#### 缝隙 E（可选，成本最低）：i18n 与 UI 按 sealed 渲染

不是代码缝隙，是**约束**：切换 UI 的徽标与文案**不要**写成 `if (isCloud)`，而写成 `when (target) { Device -> …; Remote -> … }`。
代价：现在多写一个 `when`（两个分支）。收益：未来加第三态时 `:ui` 编译期红，而不是用户看到一个错误的徽标。

### 6.3 直接回答："要不要押后 `:app` 装配和 UI？"

> **不要押后。** 但要按 §6.2 的顺序做：**先埋 A/B/C/D（半天到一天，`:core:ports` + `:inference`），再动 `:app` 装配与 UI。**

理由三条：
1. A/B/C 是**纯加法**，D 的涟漪只有 2 处 `when`，都在 `:core:ports`/`:inference` 内部，**不触及 `:app`/`:ui`**；
2. 押后的代价是**未来返工**，而未来返工的面比现在大得多（§6.1 表格）；
3. `:app` 装配本身**不需要等**这些缝隙——但装配的**形状**要等缝隙 C：`:app` 应该注入 `EgressCoordinator`，而不是自己写那段编排。⇒ **若实在要并行，至少先定 C 的签名**（半天），其余可与装配并行。

**给主理人的一句话**：`EgressCoordinator`（缝隙 C）是唯一一个"先定签名再装配"的硬依赖；其余三处可与 `:app`/UI 并行推进。

---

## 7. 【问题 6】明确裁决：要 / 不要 / 分阶段

### 7.1 裁决

> **分阶段。阶段 0 = 不做总线（现在，已如此）；阶段 1 / 2 有明确触发条件，不是时间问题。**

### 7.2 三阶段与触发条件

```
阶段 0  单向出站补全调用（无总线）              ← 今天所在，已落地 :cloudai
   │
   │  触发条件 T1（任一成立）
   ▼
阶段 1  多对端 + 能力协商（仍不是总线：仍是单向、仍无反向发起）
   │
   │  触发条件 T2（任一成立，且 T2 含"红线变化"）
   ▼
阶段 2  对等协同（这才需要总线）               ← ★ 红线不变则不可达
```

#### T1：从阶段 0 升级到阶段 1

| 信号 | 说明 |
|---|---|
| **S1** | 出现**第二个形态不同**的出站对端。判据：不是"又多填了一个 key"，而是**协议形态不同**（例如出现 A2A/Agent 端点、或出现需要 `tools/list` 协商的对端） |
| **S2** | 出现"**云→端**"的主动调用需求（回调 / webhook / 推送 / 长连接入站） |
| **S3** | 出现**跨设备续跑**需求（同一会话在另一台设备上继续） |

#### ★ 反触发（明确**不**算 T1，避免"以后再说"式拖延）

| 不是信号的事 | 为什么不算 |
|---|---|
| 多配置几家厂商端点 | 只是 `EndpointConfig` 多行数据 |
| SSE 换成 WebSocket | 传输层变化，被 `CloudTransport` 接缝吸收（§3.4） |
| 加 streaming usage 统计、加重试、换模型 | 局部增强 |
| `cloud_provider` 厂商预设表 | 配置数据（且 docs/09 §2.3 已裁决本轮不建） |
| 用户抱怨"云端回答不够好" | 换模型，不是换架构 |

#### T2：从阶段 1 升级到阶段 2

| 信号 | 说明 |
|---|---|
| **S4** | 对端**确实持有循环/工具/状态**（即它真的是 agent），且我们需要把**子任务委托**给它并回收结果（A2A 的控制模型："接收方决定怎么干"） |
| **S5** | ★ **DOM 红线被主理人显式推翻**。否则阶段 2 在结构上不可达（§3.3：总线需要双向发起 ⇒ 撞"端侧唯一执行权"） |
| **S6** | 出现"端侧作为**被调方**"的产品需求（B 通道开端口），且 §4.4 的四条理由**全部解除** |

⇒ **S5 必须显式列进 T2**，因为它诚实地说了一件事：**阶段 2 不是一个技术演进，是一个产品与红线的重新裁决。** 把它写成技术里程碑会误导后来者以为"只要工程排得上就能做"。

### 7.3 被否决的另两种裁法

| 裁法 | 否决理由 |
|---|---|
| **"直接不要，永久不做"** | 过于绝对。若 S4/S5/S6 出现（用户就是要接自己的家庭 agent），不做等于产品缺能力。而且"永久不"无法证伪 |
| **"先建一个总线骨架占位"** | 空接口会被当成既有承诺（§2.6 第三条）。且骨架的每一个维度都在撞红线（§3.3）⇒ 建出来的骨架要么被红线削成今天这个形状（白建），要么诱导后人去撞红线（有害） |

---

## 8. 【问题 7】每条结论的证伪方式 + 外部 oracle

> 外部 oracle = 非本项目自写的断言：编译器/Gradle、Android 系统命令、sqlite3 CLI、抓包工具、平台文档、公开规范与论文。

### 8.1 主结论的证伪

| # | 结论 | 最小实验 | 外部 oracle | 若失败说明什么 |
|---|---|---|---|---|
| **V1** | 云端不是 harness（§2.2 九项全无） | 对 `OpenAiCompatibleDialogPort` / `CloudChatRunner` / `CloudDialogSession` 逐个检查九项要素，任何一项成立即证伪 | 源码（可用 `javap`/反射列出 public 成员做交叉核对） | 若发现云端持有跨请求状态或能发起调用 ⇒ 我的"不是 harness"判断错，§3 全表要重做 |
| **V2** | 不存在第二个 harness | 全仓搜"循环/工具注册/轨迹落盘"三类的实现，检查是否有一个跑在云端 | 源码 + `apkanalyzer` | — |
| **V3** | 总线与红线互斥（§3.3） | 逐维度试补：给 `CloudTransport` 加一个 `suspend fun receive()` 入站方法，看它在类型上会不会碰 `DialogModelPort` 之外的东西 | Kotlin 编译器 | 若能补上且不碰红线 ⇒ 我的互斥论证有漏洞 |
| **V4** | `:cloudai` 拿不到 `PocketDom` | 在 `:cloudai/src/main` 写 `val x: com.ake.core.contract.PocketDom? = null`，跑 `:cloudai:compileKotlin` | **Kotlin 编译器**（unresolved reference）+ `./gradlew checkModuleDependencies` | 若能编过 ⇒ DOM 锁 B 失效，本文档与 docs/08 的基石塌了 |
| **V5** | §4.4 四条理由仍成立 | 在 `:app` 里起一个 `embeddedServer(CIO, port=0, host="127.0.0.1")`，退后台 5 分钟后 `adb shell ps \| grep` 我们的进程 | **`adb shell ps` / `dumpsys activity processes`**（Android 系统） | 若不被回收 ⇒ 理由 ① 失效，B 通道可重新评估 |
| **V6** | 云端端点零钉扎（§4.3） | mitmproxy 以**系统信任链里已有的 CA**（或用户已安装的根证书）做中间人，指向用户填的 BaseURL | **mitmproxy**（外部工具） | 若连接被拒 ⇒ 说明有钉扎，我的核实错了 |
| **V7** | 运行时仍绑 `LocalOnlyRoutingPort`（修正 A） | 真机云端会话发一轮，`adb logcat \| grep LocalOnlyRouting`；`sqlite3 route_decision 'select reason_key'` | `adb logcat` + **sqlite3 CLI** | 若出现 `route_cloud_allowed` ⇒ 已切换，我读的是旧代码 |
| **V8** | `:app` 未接线 cloudai/inference（修正 B） | `./gradlew :app:dependencies --configuration releaseRuntimeClasspath \| grep -c cloudai` | Gradle 输出 | — |
| **V9** | 缝隙 A 的价值（未来加变体时编译期逼宫） | 在 `CloudPeerKind` 里加一个 `AgentEndpoint` 变体，跑 `./gradlew compileKotlin` | **Kotlin 编译器**（`when` 穷尽性错误） | 若没有编译错误 ⇒ sealed 没派上用场，缝隙 A 白埋 |
| **V10** | §5.3 传递性原则适用于本项目 | 让云端**只**收到 D2 抽象计划（例如"点击销量排序按钮"），检查它产出的用户可见回答是否包含任务内容 | 逻辑推演 + 服务端侧记录（mitmproxy 的**响应**侧，不只是请求侧） | 若输出侧不含 ⇒ 传递性原则在此场景不适用（几乎不可能） |

### 8.2 ★ 关于 V10 的一个具体实验（我认为最值得做）

V10 的**响应侧**检查是本轮最容易被漏掉的。大家都盯着"请求里有什么"，而传递性原则说的是"**响应里有什么**"。

```
实验：在 :cloudai 之外，用 mitmproxy 录制一次云端对话的**双向** flow；
     断言：服务端**响应**里出现的、用户将要读到的文本，属于"信息已到达运营商"。
     ⇒ 由此校准 UI 文案：不能写"你的数据没有离开设备"，只能写
       "你的网页内容不会被用于云端推理"（§5.3-3）。
```
外部 oracle：mitmproxy 的 flow 文件（含响应体）。

### 8.3 ★ 我认为无法证伪的三条（如实标注）

| # | 结论 | 为什么无法证伪 | 我只能保证到哪 |
|---|---|---|---|
| **N1** | "总线与红线在结构上互斥"（§3.3 / V3） | 红线的边界由**产品意图**定义，不由类型定义。有人可以写一段不经过任何 Port 的裸 HTTP，把对端变成一个事实上的主体 | 我只能保证：**走 Port 的那条路**上互斥。`:app` 组合根里的裸写无法被类型系统拦（docs/08 §5.6-⑨ 已承认） |
| **N2** | "阶段 2 需要 S5（红线推翻）"（§7.2） | 这是一个**规范判断**而非事实判断：它取决于"执行权"这个词怎么定义。若某人定义"云端只建议、端侧执行"也算协同，那 S5 不必需 | 我只能保证：在 §5.4 给出的三个判据定义下成立。**定义本身是可争的** |
| **N3** | "埋这 4 处缝隙能避免未来返工"（§6.1） | 返工与否取决于未来需求的具体形态，而未来需求未知。若未来的第三态不是"外部 Agent"而是别的东西（比如"本机另一个 App 内的模型"），我预埋的 `CloudPeerKind` 可能挂错位置 | 我只能保证：埋的是**最常见的那一种未来**。这也正是 §7 要求"触发条件必须是外部信号"的原因——**让未来需求来触发，而不是让我的预判来定义** |

### 8.4 一条我**不确定**的判断（标注为不确定，而非无法证伪）

> **§5.2 说"今天实际出境的是 D1（对话文本）而非 D2"。**
> 我按 §6.2 的表做的归类，但 §6.2 的 D1 定义里包含"用户原始话语"，而 `ChatCompletionsMessages` 传的正是它。
> **可证伪**：请主理人或关千锤对照 §6.2 原文复核分级。若判定为 D2，则 docs/08 §7 的 permit 语义需要相应调整（从"每轮显式授权"变成"类别白名单授权"）。
> 外部 oracle：`docs/05` §6.2 原文的分级表（它是上游文档，相对本项目是外部）。

---

## 9. 一页结论（给主理人）

| 问题 | 裁决 |
|---|---|
| **1. 两端是否对等 harness？** | **否。** 云端是无状态补全函数的适配器，九项要素一项没有。"对等 harness 间的总线"当前是**伪命题**。唯一够格的对等主体是"用户自建的第三方 Agent"（§2.4-C），那是 A2A 场景，不是"端云"场景 |
| **2. `:cloudai` 是最薄通道吗？** | **是。** 九个维度里 ⑧ 强于通用总线、⑥ 部分具备、其余七个不具备。**且每一个"补上"都撞红线**（§3.3）⇒ 薄是正确的 |
| **3. B1–B4？** | B1 **已落地且应维持回环锁**；B2 **类型已落地、运行时待切换**（装配工作）；B3 **维持预留**（列已建，免费）；**B4 对云端端点未落地——建议补最小 SPKI 钉扎**。B 通道四条理由**全部仍成立** |
| **4. 最多能传什么？** | 今天实际传的是 D1（对话文本），不是 D2。**定性：请求-响应 + 只读结构化信封搬运，不是 harness 间通信。** ★ 附一条警告：只传 D2 也不能宣称安全（传递性原则） |
| **5. 要不要埋缝隙？** | **埋 4 处，全是加法，不用押后 `:app`/UI。** 唯一硬依赖：**先定 `EgressCoordinator` 签名**（它今天只存在于注释里）；其余可并行。**最便宜的时机就是现在** |
| **6. 裁决？** | **分阶段。** 阶段 0 不做总线；T1（S1/S2/S3）→ 阶段 1；T2（S4/**S5 红线推翻**/S6）→ 阶段 2。**反触发清单已列**，避免"以后再说" |
| **7. 证伪？** | V1–V10 十个实验，oracle 为编译器/Gradle/adb/sqlite3/mitmproxy/上游文档。**N1–N3 三条我只能保证到这里**；§8.4 一条我**不确定**，请复核 D1/D2 分级 |

---

## 10. 待主理人仲裁

| # | 事项 | 选项 | 我的推荐 |
|---|---|---|---|
| **A1** | §5.2：云端对话文本的正式分级是 D1 还是 D2？ | ① D1（我的判断）② D2 | ①。若 ②，docs/08 §7 的 permit 语义要从"每轮显式授权"改成"类别白名单授权" |
| **A2** | §4.3：是否补云端端点的 SPKI 钉扎（默认空、可选）？ | ① 补 ② 不补 | ①。它是唯一一条"审计记录了发给谁、但没保证真发给那个人"的裂缝 |
| **A3** | §6.2-A：是否接受单变体 sealed `CloudPeerKind`（今天没有 `AgentEndpoint`）？ | ① 接受 ② 连这个也不埋 | ①。成本≈0，收益是"未来加变体时编译期逼宫" |
| **A4** | §6.2-C：`EgressCoordinator` 是否现在定签名（`:core:ports`）+ 实现（`:inference`）？ | ① 是 ② 装配时再说 | ①。**这是唯一可能逼返工的一条** |
| **A5** | §6.2-D：`DialogTargetCodec` 未知态改 `Unrecognized`（编译期红 2 处）？ | ① 改 ② 保持静默降级 Device | ①。现在改 2 处，以后改要动数据语义 |
| **A6** | §5.3-3：UI 文案是否禁用"你的数据没有离开设备"？ | ① 禁用 ② 保留 | ①。改用"你的网页内容不会被用于云端推理" |
| **A7** | §7.2-T2：是否把"S5 红线推翻"明确写进阶段 2 触发条件？ | ① 写 ② 不写（写成技术里程碑） | ①。写进去才能防止后来者误以为"工程排得上就能做" |
