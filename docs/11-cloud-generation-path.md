# 云端生成路径接线规格（P0 · 待执行）

> 2026-10-31。本文不是设计愿景，是**可直接照做的手术单**。
> 起因：`ChatLoop` 至今只持有端侧 `model: ModelPort`，云端 `DialogModelPort`
> 从未进入真生成路径 —— 用户选了云端，仍由端侧出字。

---

## 一、为什么不能「加个适配器转发」

三个实证，任一条都足以否决 `RoutingModelPort : ModelPort` 这种一行接法：

| # | 事实 | 出处 |
|---|---|---|
| 1 | `DialogModelPort.openDialog(spec, permit, context)` 的 `permit` 是**必填形参**，`null` ⇒ `CloudFailure.NoPermit` | `Dialog.kt:160`、`OpenAiCompatibleDialogPort.kt:112` |
| 2 | `OpenAiCompatibleDialogPort.openSession(spec)` 内部就是 `openDialog(permit = null)` ⇒ **恒失败**。今天 `ChatLoop` 调的正是 `openSession` | 同上 `:91` |
| 3 | `permit` 由 `EgressPermitSink.open(ctx, decision)` 产出，需要完整 `RouteContext`（`agentId`/`sessionId`/`turnId`/`payloadClasses`/`taskKind`）。`SessionSpec` 里只有 `taskId`，信息不足 | `CloudEgress.kt:242`、`Model.kt:110` |

`ChatLoop` 内部也没有 `RouteContext`：它只有 `sid` 与 `ledger`。
所以「谁构造 ctx」本身就是这次改动的一部分。

---

## 二、正确形状：**一轮 = 一条 permit = 一条审计行 = 一个云端 session**

这是本规格最重要的一句话。理由：

1. `EgressPermit` 的语义是「**一次出境**」：`open` 落 `PENDING` → 发字节 → `close` 收敛终态。
   把它拉长成「一会话一条」，则一个会话内多次调用共享一行审计，
   `deliveredBytes` 无法归属到具体某一轮，`sweepPending` 也失去意义。
2. OpenAI 兼容协议是**无状态**的：历史靠 `messages` 全量重放，
   不存在端侧那种 KV cache 会话。云端 session 用完即弃是正确的，
   不是「浪费」。
3. `openDialog` 内部用 `permit.endpointHost` 校验端点一致性 ⇒ permit 与调用天然一一对应。

```
ChatLoop 每轮 answer
  ├─ ① 构造 RouteContext（turnId = ledger.turnId, sessionId = sid, payloadClasses = …）
  ├─ ② routing.decide(ctx)            → RoutingDecision
  ├─ ③ 若 decision.target 是 LocalOnly  → 走现状（model.openSession + generate），不动
  └─ ④ 若是 CloudAssisted(ref) →
         coordinator.execute(ctx, decision) { permit ->
             cloudPort.openDialog(spec, permit, PromptContext.TextOnly)
               .flatMap { it.generate(answerReq) }
               .toCloudCallOutcome()
         }
       并按 EgressResult 分支：Delivered / FellBackToLocal / NeedsUserDecision / Failed
```

★ **`coordinator.execute` 已经封装了**：permit 申请、PRE 回落判定、重试（每次新 permit）、
审计终态收敛、告知 sink 调用。`ChatLoop` 不要自己调 `permits.open` / `close`
—— 那会绕开 `FallbackPolicy`，把「是否允许回落」的判断散成两处。

---

## 三、精确改动点

| 文件 | 改什么 |
|---|---|
| `chat/ports/ChatServices.kt` | 构造新增 `router: ModelRouter? = null`、`coordinator: EgressCoordinator? = null`；转交给 `ChatLoop`。两者同时为 null ⇒ 行为与今天完全一致（端侧单栈），既有 120 条测试零改动 |
| `chat/loop/ChatLoop.kt` | 构造加同样两个可空参数。**唯一分支点是 `answer` 生成处**（`tokenStream.generate` 调用前）；`openSession` 不动（端侧仍需 KV 会话） |
| `chat/loop/ChatLoop.kt` | 新增 `suspend fun answerViaCloud(...)`：构造 `RouteContext` → `decide` → `execute` → 映射 `EgressResult` |
| `chat/loop/ChatLoop.kt` | `spec` 构造时把 `initialMessages` 填成**压缩后的历史**（云端靠它重放；端侧现在也用它做 KV 复水，语义兼容） |
| `app/di/ChatPortsModule.kt` | 把 `CloudInferenceModule` 的 `ModelRouter` / `EgressCoordinator` 传进 `ChatServices` |

### 3.1 `RouteContext` 怎么填（逐字段，别猜）

```kotlin
RouteContext(
    agentId        = "chat",                       // 聊天固定
    sessionId      = sid,
    turnId         = ledger.turnId,                // ← 必须来自本轮，不能复用上一轮
    requested      = InferenceTarget.CloudRequested(ref),  // 表达意图，不是授权
    userGrantedThisTurn = false,                   // ★ 见 §五-1
    estimatedContextTokens = budget.estimatedTokens,
    needsLongContext = budget.estimatedTokens > CHAT_LOCAL_WINDOW,
    payloadClasses = setOf(DataClass.D1_CONVERSATION),   // ★ 见 §五-2
    taskKind       = TaskKind.Chat,                // ★ 绝不能传 PageTask
)
```

