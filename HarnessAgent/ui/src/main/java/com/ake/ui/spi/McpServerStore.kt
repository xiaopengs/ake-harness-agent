package com.ake.ui.spi

import android.content.Context
import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.McpServerStorePort
import com.ake.core.ports.TransportKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 服务器清单的本地暂存。
 *
 * ## ★ 这是过渡实现，应在 `:data` 落地后删除
 *
 * 服务器清单（含静态 Header）**本该**由 `:data` 落在 Proto DataStore —— 数据库文档里
 * `WipeEverythingUseCase` 已经把 `mcp_servers` 列为要清的文件之一，说明设计上有它的位置。
 * 但目前 `core:ports` 里没有任何一个 Port 暴露它，UI 拿不到。
 *
 * 于是这里做一个「UI 自己暂存」的兜底，代价与边界都写清楚：
 *  - 用 `org.json` 手写，不引入新的依赖坐标（serialization 要邝随行协调）；
 *  - 写在 `filesDir/mcp/`，与 `AndroidPrivacyPort` 的清除范围对齐，不会留下孤儿；
 *  - **Header 是明文落盘的**，与 DataStore 版本同样受「不要放长期凭据」的约束，
 *    UI 上对此有明示提示。
 *
 * 等 `:data` 提供真正的 `McpServerStorePort` 实现，把本类换成那个即可 ——
 * 调用方（[com.ake.ui.feature.mcp.McpViewModel]）只认 Port 契约，不认本类。
 *
 * ## ★ 2026-10 跨层依赖整改：本类现在**实现** `core:ports` 的 [McpServerStorePort]
 *
 * 它本来就是这份清单的唯一实现，只是此前没对上契约的名字，于是调用方
 * 只能按具体类型 `McpServerStore` 注入。现在它显式 `implements` Port，
 * 并在 `UiRuntimeModule` 里 `@Binds` 到 `McpServerStorePort` ——
 * 这一步之后 `:ui` 源码里再没有 `:mcp` 的符号，`:data` 的实现将来是纯替换。
 *
 * ⚠ **给邝随行的交接事项**：`:data` 一旦落地它自己的 `McpServerStorePort` 实现，
 *   请删掉 `UiRuntimeModule.bindMcpServerStore` 那一条 `@Binds`，
 *   否则同一个 Port 有两个绑定，Hilt 图会直接报 duplicate binding。
 */
@Singleton
public class McpServerStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : McpServerStorePort {

    private val mutex = Mutex()
    private val file: File get() = File(File(ctx.filesDir, "mcp"), "servers.json")

    override suspend fun load(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = file
            if (!f.exists()) return@withContext emptyList()
            runCatching {
                val arr = JSONArray(f.readText())
                val out = ArrayList<McpServerConfig>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val headersObj = o.optJSONObject("headers")
                    val headers = LinkedHashMap<String, String>()
                    if (headersObj != null) {
                        for (k in headersObj.keys()) headers[k] = headersObj.optString(k)
                    }
                    out += McpServerConfig(
                        id = o.optString("id"),
                        label = o.optString("label"),
                        url = o.optString("url"),
                        headers = headers,
                        enabled = o.optBoolean("enabled", true),
                        transport = runCatching { TransportKind.valueOf(o.optString("transport", "STREAMABLE_HTTP")) }
                            .getOrDefault(TransportKind.STREAMABLE_HTTP),
                        callTimeoutMs = o.optLong("callTimeoutMs", 8_000L),
                        connectTimeoutMs = o.optLong("connectTimeoutMs", 6_000L),
                        maxReconnectAttempts = o.optInt("maxReconnectAttempts", 3),
                    )
                }
                out.filter { it.id.isNotBlank() && it.url.startsWith("http") }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun save(list: List<McpServerConfig>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                val arr = JSONArray()
                for (c in list) {
                    val o = JSONObject()
                    o.put("id", c.id)
                    o.put("label", c.label)
                    o.put("url", c.url)
                    o.put("enabled", c.enabled)
                    o.put("transport", c.transport.name)
                    o.put("callTimeoutMs", c.callTimeoutMs)
                    o.put("connectTimeoutMs", c.connectTimeoutMs)
                    o.put("maxReconnectAttempts", c.maxReconnectAttempts)
                    val h = JSONObject()
                    for ((k, v) in c.headers) h.put(k, v)
                    o.put("headers", h)
                    arr.put(o)
                }
                file.writeText(arr.toString())
            }
        }
    }

    override suspend fun upsert(config: McpServerConfig) {
        val cur = load().toMutableList()
        val idx = cur.indexOfFirst { it.id == config.id }
        if (idx >= 0) cur[idx] = config else cur += config
        save(cur)
    }

    override suspend fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    /** 清空全部清单。一键清除走的就是这里。 */
    override suspend fun clearAll() {
        save(emptyList())
    }
}
