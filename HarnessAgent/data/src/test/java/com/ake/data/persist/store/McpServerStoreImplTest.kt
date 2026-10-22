package com.ake.data.persist.store

import com.ake.core.common.Clock
import com.ake.core.common.ManualClock
import com.ake.core.ports.McpServerConfig
import com.ake.core.ports.TransportKind
import com.ake.data.persist.db.entity.McpServerEntity
import com.ake.data.persist.fake.FakeAkeDatabase
import com.ake.data.persist.fake.FakeConfigDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `McpServerStoreImpl` 的单测。
 *
 * ## 要守住的一条裁决（数据库 F7 / §8.5）
 *
 * > **按条撤销 = 行级 UPDATE/DELETE，不是整体重写文件。**
 *
 * `McpServerStorePort` 是列表语义（`upsert` 单条 / `remove` 单条），它取代的是 `:ui` 那份
 * 「读全量 JSON → 过滤 → 写全量」的过渡实现 —— 后者两个并发请求会互相覆盖。
 * 所以这里的断言盯着两件事：**Store 传给 DAO 的是不是「一行」**，以及
 * **这一行的写入有没有波及兄弟行**。
 *
 * ## 为什么用内存 DAO 而不是真 Room
 *
 * `:data` 是 `androidLibrary`，真库要 `Context`，JVM 单测起不来。
 * 于是按 `PersistenceFakes.kt` 同一条纪律：把 DAO 的守卫语义在内存里重实现一遍。
 * 验证的是**调用方与守卫之间的契约**，不是 SQL 字符串本身（那是真机/instrumented 的事）。
 *
 * ## ⚠ 本轮**未**覆盖的路径
 *
 * `save()` / `update()` / `clearAll()` 的外层事务边界
 * （`db.withTransaction { ... }`）需要真正打开的 Room 连接 ——
 * 见 `FakeAkeDatabase` 的注释与回传里的「剩余风险」。
 */
@RunWith(JUnit4::class)
class McpServerStoreImplTest {

    private val dao = FakeConfigDao()
    private val db = FakeAkeDatabase(dao)
    private val clock = ManualClock(1_000L)
    private val store = McpServerStoreImpl(db, clock)

    // ──────────────────────────────────────────── 容错装载

    @Test
    fun `库里没有行时 load 返回空表`(): Unit = runBlocking {
        assertThat(store.load()).isEmpty()
    }

    @Test
    fun `脏行被跳过而不是让整个工具页打不开`(): Unit = runBlocking {
        // ★ `McpServerConfig.init` 里 require 了 url 必须以 http(s) 开头。
        //   装载时若让它抛出去，后果是「一条脏数据让设置页白屏」—— 用户连删掉它的机会都没有。
        dao.rows["bad"] = mcpEntity(id = "bad", url = "ftp://nope")
        dao.rows["good"] = mcpEntity(id = "good")

        val loaded = store.load()

        assertThat(loaded.map { it.id }).containsExactly("good")
    }

    @Test
    fun `空 id 的行被跳过`(): Unit = runBlocking {
        dao.rows[""] = mcpEntity(id = "")
        dao.rows["ok"] = mcpEntity(id = "ok")

        assertThat(store.load().map { it.id }).containsExactly("ok")
    }

    @Test
    fun `非法 transport 字符串降级为默认形态`(): Unit = runBlocking {
        dao.rows["x"] = mcpEntity(id = "x", transport = "NOT_A_TRANSPORT")

        assertThat(store.load().single().transport).isEqualTo(TransportKind.STREAMABLE_HTTP)
    }

    @Test
    fun `坏 headersJson 降级为空 map 而不是抛异常`(): Unit = runBlocking {
        dao.rows["x"] = mcpEntity(id = "x", headersJson = "{{{ not json")

        assertThat(store.load().single().headers).isEmpty()
    }

    @Test
    fun `enabled 非零即启用，0 为禁用`(): Unit = runBlocking {
        dao.rows["a"] = mcpEntity(id = "a", enabled = 2)
        dao.rows["b"] = mcpEntity(id = "b", enabled = 0)

        val byId = store.load().associateBy { it.id }

        assertThat(byId["a"]!!.enabled).isTrue()
        assertThat(byId["b"]!!.enabled).isFalse()
    }

    @Test
    fun `load 按 updated_at 升序返回，UI 列表顺序稳定`(): Unit = runBlocking {
        dao.rows["late"] = mcpEntity(id = "late", updatedAt = 300L)
        dao.rows["early"] = mcpEntity(id = "early", updatedAt = 100L)

        assertThat(store.load().map { it.id }).containsExactly("early", "late")
    }

    @Test
    fun `DAO 抛异常时 load 返回空表而不是崩溃`(): Unit = runBlocking {
        dao.rows["a"] = mcpEntity(id = "a")
        dao.writesThrow = true // mcpServers() 也走同一开关，模拟一次 DB 故障

        assertThat(store.load()).isEmpty()

        dao.writesThrow = false
        assertThat(store.load()).hasSize(1)
    }

