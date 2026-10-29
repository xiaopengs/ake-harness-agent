# 《本地端 GUI Agent》· 云端数据落盘与密钥材料处置：v3 → v4 Schema 设计

> 撰写：宋砚秋（Cyrus Ledger）· 阿客实验室研发部 · 数据库方向（中文）
> 主理人：岑执衡　上游：`docs/02-database.md`（既有 DB 事实）／`docs/06-chat-database.md`（v3 表结构）／`docs/08-cloud-model-architecture.md`（顾图南的云端架构，尤其 §6/§7）
> 本文：**只产出设计，不改任何 `.kt` / `.gradle.kts` 文件**。文中的 Entity／Migration／DAO 是签名级与可直接照抄的代码，但落地由主程（`霍承钧`）执行。
> 本轮范围：`:data` 模块的设计边界 + 本文档。`:core:ports` / `:cloudai` / `:inference` / `:llm` / `:chat` / `:ui` 一律不碰（并行同事在作业）。
>
> **纪律：本文作者不执行任何 git 命令。**

---

## 0. 先说三条结论：本文档的三个主张

1. **A5（凭证密文落点）我推翻顾图南的推荐**：密文进 Room，不进独立文件。理由见 §4 —— 他给出的两条论据经代码核实**均不成立**，而他的方案会引入一个真正的安全缺口（残留密钥材料）。
2. **`egressGateVerdict` 存量数据建议：不重写**。原因不是"懒"，而是**这张表在今天的全量设备上恒为空 —— 且这一点是可被结构性证明 + 被外部 oracle 验证的**（见 §5.3）。重写一段永远不会被执行的 UPDATE 是 `ChatMigration2To3` 已经拒绝过一次的同类错误。
3. **`route_policy` 表建议不建**。它会把"浏览器任务永不出境"这条红线从**类型级**降级成**配置项级** —— 那是主理人明令禁止的形态。详见 §2.4。

外加一条**非本轮范围但必须立刻记录的既有缺陷**（§1.3）：`ChatMigration2To3` 里的 `PRAGMA foreign_keys = OFF` 在 Room 的事务内是**静默 no-op**。当前恰好无害（可证），但它埋在下一轮任何一次表重建里 —— 而它今天的测试仍然会亮绿。

---

## 1. 核实结果（以代码为准）

### 1.1 与转述一致的部分

| # | 事项 | 核实结果 | 出处 |
|---|---|---|---|
| V1 | Room 当前 `user_version = 3` | ✅ 属实 | `AkeDatabase.VERSION = 3`（`AkeDatabase.kt:129`） |
| V2 | 20 张表 / `integrity_check=ok` | ⚠ **措辞要修**：`@Database(entities=[...])` 里是 **19 张 Entity 表**；真机上 `sqlite_master` 数出来的 20 张含 Room 自己的 `room_master_table`。两边都对，但**文档不能混用** | `AkeDatabase.kt:41` 自述「19 张表」；`data/schemas/.../3.json` 里 `entities` 长度 = 19；`docs/07` M5 数的是 `sqlite_master` = 20 |
| V3 | `exportSchema = true`，schema 落在 `data/schemas/` | ✅ 属实，且**两个 JSON 已签入 git** | `data/build.gradle.kts` 的 `ksp { arg("room.schemaLocation") }`；目录里有 `2.json` (58 KB) / `3.json` (90 KB) |
| V4 | `FieldCipher` + `AndroidKeystoreFieldCipher` 已存在 | ✅ 属实。且比顾图南描述**更干净**：它没有任何 fallback —— `key()` 要么拿到要么生成，没有第三 path | `core/common/.../RedactionGate.kt:18-27`；`app/src/main/java/com/ake/app/security/AndroidKeystoreFieldCipher.kt:44-102` |
| V5 | `egressGateVerdict` 有两套常量 | ✅ 属实，但要补一条：**entity 侧那两个（PASS/BLOCKED）使用点数为 0**；`:chat` 侧那两个（SCREENED/GATE_DENIED）有使用点 | `ChatEntities.kt:497-498`（定义处，无引用）；`ChatEgress.kt:62,67`（引用处） |
| V6 | 既有 v3 迁移链 `1→2, 2→3` 在 `ADD COLUMNS` 上既有 `memory_card` 重建 | ✅ 属实，重建用 12 步 + `PRAGMA foreign_keys=OFF/ON` 包裹 | `ChatMigration2To3.kt:299-350` |

### 1.2 三条**与 08 文档转述不一致**的修正（请主理人确认）

#### ★ 修正 N1：`route_decision` 在今天的全量设备上**恒为空**，且这是可被证明的

不是"看起来没什么数据"，是**结构体上不可能有行**：

```
RouteDecisionSink.shouldAudit(ctx, decision) =
        ctx.requested != LOCAL_ONLY
    ||  decision.target != LOCAL_ONLY
    ||  forceAudit
```

三条每一项都为假：

| 项 | 为什么恒假 | 出处 |
|---|---|---|
| `decision.target != LOCAL_ONLY` | `LocalOnlyRoutingPort.decide()` 两个代码路径（正常 + catch）**都**返回 `InferenceTarget.LOCAL_ONLY` | `ChatEgress.kt:120-139`（对应类 `LocalOnlyRoutingPort`，方法 `decide()`） |
| `ctx.requested != LOCAL_ONLY` | `InferenceTarget` 只有两个枚举值；MVP 没有任何 UI 入口能让用户表达"想出云"，调用方传 `LOCAL_ONLY` | `core/ports/Chat.kt:865`：`enum class InferenceTarget { LOCAL_ONLY, CLOUD_ASSISTED }` |
| `forceAudit` | **注释写的是"`BuildConfig.DEBUG` 绑进来即可"，但实际装配没绑** ⇒ 取默认 `false` | 装配点：`PersistenceModule.kt:751-752` 只传 `dao` + `clock`，不传 `forceAudit`；默认值在 `RouteDecisionSink.kt:46` |

⇒ 结论：**全量设备上的 `route_decision` 行数为 0**。§5.3 给出外部 oracle（真机读数），把这条从"代码推理"升级为"实测事实"。

> **这不是可以忽略的细节**：§5 的全部取舍（要不要重写存量、要不要重建表）都建立在它之上。如果 oracle 打脸，§5 要重做 —— 所以我把这条列为自己认为最可能被真机验证打脸的第一候选（见 §7.3）。

#### ★ 修正 N2：`RouteDecisionSink` **确实**已经被接上了真 sink

`docs/08` §0.1 第 7 条说 ChatEgress"直接 return"。那是 `attempt()` 的路径；但**审计这条链路是通的**：

```kotlin
// app/src/main/java/com/ake/app/di/ChatPortsModule.kt
audit = ChatEgress.AuditSink { ctx, decision, messageId, bytes, verdict ->
    routeSink.record(ctx, decision, messageId, bytes, verdict)   // ← 真 sink，不是 AuditSink.NONE
},
```

⇒ **不存在"`AuditSink.NONE` 导致空表"这个解释。** 空表的唯一原因是 `shouldAudit` 判据。这条很重要：它排除了"接错了所以空"这个假 rationale，让 N1 的证明成立。

#### ★ 修正 N3：本机的外部 oracle 可得性，与 `docs/08` §9.1 的假设不同

| oracle | 本机可得？ | 实测 |
|---|---|---|
| `sqlite3` CLI | ❌ **没有** | `which sqlite3` → not found；`apt-get install -y sqlite3` → `E: Unable to locate package sqlite3` |
| `python3` + `sqlite3` 库 | ✅ 有，SQLite **3.45.1** | `python3 -c "import sqlite3; sqlite3.sqlite_version"` → `3.45.1` |
| `python3 -m sqlite3` CLI 模块 | ❌ 没有（3.11 起才有） | `No module named sqlite3.__main__` |
| Room 自带 `sqlite-bundled` 的 SQLite 版本 | ❓ **未能在本机确证** | coordinate 是 `androidx.sqlite:sqlite-bundled:2.7.1`；从 `libsqliteJni.so` 用 `strings` 提取到的候选取值未收敛到唯一值 |

⇒ **两条直接后果**：
1. `docs/08` §9.1 列的「sqlite3 CLI」这一条 oracle **当前不可用**。§7.1 给出三层替代 oracle，其中最实用的一层是 **`python3` 脚本 + `sqlite3` 库**（今天就能跑），而不是等装 CLI。
2. **存在 SQLite 版本 skew**：python oracle 是 3.45.1，真机是 bundled 的（版本待确认）。凡是依赖 SQLite 版本行为的写法（如 §1.3 的 PRAGMA），必须在真机上复跑一次，不能用 python 的结果代替。**这是硬要求，不是谨慎。**

### 1.3 ★ 既有缺陷：`PRAGMA foreign_keys = OFF` 在事务内是静默 no-op（可实证）

`ChatMigration2To3.MEMORY_CARD_REBUILD` 的头尾是：

```kotlin
"PRAGMA foreign_keys = OFF",   // 📍 第一条
... 12 步重建 ...
"PRAGMA foreign_keys = ON",    // 📍 最后一条
```

它在 `AkeMigrationSchemaTest` 里被断言成这样：

```kotlin
assertThat(rebuild.first()).isEqualTo("PRAGMA foreign_keys = OFF")
assertThat(rebuild.last()).isEqualTo("PRAGMA foreign_keys = ON")
```

**这个断言的形状是对的（盯住了"首尾各有一条 PRAGMA"），但 SQLite 的实际行为让那两条 PRAGMA 在 Room 里可能完全失效。** 用 SQLite 跑最小实验（本机 python sqlite3 3.45.1，脚本见 §7.1 的 L2）：

```
=== A. 事务外切换 foreign_keys ===
 before :  (1,)      ← FK 开
 OFF->  :  (0,)      ← ★ 生效

=== B. 事务**内**切换 foreign_keys（Room Migration 的实际处境）===
 in-txn before:      (1,)
 in-txn after OFF:   (1,)          ← ★ 没变！no-op
 child.pid after delete parent: (None,)   ← ★ 证据：ON DELETE SET NULL 仍然被触发了
```

**判据不是读数，是行为**：实验里删掉父行之后，子行的外键列仍然被 `ON DELETE SET NULL` 改成了 NULL —— 证明外键约束**确实还在生效**，`PRAGMA` 没有关掉它。

`sqlite.org/pragma.html#pragma_foreign_keys` 的原文：「This pragma is a no-op within a transaction; foreign key constraint enforcement may only be enabled or disabled when there is no pending BEGIN or SAVEPOINT.」

#### 为什么它今天没炸（可证）

`memory_card` 在这套 schema 里是**子表**，不是父表：`memory_card.source_task_id REFERENCES task(id)`。没人是它的孩子 ⇒ `DROP TABLE memory_card` 不会触发任何 CASCADE/SET NULL ⇒ no-op 的那次 `OFF` 恰好什么也没损失。

#### 为什么下一轮会炸

只要有人重建一张**被别人 REFERENCES 的表**（例如某天要改 `task` 或 `chat_session` 的 UNIQUE），同一段 `OFF` 会失效，而那条断言仍然会亮绿 —— **测试绿、数据错**。这正是 `docs/07` §2 点名的那类最危险失败形态：脚本（或测试）自己的问题，表现却像被测对象坏了。

#### 本轮的处置（不是本轮改动，是写入 next-round backlog）

- ✅ **v4 迁移不含任何表重建** ⇒ 本轮不受影响（§3.1）。
- 📌 给 `AkeMigrations` 头部加一条纪律（下一轮由我或霍承钧执行）：**表重建一律用 `PRAGMA defer_foreign_keys = ON`，不用 `PRAGMA foreign_keys = OFF`**。

理由（同一份实验的 C 段）：

```
=== C. 事务内用 defer_foreign_keys（SQLite >=3.20）===
 in-txn fk before:                       (1,)     ← 外键仍然开着
 in-txn defer before:                    (0,)
 in-txn defer after:                     (1,)     ← ★ 事务内确实可切换
 child.pid after delete (deferred):      (None,)  ← 中间态允许不一致
 foreign_key_check in txn:               []       ← ★ 事务内不检查
 foreign_key_check AFTER commit:         []       ← COMMIT 时才检查（此时重建已完成）
```

`defer_foreign_keys` **在事务内可用（这是它被设计的唯一场景）**，且把约束检查推迟到 COMMIT —— 正好覆盖"重建过程中中间态必然不一致"这个需求。**这一个替换同时解决了" Room 是否包事务"的不确定性**：它两种情形下都正确。

---

## 2. 表清单裁决（每张表给"为什么必须有它" / "为什么不该有它"）

### 2.0 总览

| 表 | 裁决 | 一句话理由 |
|---|---|---|
| `cloud_credential` | ✅ **建** | 凭证的元数据 + 密文需要一个**可查询、可引用、可被 wipe 一处覆盖**的落点 |
| `cloud_provider`（厂商预设注册表） | ⏸ **本轮不建** | 它会引入第二份"各家路径前缀"实现（本项目唯一真实安全 bug 的根因形态），而内容是编译期常量 —— 详见 §2.3 |
| `route_policy` | ❌ **不建** | 它会把红线①从类型级降级成配置项级。这是主理人明令禁止的形态 —— 详见 §2.4 |
| `route_decision` | ✅ **加列（不重建）** | 承载审计闭环：`outcome` / `attempt_phase` / `credential_id` / 幂等键 |
| `chat_session` | ✅ **加 3 列** | 承载"会话级允许动向 + 具体引用哪个凭证"这个**事实**（不是策略） |

**v4 的整体性质：全 Additive。** 没有一处 `DROP` / `RENAME` / 数据回填。这是"升级不丢数据"这个承诺能成立的前提，也是 §3.4 回滚方案能说硬话的原因。