`taskKind = Chat` 是 DOM 锁的第一道：`TaskKind.PageTask ⇒ 路由必须返回 LocalOnly`。
它必须由调用点**显式**给出，不能有默认值兜底（否则将来加页面任务时会静默复用）。

### 3.2 `EgressResult` 四个分支怎么落到聊天状态机

| EgressResult | 聊天层动作 | UI |
|---|---|---|
| `Delivered(text)` | 走现有落库路径，`producedBy = REMOTE` | 徽标显示云端模型名 |
| `FellBackToLocal(failure)` | **静默切回端侧重新生成**（PRE，一个字节没出去），`producedBy = DEVICE` | 必须显示「未上云，已由本机完成」（`TurnEgressNoticeSource` 已有该条） |
| `NeedsUserDecision(failure)` | 本轮**失败收尾**，不重试、不回落 | 必须显示「已经发给 <host>」，并提供重发入口 |
| `Failed(failure)` / `NotPermitted` | 本轮失败收尾 | 按 `CloudFailure` 文案表（`:ui` 已有 17 种） |

★ `NotPermitted` 的含义是「审计行都写不出来」⇒ 一个字节都没发。文案不能说「网络错误」。

---

## 四、测试断言（照这个写，别写「看起来对」）

| # | 断言 | 为什么它必须存在 |
|---|---|---|
| T1 | 选 `Device` → `CloudCallAdapter` 调用次数 == **0**，且 `route_decision` 无新行 | 防止「双发」：端侧答了、云端也发一份 |
| T2 | 选 `Remote` + `TextOnly` → `bytesSent > 0`，`route_decision` 恰有 **1** 行且 `outcome=DELIVERED` | 一轮一账 |
| T3 | 选 `Remote` + `WithPageContext` → **`bytesSent == 0`**，`route_decision` **0 行**（或 1 行 NOT_SENT） | DOM 红线；这是唯一能杀死「以为拦住了其实发出去了」的断言 |
| T4 | PRE 失败（连接拒绝）→ 端侧**确实执行了**，且 `AnnouncementCenter.last.kind == FELL_BACK_TO_LOCAL`、`turnId` 与本轮一致 | 「必须告知」不能是孤儿消息 |
| T5 | POST 失败（流截断）→ 端侧**未执行**，`kind == ALREADY_SENT` 且 `host` 非空 | 禁止把「已出境」说成「没成功」 |
| T6 | 同一 session 连跑 3 轮 → `route_decision` 恰 **3** 行，`turnId` 两两不同 | 防 permit/session 被跨轮复用 |

外部 oracle：`sqlite3` 直接读 `route_decision`（`EgressAuditReconciler` 测试已示范），
不要用 DAO 读回来断言 —— 那是自证。

---

## 五、未解问题（**必须由人拍板，代码不能替他决定**）

1. **`userGrantedThisTurn` 填什么？**
   今天没有「逐轮授权」的 UI。填 `false` ⇒ 若路由实现在此字段上做 D2 闸，
   云端会被判回本地；填 `true` ⇒ 等于默认授权，与「显式授权」的承诺冲突。
   → 需要先定「对话文本算 D1 还是 D2」（README 已列为 P2）。
   **在此之前，`PolicyRoutingPort` 不应读这个字段做决定。**

2. **`payloadClasses` 的 D 分级**
   聊天文本今天按 D1 申报。若定为 D2，permit 语义要从「每轮显式授权」
   改成「类别白名单授权」—— 这是授权粒度变更，不是加个字段。

3. **流式**
   `TokenStream` 期望增量回调，`CloudCallAdapter.once` 是聚合返回。
   要么给 adapter 加流式变体（SSE 已经是逐行解析的，成本不高），
   要么云端先整段交付（`StreamingCapability.WHOLE_TEXT`），UI 会有一次明显停顿。
   **建议先整段**，把流式留给下一轮 —— 但这会让「云端比本地快」的预期落空。

4. **`ChatLoop` 的 `InferenceFocus` 租约**
   租约是为「单 Engine 单 Conversation」设计的。云端无此约束，
   但租约若在云端路径上也申请，会让云端调用与端侧串行化 ⇒ **失去并行的意义**。
   建议：云端路径**不申请** `InferenceFocus`，但要确认 `ChatLoop` 的
   `Slot` 结构不会因为「本轮没有 conversation」而 NPE。
   ★ 这条最容易在真机上炸，且单测很难覆盖（需要并发时序）。

---

## 六、施工顺序建议

1. 先加可空参数（全 null ⇒ 行为不变），跑全量确认 1125 仍绿 —— **这一步必须先独立提交**。
2. 再实现 `answerViaCloud` + T1/T2/T3，跑绿。
3. 最后接 `:app` 装配（去掉默认 null），跑 T4/T5/T6。
4. 真机验证：需要能连外网的设备/模拟器 + 一个真实 OpenAI 兼容端点。
   在此之前，`docs/04` 的 GO/NO-GO 不得因本文而放宽。

---

## 七、本文的边界

- 本文**没有**实现任何代码，只是把「改哪里、为什么、怎么验」写到可执行。
- 本文**不改变** DOM 红线：`TaskKind.PageTask ⇒ LocalOnly` 是路由实现的义务，
  本文只是要求调用点显式声明 `taskKind`。
- 本文第 §五 的四条**没有被解决**，它们是下一轮的前置决策，不是实现细节。