    // ──────────────────────────────────────────── 行级写入（★ 核心语义）

    @Test
    fun `upsert 只提交一行，兄弟行原样保留`(): Unit = runBlocking {
        dao.rows["keep"] = mcpEntity(id = "keep", url = "http://127.0.0.1:1/mcp")

        store.upsert(McpServerConfig(id = "new", label = "N", url = "http://127.0.0.1:2/mcp"))

        assertThat(dao.rows.keys).containsExactly("keep", "new")
        // ★ 兄弟行必须逐字段未被触碰 —— 「整体重写文件」的写法会把它冲掉
        assertThat(dao.rows["keep"]!!.url).isEqualTo("http://127.0.0.1:1/mcp")
    }

    @Test
    fun `upsert 同 id 覆盖（REPLACE 语义）`(): Unit = runBlocking {
        dao.rows["a"] = mcpEntity(id = "a", url = "http://127.0.0.1:1/mcp", label = "old")

        store.upsert(McpServerConfig(id = "a", label = "new", url = "http://127.0.0.1:9/mcp"))

        assertThat(dao.rows.keys).containsExactly("a")
        val row = dao.rows["a"]!!
        assertThat(row.label).isEqualTo("new")
        assertThat(row.url).isEqualTo("http://127.0.0.1:9/mcp")
    }

    @Test
    fun `upsert 用 clock 的当前时间写 updated_at`(): Unit = runBlocking {
        clock.setTo(4_321L)
        store.upsert(McpServerConfig(id = "a", label = "A", url = "http://127.0.0.1:1/mcp"))

        assertThat(dao.rows["a"]!!.updatedAt).isEqualTo(4_321L)
    }

    // ──────────────────────────────────────────── 行级删除（★ 核心语义）

    @Test
    fun `remove 只删命中的那一行`(): Unit = runBlocking {
        dao.rows["a"] = mcpEntity(id = "a")
        dao.rows["b"] = mcpEntity(id = "b")

        store.remove("a")

        assertThat(dao.rows.keys).containsExactly("b")
    }

    @Test
    fun `remove 幂等：删不存在的 id 不抛异常`(): Unit = runBlocking {
        dao.rows["a"] = mcpEntity(id = "a")

        store.remove("ghost")
        store.remove("ghost")

        assertThat(dao.rows.keys).containsExactly("a")
    }

    @Test
    fun `clearAll 清空全部行`(): Unit = runBlocking {
        dao.rows["a"] = mcpEntity(id = "a")
        dao.rows["b"] = mcpEntity(id = "b")

        store.clearAll()

        assertThat(dao.rows).isEmpty()
    }

    // ──────────────────────────────────────────── 映射层

    @Test
    fun `toEntity 折叠 headers 与枚举，并用显式传入的 updatedAt`(): Unit = runBlocking {
        // ★ updatedAt 由调用方传入而不是内部取 clock：批量写入时它要**单调递增**，
        //   否则所有行 updated_at 相同 ⇒ ORDER BY updated_at 退化 ⇒ UI 列表每次装载都跳。
        val cfg = McpServerConfig(
            id = "a",
            label = "A",
            url = "http://127.0.0.1:1/mcp",
            headers = mapOf("Authorization" to "Bearer x"),
            enabled = false,
            transport = TransportKind.SSE,
            maxReconnectAttempts = 7,
        )

        val row = store.toEntity(cfg, 9_999L)

        assertThat(row.id).isEqualTo("a")
        assertThat(row.headersJson).isEqualTo("""{"Authorization":"Bearer x"}""")
        assertThat(row.enabled).isEqualTo(0)
        assertThat(row.transport).isEqualTo("SSE")
        assertThat(row.maxReconnectAttempts).isEqualTo(7)
        assertThat(row.updatedAt).isEqualTo(9_999L)
    }

    @Test
    fun `领域对象往返一致`(): Unit = runBlocking {
        val cfg = McpServerConfig(
            id = "fs",
            label = "本地文件",
            url = "http://localhost:7788/mcp",
            headers = mapOf("X-Trace" to "on", "Z" to "1", "A" to "2"),
            enabled = true,
            transport = TransportKind.SSE,
            callTimeoutMs = 1_234L,
            connectTimeoutMs = 5_678L,
            maxReconnectAttempts = 5,
        )

        assertThat(store.toDomain(store.toEntity(cfg, 7L))).isEqualTo(cfg)
    }

    private fun mcpEntity(
        id: String,
        label: String = id,
        url: String = "http://127.0.0.1:1/mcp",
        headersJson: String = "{}",
        enabled: Int = 1,
        transport: String = "STREAMABLE_HTTP",
        updatedAt: Long = 0L,
    ): McpServerEntity = McpServerEntity(
        id = id,
        label = label,
        url = url,
        headersJson = headersJson,
        enabled = enabled,
        transport = transport,
        callTimeoutMs = 8_000L,
        connectTimeoutMs = 6_000L,
        maxReconnectAttempts = 3,
        updatedAt = updatedAt,
    )
}