### 2.1 ✅ `cloud_credential`（新建）

#### 为什么必须有它

不是"凭证总得有地方存"，是三条更具体的：

1. **`refs()` 要能被 UI 枚举并随改随新。** Room 的 `@Query(...): Flow` 自带 `InvalidationTracker`：写表 ⇒ Flow 自动发射。走独立文件就得手写一个 `MutableStateFlow` + 手动维持 MutationGuard（顾图南在 §6.6 已经承认这是他方案的代价）。
2. **审计行要能指向它。** `route_decision.credential_id` / `chat_session.cloud_credential_id` 要写外键，就必须与它同库 —— **跨 NSManagedObject 不能建外键**，这是我否决"第二个 Room 库"的理由之一（§4.2 末的 escape hatch 有完整对比）。
3. **清除范围必须只有一处判据。** 这是 `ConfigEntities` 类注释里已经确立的项目原则。Room 安置下，wipe 的 ③（删库）自动覆盖凭证，**零新​​增清除步骤**；独立文件则要新增一步，漏了就是"一键清除"变成假的（§4.2 第 4 问）。

#### Entity（可直接照抄）

```kotlin
// data/src/main/java/com/ake/data/persist/db/entity/CloudCredentialEntity.kt  （新文件）
package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ★★ 云端凭证（元数据 + 密文）。**这张表没有任何一列能装明文秘密**。
 *
 * ## 三条forcedimmerancesaceutical
 *
 *  ① **`base_url_enc` 也必须加密。** 不是偏执：自建网关 `Server Actions` 里写着
 *     把 key 放在 query 里（`?key=xxx`）—— 顾图南 §6.3 第 3 条亲自点名了这件事。
 *     于是"完整 URL"本身就是秘密载体。只加密 key 而把 URL 明文留在库里，等于
 *     §6.3 那份清单白写了三分之一。
 *  ② **`endpoint_host` 是唯一的明文派生值。** 它是 `Hosts.normalise(baseUrl)` 的结果，
 *     也就是 UI 本来就要显示的那个字符串（`docs/08` §6.7）—— 不是秘密，但
 *     **它是索引与去重的事实来源**，所以必须落一列。
 *  ③ **`secret_enc = NULL` 是一个状态，不是一个缺陷。** 它表示"密钥材料已被密码学粉碎"。
 *     ⇒ 删凭证**不用 DELETE 行**，而是把这一列置 NULL（crypto-shred 墓碑）。
 *     这样历史审计行仍能解析出"当时用的是哪个凭证"（见 §5.3）。
 *
 * ## ★ 为什么这里没有 `baseUrl` 明文列
 *
 * 若留一个明文列，那么"拿到库文件的人"就能读到完整 URL —— 而完整 URL 在某些自建
 * 网关上就是密钥本身。**加密它，等于把 §6.3 第 3 条从"约定"变成"结构"。**
 */
@Entity(tableName = "cloud_credential")
public data class CloudCredentialEntity(
    /** CredentialRef.id —— ULID。**不是秘密**，可进日志。 */
    @PrimaryKey @ColumnInfo(name = "cred_id") val credId: String,

    /** 用户起的别名。UI 显示。 */
    @ColumnInfo(name = "label") val label: String,

    /** ★ `Hosts.normalise(baseUrl)` 的结果。**非秘密**（UI 本就要显示它）。 */
    @ColumnInfo(name = "endpoint_host") val endpointHost: String,

    /** ★★ FieldCipher 密文：完整 BaseURL。**aad = credId.toByteArray()**（防跨行搬移）。 */
    @ColumnInfo(name = "base_url_enc") val baseUrlEnc: ByteArray,

    /** ★★ FieldCipher 密文：API Key。**NULL = 已粉碎**（或从未写入）。 */
    @ColumnInfo(name = "secret_enc") val secretEnc: ByteArray?,

    @ColumnInfo(name = "model_name") val modelName: String,
    /** REQUIRED | ALLOW_INSECURE_LAN —— 见 docs/08 §6.5。 */
    @ColumnInfo(name = "tls_mode", defaultValue = "'REQUIRED'") val tlsMode: String,

    /** 用户声明的 context window；NULL ⇒ 用默认 8192。 */
    @ColumnInfo(name = "declared_context_window") val declaredContextWindow: Int?,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 最近一次真正用它出云的时间。NULL ⇒ 从未用过（UI 可以提示"还没验过"）。 */
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,

    /** ★ 密码学粉碎时间戳。NULL = 活着。非 NULL = 墓碑（行留、密文已清空）。 */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,

    @ColumnInfo(name = "schema_v", defaultValue = "1") val schemaV: Int,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        /** 墓碑行的密文占位 —— **必须不是有效 GCM 信封**（`SecretEnvelope.MIN_LEN = 29`）。 */
        public val SHREDDED: ByteArray = ByteArray(0)
    }
}
```

⚠ `ByteArray` 在 Room entity 里会被 Map 成 `BLOB`，正确。但它是 `data class` 的字段 ⇒ **自动生成的 `equals()`/  `hashCode()`/`toString()` 会碰 }.addEventListener('silo', (e)=>{ ... })`;   =>尔 ofIceCandidates "})`;`= 0 字节数组 snapshot —— `toString()` 会打印 `[B@1a2b3c`（只有引用），所以**不泄漏**；但 `equals()` 会比较内容（这是我们要的语义）。结论：可接受，但**禁止**把它换成 `List<Byte>`（那会打印全部字节）。

#### 索引取舍：**零二级索引**

| 候选 | 裁决 | 理由 |
|---|---|---|
| `UNIQUE(endpoint_host, model_name)` | ❌ 不建 | 墓碑行仍占位 ⇒ 删掉的凭证会阻止同名重录（用户可见的功能 bug）。改为 store 层在事务内 `SELECT` 查重。 |
| `UNIQUE(label)` | ❌ 不建 | 同上。 |
| `idx_cred_endpoint_host` | ❌ 不建 | **这张表的行数上界是"人手输入凭证的个数"，现实位数量级**。全表扫描比维护索引便宜。 |
| `idx_cred_deleted_at` | ❌ 不建 | 同上；墓碑本身极端少见。 |
| PK（`cred_id`） | ✅ | Room 自动建 `UNIQUE`。 |

> **这条结论的失效条件（写死）：** 若将来出现"批量导入凭证"（行数 > ~100），上表三个 ❌ 全部要重估。这是我能给出的最诚实的边界 —— **我是在用"行数上界"这个可观测量为索引取舍定价，而不是凭感觉。**

### 2.2 ✅ `route_decision` 加列（不重建）

#### 为什么这些列（逐列给理由）

| 新列 | 为什么必须有它 |
|---|---|
| `outcome` | ★ 冷启 sweep 的唯一判据。没有它，"这条permit 有没有收敛"只能靠猜 `egress_gate_verdict` + `delivered_bytes` 的组合 —— 那是个不可靠的推断。 |
| `attempt_phase` | ★ 决定"能不能静默回落"（`docs/08` §4.4）。PRE 可回落、POST 禁止。这是**语义列**，不是调试信息。 |
| `credential_id` | 事后回答"这次用的是哪个 key"。`credential_ref` 这个名字顾图南用了，我改成 `_id` 是因为**它要和 CloudCredentialEntity 建外键**，而全仓既有习惯是 `*_id`（`chat_message_id` / `source_task_id` / `template_id`）。**更名建议，不是必须。** |
| `endpoint_host` | "字节发到哪去了" —— 且它是**归一化后的 host**（`Hosts.normalise` 唯一实现产出），不是 URL ⇒ 不会把 query 里的 key 带进来。 |
| `model_name` | 服务端偶尔"给你换了个模型" ⇒ 实际用的模型可能与配置不符，要能对账。 |
| `request_id` | 服务端返回的 `id`。**唯一的对外取证抓手**（`docs/08` §9.4-③ 自己点名了）。 |
| `delivered_bytes` | 实际发出的请求体字节数。**与 `redaction_applied` 组合后才能回答"发的时候到底有没有过闸"**。 |
| `finish_reason_raw` | 原文，不映射（Kimi 等私有值不丢信息）。 |
| `retry_of` | 指向上一行 id ⇒ 重试链完整；配合 `attempt_no` 让"这是第几次尝试"成为列而不是推断。 |
| `attempt_no` | 我加的。没有它，`retry_of` 链只能靠递归查询才知道深度。 |
| `idempotency_key` | ★★ **幂等键**，见 §6.2。这是"并发不产生重复行"的结构性装置。 |
| `policy_profile` | 我加的。记录"当时生效的策略常量是哪个版本"（编译期常量的**快照**）。有了它，审计行可以在策略改版半年后仍然可解释。**它是事实记录，不是策略源** —— 这正是它能存在而 `route_policy` 表不能存在的原因（§2.4）。 |
| `updated_at` | 我加的。Sweep 要能区分"老申请人已被遗漏"和"还在流式传输中"（§6.4）。 |

#### Entity（v4 完整定义，替换 `ChatEntities.kt` 里现有的 `RouteDecisionEntity`）

```kotlin
@Entity(
    tableName = "route_decision",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_message_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        // ★ v4：出云用的凭证。SET_NULL —— 凭证被彻底删除后，审计行**仍必须存在**
        //   （口令是"这台机器有没有把数据送出去过"，缺 durumunda记载会pa）。
        ForeignKey(
            entity = CloudCredentialEntity::class,
            parentColumns = ["cred_id"],
            childColumns = ["credential_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(name = "idx_route_message", value = ["chat_message_id"]),
        Index(name = "idx_route_decided_at", value = ["decided_at"]),
        Index(name = "idx_route_decided", value = ["decided", "decided_at"]),
        // ★ v4 —— 见下方"索引取舍"逐条理由
        Index(name = "idx_route_outcome", value = ["outcome", "decided_at"]),
        Index(name = "idx_route_idem", value = ["idempotency_key"], unique = true),
    ],
)
public data class RouteDecisionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "chat_message_id") val chatMessageId: String?,
    @ColumnInfo(name = "agent_id") val agentId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "turn_id") val turnId: String,

    /** LOCAL_ONLY | CLOUD_ASSISTED —— 用户/策略**请求**的目标 */
    @ColumnInfo(name = "requested") val requested: String,
    @ColumnInfo(name = "requested_classes_json") val requestedClassesJson: String,
    /** 实际裁决 */
    @ColumnInfo(name = "decided") val decided: String,
    @ColumnInfo(name = "reason_key") val reasonKey: String,
    @ColumnInfo(name = "allowed_classes_json") val allowedClassesJson: String,

    /**
     * ★ EgressGate 的结论。**常量收口到 `EgressVerdict`（见 §5.2），本 Entity 不再自带常量。**
     * NULL ⟺ 没走到出口这一步（本地路由）/ 或 v3 及以前的历史行。
     */
    @ColumnInfo(name = "egress_gate_verdict") val egressGateVerdict: String?,

    @ColumnInfo(name = "redaction_applied", defaultValue = "0") val redactionApplied: Int,
    @ColumnInfo(name = "payload_bytes", defaultValue = "0") val payloadBytes: Int,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long?,
    @ColumnInfo(name = "decided_at") val decidedAt: Long,

    // ─────────────────────────── v4 新增 ───────────────────────────

    /**
     * ★★ 本次尝试的终态（[AuditOutcome]）。**冷启 sweep 的唯一判据。**
     *
     * NULL 只可能是 v3 及以前的历史行（本表今日恒空 ⇒ 真机上不会有）。
     * ★ **UNKNOWN 不是终态** —— 迟到的 `close()` 允许把它改回 DELIVERED/PARTIAL/FAILED（§6.1）。
     */
    @ColumnInfo(name = "outcome") val outcome: String?,

    /** PRE | POST。**决定能不能静默回落**（docs/08 §4.4）。 */
    @ColumnInfo(name = "attempt_phase") val attemptPhase: String?,

    /** 本次尝试的序号（同一 turn 内单调递增，配合 [retryOf] 构成重试链）。 */
    @ColumnInfo(name = "attempt_no", defaultValue = "0") val attemptNo: Int,

    /** 上一行 id。NULL ⇒ 首帧尝试。 */
    @ColumnInfo(name = "retry_of") val retryOf: String?,

    /** ★ 幂等键（见 §6.2）：`"<turnId>:<attemptNo>"`。UNIQUE，NULL 视为彼此不同。 */
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,

    /** 使用的凭证。**外键 → cloud_credential(cred_id) ON DELETE SET NULL**。不是 key 本身。 */
    @ColumnInfo(name = "credential_id") val credentialId: String?,

    /** ★ `Hosts.normalise(baseUrl)` 的结果。**不是 URL** ⇒ 不可能把 query 里的 key 带进来。 */
    @ColumnInfo(name = "endpoint_host") val endpointHost: String?,

    @ColumnInfo(name = "model_name") val modelName: String?,
    /** 服务端返回的 `id`。**唯一的对外取证抓手**（docs/08 §9.4-③）。 */
    @ColumnInfo(name = "request_id") val requestId: String?,

    /** 实际发出的请求体字节数（= `payload_bytes` 的实测版；两者不一致即为可疑）。 */
    @ColumnInfo(name = "delivered_bytes", defaultValue = "0") val deliveredBytes: Int,

    /** ★ finish_reason 原文（不映射，避免丢 Kimi 等私有值）。 */
    @ColumnInfo(name = "finish_reason_raw") val finishReasonRaw: String?,

    /** ★ 策略常量快照。**它是事实记录，不是策略源**（§2.4 的关键区分）。 */
    @ColumnInfo(name = "policy_profile") val policyProfile: String?,

    /** ★ sweep 用来区分"早就该收敛了"与"还在流式传输"（§6.4）。 */
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long,

    @ColumnInfo(name = "schema_v", defaultValue = "1") val schemaV: Int,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 2   // ★ v3→v4：1 → 2
        // ★★ EGRESS_PASS / EGRESS_BLOCKED **删除**。EGRESS_PASS/BLOCKED
        //    没有前缀是不能重名的，尤其 ` =~ 常量coord。旧其次是 `"PASS"=""PASS"。see §5.2。
    }
}
```

#### 索引取舍（`route_decision`）

| 索引 | 裁决 | 理由 |
|---|---|---|
| `idx_route_outcome (outcome, decided_at)` | ✅ 建 | sweep 查询是 `WHERE outcome IN (…) AND decided_at < :before`。冷启 sweep 属于 fail-closed 机制的一部分，**延迟必须可预测**。表 5,000 行上限使得索引维护成本可忽略。 |
| `idx_route_idem (idempotency_key) UNIQUE` | ✅ 建 | **它就是幂等机制本身**（§6.2）。SQLite 的 UNIQUE 把 NULL 视为彼此不同 ⇒ 本地路由的行（键为 NULL）不冲突 —— 这与既有的 `idx_chat_msg_request` 完全相同（同一手法已在 v3 用过并被 `ChatMigration2To3` 注释论证过）。 |
| `idx_route_credential (credential_id)` | ❌ 不建 | （FK 子列，Room 会 warning；application does not filter by it anywhere. 行数上限 5,000 ⇒ parent DELETE 触发的子表扫描成本可忽略。若 retention 上限被抬高 10 倍 → 重估。） |
| `idx_route_turn (turn_id)` | ❌ 不建 | `byTurn()` 确实全表扫描，但 5,000 行上限下这是微秒级；**每多一个索引都抬 INSERT 成本**，而本表 Insert 在「每条 403 Forbidden 都要记」的最坏路径上。**这个"不建"依赖 MAX_ROWS=5000 这条约束，改动它必须重估。** |
| `idx_route_request_id` | ❌ 不建 | 取证时才用，频次近乎为零，且届时直接全表扫描。 |

> **重复一遍原則（写在这里便于 review 时对齐）：索引不是越多越好。本表的两条新增索引都是"机制的一部分"，不是"为了将来可能查得快"。**

### 2.3 ⏸ `cloud_provider`（厂商预设注册表）—— **本轮不建**

任务书点名要它，所以我给出完整形状，但**我的专业建议是推迟**。

#### 若将来要建，正确的形状如下（可直接照抄）

```sql
CREATE TABLE IF NOT EXISTS `cloud_provider` (
    `provider_id`          TEXT NOT NULL,      -- 'deepseek' / 'openai' / 'zhipu' …
    `display_name`         TEXT NOT NULL,
    `base_url_hint`        TEXT NOT NULL,      -- 默认 BaseURL（含是否已带 /v1）
    `default_context_window` INTEGER,
    `default_tls_mode`     TEXT NOT NULL DEFAULT 'REQUIRED',
    `sort_order`           INTEGER NOT NULL DEFAULT 0,
    `builtin`              INTEGER NOT NULL DEFAULT 1,   -- 1=种子数据（**迁移永不改动**） 0=用户自建
    PRIMARY KEY(`provider_id`)
);
-- 种子写入必须幂等且**不触碰用户自建行**：
INSERT OR IGNORE INTO `cloud_provider` (`provider_id`, ...) VALUES (...);
```

#### 为什么不建（三条，按强度排序）

1. **它引入第二份"厂商端点形状"实现，而这正是本项目唯一真实安全 bug 的根因形态。** `docs/08` §6.5 已经把"各家的路径前缀不一致"定为只能有一份实现的东西 —— 放进 `:cloudai` 的 Kotlin 里（`CloudEndpoints`），它就能靠 `Hosts.normalise` 那套三件套（唯一实现 + 转发清单 + 扫全仓断言份数）被**机械盯住**。**放进表里就盯不住了**：表里的行可以被任意 UPDATE，而"这份数据该不该有第二处来源"这件事不再有任何断言能回答。这不是"多一个选择"，是把已经收口的份数又拆开了。
2. **它会成为整个系统里唯一"靠迁移改数据"的表。** 加一个厂商 ⇒ 要么 Room 版本号 +1（`route_decision` 只想加列，却被这张表拖着一起 bump），要么引入一套"版本感知的种子下发脚本"（与用户自建行共存的语义很快就会长出分支）。两条路都比把它留在编译期常量贵。
3. **它没有新增能力。** "用户自建 endpoint" 的完整能力已经由 `cloud_credential` 覆盖（每行自带 `base_url_enc` + `model_name` + `tls_mode` + `declared_context_window`）。缺的只有"点击即用的预设列表"，而那是** UI 便利**，不是数据需求。

#### 什么时候该翻案（写死触发条件）

- 出现 **用户可编辑/可分享/可导出** 的厂商模板市场（`docs/05` §6.6 已列为纯设计）；**或**
- UI 明确需要"用户自建 provider 条目且要在卸载重装后仍在"。
- 届时它是**纯加表迁移**（便宜、无数据迁移），推迟不积累技术债。 ⇒ **推迟是零成本期权。**

### 2.4 ❌ `route_policy` —— **不建，且我要把理由说重一点**

主理人红线①的原话：「浏览器/网页任务强制走端侧，此边界必须**类型级**而非配置项」。

> **一张 `route_policy` 表，定义上就是"行为由行决定"。写下去的那一刻，红线①已经降级成配置项了 —— 不管表里填的是什么值。**

这不是"谨慎"，是归类错误：

| | 性质 | 谁能改 | 改了会发生什么 |
|---|---|---|---|
| 类型级保证 | 编译期的事实 | 只有改代码 + 过编译 | 编译不过 = 出不了包 |
| `route_policy` 行 | 运行期的数据 | 任何一个能写这张表的调用方 | 一条 UPDATE 就翻开它；且**写这条 UPDATE 的人不需要碰到任何红线相关代码** |

顾图南在 `docs/08` §5.6-⑧⑨ 已经坦白"改白名单 / 组合根手写裸 HTTP"只能靠 PR review。**新建一张策略表等于主动再开一条同级别的路，而不是堵一条。**

#### 替代方案：区分"政策来源"与"政策事实"

原则：**允许落库的是"用户做了什么决定"，不允许落库的是"系统允许什么"。**

| 需求 | ❌ 错的做法（策略源） | ✅ 本设计（事实记录） |
|---|---|---|
| 这个会话走云还是本地 | `route_policy` 里一行写死"允许/禁止" | `chat_session.model_target` 列 —— 它记录**用户在创建会话时选了什么**；能不能生效仍由 `InferenceTarget` sealed 类型决定（`docs/08` §5.7 / A3） |
| 用户同意过出云吗 | `route_policy.enabled` | `chat_session.cloud_consent_at`（时间戳，NULL=从未同意） |
| 用什么凭证 | `route_policy.credential_default` | `chat_session.cloud_credential_id`（外键） |
| 当时生效的是哪版策略 | — | `route_decision.policy_profile`（编译期常量的**快照**） |

⇒ **`chat_session` 加 3 列即可，不需要 `route_policy` 表。** 这 3 列全是"用户做过什么"的记录；真正决定"能不能出云"的仍然是 `:core:ports` 的 `InferenceTarget` sealed 与 `:cloudai` 拿不到 `PocketDom` 那道结构锁（顾图南 §5.3）。

#### `chat_session` 加列（Entity 片段）

```kotlin
// 追加到 ChatSessionEntity
    /**
     * ★ 本会话的模型目标。**这是"用户选了什么"的记录，不是"系统允许什么"的策略源**。
     *   真正能否出云由 `InferenceTarget`（sealed）+ DOM 锁决定，见 docs/08 §5.7。
     */
    @ColumnInfo(name = "model_target", defaultValue = "'LOCAL_ONLY'") val modelTarget: String,

    /** 本会话绑定的凭证。FK → cloud_credential(cred_id) ON DELETE SET NULL。 */
    @ColumnInfo(name = "cloud_credential_id") val cloudCredentialId: String?,

    /** ★ 用户为本会话「同意出云」的时间戳。NULL = 从未同意。 */
    @ColumnInfo(name = "cloud_consent_at") val cloudConsentAt: Long?,
