package com.ake.data.persist.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.ake.core.common.AkeLog
import com.ake.core.ports.MemoryTier

/**
 * PRAGMA 装饰器（数据库 §3.6）。
 *
 * ## 为什么不能靠 `RoomDatabase.Callback.onCreate()` 设 `page_size`
 *
 * `page_size` 与 `auto_vacuum` **只在首次写入数据库文件之前**生效，
 * 而 Room 建表与 callback 的先后顺序不由我们保证。
 * **抢占式地在「连接打开后、Room 做任何事之前」执行 PRAGMA，是唯一可靠的时序。**
 *
 * `SQLiteDriver` 是普通 Kotlin 接口（`open(filename): SQLiteConnection`），所以可以装饰。
 */
class AkePragmaDriver(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
    private val config: AkePragmaConfig,
) : SQLiteDriver {

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        connection.applyPragmas()
        return connection
    }

    private fun SQLiteConnection.applyPragmas() = execSQLAll(
        "PRAGMA journal_mode = WAL",
        // WAL 下不损坏库，只丢掉电前最后几个事务。轨迹数据不是账务，这笔交易极其划算。
        "PRAGMA synchronous = NORMAL",
        // ★ SQLite 默认关闭外键！不打开则 §2.3 的 CASCADE 全是纸上谈兵。
        "PRAGMA foreign_keys = ON",
        "PRAGMA busy_timeout = 3000",
        // 新建库生效；已存在则静默忽略（无害、幂等）
        "PRAGMA page_size = ${config.pageSize}",
        // 负号 = KiB 单位
        "PRAGMA cache_size = ${config.cacheSize}",
        "PRAGMA temp_store = MEMORY",
        // 默认值，显式写出便于审计
        "PRAGMA wal_autocheckpoint = 1000",
    )

    private fun SQLiteConnection.execSQLAll(vararg sql: String) {
        for (s in sql) {
            runCatching { execSQL(s) }
                .onFailure { AkeLog.w(TAG, "PRAGMA failed (ignored): $s — ${it.message}") }
        }
    }

    private companion object {
        const val TAG: String = "AkePragmaDriver"
    }
}

/**
 * 按 MemoryTier 分档（数据库 §3.6）。
 *
 * **内存是本项目头号风险（架构 R1）**，page cache 不能拍脑袋给：
 * 进程里同时住着 2.58 GB 模型 + ~300 MB WebView + KV cache，
 * 为了读几 MB 轨迹把 RSS 抬上去是错误取舍。
 *
 * **同理 `mmap_size` 刻意不设大值** —— SQLite 的 mmap 会把页映射进常驻集，
 * 在这台机器上是为了读轨迹而增大 RSS，得不偿失。
 */
data class AkePragmaConfig(
    /** §5.2 论证：让 12 KB 级 PocketDom 压缩后落在单页内联线内。 */
    val pageSize: Int = 8192,
    /** 负值 = KiB */
    val cacheSize: Int = -2000,
) {
    companion object {
        fun forTier(tier: MemoryTier): AkePragmaConfig = when (tier) {
            MemoryTier.LOW -> AkePragmaConfig(pageSize = 4096, cacheSize = -1000)   // ≈ 4 MB
            MemoryTier.MID -> AkePragmaConfig(pageSize = 8192, cacheSize = -2000)   // ≈ 16 MB
            MemoryTier.HIGH -> AkePragmaConfig(pageSize = 8192, cacheSize = -4000)  // ≈ 32 MB
        }
    }
}
