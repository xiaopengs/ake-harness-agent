package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 配置类两张表：`mcp_server` / `app_settings`（2026-10，霍承钧）。
 *
 * ## ★★ 为什么落 Room 而不是 Proto DataStore（与 `tool_registry` 同一条裁决线）
 *
 * `data/build.gradle.kts` 头部留着一条「预留坐标」注释，说
 * “某个 Port 需要一个 `:data` 里的 Store 实现时（`SettingsStorePort` / `McpServerStorePort`
 * 就是候选），由 `:data` 的实现方一次性补齐插件 + proto + 实现三件套”。
 * **本轮就是那两个 Port 落地的时候，而结论是：不补那三件套，两张表进 Room。**
 * 三条理由，按权重排：
 *
 *  1. **清除范围只有一处判据（最硬的一条）。**
 *     `core/ports/Privacy.kt` 把「一键清除」的顺序写死成 ①删密钥 → ②停在途写入 →
 *     ③删 Room 库文件 → ④删外置轨迹 → ⑤删 DataStore / 配置 → ⑥删 WebView。
 *     注意 ② 与 ③ 是**连着的**：只有落在 Room 里的配置，才会被「停在途写入」这一步护住。
 *     DataStore 是独立文件 + 独立写协程，它不在 ② 的管辖范围里 ——
 *     于是「清完立刻又被写回来」这个已修复过的缺陷会在配置这一路重新出现一次。
 *     更糟的是 ⑤ `deleteRecursively()` 与 DataStore 自己的写协程并发时，
 *     存在「文件被重建」的已知形态 —— 那是 `WipeResult.failures` 看不见的静默失败。
 *     两张表进 Room 之后，配置与任务数据共用**同一条**落盘/停止/删除路径，
 *     「一键清除到底清没清干净」这个问题只有一处答案。
 *
 *  2. **按条撤销需要行级 UPDATE，不是整体重写文件。**
 *     `ToolPermissionStorePort` 的裁决（数据库 F7）已经证明过一次：
 *     Proto DataStore 是整体重写文件，200 项里改 1 项要重写整个文件。
 *     `McpServerStorePort` 同样是列表语义（`upsert` 单条 / `remove` 单条），
 *     落 Room 之后 `remove` 是一次带主键的 DELETE，与授权表同构。
 *
 *  3. **不引入新的三方依赖与构建插件。**
 *     `kotlinx-serialization` 不在 `:data` 的依赖里；`headers` / `allowHosts`
 *     这两个字段的序列化由一个 60 行的 internal `FlatJson` 承担（见 `store/FlatJson.kt`）。
 *     它比 proto 弱，但它能**在纯 JVM 单测里被覆盖** ——
 *     而 proto 生成的类在 JVM 单测里要么跑不动、要么得再拉一套测试依赖。
 *     在一个「七个模块零测试」的项目里，可测性的权重高于表达力。
 *
 * ## 与 `:ui` 那份过渡实现的关系
 *
 * `UiLocalSettings` 把设置写在 `filesDir/settings/ui_local.json`、
 * `McpServerStore` 把清单写在 `filesDir/mcp/servers.json`。
 * 本轮落地后那两条 `@Binds` 必须删掉（duplicate binding），
 * 但 `WipeEverythingUseCase` **仍会顺手删这两个目录** ——
 * 见该类 ⑤b 段的注释：两条边切换有窗口期，清除范围必须覆盖窗口期内的两种落点。
 */

/**
 * MCP 服务器清单（`McpServerStorePort` 的落点）。
 *
 * 与 `core:ports/McpServers.kt` 的 `McpServerConfig` 逐字段对应，
 * 唯一被折叠的是 `headers: Map<String,String>` → `headersJson`（见 [FlatJson] 的取舍）。
 *
 * ★ 行数上界很小（用户在设置页手录，MVP 不做自动发现），所以不建外键、不建复合索引，
 * 只留一个 `enabled` 索引给「工具页只读启用的那些」这条查询路径。
 */
@Entity(
    tableName = "mcp_server",
    indices = [Index(name = "idx_mcp_enabled", value = ["enabled"])],
)
data class McpServerEntity(
    /** `McpServerConfig.id`。改名会让已授权记录失效，所以它同时是工具 id 的命名空间前缀。 */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "url") val url: String,
    /** `Map<String,String>` 的 JSON 折叠。明文落盘 —— 与「不要放长期凭据」的约束并存。 */
    @ColumnInfo(name = "headers_json") val headersJson: String,
    @ColumnInfo(name = "enabled") val enabled: Int,
    @ColumnInfo(name = "transport") val transport: String,
    @ColumnInfo(name = "call_timeout_ms") val callTimeoutMs: Long,
    @ColumnInfo(name = "connect_timeout_ms") val connectTimeoutMs: Long,
    @ColumnInfo(name = "max_reconnect_attempts") val maxReconnectAttempts: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        /** `headers` 为空时的规范写法。刻意不是 `""` —— 空字符串在排查时与“解析失败”分不开。 */
        const val EMPTY_JSON: String = "{}"
    }
}

/**
 * 本地设置（`SettingsStorePort` 的落点）。**单行表，主键恒为 0。**
 *
 * ## 为什么是单行表而不是键值表
 *
 * `LocalSettings` 只有三个字段且**总是被整体读写**（`update(v)` 传的是完整对象），
 * 拆成 KV 表等于用三行去表达一行能表达的东西，还会引入「读的时候只写了一半」的中间态。
 * 单行 + 整体 REPLACE 是原子的，语义与 `update()` 的签名严格对齐。
 *
 * ⚠ **改主键语义会让迁移变成数据迁移**：新增字段走 `ALTER TABLE ... DEFAULT`，
 * 但「从单行变成多行」是一次结构性重写，届时必须写真正的 2→3 迁移而不是加列。
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    /** 恒为 [SINGLE_ROW_ID]。Room 要求主键非空，这里用占位主键而不是 UUID。 */
    @PrimaryKey @ColumnInfo(name = "id") val id: Int,
    /** `List<String>` 的 JSON 折叠。空集 = 不限（由跨域名确认兜底）。 */
    @ColumnInfo(name = "allow_hosts_json") val allowHostsJson: String,
    /** `null` = 跟随档位默认值。**可空列，不要用 0 表达「未设置」** —— 0 是合法的最大步数。 */
    @ColumnInfo(name = "max_steps") val maxSteps: Int?,
    @ColumnInfo(name = "show_dom_payload") val showDomPayload: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SINGLE_ROW_ID: Int = 0
        const val EMPTY_JSON: String = "[]"
    }
}