```

索引：`idx_chat_sess_cred (cloud_credential_id)` —— **建**。理由：`docs/08` §6.7 要求删除凭证时提示"有 N 个会话正在用它"，那是一条真实的上线查询；且它是外键子列（不建会有警告）。

---

## 3. Migration 3 → 4

### 3.1 ★ 性质：全 Additive 迁移

**没有 `DROP`、没有 `RENAME`、没有重建、没有数据回填。** 于是"不丢数据"不是一句承诺，而是一个从语句清单就能读出来的性质：**每条 `ALTER` 只增列，每条 `CREATE` 都带 `IF NOT EXISTS`，没有任何语句能删除或修改既有行的值。**

这是刻意的设计取舍： §5.3 的"不重写存量"`、§2.1 的"墓碑而非 DELETE"indows，都为了让 v4 保持这个性质。

### 3.2 完整 SQL

```kotlin
// data/src/main/java/com/ake/data/persist/db/CloudMigration3To4.kt  （新文件）
package com.ake.data.persist.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * 3 → 4：云端模型推出（docs/09-cloud-data-and-secrets.md §3）。
 *
 * ## ★★ 全 Additive —— 这是本迁移最重要的一条性质
 *
 * 没有 DROP / RENAME / 重建 / 回填 ⇒ **结构上不可能删掉或修改既有行的值**。
 * 保留它：`AkeMigrationSchemaTest` 会有一条断言专门盯"迁移里出现
 */
internal object CloudMigration3To4 : Migration(3, 4) {

    // ───────────────────────────── ① 新表

    val CREATE_TABLES: List<String> = listOf(
        // ── cloud_credential（必须先于任何指向它的 ALTER）
        """
            CREATE TABLE IF NOT EXISTS `cloud_credential` (
                `cred_id` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `endpoint_host` TEXT NOT NULL,
                `base_url_enc` BLOB NOT NULL,
                `secret_enc` BLOB,
                `model_name` TEXT NOT NULL,
                `tls_mode` TEXT NOT NULL DEFAULT 'REQUIRED',
                `declared_context_window` INTEGER,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_used_at` INTEGER,
                `deleted_at` INTEGER,
                `schema_v` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`cred_id`)
            )
        """.trimIndent(),
    )

    // ───────────────────────────── ② 加列

    /**
     * ★ 全部可空或带 DEFAULT，无回填。
     *
     * ⚠ `ALTER TABLE ADD COLUMN` 加的列一定落在表的最末 —— 不要为了列序好看去重建表
     *   （`ChatMigration2To3.ADD_COLUMNS` 已有同一条纪律）。
     * ⚠ 带 REFERENCES 的 ADD COLUMN，**默认值必须是 NULL**（sqlite.org/lang_altertable.html §4）
     *   ⇒ `credential_id` / `cloud_credential_id` 必须可空 —— 它们恰好本来就应该是可空的。
     */
    val ADD_COLUMNS: List<String> = listOf(
        // ── N1 route_decision（13 列）
        "ALTER TABLE route_decision ADD COLUMN outcome TEXT",
        "ALTER TABLE route_decision ADD COLUMN attempt_phase TEXT",
        "ALTER TABLE route_decision ADD COLUMN attempt_no INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE route_decision ADD COLUMN retry_of TEXT",
        "ALTER TABLE route_decision ADD COLUMN idempotency_key TEXT",
        "ALTER TABLE route_decision ADD COLUMN credential_id TEXT " +
            "REFERENCES `cloud_credential`(`cred_id`) ON DELETE SET NULL ON UPDATE NO ACTION",
        "ALTER TABLE route_decision ADD COLUMN endpoint_host TEXT",
        "ALTER TABLE route_decision ADD COLUMN model_name TEXT",
        "ALTER TABLE route_decision ADD COLUMN request_id TEXT",
        "ALTER TABLE route_decision ADD COLUMN delivered_bytes INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE route_decision ADD COLUMN finish_reason_raw TEXT",
        "ALTER TABLE route_decision ADD COLUMN policy_profile TEXT",
        "ALTER TABLE route_decision ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",

        // ── N2 chat_session（3 列）
        "ALTER TABLE chat_session ADD COLUMN model_target TEXT NOT NULL DEFAULT 'LOCAL_ONLY'",
        "ALTER TABLE chat_session ADD COLUMN cloud_credential_id TEXT " +
            "REFERENCES `cloud_credential`(`cred_id`) ON DELETE SET NULL ON UPDATE NO ACTION",
        "ALTER TABLE chat_session ADD COLUMN cloud_consent_at INTEGER",
    )

    // ───────────────────────────── ③ 索引

    val CREATE_INDEXES: List<String> = listOf(
        // route_decision
        "CREATE INDEX IF NOT EXISTS `idx_route_outcome` ON `route_decision` (`outcome`, `decided_at`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_route_idem` ON `route_decision` (`idempotency_key`)",
        // chat_session（FK 子列 + 「哪些会话在用这个凭证」查询）
        "CREATE INDEX IF NOT EXISTS `idx_chat_sess_cred` ON `chat_session` (`cloud_credential_id`)",
    )

    val STATEMENTS: List<String> = CREATE_TABLES + ADD_COLUMNS + CREATE_INDEXES
    // ...
}
```

### 3.3 Migration 对象（`migrate()` 本体）—— ★ 与 v3 的差别在两处

```kotlin
    override fun migrate(connection: SQLiteConnection) {
        // ① CREATE 天然幂等
        for (sql in CREATE_TABLES) connection.execSQL(sql)

        // ② ★★ ALTER 不能带 IF NOT EXISTS ⇒ 必须查 pragma_table_info 守卫
        //    这是 v3 遗留的缺口：`ChatMigration2To3ADD_COLUMNS` 重跑会直接抛
        //    "duplicate column name"。声称"迁移幂等"的注释只对 CREATE 成立（它自己的
        //    `everyCreateIsIdempotent` 测试也只查了 CREATE 开头的语句）。
        for (sql in ADD_COLUMNS) {
            val parsed = parseAddColumn(sql)
            if (parsed == null || columnMissing(connection, parsed.table, parsed.column)) {
                connection.execSQL(sql)
            }
        }

        // ③ 索引天然幂等
        for (sql in CREATE_INDEXES) connection.execSQL(sql)

        // ④ 真验证：迁移后外键必须没被破坏。**必须放在最后**（所有（此时路由表的 FK 都建完了）。
        assertNoForeignKeyViolation(connection)
    }
```

支撑的三个私有方法（照抄 `ChatMigration2To3.assertNoForeignKeyViolation` 的形状，`prepare` 而不是 `execSQL` —— 后者的返回列 elf 拿不到，断言会形同虚设）：

```kotlin
    private data class AddColumn(val table: String, val column: String)

    private fun parseAddColumn(sql: String): AddColumn? {
        val m = Regex(
            "^ALTER\\s+TABLE\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+ADD\\s+COLUMN\\s+`?([A-Za-z_][A-Za-z0-9_]*)`?",
            RegexOption.IGNORE_CASE,
        ).find(sql.trim())
            ?: return null
        return AddColumn(m.groupValues[1], m.groupValues[2])
    }

    /** `PRAGMA table_info` 的第 2 列（index 1）是列名。 */
    private fun columnMissing(connection: SQLiteConnection, table: String, column: String): Boolean {
        val stmt: SQLiteStatement = connection.prepare("PRAGMA table_info(`$table`)")
        try {
            while (stmt.step()) {
                if (stmt.getText(1) == column) return false
            }
            return true
        } finally {
            stmt.close()
        }
    }

    private fun assertNoForeignKeyViolation(connection: SQLiteConnection) { /* 照抄既有实现 */ }
```

然后在 `AkeMigrations` 里登记（★ `ALL` 必须在文件末尾 —— 既有注释解释了为什么：object 初始化按声明顺序）：

```kotlin
object AkeMigrations {
    val MIGRATION_1_2: Migration = ...      // 既有
    val MIGRATION_2_3: Migration = ChatMigration2To3   // 既有
    val MIGRATION_3_4: Migration = CloudMigration3To4  // ★ 本轮

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
```

并把 `AkeDatabase.VERSION` 改成 `4`，在 `entities = [...]` 里加 `CloudCredentialEntity::class`，登记 `abstract fun cloudCredentialDao(): CloudCredentialDao`。

### 3.4 迁移失败时的行为、回滚方案、不丢数据的保证

#### ★ 先说清楚：Room migration 是不可逆的 —— **所以"回滚"这个词在运行时不存在**

一旦 `user_version` 被写成 4 且事务提交，就没有"回到 3"的 API； Room 只会去找 `4`→`5` 的宣称迁移。因此本项目能提供的**只有三种东西**，都必须写清楚，不能含糊：

| 机制 | 是什么 | 保证什么 | 强度 |
|---|---|---|---|
| **① 原子性** | Room 在**单一事务**内跑迁移，任一条 SQL 抛 ⇒ 整个事务回滚 ⇒ `user_version` 仍是 3 | 迁移失败**不会留下半套 v4 schema**。用户下次打开 App 会重跑迁移 | ★★★ SQLite 事务保证（外部 oracle 可验） |
| **② fail-fast** | 迁移抛异常后 Room 不开库 ⇒ 调用方拿到一个 SQLException / ISE | **不会带着错 schema 继续跑** —— 比"跑着跑着 `no such column`"好一万倍 | ★★★ |
| **③ 全 Additive** | §3.1：没有语句能删/改既有值 | **即使某条中间语句成功、后面失败**，回滚后数据仍是迁移前的样子 | ★★★ |

> **唯一真实的"回滚需求"是：发版后发现问题，需要出一个补救版本。** 做法是 **v4 → v5 的新迁移（继续向前）**，而不是试图回到 3。⇒ **不要为本设计任何形式的"降级迁移"；也不要用 `fallbackToDestructiveMigration()`**（它会删用户数据；`AkeMigrations` 头部注释已写明 release 构建不允许这个 flag）。

#### 迁移失败后的用户可见行为

Room 抛 ⇒ Hilt `@Singleton` 懒加载 `provideDatabase` 失败 ⇒ 首次触库的调用炸。**当前代码的失败方向是"崩"而不是"静默"，这是好的**（符合本项目"宁可崩也不静默降级"的一贯立场，见 `RedactionGate` 注释）。

但**这不是最优**：更好的形态是"崩之前先告诉用户正在迁移 + 失败了要联系而不是重装"。 ⇒ **交接项给邝随行**：在开库前跑一次 `getDatabasePath(...)` 存在性判断 + 迁移失败时把 `Migration didn't properly handle…` 这条原始 message **原样写进 crashlytics/logcat**（它已经是外部 oracle 的诊断输入，不能吞）。

#### "老用户升级不丢数据"的三条论证（这是本章最该被 review 的部分）

1. **语句级**：§3.1 全 Additive —— 没有一个语句能 DROP/UPDATE/DELETE。
2. **行数级**：L2 oracle（§7.1）在迁移**前后**逐表数行数并比对，含 `route_decision`（预期 0）、`chat_session` / `chat_message`（不得减少）。
3. **完整性级**：`PRAGMA integrity_check` / `PRAGMA foreign_key_check` 必须在迁移后都是 `ok` / 0 行。`assertNoForeignKeyViolation` 把后者已经写进了 `migrate()` 本体。

### 3.5 既有测试必须改动的地方（不列会造成"看着全绿其实新迁移没被守"）

`data/src/test/java/com/ake/data/persist/db/AkeMigrationSchemaTest.kt` 是**本仓库唯一**盯迁移的自写断言。本轮必须改：

| # | 断言 | 改动 | 不改的后果 |
|---|---|---|---|
| 1 | `versionAndStatementsAreWired` | `VERSION` 期望 3→4；`ALL` 期望 `"1->2","2->3"` → 加 `"3->4"`；`contains(ChatMigration2To3)` → 加 `CloudMigration3To4` | 新迁移忘了登记进 `ALL` ⇒ 真机上根本不跑，而测试全绿 |
| 2 | `everyCreateIsIdempotent` | 复用。注意它只对 `CREATE` 生效 ⇒ 保留，但**要新加一条**（下一条） | — |
| 3 | 🆕 `addedColumnsMatchRoomSchema`（对 v4） | 复用既有的逐列比对逻辑，输入换成 `CloudMigration3To4.ADD_COLUMNS` 与 `4.json` | Entity 改了但迁移没改 ⇒ 开库时 `Migration didn't properly handle` ISE |
| 4 | 🆕 **FK 意图对齐断言** | ★ **既有测试有个盲点**：`Sql.parseAlterAddColumn` 会把 `REFERENCES` 子句切掉再比列形状 ⇒ **迁移里写了外键这件事没人盯**，迁移忘写 REFERENCES 也没人盯。新断言：对每条含 `REFERENCES` 的 ALTER，去 `4.json` 的 `foreignKeys` 里找同名（`childColumns` 那一侧）并比对 parent/onDelete/onUpdate | 这是我唯一一处要加法.classpath 的同时补的洞：**它本该在 v3 就有** |
| 5 | 🆕 `migrationIsAdditive` | 断言 `CloudMigration3To4.STATEMENTS` 里不含 `DROP` / `RENAME` / `UPDATE ` / `DELETE ` / 而且 regex 匹配 `^\s*ALTER TABLE ... ADD COLUMN` | §3.4 的"不丢数据"论证会退化成一句无人复核的注释 |
| 6 | `legacyTablesOnlyGainedColumns` | 增加基底：v3 的所有表在 v4 必须仍在、旧列一个不少 | — |

---

## 4. ★ A5 裁决：凭证密文**进 Room**（推翻 `docs/08` §6.6 的推荐）

### 4.1 裁决

> **我推翻顾图南的 A5 推荐。**
> **密文落 `ake_agent.db` 的 `cloud_credential` 表（§2.1），不落独立文件。**
> **MVP 复用既有 Keystore 别名 `ake.trace.dek.v1`（零 `:app` 改动、`WipePort` 立即自动覆盖）。**

这不是"两个方案差不多选一个"。**他给出的两条论据经代码核实均不成立**，而他的方案会引入一个独立文件专属的真实安全缺口。

### 4.2 逐条反驳与逐条回答四个子问题

#### 论据 1：「Room 有导出/备份/`bugreport` 的 DB dump 路径；独立文件可 `MODE_PRIVATE`，且不在 DB dump 里」—— ❌ **不成立**

**核实结果：备份这条路在本项目里对两种落点**一律**已经堵死，不是差异项。**

`app/src/main/AndroidManifest.xml:31-33`：

```xml
android:allowBackup="false"
android:fullBackupContent="false"
android:dataExtractionRules="@xml/data_extraction_rules"
```

而 `app/src/main/res/xml/data_extraction_rules.xml` 对 **cloud-backup 与 device-transfer 两种**都排除了全部 5 个 domain，其中**包含 `file` 与 `database` 两者**（均在 excluded 名单）：

```xml
<exclude domain="file"     path="." />
<exclude domain="database" path="." />
```

⇒ **`allowBackup` 关掉的正是"云端/ADB 备份"（含 `adb backup`）；Android 12+ 的"换机迁移"走 D2D、不受 `allowBackup` 约束，而它也已经被这份 rules 关掉。**
⇒ Room 的 DB 与独立文件在这条路上**完全等价**。这条论据的差异 **= 0**。

> 附带证据（立场一致）：`WipeEverythingUseCase` 伴生对象里的注释原文是「一个必须一并处理、但极易被漏掉的点：`android:allowBackup="false"` + Android 12+ `dataExtractionRules`。否则「云备份 / 换机迁移」会把本应不出机的数据带出去 —— 那是合规红线，不是优化项。**已由 `:app` 的 Manifest 处理**」。⇒ 项目早就处理了，只是 `docs/08` §6.6 在论证存储落点时没有去看 Manifest。

#### 论据 2（隐含在 "`exportSchema` 泄漏" 这一问里）：Room 的 schema JSON 会不会泄漏凭证？ —— ❌ **不会**

Room 的 `exportSchema` 产出的是**结构**，不含一行数据。核实 `3.json` 的实际内容：顶层是 `database.entities[]`，每个 entity 只有 `tableName` / `createSql` / `fields[]`（`columnName`/`affinity`/`notNull`/`defaultValue`）/ `indices[]`，以及一个 `identityHash`。**没有任何地方能装行数据。**

⇒ 密文放 Room，`3.sqlite` 类型的（i.e. `4.json`）**永远不会包含它**。这条担心是把"schema"与"data dump"混为一谈。

#### 现在逐条回答你要求的四个子问题

**问 1：会不会进 `adb backup`？ `allowBackup` / `fullBackupContent` / `dataExtractionRules`（Android 12+）该怎么设？**

✅ **不会进。** 依据：`allowBackup="false"` + `fullBackupContent="false"` + `data_extraction_rules.xml` 对 cloud-backup / device-transfer 双双排除 `root`/`file`/`database`/`sharedpref`/`external` 五个 domain。

**怎么设：不用改，现在就是对的。** 但有两件事要做：
- (a) **把它变成一条会被跑的断言**（见 §4.3 的 D3）：现在它是"一份正确的 Manifest"，半年后被人改错没人会发现。
- (b) **补一条 Caution：`RoomDatabase.Builder` 不得使用 `createFromFile` / `createFromAsset` 指向任何外部来源** —— 那是唯一能绕过 default db 路径的人。这不是本轮风险，但要进黑名单。

**问 2：会不会经 `Room` 的 exportSchema JSON 泄漏？**

✅ **不会**（§4.2 论据 2）。且要注意一个**反向**的事实：正因为 schema JSON 已签入 git，**如果你给新表起名叫 `cloud_credential_secret` 之类，那是把结构公之于众**（不泄漏数据，但泄漏设计）。现在的命名没有这个问题。

**问 3：卸载/清数据时是否会残留？**

| 场景 | Room 落点 | 独立文件落点 | 结论 |
|---|---|---|---|
| **卸载 App** | 系统删整个 `/data/data/<pkg>` ⇒ 无残留 | 同 ⇒ 无残留 | **等价** |
| **设置 → 清除数据 / Clear data** | 同上 ⇒ 无残留 | 同上 ⇒ 无残留 | **等价** |
| **App 内「一键清除」（`WipeEverythingUseCase`）** | ✅ **自动覆盖**：① 删 Keystore别名（密码学粉碎）+ ③ `context.deleteDatabase(...)` 删库文件（含 `-wal`/`-shm`） | ⚠ **需要新增一步**，且要改 `:app` 与 `:data` 两边 | ★ **Room 完胜** |
| **用户单删一个凭证** | crypto-shred 墓碑：`secret_enc=NULL` + `deleted_at=now`（§2.1） ⇒ 审计行仍可解析，且密文即刻不可恢复 | 需 `File.delete()`；Android 上 `delete()` 不保证 crash-consistent ⇒ 中断会留下"半个文件"，而半开的 GCM 信封 `open()` 返回 null，**与"密钥被销毁"不可区分** ⇒ 用户看到 `CREDENTIAL_MISSING` 却不知道为什么 | ★ **Room 完胜** |

> **问 3 是本次裁决的决定性理由。** 顾图南在 §6.6 写「代价：凭证不享受 Room 的事务与 Flow 通知」—— 他把这个代价写小了。真正的代价不是"少了事务便利"，而是 **清除清单上多了一个必须被人类记住的路径**。
>
> 而 `WipeEverythingUseCase` 的类注释已经把这个道理说死了（原文大意）：
> 「**v3 为什么必须新增 ④b / ④c —— 「一键清除」漏掉人格与聊天记录，比不做这个功能更糟**：
> 用户按了「清除全部痕迹」，而最私密的那部分还躺在磁盘上 —— 那是一次对信任的透支。」
>
> 同一段推理套到凭证上只会更强：**凭证是所有数据里最敏感的一份**。让它成为唯一需要"额外记得删"的东西，是在主动制造一个清除缺口。

**问 4：`debug` 构建是否因为 `disableEncryption` 或明文 fallback 而降级？这种降级必须能被检出，不能静默。**

**先看现状（核实结果）**：

- `AndroidKeystoreFieldCipher` **没有任何 fallback**。`key()` 要么拿到 Keystore 里的 key，要么 `generate(ks)` 生成。**不存在"拿不到就返回明文"的分支。**
- `PersistenceRedactionGate(cipher: FieldCipher? = null)` 注释写着「未提供时的 `sealField` 会抛 —— 宁可崩溃也绝不静默明文落盘」。
- ⇒ **今天没有降级路径。这很好，但它是"现状"，不是"结构保证"。**

**真正的降级路径是**"某个 debug source set 里被人加了一个 `NoOpFieldCipher`"。所以检出必须是**结构性**的：

| # | 检出机制 | 强度 | 说明 |
|---|---|---|---|
| **D1** | **份数断言**（照抄 `Hosts.kt` 三件套）：扫全仓**所有 source set**（`src/debug` / `src/test` / 各 flavor 一律要扫，不能只扫 `src/main`），断言 `: FieldCipher` 的实现**恰好 1 个** | ★★★ | 这是本项目唯一一条已经做到"靠结构不靠纪律"的先例（`docs/08` §0.2-B），把同一手法套过来是风险最低的选择。**只扫 `src/main` 这条断言等于没有** —— debug-only 的 no-op 恰恰正是要防的那一类。 |
| **D2** | **落盘字节 Canary 测试**：用 `FieldCipher.seal()` 封一个已知字符串（如 `"ake-cipher-canary-v4"`），再用 `@RawQuery` 把 blob **原样读回来**（绕过 `open()`），断言明文的 UTF-8 字节不在 blob 中出现 | ★★★ | 绕过 `open()` 是**关键**：如果只"seal 再 open 看值对不对"，那么一个直接返回明文的 no-op 实现**也能通过**（它原样返回明文，值当然对）。必须验的是"**磁盘上的字节不是明文**"。 |
| **D3** | Manifest 断言：`apkanalyzer manifest print <apk>` 的输出必须同时含 `allowBackup="false"` 与 `dataExtractionRules` | ★★☆ | `apkanalyzer` 是 Android SDK 自带 ⇒ 是外部 oracle；相比 CI 里手写 XML 解析可靠得多 |
| **D4** | 构建变体红线：断言任何 build variant 都不出现 `fallbackToDestructiveMigration` / `allowMainThreadQueries` | ★★☆ | 与既有 `PersistenceModule` 取舍③ 一致（那里已明写"不允许 `allowMainThreadQueries()`"），只是把它从注释升级为断言 |

> **【这条我只能保证到这里】** ① D1/D2 都是"可以被写错、也可以被删"的断言 —— 它们把成本抬高，但不构成物理不可能。② `setIsStrongBoxBacked(true)` 是"有就用"而非"强制"：没有 StrongBox 的机型会**静默回落到 TEE** —— 这不算降级（TEE 同样不可导出密钥），但如果对外文案声称"StrongBox 强保证"，那是错的。**建议对外只说"密钥存放在不可导出的硬件密钥库中"，不要点名 StrongBox。**

### 4.3 上线时需要落的具体改动（列出各自归属，便于并行）

| # | 归属 | 改动 |
|---|---|---|
| W1 | **`:data`（我）** | 新增 `CloudCredentialEntity` / `CloudCredentialDao` / `CloudMigration3To4` / 改 `AkeDatabase` `AkeMigrations` / 改 `AkeMigrationSchemaTest`（§3.5） |
| W2 | **`:data`（我）** | `EgressVerdict` / `AuditOutcome` 的 **消费方**（DAO + sink），不再自带常量（§5.2） |
| W3 | **`:core:ports`（同事）** | 新增 `EgressVerdict` / `AuditOutcome` 常量对象（§5.2 给全文）。**这是本设计唯一一处必须请同事加的东西** |
| W4 | **`:core:ports`（同事）** | ★ **`EndpointConfig.baseUrl` 的处理**（§4.4）。当前的 `EndpointConfig` 既有 `baseUrl` 字段、又被要求"完整 URL 不出现在任何对外方法返回值里" —— 这是自相矛盾的。§4.4 给三个解法，**推荐那个不需要改字段名的** |
| W5 | **`WipePort` 接线** | MVP 复用 `ake.trace.dek.v1` ⇒ **`WipeEverythingUseCase` 不需要改一行**（① 已经删这个别名，③ 已经删库）。这是 design choice，见 §4.5 |
| W6 | **CI / 邝随行** | D1–D4 四条断言 |
| W7 | **`:app`（同事）** | 无（MVP 不需要）。见 §4.5 的 Phase 2 触发条件 |

### 4.4 需要同事裁决的一个矛盾（`EndpointConfig.baseUrl`）

`docs/08` §6.2 的 `EndpointConfig` 有字段 `val baseUrl: String`；但 §6.3 第 3 条又说「**完整 URL 不出现在 `EndpointConfig` 的任何对外方法返回值里** —— 它只作为 `upsert` 的入参存在」。这两句不能同时为真（data class 的字段就是它的返回值的一部分）。

我的建议（**推荐 ②，它不需要改字段名，对并行同事冲击最小**）：

| 方案 | 做法 | 评价 |
|---|---|---|
| ① | 把 `baseUrl` 从 `EndpointConfig` 拿掉，改成 `EndpointConfigView { host; modelName; tlsMode; declaredContextWindow; label }` | 最干净，但改 public API 形状，同事要动 UI |
| ② **推荐** | `EndpointConfig` 保留 `baseUrl` 字段，但 **🎯 限定 `upsert` 的入参形态**：构造函数标 `@JvmInline value class EndpointUrl`，只有一个构造入口 `EndpointUrl.fromUserInput(raw): Outcome<EndpointUrl>`，且 `toString()` 覆盖为 `"***"`（复用 `PlainSecret` 的同一手法）。同时 `configOf()` 改为返回不含它的投影 | 零字段重命名；泄漏防护与 `PlainSecret` 同构，一行不用改测试用例结构 |
| ③ | 让 UI 编辑时要求用户**重新输入**完整 URL（不回读） | 最快但 UX 差；可作为 ② 的降级备选 |

⇒ **我的结论是 §2.1 的存储裁决不依赖这个选择**（`base_url_enc` 无论如何都该加密），但完整链路的安全性依赖它。

### 4.5 Keystore 别名：MVP 复用 vs 拆专用

| | 复用 `ake.trace.dek.v1`（**我的推荐**） | 拆专用 `ake.cloudcred.dek.v1` |
|---|---|---|
| 需要改 `:app` | **否**（零冲突 —— 这是并行期的关键考量） | 是（`:app/security` + `PlatformModule` 都要动） |
| 清除路径 | ✅ ① 删别名 + ③ 删库 **已自动覆盖** | 需 `WipeEverythingUseCase` 多删一个别名（且顺序要在 ①） |
| 能"只退出云端而保留本地轨迹" | ❌ 不能 | ✅ 能 |
| 实际 MVP 有没有这个用例 | **没有**（没有"退出云端登录"这个概念） | — |

**裁决：MVP 复用。写死翻案触发条件：**

> **当且仅当**出现下列任一情形时，在下一轮拆专用别名：① UI 出现「退出云端」/「删除全部云端凭证并 Kraft本机》的入口；② 需要给云端凭证做独立密钥轮换。
> 拆的过程是"读旧 key 解密 → 用新 key 重加密"的一次性迁移，**必须配一份"旧 key 已删而重加密未完成"的兜底**（那时 `open()` 返回 null ⇒ 表现为凭证不可用 ⇒ fail-closed，可接受）。

### 4.6 Escape hatch rule（我给自己留的翻案通道）

我推翻了"独立文件"，但**接受一个更窄的、未来可能成立的版本**：

> 若出现下列任一情形，应把凭证迁到 **第二个 Room 数据库 `ake_secrets.db`**（**注意：不是裸文件**）：
> - (a) 出现"把 `ake_agent.db` 整个拷给第三方（issue 附件 / bug report 流程）"的既定流程；**或**
> - (b) 主库需要使用 `fallbackToDestructiveMigration()` 作为某次升级的兜底（那样凭证会随主库一起被清掉 —— 这或许是想要「desired behavior」也可能不是，需显式决定）。
>
> 迁到第二个 Room 而不是裸文件，是因为它能保留 §4.2「问 3」里 Room 完胜的两条（事务 + 墓碑），代价只是**失去跨库外键**（⇒ `route_decision.credential_id` 要从外键降级为普通 TEXT 列 + store 层校验，且 `chat_session.cloud_credential_id` 同理）。

> **注意 device-verify:`scripts/device-verify/dv/cases/m07_m11.py:370` 已经在用
> `adb exec-out run-as … cat databases/ake_agent.db` 拉库。** 这是一个真实存在、今天就在跑的"把整库拷到开发机"的流程。**它现在是安全的**（拷走的是没有 DEK 的密文），但它意味着 (a) 那条触发条件离我们不远 —— 我认为现在是 awareness 而不是 action。

---

## 5. `egressGateVerdict` 两套常量收口 + 存量迁移

### 5.1 先修正一件事：**是三套，不是两套**

顾图南 §0.2-C 指出两套（entity 的 PASS/BLOCKED vs `ChatEgress` 的 SCREENED/GATE_DENIED）。但他在尝试收口时（§7.5）引入了**第三套**：

```kotlin
// docs/08 §7.5 的收口方案（有问题的那一份）
public object EgressVerdict {
    public const val SCREENED: String = "SCREENED"
    public const val GATE_DENIED: String = "GATE_DENIED"
    public const val NOT_SENT: String = "NOT_SENT"      // ← 与 §7.2 的 AuditOutcome.NOT_SENT 撞
    public const val DELIVERED: String = "DELIVERED"    // ← 与 §7.2 的 AuditOutcome.DELIVERED 撞
    public const val PARTIAL: String = "PARTIAL"        // ← 与 §7.2 的 AuditOutcome.PARTIAL 撞
}
```

**这三个是另一个轴的语义。** 把它们塞进 `EgressVerdict` 会让"两套"变成"两套半"，半年后一定有人往同一个对象里加第 7 个常量并把两个轴彻底Concat。

### 5.2 收口方案：**两个轴、两个对象、不许合并**

**区分点不是"谁定义常量"，是"这列回答什么问题"：**

| 列 | 回答的问题 | 答案集（谁给的） | 语义轴 |
|---|---|---|---|
| `egress_gate_verdict` | **EgressGate 对"这份 payload 能不能过闸"的判断** | 闸口的三态 | 闸裁决 |
| `outcome` | **这一次尝试最终发生/没发生什么** | 尝试的七态 | 尝试状态 |

它们是**正交**的：`SCREENED` + `FAILED`（过闸了但网络挂了）和 `GATE_DENIED` + `NOT_SENT`（被拦下，一个字节没发）是两个完全不同的事实。**合并成一个对象是丢信息，不是简化。**

#### 交给 `:core:ports` 的常量（同事侧必的文件内容）

```kotlin
// :core:ports/src/main/java/com/ake/core/ports/EgressVerdict.kt   （新增 · 由同事落地）
package com.ake.core.ports

/**
 * ★★ `route_decision.egress_gate_verdict` 列的**唯一**取值来源。
 *
 * 【它能回答什么】EgressGate 对「这份 payload 够不够格过闸」的判断。
 * 【它不能回答什么】这次尝试到底发出去没有 —— 那是 [AuditOutcome] 的事。
 *
 * ★★ 两个对象不得合并。理由见 docs/09 §5.2：它们是正交的两个语义轴，
 *     合并会把「过闸了但网络挂了」与「被拦下一个字节没发」压成同一个值。
 *
 * ★ 常量份数由 D3 断言（扫全仓）盯住：**全仓不得有第二处定义下面这些字面量**。
 */
public object EgressVerdict {
    /** 未过闸（本地路由 / 或从未走到出口这一步）。也可表达为 NULL。 */
    public const val UNSCREENED: String = "UNSCREENED"
    /** 过闸放行。 */
    public const val SCREENED: String = "SCREENED"
    /** 过闸拦下。 */
    public const val GATE_DENIED: String = "GATE_DENIED"

    // ── ★ 历史字面量 → 规范值的映射（**只读方向的兼容层**，不是第三套常量）
    private val LEGACY: Map<String, String> = mapOf(
        "PASS" to SCREENED,
        "BLOCKED" to GATE_DENIED,
    )

    /** NULL 与未知值_UPWARDhandler 为 NULL（不抛 —— 审计读路径不得因为脏值炸掉主流程）。 */
    public fun normalise(raw: String?): String? =
        LEGACY[raw] ?: raw?.takeIf { it in setOf(UNSCREENED, SCREENED, GATE_DENIED) }

    public val ALL: Set<String> = setOf(UNSCREENED, SCREENED, GATE_DENIED)
}

/**
 * ★★ `route_decision.outcome` 列的**唯一**取值来源。
 *
 * 【它能回答什么】这一次尝试发生了什么。Cold-start sweep 的唯一判据。
 * 【★ UNKNOWN 不是终态】迟到的 `close()` 允许把它改回 PENDING 之外的值 —— 见 docs/09 §6.1。
 */
public object AuditOutcome {
    public const val PENDING: String = "PENDING"        // 已落行、未收敛
    public const val NOT_SENT: String = "NOT_SENT"      // PRE 阶段失败 ⇒ 一个字节都没发出
    public const val SENT: String = "SENT"              // 请求已发出，等响应
    public const val DELIVERED: String = "DELIVERED"    // 完整收到 [DONE]
    public const val PARTIAL: String = "PARTIAL"        // 流被截断，但收到部分
    public const val FAILED: String = "FAILED"          // POST 阶段失败
    public const val UNKNOWN: String = "UNKNOWN"        // ★ 进程死了 / 未收敛。诚实标注，**可回改**
    public const val DENIED: String = "DENIED"          // 闸根本没放行（GATE_DENIED 的 outcome 侧对应物）

    /** ★ sweep 的目标集：**只有这几个状态是被"允许被 sweep 改写"的**。 */
    public val SWEEPABLE: Set<String> = setOf(PENDING, SENT, UNKNOWN)
}
```

#### `:data` 侧的删除清单（我这边执行）

| 文件 | 改动 |
|---|---|
| `db/entity/ChatEntities.kt:497-498` | **删除** `EGRESS_PASS` / `EGRESS_BLOCKED`（已核实使用点 = 0） |
| `chat/.../ChatEgress.kt:98-99` | **删除** `VERDICT_SCREENED` / `VERDICT_GATE_DENIED`，改引用 `EgressVerdict`（同事侧： `:chat` 不在我地盘 ⇒ 由主理人转给对应同事；我只能改 `:data`） |
| `store/RouteDecisionSink.kt` | `record(...)` 的 `egressGateVerdict: String?` 参数改为**调用前已经过 `EgressVerdict.normalise()`**；sink 内部再兜一次 `normalise()`（**防线在线两侧都设一次**，与补给双向不得相依赖同一.jsonObject） |

### 5.3 ★ 存量数据：我的建议是**不重写**

#### 建议

**迁移不做任何 `UPDATE`。** 理由三条：

1. **这张表在今天的全量设备上恒为空（§1.2-N1）。** ⇒ 一条重写的 `UPDATE` 会是**从未被执行过的代码**。这与 `ChatMigration2To3` 拒绝 MCP 回填时的推理完全同一形：「为了一次回填而把它固化在迁移里，等于让一个临时格式活到下一个版本」，而且这里更糟 —— **迁移里那段 UPDATE 永远无法被测试覆盖，因为它永远不会作用于任何一行。**
2. **即使有行（oracle 打脸的情况）， reader-side 兼容层已经足够。** `EgressVerdict.normalise()` 在**读路径**把 `PASS`/`BLOCKED` 映射成 `SCREENED`/`GATE_DENIED`。这样：
   - 老字面对**所有前端统计与 DAO** 都表现为规范值；
   - **原始字面量保留在库里**（留痕价值：「这台设备上曾经存在过用旧常量写的决策」）；
   - 不需要 UPDATE，不需要额外 storagemanager。
3. **写侧的强保证不需要 UPDATE 来提供：** 从 v4 起，`EgressVerdictSink` 是唯一写入口，且它只接受已经被 `normalise()` 过的值。⇒ **新行不可能出现第 4 种字面量**，这一点由类型和收口后的单点保证，不依赖存量清洗。

#### 因此：**保留字面量**（这正是你问的"保留字面量？重写？"的答案）

> **裁决：保留字面量，读时规范化，不重写。**

#### ★ 必须补一条**验证式**而非**操作式**的收尾

迁移不重写 ≠ 放着不管。加一条**冷启动 / 或者 CI 的计数检查**（注意：它是"报告"，不是"修复"）：

```sql
-- 迁移后一次性运行：任何非规范字面量都是一条报警，不是一次修复
SELECT egress_gate_verdict, COUNT(*) FROM route_decision
 WHERE egress_gate_verdict IS NOT NULL
   AND egress_gate_verdict NOT IN ('UNSCREENED','SCREENED','GATE_DENIED')
 GROUP BY 1;
-- 期望：0 行结果。
-- 若有结果 ⇒ 说明 §1.2-N1 的"恒空"结论或其hydra38 fact 被推翻 ⇒ 回到 §5.3 重做取舍，而不是就地 UPDATE。
```

> **若 oracle 打脸（表非空）：** 那时才决定重写与否，且必须同时回答"这些行是谁写的、什么时候写的" —— 因为那意味着存在一个我今天没看见的写入路径。**先诊断，再清洗。**

### 5.4 份数断言（照抄 `Hosts.kt` 三件套的第三部分）

```
D5: 扫全仓 src/**（含所有 source set），断言字符串字面量 "SCREENED" | "GATE_DENIED" | "UNSCREENED"
    | "PENDING" | "NOT_SENT" | "DELIVERED" | "PARTIAL" | "UNKNOWN" | "DENIED"
    出现在 `const val … = "…"` 上下文的文件，恰好 1 个（即 :core:ports/EgressVerdict.kt + AuditOutcome.kt）。
```
- ` hypothes.is`：这条断言同时**堵住了"第四套常量"**，而不是只堵住今天这三套。
- 它与 `Hosts.kt` 那条既有的手法形态一致 ⇒ 复用成本最低、被绕过的心理门槛最高。

---

## 6. 审计无缺口的数据侧表达（状态机 / sweep / 幂等键 / 并发）

顾图南坦白 `kill -9` 窗口无法消除，并设计成 fail-closed（拿不到 permit 就发不出字节；缺口表现为一条可见的 PENDING/UNKNOWN 行）。**本节从数据侧把这个承诺落实成一组可被实现的机制，并诚实标注剩下的缺口。**

### 6.1 状态机

```
                          ┌──────────────► NOT_SENT      (PRE 失败：一个字节没发)  [终态]
                          │
  (INSERT) ─► PENDING ────┼──────────────► DENIED        (闸未放行)               [终态]
                          │
                          └──► SENT ──┬──► DELIVERED     (完整 [DONE])            [终态]
                                      ├──► PARTIAL       (截断，有部分内容)        [终态]
                                      └──► FAILED        (POST 阶段失败)          [终态]
                                            ▲
                                            │  ★ UNKNOWN **不是终态**：迟到的 close() 允许改回来
                          sweep: PENDING|SENT|UNKNOWN ──► UNKNOWN  ───────────────┘
```

**★ 关键设计：UNKNOWN 是可回改状态。** 这是我能 answering"长流式inguistically legitimate response 会被误判为 UNKNOWN"这个问题的最干净的办法：

- sweep 用 `updated_at < now - TTL` 做判据（而不是 decided_at）⇒ 长响应期间 `updated_at` 会被刷新；
- **但即使 TTL 判错、把一条还在跑的行标成了 UNKNOWN，`close()` 迟到了也能把它改回 DELIVERED。**
- ⇒ sweep 的 UPDATE 守卫必须是 `WHERE outcome IN ('PENDING','SENT','UNKNOWN')`（不是 `= 'PENDING'`），否则会与迟到的 close() 互相覆盖。

**这条同时解决了另一个问题**：如果 UNKNOWN 是终态，那么一次被误判的行就永久变成"不知道有没有发出去"，那比 sweep 之前更糟。

### 6.2 幂等键、重复行、并发 —— ★ 幂等键本身就是那个机制

#### 幂等键定义

```
idempotency_key = "<turnId>:<attemptNo>"
```

- `turnId` 在 Room v3 `chat_message.turn_id` 已经是同一轮多条消息共享的键；
- `attemptNo` 由调用方（重试/ probes）显式传入，单调递增；
- ⇒ 同一次 recommended attempt 的重复 `open()` ⇒ **同一个键** ⇒ 唯一索引拦截 ⇒ 返回既有的那一行 ⇒ **返回同一个 permit**。

#### UNIQUE 索引的 NULL 语义（这是办法的关键，不是巧合）

SQLite 的 UNIQUE 把 NULL 视为彼此不同 ⇒ **多行 NULL 不冲突**。于是：

> 「只有真正要走云的请求才带幂等键」这件事，**用一个普通 UNIQUE 索引就能表达**，不需要部分索引（而且 Room 的 `@Entity` 也不支持部分索引 —— ≤ v3 迁移动机。既有的 `idx_chat_msg_request` 用的就是同一手法）。

#### `open()` 的实现形状（给 sign 级）

```kotlin
// store/EgressPermitSinkImpl.kt （本轮不落地，给出形状）
override suspend fun open(ctx: RouteContext, decision: RoutingDecision): Outcome<EgressPermit> {
    val key = "${ctx.turnId}:${ctx.attemptNo}"          // ★ 幂等键
    val row = RouteDecisionEntity(/* … outcome = AuditOutcome.PENDING, updatedAt = now … */)
    return runCatching {
        dao.transaction {
            // ① INSERT OR IGNORE：键冲突 ⇒ 0 行受影响，什么都不改（**不覆盖既有行**）
            val fresh = dao.tryInsert(row)              // @Insert(onConflict = OnConflictStrategy.IGNORE)
            // ② 无论是不是新鲜插入，都读回同一行 ⇒ **同键必返回同一 permit**
            val existing = dao.byIdempotencyKey(key)    // @Query("SELECT * FROM route_decision WHERE idempotency_key = :k")
            checkNotNull(existing) { "idempotency insert/select race on key=$key" }
        }
    }.fold(
        onSuccess = { Outcome.Ok(EgressPermit(auditRowId = it.id, …)) },
        onFailure = { Outcome.Err(AppError.of(CloudFailureCodes.NO_PERMIT, ...)) },  // ★ fail-closed
    )
}
```

**为什么这能挡住重复行**：两个并发的 `open()` ⇒ 键相同 ⇒ `INSERT OR IGNORE` 只有一条成功 ⇒ 两者随后都 SELECT 到同一行 ⇒ 两个调用方拿到**同一个 `auditRowId`**。这是**结构性**的（唯一索引 + 读回），不是靠"加锁"或"调用方小心别重播"。

#### 并发写会不会产生重复行 —— 分层回答

| 层 | 机制 | 保证 |
|---|---|---|
| 主键 | `route_decision.id = ULID` | 不同 open() 永远不同主键 ⇒ 不会撞主键 |
| 幂等重播 | `UNIQUE(idx_route_idem)` + `INSERT OR IGNORE` + 读回 | ★ **同一 attempt 的重播不产生第二行** |
| 写者串行 | 既有：所有写事务跑在同一个单线程 `Executors.newSingleThreadExecutor` 上（`PersistenceModule.provideWriterExecutor`） | ★ 同进程内**写操作天然串行**，不存在"写-写"锁竞争；本项目借此规避 shared-executor 死锁场景 |
| Cross-process | **不存在**：Room 单进程；wipe 会先 `db.close()` | 无此场景 |

> **诚实标注：** 「写者串行」这条是**约定，不是结构保证**。它今天成立，是因为 `PersistenceModule` 把所有写事务都绑在同一个单线程 `writeScope` 上；**将来若有人在别处注入另一个 dispatcher 去调 DAO，这条保证会静默退化，而编译器不会说话。** 我能给的缓解是一条扫全仓断言：`:data` 源码中 DAO 的写方法不得在 `writeScope` 之外的 dispatcher 上被调用。**这条属于"只能靠 PR review + 断言"那一档，不在编译期。** 好消息是：即使它退化了，**幂等键那条仍然成立**（唯一索引是数据库层的，与谁在哪个线程调它无关）—— 两道防线互不以对方为前提。
 
### 6.3 ★ 冷启 sweep：照抄 `TaskReconciler` 的形状

**这是最重要的一条复用**：`TaskReconciler` 已经把这非凡模式在项目中落地并跑通（现有代码), ^^ shape: SweepReport + BATCH + liveness registry + runCatching + guarded UPDATE + metrics. `EgressAuditReconciler` 应当照抄它的形状，一字不改 except the@3 differences in AREAS 7.

```kotlin
// data/src/main/java/com/ake/data/persist/recovery/EgressAuditReconciler.kt  （新文件 · 本轮不落地）
public class EgressAuditReconciler(
    private val dao: RouteDecisionDao,
    private val clock: Clock,
    private val metrics: PersistenceMetrics,
    private val staleAfterMs: Long = PENDING_TTL_MS,
) {
    public data class SweepReport(val scanned: Int, val convergedUnknown: Int, val raced: Int)

    public suspend fun sweep(): SweepReport {
        val before = clock.nowMillis() - staleAfterMs
        val ids = dao.staleOpenIds(before, AuditOutcome.SWEEPABLE, BATCH)   // outcome IN (…) AND updated_at < ?
        var unknown = 0; var raced = 0
        for (id in ids) {
            val rows = dao.convergeToUnknown(id, clock.nowMillis())         // 见下 ★ 守卫
            if (rows == 0) raced++ else unknown++
        }
        if (unknown > 0) AkeLog.w(TAG, "sweep: $unknown egress audit row(s) → UNKNOWN")
        metrics.recordEgressSweep(unknown)
        return SweepReport(scanned = ids.size, convergedUnknown = unknown, raced = raced)
    }

    public companion object {
        /**
         * TTL 取 15 分钟的依据（比 TaskReconciler 的 10 分钟更宽松，不是照抄）：
         * SSE 长流可能 legitimately 持续数分钟；AGENTS.md 里ModelPort 的生成上限远大于 rollback window。
         * ⇒ **过严会把"还在跑"误标 UNKNOWN**。
         * ★ 但误标的代价已经被 §6.1 的「UNKNOWN 可回改」降到了很低 —— 这是两道防线，
         *   而不是把 TTL 调到某个"正确值"就完事。
         */
        public const val PENDING_TTL_MS: Long = 15L * 60L * 1000L
        public const val BATCH: Int = 20        // 与 TaskReconciler.BATCH 同口径
    }
}
```

#### 与 `TaskReconciler` 的**三处刻意差异**（必须写清楚，否则下一个人会"修"回去）

| 差异 | `TaskReconciler` | `EgressAuditReconciler` | 为什么 |
|---|---|---|---|
| 判据列 | `last_activity_at` | **`updated_at`** | sweep 判"有没有被改动过"，而 `outcome` 的中途更新也会刷新它 |
| liveness 检查 | ✅ `¬liveness.isLive(id)` | ❌ **不要** | ★ **任务侧要它，是因为"本进程还在跑"是真实存在的情况；而 egress 侧最该被 sweep 的那批 permit 恰好来自"上一个已经被杀掉的进程" —— 它们在 `TaskLivenessRegistry` 里根本不存在。照抄这一条等于把要扫的目标全部跳过。这是三处差异里最容易照抄错的一处。** |
| UNKNOWN 是否终态 | `ABORTED` 是终态 | ❌ **UNKNOWN 可回改** | §6.1 |

#### DAO 签名（新增）

```kotlin
@Dao
interface RouteDecisionDao {
    // … 既有 insert / countCloudAssisted / byTurn / byMessage / revoke / retention 全部保留 …

    /** ★ 幂等插入：键冲突时什么都不做（返回 -1），**不覆盖既有行**。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tryInsert(decision: RouteDecisionEntity): Long

    @Query("SELECT * FROM route_decision WHERE idempotency_key = :key")
    suspend fun byIdempotencyKey(key: String): RouteDecisionEntity?

    /** ★ 幂等读取的前提：用了select-or-insert。 */
    @Transaction
    suspend fun insertOrReturn(row: RouteDecisionEntity): RouteDecisionEntity

    /**
     * ★★ 收敛终态。**守卫必须允许从 UNKNOWN 改写**（§6.1），否则迟到的 close() 会被永久拒绝。
     *   同时它就是幂等的：重复 close 第二次影响 0 行。
     */
    @Query(
        """
        UPDATE route_decision
           SET outcome = :outcome, attempt_phase = :phase, delivered_bytes = :deliveredBytes,
               finish_reason_raw = :finishReasonRaw, request_id = :requestId, updated_at = :now
         WHERE id = :id
           AND outcome IN ('PENDING','SENT','UNKNOWN')
        """,
    )
    suspend fun converge(
        id: String, outcome: String, phase: String,
        deliveredBytes: Int, finishReasonRaw: String?, requestId: String?, now: Long,
    ): Int

    /** 流式传输期间刷新心跳，避免活着的长响应被误标 UNKNOWN（§6.3 差异表①）。 */
    @Query("UPDATE route_decision SET updated_at = :now WHERE id = :id AND outcome = 'SENT'")
    suspend fun heartbeat(id: String, now: Long): Int

    /** ★ 冷启 sweep 的扫描面。返回 id 列表，限量。 */
    @Query(
        """
        SELECT id FROM route_decision
         WHERE outcome IN (:outcomes) AND updated_at < :before
         ORDER BY updated_at ASC
         LIMIT :limit
        """,
    )
    suspend fun staleOpenIds(before: Long, outcomes: Collection<String>, limit: Int): List<String>

    /** ★ 明确的 -> 未知惯用法：注意 outcome IN (…) 覆盖了 UNKNOWN 自身 ⇒ 重复 sweep 幂等。 */
    @Query(
        """
        UPDATE route_decision SET outcome = 'UNKNOWN', updated_at = :now
         WHERE id = :id AND outcome IN ('PENDING','SENT','UNKNOWN')
        """,
    )
    suspend fun convergeToUnknown(id: String, now: Long): Int

    /** UI 显示「上次有 N 次出云记录未完成」（docs/08 §7.4）。 */
    @Query("SELECT COUNT(*) FROM route_decision WHERE outcome = 'UNKNOWN'")
    suspend fun countUnknown(): Int

    /** ★ 交叉验证 T13 的查询语句（外部 oracle 也用这一句，避免我们只用 DAO 自证）。 */
    @Query("SELECT COUNT(*) FROM route_decision WHERE outcome IN ('DELIVERED','PARTIAL','FAILED','SENT','UNKNOWN','NOT_SENT','DENIED')")
    suspend fun countConvergedOrSent(): Int
}
```

### 6.4 剩下的真实缺口（照抄并保留架构师的诚实标注）

> **【这条我只能保证到这里】**
>
> ① **存在一个 `INSERT` 失败 / 进程在 `open()` 与发字节之间被杀的窗口。** 但它被设计成 fail-closed（`open()` 返回 `Err` ⇒ 拿不到 permit ⇒ 构造不出 `CloudAssisted` ⇒ 一个字节都没发出）⇒ "上云了却没记账"这个方向在结构上被堵住；剩下的只是"没能力上云"。
> ② **⑤ 的 UPDATE 失败 / 进程在 ④⑤ 之间死**：行停在 PENDING/SENT ⇒ `sweepPending()` 标 UNKNOWN。**这是"缺口可见"而不是"无缺口"。** 我认同这个设计并建议 UI **不要** 隐藏它。
> ③ **`UNKNOWN` 行的真实性无法从内部验证**（我们确实不知道字节到底发出去没有）。**唯一的交叉验证是服务端访问日志** ⇒ 这就是为什么 `request_id` 列必须存在（`docs/08` §9.4-③ 已经点名同一件事）。**这是我给第 ③ 条加的一个可操作建议：把 `request_id` 与"服务端返回的第一个 delta 的时间"一起写入 `updated_at`，让"服务端收到了"与"我们记了"这两件事能对得上。**
> ④ **"INSERT 已提交、但 `Outcome.Ok` 还没返回到调用方"这个瞬间进程被杀**：行已经落库（PENDING），而调用方从未拿到 permit ⇒ **一个字节也没发出**，但库里留了一条无人认领的 PENDING 行。它会被 sweep 标成 UNKNOWN ⇒ **表现为"上次有 1 次出云记录未完成"，而事实是根本没出云。** 这是本设计里唯一一条"**假阳性**"的缺口方向：它让审计偏保守（多报），而不是偏乐观（漏报）。**我认为这个方向是正确的取舍，但必须在 UI 文案上说清"未完成 ≠ 已发出"。**

---

## 7. 每条 schema 决策的证伪方式

### 7.0 oracle 可得性再确认（与 `docs/08` §9.1 的差异，见 §1.2-N3）

| oracle | 今天可得 | 备注 |
|---|---|---|
| **python3 + `sqlite3` 库（3.45.1）** | ✅ **唯一今天就能跑的 SQLite oracle** | 用它跑 §7.1 的 L2 |
| **sqlite3 CLI** | ❌ 本机未装，且 `apt` 源里没有 | 需要在有网/有包的环境装；否则它不是可用 oracle |
| **Room / KSP 编译期校验** | ✅ | 但它**只对 AutoMigration 生效**，手写迁移不受它保护（既有注释已点名） |
| **`adb exec-out run-as …`** | ✅ **已可用**（`docs/07` M5 修好后接了一只2 | LV3/真机层的主要抓手 |
| **`apkanalyzer`** | ✅ Android SDK 自带 | D3 |
| **`mitmproxy`** | 环境未确认 | T4/T9/T13 依赖它 |

> **⚠ SQLite 版本 skew 必须处理：** python oracle 是 **3.45.1**，真机是 bundled（版本未确证，可能更新）。凡是**依赖 SQLite 版本行为**的结论（`PRAGMA foreign_keys` 事务性、`defer_foreign_keys` 可用性、`STRICT` 表、UPSERT）**必须在真机上复跑**，python 的结果只能当 scaffolding。

### 7.1 迁移正确性：三层 oracle（缺一层都不算验证）

#### L1 —— JVM 静态比对（今天就有，毫秒级）

`./gradlew :data:test`：`AkeMigrationSchemaTest`（§3.5 列了 6 条改动）。它盯的是 **DDL 与 Entity 逐列一致**，纯静态，不含 SQLite 执行。

#### L2 —— ★ 在你提到的那个 oracle 上：用 python 把迁移**真跑一遍**

`sqlite3` CLI 不可用，替代方案是 python 脚本（以下脚本今天就能跑）：

```python
# scripts/db/oracle/check_v4.py  （建议放这里；本轮不落地，先贴正文）
import sqlite3, json, sys, pathlib

SCHEMA = pathlib.Path("HarnessAgent/data/schemas/com.ake.data.persist.db.AkeDatabase")
STATEMENTS = json.load(open(sys.argv[1]))        # ← 由 Gradle 任务把 CloudMigration3To4.STATEMENTS 导出成 json

def table_info(c, t):
    rows = c.execute(f"PRAGMA table_info(`{t}`)").fetchall()
    return {r[1]: dict(type=r[2], notnull=bool(r[3]), dflt=r[4], pk=bool(r[5])) for r in rows}

def check():
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")

    # ① 造一个 v3 库：直接执行 3.json 里的 createSql（**坐标来源与真机同一份**，不自造 DDL）
    v3 = json.load(open(SCHEMA / "3.json"))
    for e in v3["database"]["entities"]:
        con.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for i in (e.get("indices") or []):
            con.execute(i["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    con.execute("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity TEXT)")

    # ② 塞入"老设备上的真实数据"的代表：每张会被迁移碰到的表至少一行
    con.execute("INSERT INTO chat_session (id, agent_id, title, created_at, updated_at, last_message_at, context_profile) "
                "VALUES ('s1','a1','t',1,1,1,'DEFAULT')")
    con.execute("INSERT INTO agent_profile (id, agent_id, name, avatar_kind, description, persona_charter_preview, "
                "model_id, origin, trust_tier, scan_verdict, scan_hits_json, scanner_version, always_confirm_skills, "
                "created_at, updated_at) VALUES ('p1','a1','n','IDENTICON','d','prev','m','LOCAL','T0','OK','[]',1,0,1,1)")
    con.execute("INSERT INTO chat_message (id, session_id, seq, turn_id, role, kind, status, content_preview) "
                "VALUES ('m1','s1',1,'t1','USER','TEXT','COMPLETE','prev')")
    before_rows = {t: con.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0]
                   for t in [e["tableName"] for e in v3["database"]["entities"]]}
    con.commit()

    # ③ 跑迁移 —— ★必须在事务内跑（模拟 Room）
    con.execute("BEGIN")
    for sql in STATEMENTS:
        con.execute(sql)          # SQL syntax error ⇒ 这里就红，不难看的启动才错
    con.execute("COMMIT")

    # ④ 断言：行数一个不少（§3.4 论证②）
    after_rows = {t: con.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0] for t in before_rows}
    assert before_rows == after_rows, f"迁移丢了/多产了行: {before_rows} vs {after_rows}"

    # ⑤ 断言：完整性（§3.4 论证③）
    assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    violations = con.execute("PRAGMA foreign_key_check").fetchall()
    assert violations == [], f"FK 违反: {violations}"

    # ⑥ 断言：产出的列形状 == 4.json（这一条把 L1 的静态比对在真 SQLite 上再验一次）
    v4 = json.load(open(SCHEMA / "4.json"))
    for e in v4["database"]["entities"]:
        got = table_info(con, e["tableName"])
        want = {f["columnName"] for f in e["fields"]}
        assert set(got) == want, f"{e['tableName']} 列集不一致: 多={set(got)-want} 少={want-set(got)}"

    # ⑦ 断言：幂等 —— ★ 再跑一遍 STATEMENTS，不得抛（对应 §3.3 的 pragma_table_info 守卫）
    con.execute("BEGIN")
    try:
        for sql in STATEMENTS:
            con.execute(sql)
        con.execute("COMMIT")
        IDEMPOTENT = True
    except Exception as ex:
        IDEMPOTENT = False
    print(json.dumps({"idempotent_rerun": IDEMPOTENT,
                      "sqlite_version": sqlite3.sqlite_version}))
    assert IDEMPOTENT, "迁移不幂等：重跑抛异常（ALTER ADD COLUMN 不能 IF NOT EXISTS ⇒ 必须 tra `pragma_table_info` guard）"

if __name__ == "__main__":
    check(); print("L2 ORACLE: OK")
```

**★ 这一层的价值超过 L1**：它真的建了一张 SQLite 表、真的跑了 SQL、真的数了行数、真的验证了幂等。它替代不了真机（用的是系统 SQLite 而非 bundled、没有 Room 参与），但**它把"迁移当天不报错、等到第一次查询才 `no such column`"这种失败从"上线后才发现"提前到"改代码的同一分钟"。**

> **【这条我只能保证到这里】** L2 的 oracle 是 SQLite **3.45.1**，真机是 bundled 的 SQLite（版本未确证，见 U2）。两者在 `ALTER TABLE` 上的差异自 3.x 早期起就基本冻结，而 v4 用的全是二十年前就稳定的语法（`ADD COLUMN` / `CREATE INDEX` / `REFERENCES`），**风险极低 —— 但这是推理，不是验证，所以 L3 必须跑。**

#### L3 —— 真机（← 唯一不可省略的一层）

```
前置：同一个 APK 的两个版本 —— v3 包（用现有 main 分支构建）与 v4 包（本设计落地后）

 1) adb install old-v3.apk        # 冷启
 2) 造点数据：建一个 Agent + 一个会话 + 发一条消息（让 chat_session / chat_message / agent_profile 非空）
 3) 拉「迁移前」快照（★ 用 docs/07 修好的那条：exec-out 直写本地文件，不走 /sdcard 中转）
       adb exec-out run-as com.ake.harnessagent cat databases/ake_agent.db > before.db
    python3 check_counts.py before.db      # 逐表 COUNT + user_version + integrity_check
 4) adb install -r new-v4.apk              # 保留数据升级
 5) 冷启（★ 迁移在首次开库时同步执行）
 6) 拉「迁移后」快照
       adb exec-out run-as com.ake.harnessagent cat databases/ake_agent.db > after.db
    python3 check_counts.py after.db
 7) ★ 四项硬断言（外部 oracle：SQLite 本体 + Room 本体）
       · user_version                       → 4
       · PRAGMA integrity_check             → ok
       · PRAGMA foreign_key_check           → 0 行
       · 逐表 COUNT(*) 与 before.db **逐一相等**
         （特别是 route_decision：迁移前 0、迁移后仍应为 0 —— 它同时验证了 S9）
 8) SELECT sqlite_version()  ← 把 U2 的版本 skew 坐实或消除（对比 L2 的 3.45.1）
       adb exec-out run-as com.ake.harnessagent cat databases/ake_agent.db > probe.db
       python3 -c "import sqlite3;c=sqlite3.connect('probe.db');print(c.execute('select sqlite_version()').fetchone())"
```

### 7.2 每条 schema 决策 → 最小实验 → oracle（大表）

| # | schema 决策 | 最小实验 | 期望 | 外部 oracle | 强度 |
|---|---|---|---|---|---|
| **S1** | v4 全 Additive（§3.1） | L2 脚本的 §⑦；以及静态断言 `migrationIsAdditive`（§3.5-5） | 无 DROP/RENAME/UPDATE/DELETE | python SQLite + JUnit | ★★★ |
| **S2** | 迁移幂等（`pragma_table_info` 守卫） | L2 脚本重跑 `STATEMENTS` 第二次 | 不抛 | python SQLite | ★★★ |
| **S3** | DDL 与 Entity 逐列一致 | L1（`AkeMigrationSchemaTest` 改后）+ L2 ⑥ | 两侧一致 | JVM + python SQLite | ★★★ |
| **S4** | **升级不丢数据** | L3 的逐表 COUNT 比对 | 全部表行数不减少；`integrity_check=ok` | 真机 SQLite（bundled） + `run-as` | ★★★ |
| **S5** | 迁移失败不留下半套 schema | 故意在 `STATEMENTS` 中间插一条非法 SQL，跑 L2 | 整个事务回滚（`COMMIT` 抛，`user_version` 不变） | python SQLite 事务 | ★★★ |
| **S6** | `idx_route_idem` 能挡住重复 permit | 同一个 `(turnId, attemptNo)` 连续调 `open()` 两次 | 第二次返回**同一个 `auditRowId`**，表行数 = 1 | JVM 单测（假 DAO 不够 —— 需要 SQLite：`?` no, 用 L2 脚本跑) + 真机 sqlite3 count | ★★★ |
| **S7** | NULL 不影响 UNIQUE（本地路由可多行） | 插入 3 条 `idempotency_key=NULL` 的行 | 成功，不冲突 | python SQLite | ★★★ |
| **S8** | `EgressVerdict.normalise()` 覆盖历史字面量 | 单测：`normalise("PASS")==SCREENED`、`normalise("BLOCKED")==GATE_DENIED`、`normalise("XX")==NULL` | 三条都过，且未知值不抛 | kotlin.test | ★★★ |
| **S9** | **`route_decision` 今日恒为空**（§1.2-N1）★ | 真机跑现有 v3 包（有 UI 操作后）：`SELECT COUNT(*) FROM route_decision` | **0** | 真机 + `run-as` 拉库 | ★★★（**若非 0 ⇒ §5.3 全部重做**） |
| **S10** | 凭证在 L3 全 additive 后仍kingdom | 在 v4 包装一个凭证 → 再升级到未来 v5 包 → 凭证仍存在且可用 | 通过 | 真机 | ★★★ |
| **S11** | 加 REFERENCES 的 ADD COLUMN 合法 | L2 ③ 的两条带 FK 的 ALTER | 不抛（`And default NULL` 规则） | python SQLite | ★★★ |
| **S12** | **FK 意图被 Room 认账** | 改完 Entity 后编译 + L3 开库 | 无 `Migration didn't properly handle` ISE | Room 本体（开库时校验） | ★★★ |
| **S13** | `cloud_credential` 不进 adb 备份/迁移 | `apkanalyzer manifest print <apk> \| grep allowBackup`；人工走一次 Settings→换机产线 | `allowBackup="false"` + dataExtractionRules 两面排除 | Android SDK 自带 | ★★☆ |
| **S14** | exportSchema JSON 不含任何凭证数据 | 真机配一个凭证（明文含固定 `canary-xyz`）→ 跑 exportSchema → `grep -r canary-xyz data/schemas/*.json` | 0 命中 | grep + git | ★★★ |
| **S15** | 用户单删凭证不破坏审计 | 删凭证 → `SELECT COUNT(*) FROM route_decision WHERE credential_id = :id`；且行仍在 | 行仍在、`credential_id` 为 NULL（SET NULL）或指向墓碑 | python sqlite3 | ★★★ |
| **S16** | 「一键清除」不残留密文 | wipe 后 `SELECT COUNT(*) FROM cloud_credential` 应为 0（库已删）；且 sqlite payload | 库文件不在 `databases/`；临时拉一次 files 检查无 `credentials/` | `adb shell run-as … ls -l databases files` | ★★★ |
| **S17** | debug 构建没有明文降级（问 4） | D2 Canary：`base_url_enc` 原始 blob 里不得出现用户输入的那个 URL 子串 | 0 命中 | JVM 单测 + 真机 adb 拉库后 python | ★★★ |
| **S18** | FieldCipher 实现份数 = 1（含 debug source set） | 扫全仓 `: FieldCipher` / `: FieldCipher(` | 恰好 1 处 | Gradle/JUnit | ★★★ |
| **S19** | **UNKNOWN 可回改**（§6.1） | sweep 把 PENDING→UNKNOWN；随后调用 `close(DELIVERED)` | UPDATE 影响 1 行，`outcome='DELIVERED'` | python SQLite | ★★★ |
| **S20** | sweep 幂等 / 不与 close() 打架 | sweep 跑两遍；以及"close 与 sweep 并发" | 第二次 `convergedUnknown` = 0 或 `raced` 计数 | python SQLite | ★★★ |
| **S21** | 审计行数 ≥ 服务端收到的请求数（`docs/08` T13） | mock server 记 N；`SELECT COUNT(*) … outcome IN (…)` = M | **M ≥ N** | mock server 访问日志 + python sqlite3 | ★★★ |
| **S22** | `PRAGMA foreign_keys` 的事务性 no-op（§1.3） | 本文 §1.3 的实验脚本，在**真机 bundled SQLite** 上复跑 | 同结果 | 真机（这是唯一能把版本 skew 坐实的办法） | ★★★ |
| **S23** | `defer_foreign_keys` 在真机 bundled 上可用 | 真机跑 §1.3 实验 C 段 | `defer` 在事务内切得动 | 真机 | ★★★ |

### 7.3 我认为最可能被后面真机验证打脸的三条（按概率排序）

> 这是你要的"哪一 Schema 决策最可能被打脸"。我按**我自己的把握程度**排，不按后果排。

**候选 1（最高概率）：S9 —— 「`route_decision` 今日恒为空」。**
理由：它是我全部论述里**唯一一条由推理得出、而非直接观测得出**的前提。它的证明材料修缮，但我没有办法在没有真机的宿舍里亲眼看到那个 `COUNT(*) = 0`。
**打脸后果：** §5.3 的"不重写存量"要从"零成本的干净方案"降级成"需要一次真实数据诊断 + 可能一次重写"。而§5.3 明确写了：**若表非空，说明存在一个我今天没看见的写入路径 —— 先诊断，再清洗。** ⇒ 我把它排第一，是因为它是唯一一条"如果被推翻，会牵动一段设计改写"的决策。

**候选 2：`cloud_credential` 上「零二级索引」（§2.1）。**
理由：它建立在一个**外部不可控的量**上 —— 「用户手工录入凭证的个数是小数量级」。如果产品侧将来加了"批量导入"或"从云端同步凭证"，这个假设崩塌，而此时建索引已经晚了（虽然补一条索引是很便宜的 migration，所以代价低）。**它是"便宜地翻案"的那类错误，所以排第二。**

**候选 3：`route_decision` 不建 `turn_id` 索引（§2.2）。**
理由：它依赖 `MAX_ROWS = 5000` 这条 retention 约束**被持续执行**。而 retention 能不能跑起来又依赖 `RetentionJob` 的 WorkManager 周期真被调度 —— 而 WorkManager 的周期会被系统按 idle / 低电量条件推迟（`PersistenceModule` 自己的注释就写了这一点）。**若 retention 长期不生效，表会突破 5000 行上限，`byTurn()` 的全表扫描就从"微秒级"变成真的可感知。** 这条依赖的是"一个后台调度会不会真跑"这种运行期前提，比前两条更容易在真机上与我的推理不一致。

### 7.4 明确标注：**这些我证不了**

| # | 命题 | 为什么证不了 | 缓解 |
|---|---|---|---|
| **U1** | 「sqlite3 CLI 不可用」这件事本身 | 它是环境事实，不是我的设计；且我装不上（没包） | L2 用 python 代替；L3 仍要真机 |
| **U2** | bundled SQLite 的确切版本号 | `strings` 提取不到唯一值 → `SELECT sqlite_version()` 才是唯一途径 | L3 步骤 (8) 专门取它 |
| **U3** | Room 2.8.5 是否把 `Migration.migrate()` 包在事务里 | 我反编译 `room-runtime` 只追到 `SupportOpenHelperCallback.onUpgrade → RoomConnectionManager.onMigrate`，事务包装在更下层的 open helper 里，静态方法分析无法确证 | ★ **不影响本设计**：`defer_foreign_keys` 在"包了"与"没包"两种情况下都正确（§1.3-C）。但若有人要修 2→3 的 no-op，这是**必须先验的一条** —— 实证办法：在 `migrate()` 里执行 `execSQL("BEGIN")`；若抛 `cannot start a transaction within a transaction` ⇒ 说明外层已经包了。 |
| **U4** | 「是否存在一个我今天没看见的 `route_decision` 写入路径」 | 穷尽性无法自证 | S9 的 count + 扫全仓 `RouteDecisionSink.record(` 调用点断言恰好 1 |
| **U5** | Root / Frida 设备上的行为 | 不在威胁模型内（`docs/08` §9.3 已声明同款） | 无。必须在对外文案说清 |
| **U6** | 明文在进程内存里（的大驻留窗口） | `useAsBearer` 只缩短窗口，不消除 | `docs/08` §9.3-① 已诚实标注，我不重复的主张、也不假装解决了 |

---

## 8. 交接清单（按归属分派，供主理人派活）

| # | 给谁 | 事项 | 为什么必须他做 |
|---|---|---|---|
| H1 | **主理人岑执衡** | 裁决 A5 翻案（§4.1）、`route_policy` 不建（§2.4）、`cloud_provider` 推迟（§2.3） | 三者都改变 `docs/08` 的结论 |
| H2 | **同事（`:core:ports`）** | 加 `EgressVerdict` / `AuditOutcome` 两个常量对象（§5.2 全文已给） | 不在我地盘 |
| H3 | **同事（`:core:ports`）** | `EndpointConfig.baseUrl` 的矛盾解法（§4.4，推荐方案 ②） | 不在我地盘；且影响 UI |
| H4 | **同事（`:chat`）** | 删 `ChatEgress.VERDICT_SCREENED` / `VERDICT_GATE_DENIED`，改引用 `EgressVerdict`（§5.2 删除清单） | 不在我地盘 |
| H5 | **邝随行 / CI** | D1–D4 四条结构断言（§4.2 问 4 的答案）+ §5.4 的常量份数断言 D5 | 需要碰 `:app`/CI 文件 |
| H6 | **下一轮 BACKLOG**（不是本轮） | 修 `ChatMigration2To3` 的 `PRAGMA foreign_keys=OFF` → `defer_foreign_keys`（§1.3） | 本轮不涉及重建；且改它需要单独一轮 + 单独验 U3 |
| H7 | **邝随行** | 迁移失败时保留原始 `Migration didn't properly handle…` message 不吞（§3.4 末） | 碰 `:app` 的开库路径 |
| H8 | **关千锤（release-gate）** | 把 S9 / S22 / S23 / L3 加进 device-verify 的 M 序列；**且照抄 `docs/07` 的句式写「设计意图，不是已验证事实** | 验收口径一致（7.3 候选 1 是首号风险） |

---

## 9. 本文裁决汇总（一页看全）

| 编号 | 主张 | 状态 |
|---|---|---|
| A5 | 凭证密文**进 Room**（推翻 `docs/08` §6.6） | **已裁决，待主理人确认** |
| — | `route_policy` **不建** | **已裁决，待主理人确认** |
| — | `cloud_provider` **推迟**（形状已给） | **已裁决，触发条件已写死** |
| — | `egressGateVerdict` **保留字面量 + 读时规范化，不重写** | **已裁决**，依赖 S9 不打脸 |
| — | 审计 `UNKNOWN` **不是终态** | **已裁决**（这是我给架构师 §7.2 打的一个补丁） |
| — | MVP **复用** Keystore 别名（翻案条件已写死） | **已裁决** |
| ⚠ | `ChatMigration2To3` 的 `PRAGMA foreign_keys=OFF` 是事务内 no-op | **已核实：今天无害，下一轮会炸** → H6 |

---

*[财报研究员分析信号] 本记录不含金融内容，此标记按团队交付惯例保留 —— 这里要平的是"字节的账"与"密钥的账"。*
